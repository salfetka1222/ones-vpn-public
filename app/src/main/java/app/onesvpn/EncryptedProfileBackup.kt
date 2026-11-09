package app.onesvpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, password-encrypted profile-only backup. Never export raw VLESS URLs.
 *
 * Format: eight-byte magic + 16-byte random salt + 12-byte random nonce +
 * AES-256-GCM ciphertext and 128-bit authentication tag.
 * PBKDF2-HMAC-SHA256 (310,000 iterations) derives the key from the user's password.
 * The magic is authenticated as AAD; there are no unencrypted configuration fields.
 */
internal object EncryptedProfileBackup {
    private val magic = "ONESBK01".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 8 + SALT_BYTES + IV_BYTES
    private const val KDF_ITERATIONS = 310_000
    private const val MAX_FILE_BYTES = 24 * 1024 * 1024
    private const val MAX_PROFILES = 2_500
    private val random = SecureRandom()

    fun validPassword(password: CharArray): Boolean =
        password.size in 12..128 && password.none { it == '\u0000' }

    fun encrypt(profiles: List<VpnProfile>, password: CharArray): ByteArray {
        require(validPassword(password)) { "Пароль должен содержать от 12 до 128 символов" }
        require(profiles.isNotEmpty() && profiles.size <= MAX_PROFILES) { "Недопустимое число серверов" }
        val list = JSONArray()
        profiles.forEach { profile ->
            validateProfile(profile)
            list.put(JSONObject().apply {
                put("name", profile.name)
                put("uri", profile.uri)
                put("source", profile.source)
            })
        }
        val payload = JSONObject().put("version", 1).put("profiles", list)
            .toString().toByteArray(StandardCharsets.UTF_8)
        try {
            require(payload.size + HEADER_BYTES + TAG_BYTES <= MAX_FILE_BYTES) {
                "Резервная копия слишком большая"
            }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val key = deriveKey(password, salt)
            val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(magic)
                doFinal(payload)
            }
            return magic + salt + iv + sealed
        } finally {
            Arrays.fill(payload, 0.toByte())
        }
    }

    fun decrypt(file: ByteArray, password: CharArray): List<VpnProfile> {
        require(validPassword(password)) { "Неверный пароль или формат копии" }
        require(file.size in (HEADER_BYTES + TAG_BYTES + 1)..MAX_FILE_BYTES &&
            file.copyOfRange(0, magic.size).contentEquals(magic)) {
            "Неверный формат резервной копии"
        }
        val salt = file.copyOfRange(magic.size, magic.size + SALT_BYTES)
        val iv = file.copyOfRange(magic.size + SALT_BYTES, HEADER_BYTES)
        val ciphertext = file.copyOfRange(HEADER_BYTES, file.size)
        val key = deriveKey(password, salt)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(magic)
                doFinal(ciphertext)
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Неверный пароль или повреждённая резервная копия")
        }
        try {
            val json = JSONObject(String(plain, StandardCharsets.UTF_8))
            require(json.getInt("version") == 1) { "Неподдерживаемая версия копии" }
            val items = json.getJSONArray("profiles")
            require(items.length() in 1..MAX_PROFILES) { "Недопустимое число серверов" }
            val profiles = ArrayList<VpnProfile>(items.length())
            repeat(items.length()) { index ->
                val entry = items.getJSONObject(index)
                val uri = entry.getString("uri")
                val parsed = parseProfile(uri)
                val name = entry.getString("name")
                val source = entry.getString("source")
                val restored = parsed.copy(name = name, source = source)
                validateProfile(restored)
                // Explicitly reject transports the current engine cannot start.
                SingBoxConfig.outbound(restored)
                profiles.add(restored)
            }
            require(profiles.map { it.uri }.distinct().size == profiles.size) {
                "Копия содержит дублирующиеся серверы"
            }
            return profiles
        } finally {
            Arrays.fill(plain, 0.toByte())
            Arrays.fill(ciphertext, 0.toByte())
        }
    }

    fun readBounded(source: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val size = source.read(chunk)
            if (size < 0) break
            require(out.size() + size <= MAX_FILE_BYTES) { "Файл резервной копии слишком большой" }
            out.write(chunk, 0, size)
        }
        return out.toByteArray()
    }

    /** Existing profiles win, including IDs/names/subscription sources. Never remove any. */
    fun merge(existing: List<VpnProfile>, imported: List<VpnProfile>): List<VpnProfile> {
        val known = existing.mapTo(HashSet()) { it.uri }
        return existing + imported.filter { known.add(it.uri) }
    }

    private fun validateProfile(profile: VpnProfile) {
        require(profile.name.length in 1..256 && profile.uri.length in 15..8192 &&
            profile.port in 1..65535) { "Недопустимые данные сервера" }
        require(profile.source.length <= 8192) { "Недопустимый адрес подписки" }
        if (profile.source.isNotBlank()) {
            val source = URI(profile.source)
            require(source.scheme.equals("https", true) && !source.host.isNullOrBlank() &&
                source.userInfo == null && source.port == -1) {
                "Недопустимый адрес подписки"
            }
        }
        val parsed = parseProfile(profile.uri)
        require(parsed.host == profile.host && parsed.port == profile.port) {
            "Адрес сервера не соответствует ссылке"
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, 256)
        val raw = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(raw, "AES")
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }
}

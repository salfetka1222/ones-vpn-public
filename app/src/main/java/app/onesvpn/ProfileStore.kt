package app.onesvpn

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class VpnProfile(val id: String, val name: String, val host: String, val port: Int, val uri: String, val source: String = "") {
    override fun toString(): String = "VpnProfile(redacted)"
}

fun parseProfile(raw: String): VpnProfile {
    val value = raw.trim()
    require(value.length in 15..8192 && value.startsWith("vless://", ignoreCase = true)) { "Пока поддерживаются только ссылки VLESS" }
    val parsed = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Неверный формат ссылки") }
    val userInfo = parsed.rawUserInfo ?: throw IllegalArgumentException("Отсутствует UUID")
    require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(userInfo)) { "Неверный UUID" }
    val host = parsed.host ?: throw IllegalArgumentException("Не указан адрес сервера")
    val port = parsed.port
    require(port in 1..65535) { "Неверный порт сервера" }
    val label = parsed.rawFragment?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }?.take(64)?.trim().orEmpty()
    return VpnProfile(java.util.UUID.randomUUID().toString(), label.ifBlank { host }, host, port, value)
}

class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("profiles_secure_v1", Context.MODE_PRIVATE)
    private fun key(): SecretKey = ProfileKeyAccess.obtain(prefs.contains("data"), find = {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey("ones_profiles", null) as? SecretKey
    }, create = {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("ones_profiles", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generator.generateKey()
    })
    fun load(): List<VpnProfile> {
        val encoded = prefs.getString("data", null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
            (0 until json.length()).map { i ->
                val p = json.getJSONObject(i)
                VpnProfile(p.getString("id"), p.getString("name"), p.getString("host"), p.getInt("port"), p.getString("uri"), p.optString("source", ""))
            }
        } catch (_: Exception) {
            // Never turn unreadable storage into an empty list that the next import overwrites.
            throw IllegalStateException("Не удалось прочитать профили. Данные сохранены; не переустанавливайте приложение.")
        }
    }
    fun save(profiles: List<VpnProfile>) {
        if (prefs.contains("data")) load() // Fail closed if the existing encrypted data cannot be read.
        val array = JSONArray()
        profiles.forEach { p -> array.put(JSONObject().put("id", p.id).put("name", p.name).put("host", p.host).put("port", p.port).put("uri", p.uri).put("source", p.source)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Не удалось сохранить профили" }
    }
}

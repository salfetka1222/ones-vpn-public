package app.onesvpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/** Import only VLESS entries; never log subscription URLs; callers may persist them only in encrypted ProfileStore. */
object SubscriptionImport {
    fun load(source: String): List<VpnProfile> {
        val uri = URI(source.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
            "Для подписки требуется ссылка HTTPS"
        }
        require(uri.userInfo == null && uri.port == -1) { "Недопустимый адрес подписки" }
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "text/plain, application/json")
        try {
            require(connection.responseCode == 200) { "Сервер подписки вернул HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(buffer.size() + count <= 1024 * 1024) { "Подписка слишком большая" }
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            val body = String(bytes, StandardCharsets.UTF_8).trim()
            val plain = if (body.startsWith("vless://", true) || body.contains("\nvless://")) body
                else try {
                    String(Base64.decode(body.filterNot { it.isWhitespace() }, Base64.DEFAULT), StandardCharsets.UTF_8)
                } catch (_: Exception) { body }
            val lines = plain.lines().map { it.trim() }.filter { it.startsWith("vless://", true) }.take(200)
            val profiles = lines.mapNotNull { line ->
                runCatching {
                    parseProfile(line).also { SingBoxConfig.outbound(it) }
                }.getOrNull()
            }.distinctBy { it.uri }
            require(profiles.isNotEmpty()) {
                "Подписка не содержит поддерживаемых VLESS-серверов. Возможно, это страница авторизации или другой формат."
            }
            return profiles
        } finally {
            connection.disconnect()
        }
    }
}

package app.onesvpn

import android.content.Context
import android.provider.Settings

/** Read-only device setting. It is not proof of the DNS path used by the VPN or browser. */
internal enum class PrivateDnsMode { OFF, AUTOMATIC, PROVIDER, UNKNOWN }

internal object PrivateDnsPolicy {
    fun classify(mode: String?, defaultMode: String?): PrivateDnsMode {
        val value = listOf(mode, defaultMode).firstOrNull {
            !it.isNullOrBlank() && !it.equals("null", ignoreCase = true)
        }?.trim()?.lowercase()
        return when (value) {
            "off" -> PrivateDnsMode.OFF
            "opportunistic" -> PrivateDnsMode.AUTOMATIC
            "hostname" -> PrivateDnsMode.PROVIDER
            else -> PrivateDnsMode.UNKNOWN
        }
    }

    fun explanation(mode: PrivateDnsMode): String {
        val status = when (mode) {
            PrivateDnsMode.OFF -> "Системный Private DNS выключен."
            PrivateDnsMode.AUTOMATIC -> "Системный Private DNS работает в автоматическом режиме."
            PrivateDnsMode.PROVIDER -> "Системный Private DNS настроен на выбранного провайдера."
            PrivateDnsMode.UNKNOWN -> "Не удалось определить режим системного Private DNS."
        }
        return status + " Это настройка Android, не проверка реального маршрута DNS. " +
            "Браузер может отдельно использовать защищённый DNS. " +
            "Сравнивайте результаты независимого DNS-теста с ожидаемым провайдером VPN."
    }
}

internal object AndroidPrivateDnsReader {
    fun read(context: Context): PrivateDnsMode = try {
        val resolver = context.contentResolver
        // Raw keys are intentionally used for SDK compatibility (Android 10+).
        val mode = Settings.Global.getString(resolver, "private_dns_mode")
        val fallback = Settings.Global.getString(resolver, "private_dns_default_mode")
        PrivateDnsPolicy.classify(mode, fallback)
    } catch (_: Exception) {
        PrivateDnsMode.UNKNOWN
    }
}

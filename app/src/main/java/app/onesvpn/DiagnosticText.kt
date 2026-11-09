package app.onesvpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only recognized allowlisted event codes can be displayed. */
internal object DiagnosticText {
    fun describe(line: String): String {
        val epochMs = line.substringBefore(' ').toLongOrNull() ?: return "Неизвестное событие"
        val code = line.substringAfter(' ', "")
        val event = DiagnosticEvent.entries.firstOrNull { it.name == code }
            ?: return "Неизвестное событие"
        val description = when (event) {
            DiagnosticEvent.PHYSICAL_NETWORK_WAIT -> "Нет физической сети · ждём"
            DiagnosticEvent.NETWORK_HANDOVER_WAIT -> "Смена сети · стабилизация"
            DiagnosticEvent.STALE_NETWORK_PROBE -> "Старая проверка отброшена"
            DiagnosticEvent.HEALTH_PROBE_RECHECK -> "Проверка VPN повторяется"
            DiagnosticEvent.HEALTH_PROBE_TUN_HELD -> "Связь через VPN не подтверждена · TUN сохранён"
            DiagnosticEvent.VPN_RECOVERED -> "Связь через VPN восстановлена"
            DiagnosticEvent.UNDERLYING_NETWORK_READY -> "Физическая сеть доступна"
            DiagnosticEvent.UNDERLYING_NETWORK_LOST -> "Физическая сеть потеряна"
            DiagnosticEvent.PROBE_STARTED -> "Проверка связи через VPN"
            DiagnosticEvent.PROBE_OK -> "Проверка VPN успешна"
            DiagnosticEvent.CONNECT_REQUESTED -> "Запрос на подключение"
            DiagnosticEvent.CONNECTION_TIMEOUT -> "Превышено время ожидания"
            DiagnosticEvent.DNS_ERROR -> "Ошибка DNS"
            DiagnosticEvent.TLS_ERROR -> "Ошибка TLS"
            DiagnosticEvent.CONFIG_INVALID -> "Ошибка конфигурации"
            DiagnosticEvent.STOP_REQUESTED -> "Отключение VPN"
            DiagnosticEvent.SERVICE_STOPPED -> "VPN остановлен"
            else -> event.name.replace('_', ' ')
        }
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
        return "$clock · $description"
    }
}

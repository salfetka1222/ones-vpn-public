package app.onesvpn

import java.util.Locale

/** IEC byte units; rates are derived only from libbox values reported in bytes/sec. */
internal object TrafficFormat {
    private val units = arrayOf("Б", "КиБ", "МиБ", "ГиБ", "ТиБ", "ПиБ", "ЭиБ")

    fun bytes(value: Long): String {
        val safeValue = value.coerceAtLeast(0L)
        if (safeValue < 1024L) return "$safeValue Б"
        var scaled = safeValue.toDouble()
        var index = 0
        while (scaled >= 1024.0 && index < units.lastIndex) {
            scaled /= 1024.0
            index++
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[index])
    }

    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/с"
}

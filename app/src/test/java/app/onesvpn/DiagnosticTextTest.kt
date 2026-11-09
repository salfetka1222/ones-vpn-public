package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun usefulSafeRussianDescriptions() {
        val message = DiagnosticText.describe("1731000000000 PHYSICAL_NETWORK_WAIT")
        assertTrue(message.contains("Нет физической сети"))
        assertTrue(DiagnosticText.describe("1731000000000 VPN_RECOVERED")
            .contains("восстановлена"))
        assertTrue(DiagnosticText.describe("1731000000000 STALE_NETWORK_PROBE")
            .contains("отброшена"))
    }

    @Test fun rejectUnknownAndUntrustedLogText() {
        val secret = "vless://00000000-0000-4000-8000-000000000001@secret.example"
        val malformed = listOf("1731000000000 $secret", "bogus CONNECT_REQUESTED", secret)
        for (value in malformed) {
            val shown = DiagnosticText.describe(value)
            assertEquals("Неизвестное событие", shown)
            assertFalse(shown.contains("secret.example"))
        }
    }
}

package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun nativeErrorsExposeOnlyCategories() {
        val secret = "vless://00000000-0000-4000-8000-000000000001@vpn.example.org?pbk=SECRET"
        assertEquals(DiagnosticEvent.DNS_ERROR, Diagnostics.nativeEvent("dns resolve failed $secret"))
        assertEquals(DiagnosticEvent.TLS_ERROR, Diagnostics.nativeEvent("TLS handshake failed $secret"))
        assertEquals(DiagnosticEvent.CONNECTION_TIMEOUT, Diagnostics.nativeEvent("timeout $secret"))
        assertEquals(DiagnosticEvent.NATIVE_ERROR, Diagnostics.nativeEvent(secret))
    }
}

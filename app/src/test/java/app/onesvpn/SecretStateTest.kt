package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class SecretStateTest {
    @Test fun profileDebugStringDoesNotContainCredentials() {
        val profile = VpnProfile("id", "secret-label", "private.example", 443,
            "vless://credential@private.example:443", "https://provider.example/token-SECRET")
        val text = profile.toString()
        listOf("credential", "token-SECRET", "private.example", "secret-label").forEach {
            assertFalse("Profile debug representation must be redacted", text.contains(it))
        }
    }
}

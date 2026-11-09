package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class SplitTunnelPolicyTest {
    @Test fun validUserSelectedAppsAreAccepted() {
        val requested = setOf("com.example.browser", "org.example.app")
        assertEquals(requested, SplitTunnelPolicy.validate(requested, "app.onesvpn"))
    }

    @Test fun vpnAppCannotExcludeItself() {
        assertThrows(IllegalArgumentException::class.java) {
            SplitTunnelPolicy.validate(setOf("app.onesvpn"), "app.onesvpn")
        }
    }

    @Test fun invalidNamesAreRejectedOnSave() {
        listOf("example", "com.example.*", "../foo", "android:uid").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                SplitTunnelPolicy.validate(setOf(it), "app.onesvpn")
            }
        }
    }

    @Test fun excludesAreLimited() {
        val names = (0..SplitTunnelPolicy.MAX_EXCLUDED_APPS).map { "com.example.app$it" }.toSet()
        assertThrows(IllegalArgumentException::class.java) {
            SplitTunnelPolicy.validate(names, "app.onesvpn")
        }
    }

    @Test fun storedEntriesAreFilteredIfInvalid() {
        val cleaned = SplitTunnelPolicy.validForInstalled(
            setOf("app.onesvpn", "com.example.valid", "../invalid"), "app.onesvpn"
        )
        assertEquals(setOf("com.example.valid"), cleaned)
    }
}

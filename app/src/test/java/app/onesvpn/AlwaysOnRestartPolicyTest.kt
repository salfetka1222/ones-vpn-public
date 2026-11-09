package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class AlwaysOnRestartPolicyTest {
    private val id = "sample-profile-id"

    @Test fun distinguishAndroidStartFromAppAndDiagnostics() {
        assertTrue(AlwaysOnRestartPolicy.isSystemStart(null))
        assertTrue(AlwaysOnRestartPolicy.isSystemStart("android.net.VpnService"))
        assertFalse(AlwaysOnRestartPolicy.isSystemStart(OnesVpnService.ACTION_CONNECT))
        assertFalse(AlwaysOnRestartPolicy.isSystemStart(OnesVpnService.ACTION_DISCONNECT))
        assertFalse(AlwaysOnRestartPolicy.isSystemStart("unknown.action"))
    }

    @Test fun onlySystemStartWithAlwaysOnAndConsentRestores() {
        assertEquals(id, AlwaysOnRestartPolicy.profileToRestore(true, true, true, id))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(false, true, true, id))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, false, true, id))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, true, false, id))
    }

    @Test fun noStoredChoiceNeverConnectsUnexpectedly() {
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, true, true, null))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, true, true, ""))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, true, true, " ".repeat(5)))
        assertNull(AlwaysOnRestartPolicy.profileToRestore(true, true, true, "x".repeat(129)))
    }
}

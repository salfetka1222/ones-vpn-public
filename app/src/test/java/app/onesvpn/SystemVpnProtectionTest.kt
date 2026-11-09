package app.onesvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemVpnProtectionTest {
    @Test fun lockdownIsOnlyFullSystemLevel() {
        assertEquals(SystemProtectionLevel.LOCKDOWN,
            SystemVpnProtectionPolicy.classify(alwaysOn = true, lockdown = true))
        assertEquals(SystemProtectionLevel.UNKNOWN,
            SystemVpnProtectionPolicy.classify(alwaysOn = false, lockdown = true))
    }

    @Test fun alwaysOnAloneDoesNotImplyTrafficBlocking() {
        assertEquals(SystemProtectionLevel.ALWAYS_ON,
            SystemVpnProtectionPolicy.classify(alwaysOn = true, lockdown = false))
        assertEquals(SystemProtectionLevel.VPN_ONLY,
            SystemVpnProtectionPolicy.classify(alwaysOn = false, lockdown = false))
    }

    @Test fun unknownIsNotMistakenForDisabled() {
        assertEquals(SystemProtectionLevel.UNKNOWN,
            SystemVpnProtectionPolicy.classify(null, null))
        assertEquals(SystemProtectionLevel.UNKNOWN,
            SystemVpnProtectionPolicy.classify(true, null))
        assertEquals(SystemProtectionLevel.UNKNOWN,
            SystemVpnProtectionPolicy.classify(null, false))
    }

    @Test fun resetsWhenServiceBecomesUnavailable() {
        SystemVpnProtection.update(true, true)
        assertEquals(SystemProtectionLevel.LOCKDOWN, SystemVpnProtection.state.value.level)
        SystemVpnProtection.clear()
        assertEquals(SystemProtectionLevel.UNKNOWN, SystemVpnProtection.state.value.level)
        assertEquals(0L, SystemVpnProtection.state.value.manualCheckSequence)
    }

    @Test fun manuallyRequestedCheckIsVisibleEvenIfProtectionLevelIsUnchanged() {
        SystemVpnProtection.clear()
        SystemVpnProtection.update(false, false)
        assertEquals(0L, SystemVpnProtection.state.value.manualCheckSequence)
        SystemVpnProtection.update(false, false, manualCheck = true)
        assertEquals(SystemProtectionLevel.VPN_ONLY, SystemVpnProtection.state.value.level)
        assertEquals(1L, SystemVpnProtection.state.value.manualCheckSequence)
        // Activity resume and other read-only refreshes are not manual checks.
        SystemVpnProtection.update(false, false)
        assertEquals(1L, SystemVpnProtection.state.value.manualCheckSequence)
        SystemVpnProtection.update(false, false, manualCheck = true)
        assertEquals(2L, SystemVpnProtection.state.value.manualCheckSequence)
        SystemVpnProtection.clear()
    }

    @Test fun deniedManualQueryReportsUnknownAndStillCompletes() {
        SystemVpnProtection.clear()
        SystemVpnProtection.update(true, true)
        SystemVpnProtection.clear(manualCheck = true)
        assertEquals(SystemProtectionLevel.UNKNOWN, SystemVpnProtection.state.value.level)
        assertEquals(1L, SystemVpnProtection.state.value.manualCheckSequence)
        SystemVpnProtection.clear()
    }
}

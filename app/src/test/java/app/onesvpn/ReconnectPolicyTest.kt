package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun boundedBackoff() {
        assertEquals(1000L, ReconnectPolicy.delayMs(0, true, true))
        assertEquals(32000L, ReconnectPolicy.delayMs(5, true, true))
        assertNull(ReconnectPolicy.delayMs(6, true, true))
        assertNull(ReconnectPolicy.delayMs(-1, true, true))
    }

    @Test fun alwaysOnRetriesRemainCappedAfterProlongedOutage() {
        assertEquals(60000L, ReconnectPolicy.delayMs(6, true, true, alwaysOn = true))
        assertEquals(60000L, ReconnectPolicy.delayMs(50, true, true, alwaysOn = true))
        assertEquals(60000L, ReconnectPolicy.delayMs(Int.MAX_VALUE, true, true, alwaysOn = true))
        assertNull(ReconnectPolicy.delayMs(-1, true, true, alwaysOn = true))
    }

    @Test fun alwaysOnCannotOverrideManualDisconnectOrRevocation() {
        assertNull(ReconnectPolicy.delayMs(50, false, true, alwaysOn = true))
        assertNull(ReconnectPolicy.delayMs(50, true, false, alwaysOn = true))
    }

    @Test fun manualDisconnectOrRevokedConsentStopsRetry() {
        assertNull(ReconnectPolicy.delayMs(0, false, true))
        assertNull(ReconnectPolicy.delayMs(0, true, false))
        assertNull(ReconnectPolicy.delayMs(3, false, false))
    }
}

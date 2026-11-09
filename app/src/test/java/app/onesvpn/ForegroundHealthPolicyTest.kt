package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ForegroundHealthPolicyTest {
    @Test fun recentVpnProofAvoidsExtraNetworkRequest() {
        assertFalse(ForegroundHealthPolicy.shouldCheck(31_000, 28_000, null))
    }
    @Test fun oldOrMissingProofTriggersRevalidation() {
        assertTrue(ForegroundHealthPolicy.shouldCheck(31_000, 5_000, null))
        assertTrue(ForegroundHealthPolicy.shouldCheck(31_000, null, null))
        assertTrue(ForegroundHealthPolicy.shouldCheck(31_000, 40_000, null))
    }
    @Test fun quickRepeatedResumeIsDebounced() {
        assertTrue(ForegroundHealthPolicy.shouldCheck(31_000, 100, null))
        assertFalse(ForegroundHealthPolicy.shouldCheck(32_000, 100, 31_000))
        assertFalse(ForegroundHealthPolicy.shouldCheck(40_999, 100, 31_000))
        assertTrue(ForegroundHealthPolicy.shouldCheck(41_000, 100, 31_000))
    }
    @Test fun invalidClockFailsClosed() {
        assertFalse(ForegroundHealthPolicy.shouldCheck(-1, null, null))
    }
}

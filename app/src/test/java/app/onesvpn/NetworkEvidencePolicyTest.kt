package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class NetworkEvidencePolicyTest {
    private fun valid(
        beforeOnline: Boolean = true,
        afterOnline: Boolean = true,
        beforeRevision: Long = 7,
        afterRevision: Long = 7,
        verified: Boolean = true
    ) = NetworkEvidencePolicy.isCurrent(
        beforeOnline, afterOnline, beforeRevision, afterRevision, verified
    )

    @Test fun acceptsOnlyStillVerifiedNetworkRevision() {
        assertTrue(valid())
    }

    @Test fun invalidatesWhenWiFiOrMobileChanged() {
        assertFalse(valid(afterRevision = 8))
        assertFalse(valid(afterRevision = 9))
    }

    @Test fun invalidatesWhenNetworkWasOrIsMissing() {
        assertFalse(valid(beforeOnline = false))
        assertFalse(valid(afterOnline = false))
        assertFalse(valid(beforeOnline = false, afterOnline = false))
    }

    @Test fun invalidatesOnSessionStopOrRecovery() {
        assertFalse(valid(verified = false))
    }
}

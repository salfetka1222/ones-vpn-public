package app.onesvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRecoveryPolicyTest {
    private fun assess(
        physical: Boolean = true,
        ageMs: Long = 60_000,
        connected: Boolean = true,
        changed: Boolean = false
    ) = NetworkRecoveryPolicy.assess(physical, ageMs, connected, changed)

    @Test fun physicalOutageWaitsRatherThanConsumingReconnectAttempts() {
        assertEquals(NetworkRecoveryPolicy.Decision.WAIT_FOR_NETWORK,
            assess(physical = false))
        assertEquals(NetworkRecoveryPolicy.Decision.WAIT_FOR_NETWORK,
            assess(physical = false, connected = false, changed = true))
    }

    @Test fun recentHandoverGetsGraceOnlyForEstablishedSession() {
        assertEquals(NetworkRecoveryPolicy.Decision.WAIT_FOR_HANDOVER,
            assess(ageMs = 0))
        assertEquals(NetworkRecoveryPolicy.Decision.WAIT_FOR_HANDOVER,
            assess(ageMs = 11_999))
        assertEquals(NetworkRecoveryPolicy.Decision.PROBE,
            assess(ageMs = 12_000))
        assertEquals(NetworkRecoveryPolicy.Decision.PROBE,
            assess(ageMs = 0, connected = false))
    }

    @Test fun probeRacingWithNetworkSwitchIsDiscarded() {
        assertEquals(NetworkRecoveryPolicy.Decision.WAIT_FOR_HANDOVER,
            assess(connected = false, changed = true))
        assertEquals(NetworkRecoveryPolicy.Decision.PROBE, assess(changed = false))
    }
}

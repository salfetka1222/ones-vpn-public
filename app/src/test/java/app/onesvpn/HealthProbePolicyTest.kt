package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class HealthProbePolicyTest {
    private fun fail(count: Int) = HealthProbePolicy.evaluate(
        probeSucceeded = false, consecutiveFailures = count
    )

    @Test fun probeFailureNeverReleasesAnEstablishedTunnel() {
        val first = fail(0)
        assertEquals(HealthProbePolicy.Action.RECHECK, first.action)
        assertEquals(1, first.consecutiveFailures)
        val second = fail(first.consecutiveFailures)
        assertEquals(HealthProbePolicy.Action.RECHECK, second.action)
        assertEquals(2, second.consecutiveFailures)
        val third = fail(second.consecutiveFailures)
        assertEquals(HealthProbePolicy.Action.HOLD_TUN, third.action)
        assertEquals(3, third.consecutiveFailures)
        for (count in 3..30) {
            assertEquals(HealthProbePolicy.Action.HOLD_TUN, fail(count).action)
            assertEquals(3, fail(count).consecutiveFailures)
        }
    }

    @Test fun unverifiedStartupTUNAlsoRemainsHeldIfProbesFail() {
        // Applies to the first probe after the native engine starts, too:
        // an HTTP timeout is not permission to disable Android VPN routes.
        assertEquals(HealthProbePolicy.Action.RECHECK, fail(0).action)
        assertEquals(HealthProbePolicy.Action.HOLD_TUN, fail(3).action)
    }

    @Test fun successfulProbeClearsAnyFailedProbeStreak() {
        val recovered = HealthProbePolicy.evaluate(
            probeSucceeded = true, consecutiveFailures = 3
        )
        assertEquals(HealthProbePolicy.Action.HEALTHY, recovered.action)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(HealthProbePolicy.Action.RECHECK,
            fail(recovered.consecutiveFailures).action)
    }

    @Test fun counterIsBoundedAndInvalidInputRejected() {
        assertThrows(IllegalArgumentException::class.java) { fail(-1) }
        assertEquals(3, fail(Int.MAX_VALUE).consecutiveFailures)
        assertEquals(HealthProbePolicy.Action.HOLD_TUN, fail(Int.MAX_VALUE).action)
    }
}

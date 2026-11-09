package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression for Codex PR #4 P1: repeated HTTPS probe failures must not
 * drop a previously established VPN route just to reconnect.
 */
class HealthProbeRecoveryTest {
    @Test fun prolongedUnreachableHealthServersKeepEstablishedSessionAlive() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.connected(generation))
        val startedAt = lifecycle.state.value.connectedAtNanos
        var count = 0

        repeat(25) { index ->
            val decision = HealthProbePolicy.evaluate(
                probeSucceeded = false, consecutiveFailures = count
            )
            count = decision.consecutiveFailures
            when (decision.action) {
                HealthProbePolicy.Action.HEALTHY -> fail("Unreachable probe cannot be healthy")
                HealthProbePolicy.Action.RECHECK ->
                    assertTrue(lifecycle.checkingConnection(generation))
                HealthProbePolicy.Action.HOLD_TUN ->
                    assertTrue(lifecycle.tunnelUnverified(generation))
            }
            assertTrue("Tunnel must survive failed probe $index", lifecycle.isCurrent(generation))
            assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
            assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
            assertNull("No false health proof while recovering", lifecycle.verifiedGeneration())
            assertFalse(ConnectionDisplayPolicy.evaluate(lifecycle.state.value, 100L).verified)
        }
        assertEquals(3, count)
        assertEquals(VpnLifecycle.TUN_HELD_UNVERIFIED, lifecycle.state.value.detail)

        val recovery = HealthProbePolicy.evaluate(true, count)
        assertEquals(HealthProbePolicy.Action.HEALTHY, recovery.action)
        assertEquals(0, recovery.consecutiveFailures)
        assertTrue(lifecycle.connected(generation))
        assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
        assertEquals("", lifecycle.state.value.detail)
        assertEquals(generation, lifecycle.verifiedGeneration())
    }

    @Test fun startupProbeFailuresHoldNativeTunnelInsteadOfAutoRetry() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        var consecutive = 0
        repeat(8) {
            val decision = HealthProbePolicy.evaluate(false, consecutive)
            consecutive = decision.consecutiveFailures
            when (decision.action) {
                HealthProbePolicy.Action.HEALTHY -> fail("Failed probe must not be healthy")
                HealthProbePolicy.Action.RECHECK -> Unit
                HealthProbePolicy.Action.HOLD_TUN ->
                    assertTrue(lifecycle.tunnelUnverified(generation))
            }
            assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
            assertTrue(lifecycle.isCurrent(generation))
            assertNull(lifecycle.verifiedGeneration())
        }
        assertTrue(lifecycle.connected(generation))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
    }
}

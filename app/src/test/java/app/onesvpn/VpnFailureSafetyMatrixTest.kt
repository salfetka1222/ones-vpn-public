package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

/**
 * Offline failure-state matrix for the 1.0 Stable release gate.
 *
 * These are JVM policy/lifecycle tests, NOT a real Android process crash,
 * native/JNI failure injection, or a packet-level fail-closed test.
 * The live device VPN is never disturbed by running these tests.
 */
class VpnFailureSafetyMatrixTest {
    private fun ui(lifecycle: VpnLifecycle, nowMs: Long = 1_000L) =
        ConnectionDisplayPolicy.evaluate(lifecycle.state.value, nowMs)

    @Test fun repeatedNativeStopWithTunNeverTurnsFreshPastProbeIntoHealth() {
        for (startupInFlight in listOf(false, true)) {
            val lifecycle = VpnLifecycle { 1_000L }
            val session = lifecycle.start()!!
            assertTrue(lifecycle.connected(session))
            val startedAt = lifecycle.state.value.connectedAtNanos
            assertEquals(session, lifecycle.verifiedGeneration())
            repeat(100) {
                assertEquals(NativeStopPolicy.Action.HOLD_TUN,
                    NativeStopPolicy.decide(tunEstablished = true,
                        nativeStartInFlight = startupInFlight))
                assertTrue(lifecycle.tunnelUnverified(session, nativeStopped = true))
                assertTrue(lifecycle.isCurrent(session))
                assertNull(lifecycle.verifiedGeneration())
                assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
                val display = ui(lifecycle)
                assertEquals("UNVERIFIED", display.label)
                assertFalse(display.verified)
                assertTrue(display.recovering)
            }
        }
    }

    @Test fun nativeStopDuringEstablishCannotPretendTunExists() {
        val lifecycle = VpnLifecycle { 1_000L }
        val session = lifecycle.start()!!
        assertEquals(NativeStopPolicy.Action.DEFER_UNTIL_STARTUP_FINISHES,
            NativeStopPolicy.decide(tunEstablished = false,
                nativeStartInFlight = true))
        assertTrue(lifecycle.waitingForNetwork(session,
            "VPN-движок остановлен во время запуска"))
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertNull(lifecycle.verifiedGeneration())
        assertFalse(ui(lifecycle).verified)
        // If establish later completes, the real TUN must be preserved
        // in the unverified state, not discarded or reported as healthy.
        assertEquals(NativeStopPolicy.Action.HOLD_TUN,
            NativeStopPolicy.decide(tunEstablished = true,
                nativeStartInFlight = false))
        assertTrue(lifecycle.tunnelUnverified(session, nativeStopped = true))
        assertNull(lifecycle.verifiedGeneration())
        assertEquals("UNVERIFIED", ui(lifecycle).label)
    }

    @Test fun nativeStopWithoutTunAfterStartupAllowsCleanupOnly() {
        val lifecycle = VpnLifecycle { 1_000L }
        val session = lifecycle.start()!!
        assertEquals(NativeStopPolicy.Action.CLEANUP_NO_TUN,
            NativeStopPolicy.decide(tunEstablished = false,
                nativeStartInFlight = false))
        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.isCurrent(session))
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.stopped(lifecycle.stopGeneration()!!,
            "ENGINE_STOPPED_DURING_STARTUP"))
        assertEquals(VpnStatus.ERROR, lifecycle.state.value.status)
        assertFalse(ui(lifecycle).verified)
    }

    @Test fun healthServerFailuresNeverAuthorizeDroppingTun() {
        val lifecycle = VpnLifecycle { 1_000L }
        val session = lifecycle.start()!!
        assertTrue(lifecycle.connected(session))
        var failures = 0
        repeat(100) {
            val decision = HealthProbePolicy.evaluate(
                probeSucceeded = false,
                consecutiveFailures = failures
            )
            failures = decision.consecutiveFailures
            assertNotEquals(HealthProbePolicy.Action.HEALTHY, decision.action)
            when (decision.action) {
                HealthProbePolicy.Action.RECHECK ->
                    assertTrue(lifecycle.checkingConnection(session))
                HealthProbePolicy.Action.HOLD_TUN ->
                    assertTrue(lifecycle.tunnelUnverified(session))
                HealthProbePolicy.Action.HEALTHY -> fail("Unreachable HTTPS isn't proof")
            }
            assertTrue(lifecycle.isCurrent(session))
            assertNull(lifecycle.verifiedGeneration())
            assertFalse(ui(lifecycle).verified)
        }
        assertEquals(3, failures)
        assertEquals(VpnLifecycle.TUN_HELD_UNVERIFIED,
            lifecycle.state.value.detail)
    }

    @Test fun manualDisconnectInvalidatesEveryLateGenerationCompletion() {
        val lifecycle = VpnLifecycle { 1_000L }
        val original = lifecycle.start()!!
        assertTrue(lifecycle.connected(original))
        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.isCurrent(original))
        assertFalse(lifecycle.connected(original))
        assertFalse(lifecycle.tunnelUnverified(original, nativeStopped = true))
        val stopping = lifecycle.stopGeneration()!!
        assertTrue(lifecycle.stopped(stopping))
        assertFalse(ui(lifecycle).verified)
        val next = lifecycle.start()!!
        assertTrue(next > original)
        assertFalse(lifecycle.connected(original))
        assertFalse(lifecycle.tunnelUnverified(original))
        assertNull(lifecycle.verifiedGeneration())
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
    }

    @Test fun neitherAlwaysOnAloneNorRetainedTunImpliesSystemLockdown() {
        val lifecycle = VpnLifecycle { 1_000L }
        val session = lifecycle.start()!!
        assertTrue(lifecycle.connected(session))
        assertTrue(lifecycle.tunnelUnverified(session, nativeStopped = true))
        assertEquals(SystemProtectionLevel.VPN_ONLY,
            SystemVpnProtectionPolicy.classify(false, false))
        assertEquals(SystemProtectionLevel.ALWAYS_ON,
            SystemVpnProtectionPolicy.classify(true, false))
        assertEquals(SystemProtectionLevel.UNKNOWN,
            SystemVpnProtectionPolicy.classify(null, null))
        assertEquals(SystemProtectionLevel.LOCKDOWN,
            SystemVpnProtectionPolicy.classify(true, true))
        assertFalse(ui(lifecycle).verified)
    }

    @Test fun sleepingBeyondProofLifetimeCannotAppearVerified() {
        val lifecycle = VpnLifecycle { 1_000L }
        val session = lifecycle.start()!!
        assertTrue(lifecycle.connected(session))
        val late = 1_000L + ConnectionDisplayPolicy.MAX_PROOF_AGE_MS + 1L
        assertFalse(ui(lifecycle, late).verified)
        assertEquals("CHECKING", ui(lifecycle, late).label)
        assertTrue(lifecycle.tunnelUnverified(session, nativeStopped = true))
        assertFalse(ui(lifecycle, late).verified)
        assertEquals("UNVERIFIED", ui(lifecycle, late).label)
    }
}

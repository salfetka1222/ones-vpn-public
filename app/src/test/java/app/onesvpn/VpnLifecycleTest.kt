package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class VpnLifecycleTest {
    @Test fun connectIsPendingUntilProbeSucceeds() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()
        assertNotNull(generation)
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertTrue(lifecycle.connected(generation!!))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertNotNull(lifecycle.state.value.connectedAtNanos)
    }
    @Test fun repeatedSuccessfulHealthProbeDoesNotResetSessionTimer() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.connected(generation))
        val first = lifecycle.state.value.connectedAtNanos
        assertNotNull(first)
        assertTrue(lifecycle.connected(generation))
        assertEquals(first, lifecycle.state.value.connectedAtNanos)
        assertTrue(lifecycle.stop())
        assertNull(lifecycle.state.value.connectedAtNanos)
    }

    @Test fun evidenceNotAllowedUntilProbeOrDuringRecovery() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.connected(generation))
        assertEquals(generation, lifecycle.verifiedGeneration())
        assertTrue(lifecycle.waitingForNetwork(generation, "Ожидаем сеть"))
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.connected(generation))
        assertEquals(generation, lifecycle.verifiedGeneration())
        assertTrue(lifecycle.checkingConnection(generation))
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.stop())
        assertNull(lifecycle.verifiedGeneration())
    }

    @Test fun verificationEvidenceRefreshesWhileConnectionStopwatchPersists() {
        var now = 100_000L
        val lifecycle = VpnLifecycle { now }
        val generation = lifecycle.start()!!
        assertNull(lifecycle.state.value.lastVerifiedElapsedMs)
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.connected(generation))
        val stopwatch = lifecycle.state.value.connectedAtNanos
        assertEquals(100_000L, lifecycle.state.value.lastVerifiedElapsedMs)
        now += 30_000L
        assertTrue(lifecycle.connected(generation))
        assertEquals(130_000L, lifecycle.state.value.lastVerifiedElapsedMs)
        assertEquals(stopwatch, lifecycle.state.value.connectedAtNanos)
        assertTrue(lifecycle.waitingForNetwork(generation, "Ожидаем сеть"))
        assertNull(lifecycle.verifiedGeneration())
        assertEquals(130_000L, lifecycle.state.value.lastVerifiedElapsedMs)
        assertTrue(lifecycle.connected(generation))
        assertEquals(130_000L, lifecycle.state.value.lastVerifiedElapsedMs)
        assertNotNull(lifecycle.verifiedGeneration())
        lifecycle.stop()
        assertNull(lifecycle.verifiedGeneration())
        assertNull(lifecycle.state.value.lastVerifiedElapsedMs)
    }

    @Test fun slowNativeStartupIsInformationalWithoutCancellingGeneration() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        // This mirrors a 25-second startup warning while builder.establish()
        // may still be executing in libbox.
        assertTrue(lifecycle.startupDelayed(generation))
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertEquals(VpnLifecycle.STARTUP_DELAYED, lifecycle.state.value.detail)
        assertTrue(lifecycle.isCurrent(generation))
        assertEquals(generation, lifecycle.activeGeneration())
        assertNull(lifecycle.verifiedGeneration())
        // Native startup may finish later without reopening a new session.
        assertTrue(lifecycle.connected(generation))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
    }

    @Test fun cancellationRejectsLateNativeTunCreationAfterStartupWarning() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.startupDelayed(generation))
        assertTrue(lifecycle.stop()) // explicit user disconnect, not a timeout
        // AndroidPlatform's establish gate checks this on both sides of
        // builder.establish(); it cannot use the cancelled generation.
        assertFalse(lifecycle.isCurrent(generation))
        assertFalse(lifecycle.startupDelayed(generation))
        assertFalse(lifecycle.connected(generation))
    }

    @Test fun repeatedHealthFailuresPreserveEstablishedTunnelGenerationAndTimer() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.connected(generation))
        val startedAt = lifecycle.state.value.connectedAtNanos
        assertTrue(lifecycle.tunnelUnverified(generation))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertEquals(VpnLifecycle.TUN_HELD_UNVERIFIED, lifecycle.state.value.detail)
        assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.isCurrent(generation))
        assertTrue(lifecycle.connected(generation))
        assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
        assertEquals("", lifecycle.state.value.detail)
        assertEquals(generation, lifecycle.verifiedGeneration())
    }

    @Test fun unverifiedInitialTunnelCanBeReverifiedWithoutRestart() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.tunnelUnverified(generation))
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertNull(lifecycle.verifiedGeneration())
        assertTrue(lifecycle.isCurrent(generation))
        assertTrue(lifecycle.connected(generation))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertEquals(generation, lifecycle.verifiedGeneration())
    }

    @Test fun oldHealthCallbackCannotMarkNewTunnelUnverified() {
        val lifecycle = VpnLifecycle()
        val old = lifecycle.start()!!
        lifecycle.connected(old)
        lifecycle.stop()
        lifecycle.stopped(lifecycle.stopGeneration()!!)
        val current = lifecycle.start()!!
        lifecycle.connected(current)
        assertFalse(lifecycle.tunnelUnverified(old))
        assertEquals("", lifecycle.state.value.detail)
        assertEquals(current, lifecycle.verifiedGeneration())
    }

    @Test fun nativeEngineStopCanRetainTunWithoutClaimingHealthyConnectivity() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertEquals(generation, lifecycle.activeGeneration())
        assertTrue(lifecycle.connected(generation))
        assertTrue(lifecycle.tunnelUnverified(generation, nativeStopped = true))
        assertEquals(VpnLifecycle.NATIVE_STOPPED_TUN_HELD, lifecycle.state.value.detail)
        assertNull(lifecycle.verifiedGeneration())
        assertEquals(generation, lifecycle.activeGeneration())
        lifecycle.stop()
        assertNull(lifecycle.activeGeneration())
        assertFalse(lifecycle.tunnelUnverified(generation, nativeStopped = true))
    }

    @Test fun duplicateConnectCannotCreateSecondSession() {
        val lifecycle = VpnLifecycle()
        assertNotNull(lifecycle.start())
        assertNull(lifecycle.start())
    }
    @Test fun cancellationRejectsLateStartupAndBlocksRestartUntilCleanup() {
        val lifecycle = VpnLifecycle()
        val old = lifecycle.start()!!
        assertTrue(lifecycle.stop())
        assertEquals(VpnStatus.DISCONNECTING, lifecycle.state.value.status)
        assertFalse(lifecycle.connected(old))
        assertNull(lifecycle.start())
        lifecycle.stopped(lifecycle.stopGeneration()!!)
        assertNull(lifecycle.state.value.connectedAtNanos)
        val fresh = lifecycle.start()!!
        assertNotEquals(old, fresh)
        assertFalse(lifecycle.connected(old))
        assertTrue(lifecycle.connected(fresh))
    }
    @Test fun stopIsIdempotentAndErrorAllowsRetry() {
        val lifecycle = VpnLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.stop())
        lifecycle.stopped(lifecycle.stopGeneration()!!, "NETWORK_TIMEOUT")
        assertEquals(VpnStatus.ERROR, lifecycle.state.value.status)
        assertNotNull(lifecycle.start())
        assertEquals("", lifecycle.state.value.detail)
    }
    @Test fun lateConnectionAfterFailureCannotRestoreOldSession() {
        val lifecycle = VpnLifecycle()
        val old = lifecycle.start()!!
        assertTrue(lifecycle.stop())
        lifecycle.stopped(lifecycle.stopGeneration()!!, "STARTUP_TIMEOUT")
        val fresh = lifecycle.start()!!
        assertFalse(lifecycle.connected(old))
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertTrue(lifecycle.connected(fresh))
        assertNotNull(lifecycle.state.value.connectedAtNanos)
    }

    @Test fun repeatedDisconnectCannotInvalidateNewSession() {
        val lifecycle = VpnLifecycle()
        val old = lifecycle.start()!!
        assertTrue(lifecycle.connected(old))
        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.stop())
        lifecycle.stopped(lifecycle.stopGeneration()!!)
        val fresh = lifecycle.start()!!
        assertFalse(lifecycle.connected(old))
        assertTrue(lifecycle.connected(fresh))
    }

    @Test fun staleStopCompletionCannotOverwriteNewSession() {
        val lifecycle = VpnLifecycle()
        val first = lifecycle.start()!!
        assertTrue(lifecycle.stop())
        val firstStop = lifecycle.stopGeneration()!!
        assertTrue(lifecycle.stopped(firstStop))
        val second = lifecycle.start()!!
        assertTrue(lifecycle.connected(second))
        assertFalse(lifecycle.stopped(firstStop, "LATE_ERROR"))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertTrue(lifecycle.stop())
        val secondStop = lifecycle.stopGeneration()!!
        assertFalse(lifecycle.stopped(firstStop))
        assertEquals(VpnStatus.DISCONNECTING, lifecycle.state.value.status)
        assertTrue(lifecycle.stopped(secondStop))
        assertFalse(lifecycle.connected(first))
    }

    @Test fun transientFailureIsVisibleAndRecoveryPreservesStopwatch() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertFalse(lifecycle.checkingConnection(generation)) // Not connected yet
        assertTrue(lifecycle.connected(generation))
        val started = lifecycle.state.value.connectedAtNanos
        assertTrue(lifecycle.checkingConnection(generation))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertTrue(lifecycle.state.value.detail.isNotBlank())
        assertTrue(lifecycle.connected(generation))
        assertEquals("", lifecycle.state.value.detail)
        assertEquals(started, lifecycle.state.value.connectedAtNanos)
    }

    @Test fun physicalOutagePreservesSessionTimerAndDoesNotClaimRecovery() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.waitingForNetwork(generation, "Ожидаем сеть"))
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertEquals("Ожидаем сеть", lifecycle.state.value.detail)
        assertTrue(lifecycle.connected(generation))
        val startedAt = lifecycle.state.value.connectedAtNanos
        assertTrue(lifecycle.waitingForNetwork(generation, "Восстанавливаем сеть"))
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
        assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
        assertTrue(lifecycle.connected(generation))
        assertEquals("", lifecycle.state.value.detail)
        assertEquals(startedAt, lifecycle.state.value.connectedAtNanos)
    }

    @Test fun stoppedSessionCannotBeResurrectedByLateNetworkCallback() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.waitingForNetwork(generation, "Ожидаем сеть"))
        assertEquals(VpnStatus.DISCONNECTING, lifecycle.state.value.status)
    }

    @Test fun lateHealthFailureCannotCorruptNewSession() {
        val lifecycle = VpnLifecycle()
        val old = lifecycle.start()!!
        lifecycle.connected(old)
        lifecycle.stop()
        lifecycle.stopped(lifecycle.stopGeneration()!!)
        val current = lifecycle.start()!!
        lifecycle.connected(current)
        assertFalse(lifecycle.checkingConnection(old))
        assertEquals("", lifecycle.state.value.detail)
    }

}

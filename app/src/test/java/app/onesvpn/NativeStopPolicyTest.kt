package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class NativeStopPolicyTest {
    @Test fun establishedTunIsNeverClosedBySpontaneousServiceStop() {
        for (inFlight in listOf(false, true)) {
            assertEquals(NativeStopPolicy.Action.HOLD_TUN,
                NativeStopPolicy.decide(tunEstablished = true, nativeStartInFlight = inFlight))
        }
    }

    @Test fun nativeStopDuringEstablishDefersTeardown() {
        assertEquals(NativeStopPolicy.Action.DEFER_UNTIL_STARTUP_FINISHES,
            NativeStopPolicy.decide(tunEstablished = false, nativeStartInFlight = true))
    }

    @Test fun noTunAfterNativeStartupMayCleanup() {
        assertEquals(NativeStopPolicy.Action.CLEANUP_NO_TUN,
            NativeStopPolicy.decide(tunEstablished = false, nativeStartInFlight = false))
    }

    @Test fun deferredNativeStopCannotClaimTunnelOrVerifiedConnection() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertEquals(NativeStopPolicy.Action.DEFER_UNTIL_STARTUP_FINISHES,
            NativeStopPolicy.decide(tunEstablished = false, nativeStartInFlight = true))
        assertTrue(lifecycle.waitingForNetwork(generation, "Остановка движка при запуске"))
        assertNull(lifecycle.verifiedGeneration())
        assertEquals(VpnStatus.CONNECTING, lifecycle.state.value.status)
        assertTrue(lifecycle.isCurrent(generation))
    }

    @Test fun nativeStopWithExistingTunKeepsGenerationUnverified() {
        val lifecycle = VpnLifecycle()
        val generation = lifecycle.start()!!
        assertTrue(lifecycle.connected(generation))
        assertEquals(NativeStopPolicy.Action.HOLD_TUN,
            NativeStopPolicy.decide(tunEstablished = true, nativeStartInFlight = true))
        assertTrue(lifecycle.tunnelUnverified(generation, nativeStopped = true))
        assertNull(lifecycle.verifiedGeneration())
        assertEquals(VpnStatus.CONNECTED, lifecycle.state.value.status)
    }
}

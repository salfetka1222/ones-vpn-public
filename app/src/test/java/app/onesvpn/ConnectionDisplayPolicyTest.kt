package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ConnectionDisplayPolicyTest {
    private fun display(
        status: VpnStatus, detail: String = "",
        verifiedAt: Long? = 1_000L, now: Long = 2_000L
    ) = ConnectionDisplayPolicy.evaluate(
        VpnState(status = status, detail = detail, lastVerifiedElapsedMs = verifiedAt), now
    )

    @Test fun verifiedOnlyAfterSuccessfulProbe() {
        assertTrue(display(VpnStatus.CONNECTED).verified)
        assertEquals("GOOD", display(VpnStatus.CONNECTED).label)
        assertFalse(display(VpnStatus.CONNECTING).verified)
    }

    @Test fun outageNeverClaimsGoodConnectivity() {
        for (status in listOf(VpnStatus.CONNECTED, VpnStatus.CONNECTING)) {
            for (message in listOf("Ожидаем сеть", "Восстанавливаем сеть")) {
                val ui = display(status, message)
                assertFalse(ui.verified)
                assertTrue(ui.recovering)
                assertEquals("WAIT", ui.label)
                assertEquals(message, ui.subtitle)
            }
        }
    }

    @Test fun checkingTunnelIsNotVerified() {
        val ui = display(VpnStatus.CONNECTED, "Проверяем соединение…")
        assertEquals("CHECKING", ui.label)
        assertFalse(ui.verified)
        assertTrue(ui.recovering)
    }

    @Test fun keepingTunOnRepeatedProbeFailureNeverShowsVerifiedOrReady() {
        for (status in listOf(VpnStatus.CONNECTED, VpnStatus.CONNECTING)) {
            val ui = display(status, VpnLifecycle.TUN_HELD_UNVERIFIED,
                verifiedAt = 2_000L, now = 2_000L)
            assertEquals("UNVERIFIED", ui.label)
            assertFalse(ui.verified)
            assertTrue(ui.recovering)
            assertTrue(ui.subtitle.contains("ТУННЕЛЬ СОХРАНЁН"))
        }
    }

    @Test fun delayedNativeStartupCannotClaimVpnSuccess() {
        val ui = display(VpnStatus.CONNECTING, VpnLifecycle.STARTUP_DELAYED)
        assertEquals("WAIT", ui.label)
        assertFalse(ui.verified)
        assertTrue(ui.recovering)
        assertTrue(ui.subtitle.contains("ЗАДЕРЖИВАЕТСЯ"))
    }

    @Test fun nativeEngineStopDoesNotMasqueradeAsVpnHealth() {
        val ui = display(VpnStatus.CONNECTED, VpnLifecycle.NATIVE_STOPPED_TUN_HELD)
        assertEquals("UNVERIFIED", ui.label)
        assertTrue(ui.subtitle.contains("ДВИЖОК ОСТАНОВЛЕН"))
        assertFalse(ui.verified)
        assertTrue(ui.recovering)
    }

    @Test fun oldEvidenceDoesNotShowGoodAfterLongBackgroundSleep() {
        val maxAge = ConnectionDisplayPolicy.MAX_PROOF_AGE_MS
        assertTrue(display(VpnStatus.CONNECTED, verifiedAt = 100L, now = 100L + maxAge).verified)
        val expired = display(VpnStatus.CONNECTED, verifiedAt = 100L, now = 101L + maxAge)
        assertEquals("CHECKING", expired.label)
        assertFalse(expired.verified)
        assertTrue(expired.recovering)
    }

    @Test fun missingOrFutureEvidenceNeverShowsGood() {
        assertFalse(display(VpnStatus.CONNECTED, verifiedAt = null).verified)
        assertFalse(display(VpnStatus.CONNECTED, verifiedAt = -1L).verified)
        assertFalse(display(VpnStatus.CONNECTED, verifiedAt = 2_500L, now = 2_000L).verified)
    }

    @Test fun freshProofCannotOverrideRecoveryMessage() {
        val ui = display(VpnStatus.CONNECTED, "Ожидаем сеть", verifiedAt = 2_000, now = 2_000)
        assertEquals("WAIT", ui.label)
        assertFalse(ui.verified)
    }

    @Test fun disconnectedErrorAndStoppingAreNeverHealthy() {
        for (status in listOf(VpnStatus.DISCONNECTED, VpnStatus.DISCONNECTING, VpnStatus.ERROR)) {
            assertFalse(display(status).verified)
        }
    }
}

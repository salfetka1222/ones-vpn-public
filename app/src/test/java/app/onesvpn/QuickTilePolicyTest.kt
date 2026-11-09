package app.onesvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickTilePolicyTest {
    private val now = 200_000L

    @Test fun connectsOnlyWithSelectedProfileAndVpnConsent() {
        for (status in listOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR)) {
            assertEquals(QuickTilePolicy.Action.OPEN_APP, QuickTilePolicy.decide(status, false, false))
            assertEquals(QuickTilePolicy.Action.OPEN_APP, QuickTilePolicy.decide(status, true, false))
            assertEquals(QuickTilePolicy.Action.OPEN_APP, QuickTilePolicy.decide(status, false, true))
            assertEquals(QuickTilePolicy.Action.CONNECT, QuickTilePolicy.decide(status, true, true))
        }
    }

    @Test fun disconnectsWhenConnectedOrConnecting() {
        for (status in listOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED)) {
            assertEquals(QuickTilePolicy.Action.DISCONNECT, QuickTilePolicy.decide(status, false, false))
        }
    }

    @Test fun unverifiedTunnelIsNotLabelledHealthyInQuickSettings() {
        val state = VpnState(VpnStatus.CONNECTED,
            detail = VpnLifecycle.TUN_HELD_UNVERIFIED)
        assertEquals("Связь не проверена", QuickTilePolicy.subtitle(state, now))
        assertEquals("VPN-движок остановлен", QuickTilePolicy.subtitle(
            state.copy(detail = VpnLifecycle.NATIVE_STOPPED_TUN_HELD), now))
        assertEquals("Запуск задерживается", QuickTilePolicy.subtitle(
            VpnState(VpnStatus.CONNECTING, detail = VpnLifecycle.STARTUP_DELAYED), now))
        assertEquals(VpnStatus.CONNECTED.label, QuickTilePolicy.subtitle(
            state.copy(detail = "", lastVerifiedElapsedMs = now - 5000L), now))
        // Even an unverified tunnel must still allow explicit disconnection.
        assertEquals(QuickTilePolicy.Action.DISCONNECT,
            QuickTilePolicy.decide(VpnStatus.CONNECTED, true, true))
    }

    @Test fun staleOrMissingProofCannotShowHealthySubtitle() {
        val connected = VpnState(VpnStatus.CONNECTED)
        assertEquals("Требуется проверка", QuickTilePolicy.subtitle(connected, now))
        assertEquals("Требуется проверка", QuickTilePolicy.subtitle(
            connected.copy(lastVerifiedElapsedMs = now - 91_000L), now))
        assertEquals("Требуется проверка", QuickTilePolicy.subtitle(
            connected.copy(lastVerifiedElapsedMs = now + 1L), now))
        assertEquals("Подключено", QuickTilePolicy.subtitle(
            connected.copy(lastVerifiedElapsedMs = now - 89_000L), now))
    }

    @Test fun recoveringSessionHasAccurateSubtitle() {
        val connected = VpnState(VpnStatus.CONNECTED, detail = "Проверяем соединение…",
            lastVerifiedElapsedMs = now - 5000L)
        assertEquals("Проверяем соединение", QuickTilePolicy.subtitle(connected, now))
        assertEquals("Ожидаем сеть", QuickTilePolicy.subtitle(
            connected.copy(detail = "Ожидаем сеть"), now))
        assertEquals("Восстанавливаем сеть", QuickTilePolicy.subtitle(
            connected.copy(detail = "Восстанавливаем сеть"), now))
    }

    @Test fun cannotToggleWhileDisconnecting() {
        assertEquals(QuickTilePolicy.Action.WAIT,
            QuickTilePolicy.decide(VpnStatus.DISCONNECTING, true, true))
    }
}

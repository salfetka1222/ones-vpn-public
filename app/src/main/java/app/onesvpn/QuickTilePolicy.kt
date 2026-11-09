package app.onesvpn

/** Pure policy keeps the tile from silently connecting without a chosen profile or consent. */
internal object QuickTilePolicy {
    enum class Action { CONNECT, DISCONNECT, OPEN_APP, WAIT }

    /** A connected TUN does not imply a verified working VPN. */
    fun subtitle(state: VpnState, nowElapsedMs: Long): String = when (state.detail) {
        VpnLifecycle.TUN_HELD_UNVERIFIED -> "Связь не проверена"
        VpnLifecycle.NATIVE_STOPPED_TUN_HELD -> "VPN-движок остановлен"
        VpnLifecycle.STARTUP_DELAYED -> "Запуск задерживается"
        "Ожидаем сеть", "Восстанавливаем сеть" -> state.detail
        else -> when {
            state.status == VpnStatus.CONNECTED && state.detail.isNotEmpty() ->
                "Проверяем соединение"
            state.status == VpnStatus.CONNECTED &&
                !ConnectionDisplayPolicy.evaluate(state, nowElapsedMs).verified ->
                "Требуется проверка"
            else -> state.status.label
        }
    }

    fun decide(status: VpnStatus, hasProfile: Boolean, hasConsent: Boolean): Action =
        when (status) {
            VpnStatus.CONNECTING, VpnStatus.CONNECTED -> Action.DISCONNECT
            VpnStatus.DISCONNECTING -> Action.WAIT
            VpnStatus.DISCONNECTED, VpnStatus.ERROR ->
                if (hasProfile && hasConsent) Action.CONNECT else Action.OPEN_APP
        }
}

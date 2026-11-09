package app.onesvpn

/**
 * A live VPN tunnel is not proof that the network is currently reachable.
 */
internal data class ConnectionDisplay(
    val label: String,
    val subtitle: String,
    val verified: Boolean,
    val recovering: Boolean
)

internal object ConnectionDisplayPolicy {
    // After an extended screen-off/Doze interval a still-open TUN is not
    // fresh evidence that HTTPS works through the VPN.
    const val MAX_PROOF_AGE_MS = 90_000L

    fun evaluate(state: VpnState, nowElapsedMs: Long): ConnectionDisplay {
        val recoveryMessage = state.detail == "Ожидаем сеть" ||
            state.detail == "Восстанавливаем сеть"
        if (recoveryMessage) return ConnectionDisplay(
            "WAIT", state.detail, verified = false, recovering = true
        )
        if (state.detail == VpnLifecycle.STARTUP_DELAYED &&
            state.status == VpnStatus.CONNECTING) {
            return ConnectionDisplay(
                "WAIT", "ЗАПУСК ЗАДЕРЖИВАЕТСЯ · СВЯЗЬ НЕ ПРОВЕРЕНА",
                verified = false, recovering = true
            )
        }
        if (state.detail in setOf(
                VpnLifecycle.TUN_HELD_UNVERIFIED, VpnLifecycle.NATIVE_STOPPED_TUN_HELD
            ) && state.status in setOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED)) {
            val subtitle = if (state.detail == VpnLifecycle.NATIVE_STOPPED_TUN_HELD)
                "VPN-ДВИЖОК ОСТАНОВЛЕН · ТУННЕЛЬ СОХРАНЁН"
            else "СВЯЗЬ НЕ ПОДТВЕРЖДЕНА · ТУННЕЛЬ СОХРАНЁН"
            return ConnectionDisplay(
                "UNVERIFIED", subtitle, verified = false, recovering = true
            )
        }
        return when (state.status) {
            VpnStatus.CONNECTED -> {
                val proofAt = state.lastVerifiedElapsedMs
                val proofFresh = proofAt != null && proofAt >= 0L &&
                    nowElapsedMs >= proofAt && nowElapsedMs - proofAt <= MAX_PROOF_AGE_MS
                when {
                    state.detail.isNotEmpty() -> ConnectionDisplay(
                        "CHECKING", "ПРОВЕРЯЕМ СОЕДИНЕНИЕ", verified = false, recovering = true
                    )
                    proofFresh -> ConnectionDisplay(
                        "GOOD", "VPN-СОЕДИНЕНИЕ ПРОВЕРЕНО", verified = true, recovering = false
                    )
                    else -> ConnectionDisplay(
                        "CHECKING", "ТРЕБУЕТСЯ ПОВТОРНАЯ ПРОВЕРКА", verified = false, recovering = true
                    )
                }
            }
            VpnStatus.CONNECTING -> ConnectionDisplay(
                "CONNECTING", "УСТАНАВЛИВАЕМ СОЕДИНЕНИЕ", verified = false, recovering = false
            )
            VpnStatus.DISCONNECTING -> ConnectionDisplay(
                "ENDING", "ОТКЛЮЧАЕМ VPN", verified = false, recovering = false
            )
            VpnStatus.ERROR -> ConnectionDisplay(
                "RETRY", "ПРОВЕРЬТЕ ПОДКЛЮЧЕНИЕ", verified = false, recovering = false
            )
            VpnStatus.DISCONNECTED -> ConnectionDisplay(
                "READY", "VPN НЕ ПОДКЛЮЧЁН", verified = false, recovering = false
            )
        }
    }
}

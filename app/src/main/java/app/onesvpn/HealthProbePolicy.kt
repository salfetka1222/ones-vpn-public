package app.onesvpn

/**
 * A failed HTTPS health probe is NOT evidence that the native VPN is unsafe:
 * probe hosts may be blocked while the rest of the proxy remains usable.
 *
 * IMPORTANT: callers may use this policy only AFTER native VPN/TUN startup.
 * Never close an established TUN just to recover from failed health probes:
 * without Android Lockdown that would expose direct Wi-Fi/mobile traffic.
 */
internal object HealthProbePolicy {
    private const val MAX_CONSECUTIVE_FAILURES = 3
    const val HEALTHY_INTERVAL_SECONDS = 30L
    const val RETRY_INTERVAL_SECONDS = 10L
    const val HOLD_INTERVAL_SECONDS = 30L

    enum class Action { HEALTHY, RECHECK, HOLD_TUN }
    data class Decision(val action: Action, val consecutiveFailures: Int)

    fun evaluate(
        probeSucceeded: Boolean,
        consecutiveFailures: Int,
    ): Decision {
        require(consecutiveFailures >= 0) { "invalid failure count" }
        if (probeSucceeded) return Decision(Action.HEALTHY, 0)
        val failures = if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            MAX_CONSECUTIVE_FAILURES
        } else {
            consecutiveFailures + 1
        }
        // Failure must never return an action that closes the TUN.
        val action = if (failures >= MAX_CONSECUTIVE_FAILURES) {
            Action.HOLD_TUN
        } else Action.RECHECK
        return Decision(action, failures)
    }
}

package app.onesvpn

/**
 * Wait for Android's physical network rather than treating Wi-Fi/cellular
 * handover as a failed VPN server. This does not provide a system Kill Switch.
 */
internal object NetworkRecoveryPolicy {
    const val RETRY_SECONDS = 5L
    private const val HANDOVER_GRACE_MS = 12_000L

    enum class Decision { PROBE, WAIT_FOR_NETWORK, WAIT_FOR_HANDOVER }

    fun assess(
        physicalAvailable: Boolean,
        physicalAgeMs: Long,
        previouslyConnected: Boolean,
        changedDuringProbe: Boolean = false
    ): Decision {
        if (!physicalAvailable) return Decision.WAIT_FOR_NETWORK
        // A successful response from an older network must not mark the new
        // network healthy; repeat a VPN-bound probe on the new interface.
        if (changedDuringProbe || (previouslyConnected &&
                (physicalAgeMs >= 0L && physicalAgeMs < HANDOVER_GRACE_MS))) {
            return Decision.WAIT_FOR_HANDOVER
        }
        return Decision.PROBE
    }
}

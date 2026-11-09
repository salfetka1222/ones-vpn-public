package app.onesvpn

/** Pure retry policy. Never reconnect without explicit user intent and VPN consent. */
internal object ReconnectPolicy {
    private const val MAX_ATTEMPTS = 6
    private const val MAX_DELAY_MS = 60_000L

    fun delayMs(
        attempt: Int,
        userWantsConnection: Boolean,
        vpnConsentGranted: Boolean,
        alwaysOn: Boolean = false
    ): Long? {
        if (!userWantsConnection || !vpnConsentGranted || attempt < 0) return null
        // Non-Always-on sessions remain bounded. System-managed Always-on keeps
        // trying at most once a minute while consent and user intent remain.
        if (!alwaysOn && attempt >= MAX_ATTEMPTS) return null
        return (1_000L shl attempt.coerceAtMost(MAX_ATTEMPTS)).coerceAtMost(MAX_DELAY_MS)
    }
}

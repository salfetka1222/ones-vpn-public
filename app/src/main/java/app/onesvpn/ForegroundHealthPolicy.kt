package app.onesvpn

/**
 * UI foregrounding opportunistically refreshes VPN-bound proof. No wakelock,
 * external polling, bypass network, or direct TUN changes are required.
 */
internal object ForegroundHealthPolicy {
    private const val PROOF_RECHECK_AFTER_MS = 20_000L
    private const val REQUEST_COOLDOWN_MS = 10_000L

    fun shouldCheck(
        nowElapsedMs: Long,
        lastVerifiedElapsedMs: Long?,
        lastRequestedElapsedMs: Long?
    ): Boolean {
        if (nowElapsedMs < 0L) return false
        if (lastRequestedElapsedMs != null &&
            nowElapsedMs >= lastRequestedElapsedMs &&
            nowElapsedMs - lastRequestedElapsedMs < REQUEST_COOLDOWN_MS) return false
        return lastVerifiedElapsedMs == null ||
            lastVerifiedElapsedMs < 0L ||
            nowElapsedMs < lastVerifiedElapsedMs ||
            nowElapsedMs - lastVerifiedElapsedMs >= PROOF_RECHECK_AFTER_MS
    }
}

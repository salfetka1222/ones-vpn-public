package app.onesvpn

/** The first foreground posting is mandatory; same-label updates are redundant. */
internal object ForegroundNotificationPolicy {
    fun needsUpdate(lastPostedLabel: String?, nextLabel: String): Boolean =
        lastPostedLabel != nextLabel
}

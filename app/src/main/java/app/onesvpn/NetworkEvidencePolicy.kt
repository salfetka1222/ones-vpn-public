package app.onesvpn

/** Reject checks that raced with physical network loss, handover or disconnect. */
internal object NetworkEvidencePolicy {
    fun isCurrent(
        beforeAvailable: Boolean,
        afterAvailable: Boolean,
        beforeRevision: Long,
        afterRevision: Long,
        verifiedSession: Boolean
    ): Boolean = beforeAvailable && afterAvailable &&
        beforeRevision == afterRevision && verifiedSession
}

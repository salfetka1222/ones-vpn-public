package app.onesvpn

/** Pure decision boundary for incoming physical-network callback metadata. */
internal object NetworkHandoverPolicy {
    enum class Action { WAIT, USE, CLEAR }

    fun decide(
        capabilitiesKnown: Boolean,
        linkPropertiesKnown: Boolean,
        internet: Boolean,
        physical: Boolean,
        interfaceIndex: Int?,
        blocked: Boolean
    ): Action = when {
        // A blocked network must never remain bound while metadata is pending.
        blocked -> Action.CLEAR
        !capabilitiesKnown || !linkPropertiesKnown -> Action.WAIT
        !internet || !physical || interfaceIndex == null || interfaceIndex <= 0 -> Action.CLEAR
        else -> Action.USE
    }
}

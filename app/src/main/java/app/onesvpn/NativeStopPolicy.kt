package app.onesvpn

/**
 * A spontaneous libbox service-stop may race with Android's TUN establish().
 * Only the engine worker can resolve an in-flight native startup safely.
 * Never call requestStop() from that callback while a TUN might be created.
 *
 * This cannot replace Android's system Always-on / lockdown protection.
 */
internal object NativeStopPolicy {
    enum class Action { HOLD_TUN, DEFER_UNTIL_STARTUP_FINISHES, CLEANUP_NO_TUN }

    fun decide(tunEstablished: Boolean, nativeStartInFlight: Boolean): Action =
        when {
            tunEstablished -> Action.HOLD_TUN
            nativeStartInFlight -> Action.DEFER_UNTIL_STARTUP_FINISHES
            else -> Action.CLEANUP_NO_TUN
        }
}

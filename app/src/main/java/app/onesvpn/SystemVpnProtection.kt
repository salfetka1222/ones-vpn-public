package app.onesvpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports the OS-managed protection of THIS VPN service, not a simulated
 * application-level kill switch. Unknown never means disabled.
 */
internal enum class SystemProtectionLevel {
    UNKNOWN,
    VPN_ONLY,
    ALWAYS_ON,
    LOCKDOWN
}

internal object SystemVpnProtectionPolicy {
    fun classify(alwaysOn: Boolean?, lockdown: Boolean?): SystemProtectionLevel =
        when {
            alwaysOn == null || lockdown == null -> SystemProtectionLevel.UNKNOWN
            // Inconsistent OS state must never be presented as an enabled Kill Switch.
            lockdown && !alwaysOn -> SystemProtectionLevel.UNKNOWN
            lockdown -> SystemProtectionLevel.LOCKDOWN
            alwaysOn -> SystemProtectionLevel.ALWAYS_ON
            else -> SystemProtectionLevel.VPN_ONLY
        }
}

internal data class SystemProtectionSnapshot(
    val level: SystemProtectionLevel = SystemProtectionLevel.UNKNOWN,
    // Changes even when Android returns the same protection level. The UI can
    // distinguish a completed user-requested check from a silent refresh.
    val manualCheckSequence: Long = 0L
)

internal object SystemVpnProtection {
    private val mutableState = MutableStateFlow(SystemProtectionSnapshot())
    val state = mutableState.asStateFlow()

    fun update(alwaysOn: Boolean, lockdown: Boolean, manualCheck: Boolean = false) {
        val current = mutableState.value
        mutableState.value = SystemProtectionSnapshot(
            SystemVpnProtectionPolicy.classify(alwaysOn, lockdown),
            current.manualCheckSequence + (if (manualCheck) 1L else 0L)
        )
    }

    fun clear(manualCheck: Boolean = false) {
        // A failed manual query must finish visibly as UNKNOWN, not leave the
        // button indefinitely saying that Android is still being checked.
        val previous = mutableState.value.manualCheckSequence
        mutableState.value = SystemProtectionSnapshot(
            manualCheckSequence = if (manualCheck) previous + 1L else 0L
        )
    }
}

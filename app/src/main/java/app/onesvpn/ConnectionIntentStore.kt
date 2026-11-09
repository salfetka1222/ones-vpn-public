package app.onesvpn

import android.content.Context

/**
 * Only the opaque profile ID is persisted. The VLESS URI and credentials remain
 * in ProfileStore's Android Keystore-encrypted storage.
 *
 * Remembering a successful explicit request permits the *system* to restore
 * a profile later if the user enables Android Always-on VPN. Manual disconnect
 * and revoked consent clear the request.
 */
internal class ConnectionIntentStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection_intent_v1", Context.MODE_PRIVATE)

    fun rememberedId(): String? = prefs.getString("requested_profile_id", null)

    fun remember(profileId: String) {
        require(profileId.isNotBlank() && profileId.length <= 128)
        check(prefs.edit().putString("requested_profile_id", profileId).commit()) {
            "COULD_NOT_REMEMBER_PROFILE"
        }
    }

    fun clear(): Boolean =
        prefs.edit().remove("requested_profile_id").commit()
}

/** Testable guard for service starts that were not initiated by the app. */
internal object AlwaysOnRestartPolicy {
    // Android can send a null intent or an explicit intent without an action.
    // Our own callers always supply one of the app-specific actions.
    fun isSystemStart(action: String?): Boolean =
        action == null || action == "android.net.VpnService"

    fun profileToRestore(
        systemInitiated: Boolean,
        alwaysOn: Boolean,
        vpnConsentGranted: Boolean,
        rememberedId: String?
    ): String? {
        if (!systemInitiated || !alwaysOn || !vpnConsentGranted) return null
        return rememberedId?.takeIf { it.isNotBlank() && it.length <= 128 }
    }
}

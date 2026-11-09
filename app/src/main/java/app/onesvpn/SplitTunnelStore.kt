package app.onesvpn

import android.content.Context

/** Only user-selected, launcher-visible apps can bypass the VPN tunnel. */
internal object SplitTunnelPolicy {
    const val MAX_EXCLUDED_APPS = 64
    private val packageName = Regex("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+")

    fun validate(excluded: Set<String>, ownPackage: String): Set<String> {
        require(excluded.size <= MAX_EXCLUDED_APPS) { "TOO_MANY_EXCLUSIONS" }
        require(excluded.all { it != ownPackage && packageName.matches(it) }) {
            "INVALID_EXCLUSION"
        }
        return excluded.toSet()
    }

    fun validForInstalled(excluded: Set<String>, ownPackage: String): Set<String> =
        excluded.filterTo(mutableSetOf()) { it != ownPackage && packageName.matches(it) }
            .take(MAX_EXCLUDED_APPS).toSet()
}

internal class SplitTunnelStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("split_tunnel_v1", Context.MODE_PRIVATE)

    fun load(): Set<String> = SplitTunnelPolicy.validForInstalled(
        preferences.getStringSet("exclude", emptySet()).orEmpty(),
        context.packageName
    )

    fun save(excluded: Set<String>) {
        val checked = SplitTunnelPolicy.validate(excluded, context.packageName)
        check(preferences.edit().putStringSet("exclude", checked).commit()) {
            "SPLIT_TUNNEL_SAVE_FAILED"
        }
    }
}

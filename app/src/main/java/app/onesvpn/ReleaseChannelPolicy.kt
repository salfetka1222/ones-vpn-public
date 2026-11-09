package app.onesvpn

/**
 * Once a user has installed a final Stable build, the update center must
 * never automatically offer legacy alpha releases with higher version codes.
 * RC and alpha builds may still migrate to Stable.
 */
internal object ReleaseChannelPolicy {
    private val finalVersion = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

    fun isStable(versionName: String): Boolean {
        val match = finalVersion.matchEntire(versionName) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        return major >= 1
    }
}

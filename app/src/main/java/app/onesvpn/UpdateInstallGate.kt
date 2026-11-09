package app.onesvpn

/**
 * Android may require a separate user grant to launch package installers.
 * Check before doing any network or disk work; do not waste an APK download
 * while waiting for the user to change system settings.
 */
internal object UpdateInstallGate {
    fun <T> runWhenPermitted(canRequestInstalls: Boolean, download: () -> T): T? {
        if (!canRequestInstalls) return null
        return download()
    }
}

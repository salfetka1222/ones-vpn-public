package app.onesvpn

import java.net.HttpURLConnection

/** Every request is bound to our VPN Network. No physical-network fallback. */
internal class VpnConnectivityProbe(private val platform: AndroidPlatform) : AutoCloseable {
    private val session = HttpProbeSession()

    fun check(): Boolean {
        val network = platform.vpnNetwork() ?: return false
        return session.check(
            openConnection = { url -> network.openConnection(url) as HttpURLConnection },
            onFailure = { failure -> Diagnostics.record(Diagnostics.failure(failure)) }
        )
    }

    override fun close() = session.close()
}

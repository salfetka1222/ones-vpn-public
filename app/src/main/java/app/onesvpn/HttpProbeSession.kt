package app.onesvpn

import java.net.HttpURLConnection
import java.net.URL

/**
 * A cancellable, VPN-bound HTTPS probe. The caller supplies an opener already
 * bound to the VPN Network; this helper never creates a default-network socket.
 *
 * Keep connection creation inside the try/finally so even failures during
 * request setup release the connection and cannot leave a stale close target.
 */
internal class HttpProbeSession : AutoCloseable {
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null

    fun check(
        openConnection: (URL) -> HttpURLConnection,
        onFailure: (Exception) -> Unit = {}
    ): Boolean {
        for (endpoint in listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204"
        )) {
            if (closed) return false
            var request: HttpURLConnection? = null
            try {
                val opened = openConnection(URL(endpoint))
                request = opened
                synchronized(this) {
                    if (closed) return false
                    connection = opened
                }
                opened.connectTimeout = 5000
                opened.readTimeout = 5000
                opened.instanceFollowRedirects = false
                opened.useCaches = false
                if (opened.responseCode == 204) return !closed
            } catch (failure: Exception) {
                if (!closed) onFailure(failure)
            } finally {
                runCatching { request?.disconnect() }
                synchronized(this) {
                    if (connection === request) connection = null
                }
            }
        }
        return false
    }

    @Synchronized override fun close() {
        closed = true
        runCatching { connection?.disconnect() }
        connection = null
    }
}

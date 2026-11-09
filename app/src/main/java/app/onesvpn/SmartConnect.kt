package app.onesvpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Probes only TCP reachability to each configured endpoint.
 * This is NOT a TLS/Reality handshake or proof that a VPN session will work.
 * A failed probe is never treated as a successful fallback candidate.
 */
internal object SmartConnect {
    const val MAX_PROFILES = 500
    private const val PARALLEL_PROBES = 6
    private const val CONNECT_TIMEOUT_MS = 1800

    data class Probe(val profileId: String, val latencyMs: Long?)

    fun validateCount(count: Int) {
        require(count in 1..MAX_PROFILES) {
            "Для Smart Connect выберите от 1 до $MAX_PROFILES серверов."
        }
    }

    fun choose(profiles: List<VpnProfile>, probes: List<Probe>): VpnProfile? {
        val times = probes.mapNotNull { result ->
            result.latencyMs?.takeIf { it >= 0L }?.let { result.profileId to it }
        }.toMap()
        return profiles.filter { times.containsKey(it.id) }
            .minWithOrNull(compareBy<VpnProfile> { times.getValue(it.id) }.thenBy { it.id })
    }

    suspend fun probeAll(profiles: List<VpnProfile>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Probe> {
        validateCount(profiles.size)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val results = ArrayList<Probe>(profiles.size)
                for (batch in profiles.chunked(PARALLEL_PROBES)) {
                    currentCoroutineContext().ensureActive()
                    val measured = batch.map { profile ->
                        async {
                            currentCoroutineContext().ensureActive()
                            val result = runCatching {
                                // Reject invalid or insecure profiles before probing.
                                SingBoxConfig.outbound(profile)
                                val start = System.nanoTime()
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                                }
                                ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
                            }.getOrNull()
                            currentCoroutineContext().ensureActive()
                            Probe(profile.id, result)
                        }
                    }.awaitAll()
                    results.addAll(measured)
                    onProgress(results.size, profiles.size)
                }
                results
            }
        }
    }
}

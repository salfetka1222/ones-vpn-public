package app.onesvpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.math.BigInteger

/** On-device routing evidence; two sample IPs per family never prove absence of leaks. */
internal object RouteAuditPolicy {
    data class Prefix(val address: ByteArray, val length: Int)

    /** A present route does not necessarily forward traffic into our VPN. */
    data class RouteEntry(
        val prefix: Prefix,
        val unicast: Boolean,
        val onVpnInterface: Boolean
    )

    fun evaluateForwarding(
        entries: List<RouteEntry>, dnsCount: Int, excludedApps: Int
    ): RouteEvidence = evaluate(
        entries.filter { it.unicast && it.onVpnInterface }.map { it.prefix },
        dnsCount, excludedApps
    )

    /**
     * Android 13+ only: check whether each DNS address is covered by a
     * forwarding route on this VPN Network. This is route metadata, NOT proof
     * that the resolver receives packets or that DNS cannot leak elsewhere.
     */
    fun evaluateForwarding(
        entries: List<RouteEntry>, dnsAddresses: List<ByteArray>, excludedApps: Int
    ): RouteEvidence {
        val forwarding = entries.filter { it.unicast && it.onVpnInterface }.map { it.prefix }
        return evaluate(forwarding, dnsAddresses.size, excludedApps).copy(
            dnsIpv4Servers = dnsAddresses.count { it.size == 4 },
            dnsIpv6Servers = dnsAddresses.count { it.size == 16 },
            dnsServersWithoutVpnRoute = dnsAddresses.count { dns ->
                forwarding.none { prefix -> contains(prefix, dns) }
            }
        )
    }

    private val samples4 = listOf(byteArrayOf(1,1,1,1), byteArrayOf(8,8,8,8))
    private val samples6 = listOf(
        InetAddress.getByName("2606:4700:4700::1111").address,
        InetAddress.getByName("2001:4860:4860::8888").address
    )

    fun contains(prefix: Prefix, ip: ByteArray): Boolean {
        if (prefix.address.size != ip.size || prefix.length !in 0..(ip.size * 8)) return false
        val whole = prefix.length / 8
        for (i in 0 until whole) if (prefix.address[i] != ip[i]) return false
        val remain = prefix.length % 8
        if (remain == 0) return true
        val mask = (255 shl (8 - remain)) and 255
        return (prefix.address[whole].toInt() and mask) == (ip[whole].toInt() and mask)
    }

    /**
     * Verify that the union of Android routes covers the entire IP family,
     * not just a few example addresses. A routing table can contain two /1
     * prefixes or many narrower prefixes instead of a single /0.
     *
     * This checks Android metadata only; it cannot prove leak-free traffic,
     * DNS correctness, app exclusions, or that the native tunnel works.
     */
    private fun coversEntireFamily(routes: List<Prefix>, bytes: Int): Boolean {
        val bits = bytes * 8
        val maxAddress = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val intervals = routes.asSequence()
            .filter { it.address.size == bytes && it.length in 0..bits }
            .map { prefix ->
                val hostBits = bits - prefix.length
                val first = BigInteger(1, prefix.address).shiftRight(hostBits).shiftLeft(hostBits)
                val last = first.add(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE))
                first to last
            }
            .sortedWith(compareBy<Pair<BigInteger, BigInteger>> { it.first }.thenBy { it.second })
            .toList()
        var lastCovered = BigInteger.valueOf(-1)
        for ((first, last) in intervals) {
            if (first > lastCovered.add(BigInteger.ONE)) return false
            if (last > lastCovered) lastCovered = last
            if (lastCovered == maxAddress) return true
        }
        return false
    }

    fun evaluate(routes: List<Prefix>, dnsCount: Int, excludedApps: Int): RouteEvidence {
        require(dnsCount >= 0 && excludedApps >= 0)
        return RouteEvidence(
            samples4.count { ip -> routes.any { contains(it, ip) } },
            samples6.count { ip -> routes.any { contains(it, ip) } },
            dnsCount, excludedApps, routes.size,
            coversEntireFamily(routes, 4),
            coversEntireFamily(routes, 16)
        )
    }
}

internal data class RouteEvidence(
    val ipv4Examples: Int,
    val ipv6Examples: Int,
    val vpnDnsServers: Int,
    val appExclusions: Int,
    val totalRoutes: Int,
    val fullIpv4Coverage: Boolean,
    val fullIpv6Coverage: Boolean,
    val dnsIpv4Servers: Int = 0,
    val dnsIpv6Servers: Int = 0,
    // null = unavailable (e.g. Android 10–12 without public RouteInfo.type).
    val dnsServersWithoutVpnRoute: Int? = null
)

internal enum class AuditStage { NOT_RUN, RUNNING, NO_TUN, COMPLETED, FAILED }
internal data class AuditDisplay(
    val stage: AuditStage = AuditStage.NOT_RUN,
    val result: RouteEvidence? = null
)

internal object VpnRouteAudit {
    private val mutable = MutableStateFlow(AuditDisplay())
    val state = mutable.asStateFlow()

    fun clear() { mutable.value = AuditDisplay() }
    fun running() { mutable.value = AuditDisplay(AuditStage.RUNNING) }
    fun finish(result: RouteEvidence?) {
        mutable.value = if (result == null) AuditDisplay(AuditStage.NO_TUN)
            else AuditDisplay(AuditStage.COMPLETED, result)
    }
    fun fail() { mutable.value = AuditDisplay(AuditStage.FAILED) }
}

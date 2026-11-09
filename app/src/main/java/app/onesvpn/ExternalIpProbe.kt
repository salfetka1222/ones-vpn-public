package app.onesvpn

import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Opt-in public IP checks, strictly bound to the already established VPN Network.
 * Never compare to an unprotected network here; a real before/after baseline is manual.
 * ipify receives the request IP. Never log, persist, or upload these results elsewhere.
 */
internal object ExternalIpFormat {
    /**
     * An "external IP" result must be a public unicast address. Never render
     * loopback, LAN, documentation, carrier NAT or multicast test strings as
     * externally routable. No DNS lookup is performed for IPv4 responses.
     */
    fun validate(raw: String, ipv6: Boolean): String? {
        val input = raw.trim()
        if (input.length !in 2..45) return null
        if (ipv6) {
            if (':' !in input || !input.all { it in "0123456789abcdefABCDEF:" }) return null
            val address = runCatching { InetAddress.getByName(input) }.getOrNull()
            if (address !is Inet6Address || address.isAnyLocalAddress ||
                address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isMulticastAddress || address.isSiteLocalAddress) return null

            val rawIp = address.address
            val first = rawIp[0].toInt() and 255
            val second = rawIp[1].toInt() and 255
            // IPv6 unique local (fc00::/7) and documentation (2001:db8::/32).
            if ((first and 0xFE) == 0xFC ||
                (first == 0x20 && second == 0x01 &&
                    (rawIp[2].toInt() and 255) == 0x0D &&
                    (rawIp[3].toInt() and 255) == 0xB8)) return null
            // IPv6 global unicast allocation 2000::/3. Other address spaces
            // may carry special or local-only semantics.
            return input.takeIf { (first and 0xE0) == 0x20 }
        }

        val octets = input.split('.')
        if (octets.size != 4 || octets.any {
                it.isEmpty() || it.length > 3 || !it.all(Char::isDigit)
            }) return null
        val values = octets.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 0..255 }) return null
        val (a, b, c, _) = values
        val reserved =
            a in listOf(0, 10, 127) || a >= 224 ||
            (a == 100 && b in 64..127) || // Carrier-grade NAT
            (a == 169 && b == 254) ||     // Link-local
            (a == 172 && b in 16..31) || // Private
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && c in 0..2) ||
            (a == 192 && b == 88 && c == 99) ||
            (a == 198 && b in 18..19) || // Benchmarking
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113)
        return input.takeUnless { reserved }
    }

    fun masked(ip: String): String =
        if (':' in ip) ip.substringBefore(':') + ":••••:••••"
        else ip.split('.').take(2).joinToString(".") + ".•••.•••"
}

internal enum class ExternalIpStatus { NOT_CHECKED, CHECKING, MEASURED, UNAVAILABLE, INVALID_RESPONSE }

internal data class ExternalIpResult(
    val status: ExternalIpStatus = ExternalIpStatus.NOT_CHECKED,
    val address: String? = null
)

internal data class ExternalIpSnapshot(
    val inProgress: Boolean = false,
    val ipv4: ExternalIpResult = ExternalIpResult(),
    val ipv6: ExternalIpResult = ExternalIpResult(),
    val noVpnNetwork: Boolean = false
)

internal object ExternalIpState {
    private val current = MutableStateFlow(ExternalIpSnapshot())
    val state = current.asStateFlow()
    fun clear() { current.value = ExternalIpSnapshot() }
    fun running() {
        current.value = ExternalIpSnapshot(
            inProgress = true,
            ipv4 = ExternalIpResult(ExternalIpStatus.CHECKING),
            ipv6 = ExternalIpResult(ExternalIpStatus.CHECKING)
        )
    }
    fun noVpn() { current.value = ExternalIpSnapshot(noVpnNetwork = true) }
    fun finished(ipv4: ExternalIpResult, ipv6: ExternalIpResult) {
        current.value = ExternalIpSnapshot(ipv4 = ipv4, ipv6 = ipv6)
    }
}

internal object ExternalIpProbe {
    private const val MAX_RESPONSE = 96
    fun run(network: Network): Pair<ExternalIpResult, ExternalIpResult> =
        fetch(network, "https://api.ipify.org", false) to
            fetch(network, "https://api6.ipify.org", true)

    private fun fetch(network: Network, endpoint: String, ipv6: Boolean): ExternalIpResult {
        var connection: HttpsURLConnection? = null
        return try {
            connection = network.openConnection(URL(endpoint)) as HttpsURLConnection
            connection.connectTimeout = 5500
            connection.readTimeout = 5500
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode != 200) {
                ExternalIpResult(ExternalIpStatus.UNAVAILABLE)
            } else {
                val output = connection.inputStream.use { input ->
                    val limited = ByteArray(MAX_RESPONSE + 1)
                    var read = 0
                    while (read < limited.size) {
                        val count = input.read(limited, read, limited.size - read)
                        if (count == -1) break
                        if (count == 0) continue
                        read += count
                    }
                    if (read > MAX_RESPONSE) null
                    else String(limited, 0, read, Charsets.US_ASCII).trim()
                }
                val ip = output?.let { ExternalIpFormat.validate(it, ipv6) }
                if (ip == null) ExternalIpResult(ExternalIpStatus.INVALID_RESPONSE)
                else ExternalIpResult(ExternalIpStatus.MEASURED, ip)
            }
        } catch (_: Exception) {
            ExternalIpResult(ExternalIpStatus.UNAVAILABLE)
        } finally {
            connection?.disconnect()
        }
    }
}

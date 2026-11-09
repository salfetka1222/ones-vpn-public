package app.onesvpn

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class VpnRouteAuditTest {
    private fun prefix(ip: String, bits: Int) =
        RouteAuditPolicy.Prefix(InetAddress.getByName(ip).address, bits)

    @Test fun nonForwardingOrForeignInterfaceRoutesNeverClaimFullCoverage() {
        val entries = listOf(
            RouteAuditPolicy.RouteEntry(prefix("0.0.0.0", 0),
                unicast = false, onVpnInterface = true),
            RouteAuditPolicy.RouteEntry(prefix("::", 0),
                unicast = true, onVpnInterface = false)
        )
        val result = RouteAuditPolicy.evaluateForwarding(entries, 2, 0)
        assertFalse(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
        assertEquals(0, result.totalRoutes)
        assertEquals(0, result.ipv4Examples)
        assertEquals(0, result.ipv6Examples)
    }

    @Test fun onlyUnicastVpnRoutesCountAndGapRemainsVisible() {
        val entries = listOf(
            RouteAuditPolicy.RouteEntry(prefix("0.0.0.0", 1), true, true),
            RouteAuditPolicy.RouteEntry(prefix("128.0.0.0", 1), true, false),
            RouteAuditPolicy.RouteEntry(prefix("::", 0), false, true)
        )
        val result = RouteAuditPolicy.evaluateForwarding(entries, 1, 2)
        assertEquals(1, result.totalRoutes)
        assertEquals(2, result.appExclusions)
        assertFalse(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
    }

    @Test fun vpnDnsAddressesMustHaveForwardingRoutesOnVpnInterface() {
        val dns4 = InetAddress.getByName("172.19.0.2").address
        val dns6 = InetAddress.getByName("fd00:19::2").address
        val routes = listOf(
            RouteAuditPolicy.RouteEntry(prefix("172.19.0.0", 30), true, true),
            RouteAuditPolicy.RouteEntry(prefix("fd00:19::", 126), true, true)
        )
        val complete = RouteAuditPolicy.evaluateForwarding(routes, listOf(dns4, dns6), 0)
        assertEquals(2, complete.vpnDnsServers)
        assertEquals(1, complete.dnsIpv4Servers)
        assertEquals(1, complete.dnsIpv6Servers)
        assertEquals(0, complete.dnsServersWithoutVpnRoute)
        val missingIpv6 = RouteAuditPolicy.evaluateForwarding(routes.dropLast(1),
            listOf(dns4, dns6), 0)
        assertEquals(1, missingIpv6.dnsServersWithoutVpnRoute)
        assertFalse(missingIpv6.fullIpv6Coverage)
    }

    @Test fun foreignOrNonForwardingDnsRouteCannotHideGap() {
        val dns = InetAddress.getByName("172.19.0.2").address
        val fake = listOf(
            RouteAuditPolicy.RouteEntry(prefix("172.19.0.0", 30), false, true),
            RouteAuditPolicy.RouteEntry(prefix("172.19.0.0", 30), true, false)
        )
        val result = RouteAuditPolicy.evaluateForwarding(fake, listOf(dns), 0)
        assertEquals(1, result.dnsServersWithoutVpnRoute)
        assertEquals(0, result.totalRoutes)
    }

    @Test fun oldAndroidDnsCountDoesNotClaimRouteAudit() {
        val old = RouteAuditPolicy.evaluateForwarding(emptyList(), 2, 0)
        assertEquals(2, old.vpnDnsServers)
        assertNull(old.dnsServersWithoutVpnRoute)
    }

    @Test fun emptyRoutesDoNotLookSafe() {
        val result = RouteAuditPolicy.evaluate(emptyList(), 0, 0)
        assertEquals(0, result.ipv4Examples)
        assertEquals(0, result.ipv6Examples)
        assertEquals(0, result.vpnDnsServers)
        assertFalse(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
    }

    @Test fun fullFamilyRoutesCoverAllExamples() {
        val result = RouteAuditPolicy.evaluate(
            listOf(prefix("0.0.0.0", 0), prefix("::", 0)), 2, 0)
        assertEquals(2, result.ipv4Examples)
        assertEquals(2, result.ipv6Examples)
        assertEquals(2, result.vpnDnsServers)
        assertTrue(result.fullIpv4Coverage)
        assertTrue(result.fullIpv6Coverage)
    }

    @Test fun splitDefaultIpv4RoutesAlsoCount() {
        val result = RouteAuditPolicy.evaluate(
            listOf(prefix("0.0.0.0", 1), prefix("128.0.0.0", 1)), 1, 3)
        assertEquals(2, result.ipv4Examples)
        assertEquals(0, result.ipv6Examples)
        assertEquals(3, result.appExclusions)
        assertTrue(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
    }

    @Test fun narrowRoutesDoNotCountAsFullCoverage() {
        val result = RouteAuditPolicy.evaluate(
            listOf(prefix("1.1.1.0", 24), prefix("2606:4700:4700::", 48)), 0, 0)
        assertEquals(1, result.ipv4Examples)
        assertEquals(1, result.ipv6Examples)
        assertFalse(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
    }

    @Test fun twoExamplesCanPassWithoutFullRouting() {
        val routes = listOf(
            prefix("1.1.1.1", 32), prefix("8.8.8.8", 32),
            prefix("2606:4700:4700::1111", 128), prefix("2001:4860:4860::8888", 128)
        )
        val result = RouteAuditPolicy.evaluate(routes, 1, 0)
        assertEquals(2, result.ipv4Examples)
        assertEquals(2, result.ipv6Examples)
        assertFalse(result.fullIpv4Coverage)
        assertFalse(result.fullIpv6Coverage)
    }

    @Test fun splitIpv6DefaultRoutesTogetherCoverWholeFamily() {
        val result = RouteAuditPolicy.evaluate(
            listOf(prefix("::", 1), prefix("8000::", 1)), 1, 0)
        assertTrue(result.fullIpv6Coverage)
        assertFalse(result.fullIpv4Coverage)
    }

    @Test fun gapAndOverlapsDoNotCountAsFullCoverage() {
        val result = RouteAuditPolicy.evaluate(
            listOf(prefix("0.0.0.0", 2), prefix("0.0.0.0", 1),
                prefix("128.0.0.0", 2)), 1, 0)
        assertFalse(result.fullIpv4Coverage)
    }

    @Test fun partialByteMasksAndWrongFamilyAreRejected() {
        assertTrue(RouteAuditPolicy.contains(prefix("2001:db8::", 33),
            InetAddress.getByName("2001:db8:7000::1").address))
        assertFalse(RouteAuditPolicy.contains(prefix("2001:db8::", 33),
            InetAddress.getByName("2001:db8:8000::1").address))
        assertFalse(RouteAuditPolicy.contains(prefix("0.0.0.0", 33),
            InetAddress.getByName("1.1.1.1").address))
        assertFalse(RouteAuditPolicy.contains(prefix("0.0.0.0", 0),
            InetAddress.getByName("2001:db8::1").address))
    }
}

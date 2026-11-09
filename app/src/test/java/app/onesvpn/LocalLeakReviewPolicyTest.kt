package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class LocalLeakReviewPolicyTest {
    private fun routes(
        ipv4: Boolean = true,
        ipv6: Boolean = true,
        dns: Int = 2,
        apps: Int = 0
    ) = RouteEvidence(
        ipv4Examples = 2,
        ipv6Examples = 2,
        vpnDnsServers = dns,
        appExclusions = apps,
        totalRoutes = 2,
        fullIpv4Coverage = ipv4,
        fullIpv6Coverage = ipv6
    )

    private fun codes(evidence: RouteEvidence, protection: SystemProtectionLevel, api: Int = 35) =
        LocalLeakReviewPolicy.assess(evidence, protection, api).map { it.code }.toSet()

    @Test fun evenPerfectRoutesAndLockdownRequireIndependentLeakTests() {
        val codes = codes(routes(), SystemProtectionLevel.LOCKDOWN)
        assertTrue(LeakFindingCode.EXTERNAL_TESTS_REQUIRED in codes)
        assertTrue(LeakFindingCode.LOCKDOWN_ACTIVE_UNTESTED in codes)
        assertFalse(LeakFindingCode.IPV4_ROUTE_GAP in codes)
        assertFalse(LeakFindingCode.IPV6_ROUTE_GAP in codes)
    }

    @Test fun incompleteIpv4Ipv6AndMissingDnsAreReportedIndividually() {
        val codes = codes(routes(ipv4 = false, ipv6 = false, dns = 0),
            SystemProtectionLevel.VPN_ONLY)
        assertTrue(LeakFindingCode.IPV4_ROUTE_GAP in codes)
        assertTrue(LeakFindingCode.IPV6_ROUTE_GAP in codes)
        assertTrue(LeakFindingCode.DNS_SERVER_UNLISTED in codes)
        assertTrue(LeakFindingCode.LOCKDOWN_NOT_ENABLED in codes)
    }

    @Test fun dnsRouteGapWarnsWithoutClaimingPacketLevelLeak() {
        val evidence = routes().copy(dnsIpv4Servers = 1, dnsIpv6Servers = 1,
            dnsServersWithoutVpnRoute = 1)
        val newer = codes(evidence, SystemProtectionLevel.LOCKDOWN, 36)
        assertTrue(LeakFindingCode.DNS_VPN_ROUTE_GAP in newer)
        assertTrue(LeakFindingCode.EXTERNAL_TESTS_REQUIRED in newer)
        val older = codes(evidence, SystemProtectionLevel.UNKNOWN, 29)
        assertFalse(LeakFindingCode.DNS_VPN_ROUTE_GAP in older)
        assertFalse(LeakFindingCode.DNS_VPN_ROUTE_GAP in
            codes(evidence.copy(dnsServersWithoutVpnRoute = null),
                SystemProtectionLevel.UNKNOWN, 36))
    }

    @Test fun splitExceptionsRemainVisibleWithFullRouteCoverage() {
        val codes = codes(routes(apps = 3), SystemProtectionLevel.LOCKDOWN)
        assertTrue(LeakFindingCode.APP_EXCLUSIONS in codes)
        assertTrue(LeakFindingCode.EXTERNAL_TESTS_REQUIRED in codes)
    }

    @Test fun oldAndroidNeverReportsRoutingSafety() {
        val codes = codes(routes(), SystemProtectionLevel.UNKNOWN, 29)
        assertTrue(LeakFindingCode.ROUTE_TYPE_UNAVAILABLE in codes)
        assertTrue(LeakFindingCode.LOCKDOWN_UNCONFIRMED in codes)
        assertFalse(LeakFindingCode.IPV4_ROUTE_GAP in codes)
        assertFalse(LeakFindingCode.IPV6_ROUTE_GAP in codes)
    }

    @Test fun alwaysOnWithoutLockdownCannotBeConsideredFailClosed() {
        val codes = codes(routes(), SystemProtectionLevel.ALWAYS_ON)
        assertTrue(LeakFindingCode.LOCKDOWN_NOT_ENABLED in codes)
    }
}

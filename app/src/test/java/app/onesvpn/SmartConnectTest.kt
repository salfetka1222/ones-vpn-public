package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class SmartConnectTest {
    private fun profile(id: String): VpnProfile = VpnProfile(
        id, "Server $id", "example.org", 443,
        "vless://00000000-0000-4000-8000-000000000001@example.org:443?security=tls"
    )

    @Test fun choosesLowestMeasuredLatencyNotListOrder() {
        val profiles = listOf(profile("slow"), profile("fast"), profile("offline"))
        val probes = listOf(
            SmartConnect.Probe("slow", 105),
            SmartConnect.Probe("fast", 19),
            SmartConnect.Probe("offline", null)
        )
        assertEquals("fast", SmartConnect.choose(profiles, probes)?.id)
    }

    @Test fun noReachableEndpointsMeansNoFallback() {
        assertNull(SmartConnect.choose(listOf(profile("one")), listOf(SmartConnect.Probe("one", null))))
        assertNull(SmartConnect.choose(listOf(profile("one")), emptyList()))
    }

    @Test fun ignoresUnknownIdsAndNegativeResults() {
        val profiles = listOf(profile("valid"), profile("negative"))
        val probes = listOf(
            SmartConnect.Probe("unknown", 1),
            SmartConnect.Probe("negative", -1),
            SmartConnect.Probe("valid", 40)
        )
        assertEquals("valid", SmartConnect.choose(profiles, probes)?.id)
    }

    @Test fun equalLatencyHasDeterministicTieBreak() {
        val profiles = listOf(profile("z"), profile("a"))
        val probes = listOf(SmartConnect.Probe("z", 15), SmartConnect.Probe("a", 15))
        assertEquals("a", SmartConnect.choose(profiles, probes)?.id)
    }

    @Test fun refusesEmptyAndOversizedProbeBatch() {
        assertThrows(IllegalArgumentException::class.java) { SmartConnect.validateCount(0) }
        assertThrows(IllegalArgumentException::class.java) {
            SmartConnect.validateCount(SmartConnect.MAX_PROFILES + 1)
        }
        SmartConnect.validateCount(200)
        SmartConnect.validateCount(SmartConnect.MAX_PROFILES)
    }
}

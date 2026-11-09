package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ReleaseChannelPolicyTest {
    @Test fun finalStableVersionsCloseLegacyAlphaChannel() {
        for (name in listOf("1.0.0", "1.0.1", "1.9.10", "2.0.0")) {
            assertTrue(name, ReleaseChannelPolicy.isStable(name))
        }
    }

    @Test fun rcBetaAlphaAndInvalidNamesKeepStableGateClosed() {
        for (name in listOf(
            "1.0.0-rc.3", "1.0.0-beta", "0.9.0", "1.0.0+debug",
            "01.0.0", "1.0", "1.0.0-rc.0", ""
        )) {
            assertFalse(name, ReleaseChannelPolicy.isStable(name))
        }
    }
}

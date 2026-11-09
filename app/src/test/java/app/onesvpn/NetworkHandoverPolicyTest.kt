package app.onesvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkHandoverPolicyTest {
    private fun decide(
        caps: Boolean = true, links: Boolean = true,
        internet: Boolean = true, physical: Boolean = true,
        index: Int? = 7, blocked: Boolean = false
    ) = NetworkHandoverPolicy.decide(caps, links, internet, physical, index, blocked)

    @Test fun onAvailableDoesNotBindBeforeCapabilitiesAndLinkProperties() {
        assertEquals(NetworkHandoverPolicy.Action.WAIT, decide(caps = false, links = false))
        assertEquals(NetworkHandoverPolicy.Action.WAIT, decide(links = false))
        assertEquals(NetworkHandoverPolicy.Action.WAIT, decide(caps = false))
    }

    @Test fun usablePhysicalNetworkCanBeSelected() {
        assertEquals(NetworkHandoverPolicy.Action.USE, decide())
    }

    @Test fun invalidOrBlockedNetworkMustNotBeBound() {
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(physical = false))
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(internet = false))
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(index = null))
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(index = -1))
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(index = 0))
        assertEquals(NetworkHandoverPolicy.Action.CLEAR, decide(blocked = true))
    }

    @Test fun blockedEventClearsBindingEvenBeforeMetadataArrives() {
        assertEquals(NetworkHandoverPolicy.Action.CLEAR,
            decide(caps = false, links = true, blocked = true))
    }

    @Test fun incompleteUnblockedMetadataDoesNotPrematurelyClearOldRoute() {
        assertEquals(NetworkHandoverPolicy.Action.WAIT,
            decide(caps = false, links = true, blocked = false))
    }
}

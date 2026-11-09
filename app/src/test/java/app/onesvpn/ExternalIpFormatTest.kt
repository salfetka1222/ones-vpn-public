package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ExternalIpFormatTest {
    @Test fun correctAddressFamilyIsMandatory() {
        assertEquals("1.1.1.1", ExternalIpFormat.validate("1.1.1.1", false))
        assertEquals("2606:4700:4700::1111",
            ExternalIpFormat.validate("2606:4700:4700::1111", true))
        assertNull(ExternalIpFormat.validate("1.1.1.1", true))
        assertNull(ExternalIpFormat.validate("2606:4700:4700::1111", false))
    }

    @Test fun hostnamesInvalidOctetsAndResponsesAreRejected() {
        for (value in listOf("example.com", "1.2.3", "256.2.3.4",
                "127.0.0.1 extra", "<html>", "0.1.2.3:443", "")) {
            assertNull(value, ExternalIpFormat.validate(value, false))
        }
        for (value in listOf("www.example.com", "::1%", "1.2.3.4", "hello", "::ffff:192.0.2.1")) {
            assertNull(value, ExternalIpFormat.validate(value, true))
        }
    }

    @Test fun reservedIpv4IsNotMisrepresentedAsExternalPublicIp() {
        val reserved = listOf(
            "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.254",
            "127.0.0.1", "169.254.10.2", "172.16.0.1", "172.31.255.2",
            "192.168.1.1", "192.0.2.1", "192.0.0.2", "198.51.100.3",
            "198.18.0.1", "203.0.113.5", "224.0.0.1", "255.255.255.255"
        )
        for (value in reserved) assertNull(value, ExternalIpFormat.validate(value, false))
        assertEquals("8.8.8.8", ExternalIpFormat.validate("8.8.8.8", false))
        assertEquals("9.9.9.9", ExternalIpFormat.validate("9.9.9.9", false))
    }

    @Test fun ipv6LocalDocumentationAndMulticastAreRejected() {
        val reserved = listOf(
            "::", "::1", "fc00::1", "fd42::1", "fe80::1", "ff02::1",
            "2001:db8::1"
        )
        for (value in reserved) assertNull(value, ExternalIpFormat.validate(value, true))
        assertEquals("2001:4860:4860::8888",
            ExternalIpFormat.validate("2001:4860:4860::8888", true))
    }

    @Test fun redactedAddressesDoNotExposeFullIps() {
        assertEquals("203.0.•••.•••", ExternalIpFormat.masked("203.0.113.5"))
        assertEquals("2001:••••:••••", ExternalIpFormat.masked("2001:db8::1"))
    }

    @Test fun resultsInitiallyUnmeasuredAndFailClosed() {
        val initial = ExternalIpSnapshot()
        assertFalse(initial.inProgress)
        assertEquals(ExternalIpStatus.NOT_CHECKED, initial.ipv4.status)
        assertNull(initial.ipv6.address)
    }
}

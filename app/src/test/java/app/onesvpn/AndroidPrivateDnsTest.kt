package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class AndroidPrivateDnsTest {
    @Test fun recognizesAllDocumentedAndroidDnsModes() {
        assertEquals(PrivateDnsMode.OFF, PrivateDnsPolicy.classify("off", null))
        assertEquals(PrivateDnsMode.AUTOMATIC,
            PrivateDnsPolicy.classify("opportunistic", null))
        assertEquals(PrivateDnsMode.PROVIDER,
            PrivateDnsPolicy.classify("hostname", null))
    }

    @Test fun explicitSettingOverridesDefaultAndIgnoresStaleHostname() {
        assertEquals(PrivateDnsMode.AUTOMATIC,
            PrivateDnsPolicy.classify("opportunistic", "hostname"))
        assertEquals(PrivateDnsMode.OFF,
            PrivateDnsPolicy.classify("off", "hostname"))
    }

    @Test fun usesDefaultOnlyWhenExplicitValueMissing() {
        assertEquals(PrivateDnsMode.PROVIDER,
            PrivateDnsPolicy.classify(null, "hostname"))
        assertEquals(PrivateDnsMode.AUTOMATIC,
            PrivateDnsPolicy.classify("", "opportunistic"))
        assertEquals(PrivateDnsMode.OFF,
            PrivateDnsPolicy.classify("null", "off"))
    }

    @Test fun unrecognizedValuesCannotProduceFalseCertainty() {
        for (value in listOf("invalid", "strict", "true", "", "null")) {
            assertEquals(value, PrivateDnsMode.UNKNOWN, PrivateDnsPolicy.classify(value, null))
        }
        assertEquals(PrivateDnsMode.UNKNOWN, PrivateDnsPolicy.classify(null, null))
    }

    @Test fun everyStatusExplainsItIsNotEvidenceOfNoDnsLeak() {
        for (mode in PrivateDnsMode.entries) {
            val text = PrivateDnsPolicy.explanation(mode)
            assertTrue(text.contains("не проверка реального маршрута DNS"))
            assertTrue(text.contains("Браузер"))
        }
    }
}

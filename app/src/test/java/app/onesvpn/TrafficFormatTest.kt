package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class TrafficFormatTest {
    @Test fun zeroAndByteUnitsAreExact() {
        assertEquals("0 Б", TrafficFormat.bytes(0L))
        assertEquals("999 Б", TrafficFormat.bytes(999L))
        assertEquals("1.0 КиБ", TrafficFormat.bytes(1024L))
        assertEquals("1.5 МиБ", TrafficFormat.bytes(1_572_864L))
        assertEquals("2.0 ГиБ", TrafficFormat.bytes(2_147_483_648L))
        assertEquals("1.0 МиБ/с", TrafficFormat.rate(1_048_576L))
    }

    @Test fun invalidNegativeValuesAreClampedAndVeryLargeValuesDoNotOverflow() {
        assertEquals("0 Б", TrafficFormat.bytes(-5))
        assertEquals("0 Б/с", TrafficFormat.rate(-1L))
        assertTrue(TrafficFormat.bytes(Long.MAX_VALUE).contains("ЭиБ"))
    }

    @Test fun unavailableOrStaleTrafficNeverLooksFresh() {
        assertFalse(TrafficSnapshot().isFresh(12_000L))
        val snapshot = TrafficSnapshot(available = true, measuredAtElapsedMs = 12_000L)
        assertTrue(snapshot.isFresh(14_000L))
        assertTrue(snapshot.isFresh(16_000L))
        assertFalse(snapshot.isFresh(16_001L))
        assertFalse(snapshot.isFresh(11_000L))
        assertFalse(TrafficSnapshot(available = false, measuredAtElapsedMs = 15_000L).isFresh(15_000L))
    }
}

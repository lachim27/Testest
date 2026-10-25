package pl.zlapegzamin.test

import org.junit.Assert.*
import org.junit.Test

class BookingTimeRangeTest {
    @Test fun boundariesIncludeTheWholeMinute() {
        assertFalse(BookingTimeRange.includes("2026-11-05T09:59:59", "10:00", "14:30"))
        assertTrue(BookingTimeRange.includes("2026-11-05T10:00:00", "10:00", "14:30"))
        assertTrue(BookingTimeRange.includes("2026-11-05T14:30:59", "10:00", "14:30"))
        assertFalse(BookingTimeRange.includes("2026-11-05T14:31:00", "10:00", "14:30"))
    }
    @Test fun defaultRangeAndSingleMinute() {
        assertTrue(BookingTimeRange.includes("2026-11-05T00:00:00", "00:00", "23:59"))
        assertTrue(BookingTimeRange.includes("2026-11-05T23:59:59", "00:00", "23:59"))
        assertTrue(BookingTimeRange.includes("2026-11-05T12:15:00", "12:15", "12:15"))
        assertFalse(BookingTimeRange.includes("2026-11-05T12:16:00", "12:15", "12:15"))
    }
    @Test fun invalidInputsFailClosed() {
        for ((from, to) in listOf("15:00" to "10:00", "24:00" to "23:59",
                "9:00" to "17:00", "" to "", "10:60" to "11:00")) {
            assertFalse(BookingTimeRange.isValid(from, to))
            assertFalse(BookingTimeRange.includes("2026-11-05T12:00:00", from, to))
        }
        assertFalse(BookingTimeRange.includes("unknown", "00:00", "23:59"))
        assertFalse(BookingTimeRange.includes("2026-02-30T12:00:00", "00:00", "23:59"))
    }
    @Test fun explicitOffsetsUsePolishWinterAndSummerTime() {
        assertTrue(BookingTimeRange.includes("2026-11-05T09:00:00Z", "10:00", "10:00"))
        assertTrue(BookingTimeRange.includes("2026-07-05T08:00:00Z", "10:00", "10:00"))
        assertFalse(BookingTimeRange.includes("2026-07-05T09:00:00Z", "10:00", "10:00"))
    }
    @Test fun skipsEarlierNonMatchingCandidate() {
        val dates = listOf("2026-11-05T08:00:00", "2026-11-05T11:15:00", "2026-11-05T16:00:00")
        assertEquals("2026-11-05T11:15:00", dates.sorted().firstOrNull {
            BookingTimeRange.includes(it, "10:00", "14:30")
        })
        assertNull(dates.firstOrNull { BookingTimeRange.includes(it, "18:00", "19:00") })
    }
}

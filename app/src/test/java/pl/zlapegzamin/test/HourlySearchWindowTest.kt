package pl.zlapegzamin.test

import org.junit.Assert.*
import org.junit.Test

class HourlySearchWindowTest {
    private val start = 1_800_000_000_000L
    private val hour = HourlySearchWindow.HOUR

    @Test fun allFourSearchesExpireTogetherExactlyOneHourLater() {
        var window = HourlySearchWindow(0L, emptyList())
        for (minute in listOf(0, 15, 30, 45)) window = window.record(start + minute * 60_000L)
        assertEquals(start + hour, window.resetsAt)
        assertEquals(4, HourlySearchWindow.restore(window.startedAt, window.requests, start + hour - 1).requests.size)
        val reset = HourlySearchWindow.restore(window.startedAt, window.requests, start + hour)
        assertEquals(0, reset.requests.size)
        assertEquals(0L, reset.resetsAt)
        val next = reset.record(start + hour)
        assertEquals(1, next.requests.size)
        assertEquals(start + 2 * hour, next.resetsAt)
    }

    @Test fun restartAndExtraRequestsDoNotMoveTheReset() {
        var window = HourlySearchWindow(0L, emptyList()).record(start)
        for (i in 1..10) window = window.record(start + i * 60_000L)
        val restored = HourlySearchWindow.restore(window.startedAt, window.requests, start + 20 * 60_000L)
        assertEquals(11, restored.requests.size)
        assertEquals(start + hour, restored.resetsAt)
    }

    @Test fun upgradeUsesFirstLegacySearchAsCycleAnchor() {
        val legacy = listOf(start, start + 15 * 60_000L, start + 45 * 60_000L)
        val migrated = HourlySearchWindow.restore(0L, legacy, start + 50 * 60_000L)
        assertEquals(start + hour, migrated.resetsAt)
        assertEquals(3, migrated.requests.size)
        assertTrue(HourlySearchWindow.restore(0L, legacy, start + hour).requests.isEmpty())
    }

    @Test fun idleHoursDoNotCreatePhantomCycles() {
        val restored = HourlySearchWindow.restore(start, listOf(start), start + 5 * hour)
        assertEquals(0L, restored.startedAt)
        val next = restored.record(start + 5 * hour + 1234)
        assertEquals(start + 6 * hour + 1234, next.resetsAt)
    }

    @Test fun backwardsClockDoesNotGrantAnExtraBudget() {
        val restored = HourlySearchWindow.restore(start, listOf(start), start - 60_000)
        assertEquals(1, restored.requests.size)
        assertEquals(start + hour, restored.resetsAt)
    }
}

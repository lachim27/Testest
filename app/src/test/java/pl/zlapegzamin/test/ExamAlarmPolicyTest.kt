package pl.zlapegzamin.test

import org.junit.Assert.*
import org.junit.Test

class ExamAlarmPolicyTest {
    private val slots = mapOf("early" to "2026-11-02T08:00:00",
        "match" to "2026-11-02T11:00:00", "late" to "2026-11-02T17:00:00")
    @Test fun firstDiscoveryRingsOnlyForMatchingHours() {
        val matches = ExamAlarmPolicy.matching(slots, "10:00", "14:30")
        assertEquals(listOf("match"), ExamAlarmPolicy.newKeys(matches, emptySet()))
    }
    @Test fun repeatingTheSameListOrRestoringSavedKeysDoesNotRing() {
        val matches = ExamAlarmPolicy.matching(slots, "00:00", "23:59")
        assertTrue(ExamAlarmPolicy.newKeys(matches, matches.keys.toSet()).isEmpty())
    }
    @Test fun oneNewSlotProducesOneNewCandidate() {
        assertEquals(listOf("late"), ExamAlarmPolicy.newKeys(slots, setOf("early", "match")))
    }
    @Test fun emptyOrOutOfRangeDoesNotRing() {
        assertTrue(ExamAlarmPolicy.newKeys(emptyMap(), emptySet()).isEmpty())
        assertTrue(ExamAlarmPolicy.matching(slots, "18:00", "19:00").isEmpty())
    }
    @Test fun returningSlotIsNewAfterAnEmptyRead() {
        assertEquals(listOf("early", "match", "late"), ExamAlarmPolicy.newKeys(slots, emptySet()))
    }
}

package pl.zlapegzamin.test

internal object ExamAlarmPolicy {
    fun matching(current: Map<String, String>, from: String, to: String): Map<String, String> =
        current.filterValues { BookingTimeRange.includes(it, from, to) }

    // First discovery rings too. Persist the set so identical reads/restarts
    // do not ring again; a disappearing and later returning slot is new again.
    fun newKeys(current: Map<String, String>, previous: Set<String>): List<String> =
        current.entries.sortedBy { it.value }.filter { it.key !in previous }.map { it.key }
}

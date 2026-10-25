package pl.zlapegzamin.test

/** One whole budget expires 60 minutes after the first search in its cycle. */
data class HourlySearchWindow(val startedAt: Long, val requests: List<Long>) {
    val resetsAt: Long get() = if (startedAt > 0L) startedAt + HOUR else 0L

    fun record(now: Long): HourlySearchWindow {
        val current = restore(startedAt, requests, now)
        return HourlySearchWindow(
            if (current.startedAt == 0L) now else current.startedAt,
            current.requests + now
        )
    }

    companion object {
        const val HOUR = 3_600_000L

        fun restore(startedAt: Long, timestamps: List<Long>, now: Long): HourlySearchWindow {
            val entries = timestamps.filter { it > 0L }.sorted()
            // Older versions stored timestamps without a cycle anchor.
            val start = if (startedAt > 0L) startedAt else entries.firstOrNull() ?: 0L
            if (start == 0L || now >= start + HOUR) return HourlySearchWindow(0L, emptyList())
            return HourlySearchWindow(start, entries.filter { it >= start && it < start + HOUR })
        }
    }
}

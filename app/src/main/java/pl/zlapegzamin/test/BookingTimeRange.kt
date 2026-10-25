package pl.zlapegzamin.test

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Inclusive minute boundaries, in the exam centre's Polish time zone. */
object BookingTimeRange {
    private fun parse(value: String): LocalTime? = try {
        if (Regex("^[0-9]{2}:[0-9]{2}$").matches(value)) LocalTime.parse(value) else null
    } catch (_: Exception) { null }

    fun isValid(from: String, to: String): Boolean {
        val start = parse(from) ?: return false
        val end = parse(to) ?: return false
        return !start.isAfter(end)
    }

    fun includes(date: String, from: String, to: String): Boolean {
        if (!isValid(from, to)) return false
        val time = try {
            // Offset-bearing responses must not depend on the phone's time zone.
            OffsetDateTime.parse(date).atZoneSameInstant(ZoneId.of("Europe/Warsaw"))
                .toLocalTime()
        } catch (_: Exception) {
            try { LocalDateTime.parse(date).toLocalTime() }
            catch (_: Exception) { return false }
        }.truncatedTo(ChronoUnit.MINUTES)
        return !time.isBefore(parse(from)!!) && !time.isAfter(parse(to)!!)
    }
}

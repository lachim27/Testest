package pl.zlapegzamin.test

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Conservative, persistent local budget for the portal's search endpoint.
 * Not a bypass: server-side limits always take precedence.
 * Includes manual searches in the hourly budget, but only automatic searches
 * advance the cadence of the next automatic search.
 */
class RateGuard(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("zlap_rate_guard_v1", Context.MODE_PRIVATE)

    private val hour = 3_600_000L
    private val minSpacing = 15 * 60_000L
    private val safety = 45_000L
    private val maxRequestsPerHour = 4

    private fun requests(now: Long): MutableList<Long> =
        (prefs.getString("requests", "") ?: "")
            .split(",")
            .mapNotNull { it.toLongOrNull() }
            .filter { it > now - hour && it <= now + 60_000L }
            .sorted()
            .toMutableList()

    val blockedUntil: Long get() = prefs.getLong("blocked_until", 0L)
    val strikes: Int get() = prefs.getInt("strikes", 0)

    fun usedInHour(): Int = requests(System.currentTimeMillis()).size

    /**
     * All real searches count towards the shared hourly quota. Only real
     * automatic searches advance the 15-minute automatic timetable.
     * Manual and user-driven portal searches never reset that timer.
     */
    fun recordRequest(automatic: Boolean = false) {
        val now = System.currentTimeMillis()
        val list = requests(now)
        list.add(now)
        val editor = prefs.edit().putString("requests", list.joinToString(","))
            .putLong("last_request", now)
        if (automatic) editor.putLong("last_automatic_request", now)
        editor.apply()
    }

    fun nextScheduledAutomatic(chosenSeconds: Int): Long {
        val now = System.currentTimeMillis()
        val lastAuto = prefs.getLong("last_automatic_request", 0L)
        if (lastAuto <= 0L) return now
        return maxOf(now, lastAuto + maxOf(minSpacing, chosenSeconds * 1000L))
    }

    fun nextAllowed(chosenSeconds: Int): Long {
        val now = System.currentTimeMillis()
        val list = requests(now)
        var due = maxOf(nextScheduledAutomatic(chosenSeconds), blockedUntil)
        if (list.size >= maxRequestsPerHour) {
            due = maxOf(due, list[list.size - maxRequestsPerHour] + hour + safety)
        }
        return due
    }

    private fun parseRetryAfter(raw: String, now: Long): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { seconds ->
            if (seconds in 0..604800) return now + seconds * 1000
        }
        try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.isLenient = false
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(trimmed)?.time?.let { return it }
        } catch (_: Exception) { }
        try {
            val match = Regex("""(\d{2}\.\d{2}\.\d{4})\s*[, ]\s*(\d{2}:\d{2}:\d{2})""")
                .find(trimmed)
            if (match != null) {
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("pl", "PL"))
                date.isLenient = false
                date.parse(match.groupValues[1] + " " + match.groupValues[2])?.time?.let { return it }
            }
        } catch (_: Exception) { }
        return null
    }

    fun on429(retryAfter: String): Long {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_429", 0L) < 5000L) return blockedUntil
        val strikes = (this.strikes + 1).coerceAtMost(10)
        val fallback = now + (30L * 60_000L * (1L shl minOf(strikes - 1, 3)))
        val specified = parseRetryAfter(retryAfter, now)
        val due = maxOf(blockedUntil,
            if (specified != null && specified > now) specified + safety else fallback)
        prefs.edit().putLong("blocked_until", due).putLong("last_429", now)
            .putInt("strikes", strikes).apply()
        return due
    }

    fun resetAfterLongSuccess() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_429", 0L) >= 24 * hour) {
            prefs.edit().putInt("strikes", 0).apply()
        }
    }

    fun readableNext(chosenSeconds: Int): String =
        SimpleDateFormat("dd.MM HH:mm", Locale("pl", "PL")).format(Date(nextAllowed(chosenSeconds)))
}

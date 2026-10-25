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
    /** Factory default: 15 minutes. The user can change it in Options. */
    val minSpacingSeconds: Int get() =
        prefs.getInt("minimum_auto_gap_seconds", 900).coerceIn(10, 3600)
    private val minSpacing: Long get() = minSpacingSeconds * 1000L

    fun setMinimumSpacingSeconds(seconds: Int): Boolean {
        if (seconds !in 10..3600) return false
        prefs.edit().putInt("minimum_auto_gap_seconds", seconds).apply()
        return true
    }
    private val safety = 45_000L
    /** User-configurable local automatic search quota. Default remains 4/hour. */
    val maxRequestsPerHour: Int
        get() = prefs.getInt("max_requests_per_hour", 4).coerceAtLeast(1)

    fun setMaxRequestsPerHour(limit: Int): Boolean {
        if (limit <= 0) return false
        // Preserve historical request timestamps: changing the quota does
        // not wipe activity or circumvent an existing HTTP 429 Retry-After.
        return prefs.edit().putInt("max_requests_per_hour", limit).commit()
    }

    private fun window(now: Long): HourlySearchWindow {
        val raw = prefs.getString("requests", "").orEmpty()
        val start = prefs.getLong("quota_window_start", 0L)
        val current = HourlySearchWindow.restore(start,
            raw.split(",").mapNotNull { it.toLongOrNull() }, now)
        val timestamps = current.requests.joinToString(",")
        if (start != current.startedAt || raw != timestamps) {
            prefs.edit().putLong("quota_window_start", current.startedAt)
                .putString("requests", timestamps).apply()
        }
        return current
    }

    val blockedUntil: Long get() = prefs.getLong("blocked_until", 0L)
    val strikes: Int get() = prefs.getInt("strikes", 0)
    val lastAutomaticRequestAt: Long get() = prefs.getLong("last_automatic_request", 0L)

    /** All real searches in the current fixed 60-minute cycle. */
    fun usedInHour(): Int = window(System.currentTimeMillis()).requests.size

    /** The whole counter resets together, never one request at a time. */
    fun nextCountDecreaseAt(): Long = window(System.currentTimeMillis()).resetsAt
    fun allCountedRequestsExpireAt(): Long = nextCountDecreaseAt()

    fun nextQuotaSlotAt(): Long {
        val current = window(System.currentTimeMillis())
        return if (current.requests.size < maxRequestsPerHour) 0L else current.resetsAt
    }

    /**
     * All real searches count towards the shared hourly quota. Only real
     * automatic searches advance the configured automatic timetable.
     * Manual and user-driven portal searches never reset that timer.
     */
    fun recordRequest(automatic: Boolean = false) {
        val now = System.currentTimeMillis()
        val current = window(now).record(now)
        val editor = prefs.edit().putString("requests", current.requests.joinToString(","))
            .putLong("quota_window_start", current.startedAt)
            .putLong("last_request", now)
        if (automatic) editor.putLong("last_automatic_request", now)
        editor.apply()
    }

    /** Stable due time: never move a missed deadline forward just because the clock advances. */
    fun nextScheduledAutomatic(chosenSeconds: Int): Long {
        val lastAuto = prefs.getLong("last_automatic_request", 0L)
        if (lastAuto <= 0L) return 0L  // Ready for the first automatic request.
        return lastAuto + maxOf(minSpacing, chosenSeconds * 1000L)
    }

    fun nextAllowed(chosenSeconds: Int): Long {
        val now = System.currentTimeMillis()
        val current = window(now)
        var due = maxOf(nextScheduledAutomatic(chosenSeconds), blockedUntil)
        if (current.requests.size >= maxRequestsPerHour) {
            due = maxOf(due, current.resetsAt)
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

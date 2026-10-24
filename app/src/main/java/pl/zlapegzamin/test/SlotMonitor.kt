package pl.zlapegzamin.test

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Observes the user's authenticated exam requests inside the Activity's WebView.
 *  A foreground service can request a heartbeat while this WebView remains alive.
 *  It does not keep the Activity alive after Android destroys it.
 */
class SlotMonitor(
    private val activity: Activity,
    private val web: WebView,
    private val onUpdate: () -> Unit
) {
    private val prefs = activity.getSharedPreferences("slot_monitor_v1", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val rateGuard = RateGuard(activity)
    val intervalSeconds: Int get() = prefs.getInt("interval_seconds", 30)
    val minimumAutoGapSeconds: Int get() = rateGuard.minSpacingSeconds
    val effectiveAutoGapSeconds: Int get() = maxOf(intervalSeconds, minimumAutoGapSeconds, ((adaptiveMs + 999L) / 1000L).toInt())
    private val intervalMs: Long get() = intervalSeconds * 1000L
    private var adaptiveMs = prefs.getLong("adaptive_interval_ms", 0L).coerceAtLeast(0L)
    private val effectiveMs: Long get() = maxOf(intervalMs, adaptiveMs)
    private var lastNetworkActivity = 0L
    private var requestPending = false
    private var strikes = prefs.getInt("strikes", 0)
    private val hourMs = 60 * 60 * 1000L
    private val effectiveSeconds: Int get() = ((effectiveMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    private fun nextAllowed(): Long = maxOf(rateGuard.nextAllowed(effectiveSeconds), lastNetworkActivity + 15000L)
    var supported = false
        private set
    var requestReady = false
        private set
    var lastCheckedAt = prefs.getLong("last_checked_at", 0L)
        private set
    var lastError = ""
        private set
    var slotCount = prefs.getInt("slot_count", 0)
        private set
    var earliest = prefs.getString("earliest_slot", "") ?: ""
        private set
    var lastNewCount = 0
        private set
    var lastAttemptAt = 0L
        private set
    private var resumed = false
    private var destroyed = false
    private var lastReadyAt = 0L
    var diagnostics = "Oczekiwanie na skrypt strony..."
        private set
    private var hookDetected = false
    private var sessionAlertSent = prefs.getBoolean("session_alert_sent", false)
    val sessionExpired: Boolean get() = prefs.getBoolean("session_expired", false)
    private var monitorStartedAt = System.currentTimeMillis()
    private var lastStaleAlertAt = prefs.getLong("stale_alert_at", 0L)

    val dateFrom: String get() = prefs.getString("date_from", "") ?: ""
    val dateTo: String get() = prefs.getString("date_to", "") ?: ""
    fun setDateRange(from: String, to: String) {
        if (from.isNotBlank() && to.isNotBlank() && from > to) return
        prefs.edit().putString("date_from", from).putString("date_to", to).remove("last_ids").apply()
        onUpdate()
    }
    val enabled: Boolean get() = prefs.getBoolean("enabled", true)
    val backgroundSessionMaintenanceEnabled: Boolean
        get() = prefs.getBoolean("background_session_maintenance", true)
    fun setBackgroundSessionMaintenanceEnabled(value: Boolean) {
        prefs.edit().putBoolean("background_session_maintenance", value).apply()
        event(if (value) "Włączono podtrzymywanie sesji w tle"
            else "Wyłączono podtrzymywanie sesji w tle")
        onUpdate()
    }
    val notifyFirst: Boolean get() = prefs.getBoolean("notify_first", false)
    val autoReserveEnabled: Boolean get() = prefs.getBoolean("auto_reserve", false)
    val lastBookingStatus: String get() = prefs.getString("booking_status", "Nie próbowano") ?: "Nie próbowano"
    val lastBookingDate: String get() = prefs.getString("booking_date", "") ?: ""
    val lastReservationId: String get() = prefs.getString("booking_id", "") ?: ""
    private var bookingInFlight = false
    fun setAutoReserve(value: Boolean): Boolean {
        if (value) {
            if (dateFrom.isBlank() || dateTo.isBlank() || dateFrom > dateTo) return false
            try {
                val from = java.time.LocalDate.parse(dateFrom)
                val to = java.time.LocalDate.parse(dateTo)
                val today = java.time.LocalDate.now()
                if (to.isBefore(today) || from.isAfter(today.plusDays(30)) ||
                    java.time.temporal.ChronoUnit.DAYS.between(from, to) > 30) return false
            } catch (_: Exception) { return false }
        }
        prefs.edit().putBoolean("auto_reserve", value).apply()
        event(if (value) "Włączono tymczasową autorezerwację" else "Wyłączono autorezerwację")
        onUpdate()
        return true
    }
    val blockedUntil: Long get() = rateGuard.blockedUntil
    val searchesThisHour: Int get() = rateGuard.usedInHour()
    val nextCountDecreaseAt: Long get() = rateGuard.nextCountDecreaseAt()
    val allCountedRequestsExpireAt: Long get() = rateGuard.allCountedRequestsExpireAt()
    val nextQuotaSlotAt: Long get() = rateGuard.nextQuotaSlotAt()
    /** Planned auto time is intentionally unaffected by manual search requests. */
    val nextPlannedAutoAt: Long get() = rateGuard.nextScheduledAutomatic(effectiveSeconds)
    val lastAutomaticRequestAt: Long get() = rateGuard.lastAutomaticRequestAt
    val nextCheckAt: Long get() = nextAllowed()
    val lastAutoIssue: String get() = prefs.getString("last_auto_issue", "").orEmpty()
    val lastAutoIssueAt: Long get() = prefs.getLong("last_auto_issue_at", 0L)
    val lastBackgroundRefreshAt: Long get() = prefs.getLong("native_refresh_ok_at", 0L)
    val lastBackgroundRefreshStatus: String get() =
        prefs.getString("native_refresh_status", "").orEmpty()
    val recentSlots: List<String> get() = (prefs.getString("recent_slots", "") ?: "")
        .split("|").filter { it.isNotBlank() }.take(20)
    val eventHistory: List<String> get() = (prefs.getString("events", "") ?: "")
        .split("\n").filter { it.isNotBlank() }.take(12)
    private fun event(message: String) {
        val whenText = SimpleDateFormat("dd.MM HH:mm", Locale("pl", "PL")).format(Date())
        val entries = listOf(whenText + "  •  " + message) + eventHistory.take(11)
        prefs.edit().putString("events", entries.joinToString("\n")).apply()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            val now = System.currentTimeMillis()
            if (requestPending && now - lastAttemptAt > 90000L) requestPending = false
            // Native Handler keeps examining the portal session even when
            // WebView's JavaScript timers are throttled on a locked screen.
            if (enabled && supported && isPortal(web.url)) {
                pulseFromForegroundService()
            }
            val staleAfterMs = maxOf(15 * 60_000L, effectiveAutoGapSeconds.toLong() * 1000L + 5 * 60_000L)
            if (enabled && supported && now >= blockedUntil &&
                now - maxOf(lastCheckedAt, monitorStartedAt) > staleAfterMs &&
                now - lastStaleAlertAt > 60 * 60_000L) {
                lastStaleAlertAt = now
                prefs.edit().putLong("stale_alert_at", now).apply()
                val warning = "Brak udanego odczytu terminów od dłuższego czasu. Sprawdź sesję w Portalu."
                notify(warning, "ZłapEgzamin – monitor wymaga uwagi")
                sendTelegram("ZłapEgzamin: " + warning)
            }
            if (enabled && supported && requestReady && !sessionExpired &&
                now >= blockedUntil &&
                now >= nextAllowed() && !requestPending && isPortal(web.url)) {
                requestPending = true
                lastAttemptAt = now
                web.evaluateJavascript(
                    """(function() {
                      if (!window.__zlapCheckNow) return 'missing_hook';
                      return window.__zlapCheckNow() ? 'started' :
                        String(window.__zlapLastCheckReason || 'unknown');
                    })();"""
                ) { result ->
                    if (result != "\"started\"") {
                        autoCheckDidNotStart(result?.removeSurrounding("\"").orEmpty())
                    }
                }
            }
            handler.postDelayed(this, 15_000L)
        }
    }

    private fun autoCheckDidNotStart(reason: String) {
        requestPending = false
        val detail = when (reason) {
            "missing_hook" -> "Brak skryptu monitorowania w bieżącej stronie."
            "missing_request" -> "Strona utraciła zapamiętane zapytanie o terminy."
            "previous_check_running" -> "Poprzedni odczyt wciąż trwa."
            "portal_request_running" -> "Portal właśnie wysyła własne zapytanie."
            "cooldown_429" -> "Skrypt portalu nadal odczekuje po HTTP 429."
            "portal_recent_request" -> "Wymagany krótki odstęp od ostatniego zapytania portalu."
            "request_start_failed" -> "Nie można było rozpocząć zapytania z WebView."
            else -> "Nie udało się wywołać skryptu odczytu (WebView: " + reason.take(32) + ")."
        }
        val now = System.currentTimeMillis()
        val previous = prefs.getString("last_auto_issue", "").orEmpty()
        val previousAt = prefs.getLong("last_auto_issue_at", 0L)
        if (reason == "missing_hook" || reason == "missing_request") {
            requestReady = false
            lastError = detail + " Otwórz ponownie listę terminów w zakładce Portal."
        }
        if (previous != detail || now - previousAt >= 2 * 60_000L) {
            prefs.edit().putString("last_auto_issue", detail)
                .putLong("last_auto_issue_at", now).apply()
            diagnostics = "Automat: " + detail
            event("Automat: " + detail)
            onUpdate()
        }
    }

    fun install() {
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("exam_dates", "Nowe terminy egzaminów", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel("session_alerts", "Sesja i problemy monitorowania", NotificationManager.IMPORTANCE_HIGH)
        )

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            lastError = "Zaktualizuj Android System WebView, aby włączyć monitoring."
            onUpdate()
            return
        }
        try {
            val origins = setOf("https://info-kierowca.pl", "https://*.info-kierowca.pl")
            WebViewCompat.addWebMessageListener(
                web, "ZlapBridge", origins,
                WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, _ ->
                    val host = sourceOrigin.host?.lowercase(Locale.ROOT) ?: ""
                    if (isMainFrame && sourceOrigin.scheme == "https" &&
                        (host == "info-kierowca.pl" || host.endsWith(".info-kierowca.pl"))) {
                        val data = message.data
                        if (data != null && data.length <= 80000) {
                            activity.runOnUiThread { receive(data) }
                        }
                    }
                }
            )
            val script = activity.assets.open("slot-hook.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(web, script, origins)
            supported = true
            lastError = ""
        } catch (_: Exception) {
            lastError = "Monitor nie uruchomił się. Zaktualizuj Android System WebView."
        }
        onUpdate()
    }

    fun onPortalNavigation(url: String?) {
        if (url.isNullOrBlank()) return
        // Main-frame navigations replace the JS context. Check the actual page
        // text natively, rather than relying on a volatile JS login flag.
        inspectSessionState()
        if (!requestReady) return
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return
        if (host != "info-kierowca.pl" && !host.endsWith(".info-kierowca.pl")) return
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (Regex("login|logowanie|signin|sign-in|authorize|authentication").containsMatchIn(path)) {
            reportExpiredSession("Przekierowanie do logowania")
        }
    }

    fun inspectSessionState() {
        if (destroyed || !enabled || !isPortal(web.url)) return
        // This is an exact match for the logged-out screen shown by the portal.
        // It never submits a request or touches the user's authentication data.
        web.evaluateJavascript(
            """(function() {
              try {
                var text = (document.body && document.body.innerText || '').toLocaleLowerCase('pl-PL');
                return text.includes('wylogowano z powodu nieaktywności') ||
                       text.includes('sesja wygasła po braku aktywności') ? 'expired' : 'ok';
              } catch (_) { return 'unknown'; }
            })();"""
        ) { value ->
            if (value == "\"expired\"" && (requestReady || lastReadyAt > 0L ||
                    lastCheckedAt > 0L || prefs.getLong("authenticated_at", 0L) > 0L)) {
                reportExpiredSession("Wylogowano z powodu nieaktywności")
            }
        }
    }

    private fun reportExpiredSession(reason: String) {
        requestPending = false
        requestReady = false
        lastError = "Sesja wygasła ($reason). Zaloguj się ponownie."
        diagnostics = lastError
        prefs.edit().putBoolean("session_expired", true).apply()
        if (!sessionAlertSent && !prefs.getBoolean("session_alert_sent", false)) {
            event("Sesja wygasła – ponów logowanie")
            sessionAlertSent = true
            prefs.edit().putBoolean("session_alert_sent", true).apply()
            sendTelegram(lastError)
            notify(lastError, "ZłapEgzamin – sesja wygasła")
        }
        onUpdate()
    }

    /**
     * Run a session heartbeat from the native main thread. The foreground service
     * calls this even when Android throttles JavaScript timers after Home/lock.
     * A successful callback proves only that JS ran, not that the server extended
     * the login; actual authentication is verified by the refresh response.
     */
    fun pulseFromForegroundService(): Boolean {
        if (destroyed || !enabled || !supported || sessionExpired ||
            !requestReady || !isPortal(web.url)) return false
        web.evaluateJavascript(
            "Boolean(window.__zlapSessionTick && window.__zlapSessionTick());"
        ) { result ->
            if (!destroyed && result == "true" && requestReady && !sessionExpired) {
                prefs.edit().putLong("web_heartbeat_at", System.currentTimeMillis()).apply()
            }
        }
        inspectSessionState()
        return true
    }

    fun resume() {
        if (destroyed || resumed) return
        resumed = true
        handler.post(ticker)
        handler.postDelayed({ if (!destroyed) inspectSessionState() }, 800L)
    }

    fun pause() {
        resumed = false
        handler.removeCallbacks(ticker)
    }

    fun destroy() {
        pause()
        destroyed = true
    }

    /**
     * One manual check through the authenticated portal WebView. It bypasses only
     * the periodic scheduler (including its local 15-minute spacing / hourly
     * budget), but NEVER a server HTTP 429 / Retry-After lock.
     * The user must first open the exam results in the portal.
     */
    fun checkNow(onResult: (String) -> Unit) {
        if (destroyed || !supported) {
            onResult("Monitor nie jest gotowy. Sprawdź WebView i otwórz portal.")
            return
        }
        if (!enabled) {
            onResult("Najpierw włącz monitorowanie.")
            return
        }
        if (!requestReady || !isPortal(web.url)) {
            onResult("Najpierw zaloguj się w Portalu i wyświetl listę terminów.")
            return
        }
        if (requestPending) {
            onResult("Poprzedni odczyt nadal trwa.")
            return
        }
        val now = System.currentTimeMillis()
        // The manual button skips only our 15-minute / 4-per-hour automatic
        // scheduler. A server HTTP 429 / Retry-After lock is never bypassed.
        if (now < blockedUntil) {
            onResult("HTTP 429: portal wstrzymał odczyty do " + clock(blockedUntil) + ".")
            return
        }
        requestPending = true
        lastAttemptAt = now
        web.evaluateJavascript(
            "Boolean(window.__zlapCheckNow && window.__zlapCheckNow('manual'));"
        ) { value ->
            if (value == "true") {
                event("Ręczne sprawdzenie uruchomione")
                onResult("Wysłano jedno zapytanie. Wynik pojawi się po odpowiedzi portalu.")
            } else {
                requestPending = false
                // The saved authenticated request might have expired or the page
                // may still be busy; never silently claim a successful check.
                onResult("Portal nie rozpoczął odczytu. Otwórz listę terminów i spróbuj ponownie.")
            }
            onUpdate()
        }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (!value) requestPending = false
        event(if (value) "Włączono monitorowanie" else "Wstrzymano monitorowanie")
        onUpdate()
    }

    fun setInterval(seconds: Int) {
        if (seconds !in 10..3600) return
        prefs.edit().putInt("interval_seconds", seconds).apply()
        if (adaptiveMs < intervalMs) adaptiveMs = intervalMs
        onUpdate()
    }

    fun setMinimumAutoGap(seconds: Int): Boolean {
        if (!rateGuard.setMinimumSpacingSeconds(seconds)) return false
        event("Minimalny odstęp automatu: " + seconds + " sekund")
        onUpdate()
        return true
    }

    fun setNotifyFirst(value: Boolean) {
        prefs.edit().putBoolean("notify_first", value).apply()
        onUpdate()
    }

    fun status(): String {
        val now = System.currentTimeMillis()
        return when {
            prefs.getLong("booking_confirmed_at", 0L) > 0L &&
                lastBookingStatus.startsWith("Miejsce zarezerwowane") ->
                lastBookingStatus + ": " + lastBookingDate.replace('T', ' ').take(16)
            !supported -> lastError.ifEmpty { "Brak obsługi odczytu terminów w WebView." }
            !enabled -> "Monitorowanie wyłączone."
            sessionExpired -> "Sesja portalu wygasła. Zaloguj się ponownie w zakładce Portal."
            now < blockedUntil -> "Wstrzymano po limicie HTTP 429 do " + clock(blockedUntil)
            !requestReady -> diagnostics
            lastError.isNotEmpty() -> lastError
            lastCheckedAt == 0L -> "Połączono z listą egzaminów. Oczekiwanie na pierwszy odczyt."
            else -> "Ostatni odczyt: " + clock(lastCheckedAt) +
                ". Zapytania z ostatnich 60 min: " + searchesThisHour + " (próg automatu: 4). " +
                "Następny automat: " + (if (nextPlannedAutoAt == 0L) "gotowy do startu" else clock(nextPlannedAutoAt)) +
                (if (nextPlannedAutoAt > 0L && nextPlannedAutoAt <= now)
                    " (termin minął – czeka na możliwość wykonania)" else "") +
                ". Minimalny odstęp: " + minimumAutoGapSeconds + " s."
        }
    }

    private fun receive(raw: String) {
        if (destroyed) return
        try {
            val message = JSONObject(raw)
            when (message.optString("kind")) {
                "hook" -> { hookDetected = true; diagnostics = "Skrypt działa. Otwórz listę terminów egzaminu w portalu." }
                "candidate" -> { if (!requestReady) diagnostics = "Wykryto ścieżkę API: " + message.optString("path").take(100) + ". Sprawdzanie zgodności..." }
                "endpoint" -> { lastNetworkActivity = System.currentTimeMillis(); diagnostics = "Wykryto zapytanie o terminy. Analiza wyboru ośrodka i kategorii..." }
                "body" -> diagnostics = "Portal wysłał zapytanie w innym formacie (nie JSON)."
                "filter" -> diagnostics = "Wykryto API terminów, ale nie pasuje identyfikator ośrodka lub kategorii. Pola: " +
                    message.optJSONArray("fields")?.toString().orEmpty().take(120)
                "request" -> {
                    val automatic = message.optString("source") == "automatic"
                    rateGuard.recordRequest(automatic = automatic)
                    if (automatic) {
                        prefs.edit().remove("last_auto_issue")
                            .remove("last_auto_issue_at").apply()
                        event("Wysłano automatyczne zapytanie do portalu")
                    }
                    lastAttemptAt = System.currentTimeMillis()
                    diagnostics = (if (automatic) "Automatyczne" else "Ręczne lub portalowe") +
                        " zapytanie. Ostatnie 60 min: " + rateGuard.usedInHour() + " (próg automatu 4)."
                }
                "response" -> { requestPending = false; lastNetworkActivity = System.currentTimeMillis(); diagnostics = "Odpowiedź API: HTTP " + message.optInt("status") }
                "ready" -> {
                    requestReady = true
                    lastReadyAt = System.currentTimeMillis()
                    prefs.edit()
                        .putLong("authenticated_at", lastReadyAt)
                        .putLong("web_heartbeat_at", lastReadyAt)
                        .putLong("verified_portal_at", lastReadyAt)
                        .putBoolean("session_expired", false)
                        .putInt("native_refresh_failures", 0)
                        .putBoolean("native_refresh_issue_alerted", false).apply()
                    sessionAlertSent = false
                    prefs.edit().putBoolean("session_alert_sent", false).apply()
                    lastError = ""
                    diagnostics = "Zidentyfikowano zapytanie MORD Kraków, B. Oczekiwanie na odpowiedź."
                }
                "terms" -> consume(message)
                "auth_alive" -> {
                    if (requestReady && !sessionExpired) {
                        prefs.edit().putLong("verified_portal_at",
                            System.currentTimeMillis()).apply()
                        diagnostics = "Portal potwierdził odnowienie uwierzytelnienia."
                    }
                }
                "booking_started" -> {
                    bookingInFlight = true
                    diagnostics = "Próba zajęcia terminu: " + message.optString("date").take(24)
                    event("Rozpoczęto zajmowanie miejsca")
                }
                "booking_pending" -> {
                    val id = message.optString("reservationId").take(90)
                    prefs.edit().putString("booking_id", id)
                        .putString("booking_status", "Utworzona – trwa potwierdzanie")
                        .apply()
                    diagnostics = "Utworzono rezerwację, sprawdzam czy miejsce zostało zablokowane."
                }
                "booking_success" -> bookingSucceeded(message)
                "booking_failed" -> bookingFailed(message)
                "rate" -> {
                    requestPending = false
                    val previousBlock = blockedUntil
                    val until = rateGuard.on429(message.optString("retryAfter"))
                    strikes = rateGuard.strikes
                    adaptiveMs = minOf(4 * hourMs, maxOf(120000L, maxOf(intervalMs * 2L, adaptiveMs * 2L)))
                    prefs.edit().putInt("strikes", strikes)
                        .putLong("adaptive_interval_ms", adaptiveMs).apply()
                    lastError = "HTTP 429 — portal ograniczył zapytania. Następna próba najwcześniej " + clock(until) + "."
                    diagnostics = "Wstrzymano sprawdzanie zgodnie z limitem portalu."
                    if (until > previousBlock) {
                        event("HTTP 429 – przerwa do " + clock(until))
                        notify(lastError, "ZłapEgzamin – limit zapytań")
                        sendTelegram("ZłapEgzamin: " + lastError)
                    }
                }
                "auth" -> {
                    val reason = if (message.optString("reason") == "idle")
                        "Wylogowano z powodu nieaktywności"
                    else "HTTP " + message.optInt("status") + " lub ekran logowania"
                    reportExpiredSession(reason)
                }
                "extended" -> {
                    event("Kliknięto w portalu: Przedłuż sesję (oczekiwanie na potwierdzenie)")
                    diagnostics = "Wysłano kliknięcie Przedłuż sesję. Sprawdzam, czy portal je zaakceptował."
                }
                "http" -> { requestPending = false; adaptiveMs = minOf(4 * hourMs, maxOf(120000L, effectiveMs * 2)); prefs.edit().putLong("adaptive_interval_ms", adaptiveMs).apply(); lastError = "Odczyt nieudany: HTTP " + message.optInt("status") }
                "network" -> { requestPending = false; lastError = "Błąd sieci podczas sprawdzania terminów." }
                "format" -> lastError = "Nieznany format odpowiedzi. Pola: " +
                    message.optJSONArray("fields")?.toString().orEmpty().take(120)
            }
            onUpdate()
        } catch (_: Exception) {
            lastError = "Błąd odczytu danych terminów."
            onUpdate()
        }
    }

    private fun consume(message: JSONObject) {
        requestPending = false
        if (adaptiveMs > intervalMs) {
            adaptiveMs = maxOf(intervalMs, (adaptiveMs * 0.85).toLong())
            prefs.edit().putLong("adaptive_interval_ms", adaptiveMs).apply()
        }
        val array = message.optJSONArray("terms") ?: return
        val current = linkedMapOf<String, String>()
        for (i in 0 until minOf(array.length(), 300)) {
            val e = array.optJSONObject(i) ?: continue
            val id = e.optString("id").take(90)
            val date = e.optString("date").take(32)
            val places = e.optInt("places")
            val day = date.take(10)
            if ((dateFrom.isNotBlank() && day < dateFrom) || (dateTo.isNotBlank() && day > dateTo)) continue
            if (id.isBlank() || places <= 0 ||
                !Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""").containsMatchIn(date)) continue
            current[id + "|" + date] = date
        }
        val previous = prefs.getStringSet("last_ids", null)?.toSet()
        val newItems = if (previous == null) {
            if (notifyFirst) current.keys.toList() else emptyList()
        } else current.keys.filterNot { it in previous }

        val sorted = current.values.sorted()
        earliest = sorted.firstOrNull()?.replace('T', ' ')?.take(16) ?: ""
        slotCount = current.size
        lastCheckedAt = System.currentTimeMillis()
        prefs.edit().remove("last_auto_issue").remove("last_auto_issue_at").apply()
        prefs.edit()
            .putBoolean("session_expired", false)
            .putLong("verified_portal_at", lastCheckedAt).apply()
        rateGuard.resetAfterLongSuccess()
        sessionAlertSent = false
        prefs.edit().putBoolean("session_alert_sent", false).apply()
        lastNewCount = newItems.size
        lastError = ""
        diagnostics = "Poprawny odczyt: " + slotCount + " terminów."
        prefs.edit()
            .putStringSet("last_ids", current.keys.toSet())
            .putString("earliest_slot", earliest)
            .putInt("slot_count", slotCount)
            .putString("recent_slots", sorted.distinct().take(20).joinToString("|"))
            .putLong("last_checked_at", lastCheckedAt)
            .apply()

        event("Odczyt: " + slotCount + " terminów" + if (newItems.isNotEmpty()) " (" + lastNewCount + " nowych)" else "")
        if (enabled && autoReserveEnabled) attemptBooking(current)
        if (enabled && newItems.isNotEmpty()) {
            val date = current[newItems.first()]?.replace('T', ' ')?.take(16) ?: ""
            val body = if (newItems.size == 1) {
                "MORD Kraków, kat. B – nowy termin: " + date
            } else {
                "MORD Kraków, kat. B – nowe terminy: " + newItems.size +
                    ". Najbliższy nowy: " + date
            }
            notify(body)
            sendTelegram(body)
        }
    }

    private fun attemptBooking(available: Map<String, String>) {
        if (!autoReserveEnabled || bookingInFlight || !requestReady || sessionExpired ||
            !isPortal(web.url) || System.currentTimeMillis() < blockedUntil) return
        val now = System.currentTimeMillis()
        val lastKey = prefs.getString("last_booking_key", "") ?: ""
        val lastWhen = prefs.getLong("last_booking_attempt_at", 0L)
        val candidate = available.entries.sortedBy { it.value }.firstOrNull { (key, date) ->
            val day = date.take(10)
            day >= java.time.LocalDate.now().toString() &&
                (key != lastKey || now - lastWhen >= 45 * 60_000L)
        } ?: return
        val separator = candidate.key.indexOf('|')
        if (separator < 1) return
        val practiceId = candidate.key.take(separator)
        val date = candidate.value
        if (!Regex("^[A-Za-z0-9_-]{1,90}$").matches(practiceId)) {
            diagnostics = "Nieobsługiwany identyfikator terminu – bez autorezerwacji."
            return
        }
        bookingInFlight = true
        prefs.edit().putString("last_booking_key", candidate.key)
            .putLong("last_booking_attempt_at", now)
            .putString("booking_status", "Trwa próba zarezerwowania")
            .putString("booking_date", date)
            .remove("booking_id")
            .apply()
        web.evaluateJavascript(
            "Boolean(window.__zlapBook && window.__zlapBook(" +
                JSONObject.quote(practiceId) + "," + JSONObject.quote(date) + "));"
        ) { result ->
            if (result != "true") {
                bookingInFlight = false
                val info = "Portal nie rozpoczął rezerwacji. Sprawdź sesję i zgodność terminu."
                prefs.edit().putString("booking_status", info).apply()
                diagnostics = info
                event("Nie udało się rozpocząć autorezerwacji")
                onUpdate()
            } else {
                event("Wysłano próbę autorezerwacji " + date.take(16))
                onUpdate()
            }
        }
    }

    private fun bookingSucceeded(message: JSONObject) {
        if (!bookingInFlight) return
        val reservationId = message.optString("reservationId").take(90)
        val date = message.optString("date").take(32)
        if (!Regex("^[A-Za-z0-9_-]{1,90}$").matches(reservationId)) return
        bookingInFlight = false
        val now = System.currentTimeMillis()
        prefs.edit().putBoolean("auto_reserve", false)
            .putBoolean("enabled", false)
            .putString("booking_id", reservationId)
            .putString("booking_date", date)
            .putString("booking_status", "Miejsce zarezerwowane – opłać w portalu")
            .putLong("booking_confirmed_at", now)
            .apply()
        val body = "Zajęto termin MORD Kraków, B: " + date.replace('T',' ').take(16) +
            ". Miejsce jest tymczasowo zarezerwowane. Zaloguj się i opłać " +
            "w ciągu ok. 30 minut. ID: " + reservationId
        diagnostics = "Serwer potwierdził " + message.optString("status") + "."
        event("Zajęto miejsce, ID " + reservationId.take(16))
        notify(body, "ZłapEgzamin – zarezerwowano miejsce!")
        sendTelegram("ZłapEgzamin: " + body)
        activity.stopService(Intent(activity, MonitorForegroundService::class.java))
    }

    private fun bookingFailed(message: JSONObject) {
        if (!bookingInFlight) return
        bookingInFlight = false
        val id = message.optString("reservationId").take(90)
        val date = message.optString("date").take(32)
        val reason = message.optString("reason").take(180)
        val change = prefs.edit().putString("booking_status", "Niepotwierdzona: " + reason)
            .putString("booking_date", date).putString("booking_id", id)
        // A server reservation ID can already represent a pending hold. Never
        // attempt another booking until the user manually verifies that state.
        if (id.isNotBlank()) change.putBoolean("auto_reserve", false)
        change.apply()
        diagnostics = "Nie udało się potwierdzić zajęcia miejsca. " + reason
        event("Nieudana autorezerwacja: " + reason)
        val body = "Próba rezerwacji " + date.replace('T', ' ').take(16) +
            " nie została potwierdzona. Sprawdź portal" +
            if (id.isNotBlank()) " (automatyczne próby wstrzymane). " + reason
            else ". " + reason
        notify(body, "ZłapEgzamin – sprawdź rezerwację")
        sendTelegram("ZłapEgzamin: " + body)
    }

    fun configureTelegram(token: String, chatId: String) {
        // Empty text fields mean "leave existing value unchanged", never silently erase credentials.
        val oldToken = prefs.getString("tg_token", "") ?: ""
        val oldChat = prefs.getString("tg_chat", "") ?: ""
        prefs.edit()
            .putString("tg_token", token.trim().ifBlank { oldToken })
            .putString("tg_chat", chatId.trim().ifBlank { oldChat })
            .apply()
        event("Zapisano ustawienia powiadomień Telegram")
        onUpdate()
    }
    fun telegramConfigured(): Boolean = !prefs.getString("tg_token", "").isNullOrBlank() && !prefs.getString("tg_chat", "").isNullOrBlank()
    private fun sendTelegram(message: String) {
        val token = prefs.getString("tg_token", "") ?: ""
        val chat = prefs.getString("tg_chat", "") ?: ""
        if (token.isBlank() || chat.isBlank()) return
        thread(name = "telegram-alert") {
            try {
                val url = URL("https://api.telegram.org/bot" + token + "/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                val data = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(message, "UTF-8")
                conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) activity.runOnUiThread { lastError = "Telegram HTTP " + code; onUpdate() }
            } catch (_: Exception) { activity.runOnUiThread { lastError = "Błąd połączenia Telegram."; onUpdate() } }
        }
    }
    fun testTelegram() { sendTelegram("ZłapEgzamin Android: test Telegram (to nie jest wolny termin).") }

    fun testNotification() {
        notify("To jest test powiadomienia ZłapEgzamin, nie rzeczywisty termin egzaminu.")
    }

    private fun notify(body: String, title: String = "ZłapEgzamin – nowy wolny termin") {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Włącz powiadomienia ZłapEgzamin w ustawieniach Androida."
            return
        }
        val intent = Intent(activity, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_portal", true)
        }
        val pending = PendingIntent.getActivity(
            activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isSessionAlert = title.contains("sesja", ignoreCase = true) ||
            title.contains("monitor wymaga", ignoreCase = true) ||
            title.contains("limit zapytań", ignoreCase = true)
        val channel = if (isSessionAlert) "session_alerts" else "exam_dates"
        val note = Notification.Builder(activity, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(if (isSessionAlert) 304 else 303, note)
    }

    private fun clock(at: Long): String =
        SimpleDateFormat("HH:mm", Locale("pl", "PL")).format(Date(at))

    private fun isPortal(value: String?): Boolean {
        val uri = Uri.parse(value ?: "")
        val host = uri.host?.lowercase(Locale.ROOT) ?: ""
        return uri.scheme == "https" &&
            (host == "info-kierowca.pl" || host.endsWith(".info-kierowca.pl"))
    }
}

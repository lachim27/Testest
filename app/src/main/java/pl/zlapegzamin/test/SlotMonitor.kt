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

/** Observes only the user's authenticated OneCenterExam requests inside WebView.
 *  Polling works while the activity is on screen, not as a background service.
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
    private val intervalMs: Long get() = intervalSeconds * 1000L
    private var adaptiveMs = prefs.getLong("adaptive_interval_ms", 0L).coerceAtLeast(0L)
    private val effectiveMs: Long get() = maxOf(intervalMs, adaptiveMs)
    private var lastNetworkActivity = 0L
    private var requestPending = false
    private var nextJitterMs = 0L
    private var strikes = prefs.getInt("strikes", 0)
    private val hourMs = 60 * 60 * 1000L
    private fun nextAllowed(): Long = maxOf(rateGuard.nextAllowed(intervalSeconds), lastAttemptAt + effectiveMs + nextJitterMs, lastNetworkActivity + 15000L)
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
    val notifyFirst: Boolean get() = prefs.getBoolean("notify_first", false)
    val blockedUntil: Long get() = rateGuard.blockedUntil
    val searchesThisHour: Int get() = rateGuard.usedInHour()
    val nextCheckAt: Long get() = nextAllowed()
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
                web.evaluateJavascript("Boolean(window.__zlapSessionTick && window.__zlapSessionTick());") { result ->
                    if (result == "true" && requestReady && !sessionExpired) {
                        prefs.edit().putLong("web_heartbeat_at", System.currentTimeMillis()).apply()
                    }
                }
                inspectSessionState()
            }
            if (enabled && supported && now >= blockedUntil && now - maxOf(lastCheckedAt, monitorStartedAt) > 15 * 60_000L && now - lastStaleAlertAt > 60 * 60_000L) {
                lastStaleAlertAt = now
                prefs.edit().putLong("stale_alert_at", now).apply()
                val warning = "Brak udanego odczytu terminów od ponad 15 minut. Sprawdź sesję w Portalu."
                notify(warning, "ZłapEgzamin – monitor wymaga uwagi")
                sendTelegram("ZłapEgzamin: " + warning)
            }
            if (enabled && supported && requestReady && now >= blockedUntil &&
                now >= nextAllowed() && !requestPending && isPortal(web.url)) {
                requestPending = true
                nextJitterMs = (0L..minOf(15000L, effectiveMs / 10L)).random()
                lastAttemptAt = now
                web.evaluateJavascript("window.__zlapCheckNow && window.__zlapCheckNow();") { result ->
                    if (result != "true") requestPending = false
                }
            }
            handler.postDelayed(this, 15_000L)
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
        if (!sessionAlertSent) {
            event("Sesja wygasła – ponów logowanie")
            sessionAlertSent = true
            prefs.edit().putBoolean("session_alert_sent", true).apply()
            sendTelegram(lastError)
            notify(lastError, "ZłapEgzamin – sesja wygasła")
        }
        onUpdate()
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
        nextJitterMs = 0L
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

    fun setNotifyFirst(value: Boolean) {
        prefs.edit().putBoolean("notify_first", value).apply()
        onUpdate()
    }

    fun status(): String {
        val now = System.currentTimeMillis()
        return when {
            !supported -> lastError.ifEmpty { "Brak obsługi odczytu terminów w WebView." }
            !enabled -> "Monitorowanie wyłączone."
            sessionExpired -> "Sesja portalu wygasła. Zaloguj się ponownie w zakładce Portal."
            now < blockedUntil -> "Wstrzymano po limicie HTTP 429 do " + clock(blockedUntil)
            !requestReady -> diagnostics
            lastError.isNotEmpty() -> lastError
            lastCheckedAt == 0L -> "Połączono z listą egzaminów. Oczekiwanie na pierwszy odczyt."
            else -> "Ostatni odczyt: " + clock(lastCheckedAt) +
                ". Wykorzystano " + searchesThisHour + "/4 zapytania/h. Kolejna próba: " + clock(nextAllowed()) +
                ". Wybrany interwał: " + intervalSeconds + " s (minimalna przerwa 15 minut)."
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
                "request" -> { rateGuard.recordRequest(); lastAttemptAt = System.currentTimeMillis(); diagnostics = "Zapytanie do portalu; wykorzystano " + rateGuard.usedInHour() + "/4 próby w tej godzinie." }
                "response" -> { requestPending = false; lastNetworkActivity = System.currentTimeMillis(); diagnostics = "Odpowiedź API: HTTP " + message.optInt("status") }
                "ready" -> {
                    requestReady = true
                    lastReadyAt = System.currentTimeMillis()
                    prefs.edit()
                        .putLong("authenticated_at", lastReadyAt)
                        .putLong("web_heartbeat_at", lastReadyAt)
                        .putBoolean("session_expired", false).apply()
                    lastAttemptAt = lastReadyAt - effectiveMs
                    sessionAlertSent = false
                    prefs.edit().putBoolean("session_alert_sent", false).apply()
                    lastError = ""
                    diagnostics = "Zidentyfikowano zapytanie MORD Kraków, B. Oczekiwanie na odpowiedź."
                }
                "terms" -> consume(message)
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
        prefs.edit().putBoolean("session_expired", false).apply()
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

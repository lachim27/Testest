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
    val intervalSeconds: Int get() = prefs.getInt("interval_seconds", 30)
    private val intervalMs: Long get() = intervalSeconds * 1000L
    private var adaptiveMs = prefs.getLong("adaptive_interval_ms", 0L).coerceAtLeast(0L)
    private val effectiveMs: Long get() = maxOf(intervalMs, adaptiveMs)
    private var lastNetworkActivity = 0L
    private var requestPending = false
    private var nextJitterMs = 0L
    private var strikes = prefs.getInt("strikes", 0)
    private val hourMs = 60 * 60 * 1000L
    private fun nextAllowed(): Long = maxOf(blockedUntil, lastAttemptAt + effectiveMs + nextJitterMs, lastNetworkActivity + 15000L)
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
    private var sessionAlertSent = false

    val dateFrom: String get() = prefs.getString("date_from", "") ?: ""
    val dateTo: String get() = prefs.getString("date_to", "") ?: ""
    fun setDateRange(from: String, to: String) {
        if (from.isNotBlank() && to.isNotBlank() && from > to) return
        prefs.edit().putString("date_from", from).putString("date_to", to).remove("last_ids").apply()
        onUpdate()
    }
    val enabled: Boolean get() = prefs.getBoolean("enabled", true)
    val notifyFirst: Boolean get() = prefs.getBoolean("notify_first", false)
    val blockedUntil: Long get() = prefs.getLong("blocked_until", 0L)

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || destroyed) return
            val now = System.currentTimeMillis()
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

    fun resume() {
        if (destroyed || resumed) return
        resumed = true
        handler.post(ticker)
    }

    fun pause() {
        resumed = false
        handler.removeCallbacks(ticker)
    }

    fun destroy() {
        pause()
        destroyed = true
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (value) lastAttemptAt = System.currentTimeMillis()
        else requestPending = false
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
            now < blockedUntil -> "Wstrzymano po limicie HTTP 429 do " + clock(blockedUntil)
            !requestReady -> diagnostics
            lastError.isNotEmpty() -> lastError
            lastCheckedAt == 0L -> "Połączono z listą egzaminów. Oczekiwanie na pierwszy odczyt."
            else -> "Monitoring włączony. Ostatni odczyt: " + clock(lastCheckedAt) +
                ". Interwał efektywny: " + (effectiveMs / 1000L) + " s. Kolejny odczyt po: " + clock(nextAllowed()) + "."
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
                "response" -> { requestPending = false; lastNetworkActivity = System.currentTimeMillis(); diagnostics = "Odpowiedź API: HTTP " + message.optInt("status") }
                "ready" -> {
                    requestReady = true
                    lastReadyAt = System.currentTimeMillis()
                    lastAttemptAt = lastReadyAt - effectiveMs
                    sessionAlertSent = false
                    lastError = ""
                    diagnostics = "Zidentyfikowano zapytanie MORD Kraków, B. Oczekiwanie na odpowiedź."
                }
                "terms" -> consume(message)
                "rate" -> {
                    requestPending = false
                    val retry = message.optString("retryAfter").trim()
                    val now = System.currentTimeMillis()
                    val retryMs = retry.toLongOrNull()?.takeIf { it >= 0 }?.let {
                        (it.coerceAtMost(86400L) * 1000L)
                    } ?: try {
                        val parsed = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                            isLenient = false
                        }.parse(retry)
                        parsed?.time?.minus(now)?.coerceAtLeast(0L)
                    } catch (_: Exception) { null }
                    strikes = minOf(10, strikes + 1)
                    adaptiveMs = minOf(4 * hourMs, maxOf(120000L, maxOf(intervalMs * 2L, adaptiveMs * 2L)))
                    val fallback = minOf(4 * hourMs, 5 * 60_000L * (1L shl minOf(5, strikes - 1)))
                    val waitMs = maxOf(fallback, retryMs ?: 0L)
                    val until = maxOf(blockedUntil, now + waitMs)
                    prefs.edit().putInt("strikes", strikes)
                        .putLong("adaptive_interval_ms", adaptiveMs)
                        .putLong("blocked_until", until).apply()
                    lastError = "Portal ograniczył liczbę żądań (HTTP 429)."
                }
                "auth" -> {
                    requestPending = false
                    requestReady = false
                    lastError = "Sesja wygasła (HTTP " + message.optInt("status") + "). Zaloguj się ponownie."
                    if (!sessionAlertSent) {
                        sessionAlertSent = true
                        sendTelegram(lastError)
                        notify(lastError, "ZłapEgzamin – sesja wygasła")
                    }
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
        lastNewCount = newItems.size
        lastError = ""
        diagnostics = "Poprawny odczyt: " + slotCount + " terminów."
        prefs.edit()
            .putStringSet("last_ids", current.keys.toSet())
            .putString("earliest_slot", earliest)
            .putInt("slot_count", slotCount)
            .putLong("last_checked_at", lastCheckedAt)
            .apply()

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
        prefs.edit().putString("tg_token", token.trim()).putString("tg_chat", chatId.trim()).apply()
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
        val note = Notification.Builder(activity, "exam_dates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(303, note)
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

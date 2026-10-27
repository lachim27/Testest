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
    init {
        // Versions up to 0.21 saved the experimental form-helper toggle as false.
        // Upgrade it ON once for users installing the dedicated auto-start fix.
        // Future explicit opt-outs remain respected.
        if (!prefs.getBoolean("portal_form_preparation_migrated_v22", false)) {
            prefs.edit()
                .putBoolean("portal_form_preparation_migrated_v22", true)
                .putBoolean("portal_form_preparation", true)
                .apply()
        }
    }
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
    private var nativeRequest: NativeExamReader.Request? = null
    private var nativeReadBusy = false
    private var nativeRequestVersion = 0L
    private var lastNativeExportAt = 0L
    private var nativeSetupIssue = "Brak zapytania do odczytu w tle. Otwórz listę terminów w Portalu."
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
    val bookingTimeFrom: String get() = prefs.getString("booking_time_from", "00:00") ?: "00:00"
    val bookingTimeTo: String get() = prefs.getString("booking_time_to", "23:59") ?: "23:59"
    fun setBookingTimeRange(from: String, to: String): Boolean {
        // A submitted reservation cannot be changed by editing preferences.
        if (bookingInFlight || !BookingTimeRange.isValid(from, to)) return false
        prefs.edit().putString("booking_time_from", from)
            .putString("booking_time_to", to).apply()
        event("Godziny egzaminu dla autorezerwacji: $from–$to")
        onUpdate()
        return true
    }
    val enabled: Boolean get() = prefs.getBoolean("enabled", true)
    val portalFormPreparationEnabled: Boolean
        get() = prefs.getBoolean("portal_form_preparation", true)
    var portalFormPreparationStatus: String =
        if (portalFormPreparationEnabled) "Oczekiwanie na zalogowaną stronę Portalu"
        else "Wyłączone"
        private set

    /**
     * Prepare the visible portal form using the authenticated user's own page.
     * This sends no credentials and makes no HTTP requests of its own.
     */
    fun setPortalFormPreparationEnabled(value: Boolean) {
        prefs.edit().putBoolean("portal_form_preparation", value).apply()
        portalFormPreparationStatus = if (value) "Włączone – oczekiwanie na formularz" else "Wyłączone"
        configurePortalFormPreparation()
        onUpdate()
    }

    fun configurePortalFormPreparation() {
        if (destroyed || !supported || !isPortal(web.url)) return
        val on = if (portalFormPreparationEnabled) "true" else "false"
        web.evaluateJavascript(
            "Boolean(window.__zlapFormPrep && " +
                "window.__zlapFormPrep.configure({enabled:" + on + "}));", null
        )
    }
    fun portalFormPreparationReport(onResult: (String) -> Unit) {
        if (!supported) {
            onResult("Skrypt portalu niedostępny: sprawdź Android System WebView.")
            return
        }
        if (!isPortal(web.url)) {
            onResult("Portal Info-Kierowca nie jest obecnie otwarty w aplikacji.")
            return
        }
        web.evaluateJavascript(
            """(function() {
                try { return JSON.stringify(window.__zlapFormPrep &&
                    window.__zlapFormPrep.diagnose ?
                    window.__zlapFormPrep.diagnose() : {script:false}); }
                catch (e) { return JSON.stringify({script:false,error:'js_exception'}); }
            })();"""
        ) { result ->
            try {
                // evaluateJavascript returns a JSON-escaped string. Parse twice.
                val payload = JSONObject("{\"v\":" + result + "}").optString("v")
                val data = JSONObject(payload)
                val fields = data.optJSONObject("fields") ?: JSONObject()
                val controls = data.optJSONObject("controls") ?: JSONObject()
                val otherTags = data.optJSONObject("otherTags") ?: JSONObject()
                val steps = data.optJSONObject("setupSteps") ?: JSONObject()
                val nextDiag = data.optJSONObject("nextDiagnostics") ?: JSONObject()
                val report = "Automat formularza ZłapEgzamin 0.34\n" +
                    "Monitor: " + (if (enabled) "włączony" else "wyłączony") + "\n" +
                    "Automat w Opcjach: " +
                        (if (portalFormPreparationEnabled) "WŁĄCZONY" else "WYŁĄCZONY") + "\n" +
                    "Skrypt na stronie: " +
                        (if (data.optBoolean("script")) "działa" else "BRAK") + "\n" +
                    "Skrypt aktywny: " + data.optBoolean("enabled") + "\n" +
                    "Wstrzymany przez ręczne przewijanie menu: " +
                        data.optBoolean("manualBrowsingPaused") + "\n" +
                    "Ekran: " + data.optString("screen").take(40) + "\n" +
                    "Zatrzymany: " + data.optBoolean("stopped") + "\n" +
                    "Wykonane kroki: " + data.optInt("actions") + "\n" +
                    "Kontrolki: start=" + controls.optInt("start") +
                        ", PKK=" + controls.optInt("pkk") +
                        ", dalej=" + controls.optInt("next") +
                        ", szukaj=" + controls.optInt("search") + "\n" +
                    "Pola: profil=" + fields.optInt("profile") +
                        ", ośrodek=" + fields.optInt("center") +
                        ", kategoria=" + fields.optInt("category") +
                        ", rodzaj=" + fields.optInt("type") +
                        ", data=" + fields.optInt("date") +
                        ", inne=" + fields.optInt("other") + "\n" +
                    "Typy nierozpoznanych: select=" + otherTags.optInt("select") +
                        ", combobox=" + otherTags.optInt("combobox") +
                        ", radio=" + otherTags.optInt("radio") +
                        ", data=" + otherTags.optInt("date") +
                        ", inne=" + otherTags.optInt("other") + "\n" +
                    "Ramki strony: " + data.optInt("embeddedFrames") +
                        ", Shadow DOM: " + data.optInt("shadowHosts") + "\n" +
                    "Rozpoznano stronę główną: " +
                        data.optBoolean("onHomePage") + "\n" +
                    "Ikony menu ☰ w lewym górnym rogu: " +
                        data.optInt("hamburgerCount") + "\n" +
                    "Menu usług otwarto automatycznie: " +
                        data.optBoolean("menuOpened") + "\n" +
                    "PKK wybrane: " + steps.optBoolean("pkk") +
                        ", numer profilu: " + steps.optBoolean("profile") + "\n" +
                    "Kategoria B (już ustawiona w Portalu): " +
                        (if (steps.optBoolean("categoryB")) "B odczytana" else
                         if (!steps.optBoolean("categoryReadable")) "wartość nieodczytana — bez zmiany"
                         else "inna kategoria") + "\n" +
                    "Tryb konkretnego WORD: " + steps.optBoolean("specificWord") +
                        ", Kraków Nowohucka: " + steps.optBoolean("centerNowohucka") +
                        ", Następny: " + steps.optBoolean("nextStep") + "\n" +                    "Ekran wyboru rodzaju egzaminu: " +
                        steps.optBoolean("practicalRadioScreen") +
                        ", praktyczny POTWIERDZONY: " +
                        steps.optBoolean("practicalRadioVerified") + "\n" +
                    "Wybór radio: " +
                        steps.optString("practicalRadioSelected", "nieodczytany").take(25) +
                        ", próba zaznaczenia: " +
                        steps.optBoolean("practicalRadioAttempted") + "\n" +
                    "Pierwszy etap formularza rozpoznany: " +
                        nextDiag.optBoolean("firstStage") +
                        ", wymagane wybory gotowe: " +
                        nextDiag.optBoolean("profileAndWordReady") + "\n" +
                    "Przycisk Następny — wykryte: " +
                        nextDiag.optInt("rawCandidates") +
                        ", aktywne: " + nextDiag.optInt("enabledCandidates") +
                        ", nieaktywne: " + nextDiag.optInt("disabledCandidates") + "\n" +
                    "Tekst zakładki w menu: " +
                        (if (data.optBoolean("menuTextVisible")) "widoczny" else "nie wykryto") + "\n" +
                    "Etap wyboru terminu w Portalu: " +
                        data.optString("bookingUiStage", "nieuruchomiony").take(40) + "\n" +
                    "Etap: " + data.optString("last").take(180) + "\n" +
                    "Status odczytu: " + diagnostics.take(160)
                onResult(report)
            } catch (_: Exception) {
                onResult("Nie udało się odczytać diagnostyki formularza. " +
                    "W aplikacji Portal może być otwarta inna strona.")
            }
        }
    }

    fun retryPortalFormPreparation(onResult: (String) -> Unit) {
        if (destroyed || !supported || !isPortal(web.url)) {
            onResult("Najpierw otwórz Portal Info-Kierowca w aplikacji.")
            return
        }
        if (!portalFormPreparationEnabled) {
            setPortalFormPreparationEnabled(true)
        }
        // In case WebView missed document-start injection after external login,
        // reinstall only our own local helper on the already trusted origin.
        web.evaluateJavascript("Boolean(window.__zlapFormPrep);") { exists ->
            fun retry() {
                web.evaluateJavascript(
                    "Boolean(window.__zlapFormPrep && " +
                    "window.__zlapFormPrep.configure({enabled:true}) && " +
                    "window.__zlapFormPrep.retry());"
                ) { response ->
                    onResult(if (response == "true")
                        "Ponownie uruchomiono wybór formularza w Portalu."
                    else "Skrypt przygotowania nie wystartował. Skopiuj diagnostykę.")
                }
            }
            if (exists == "true") retry()
            else {
                try {
                    val source = activity.assets.open("portal-autostart.js")
                        .bufferedReader().use { it.readText() }
                    web.evaluateJavascript(source) { retry() }
                } catch (_: Exception) {
                    onResult("Nie udało się załadować skryptu przygotowania.")
                }
            }
        }
    }

    val backgroundSessionMaintenanceEnabled: Boolean
        get() = prefs.getBoolean("background_session_maintenance", true)
    fun setBackgroundSessionMaintenanceEnabled(value: Boolean) {
        prefs.edit().putBoolean("background_session_maintenance", value).apply()
        event(if (value) "Włączono podtrzymywanie sesji w tle"
            else "Wyłączono podtrzymywanie sesji w tle")
        onUpdate()
    }
    val alarmOnFound: Boolean get() = prefs.getBoolean("alarm_on_found", true)
    val alarmOnBooked: Boolean get() = prefs.getBoolean("alarm_on_booked", true)
    fun setAlarmOnFound(value: Boolean) {
        prefs.edit().putBoolean("alarm_on_found", value).remove("alarm_last_ids").apply()
        if (!value) ExamAlarmService.silence(activity)
        onUpdate()
    }
    fun setAlarmOnBooked(value: Boolean) {
        prefs.edit().putBoolean("alarm_on_booked", value).apply()
        if (!value) ExamAlarmService.silence(activity)
        onUpdate()
    }
    fun testAlarm(): Boolean = ExamAlarmService.trigger(activity, "TEST alarmu ZłapEgzamin",
        "To tylko test. Nie znaleziono ani nie zarezerwowano egzaminu. Wycisz przyciskiem w powiadomieniu.")
    private fun ringAlarm(title: String, body: String, confirmed: Boolean = false) {
        if (!ExamAlarmService.trigger(activity, title, body, confirmed)) {
            lastError = "Nie uruchomiono alarmu. Sprawdź zgodę na powiadomienia i ograniczenia pracy w tle."
            event(lastError)
        }
    }

    val notifyFirst: Boolean get() = prefs.getBoolean("notify_first", false)
    val autoReserveEnabled: Boolean get() = prefs.getBoolean("auto_reserve", false)
    val lastBookingStatus: String get() = prefs.getString("booking_status", "Nie próbowano") ?: "Nie próbowano"
    val lastBookingDate: String get() = prefs.getString("booking_date", "") ?: ""
    val lastReservationId: String get() = prefs.getString("booking_id", "") ?: ""
    private var bookingInFlight = false
    val bookingPhase: String get() = prefs.getString("booking_phase", "idle").orEmpty()
    val bookingNeedsReview: Boolean
        get() = bookingPhase in setOf("creating", "pending", "uncertain", "confirmed")

    init {
        // A process/Activity death during the POST cannot tell us whether the
        // server already created a reservation. Never retry it automatically.
        val phase = bookingPhase
        val priorId = prefs.getString("booking_id", "").orEmpty()
        val previousSuccess = prefs.getLong("booking_confirmed_at", 0L) > 0L
        when {
            previousSuccess && phase == "idle" -> {
                prefs.edit().putString("booking_phase", "confirmed")
                    .putBoolean("auto_reserve", false).apply()
            }
            phase == "creating" || phase == "pending" ||
                // Legacy installations did not store booking_phase.
                // After the user explicitly reviews a reservation and resets
                // the guard, phase="idle" is intentional even if we retain
                // the previous booking ID in the audit/status screen.
                (phase == "idle" && !prefs.contains("booking_phase") &&
                    priorId.isNotBlank()) -> {
                prefs.edit()
                    .putString("booking_phase", "uncertain")
                    .putString("booking_status",
                        "Niepewny stan rezerwacji po wznowieniu aplikacji. Sprawdź w portalu.")
                    .putBoolean("auto_reserve", false)
                    .putBoolean("booking_needs_attention", true).apply()
            }
        }
    }

    /** User can unlock additional attempts only after reviewing the official portal. */
    fun resetBookingGuardAfterPortalReview() {
        if (bookingInFlight) return
        prefs.edit()
            .putString("booking_phase", "idle")
            .putBoolean("auto_reserve", false)
            .putLong("booking_confirmed_at", 0L)
            .putString("booking_status", "Stan rezerwacji sprawdzony ręcznie w portalu")
            .remove("last_booking_key")
            .remove("last_booking_attempt_at")
            .remove("booking_needs_attention")
            .apply()
        event("Ręcznie potwierdzono sprawdzenie stanu rezerwacji w portalu")
        onUpdate()
    }

    fun setAutoReserve(value: Boolean): Boolean {
        if (value) {
            if (bookingNeedsReview || bookingInFlight) return false
            if (!BookingTimeRange.isValid(bookingTimeFrom, bookingTimeTo)) return false
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
    val maxRequestsPerHour: Int get() = rateGuard.maxRequestsPerHour
    fun setMaxRequestsPerHour(value: Int): Boolean {
        if (!rateGuard.setMaxRequestsPerHour(value)) return false
        event("Zmieniono lokalny limit automatu na " + value + " zapytań/60 min")
        onUpdate()
        return true
    }
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
    val lastBackgroundSchedulerAt: Long
        get() = prefs.getLong("background_poll_scheduler_at", 0L)
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
            if (!nativeReadBusy && requestPending && now - lastAttemptAt > 90000L) requestPending = false
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
            checkAutomaticDue()
            handler.postDelayed(this, 15_000L)
        }
    }

    /**
     * Shared, rate-guarded automatic scheduler used both by the foreground
     * Activity and by its already-running foreground service. It NEVER starts
     * another search when one is pending, or when HTTP 429/hourly limits apply.
     * Calling it from the service makes polling resilient to Activity callback
     * throttling, as long as the authenticated WebView still exists.
     */
    fun checkAutomaticDue(): Boolean {
        if (destroyed || !enabled || !supported || !requestReady ||
            sessionExpired || !isPortal(web.url)) return false
        val now = System.currentTimeMillis()
        if (!nativeReadBusy && requestPending && now - lastAttemptAt >= 90_000L) {
            requestPending = false
            diagnostics = "Odczyt przekroczył 90 sekund; sprawdzam gotowość portalu."
        }
        if (bookingInFlight || nativeReadBusy || now < blockedUntil || now < nextAllowed() ||
            requestPending) return false
        if (nativeRequest != null) return startNativeRead()
        // Fail visibly rather than starting a WebView fetch that can freeze
        // when the screen locks. Opening the exam list captures the query.
        diagnostics = nativeSetupIssue
        if (now - lastNativeExportAt >= 30_000L) {
            lastNativeExportAt = now
            web.evaluateJavascript("Boolean(window.__zlapExportNativeRequest && window.__zlapExportNativeRequest());", null)
        }
        return false
    }

    private fun startNativeRead(): Boolean {
        val request = nativeRequest ?: return false
        if (nativeReadBusy || destroyed) return false
        val version = nativeRequestVersion
        val cookies = android.webkit.CookieManager.getInstance().getCookie(request.url).orEmpty()
        val userAgent = web.settings.userAgentString
        nativeReadBusy = true
        requestPending = true
        receive(JSONObject().put("kind", "request").put("source", "automatic").toString())
        diagnostics = "Automatyczny odczyt w tle – połączenie natywne Androida."
        thread(name = "zlap-exam-read") {
            val result = try {
                val response = NativeExamReader.read(request, cookies, userAgent)
                val jar = android.webkit.CookieManager.getInstance()
                response.cookies.forEach { jar.setCookie(request.url, it) }
                NativeExamReader.message(response)
            } catch (_: Exception) {
                JSONObject().put("kind", "network").put("reason", "native_timeout_or_network")
            }
            handler.post {
                nativeReadBusy = false
                if (!destroyed) {
                    requestPending = false
                    lastNetworkActivity = System.currentTimeMillis()
                    // Rate limits are authoritative even if the user changed
                    // search criteria while this read was running.
                    if (result.optString("kind") == "rate" ||
                        (enabled && !sessionExpired && version == nativeRequestVersion)) {
                        receive(result.toString())
                    }
                }
            }
        }
        onUpdate()
        return true
    }

    fun install() {
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("exam_dates", "Nowe terminy egzaminów", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel("session_alerts", "Sesja i problemy monitorowania", NotificationManager.IMPORTANCE_HIGH)
        )
        if (prefs.getBoolean("booking_needs_attention", false)) {
            prefs.edit().putBoolean("booking_needs_attention", false).apply()
            val msg = "Nie znamy wyniku poprzedniej próby rezerwacji. Otwórz " +
                "info-kierowca.pl i sprawdź, czy miejsce jest już zajęte. " +
                "Autorezerwacja jest wstrzymana."
            notify(msg, "ZłapEgzamin – sprawdź rezerwację")
            sendTelegram("ZłapEgzamin: " + msg)
        }

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
            val formPrepScript = activity.assets.open("portal-autostart.js")
                .bufferedReader().use { it.readText() }
            // Each full WebView navigation creates a fresh JavaScript realm.
            // Initialize consent at document start, not only in onPageFinished:
            // the portal also uses client-side routing after authentication.
            val initialFormPrep = formPrepScript + "\nwindow.__zlapFormPrep && " +
                "window.__zlapFormPrep.configure({enabled:" +
                (if (portalFormPreparationEnabled) "true" else "false") + "});"
            WebViewCompat.addDocumentStartJavaScript(web, initialFormPrep, origins)
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
        configurePortalFormPreparation()
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
        nativeRequest = null
        nativeRequestVersion++
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
        if (requestPending || nativeReadBusy || bookingInFlight) {
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
            nativeRequest == null -> nativeSetupIssue
            lastError.isNotEmpty() -> lastError
            lastCheckedAt == 0L -> "Połączono z listą egzaminów. Oczekiwanie na pierwszy odczyt."
            else -> "Ostatni odczyt: " + clock(lastCheckedAt) +
                ". Zapytania w bieżącym cyklu: " + searchesThisHour + " (limit automatu: " + maxRequestsPerHour + "). " +
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
                "form_prep" -> {
                    portalFormPreparationStatus = message.optString("status").take(160)
                    if (!requestReady) {
                        diagnostics = "Automat formularza: " + portalFormPreparationStatus
                    }
                }
                "native_request_ready" -> {
                    val captured = NativeExamReader.capture(message)
                    // Capturing the API request is NOT proof the exam-type
                    // wizard is complete: the portal may prefetch term lists
                    // with "Wyświetl wszystkie typy" still selected. Previously
                    // this stopped form preparation before it could click the
                    // exact "Egzamin praktyczny" radio button. The DOM helper
                    // now stops itself only when the real results view is ready.
                    if (captured != nativeRequest) nativeRequestVersion++
                    nativeRequest = captured
                    nativeSetupIssue = if (captured != null) "" else {
                        // Only the path, never query parameters, PKK or headers.
                        val path = runCatching { java.net.URL(message.optString("url")).path }.getOrDefault("")
                        "Odczyt w tle odrzucił format zapytania API: " + path.take(160)
                    }
                    diagnostics = if (captured != null) "Odczyt w tle gotowy." else nativeSetupIssue
                }
                "hook" -> {
                    hookDetected = true
                    if (!portalFormPreparationEnabled)
                        diagnostics = "Skrypt działa. Otwórz listę terminów egzaminu w portalu."
                }
                "candidate" -> {
                    // /Reservations means the portal is alive, not that the
                    // exam timetable has been selected. Don't overwrite the
                    // helpful step-by-step form preparation status.
                    val path = message.optString("path")
                    if (!requestReady && path.contains("OneCenterExam", ignoreCase = true))
                        diagnostics = "Wykryto API terminów: OneCenterExam."
                }
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
                        " zapytanie. Bieżący cykl: " + rateGuard.usedInHour() + " (limit automatu " + maxRequestsPerHour + ")."
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
                        .putInt("native_refresh_rejections", 0)
                        .putBoolean("native_refresh_issue_alerted", false)
                        .putBoolean("session_maximum_age_warned", false).apply()
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
                "booking_ui_expanding" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        diagnostics = "Rozwijam właściwy dzień, szukam pasującej godziny i kategorii B."
                        event("Rozwijanie daty pasującego egzaminu")
                    }
                }
                "booking_ui_no_card" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        bookingInFlight = false
                        prefs.edit().putString("booking_phase", "idle")
                            .putString("booking_status",
                                "Rozwinięto datę, ale nie ma jednoznacznej pasującej karty.")
                            .remove("last_booking_key")
                            .remove("last_booking_attempt_at")
                            .apply()
                        diagnostics = "Nie kliknięto egzaminu. Sprawdź listę terminów w Portalu."
                        event(diagnostics)
                        onUpdate()
                    }
                }
                "booking_ui_selected" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        diagnostics = "Znaleziono pasującą kartę terminu; portal otwiera potwierdzenie."
                        event("Wybrano kartę pasującego egzaminu")
                    }
                }
                "booking_ui_confirming" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        diagnostics = "Potwierdzanie wybranego terminu w oknie portalu."
                        event("Potwierdzanie wyboru egzaminu")
                    }
                }
                "booking_ui_advanced" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        flagUncertainBooking(
                            "Przeszło przez okno wyboru egzaminu. " +
                            "Potwierdź rzeczywisty stan rezerwacji i ewentualną opłatę w portalu.",
                            "", lastBookingDate)
                    }
                }
                "booking_ui_problem" -> {
                    if (bookingInFlight && bookingPhase == "creating" &&
                        message.optString("date") == lastBookingDate) {
                        flagUncertainBooking(
                            "Zatrzymano wybór karty/okna: " +
                            message.optString("detail").take(95) +
                            ". Sprawdź rezerwacje w portalu.", "", lastBookingDate)
                    }
                }
                "booking_started" -> {
                    bookingInFlight = true
                    diagnostics = "Próba zajęcia terminu: " + message.optString("date").take(24)
                    event("Rozpoczęto zajmowanie miejsca")
                }
                "booking_pending" -> {
                    val id = message.optString("reservationId").take(90)
                    if (bookingInFlight &&
                        Regex("^[A-Za-z0-9_-]{1,90}$").matches(id) &&
                        bookingPhase == "creating") {
                        // The server may already hold this place. Persist the
                        // identifier synchronously before reading confirmation.
                        prefs.edit().putString("booking_id", id)
                            .putString("booking_phase", "pending")
                            .putString("booking_status", "Utworzona – trwa potwierdzanie")
                            .commit()
                        diagnostics = "Utworzono rezerwację, sprawdzam czy miejsce zostało zablokowane."
                    }
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
        val alarmMatches = ExamAlarmPolicy.matching(current, bookingTimeFrom, bookingTimeTo)
        val newAlarmKeys = ExamAlarmPolicy.newKeys(alarmMatches,
            prefs.getStringSet("alarm_last_ids", emptySet())?.toSet().orEmpty())
        prefs.edit().putStringSet("alarm_last_ids", alarmMatches.keys.toSet()).apply()
        if (enabled && alarmOnFound && newAlarmKeys.isNotEmpty()) {
            val date = alarmMatches[newAlarmKeys.first()].orEmpty().replace('T', ' ').take(16)
            ringAlarm("Znaleziono pasujący termin!",
                "MORD Kraków, B: " + date + ". To dostępny termin, NIE potwierdzenie rezerwacji.")
        }
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
        if (!autoReserveEnabled || bookingInFlight || bookingNeedsReview ||
            !requestReady || sessionExpired ||
            !isPortal(web.url) || System.currentTimeMillis() < blockedUntil) return
        val now = System.currentTimeMillis()
        val lastKey = prefs.getString("last_booking_key", "") ?: ""
        val lastWhen = prefs.getLong("last_booking_attempt_at", 0L)
        val candidate = available.entries.sortedBy { it.value }.firstOrNull { (key, date) ->
            val day = date.take(10)
            day >= java.time.LocalDate.now().toString() &&
                BookingTimeRange.includes(date, bookingTimeFrom, bookingTimeTo) &&
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
        // Durable write BEFORE submitting the real create operation.
        // After a process death this becomes an uncertain hold, not a retry.
        val recorded = prefs.edit().putString("last_booking_key", candidate.key)
            .putLong("last_booking_attempt_at", now)
            .putString("booking_phase", "creating")
            .putString("booking_status", "Rozpoczęto wybór terminu w Portalu")
            .putString("booking_date", date)
            .remove("booking_id")
            .commit()
        if (!recorded) {
            bookingInFlight = false
            diagnostics = "Nie zapisano bezpiecznie stanu rezerwacji – pomijam próbę."
            event(diagnostics)
            onUpdate()
            return
        }
        val startedAt = now
        handler.postDelayed({
            if (!destroyed && bookingInFlight &&
                prefs.getLong("last_booking_attempt_at", 0L) == startedAt &&
                bookingPhase in setOf("creating", "pending")) {
                bookingInFlight = false
                flagUncertainBooking(
                    "Upłynęły 2 minuty bez potwierdzenia zakończenia rezerwacji.",
                    prefs.getString("booking_id", "").orEmpty(),
                    date
                )
            }
        }, 120_000L)
        // Use the real user-visible exam card and "Potwierdź wybrany egzamin"
        // dialog. Do not submit an undocumented /Reservations/create request in
        // parallel: that could reserve the same place twice.
        web.evaluateJavascript(
            "Boolean(window.__zlapFormPrep && window.__zlapFormPrep.bookUi && " +
                "window.__zlapFormPrep.bookUi(" +
                JSONObject.quote(practiceId) + "," + JSONObject.quote(date) + "," +
                JSONObject.quote(dateFrom) + "," + JSONObject.quote(dateTo) + "," +
                JSONObject.quote(bookingTimeFrom) + "," +
                JSONObject.quote(bookingTimeTo) + "));"
        ) { result ->
            if (result != "true") {
                bookingInFlight = false
                val info = "Nie widać jednoznacznej karty pasującego terminu " +
                    "w Portalu. Otwórz listę terminów w zakładce Portal."
                // No card was clicked, so no confirmation or booking attempt.
                prefs.edit().putString("booking_phase", "idle")
                    .putString("booking_status", info)
                    .remove("last_booking_key")
                    .remove("last_booking_attempt_at").apply()
                diagnostics = info
                event("Autorezerwacja: brak pasującej karty w widoku")
                onUpdate()
            } else {
                event("Wybrano kartę terminu w Portalu " + date.take(16))
                onUpdate()
            }
        }
    }

    private fun flagUncertainBooking(reason: String, id: String, date: String) {
        bookingInFlight = false
        val safeId = id.take(90).takeIf { Regex("^[A-Za-z0-9_-]{1,90}$").matches(it) }
            ?: prefs.getString("booking_id", "").orEmpty()
        prefs.edit().putBoolean("auto_reserve", false)
            .putString("booking_phase", "uncertain")
            .putString("booking_status", "NIEPOTWIERDZONA: " + reason.take(160))
            .putString("booking_date", date.take(32))
            .putString("booking_id", safeId)
            .commit()
        diagnostics = "Rezerwacja wymaga ręcznej weryfikacji w portalu."
        event("Niepewna rezerwacja – wstrzymano autorezerwację")
        val body = "Nie można potwierdzić wyniku rezerwacji " +
            date.replace('T', ' ').take(16) +
            ". Autorezerwacja wstrzymana, aby uniknąć podwójnej rezerwacji. " +
            "Sprawdź rezerwacje w info-kierowca.pl" +
            (if (safeId.isNotBlank()) " (ID: " + safeId + "). " else ". ") +
            reason.take(140)
        notify(body, "ZłapEgzamin – sprawdź rezerwację")
        sendTelegram("ZłapEgzamin: " + body)
        onUpdate()
    }

    private fun bookingSucceeded(message: JSONObject) {
        val reservationId = message.optString("reservationId").take(90)
        val date = message.optString("date").take(32)
        if (!Regex("^[A-Za-z0-9_-]{1,90}$").matches(reservationId) ||
            date != lastBookingDate || bookingPhase == "confirmed" ||
            (bookingPhase !in setOf("creating", "pending", "uncertain") &&
                !bookingInFlight)) return
        val existingId = prefs.getString("booking_id", "").orEmpty()
        if (existingId.isNotBlank() && existingId != reservationId) return
        // A delayed confirmation may arrive AFTER the native 2-minute
        // watchdog. Accept only the same known candidate and reservation.
        bookingInFlight = false
        val now = System.currentTimeMillis()
        prefs.edit().putBoolean("auto_reserve", false)
            .putBoolean("enabled", false)
            .putString("booking_phase", "confirmed")
            .putString("booking_id", reservationId)
            .putString("booking_date", date)
            .putString("booking_status", "Miejsce zarezerwowane – opłać w portalu")
            .putLong("booking_confirmed_at", now)
            .commit()
        val body = "Zajęto termin MORD Kraków, B: " + date.replace('T',' ').take(16) +
            ". Miejsce jest tymczasowo zarezerwowane. Zaloguj się i opłać " +
            "zgodnie z terminem podanym przez portal. ID: " + reservationId
        diagnostics = "Serwer potwierdził " + message.optString("status") + "."
        event("Zajęto miejsce, ID " + reservationId.take(16))
        notify(body, "ZłapEgzamin – zarezerwowano miejsce!")
        if (alarmOnBooked) ringAlarm("Potwierdzono rezerwację!", body, confirmed = true)
        sendTelegram("ZłapEgzamin: " + body)
        activity.stopService(Intent(activity, MonitorForegroundService::class.java))
        onUpdate()
    }

    private fun bookingFailed(message: JSONObject) {
        if (!bookingInFlight || bookingPhase !in setOf("creating", "pending")) return
        bookingInFlight = false
        val id = message.optString("reservationId").take(90)
        val date = message.optString("date").take(32)
        if (date != lastBookingDate) return
        val reason = message.optString("reason").take(180)
        val uncertain = message.optBoolean("uncertain", false) || id.isNotBlank() ||
            bookingPhase == "pending"
        if (uncertain) {
            flagUncertainBooking(reason, id, date)
            return
        }
        // Definitive gateway rejection before a reservation was created.
        // Keep auto-reservation enabled but avoid retrying the same candidate
        // for 45 min; try another fresh candidate at a later read.
        prefs.edit().putString("booking_phase", "idle")
            .putString("booking_status", "Odrzucono próbę: " + reason)
            .putString("booking_date", date)
            .remove("booking_id")
            .apply()
        diagnostics = "Nie udało się zarezerwować: " + reason
        event("Odrzucono próbę rezerwacji: " + reason)
        val body = "Rezerwacja " + date.replace('T', ' ').take(16) +
            " nie powiodła się: " + reason +
            ". Aplikacja spróbuje przy kolejnym poprawnym odczycie."
        notify(body, "ZłapEgzamin – nieudana próba rezerwacji")
        sendTelegram("ZłapEgzamin: " + body)
        onUpdate()
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

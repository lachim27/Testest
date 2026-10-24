package pl.zlapegzamin.test

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Maintains foreground priority and independently warns if the WebView
 * session checker stops responding. It does not pretend to run WebView
 * polling after the Activity has been destroyed.
 */
class MonitorForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("slot_monitor_v1", Context.MODE_PRIVATE) }
    private val minute = 60_000L
    private val refreshPeriod = 4 * minute
    private var backgroundWakeLock: PowerManager.WakeLock? = null
    private var lastWakeAcquiredAt = 0L
    private var refreshBusy = false
    private var destroyed = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            try {
                maintainBackgroundSession()
                checkWebViewHeartbeat()
            } catch (_: Exception) {
                // The watchdog and notification service must never crash.
            } finally {
                if (!destroyed) handler.postDelayed(this, 20_000L)
            }
        }
    }

    private fun releaseCpuAwake() {
        try { if (backgroundWakeLock?.isHeld == true) backgroundWakeLock?.release() }
        catch (_: Exception) { }
        lastWakeAcquiredAt = 0L
    }

    private fun keepCpuAwake() {
        try {
            val now = System.currentTimeMillis()
            if (backgroundWakeLock == null) {
                backgroundWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZlapEgzamin:BackgroundSession")
                    .apply { setReferenceCounted(false) }
            }
            if (backgroundWakeLock?.isHeld != true || now - lastWakeAcquiredAt >= 90_000L) {
                releaseCpuAwake()
                // A bounded lock is renewed only while an authenticated WebView
                // is actively being monitored in the background. The display
                // stays off; this may increase battery use.
                backgroundWakeLock?.acquire(3 * minute)
                lastWakeAcquiredAt = now
            }
        } catch (_: Exception) {
            // Device power-management policy may restrict background operation.
        }
    }

    private fun maintainBackgroundSession() {
        val enabled = prefs.getBoolean("enabled", true)
        val background = prefs.getBoolean("app_in_background", false)
        val keepAlive = prefs.getBoolean("background_session_maintenance", true)
        val expired = prefs.getBoolean("session_expired", false)
        val authenticatedAt = prefs.getLong("authenticated_at", 0L)
        val now = System.currentTimeMillis()
        if (!enabled || !background || !keepAlive || expired ||
            authenticatedAt <= 0L || authenticatedAt > now) {
            releaseCpuAwake()
            return
        }

        // If the Activity's WebView has been destroyed, an isolated foreground
        // service cannot perform the authenticated JavaScript exam polling.
        if (!PortalMonitorBridge.attached()) {
            releaseCpuAwake()
            // WebView was destroyed while a real booking request might have
            // been executing. Do not retry /Reservations/create with unknown
            // outcome when the Activity opens again.
            val phase = prefs.getString("booking_phase", "idle").orEmpty()
            if (phase == "creating" || phase == "pending") {
                prefs.edit().putString("booking_phase", "uncertain")
                    .putBoolean("auto_reserve", false)
                    .putString("booking_status",
                        "Okno portalu zamknięto w trakcie rezerwacji – sprawdź jej stan.")
                    .apply()
                if (!prefs.getBoolean("booking_background_interrupted_alerted", false)) {
                    prefs.edit().putBoolean("booking_background_interrupted_alerted", true).apply()
                    val bookingMsg = "Android zamknął portal w trakcie próby rezerwacji. " +
                        "Jej wynik jest nieznany, więc wstrzymałem kolejne próby. " +
                        "Sprawdź istniejące rezerwacje na info-kierowca.pl."
                    showSessionWarning(bookingMsg)
                    sendTelegram("ZłapEgzamin: " + bookingMsg)
                }
            }
            if (now - authenticatedAt >= 4 * minute &&
                prefs.getBoolean("background_monitor_absent_alerted", false).not()) {
                prefs.edit().putBoolean("background_monitor_absent_alerted", true).apply()
                val msg = "Android zamknął okno portalu, więc monitorowanie i " +
                    "przedłużanie sesji nie może działać. Otwórz ponownie ZłapEgzamin."
                showSessionWarning(msg)
                sendTelegram("ZłapEgzamin: " + msg)
            }
            return
        }

        // Invoke the live WebView on the UI thread. If its saved authenticated
        // request disappeared, do not waste battery refreshing an unusable tab.
        val portalAlive = PortalMonitorBridge.pulse()
        if (portalAlive) {
            // The same native rate guard is shared with Activity ticker.
            // This drives exam reads (and subsequent opt-in booking) even if
            // the Activity's own handler becomes delayed after Home/screen lock.
            PortalMonitorBridge.pollIfDue()
            prefs.edit().putLong("background_poll_scheduler_at",
                System.currentTimeMillis()).apply()
        }
        if (!portalAlive) {
            releaseCpuAwake()
            return
        }
        val lastRefreshOk = prefs.getLong("native_refresh_ok_at", 0L)
        // Some sessions have a fixed maximum lifetime. Stop the expensive CPU
        // hold when renewals have ceased to work beyond roughly an hour.
        if (now - authenticatedAt > 65 * minute &&
            (lastRefreshOk <= 0L || now - lastRefreshOk > 9 * minute)) {
            releaseCpuAwake()
            if (!prefs.getBoolean("session_maximum_age_warned", false)) {
                prefs.edit().putBoolean("session_maximum_age_warned", true).apply()
                val msg = "Nie potwierdzono dalszego odnawiania sesji portalu po " +
                    "około godzinie. Otwórz ZłapEgzamin i zaloguj się ponownie."
                showSessionWarning(msg)
                sendTelegram("ZłapEgzamin: " + msg)
            }
            return
        }
        keepCpuAwake()

        if (refreshBusy) return
        val lastAttempt = prefs.getLong("native_refresh_attempt_at", 0L)
        val base = maxOf(authenticatedAt, lastAttempt)
        if (now - base < refreshPeriod) return

        refreshBusy = true
        prefs.edit().putLong("native_refresh_attempt_at", now)
            .putString("native_refresh_status", "Odświeżanie sesji w toku").apply()
        thread(name = "zlap-jwt-refresh") {
            val result = PortalJwtRefresher.refresh()
            handler.post {
                refreshBusy = false
                if (!destroyed) handleRefreshResult(result)
            }
        }
    }

    private fun handleRefreshResult(result: PortalJwtRefresher.Result) {
        val now = System.currentTimeMillis()
        // Ignore responses arriving after the monitor has been disabled.
        if (!prefs.getBoolean("enabled", true)) return
        when (result) {
            is PortalJwtRefresher.Result.Refreshed -> {
                prefs.edit()
                    .putLong("native_refresh_ok_at", now)
                    .putLong("verified_portal_at", now)
                    .putString("native_refresh_status", "Odnowiono sesję przez JWT")
                    .putInt("native_refresh_failures", 0)
                    .putInt("native_refresh_rejections", 0)
                    .putBoolean("native_refresh_issue_alerted", false)
                    .apply()
            }
            is PortalJwtRefresher.Result.Rejected -> {
                val count = prefs.getInt("native_refresh_rejections", 0) + 1
                prefs.edit().putInt("native_refresh_rejections", count)
                    .putInt("native_refresh_failures",
                        prefs.getInt("native_refresh_failures", 0) + 1)
                    .putString("native_refresh_status", "JWT HTTP " + result.status)
                    .apply()
                // Two explicit auth rejections in a row reduce the risk of
                // mistaking a brief token-rotation race for a logged-out user.
                if (count >= 2 && !prefs.getBoolean("session_expired", false)) {
                    prefs.edit().putBoolean("session_expired", true)
                        .putBoolean("session_alert_sent", true).apply()
                    val msg = "Portal odrzucił odnowienie sesji (HTTP " + result.status +
                        "). Otwórz ZłapEgzamin i zaloguj się ponownie przez mObywatel."
                    showSessionWarning(msg)
                    sendTelegram("ZłapEgzamin: " + msg)
                    releaseCpuAwake()
                }
            }
            is PortalJwtRefresher.Result.AcceptedWithoutCookieUpdate -> {
                recordRefreshProblem("Portal potwierdził żądanie, ale nie zwrócił nowego JWT.")
            }
            is PortalJwtRefresher.Result.NoAuthenticatedCookie -> {
                recordRefreshProblem("Nie znaleziono ciasteczka sesji WebView.")
            }
            is PortalJwtRefresher.Result.TemporaryFailure -> {
                recordRefreshProblem(
                    if (result.status == null) "Błąd sieci przy odnawianiu sesji."
                    else "Odnowienie sesji: HTTP " + result.status + "."
                )
            }
        }
    }

    private fun recordRefreshProblem(reason: String) {
        val count = prefs.getInt("native_refresh_failures", 0) + 1
        prefs.edit().putInt("native_refresh_failures", count)
            .putInt("native_refresh_rejections", 0)
            .putString("native_refresh_status", reason).apply()
        if (count >= 2 && !prefs.getBoolean("native_refresh_issue_alerted", false)) {
            prefs.edit().putBoolean("native_refresh_issue_alerted", true).apply()
            val msg = reason + " Sesja może wygasnąć w tle. Sprawdź portal."
            showSessionWarning(msg)
            sendTelegram("ZłapEgzamin: " + msg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("monitor_active", "Monitorowanie", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel("session_alerts", "Sesja i problemy monitorowania",
                NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(
            991,
            Notification.Builder(this, "monitor_active")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("ZłapEgzamin — monitor")
                .setContentText("Monitor i podtrzymywanie sesji po zminimalizowaniu")
                .setOngoing(true)
                .build()
        )
        handler.post(watchdog)
    }

    private fun checkWebViewHeartbeat() {
        try {
            if (!prefs.getBoolean("enabled", true) ||
                prefs.getBoolean("session_expired", false)) return
            val authenticatedAt = prefs.getLong("authenticated_at", 0L)
            val lastBeat = prefs.getLong("web_heartbeat_at", 0L)
            val verifiedAt = prefs.getLong("verified_portal_at", 0L)
            val now = System.currentTimeMillis()
            // The JS heartbeat only confirms that WebView is running. It does
            // NOT prove that the portal has extended the server-side session.
            val lastPortalEvidence = maxOf(authenticatedAt, verifiedAt)
            if (lastPortalEvidence > 0L && now >= lastPortalEvidence &&
                now - lastPortalEvidence in (7 * minute)..(48 * 60 * minute) &&
                prefs.getLong("risk_notified_for", 0L) != lastPortalEvidence) {
                prefs.edit().putLong("risk_notified_for", lastPortalEvidence).apply()
                val risk = "Od ponad 7 minut nie ma potwierdzenia aktywnej sesji portalu. " +
                    "Info-kierowca może wylogować po około 10 minutach bezczynności. " +
                    "Otwórz Portal i sprawdź logowanie."
                showSessionWarning(risk)
                sendTelegram("ZłapEgzamin: " + risk)
            }
            if (authenticatedAt <= 0L || lastBeat <= 0L ||
                lastBeat < authenticatedAt || now < lastBeat ||
                now - authenticatedAt > 48 * 60 * minute) return
            // Warn before a known ~10-minute browser idle timeout is likely.
            // A missing heartbeat does not by itself prove the portal logged out.
            if (now - lastBeat < 4 * minute) return
            if (prefs.getLong("web_stalled_notified_beat", 0L) == lastBeat) return
            prefs.edit().putLong("web_stalled_notified_beat", lastBeat).apply()
            val msg = "Od ponad 4 minut nie udało się potwierdzić działania monitora w tle. " +
                "Portal może zakończyć sesję z powodu nieaktywności. Otwórz ZłapEgzamin."
            showSessionWarning(msg)
            sendTelegram("ZłapEgzamin: " + msg)
        } catch (_: Exception) {
            // The service must not crash because of the watchdog.
        }
    }

    private fun showSessionWarning(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_portal", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "session_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ZłapEgzamin — sprawdź sesję")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(305, notification)
    }

    private fun sendTelegram(message: String) {
        val token = prefs.getString("tg_token", "").orEmpty()
        val chat = prefs.getString("tg_chat", "").orEmpty()
        if (token.isBlank() || chat.isBlank()) return
        thread(name = "telegram-session-watchdog") {
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                val data = "chat_id=" + URLEncoder.encode(chat, "UTF-8") +
                    "&text=" + URLEncoder.encode(message, "UTF-8")
                conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.getBoolean("enabled", true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(watchdog)
        releaseCpuAwake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

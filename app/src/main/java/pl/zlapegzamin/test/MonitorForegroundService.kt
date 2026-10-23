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
    private val watchdog = object : Runnable {
        override fun run() {
            checkWebViewHeartbeat()
            handler.postDelayed(this, minute)
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
                .setContentText("Sprawdzanie stanu monitorowania")
                .setOngoing(true)
                .build()
        )
        handler.postDelayed(watchdog, minute)
    }

    private fun checkWebViewHeartbeat() {
        try {
            if (!prefs.getBoolean("enabled", true) ||
                prefs.getBoolean("session_expired", false)) return
            val authenticatedAt = prefs.getLong("authenticated_at", 0L)
            val lastBeat = prefs.getLong("web_heartbeat_at", 0L)
            val now = System.currentTimeMillis()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

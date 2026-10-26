package pl.zlapegzamin.test

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*

/** Bounded local alarm; never changes the user's volume or DND settings. */
class ExamAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null
    private var confirmed = false
    private var active = false
    private val finish = Runnable { stopSelf() }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val incomingConfirmed = intent.getBooleanExtra("confirmed", false)
        if (active && confirmed && !incomingConfirmed) return START_NOT_STICKY
        val upgrade = incomingConfirmed && !confirmed
        confirmed = incomingConfirmed
        val title = intent.getStringExtra("title").orEmpty()
        val body = intent.getStringExtra("body").orEmpty()
        try {
            createChannel(this)
            startForeground(ID, notification(title, body))
            if (!active) {
                active = true
                handler.postDelayed(finish, 120_000L)
                startSound()
            } else if (upgrade) {
                handler.removeCallbacks(finish)
                handler.postDelayed(finish, 120_000L)
            }
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(title: String, body: String): Notification {
        val stop = PendingIntent.getBroadcast(this, 740,
            Intent(this, ExamAlarmStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 741,
            Intent(this, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_portal", true)
                putExtra("stop_exam_alarm", true)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Wycisz", stop).build())
            .addAction(Notification.Action.Builder(null, "Otwórz portal", open).build())
            .build()
    }

    private fun startSound() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopSelf()
            }, handler).build()
        if (audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setWakeMode(this@ExamAlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(this@ExamAlarmService, uri)
                    isLooping = true
                    setOnPreparedListener { if (active) it.start() }
                    setOnErrorListener { _, _, _ -> stopSelf(); true }
                    prepareAsync()
                }
            } catch (_: Exception) { player?.release(); player = null }
        }
        try {
            @Suppress("DEPRECATION")
            val device = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator = device
            // Finite waveform also stops in hardware if Android delays our Handler.
            val pattern = LongArray(121) { index ->
                if (index == 0) 0L else longArrayOf(700L, 500L, 700L, 2100L)[(index - 1) % 4]
            }
            device.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) { }
        player = null
        vibrator?.cancel()
        focus?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "exam_alarm_v1"
        private const val ID = 740
        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Alarm terminu i rezerwacji", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Dzwonek alarmowy do wyciszenia, maksymalnie 2 minuty"
            // Sound/vibration is owned by the bounded service, not the channel.
            channel.setSound(null, null)
            channel.enableVibration(false)
            (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        fun trigger(context: Context, title: String, body: String, confirmed: Boolean = false): Boolean {
            createChannel(context)
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
                (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false
            return try {
                context.startForegroundService(Intent(context, ExamAlarmService::class.java)
                    .putExtra("title", title).putExtra("body", body).putExtra("confirmed", confirmed))
                true
            } catch (_: Exception) { false }
        }
        fun silence(context: Context) { context.stopService(Intent(context, ExamAlarmService::class.java)) }
    }
}

class ExamAlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) { ExamAlarmService.silence(context) }
}

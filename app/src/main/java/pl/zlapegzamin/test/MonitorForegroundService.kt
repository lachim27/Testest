package pl.zlapegzamin.test

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder

class MonitorForegroundService : Service() {
 override fun onCreate() {
  super.onCreate()
  val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  manager.createNotificationChannel(NotificationChannel("monitor_active", "Monitorowanie", NotificationManager.IMPORTANCE_LOW))
  startForeground(991, Notification.Builder(this, "monitor_active").setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("ZlapEgzamin monitor").setContentText("Monitor aktywny w procesie aplikacji").setOngoing(true).build())
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
 override fun onBind(intent: Intent?): IBinder? = null
}

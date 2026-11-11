package de.ayutv.data.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import de.ayutv.AyuApp
import de.ayutv.R

/**
 * Fernseher: Das Handy erreicht DnA-TV auch, wenn die App gerade nicht zu sehen ist (Fire-TV-Startseite, andere
 * App). Der Dienst hält die Fernbedienung samt „Auf dem Fernseher abspielen“ am Laufen; eine Übergabe vom Handy
 * startet den Player dann von selbst. Startet mit DnA-TV und nach dem Einschalten des Geräts.
 */
class RemoteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        running = true
        (application as AyuApp).container.remote.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        // Ist die App gerade zu sehen, läuft die Fernbedienung weiter (sie gehört dann zur App).
        val container = (application as AyuApp).container
        if (container.foreground?.get() == null) container.remote.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Handy-Verbindung", NotificationManager.IMPORTANCE_MIN))
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DnA-TV wartet auf das Handy")
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "Vordergrund nicht möglich", it) }
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 4712
        @Volatile var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Dienst nicht gestartet", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteService::class.java)) }
        }
    }
}

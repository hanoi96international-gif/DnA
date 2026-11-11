package de.ayutv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import de.ayutv.AyuApp
import de.ayutv.R
import de.ayutv.data.net.DlnaCast
import de.ayutv.data.net.DlnaRelay
import kotlinx.coroutines.launch

/**
 * Weiterleitung zum Fernseher im Hintergrund: Muss das Handy den Stream an den LG & Co. weiterreichen, läuft das in
 * diesem Dienst, nicht im Player. So hört der Fernseher nicht auf, wenn man DnA-TV verlässt, den Bildschirm sperrt oder
 * Android den Player beendet. Eine Benachrichtigung zeigt, was läuft, mit „Beenden“ (hält den Fernseher an).
 */
class CastService : Service() {
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifiLock = runCatching {
            applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dna-cast")?.apply { acquire() }
        }.getOrNull()
        wakeLock = runCatching {
            getSystemService(android.os.PowerManager::class.java)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dna:cast")?.apply { acquire(6 * 60 * 60_000L) }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCasting(this, stopTv = true)
            return START_NOT_STICKY
        }
        goForeground(intent?.getStringExtra(EXTRA_TEXT) ?: "Läuft auf dem Fernseher")
        // Ohne Weiterleitung (z. B. nach einem Neustart des Prozesses) gibt es nichts zu tun.
        if (relay == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Auf dem Fernseher", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📺 $text")
            .setContentText("Über das Handy weitergeleitet")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "Vordergrund nicht möglich", it) }
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 4713
        private const val ACTION_STOP = "de.ayutv.cast.STOP"
        private const val EXTRA_TEXT = "text"

        /** Laufende Weiterleitung und ihr Fernseher (gehören dem Dienst, nicht dem Player). */
        @Volatile var relay: DlnaRelay? = null
            private set
        @Volatile var renderer: DlnaCast.Renderer? = null
            private set

        /** Beendet, wenn die Benachrichtigung „Beenden“ gedrückt wurde (der Player geht dann wieder aufs Handy). */
        @Volatile var onStopped: (() -> Unit)? = null

        /** Weiterleitung übernehmen (eine vorherige wird beendet) und die Benachrichtigung zeigen. */
        fun start(context: Context, relay: DlnaRelay, renderer: DlnaCast.Renderer, text: String) {
            val old = this.relay
            this.relay = relay
            this.renderer = renderer
            if (old !== relay) old?.close()
            val intent = Intent(context, CastService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Dienst nicht gestartet", it) }
        }

        /** Weiterleitung beenden; mit [stopTv] auch den Fernseher anhalten (gibt die Verbindung beim Anbieter frei). */
        fun stopCasting(context: Context, stopTv: Boolean) {
            val r = relay
            val tv = renderer
            relay = null
            renderer = null
            r?.close()
            // Fernseher beendet: neue Player spielen wieder auf dem Handy.
            if (stopTv) PlayerActivity.activeTv = null
            if (stopTv && tv != null) {
                val container = (context.applicationContext as AyuApp).container
                container.scope.launch { runCatching { DlnaCast.stop(container.http, tv) } }
                onStopped?.invoke()
            }
            runCatching { context.stopService(Intent(context, CastService::class.java)) }
        }
    }
}

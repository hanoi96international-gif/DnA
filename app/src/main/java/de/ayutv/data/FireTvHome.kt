package de.ayutv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import de.ayutv.AppContainer
import de.ayutv.R
import de.ayutv.data.db.HistoryKind
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Fire-TV-Startseite: „Weiterschauen“ (angefangene Filme und Folgen) und Lieblingssender erscheinen in der Reihe
 * „Empfohlen von deinen Apps“ und starten direkt im Player, ohne erst DnA-TV zu öffnen. Technisch sind das
 * Empfehlungs-Benachrichtigungen, wie Fire OS sie erwartet; nur auf Fire-TV-Geräten (auf Handys stünden sie in
 * der Benachrichtigungsleiste).
 */
object FireTvHome {
    private const val TAG = "DnAHome"
    private const val CHANNEL_ID = "home_recommendations"
    private const val FIRST_ID = 9_100
    private const val MAX_ITEMS = 6

    fun supported(context: Context): Boolean = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    private data class Item(val key: String, val title: String, val text: String, val image: String?, val intent: Intent)

    suspend fun update(context: Context, container: AppContainer) {
        if (!supported(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!container.settings.current().fireTvHome) {
            clear(manager)
            return
        }
        val items = runCatching { items(context, container) }.onFailure { Log.w(TAG, "Empfehlungen", it) }.getOrDefault(emptyList())
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Fire-TV-Startseite", NotificationManager.IMPORTANCE_LOW))
            }
            items.forEachIndexed { rank, item ->
                val image = item.image?.let { load(container, it) } ?: banner(context)
                val pending = PendingIntent.getActivity(
                    context, FIRST_ID + rank, item.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val extras = android.os.Bundle().apply {
                    putString("com.amazon.extra.DISPLAY_NAME", "DnA-TV")
                    putInt("com.amazon.extra.RANK", rank)
                    putString("com.amazon.extra.CONTENT_ID", item.key)
                    putString("com.amazon.extra.LONG_DESCRIPTION", item.text)
                }
                @Suppress("DEPRECATION")
                val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context))
                    .setContentTitle(item.title)
                    .setContentText(item.text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(image)
                    .setStyle(Notification.BigPictureStyle().bigPicture(image))
                    .setContentIntent(pending)
                    .setCategory(Notification.CATEGORY_RECOMMENDATION)
                    .setLocalOnly(true)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setExtras(extras)
                manager.notify(FIRST_ID + rank, builder.build())
            }
            for (id in FIRST_ID + items.size until FIRST_ID + MAX_ITEMS) manager.cancel(id)
        }
    }

    private fun clear(manager: NotificationManager) {
        for (id in FIRST_ID until FIRST_ID + MAX_ITEMS) manager.cancel(id)
    }

    /** Angefangene Filme/Folgen (zuletzt geschaut zuerst), dann Lieblingssender mit der laufenden Sendung. */
    private suspend fun items(context: Context, container: AppContainer): List<Item> {
        val out = ArrayList<Item>()
        for (entry in container.db.history().recent(30)) {
            if (out.size >= 3) break
            if (entry.kind == HistoryKind.LIVE || entry.kind == HistoryKind.CATCHUP) continue
            val unfinished = entry.durationMs <= 0 || entry.positionMs < entry.durationMs * 0.95
            if (!unfinished || entry.positionMs < 60_000) continue
            val left = if (entry.durationMs > 0) " · noch ${(entry.durationMs - entry.positionMs) / 60_000} min" else ""
            out += Item(
                entry.key, entry.title, "Weiterschauen$left", entry.image,
                PlayerActivity.vodIntent(context, VodRequest(entry.key, entry.kind, entry.title, entry.subtitle, entry.image, entry.url)),
            )
        }
        val favorites = container.db.channels().favorites().take(MAX_ITEMS - out.size)
        val now = container.content.nowPlaying(favorites)
        for (channel in favorites) {
            val programme = channel.epgId?.let { now[it] }
            out += Item(
                channel.key, channel.name, programme?.title?.let { "Jetzt: $it" } ?: "Live TV", channel.logo,
                PlayerActivity.liveIntent(context, channel.key, group = null, favoritesOnly = true),
            )
        }
        return out.take(MAX_ITEMS)
    }

    /** Bild laden und auf höchstens 640 px Breite verkleinern (Fire OS lehnt große Bilder ab, Speicher). */
    private fun load(container: AppContainer, url: String): Bitmap? = runCatching {
        container.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun banner(context: Context): Bitmap {
        val drawable = context.getDrawable(R.drawable.banner)!!
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

package de.ayutv.player.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import de.ayutv.ui.components.hasTouchscreen

/** Einstellungen für Chromecast: der Standard-Empfänger von Google (spielt HLS und MP4). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Vom Handy auf einen Chromecast / Google-TV-Fernseher streamen. Nur mit Google-Play-Diensten (also nicht
 * auf Fire TV) und nur auf Geräten mit Touchscreen. Der Fernseher holt den Stream selbst beim Anbieter.
 */
object Cast {
    private const val TAG = "Cast"

    data class Media(val url: String, val title: String, val subtitle: String?, val image: String?, val live: Boolean, val positionMs: Long)

    fun available(context: Context): Boolean = context.hasTouchscreen() && runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    private fun castContext(context: Context): CastContext? =
        if (!available(context)) null else runCatching { CastContext.getSharedInstance(context) }.onFailure { Log.w(TAG, "Cast nicht verfügbar", it) }.getOrNull()

    fun connected(context: Context): Boolean = castContext(context)?.sessionManager?.currentCastSession?.isConnected == true

    /** Gerätewahl zeigen; ist eins verbunden, [onConnected] (auch sofort, wenn schon verbunden). */
    fun choose(context: Context, onConnected: (deviceName: String) -> Unit, onEnded: () -> Unit) {
        val cast = castContext(context) ?: return
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let {
            onConnected(it.castDevice?.friendlyName ?: "Chromecast")
            return
        }
        cast.sessionManager.addSessionManagerListener(object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session.castDevice?.friendlyName ?: "Chromecast")
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session.castDevice?.friendlyName ?: "Chromecast")
            override fun onSessionEnded(session: CastSession, error: Int) {
                cast.sessionManager.removeSessionManagerListener(this, CastSession::class.java)
                onEnded()
            }
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }, CastSession::class.java)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        runCatching {
            MediaRouteChooserDialog(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog)).apply {
                routeSelector = selector
                show()
            }
        }.onFailure { Log.w(TAG, "Auswahl nicht möglich", it) }
    }

    fun play(context: Context, media: Media): Boolean {
        val session = castContext(context)?.sessionManager?.currentCastSession ?: return false
        val client = session.remoteMediaClient ?: return false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            media.image?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(castUrl(media.url, media.live))
            .setStreamType(if (media.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(castUrl(media.url, media.live)))
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(if (media.live) 0 else media.positionMs).build())
        return true
    }

    fun stop(context: Context) {
        castContext(context)?.sessionManager?.endCurrentSession(true)
    }

    /** Chromecast spielt kein rohes MPEG-TS: Xtream-Livesender als HLS anfordern. */
    fun castUrl(url: String, live: Boolean): String =
        if (live && Regex("""/live/[^/]+/[^/]+/\d+\.ts$""").containsMatchIn(url)) url.removeSuffix(".ts") + ".m3u8" else url

    fun contentType(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }
}

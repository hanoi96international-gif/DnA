package de.ayutv.data.repo

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.ayutv.AppContainer
import de.ayutv.AyuApp
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.player.PlayerActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Sender-Check in der Ruhezeit: Fassungen doppelt geführter Sender (und Favoriten) kurz unsichtbar anspielen,
 * ohne Bild und Ton zu dekodieren. Gemessen werden Auflösung, Startzeit und ob die Fassung überhaupt läuft.
 * Damit zeigt die Liste je Sender die wirklich beste Fassung, der Qualitätsfilter kennt die echte Auflösung, und
 * ausgefallene Fassungen landen hinten. Läuft nie, während ein Film oder Sender läuft (Verbindungslimit).
 */
class ChannelCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AyuApp).container
        if (!container.settings.current().channelCheck) return Result.success()
        val quick = inputData.getBoolean(KEY_QUICK, false)
        // Schwaches Gerät und die Menüs sind offen: später noch einmal (in 10 Minuten), damit die Bedienung flüssig bleibt.
        if (quick && de.ayutv.ui.UiPerformance.reduced && container.foreground?.get() != null) return Result.retry()
        runCatching {
            val check = ChannelCheck(applicationContext, container)
            if (quick) check.run(QUICK_BUDGET_MS, maxChannels = QUICK_CHANNELS, quick = true) else check.run(BUDGET_MS)
        }.onFailure { Log.w(TAG, "Sender-Check", it) }
        return Result.success()
    }

    companion object {
        const val TAG = "DnAChannelCheck"
        private const val BUDGET_MS = 8 * 60_000L
        private const val QUICK_BUDGET_MS = 5 * 60_000L
        private const val QUICK_CHANNELS = 40
        private const val KEY_QUICK = "quick"

        private fun constraints(idle: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .apply { if (idle) setRequiresDeviceIdle(true) }
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChannelCheckWorker>(6, TimeUnit.HOURS).setConstraints(constraints(idle = true)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("channel-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChannelCheckWorker>().setConstraints(constraints(idle = false)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("channel-check-now", ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Kurzer Check gleich nach dem Laden einer Playlist (nicht erst nachts): Favoriten, Sportsender und zuletzt
         * geschaute Sender, damit „Best of“ und die beste Fassung je Sender sofort etwas zeigen. Eine Minute
         * Verzögerung, damit der Import und das erste Umschalten nicht um die Verbindung konkurrieren.
         */
        fun runQuick(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChannelCheckWorker>()
                .setConstraints(constraints(idle = false))
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(KEY_QUICK to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("channel-check-quick", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Je Sendername nur eine Fassung (die nach Namen beste), damit der kurze Check viele verschiedene Sender schafft. */
internal object ContentPicks {
    fun onePerName(channels: List<ChannelEntity>): List<ChannelEntity> =
        channels.groupBy { ChannelVariants.key(it.name) ?: it.name.lowercase() }.values
            .map { group -> group.minBy { ChannelQuality.score(it.key, it.name) } }
}

/** Die eigentliche Prüfung (auch direkt aufrufbar, z. B. im Gerätetest). */
class ChannelCheck(private val context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("channel_check", Context.MODE_PRIVATE)

    /** Ergebnis des letzten Laufs (für die Einstellungen). */
    val lastResult: String? get() = prefs.getString("last", null)

    /** [quick]: nur Favoriten, Sportsender und zuletzt geschaute Sender (für „Best of“ gleich nach dem Import). */
    suspend fun run(budgetMs: Long, maxChannels: Int = Int.MAX_VALUE, quick: Boolean = false): String {
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val all = container.db.channels().all()
        val favorites = container.db.favorites().keys().toHashSet()
        val recent = container.db.history().recentLiveKeys(40).toHashSet()
        fun sport(c: ChannelEntity) = de.ayutv.data.parse.ProgrammeText.isSportChannel(c.name) || c.groupTitle.contains("sport", ignoreCase = true)
        // Zuerst Favoriten, dann zuletzt geschaute, dann Sport, dann Fassungen doppelter Sender (dort entscheidet der
        // Check); je seit drei Tagen ungeprüft.
        val variants = if (quick) emptyList() else ChannelVariants.groups(all, { it.name }, { it.playlistId }).flatten()
        val sportChannels = if (quick) all.filter(::sport).let { list -> ContentPicks.onePerName(list) } else emptyList()
        val candidates = (all.filter { it.key in favorites } + all.filter { it.key in recent } + sportChannels + variants).distinctBy { it.key }
            .filter { ChannelQuality.info(it.key)?.let { info -> now - info.checkedAt > RECHECK_MS } ?: true }
            .sortedBy { if (it.key in favorites) 0 else if (it.key in recent) 1 else 2 }
        var checked = 0
        var working = 0
        // Schwaches Gerät: nicht prüfen, während jemand die Menüs bedient (Netz und Rechner gehören der Bedienung).
        val weak = de.ayutv.ui.UiPerformance.reduced
        for (channel in candidates) {
            if (checked >= maxChannels || SystemClock.elapsedRealtime() - started > budgetMs) break
            // Läuft etwas im Player: aufhören, sonst nähme der Check die einzige Verbindung beim Anbieter weg.
            val shown = container.foreground?.get()
            if (shown is PlayerActivity || (weak && shown != null)) break
            val result = probe(channel, stopWhenWatching = true)
            // Mitten im Test wurde ein Sender gestartet: sofort aufgehört, nichts eintragen.
            if (result.first < 0) break
            ChannelQuality.recordCheck(channel.key, result.first, result.second)
            checked++
            if (result.first > 0) working++
            Log.i(ChannelCheckWorker.TAG, "${channel.name}: ${if (result.first > 0) "${result.first}p, ${result.second} ms" else "läuft nicht"}")
        }
        ChannelQuality.saveIfChanged(container.qualityFile)
        val summary = "$checked Fassungen geprüft, $working laufen" + (if (candidates.size > checked) ", ${candidates.size - checked} folgen beim nächsten Mal" else "")
        prefs.edit().putString("last", "${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())}: $summary").apply()
        return summary
    }

    /**
     * Auflösung (Höhe, 0 = läuft nicht) und Zeit bis zur ersten Bildinformation. Mit [stopWhenWatching] bricht der Test
     * sofort ab (Höhe −1), sobald der Player startet: Viele Zugänge erlauben nur eine Verbindung, und der Test darf
     * sie dem gerade gestarteten Sender nicht wegnehmen.
     */
    suspend fun probe(channel: ChannelEntity, stopWhenWatching: Boolean = false): Pair<Int, Long> {
        val started = SystemClock.elapsedRealtime()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            kotlinx.coroutines.coroutineScope {
                val job = async(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        de.ayutv.player.dna.engine.DnaProbe.videoHeight(container.http, channel.url, channel.userAgent ?: DEFAULT_AGENT) {
                            stopped.get() || (stopWhenWatching && container.foreground?.get() is PlayerActivity)
                        }
                    }.getOrDefault(0)
                }
                val height = job.await()
                when {
                    height < 0 -> -1 to 0L
                    height > 0 -> height to SystemClock.elapsedRealtime() - started
                    else -> 0 to 0L
                }
            }
        } ?: (0 to 0L)
        stopped.set(true)
        // Dem Anbieter Zeit geben, die Verbindung zu schließen (sonst 403 „zu viele Verbindungen“ beim nächsten).
        if (result.first >= 0) kotlinx.coroutines.delay(600)
        return result
    }

    private companion object {
        const val RECHECK_MS = 3 * 24 * 3_600_000L
        const val PROBE_TIMEOUT_MS = 10_000L
        const val DEFAULT_AGENT = de.ayutv.data.AppSettings.DEFAULT_USER_AGENT
    }
}

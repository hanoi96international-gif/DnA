package de.ayutv.data.repo

import de.ayutv.data.parse.Hid

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Vorübersetzen in der Ruhezeit: Für „Weiter schauen“, die nächste Folge angefangener Serien und die Merkliste
 * werden Untertitel in der gewünschten Sprache vorbereitet. Zuerst aus den eingebetteten Untertiteln der Datei
 * (passen immer exakt, [EmbeddedPrep]), nur ohne solche von OpenSubtitles. Beim Schauen sind sie dann sofort da.
 * Läuft nur, wenn das Gerät nicht benutzt wird; der Stand bleibt erhalten und geht beim nächsten Mal weiter.
 */
class PretranslateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AyuApp).container
        // Als Vordergrund-Arbeit darf sie länger als 10 Minuten laufen: Einen Film einmal zu lesen dauert länger.
        val long = runCatching { setForeground(foregroundInfo()) }.onFailure { Log.i(TAG, "Nur kurze Etappe: ${it.message}") }.isSuccess
        val manual = inputData.getBoolean(KEY_NOW, false)
        return runCatching { Pretranslation(applicationContext, container).run(if (long) LONG_BUDGET_MS else BUDGET_MS, manual) }
            .onFailure { Log.w(TAG, "Vorübersetzen", it) }
            .fold({ Result.success() }, { Result.success() })
            // Übersetzer-Prozess beenden (Speicher), außer ein Film läuft inzwischen und braucht ihn.
            .also { if (container.foreground?.get() !is de.ayutv.player.PlayerActivity) runCatching { Translators.remote.release() } }
    }

    private fun foregroundInfo(): androidx.work.ForegroundInfo {
        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID, "Untertitel vorbereiten", android.app.NotificationManager.IMPORTANCE_MIN))
        }
        @Suppress("DEPRECATION")
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) android.app.Notification.Builder(applicationContext, CHANNEL_ID)
        else android.app.Notification.Builder(applicationContext)
        val notification = builder
            .setSmallIcon(de.ayutv.R.drawable.ic_notification)
            .setContentTitle("DnA-TV bereitet Untertitel vor")
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else androidx.work.ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val TAG = "DnAPretranslate"
        /** „Jetzt vorbereiten“: auch außerhalb der Nacht Filme lesen. */
        val KEY_NOW = Hid.s("KgE2")
        /** Ohne Vordergrund bricht WorkManager nach 10 Minuten ab: rechtzeitig aufhören und den Stand sichern. */
        private const val BUDGET_MS = 8 * 60_000L
        /** Mit Vordergrund: Zeit, um bis zu drei Filme zu lesen und zu übersetzen. */
        private const val LONG_BUDGET_MS = 3 * 60 * 60_000L
        private const val CHANNEL_ID = "prepare"
        private const val NOTIFICATION_ID = 4713

        private fun constraints(idle: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .apply { if (idle) setRequiresDeviceIdle(true) }
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PretranslateWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints(idle = true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("pretranslate", ExistingPeriodicWorkPolicy.KEEP, request)
            scheduleNight(context)
        }

        /**
         * Zusätzlich jede Nacht um 2:30 Uhr, ohne auf „Gerät ungenutzt“ zu warten: Ob und wann der Fire TV das meldet,
         * ist nicht sicher. Läuft gerade ein Film, hört die Vorbereitung von selbst sofort auf.
         */
        private fun scheduleNight(context: Context) {
            val now = java.util.Calendar.getInstance()
            val next = (now.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 2)
                set(java.util.Calendar.MINUTE, 30)
                set(java.util.Calendar.SECOND, 0)
                if (!after(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val request = PeriodicWorkRequestBuilder<PretranslateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints(idle = false))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("pretranslate-night", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Gleich eine Etappe (Einstellungen „Jetzt vorbereiten“), ohne auf Ruhe zu warten. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PretranslateWorker>().setConstraints(constraints(idle = false))
                .setInputData(androidx.work.workDataOf(KEY_NOW to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pretranslate-now", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Die eigentliche Arbeit (auch ohne WorkManager aufrufbar, z. B. im Gerätetest). */
class Pretranslation(private val context: Context, private val container: AppContainer) {
    data class Item(val key: String, val url: String, val title: String, val durationMs: Long, val language: String)

    private val prefs = context.getSharedPreferences("pretranslate", Context.MODE_PRIVATE)

    /** Was zuletzt vorbereitet wurde (für die Einstellungen). */
    val lastResult: String? get() = prefs.getString("last", null)

    suspend fun run(budgetMs: Long, manual: Boolean = false): String {
        // Erst aufräumen (lange unbenutzte Modelle und Untertitel), dann vorbereiten.
        runCatching { de.ayutv.data.StorageCleaner.clean(context) }.onSuccess { Log.i(PretranslateWorker.TAG, "Speicher: $it") }
        val settings = container.settings.current()
        if (!settings.pretranslate) return "aus"
        val started = SystemClock.elapsedRealtime()
        fun timeLeft() = budgetMs - (SystemClock.elapsedRealtime() - started)
        val done = mutableListOf<String>()
        val embedded = EmbeddedPrep(context, container)
        // Wird geschaut, hört das Lesen auf: Das Konto beim Anbieter erlaubt oft nur eine Verbindung.
        // Auch nicht, solange dieses Gerät zum Fernseher weiterleitet (Handy gesperrt, Player nicht vorn). Und Filme lesen
        // nur nachts (1–7 Uhr) oder auf „Jetzt vorbereiten“: Tagsüber schaut vielleicht ein anderes Gerät mit demselben
        // Konto (Handy, Fernseher) – das zweite Lesen ließe dort das Bild alle paar Sekunden abbrechen.
        fun night() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 1..6
        fun idle() = container.foreground?.get() !is de.ayutv.player.PlayerActivity &&
            de.ayutv.player.CastService.relay == null && (manual || night())
        for (item in items(settings.subtitleLanguage, settings.subtitlesAutoOn, settings.lastTranslationTarget)) {
            if (timeLeft() < 30_000 || !idle()) break
            // 0. Eingebettete Untertitel gehen vor: aus der Datei selbst, passen immer exakt.
            if (settings.translationEnabled && embedded.readable(item.url)) {
                val before = embedded.isDone(item.key, item.language)
                when (embedded.prepare(item.key, item.url, item.language, ::timeLeft, ::idle)) {
                    EmbeddedPrep.Outcome.DONE -> {
                        if (!before) done += "${item.title}: aus den eingebetteten Untertiteln übersetzt"
                        continue
                    }
                    EmbeddedPrep.Outcome.HAS_TARGET -> continue
                    EmbeddedPrep.Outcome.PAUSED -> {
                        done += "${item.title}: angefangen, geht beim nächsten Mal weiter"
                        break
                    }
                    // Heute keine weiteren Filme lesen; ob sie eingebettete Untertitel haben, ist noch offen.
                    EmbeddedPrep.Outcome.LIMIT -> continue
                    EmbeddedPrep.Outcome.NONE, EmbeddedPrep.Outcome.FAILED -> Unit
                }
            }
            if (container.subtitles.cached(item.key, item.language) != null) continue
            val film = SubtitleRepository.FilmInfo(durationMs = item.durationMs)
            // 1. Fertige Untertitel in der Sprache (ein Download, höchstens das Tagesbudget)
            if (settings.onlineSubtitlesEnabled && takeDownload(settings.openSubtitlesUser.isNotBlank())) {
                val found = runCatching { container.subtitles.find(item.key, item.url, item.title, item.language, settings, film) }
                    .onFailure { Log.w(PretranslateWorker.TAG, "Suche ${item.title}", it) }.getOrNull()
                if (found != null) {
                    done += "${item.title}: Untertitel geladen"
                    continue
                }
            }
            // 2. Aus einer anderen Sprache auf dem Gerät übersetzen
            if (!settings.translationEnabled) continue
            if (container.subtitles.cachedAll(item.key).isEmpty() && !(settings.onlineSubtitlesEnabled && takeDownload(settings.openSubtitlesUser.isNotBlank()))) continue
            val source = runCatching { container.subtitles.findSource(item.key, item.url, item.title, item.language, settings, film) }
                .getOrNull() ?: continue
            if (source.language == item.language) continue
            val finished = translate(item, source, timeLeft = ::timeLeft) ?: break
            done += "${item.title}: übersetzt ($finished Zeilen)"
        }
        val summary = if (done.isEmpty()) "nichts zu tun" else done.joinToString(" · ")
        prefs.edit().putString("last", "${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())}: $summary").apply()
        Log.i(PretranslateWorker.TAG, summary)
        return summary
    }

    /** Übersetzt die Datei Stück für Stück; null, wenn die Zeit vorher endet (der Stand bleibt gespeichert). */
    private suspend fun translate(item: Item, source: SubtitleRepository.Result, timeLeft: () -> Long): Int? {
        val cues = runCatching { Srt.parse(source.file.readText()) }.getOrDefault(emptyList())
        if (cues.isEmpty()) return 0
        val partial = container.subtitles.lineTranslations(item.key, PARTIAL_TRACK, item.language).toMutableMap()
        fun startOf(cue: SrtCue) = millis(cue.start) ?: 0L
        // Sätze über mehrere Einblendungen als Ganzes übersetzen (wie im Player).
        val sentenceCues = cues.map { de.ayutv.data.translate.SubtitleSentences.Cue(startOf(it), millis(it.end) ?: startOf(it), it.text) }
        val groups = de.ayutv.data.translate.SubtitleSentences.groups(sentenceCues)
        val translator = Translators.from(container.settings.current()) ?: Translators.remote
        // Namen der Serie schützen (wie im Player) und kurze Zeilen mit dem Satz davor übersetzen.
        val seriesKey = de.ayutv.data.parse.Episodes.seriesKeyOf(item.key) ?: item.key
        val names = de.ayutv.data.translate.SeriesNames(context)
            .add(seriesKey, de.ayutv.data.translate.TranslationNames.learn(cues.map { it.text }))
        val smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
        try {
            for (chunk in groups.filter { range -> range.any { startOf(cues[it]) !in partial } }.chunked(BATCH)) {
                // Läuft ein Film, gehört der Übersetzer ihm: sofort aufhören (der Stand bleibt gespeichert).
                if (timeLeft() < 20_000 || container.foreground?.get() is de.ayutv.player.PlayerActivity) return null
                val before = groups.getOrNull(groups.indexOf(chunk.first()) - 1)?.let { range ->
                    val done = range.map { partial[startOf(cues[it])] }
                    if (done.any { it == null }) null
                    else de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, range) to done.joinToString(" ") { it!!.replace('\n', ' ') }
                }
                val result = smart.translate(chunk.map { de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, it) }, item.language, source.language, before)
                chunk.forEachIndexed { k, range ->
                    val sentence = result.getOrNull(k) ?: return@forEachIndexed
                    val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, range.map { Srt.plainText(cues[it].text).length })
                    range.forEachIndexed { j, index -> partial[startOf(cues[index])] = de.ayutv.data.translate.SubtitleSentences.wrap(parts[j]) }
                }
            }
        } finally {
            container.subtitles.saveLineTranslations(item.key, PARTIAL_TRACK, item.language, partial)
        }
        val translated = cues.map { it.copy(text = partial[startOf(it)] ?: it.text) }
        container.subtitles.saveTranslation(item.key, item.language, translated)
        // Gleiche Zeiten wie die Vorlage: deren Ausrichtung (z. B. Bildrate) gilt auch hier.
        source.language?.let { container.subtitles.timing(item.key, it) }?.let { container.subtitles.saveTiming(item.key, item.language, it) }
        return cues.size
    }

    /**
     * Angefangene Filme und Folgen, dazu die nächste Folge jeder angefangenen Serie (zuletzt geschaut zuerst),
     * danach die Merkliste (in der zuletzt gewählten Übersetzungssprache).
     */
    private suspend fun items(defaultLanguage: String, autoOn: Boolean, lastTarget: String): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun language(key: String): String? {
            val memory = container.playerMemory.get(Episodes.seriesKeyOf(key) ?: key)
            if (memory.textOff) return null
            return memory.load ?: defaultLanguage.takeIf { autoOn }
        }
        // Mit großem Kontingent (VIP) mehr Filme vorbereiten.
        val maxItems = if (OpenSubtitlesQuota.generous) MAX_ITEMS_GENEROUS else MAX_ITEMS
        for (entry in container.db.history().recent(40)) {
            if (entry.kind == HistoryKind.LIVE || out.size >= maxItems) continue
            val unfinished = entry.durationMs <= 0 || entry.positionMs < entry.durationMs * 0.95
            if (unfinished && entry.positionMs > 60_000) {
                language(entry.key)?.let { out.putIfAbsent(entry.key, Item(entry.key, entry.url, entry.title, entry.durationMs, it)) }
            }
            if (entry.kind == HistoryKind.EPISODE) {
                // Mit großem Kontingent (VIP) die nächsten Folgen, sonst nur die nächste.
                val ahead = if (OpenSubtitlesQuota.generous) EPISODES_AHEAD_GENEROUS else 1
                for (next in runCatching { container.content.nextEpisodes(entry.key, ahead) }.getOrDefault(emptyList())) {
                    if (out.size >= maxItems) break
                    val key = Episodes.key(next.series.key, next.episode.id)
                    language(key)?.let { out.putIfAbsent(key, Item(key, next.url, Episodes.title(next.series.name, next.episode), 0L, it)) }
                }
            }
        }
        if (lastTarget.isNotEmpty()) {
            val watchlist = runCatching { container.db.vod().observeFavorites().first() }.getOrDefault(emptyList())
            for (film in watchlist) {
                if (out.size >= maxItems) break
                val memory = container.playerMemory.get(film.key)
                if (memory.textOff) continue
                out.putIfAbsent(film.key, Item(film.key, film.url, film.name, 0L, memory.load ?: lastTarget))
            }
        }
        return out.values.take(maxItems)
    }

    /** OpenSubtitles erlaubt ohne Konto 5 Downloads am Tag: höchstens 2 davon nachts, mit Konto 8 von 20. */
    private fun takeDownload(account: Boolean): Boolean {
        val day = System.currentTimeMillis() / 86_400_000L
        val used = if (prefs.getLong("day", -1) == day) prefs.getInt("downloads", 0) else 0
        if (used >= OpenSubtitlesQuota.nightlyBudget(account)) return false
        prefs.edit().putLong("day", day).putInt("downloads", used + 1).apply()
        return true
    }

    private fun millis(value: String): Long? {
        val m = TIME.matchEntire(value.trim()) ?: return null
        val (h, min, sec, ms) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    companion object {
        /** Name der Zwischenablage für halb übersetzte Dateien (auch der Player liest sie). */
        const val PARTIAL_TRACK = "file"
        /** Klein, damit ein gerade gestarteter Film nie lange auf den Übersetzer warten muss. */
        private const val BATCH = 2
        private const val MAX_ITEMS = 6
        private const val MAX_ITEMS_GENEROUS = 40
        private const val EPISODES_AHEAD_GENEROUS = 5
        private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    }
}

package de.ayutv.data.repo

import android.content.Context
import android.util.Log
import de.ayutv.AppContainer
import de.ayutv.data.translate.SubtitleSentences
import de.ayutv.player.EmbeddedReader
import de.ayutv.player.EmbeddedSubtitles
import de.ayutv.player.isForcedSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Nächtliches Vorbereiten aus den eingebetteten Untertiteln: Die Filmdatei wird einmal gelesen (Bild und Ton
 * verworfen), die Untertitelspur – Englisch bevorzugt – gemerkt und in Ruhe übersetzt. Beim Schauen lädt der
 * Player diese Zeilen sofort (gleiche Spur-Kennung und Zeiten), ganz ohne Vorlauf und ohne Download-Datei.
 * Zwischenstände bleiben erhalten: Lesen und Übersetzen gehen in der nächsten Ruhezeit weiter.
 */
class EmbeddedPrep(private val context: Context, private val container: AppContainer) {
    enum class Outcome {
        /** Fertig übersetzt (jetzt oder früher). */
        DONE,
        /** Zeit um oder Film wird geschaut: geht beim nächsten Mal weiter. */
        PAUSED,
        /** Datei hat keine passenden eingebetteten Untertitel: dann wie bisher (OpenSubtitles). */
        NONE,
        /** Datei hat schon Untertitel in der Wunschsprache: nichts zu tun. */
        HAS_TARGET,
        /** Für heute genug Filme gelesen (je Nacht höchstens [MAX_READS_PER_DAY]). */
        LIMIT,
        /** Lesen nicht möglich (Netz, zu groß, kein Dateiformat): wie bisher weiter. */
        FAILED,
    }

    private val prefs = context.getSharedPreferences("embedded_prep", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "embedded-lines")

    /** Nur Dateien (mkv, mp4 …) lassen sich so lesen, keine Live-Streams oder HLS-Listen. */
    fun readable(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".ts") && !path.endsWith(".mpd") && !path.contains("/live/")
    }

    suspend fun prepare(key: String, url: String, language: String, timeLeft: () -> Long, keepGoing: () -> Boolean): Outcome {
        val state = prefs.getString(stateKey(key), null)?.split('\t')
        val status = state?.getOrNull(0)
        when (status) {
            "none" -> return Outcome.NONE
            "target-$language" -> return Outcome.HAS_TARGET
        }
        if (status == "done-$language") return Outcome.DONE
        val read = status == "read"
        var track = state?.getOrNull(1).orEmpty()
        var source = state?.getOrNull(2)
        if (!read) {
            val resumeUs = state?.getOrNull(3)?.toLongOrNull() ?: 0L
            // Neuer Film (nicht weiterlesen): zählt gegen die Grenze je Nacht.
            if (resumeUs == 0L && !takeRead()) return Outcome.LIMIT
            val settings = container.settings.current()
            val reader = EmbeddedReader(container.http, settings.userAgent)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    reader.read(
                        url = url,
                        resumeUs = resumeUs,
                        maxBytes = MAX_BYTES,
                        choose = { formats ->
                            val chosen = EmbeddedReader.bestSource(formats, language)
                            if (chosen == null) {
                                val wanted = de.ayutv.data.Languages.normalize(language)
                                val hasTarget = formats.any { !it.isForcedSubtitle() && de.ayutv.data.Languages.normalize(it.language) == wanted }
                                save(key, if (hasTarget) "target-$language" else "none")
                            }
                            chosen
                        },
                        recognize = { de.ayutv.data.repo.Translators.remote.recognize(it) },
                        keepGoing = { keepGoing() && timeLeft() > 60_000 },
                    )
                }
            }.getOrElse {
                Log.w(TAG, "Lesen fehlgeschlagen: ${it.message}")
                return Outcome.FAILED
            }
            val format = result.format ?: return when (prefs.getString(stateKey(key), null)?.substringBefore('\t')) {
                "target-$language" -> Outcome.HAS_TARGET
                "none" -> Outcome.NONE
                // Abgebrochen, bevor die Spuren bekannt waren: nächstes Mal weiter.
                else -> Outcome.PAUSED
            }
            track = EmbeddedSubtitles.trackKey(format)
            source = de.ayutv.data.Languages.normalize(format.language)
            val lines = merge(readLines(key), result.lines)
            writeLines(key, lines)
            Log.i(TAG, "$key: ${lines.size} Zeilen (${source ?: "?"}), ${if (result.complete) "fertig gelesen" else "bis ${result.reachedUs / 60_000_000} min"}")
            if (!result.complete) {
                save(key, "reading", track, source, result.reachedUs.toString())
                return Outcome.PAUSED
            }
            save(key, "read", track, source)
        }
        val finished = translate(key, track, source, language, timeLeft, keepGoing)
        if (finished) save(key, "done-$language", track, source)
        return if (finished) Outcome.DONE else Outcome.PAUSED
    }

    /** Übersetzt die gelesenen Zeilen satzweise (wie im Player); false, wenn die Zeit vorher endet. */
    private suspend fun translate(key: String, track: String, source: String?, language: String, timeLeft: () -> Long, keepGoing: () -> Boolean): Boolean {
        val lines = readLines(key)
        if (lines.isEmpty()) return true
        val done = container.subtitles.lineTranslations(key, track, language).toMutableMap()
        val cues = lines.map { SubtitleSentences.Cue(it.startMs, if (it.endMs >= 0) it.endMs else it.startMs + 3_000, it.text) }
        val groups = SubtitleSentences.groups(cues)
        val translator = Translators.from(container.settings.current()) ?: Translators.remote
        val seriesKey = de.ayutv.data.parse.Episodes.seriesKeyOf(key) ?: key
        val names = de.ayutv.data.translate.SeriesNames(context).add(seriesKey, de.ayutv.data.translate.TranslationNames.learn(lines.map { it.text }))
        val smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
        try {
            for (chunk in groups.filter { range -> range.any { cues[it].startMs !in done } }.chunked(BATCH)) {
                if (timeLeft() < 20_000 || !keepGoing()) return false
                val before = groups.getOrNull(groups.indexOf(chunk.first()) - 1)?.let { range ->
                    val previous = range.map { done[cues[it].startMs] }
                    if (previous.any { it == null }) null
                    else SubtitleSentences.joined(cues, range) to previous.joinToString(" ") { it!!.replace('\n', ' ') }
                }
                val result = smart.translate(chunk.map { SubtitleSentences.joined(cues, it) }, language, source, before)
                chunk.forEachIndexed { k, range ->
                    val sentence = result.getOrNull(k) ?: return@forEachIndexed
                    val parts = SubtitleSentences.split(sentence, range.map { de.ayutv.data.parse.Srt.plainText(cues[it].text).length })
                    range.forEachIndexed { j, index -> done[cues[index].startMs] = SubtitleSentences.wrap(parts[j]) }
                }
            }
        } finally {
            container.subtitles.saveLineTranslations(key, track, language, done)
        }
        return true
    }

    /** Ist der Film für [language] fertig vorbereitet? */
    fun isDone(key: String, language: String): Boolean = prefs.getString(stateKey(key), null)?.startsWith("done-$language") == true

    /** Vorbereitete Übersetzungen (Beginn der Zeile → Text), wie der Player sie lädt. */
    fun translations(key: String, language: String): Map<Long, String> {
        val track = prefs.getString(stateKey(key), null)?.split('\t')?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return container.subtitles.lineTranslations(key, track, language)
    }

    private fun merge(old: List<EmbeddedReader.Line>, new: List<EmbeddedReader.Line>): List<EmbeddedReader.Line> =
        (old.associateBy { it.startMs } + new.associateBy { it.startMs }).values.sortedBy { it.startMs }

    private fun linesFile(key: String) = File(dir, safeName(key) + ".tsv")

    private fun readLines(key: String): List<EmbeddedReader.Line> = runCatching {
        linesFile(key).readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) null
            else EmbeddedReader.Line(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].toLongOrNull() ?: -1L, parts[2].replace("\\n", "\n"))
        }
    }.getOrDefault(emptyList())

    private fun writeLines(key: String, lines: List<EmbeddedReader.Line>) {
        dir.mkdirs()
        val target = linesFile(key)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(lines.joinToString("\n", postfix = "\n") { "${it.startMs}\t${it.endMs}\t" + it.text.replace("\n", "\\n").replace('\t', ' ') })
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun save(key: String, vararg values: String?) {
        prefs.edit().putString(stateKey(key), values.joinToString("\t") { it.orEmpty() }).apply()
    }

    private fun stateKey(key: String) = "state:$key"

    /** Je Nacht höchstens [MAX_READS_PER_DAY] neue Filme lesen (jeder ist einige GB groß). */
    private fun takeRead(): Boolean {
        val day = System.currentTimeMillis() / 86_400_000L
        val used = if (prefs.getLong("day", -1) == day) prefs.getInt("reads", 0) else 0
        if (used >= MAX_READS_PER_DAY) return false
        prefs.edit().putLong("day", day).putInt("reads", used + 1).apply()
        return true
    }

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    companion object {
        private const val TAG = "DnAEmbeddedPrep"
        const val MAX_READS_PER_DAY = 3
        /** Größere Dateien (4K-Remuxe) nicht nachts herunterladen. */
        private const val MAX_BYTES = 15_000_000_000L
        /** Klein, damit ein gerade gestarteter Film nie lange auf den Übersetzer warten muss. */
        private const val BATCH = 2
    }
}

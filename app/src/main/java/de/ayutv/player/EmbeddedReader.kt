@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Liest die eingebetteten Untertitel einer ganzen Filmdatei, ohne sie abzuspielen (nachts, zum Vorbereiten):
 * Die Datei wird einmal durchgelesen, Bild und Ton werden verworfen, nur die Untertitel bleiben. Dieselben
 * Extraktoren wie im Player, daher dieselben Spur-Kennungen: Die Übersetzungen passen beim Schauen zur Spur.
 * Bild-Untertitel werden unterwegs per Texterkennung gelesen (das bremst das Lesen, der Speicher bleibt klein).
 */
class EmbeddedReader(private val http: OkHttpClient, private val userAgent: String) {
    class Result(
        /** Gewählte Spur, null = keine passende (oder schon eine in der Zielsprache). */
        val format: Format?,
        val lines: List<Line>,
        /** Bis zum Ende gelesen. */
        val complete: Boolean,
        /** Bis hierhin gelesen (für das nächste Mal), Mikrosekunden Filmzeit. */
        val reachedUs: Long,
        val bytes: Long,
    )

    class Line(val startMs: Long, val endMs: Long, val text: String)

    /** Abbruch von außen (Film wird geschaut, Zeit um), [Result] enthält das bis dahin Gelesene. */
    private class Stop : Exception()

    /**
     * Liest [url] ab [resumeUs] mit den Lesern der DNA Engine (MKV, MP4): Bild und Ton werden übersprungen, nur die
     * Untertitel bleiben. [choose] wählt aus den Untertitelspuren die zu übersetzende (null = keine, dann wird gar nicht
     * weitergelesen). [recognize] liest Bild-Untertitel; [keepGoing] wird laufend gefragt.
     */
    suspend fun read(
        url: String,
        resumeUs: Long,
        maxBytes: Long,
        choose: (List<Format>) -> Format?,
        recognize: suspend (EmbeddedSubtitles.GrayImage) -> String,
        keepGoing: () -> Boolean,
    ): Result {
        val store = EmbeddedSubtitles(keepImages = true)
        val source = de.ayutv.player.dna.engine.HttpByteSource(http, url, userAgent, retries = MAX_ERRORS)
        var chosen: Format? = null
        var complete = false
        var reachedUs = resumeUs
        var ocrFromMs = 0L
        var total = -1L
        try {
            source.connect()
            total = source.length
            if (total > maxBytes) throw IOException("Datei zu groß (${total / 1_000_000} MB)")
            val reader = de.ayutv.player.dna.engine.SourceReader(source)
            val head = reader.readBytes(16)
            reader.seek(0)
            val demuxer: de.ayutv.player.dna.engine.ContainerDemuxer = when {
                de.ayutv.player.dna.engine.MkvDemuxer.sniff(head) -> de.ayutv.player.dna.engine.MkvDemuxer(reader)
                de.ayutv.player.dna.engine.Mp4Demuxer.sniff(head) -> de.ayutv.player.dna.engine.Mp4Demuxer(reader)
                else -> return Result(null, emptyList(), false, resumeUs, total)
            }
            val info = demuxer.init()
            val texts = info.tracks.filter { it.codec.kind == de.ayutv.player.dna.engine.Kind.TEXT }
            val formats = texts.map { de.ayutv.player.dna.engine.DnaEnginePlayer.textFormat(it) }
            val format = choose(formats) ?: return Result(null, emptyList(), false, resumeUs, total)
            chosen = format
            val track = texts[formats.indexOf(format)]
            if (store.isBitmap(format)) store.readImagesOf(format)
            val cues = de.ayutv.player.dna.engine.SubtitleCues(track)
            if (resumeUs > 0 && info.seekable) demuxer.seek(resumeUs)
            var reads = 0L
            while (true) {
                val sample = demuxer.read() ?: run { complete = true; null } ?: break
                if (sample.track.id == track.id) {
                    cues.decode(sample)?.let { entry ->
                        val duration = if (entry.endUs == Long.MAX_VALUE) 0 else entry.endUs - entry.startUs
                        store.add(format, entry.startUs, duration, entry.cues)
                    }
                    reachedUs = maxOf(reachedUs, sample.timeUs)
                }
                // Nebenarbeit nur ab und zu.
                if (++reads % CHECK_EVERY != 0L) continue
                // Bild-Untertitel gleich lesen: so liegen nie viele Bilder im Speicher.
                for (entry in store.upcoming(format, ocrFromMs, 50) { it.image != null }) {
                    val image = entry.image ?: continue
                    entry.text = runCatching { recognize(image) }.getOrDefault("")
                    entry.image = null
                    ocrFromMs = entry.startMs
                }
                if (!keepGoing()) throw Stop()
                currentCoroutineContext().ensureActive()
            }
            for (entry in store.upcoming(format, ocrFromMs, Int.MAX_VALUE) { it.image != null }) {
                val image = entry.image ?: continue
                entry.text = runCatching { recognize(image) }.getOrDefault("")
                entry.image = null
            }
        } catch (_: Stop) {
            // Gelesenes behalten.
        } catch (e: IOException) {
            Log.w(TAG, "Lesen abgebrochen: ${e.message}")
            if (chosen == null) throw e
        } finally {
            runCatching { source.close() }
        }
        val lines = chosen?.let { format ->
            store.upcoming(format, 0, Int.MAX_VALUE) { !it.text.isNullOrBlank() }.map { Line(it.startMs, it.endMs, it.text.orEmpty()) }
        }.orEmpty()
        return Result(chosen, lines, complete && chosen != null, reachedUs, total)
    }

    companion object {
        private const val TAG = "DnAEmbeddedReader"
        private const val MAX_ERRORS = 5
        private const val CHECK_EVERY = 64L

        /**
         * Spur zum Übersetzen wie im Player: vollständige (keine erzwungenen), Englisch zuerst, Text vor Bild,
         * normale vor SDH. Gibt es schon eine vollständige Spur in [target], ist nichts zu tun (null).
         */
        fun bestSource(formats: List<Format>, target: String): Format? {
            val language = { f: Format -> de.ayutv.data.Languages.normalize(f.language) }
            val full = formats.filter { !it.isForcedSubtitle() }
            if (full.any { language(it) == de.ayutv.data.Languages.normalize(target) }) return null
            return full.sortedWith(
                compareBy<Format> { language(it) != "en" }
                    .thenBy { it.isBitmapSubtitle() }
                    .thenBy { it.isSdhSubtitle() }
            ).firstOrNull()
        }
    }
}

@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Eingebettete Untertitel, schon beim Laden mitgelesen: Der Player puffert 30–60 s voraus, so
 * liegen Zeilen lange vor ihrer Einblendung vor. Damit lassen sich auch Bild-Untertitel (PGS, DVB,
 * VobSub) per Texterkennung lesen und übersetzen, bevor sie gebraucht werden: synchron zum Bild,
 * ohne Verzögerung. Gilt für alle Untertitelspuren, nicht nur die gerade gewählte.
 */
class EmbeddedSubtitles(
    /** Bilder nicht nach 30 s verwerfen (nächtliches Lesen: die Texterkennung kommt hinterher). */
    private val keepImages: Boolean = false,
) {
    /** Eine Einblendung. [text] kommt aus Textspuren oder später aus der Texterkennung. */
    class Entry(val startMs: Long, @Volatile var endMs: Long, @Volatile var text: String?, @Volatile var image: GrayImage?)

    /** Graustufen-Bild einer Bild-Untertitel-Einblendung: hell = Schrift, dunkel = Rand/Hintergrund. */
    class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
        companion object {
            /** Helligkeit × Deckkraft: Schrift hell, Rand und Hintergrund dunkel. */
            fun of(bitmap: Bitmap): GrayImage {
                val pixels = ByteArray(bitmap.width * bitmap.height)
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in 0 until bitmap.width) pixels[y * bitmap.width + x] = gray(row[x])
                }
                return GrayImage(bitmap.width, bitmap.height, pixels)
            }

            internal fun gray(argb: Int): Byte {
                val a = argb ushr 24
                val luma = (299 * (argb shr 16 and 0xFF) + 587 * (argb shr 8 and 0xFF) + 114 * (argb and 0xFF)) / 1000
                return (luma * a / 255).toByte()
            }
        }
    }

    private class Track(val format: Format) {
        val entries = TreeMap<Long, Entry>()
    }

    private val tracks = ConcurrentHashMap<String, Track>()

    /**
     * Spur, deren Bilder gelesen werden sollen (Übersetzung läuft), sonst null. Bild-Untertitel aller
     * anderen Spuren werden gar nicht erst dekodiert: Das spart Rechenzeit und Speicher bei 4K.
     */
    @Volatile
    private var imageTrack: String? = null

    fun clear() = tracks.clear()

    fun readImagesOf(format: Format?) {
        imageTrack = format?.let(::key)
    }

    internal fun wantsImages(format: Format) = imageTrack == key(format)

    /** Bild-Untertitel (PGS, DVB, VobSub): ursprüngliches Format steht nach der Umwandlung in codecs. */
    internal fun isBitmap(format: Format) = format.codecs in BITMAP_TYPES || format.sampleMimeType in BITMAP_TYPES

    /** Gibt es schon mitgelesene Einblendungen dieser Spur? */
    fun hasEntries(format: Format): Boolean = find(format)?.let { synchronized(it) { it.entries.isNotEmpty() } } == true

    /**
     * Startzeiten der vollständigsten Spur (keine erzwungene), egal welche Sprache und ob Text oder Bild: Taktgeber,
     * an dem geladene Untertitel Zeile für Zeile ausgerichtet werden ([SubtitleAlign]).
     */
    fun referenceStarts(): List<Long> {
        val track = tracks.values.filter { !it.format.isForcedSubtitle() }
            .maxByOrNull { synchronized(it) { it.entries.size } } ?: return emptyList()
        synchronized(track) { return track.entries.keys.toList() }
    }

    /**
     * Bild-Untertitel, deren Bilder niemand braucht: nur die Zeit merken (Taktgeber), ohne das Bild zu dekodieren.
     * Ein Bild-Sample mit Inhalt ist groß, das Ausblenden ist ein winziges Sample ohne Bild ([TIMING_MIN_BYTES]).
     */
    internal fun addTiming(format: Format, timeUs: Long, sampleBytes: Int) {
        val track = tracks.getOrPut(key(format)) { Track(format) }
        val startMs = timeUs / 1000
        synchronized(track) {
            track.entries.lowerEntry(startMs)?.value?.let { if (it.endMs < 0 || it.endMs > startMs) it.endMs = startMs }
            if (sampleBytes < TIMING_MIN_BYTES || track.entries.containsKey(startMs)) return
            track.entries[startMs] = Entry(startMs, -1L, null, null)
            while (track.entries.size > MAX_ENTRIES) track.entries.pollFirstEntry()
        }
    }

    /** Einblendung, die bei [positionMs] zu sehen ist. */
    fun at(format: Format, positionMs: Long): Entry? {
        val track = find(format) ?: return null
        synchronized(track) {
            val entry = track.entries.floorEntry(positionMs)?.value ?: return null
            val end = entry.endMs.takeIf { it >= 0 }
                ?: track.entries.higherKey(entry.startMs)
                ?: (entry.startMs + OPEN_END_MS)
            return entry.takeIf { positionMs < end }
        }
    }

    /** Einblendungen ab [fromMs] in zeitlicher Reihenfolge, die [wanted] erfüllen. */
    fun upcoming(format: Format, fromMs: Long, limit: Int, wanted: (Entry) -> Boolean): List<Entry> {
        val track = find(format) ?: return emptyList()
        synchronized(track) {
            return track.entries.tailMap(fromMs, true).values.asSequence().filter(wanted).take(limit).toList()
        }
    }

    private fun find(format: Format): Track? {
        format.id?.let { id -> tracks[id]?.let { return it } }
        // Ohne Kennung: über Sprache und Bezeichnung (eindeutig) zuordnen.
        return tracks.values.singleOrNull { it.format.language == format.language && it.format.label == format.label }
    }

    private fun key(format: Format) = format.id ?: "${format.language}|${format.label}|${format.codecs}"

    internal fun add(format: Format, timeUs: Long, durationUs: Long, cues: List<Cue>) {
        val track = tracks.getOrPut(key(format)) { Track(format) }
        val startMs = timeUs / 1000
        val endMs = if (durationUs != C.TIME_UNSET && durationUs > 0) startMs + durationUs / 1000 else -1L
        synchronized(track) {
            // Bild-Untertitel enden mit der nächsten (ggf. leeren) Einblendung.
            track.entries.lowerEntry(startMs)?.value?.let { if (it.endMs < 0 || it.endMs > startMs) it.endMs = startMs }
            if (cues.isEmpty()) return
            val existing = track.entries[startMs]
            if (existing != null && (existing.text != null || existing.image != null)) {
                if (endMs >= 0) existing.endMs = endMs
                return
            }
            val text = cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
            val image = if (text == null && wantsImages(format)) toGray(cues) else null
            // Bild-Untertitel (PGS, DVB), deren Bild niemand braucht: trotzdem die Zeit merken – sie sind Taktgeber für die
            // Ausrichtung geladener Untertitel (OpenSubtitles). Mit ExoPlayer kam das über addTiming; mit der DNA Engine
            // fielen diese Einblendungen hier weg, und die zuverlässigste Ausrichtung (an der Spur des Films) fehlte.
            if (text == null && image == null) {
                if (cues.none { it.bitmap != null }) return
                track.entries[startMs] = Entry(startMs, endMs, null, null)
                while (track.entries.size > MAX_ENTRIES) track.entries.pollFirstEntry()
                return
            }
            track.entries[startMs] = Entry(startMs, endMs, text, image)
            while (track.entries.size > MAX_ENTRIES) track.entries.pollFirstEntry()
            // Bilder längst vergangener Einblendungen braucht niemand mehr: Speicher freigeben.
            if (image != null && !keepImages) track.entries.headMap(startMs - KEEP_IMAGES_BEHIND_MS).values.forEach { it.image = null }
        }
    }

    /** Alle Bilder einer Einblendung untereinander (von oben nach unten) zu einem Graustufenbild. */
    private fun toGray(cues: List<Cue>): GrayImage? {
        val bitmaps = cues.filter { it.bitmap != null }
            .sortedBy { if (it.line == Cue.DIMEN_UNSET) 0f else it.line }
            .mapNotNull { it.bitmap }
        if (bitmaps.isEmpty()) return null
        val scaled = bitmaps.map { bitmap ->
            // Große Bilder verkleinern: Für die Erkennung reichen ~60 px pro Zeile.
            val factor = (MAX_HEIGHT_PER_IMAGE.toFloat() / bitmap.height).coerceAtMost(1f)
            if (factor < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
            else bitmap
        }
        val width = scaled.maxOf { it.width }
        val gap = 12
        val height = scaled.sumOf { it.height } + gap * (scaled.size - 1)
        val pixels = ByteArray(width * height)
        var top = 0
        val row = IntArray(width)
        for (bitmap in scaled) {
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                val offset = (top + y) * width
                for (x in 0 until bitmap.width) pixels[offset + x] = GrayImage.gray(row[x])
            }
            top += bitmap.height + gap
        }
        return GrayImage(width, height, pixels)
    }

    companion object {
        /** Kennung einer Spur für gespeicherte Übersetzungen: gleich beim Schauen und beim nächtlichen Lesen. */
        fun trackKey(format: Format): String = format.id ?: format.language ?: "spur"

        private const val MAX_ENTRIES = 3_000
        private const val OPEN_END_MS = 6_000L
        /** Kleinere Bild-Samples sind „ausblenden“ (kein Bild). */
        private const val TIMING_MIN_BYTES = 256
        private const val MAX_HEIGHT_PER_IMAGE = 110
        private const val KEEP_IMAGES_BEHIND_MS = 30_000L
        private val BITMAP_TYPES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)
    }
}

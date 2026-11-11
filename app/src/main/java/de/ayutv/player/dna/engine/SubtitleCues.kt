package de.ayutv.player.dna.engine

import android.text.Html
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * Untertitel der DNA Engine: macht aus den Proben einer Spur Einblendungen ([Cue]) mit Anfang und Ende.
 * Text: SRT, ASS/SSA (Formatbefehle entfernt), WebVTT, tx3g. Bild: PGS (Blu-ray) als Bitmap an seiner Stelle im Bild.
 */
@OptIn(UnstableApi::class)
class SubtitleCues(private val track: Track) {
    class Entry(val startUs: Long, var endUs: Long, val cues: List<Cue>)

    private val pgs = if (track.codec == Codec.PGS) PgsDecoder() else null
    private val dvb = if (track.codec == Codec.DVBSUB) {
        val pages = track.extra ?: ByteArray(4)
        DvbSubtitleDecoder(((pages[0].toInt() and 0xFF) shl 8) or (pages[1].toInt() and 0xFF), ((pages[2].toInt() and 0xFF) shl 8) or (pages[3].toInt() and 0xFF))
    } else null

    /** Einblendung aus einer Probe; bei PGS ohne Ende (die nächste beendet sie), leere PGS-Probe = Ende. */
    fun decode(sample: Sample): Entry? {
        val start = sample.timeUs
        val end = if (sample.durationUs > 0) start + sample.durationUs else Long.MAX_VALUE
        return when (track.codec) {
            Codec.SRT -> text(start, end, html(String(sample.data, Charsets.UTF_8)))
            Codec.ASS -> text(start, end, ass(String(sample.data, Charsets.UTF_8)))
            Codec.WEBVTT -> text(start, end, html(String(sample.data, Charsets.UTF_8)))
            Codec.TX3G -> text(start, end, String(sample.data, Charsets.UTF_8))
            Codec.PGS -> pgs?.decodeImages(sample.data)?.let { images -> Entry(start, end, images.mapNotNull { it.toCue() }) }
            Codec.DVBSUB -> dvb?.decodeImages(sample.data)?.let { images -> Entry(start, end, images.mapNotNull { it.toCue() }) }
            else -> null
        }
    }

    private fun text(start: Long, end: Long, text: CharSequence): Entry =
        Entry(start, end, if (text.isBlank()) emptyList() else listOf(Cue.Builder().setText(text).build()))

    private fun html(raw: String): CharSequence {
        val cleaned = raw.replace(Regex("""\{\\[^}]*\}"""), "").trim()
        if (!cleaned.contains('<')) return cleaned
        return runCatching { Html.fromHtml(cleaned.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).trim() }.getOrDefault(cleaned)
    }

    companion object {
        /** ASS in MKV: „ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text“ → nur der Text. */
        fun ass(line: String): String {
            var commas = 0
            var i = 0
            // Mit Dialogue:-Kopf (SSA in anderen Containern) gibt es neun Felder davor.
            val fields = if (line.startsWith("Dialogue:")) 9 else 8
            while (i < line.length && commas < fields) {
                if (line[i] == ',') commas++
                i++
            }
            val text = if (commas == fields) line.substring(i) else line
            return text.replace(Regex("""\{[^}]*\}"""), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
        }
    }
}

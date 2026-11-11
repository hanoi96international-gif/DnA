package de.ayutv.data.net

import de.ayutv.data.parse.Hid

import java.io.Closeable

/**
 * Filme für den Fernseher umverpacken (native Bibliothek `libdnaremux`, app/src/main/cpp/remux): Eine Film-Datei (MKV,
 * MP4, AVI) wird Stück für Stück als HLS im MPEG-TS-Format ausgegeben, das Bild unverändert. Ton, den Fernseher oft
 * nicht können (DTS, TrueHD, FLAC, Opus …), wird dabei in AC3 (Dolby Digital) umgerechnet.
 *
 * Damit spielt der Mediaplayer von LG webOS auch Filme vom Handy richtig: mit Ton, mit Spulen (er holt das Stück an
 * der gewünschten Stelle) und mit Untertitelspur.
 */
object Remux {
    /** Ist die native Bibliothek in dieser App (in der CI gebaut; lokal ohne NDK fehlt sie)? */
    val available: Boolean by lazy { runCatching { System.loadLibrary("dnaremux"); true }.getOrDefault(false) }

    /** Lesen der Film-Datei (aus nativem Code aufgerufen – Namen nicht ändern, siehe proguard-rules.pro). */
    interface Source {
        /** Bis zu [len] Bytes nach [buf]; 0 = Ende, < 0 = Fehler. */
        fun read(buf: ByteArray, len: Int): Int

        /** Wie bei FFmpeg: whence 0/1/2 (Anfang/aktuell/Ende) oder [SEEK_SIZE] (Größe liefern). Neue Stelle, < 0 = Fehler. */
        fun seek(offset: Long, whence: Int): Long
    }

    /** Ziel eines Stücks (aus nativem Code aufgerufen). false = abbrechen. */
    interface Sink {
        fun write(buf: ByteArray, len: Int): Boolean

        /** Zeile eingebetteter Text-Untertitel aus dem gelesenen Bereich (Zeiten der Datei in Sekunden). */
        fun cue(stream: Int, start: Double, end: Double, text: ByteArray)
    }

    const val SEEK_SIZE = 0x10000

    /** Tonspur in der Datei. */
    data class Track(val index: Int, val codec: String, val channels: Int, val language: String?, val title: String?) {
        /** Für die Auswahl: „Deutsch · 5.1 · DTS“. */
        fun label(): String {
            val lang = language?.let { code ->
                runCatching { java.util.Locale.forLanguageTag(isoLanguage(code)).getDisplayLanguage(java.util.Locale.GERMAN) }.getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
            } ?: "Spur ${index + 1}"
            val layout = when {
                channels >= 8 -> "7.1"
                channels >= 6 -> "5.1"
                channels == 2 -> "Stereo"
                channels == 1 -> "Mono"
                else -> null
            }
            val name = codecName(codec)
            return listOfNotNull(lang, layout, name, title?.takeIf { it.isNotBlank() && !it.equals(lang, true) }).joinToString(" · ")
        }
    }

    /**
     * Sprachcode der Datei (MKV: ISO 639-2, oft in der bibliografischen Form „ger“, „fre“) als zweibuchstabiger Code,
     * wie ihn Android und HLS kennen („de“, „fr“). Unbekannte bleiben, wie sie sind.
     */
    fun isoLanguage(code: String): String {
        val c = code.trim().lowercase().substringBefore('-')
        BIBLIOGRAPHIC[c]?.let { return it }
        if (c.length == 3) {
            java.util.Locale.getISOLanguages().firstOrNull { runCatching { java.util.Locale(it).isO3Language == c }.getOrDefault(false) }?.let { return it }
        }
        return c
    }

    private val BIBLIOGRAPHIC = mapOf(
        "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa", "rum" to "ro",
        "slo" to "sk", "alb" to "sq", "arm" to "hy", Hid.s("Iwsu") to "ka", "mac" to "mk", "may" to "ms", "ice" to "is", "wel" to "cy",
        "baq" to "eu", "bur" to "my", "tib" to "bo",
    )

    /** Eingebettete Untertitelspur (nur Text-Formate kommen beim Fernseher an, siehe [text]). */
    data class SubTrack(val index: Int, val codec: String, val language: String?, val forced: Boolean, val title: String?) {
        /** Text-Untertitel (SRT, ASS, WebVTT, mov_text) – Bild-Untertitel (PGS, DVD) gehen als WebVTT nicht. */
        val text: Boolean get() = codec.lowercase() in setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")

        /** Für die Auswahl: „Deutsch · erzwungen“. */
        fun label(): String {
            val lang = language?.let { code ->
                runCatching { java.util.Locale.forLanguageTag(isoLanguage(code)).getDisplayLanguage(java.util.Locale.GERMAN) }.getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
            } ?: "Untertitel ${index + 1}"
            return listOfNotNull(lang, "erzwungen".takeIf { forced }, title?.takeIf { it.isNotBlank() && !it.equals(lang, true) }).joinToString(" · ")
        }
    }

    /**
     * Inhalt eines Untertitel-Pakets als einfacher Text für WebVTT: ASS-Dialogzeile ohne die Felder davor und ohne
     * Formatierungen ({\i1} …), \N als Zeilenumbruch; SRT mit <i>/<b>/<u> (kann WebVTT), andere Auszeichnungen weg.
     */
    fun cueText(codec: String, data: ByteArray): String {
        var text = when (codec.lowercase()) {
            "mov_text" -> if (data.size >= 2) {
                val len = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                String(data, 2, minOf(len, data.size - 2), Charsets.UTF_8)
            } else ""
            else -> String(data, Charsets.UTF_8)
        }
        if (codec.lowercase() in setOf("ass", "ssa")) {
            // ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            var commas = 0
            var cut = -1
            for (i in text.indices) if (text[i] == ',' && ++commas == 8) { cut = i; break }
            if (cut >= 0) text = text.substring(cut + 1)
            text = text.replace(Regex("""\{[^}]*\}"""), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        }
        text = text.replace("\r\n", "\n").replace(Regex("""</?(?!(?:i|b|u)>)[a-zA-Z][^>]*>"""), "")
        return text.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** Bildspur. */
    data class Video(val codec: String, val width: Int, val height: Int)

    internal fun codecName(codec: String) = when (codec.lowercase()) {
        "dts" -> "DTS"
        "truehd" -> "Dolby TrueHD"
        "eac3" -> "Dolby Digital Plus"
        "ac3" -> "Dolby Digital"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        else -> codec.uppercase()
    }

    /** Beschreibung aus der nativen Bibliothek lesen (eine Zeile je Spur, siehe dnaremux.h). */
    internal class Description(val video: Video?, val audio: List<Track>, val defaultAudio: Int, val subtitles: List<SubTrack> = emptyList())

    internal fun parseDescription(text: String): Description {
        var video: Video? = null
        val audio = ArrayList<Track>()
        var defaultAudio = -1
        val subtitles = ArrayList<SubTrack>()
        for (line in text.lines()) {
            val parts = line.trim().split(' ', limit = 6)
            when (parts.firstOrNull()) {
                "video" -> if (video == null && parts.size >= 4) {
                    val size = parts[3].split('x')
                    video = Video(parts[2], size.getOrNull(0)?.toIntOrNull() ?: 0, size.getOrNull(1)?.toIntOrNull() ?: 0)
                }
                "default" -> defaultAudio = parts.getOrNull(2)?.toIntOrNull() ?: -1
                "subtitle" -> if (parts.size >= 5) {
                    val index = parts[1].toIntOrNull() ?: continue
                    subtitles += SubTrack(
                        index, parts[2], parts[3].takeIf { it != "-" && it.isNotBlank() }, parts[4] == "1",
                        parts.getOrNull(5)?.trim()?.ifBlank { null },
                    )
                }
                "audio" -> if (parts.size >= 5) {
                    val index = parts[1].toIntOrNull() ?: continue
                    audio += Track(
                        index, parts[2], parts[3].toIntOrNull() ?: 0,
                        parts[4].takeIf { it != "-" && it.isNotBlank() }, parts.getOrNull(5)?.trim()?.ifBlank { null },
                    )
                }
            }
        }
        return Description(video, audio, defaultAudio, subtitles)
    }

    /** Geöffneter Film. Nicht für mehrere Threads zugleich (der Aufrufer sperrt). */
    class Film internal constructor(private var handle: Long) : Closeable {
        val duration: Double = nDuration(handle)
        val video: Video?
        val audio: List<Track>
        /** Tonspur, die ohne Wahl genommen wird (Index in der Datei, -1 = keine). */
        val defaultAudio: Int
        /** Eingebettete Untertitelspuren. */
        val subtitles: List<SubTrack>

        init {
            val d = parseDescription(nDescribe(handle))
            video = d.video
            audio = d.audio
            defaultAudio = d.defaultAudio
            subtitles = d.subtitles
        }

        /** Stückgrenzen (Sekunden, an Schlüsselbildern, je etwa [target] s): erste 0, letzte = Dauer. */
        fun boundaries(target: Double): DoubleArray = nBoundaries(handle, target)

        /** Muss Spur [audio] (Index der Datei, -1 = Standard) für Fernseher umgerechnet werden? */
        fun needsTranscode(audio: Int): Boolean = nNeedsTranscode(handle, audio)

        /**
         * Stück [start, end) als MPEG-TS an [sink]. null = ok, sonst Grund. transcode: 1 ja, 0 nein, -1 automatisch.
         * Mit [cues] gehen die Zeilen der eingebetteten Text-Untertitel aus dem gelesenen Bereich an [Sink.cue].
         */
        fun segment(start: Double, end: Double, audio: Int, transcode: Int, sink: Sink, cues: Boolean = false): String? {
            check(handle != 0L) { "geschlossen" }
            return nSegment(handle, start, end, audio, transcode, cues, sink)
        }

        override fun close() {
            if (handle != 0L) nClose(handle)
            handle = 0L
        }
    }

    /** Film öffnen (liest den Kopf, bei MKV auch das Verzeichnis am Ende): Film oder Grund. */
    fun open(source: Source): Result<Film> {
        if (!available) return Result.failure(IllegalStateException("Umverpacken nicht in dieser App"))
        val error = arrayOfNulls<String>(1)
        val handle = nOpen(source, error)
        return if (handle == 0L) Result.failure(java.io.IOException(error[0] ?: "Datei nicht lesbar")) else Result.success(Film(handle))
    }

    @JvmStatic private external fun nOpen(source: Source, error: Array<String?>): Long
    @JvmStatic private external fun nClose(handle: Long)
    @JvmStatic private external fun nDuration(handle: Long): Double
    @JvmStatic private external fun nDescribe(handle: Long): String
    @JvmStatic private external fun nBoundaries(handle: Long, target: Double): DoubleArray
    @JvmStatic private external fun nNeedsTranscode(handle: Long, audio: Int): Boolean
    @JvmStatic private external fun nSegment(handle: Long, start: Double, end: Double, audio: Int, transcode: Int, cues: Boolean, sink: Sink): String?
}

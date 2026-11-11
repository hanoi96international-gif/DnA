package de.ayutv.desktop.player

import de.ayutv.data.parse.Srt
import de.ayutv.player.SubtitleTiming

/** SRT-Zeiten lesen und mit einer Ausrichtung ([SubtitleTiming]: Versatz und Bildraten-Faktor) neu schreiben. */
object SubtitleFiles {
    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2}),(\d{1,3})""")

    fun parseMs(text: String): Long? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val (h, min, s, ms) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    fun formatMs(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    /** Beginn und Ende jeder Einblendung (Dateizeit, ms). */
    fun cues(srt: String): List<Pair<Long, Long>> =
        Srt.parse(srt).mapNotNull { c -> parseMs(c.start)?.let { s -> s to (parseMs(c.end) ?: s) } }

    /** Dieselbe Datei mit verschobenen/gestreckten Zeiten (ganz vor dem Film liegende Einblendungen fallen weg). */
    fun shifted(srt: String, timing: SubtitleTiming): String = Srt.write(
        Srt.parse(srt).mapNotNull { c ->
            val start = parseMs(c.start)?.let(timing::toVideo) ?: return@mapNotNull null
            val end = parseMs(c.end)?.let(timing::toVideo) ?: return@mapNotNull null
            if (end <= 0) null else c.copy(start = formatMs(start), end = formatMs(end))
        },
    )
}

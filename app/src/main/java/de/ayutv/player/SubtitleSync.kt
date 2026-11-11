package de.ayutv.player

import kotlin.math.abs

/** Zeitliche Lage geladener Untertitel im Film: Filmzeit = Untertitelzeit × [ratio] + [offsetMs]. */
data class SubtitleTiming(val ratio: Double = 1.0, val offsetMs: Long = 0L) {
    fun toVideo(subtitleMs: Long): Long = (subtitleMs * ratio).toLong() + offsetMs
    fun toSubtitle(videoMs: Long): Long = ((videoMs - offsetMs) / ratio).toLong()
}

/**
 * Richtet geladene Untertitel (andere Fassung des Films, anderer Vorspann, 25 statt 23,976 fps) an
 * eingebetteten Untertiteln aus. Deren Zeiten passen exakt zur Datei, auch wenn es Bilduntertitel
 * (PGS) in einer anderen Sprache sind: Gesprochen wird in jeder Sprache zur selben Zeit.
 *
 * Jede eingebettete Einblendung stimmt für alle Versätze ab, die sie mit einer geladenen Zeile
 * zur Deckung bringen. Der richtige Versatz sammelt die meisten Stimmen; Zufallstreffer verteilen
 * sich. Zusätzlich werden die üblichen Bildraten-Unterschiede (PAL-Beschleunigung) geprüft.
 */
object SubtitleSync {
    data class Fit(val timing: SubtitleTiming, val votes: Int, val references: Int)

    const val MIN_REFERENCES = 6
    private const val WINDOW_MS = 90_000L
    private const val BIN_MS = 100L
    private const val MATCH_MS = 250L
    private val RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /**
     * [references]: Startzeiten eingebetteter Einblendungen (Filmzeit), [starts]: Startzeiten der
     * geladenen Datei. null, solange das Ergebnis nicht eindeutig ist.
     */
    /**
     * Anteil der eingebetteten Einblendungen (in %), zu denen die geladene Datei mit [timing] eine Zeile hat
     * (auf ±[MATCH_MS] genau). Schon mit wenigen Einblendungen aussagekräftig, wenn sie fast alle oder fast keine
     * treffen.
     */
    fun coverage(references: List<Long>, starts: List<Long>, timing: SubtitleTiming): Int {
        if (references.isEmpty() || starts.isEmpty()) return 0
        val sorted = starts.map { timing.toVideo(it) }.sorted().toLongArray()
        val hits = references.count { r ->
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < r) lo = mid + 1 else hi = mid
            }
            (lo < sorted.size && abs(sorted[lo] - r) <= MATCH_MS) || (lo > 0 && abs(sorted[lo - 1] - r) <= MATCH_MS)
        }
        return hits * 100 / references.size
    }

    fun estimate(references: List<Long>, starts: List<Long>): Fit? {
        if (references.size < MIN_REFERENCES || starts.isEmpty()) return null
        val sorted = starts.sorted().toLongArray()
        // 24 ↔ 23,976 fps verschiebt erst nach vielen Minuten merklich: nur mit genug Film dazwischen prüfen.
        val span = references.max() - references.min()
        val ratios = RATIOS.filter { abs(it - 1.0) > 0.01 || it == 1.0 || span > 20 * 60_000L }
        val candidates = ratios.mapNotNull { ratio ->
            val bins = votes(references, sorted, ratio)
            val top = bins.keys.maxByOrNull { score(bins, it) } ?: return@mapNotNull null
            // Zweitbester Versatz derselben Bildrate, mindestens 1 s entfernt: Maß für Eindeutigkeit.
            val second = bins.keys.filter { abs(it - top) * BIN_MS > 1_000 }.maxOfOrNull { score(bins, it) } ?: 0
            Candidate(ratio, top, score(bins, top), second)
        }
        // Bildratenkorrektur nur, wenn sie klar mehr erklärt als "gleiche Bildrate".
        var winner = candidates.firstOrNull() ?: return null
        for (c in candidates.drop(1)) if (c.score > winner.score + 4) winner = c
        // Andere Bildraten mit ganz anderem Versatz, aber ähnlich vielen Stimmen: nicht eindeutig.
        val runnerUp = candidates.filter { it !== winner && abs(it.bin - winner.bin) * BIN_MS > 1_000 }.maxOfOrNull { it.score } ?: 0
        val enough = winner.score >= MIN_REFERENCES && winner.score * 3 >= references.size
        val clear = winner.score >= 2 * maxOf(winner.second, runnerUp)
        if (!enough || !clear) return null
        // Feinabstimmung: Median der Abweichungen um den gewählten Versatz.
        val center = winner.bin * BIN_MS + BIN_MS / 2
        val diffs = references.mapNotNull { r -> nearest(r, sorted, winner.ratio, center) }.sorted()
        if (diffs.size < MIN_REFERENCES) return null
        return Fit(SubtitleTiming(winner.ratio, diffs[diffs.size / 2]), diffs.size, references.size)
    }

    private class Candidate(val ratio: Double, val bin: Long, val score: Int, val second: Int)

    /** Stimmen je 100-ms-Fach; jede Einblendung zählt pro Fach höchstens einmal. */
    private fun votes(references: List<Long>, sorted: LongArray, ratio: Double): Map<Long, Int> {
        val bins = HashMap<Long, Int>()
        val seen = HashSet<Long>()
        for (r in references) {
            seen.clear()
            var i = lowerBound(sorted, ((r - WINDOW_MS) / ratio).toLong())
            val last = ((r + WINDOW_MS) / ratio).toLong()
            while (i < sorted.size && sorted[i] <= last) {
                val bin = Math.floorDiv(r - (sorted[i] * ratio).toLong(), BIN_MS)
                if (seen.add(bin)) bins[bin] = (bins[bin] ?: 0) + 1
                i++
            }
        }
        return bins
    }

    /** Drei Fächer (300 ms) zusammen: Einblendungen beginnen nie auf die Millisekunde gleich. */
    private fun score(bins: Map<Long, Int>, bin: Long) = (bins[bin - 1] ?: 0) + (bins[bin] ?: 0) + (bins[bin + 1] ?: 0)

    private fun nearest(reference: Long, sorted: LongArray, ratio: Double, center: Long): Long? {
        val i = lowerBound(sorted, ((reference - center - MATCH_MS) / ratio).toLong())
        var bestDiff: Long? = null
        var j = i
        while (j < sorted.size) {
            val diff = reference - (sorted[j] * ratio).toLong()
            if (diff < center - MATCH_MS) break
            if (abs(diff - center) <= MATCH_MS && (bestDiff == null || abs(diff - center) < abs(bestDiff - center))) bestDiff = diff
            j++
        }
        return bestDiff
    }

    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

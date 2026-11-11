package de.ayutv.player

import kotlin.math.abs

/**
 * Zeitlage einer geladenen Untertitel-Datei, Zeile für Zeile: Jede Zeile übernimmt den Versatz der nächstgelegenen
 * sicher zugeordneten Zeile (Anker). So passt eine Datei aus einer anderen Fassung auch dann, wenn der Versatz mitten
 * im Film springt (fehlende Szene, anderer Vorspann, Werbepause), nicht nur bei einem festen Versatz.
 *
 * [anchors]: Paare (Zeit in der Datei, Filmzeit), nach beiden aufsteigend. [ratio]: Bildraten-Faktor zwischen Ankern.
 */
class ElasticTiming(anchors: List<Pair<Long, Long>>, val ratio: Double) {
    private val file = LongArray(anchors.size) { anchors[it].first }
    private val video = LongArray(anchors.size) { anchors[it].second }
    private val offsets = LongArray(anchors.size) { video[it] - (file[it] * ratio).toLong() }

    val size: Int get() = file.size

    /** Zeit in der Datei → Filmzeit (Versatz des nächstgelegenen Ankers). */
    fun toVideo(fileMs: Long): Long = (fileMs * ratio).toLong() + offsets[nearest(file, fileMs)]

    /** Filmzeit → Zeit in der Datei (für die Anzeige der Zeile, die gerade zu sehen sein soll). */
    fun toSubtitle(videoMs: Long): Long = ((videoMs - offsets[nearest(video, videoMs)]) / ratio).toLong()

    /**
     * Abstand der Filmzeit [videoMs] zum nächsten Anker. Weit weg (z. B. direkt nach einem großen Sprung im Film, bevor
     * dort Einblendungen gelesen wurden) ist der Versatz dort nur geraten: Springt er dazwischen, wären die Zeilen falsch.
     */
    fun distanceTo(videoMs: Long): Long = if (video.isEmpty()) Long.MAX_VALUE else abs(video[nearest(video, videoMs)] - videoMs)

    private fun nearest(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        // lo: erster Anker ≥ value; der davor kann näher liegen.
        return if (lo > 0 && abs(sorted[lo - 1] - value) <= abs(sorted[lo] - value)) lo - 1 else lo
    }
}

/**
 * Richtet eine geladene Datei Zeile für Zeile an den eingebetteten Untertiteln des Films aus. Verglichen werden nur
 * Zeiten, nicht Text: Die eingebettete Spur darf jede Sprache haben, auch Bild-Untertitel (PGS) ohne Texterkennung.
 *
 * Vorgehen: Für jede eingebettete Einblendung wird der örtliche Versatz aus den Nachbarn (±[WINDOW_MS]) bestimmt
 * (Abstimmung in 100-ms-Fächern, wie [SubtitleSync], aber je Abschnitt). Zeilen, die damit auf ±[MATCH_MS] passen,
 * werden Anker. Anker, die die Reihenfolge verletzen, fliegen raus (längste aufsteigende Folge).
 */
object SubtitleAlign {
    data class Result(val timing: ElasticTiming, val matched: Int, val references: Int, val fileLines: Int) {
        /** Wie gut die Datei passt: Anteil der zugeordneten Zeilen (bezogen auf die kleinere der beiden Mengen). */
        val percent: Int get() = (matched * 100 / maxOf(1, minOf(references, fileLines))).coerceAtMost(100)
    }

    /** Mindestens so viele eingebettete Einblendungen, bevor ausgerichtet wird. */
    const val MIN_REFERENCES = 12
    private const val WINDOW_MS = 60_000L
    private const val MAX_SHIFT_MS = 120_000L
    private const val BIN_MS = 100L
    private const val MATCH_MS = 300L
    private const val MIN_VOTES = 4

    /**
     * [references]: Startzeiten der eingebetteten Einblendungen (Filmzeit), [starts]: Startzeiten der geladenen
     * Datei. [ratio]: Bildraten-Faktor (aus [SubtitleSync] oder 1), [center]: ungefährer Gesamtversatz, um den herum
     * örtliche Versätze gesucht werden (±[MAX_SHIFT_MS]). null, wenn zu wenig sicher zugeordnet wird.
     */
    fun align(references: List<Long>, starts: List<Long>, ratio: Double = 1.0, center: Long = 0L): Result? {
        if (references.size < MIN_REFERENCES || starts.size < MIN_REFERENCES) return null
        val refs = references.sorted().toLongArray()
        val file = starts.sorted().toLongArray()
        val scaled = LongArray(file.size) { (file[it] * ratio).toLong() }
        // Je Einblendung: Versätze zu allen Datei-Zeilen in Reichweite (einmal berechnet, von den Nachbarn genutzt).
        val candidates = Array(refs.size) { i ->
            val r = refs[i]
            var j = lowerBound(scaled, r - center - MAX_SHIFT_MS)
            val out = ArrayList<Long>()
            while (j < scaled.size && scaled[j] <= r - center + MAX_SHIFT_MS) {
                out += r - scaled[j]
                j++
            }
            out
        }
        val anchors = ArrayList<Pair<Long, Long>>()
        var left = 0
        var right = 0
        val bins = HashMap<Long, Int>()
        val seen = HashSet<Long>()
        for (i in refs.indices) {
            val r = refs[i]
            while (refs[left] < r - WINDOW_MS) left++
            while (right < refs.size && refs[right] <= r + WINDOW_MS) right++
            if (right - left < MIN_VOTES) continue
            bins.clear()
            for (k in left until right) {
                seen.clear()
                for (d in candidates[k]) {
                    val bin = Math.floorDiv(d, BIN_MS)
                    if (seen.add(bin)) bins[bin] = (bins[bin] ?: 0) + 1
                }
            }
            // Drei Fächer zusammen (Einblendungen beginnen nie auf die Millisekunde gleich).
            fun score(bin: Long) = (bins[bin - 1] ?: 0) + (bins[bin] ?: 0) + (bins[bin + 1] ?: 0)
            val top = bins.keys.maxByOrNull(::score) ?: continue
            val best = score(top)
            val second = bins.keys.filter { abs(it - top) * BIN_MS > 1_000 }.maxOfOrNull(::score) ?: 0
            // Örtlich eindeutig: genug Nachbarn stimmen zu, und klar mehr als für jeden anderen Versatz.
            if (best < maxOf(MIN_VOTES, (right - left) * 2 / 5) || best < second * 3 / 2) continue
            val offset = top * BIN_MS + BIN_MS / 2
            // Passende Datei-Zeile für genau diese Einblendung.
            val j = nearestIndex(scaled, r - offset)
            if (j >= 0 && abs(scaled[j] + offset - r) <= MATCH_MS) anchors += file[j] to r
        }
        val ordered = increasing(anchors)
        if (ordered.size < MIN_REFERENCES) return null
        return Result(ElasticTiming(ordered, ratio), ordered.size, refs.size, linesWithin(file, ordered))
    }

    /** Datei-Zeilen im Bereich, den die eingebetteten Einblendungen abdecken (für den Anteil in %). */
    private fun linesWithin(file: LongArray, anchors: List<Pair<Long, Long>>): Int {
        val from = anchors.first().first
        val to = anchors.last().first
        return file.count { it in from..to }
    }

    /** Längste Folge, in der Datei- und Filmzeit gemeinsam steigen; jede Datei-Zeile höchstens einmal. */
    private fun increasing(anchors: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val byVideo = anchors.sortedBy { it.second }.distinctBy { it.first }
        if (byVideo.isEmpty()) return byVideo
        val tails = IntArray(byVideo.size)
        val previous = IntArray(byVideo.size) { -1 }
        var length = 0
        for (i in byVideo.indices) {
            var lo = 0
            var hi = length
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (byVideo[tails[mid]].first < byVideo[i].first) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[i] = tails[lo - 1]
            tails[lo] = i
            if (lo == length) length++
        }
        val out = ArrayList<Pair<Long, Long>>(length)
        var k = tails[length - 1]
        while (k >= 0) {
            out += byVideo[k]
            k = previous[k]
        }
        return out.asReversed()
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

    private fun nearestIndex(sorted: LongArray, value: Long): Int {
        if (sorted.isEmpty()) return -1
        val i = lowerBound(sorted, value)
        return when {
            i == 0 -> 0
            i == sorted.size -> sorted.size - 1
            abs(sorted[i - 1] - value) <= abs(sorted[i] - value) -> i - 1
            else -> i
        }
    }
}

package de.ayutv.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sprechpausen aus dem Ton: je 100 ms, wie laut es im Sprachband (300–3000 Hz) ist und wie stark die
 * Lautstärke von Silbe zu Silbe schwankt (Sprache pulsiert, Musik klingt gleichmäßiger). Wird vom Ton
 * gefüttert, während der Film läuft ([SpeechTapProcessor]); kostet kaum Rechenzeit.
 */
class SpeechActivity(maxMinutes: Int = 300) {
    private val bins = maxMinutes * 60 * 10
    private val level = FloatArray(bins) { Float.NaN }
    private val flux = FloatArray(bins)
    /**
     * Anteil des Sprachbands an der ganzen Lautstärke (log). Sprache liegt fast ganz zwischen 300 und 3000 Hz; Musik
     * (Bass, Schlagzeug) und Geräusche (Explosionen, Wind, Motoren) haben viel Energie darunter oder darüber. Das trennt
     * Sprache von lauten Szenen besser als die Lautstärke allein (wie bei Sprach-Erkennern, etwa WebRTC VAD).
     */
    private val share = FloatArray(bins)

    // Filterzustand (zwei Biquads: Hochpass 300 Hz, Tiefpass 3000 Hz)
    private var rate = 0
    private val hp = Biquad()
    private val lp = Biquad()
    private var frameEnergy = 0.0
    private var frameFull = 0.0
    private var binShare = 0.0
    private var frameSamples = 0
    private var frameSize = 0
    private var binIndex = -1
    private var binSum = 0.0
    private var binFlux = 0.0
    private var binFrames = 0
    private var lastFrameLog = Double.NaN
    private var sampleIndex = 0L
    private var startMs = 0L
    @Volatile private var heardBins = 0

    /** Neuer Abschnitt (Start oder Sprung im Film): ab [positionMs] beginnt der Ton. */
    @Synchronized
    fun restart(positionMs: Long, sampleRate: Int) {
        flushBin()
        if (sampleRate <= 0) { rate = 0; return }
        if (sampleRate != rate) {
            rate = sampleRate
            hp.highPass(300.0, sampleRate)
            lp.lowPass(3000.0, sampleRate)
            frameSize = sampleRate / 100
        }
        hp.reset(); lp.reset()
        startMs = positionMs
        sampleIndex = 0
        frameEnergy = 0.0; frameFull = 0.0; frameSamples = 0
        binIndex = -1; binSum = 0.0; binFlux = 0.0; binShare = 0.0; binFrames = 0
        lastFrameLog = Double.NaN
    }

    /** Anderer Film: alles Gehörte vergessen. */
    @Synchronized
    fun clear() {
        level.fill(Float.NaN)
        flux.fill(0f)
        share.fill(0f)
        heardBins = 0
        rate = 0
    }

    /** Monosamples (−1…1) in Filmreihenfolge. */
    @Synchronized
    fun add(samples: FloatArray, count: Int) {
        if (rate == 0) return
        for (k in 0 until count) add(samples[k])
    }

    private fun add(sample: Float) {
        val y = lp.process(hp.process(sample.toDouble()))
        frameEnergy += y * y
        frameFull += sample.toDouble() * sample
        if (++frameSamples < frameSize) return
        val log = ln(frameEnergy / frameSamples + 1e-10)
        val bandShare = ln((frameEnergy + 1e-10) / (frameFull + 1e-10))
        val ms = startMs + sampleIndex * 1000 / rate
        sampleIndex += frameSamples
        frameEnergy = 0.0; frameFull = 0.0; frameSamples = 0
        val bin = (ms / 100).toInt()
        if (bin != binIndex) {
            flushBin()
            binIndex = bin
        }
        binSum += log
        binShare += bandShare
        if (!lastFrameLog.isNaN()) binFlux += abs(log - lastFrameLog)
        lastFrameLog = log
        binFrames++
    }

    private fun flushBin() {
        if (binIndex in 0 until bins && binFrames >= 5) {
            if (level[binIndex].isNaN()) heardBins++
            level[binIndex] = (binSum / binFrames).toFloat()
            flux[binIndex] = (binFlux / binFrames).toFloat()
            share[binIndex] = (binShare / binFrames).toFloat()
        }
        binSum = 0.0; binFlux = 0.0; binShare = 0.0; binFrames = 0
    }

    /** Lautstärke je 100 ms zwischen [fromMs] und [toMs], wie gehört (NaN = nicht gehört). Für die Intro-Erkennung. */
    fun levels(fromMs: Long, toMs: Long): FloatArray {
        val from = (fromMs / 100).coerceIn(0, bins.toLong()).toInt()
        val to = (toMs / 100).coerceIn(from.toLong(), bins.toLong()).toInt()
        synchronized(this) { return level.copyOfRange(from, to) }
    }

    /** Wie viele Sekunden Ton insgesamt ausgewertet sind. */
    fun seconds(): Int = heardBins / 10

    /**
     * Sprachsignal je 100 ms zwischen [fromMs] und [toMs] (sonst und wo nicht gehört: NaN): Lautstärke
     * und Silbenschwankung, jeweils über dem örtlichen Grundpegel (±15 s), damit laute Musikszenen
     * nicht als Dauersprechen gelten.
     */
    fun signal(fromMs: Long = 0, toMs: Long = Long.MAX_VALUE): FloatArray {
        val from = (fromMs / 100).coerceIn(0, bins.toLong()).toInt()
        val to = (toMs / 100).coerceIn(0, bins.toLong()).toInt()
        // Kurz unter Sperre kopieren: Der Ton läuft weiter, während gerechnet wird.
        val l: FloatArray
        val f: FloatArray
        val sh: FloatArray
        synchronized(this) {
            l = level.copyOfRange(from, to)
            f = flux.copyOfRange(from, to)
            sh = share.copyOfRange(from, to)
        }
        val out = FloatArray(bins) { Float.NaN }
        val known = l.indices.filter { !l[it].isNaN() }
        if (known.isEmpty()) return out
        val lb = localBaseline(l, known)
        val fb = localBaseline(f, known)
        val sb = localBaseline(sh, known)
        for (i in known) out[from + i] = (l[i] - lb[i]) + 2f * (f[i] - fb[i]) + SHARE_WEIGHT * (sh[i] - sb[i])
        return out
    }

    private fun localBaseline(values: FloatArray, known: List<Int>): FloatArray {
        val base = FloatArray(values.size)
        val window = 150
        // Median über ein gleitendes Fenster, in Schritten von 1 s (genügt für einen Grundpegel).
        var i = 0
        while (i < known.size) {
            val center = known[i]
            val from = known.binarySearch(center - window).let { if (it < 0) -it - 1 else it }
            val to = known.binarySearch(center + window).let { if (it < 0) -it - 1 else it + 1 }
            val sample = FloatArray(to - from) { values[known[from + it]] }
            sample.sort()
            val median = sample[sample.size / 2]
            var j = i
            while (j < known.size && known[j] < center + 10) { base[known[j]] = median; j++ }
            i = j
        }
        return base
    }

    private companion object {
        /** Gewicht des Sprachband-Anteils im Sprachsignal. */
        const val SHARE_WEIGHT = 1f
    }

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun lowPass(f: Double, rate: Int) = set(f, rate, low = true)
        fun highPass(f: Double, rate: Int) = set(f, rate, low = false)
        private fun set(f: Double, rate: Int, low: Boolean) {
            val w = 2 * Math.PI * f / rate
            val alpha = kotlin.math.sin(w) / (2 * 0.7071)
            val cos = kotlin.math.cos(w)
            val a0 = 1 + alpha
            if (low) { b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2 } else { b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2 }
            a1 = -2 * cos; a2 = 1 - alpha
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
        }
    }
}

/**
 * Richtet geladene Untertitel am gesprochenen Ton aus, wenn der Film keine eigenen Untertitel hat:
 * Wo Untertitel stehen, wird gesprochen. Gesucht wird der Versatz (bis ±10 min) und die Bildrate,
 * bei der Untertitel und Sprachsignal am besten übereinanderliegen; nur ein klar herausragender
 * Treffer zählt, sonst bleibt alles, wie es ist.
 */
object SpeechSync {
    private const val BIN_MS = 100L
    private const val COARSE = 5           // grob: 500-ms-Schritte
    private const val MAX_SHIFT_MS = 600_000L
    // Ab 45 s gehörtem bzw. vorausgelesenem Ton wird es versucht (früher 60 s). Gemessen (SpeechSyncTest): Mit 45 s gibt
    // es nur gelegentlich schon ein Ergebnis, ab ~90 s meist – aber nie ein falsches; falsche Treffer verhindern MIN_Z
    // und CLEAR. Danach wird laufend mit mehr Ton verfeinert.
    const val MIN_SECONDS = 45
    private val RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /** [signal] aus [SpeechActivity.signal], [cues] Start/Ende der geladenen Untertitel (Dateizeit, ms). */
    fun estimate(signal: FloatArray, cues: List<Pair<Long, Long>>): SubtitleSync.Fit? {
        val e = evaluate(signal, cues) ?: return null
        // Eindeutig: weit über dem Rauschen und deutlich vor der zweitbesten Lösung.
        if (e.z < MIN_Z || e.z < e.runnerUpZ * CLEAR) return null
        return SubtitleSync.Fit(e.timing, (e.z * 10).toInt(), e.heardSeconds)
    }

    /**
     * Beste Lage einer Datei am Ton, auch wenn sie nicht eindeutig ist: [Evaluation.z] = wie weit der Treffer über dem
     * Rauschen liegt, [Evaluation.runnerUpZ] = die zweitbeste, andere Lösung. Zum Vergleich mehrerer Dateien: Die richtige
     * liegt nach 1,5 Minuten Gespräch fast immer klar vor Dateien anderer Filme oder Fassungen (siehe SpeechSyncTest).
     */
    class Evaluation(val timing: SubtitleTiming, val z: Double, val runnerUpZ: Double, val heardSeconds: Int)

    fun evaluate(signal: FloatArray, cues: List<Pair<Long, Long>>): Evaluation? {
        val heard = signal.indices.filter { !signal[it].isNaN() }
        if (heard.size < MIN_SECONDS * 10 || cues.size < 20) return null
        // Sprachsignal standardisieren (nur gehörte Stellen).
        val mean = heard.sumOf { signal[it].toDouble() } / heard.size
        val sd = sqrt(heard.sumOf { (signal[it] - mean) * (signal[it] - mean) } / heard.size).takeIf { it > 1e-6 } ?: return null
        val x = FloatArray(signal.size) { if (signal[it].isNaN()) 0f else ((signal[it] - mean) / sd).toFloat() }
        // Nur noch „gesprochen“ (lauteste 40 %) oder „still“, mittelwertfrei: Laute Geräusche und Musikeinsätze
        // überstimmen so nicht mehr ganze Sätze. Gemessen findet das den Versatz mit gut halb so viel Ton.
        val threshold = heard.map { x[it] }.sorted()[heard.size * 6 / 10]
        for (i in heard) x[i] = if (x[i] > threshold) 1f else -2f / 3f

        val sortedCues = cues.sortedBy { it.first }
        val first = heard.first().toLong() * BIN_MS
        val last = heard.last().toLong() * BIN_MS
        val found = RATIOS.mapNotNull { ratio -> search(x, heard, sortedCues, ratio)?.let { Found(ratio, it.first, it.second, it.third) } }
        val winner = found.maxByOrNull { it.z } ?: return null
        // Andere Lösung = andere Zuordnung irgendwo im gehörten Stück (Bildraten wie 24 und 23,976 fps
        // liegen über kurze Strecken praktisch aufeinander und sind keine Konkurrenz).
        fun differs(o: Found) = listOf(first, last).any { t ->
            abs(o.timing.toSubtitle(t) - winner.timing.toSubtitle(t)) * winner.ratio > SAME_MS
        }
        val runnerUp = maxOf(winner.secondZ, found.filter { it !== winner && differs(it) }.maxOfOrNull { it.z } ?: 0.0)
        // Liegen mehrere Bildraten im gehörten Stück praktisch gleich gut, die übliche nehmen (zuerst
        // "gleiche Bildrate"): 24 statt 23,976 fps liefe sonst nach einer Stunde 4 s daneben.
        val chosen = found.filter { !differs(it) && it.z >= winner.z - TIE_Z }.minBy { RATIOS.indexOf(it.ratio) }
        return Evaluation(chosen.timing, winner.z, runnerUp, heard.size / 10)
    }

    /**
     * Wie gut eine Datei in der Lage [timing] zum Ton passt, in Prozent (für die Anzeige „passt X %“, auch ohne
     * eingebettete Untertitel): Anteil der Zeilen im gehörten Stück, die höchstens 0,7 s neben einem Sprach-Einsatz
     * beginnen – gezählt von den Satzanfängen im Ton aus. Die passende Datei liegt hoch, eine fremde nahe am Zufall. null: zu wenig Ton oder Zeilen.
     */
    fun matchPercent(signal: FloatArray, cues: List<Pair<Long, Long>>, timing: SubtitleTiming): Int? {
        val onsets = onsets(signal)
        if (onsets.size < 10) return null
        val first = onsets.first()
        val last = onsets.last()
        val starts = cues.map { timing.toVideo(it.first) }.filter { it in first - MATCH_MS..last + MATCH_MS }.sorted().toLongArray()
        if (starts.size < 10) return null
        // Umgekehrt gezählt: Hat jeder deutliche Satzanfang im Ton eine Zeile? (Zeilen nach kurzen Pausen hat der Ton
        // nicht als Einsatz – von dort gezählt läge auch die richtige Datei nur bei gut der Hälfte.)
        val hits = onsets.count { onset ->
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < onset) lo = mid + 1 else hi = mid
            }
            val near = listOfNotNull(starts.getOrNull(lo), starts.getOrNull(lo - 1)).minOf { abs(it - onset) }
            near <= MATCH_MS
        }
        return hits * 100 / onsets.size
    }

    /**
     * Sprach-Einsätze (Filmzeit, ms): Stellen, an denen nach mindestens 0,5 s Stille wieder gesprochen wird – wie
     * Satzanfänge, also Untertitel-Anfänge. Als Anker für den Zeilen-Abgleich ([SubtitleAlign]) findet so auch eine
     * Fassung mit fehlenden Szenen oder Werbepausen ihre Lage abschnittsweise (Idee wie alass).
     */
    fun onsets(signal: FloatArray): List<Long> {
        val heard = signal.indices.filter { !signal[it].isNaN() }
        if (heard.size < 100) return emptyList()
        val threshold = heard.map { signal[it] }.sorted()[heard.size * 6 / 10]
        val out = ArrayList<Long>()
        var silent = 0
        var i = 0
        while (i < signal.size) {
            val v = signal[i]
            if (v.isNaN()) {
                silent = 0
                i++
                continue
            }
            if (v > threshold) {
                // Mindestens 0,3 s Sprache, damit einzelne Geräusche nicht zählen.
                var len = 0
                while (i + len < signal.size && !signal[i + len].isNaN() && signal[i + len] > threshold) len++
                if (silent >= 5 && len >= 3) out += i * BIN_MS
                silent = 0
                i += maxOf(1, len)
            } else {
                silent++
                i++
            }
        }
        return out
    }

    /**
     * Unter mehreren Dateien die passende wählen ([evaluations] je Datei, null = zu wenig Ton/Einblendungen). Index der
     * Gewinnerin oder null: Sie muss über [COMPARE_MIN_Z] liegen (höher als jede fremde Datei in den Messungen), in sich
     * eindeutig sein und alle anderen um [COMPARE_MARGIN] übertreffen – sonst bleibt alles, wie es ist.
     */
    fun pickBest(evaluations: List<Evaluation?>): Int? {
        val best = evaluations.indices.filter { evaluations[it] != null }.maxByOrNull { evaluations[it]!!.z } ?: return null
        val winner = evaluations[best]!!
        val others = evaluations.filterIndexed { i, e -> i != best && e != null }.maxOfOrNull { it!!.z } ?: 0.0
        if (winner.z < COMPARE_MIN_Z || winner.z < winner.runnerUpZ * COMPARE_CLEAR || winner.z < others * COMPARE_MARGIN) return null
        return best
    }

    private class Found(val ratio: Double, offset: Long, val z: Double, val secondZ: Double) {
        val timing = SubtitleTiming(ratio, offset)
    }

    /** Bester Versatz für eine Bildrate: grob in 500-ms-Schritten, dann fein in 100 ms. */
    private fun search(x: FloatArray, heard: List<Int>, cues: List<Pair<Long, Long>>, ratio: Double): Triple<Long, Double, Double>? {
        val from = heard.first()
        val to = heard.last()
        // Untertitel als 0/1-Folge auf dem 100-ms-Raster der Datei (mit Bildrate umgerechnet in Filmzeit).
        val subEnd = ((cues.maxOf { it.second } * ratio) / BIN_MS).toInt() + 1
        val sub = FloatArray(subEnd)
        for ((a, b) in cues) {
            val s = max(0, ((a * ratio) / BIN_MS).toInt())
            val e = min(subEnd, ((b * ratio) / BIN_MS).toInt())
            for (i in s until e) sub[i] = 1f
        }
        // Für die Feinsuche: Untertitel stehen nach dem Satz meist noch ein Stück; ihr Ende verschiebt sonst den Treffer.
        val fineSub = FloatArray(subEnd)
        for ((a, b) in cues) {
            val s = max(0, ((a * ratio) / BIN_MS).toInt())
            val e = min(subEnd, max(s + 3, (((b - LINGER_MS) * ratio) / BIN_MS).toInt()))
            for (i in s until e) fineSub[i] = 1f
        }
        val maxShift = (MAX_SHIFT_MS / BIN_MS).toInt()
        // Grob: Summe über 500-ms-Blöcke
        val coarseX = block(x, from, to, COARSE)
        val coarseSub = block(sub, 0, sub.size - 1, COARSE)
        val shifts = (-maxShift / COARSE)..(maxShift / COARSE)
        val base = from / COARSE
        // Alle Verschiebungen auf einmal per FFT (Kreuzkorrelation, wie ffsubsync): gleiche Werte wie Verschiebung für
        // Verschiebung, aber in Millisekunden statt Sekunden – bei mehreren Bildraten und Dateien zählt das.
        val correlation = crossCorrelation(coarseX, coarseSub)
        val scores = DoubleArray(shifts.count()) { k -> correlation(base - (shifts.first + k)) }
        val m = scores.average()
        val sd = sqrt(scores.sumOf { (it - m) * (it - m) } / scores.size).takeIf { it > 1e-9 } ?: return null
        val top = scores.indices.maxBy { scores[it] }
        // Zweitbester Gipfel mindestens 3 s entfernt
        val runner = scores.indices.filter { abs(it - top) > 6 }.maxOfOrNull { scores[it] } ?: m
        // Fein: ±1 s um den groben Treffer in 100-ms-Schritten
        val coarseShift = shifts.first + top
        var bestShift = coarseShift * COARSE
        var bestScore = Double.NEGATIVE_INFINITY
        for (shift in coarseShift * COARSE - 10..coarseShift * COARSE + 10) {
            var s = 0.0
            for (i in heard) {
                val j = i - shift
                if (j < 0 || j >= sub.size) continue
                s += x[i] * fineSub[j]
            }
            if (s > bestScore) { bestScore = s; bestShift = shift }
        }
        return Triple(bestShift * BIN_MS, (scores[top] - m) / sd, (runner - m) / sd)
    }

    /**
     * Kreuzkorrelation c(d) = Σ a[i]·b[i+d] (Werte außerhalb zählen als 0) für alle d per FFT; Abfrage über die
     * zurückgegebene Funktion.
     */
    private fun crossCorrelation(a: FloatArray, b: FloatArray): (Int) -> Double {
        var n = 1
        while (n < a.size + b.size) n = n shl 1
        val ar = DoubleArray(n); val ai = DoubleArray(n)
        val br = DoubleArray(n); val bi = DoubleArray(n)
        for (i in a.indices) ar[i] = a[i].toDouble()
        for (i in b.indices) br[i] = b[i].toDouble()
        fft(ar, ai, inverse = false)
        fft(br, bi, inverse = false)
        // conj(A)·B
        for (k in 0 until n) {
            val re = ar[k] * br[k] + ai[k] * bi[k]
            val im = ar[k] * bi[k] - ai[k] * br[k]
            ar[k] = re; ai[k] = im
        }
        fft(ar, ai, inverse = true)
        val minD = -(a.size - 1)
        val maxD = b.size - 1
        return { d -> if (d < minD || d > maxD) 0.0 else ar[if (d >= 0) d else n + d] }
    }

    /** Radix-2-FFT an Ort und Stelle (Länge Zweierpotenz); invers mit Teilung durch n. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * Math.PI / len * if (inverse) 1 else -1
            val wr = kotlin.math.cos(angle); val wi = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                    val vr = xr * cr - xi * ci; val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (k in 0 until n) { re[k] /= n; im[k] /= n }
    }

    private fun block(values: FloatArray, from: Int, to: Int, size: Int): FloatArray {
        val start = from / size
        val out = FloatArray(to / size - start + 1)
        for (i in from..to) out[i / size - start] += values[i]
        return out
    }

    /** Zeile beginnt höchstens so weit neben einem Sprach-Einsatz: zählt als passend. */
    private const val MATCH_MS = 700L
    private const val MIN_Z = 6.0
    /** Gemessen (SpeechSyncTest): Dateien anderer Filme erreichen bis 5,8, die richtige nach 90 s Gespräch meist über 5,7. */
    const val COMPARE_MIN_Z = 5.8
    private const val COMPARE_MARGIN = 1.3
    private const val COMPARE_CLEAR = 1.15
    private const val LINGER_MS = 500L
    private const val CLEAR = 1.3
    private const val SAME_MS = 1_500L
    private const val TIE_Z = 1.0
}

package de.ayutv.player

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SubtitleAlignTest {
    /** Zeilenanfänge einer Datei: alle 1,5–6 s eine Zeile, 90 Minuten. */
    private fun lines(seed: Int, minutes: Int = 90): List<Long> {
        val random = Random(seed)
        var t = 30_000L
        return buildList {
            while (t < minutes * 60_000L) {
                add(t)
                t += 1_500 + random.nextInt(4_500)
            }
        }
    }

    /** Anteil der Datei-Zeilen, die nach der Ausrichtung auf ±250 ms an der richtigen Stelle im Film stehen. */
    private fun accuracy(result: SubtitleAlign.Result, file: List<Long>, truth: (Long) -> Long?): Double {
        val checked = file.mapNotNull { s -> truth(s)?.let { abs(result.timing.toVideo(s) - it) <= 250 } }
        return checked.count { it }.toDouble() / checked.size
    }

    @Test
    fun followsJumpsInsideTheFilm() {
        // Die Datei gehört zu einer Fassung mit 2,5 s mehr Vorspann; nach 40 Minuten fehlt im Film eine Szene von 7 s,
        // nach 70 Minuten eine Werbepause von 45 s mehr. Ein fester Versatz kann das nicht abbilden.
        val file = lines(1)
        fun video(s: Long): Long = s - 2_500 + (if (s > 40 * 60_000L) -7_000 else 0) + (if (s > 70 * 60_000L) 45_000 else 0)
        val jitter = Random(2)
        // Eingebettete Spur: andere Sprache, einzelne Zeilen fehlen oder sind zusätzlich, ±80 ms Abweichung.
        val references = file.filter { jitter.nextInt(10) != 0 }.map { video(it) + jitter.nextInt(-80, 80) } +
            List(40) { jitter.nextLong(60_000L, 90 * 60_000L) }
        val result = SubtitleAlign.align(references, file)!!
        val accuracy = accuracy(result, file) { s -> video(s).takeUnless { s in 40 * 60_000L - 60_000..40 * 60_000L + 60_000 || s in 70 * 60_000L - 60_000..70 * 60_000L + 60_000 } }
        assertTrue("Genauigkeit $accuracy, ${result.percent} %", accuracy > 0.95)
        assertTrue("Anteil ${result.percent} %", result.percent >= 75)
    }

    @Test
    fun frameRateWithOffset() {
        val file = lines(3)
        val ratio = 25.0 / 23.976
        fun video(s: Long) = (s * ratio).toLong() + 12_000
        val references = file.map { video(it) }
        val global = SubtitleSync.estimate(references.take(60), file)!!
        val result = SubtitleAlign.align(references, file, global.timing.ratio, global.timing.offsetMs)!!
        val accuracy = accuracy(result, file) { video(it) }
        assertTrue("Genauigkeit $accuracy", accuracy > 0.97)
    }

    @Test
    fun otherFilmIsRejectedOrScoresLow() {
        val result = SubtitleAlign.align(lines(5), lines(6))
        assertTrue("Fremder Film passt angeblich zu ${result?.percent} %", result == null || result.percent < 30)
    }

    @Test
    fun tooFewReferences() {
        assertNull(SubtitleAlign.align(lines(7).take(5), lines(7)))
    }

    @Test
    fun farFromAnchorsIsReportedAsUncovered() {
        // Nur die ersten Minuten sind gelesen (Player stand am Anfang), dann wird weit gesprungen.
        val file = lines(5)
        val early = file.filter { it < 5 * 60_000L }.map { it - 2_500 }
        val result = SubtitleAlign.align(early, file)!!
        assertTrue(result.timing.distanceTo(2 * 60_000L) < 10_000)
        assertTrue("Nach dem Sprung dürfen die frühen Anker nicht als passend gelten", result.timing.distanceTo(60 * 60_000L) > 90_000)
    }
}

package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Geladene Untertitel an eingebetteten ausrichten: realistische Fälle mit Zufallsabständen wie im Film. */
class SubtitleSyncTest {
    private val random = Random(7)

    /** Sprechzeiten im Film: Abstände 1,5–7 s, wie bei echten Dialogen. */
    private val speech: List<Long> = buildList {
        var t = 0L
        repeat(1_200) {
            t += random.nextLong(1_500, 7_000)
            add(t)
        }
    }

    /**
     * Eingebettete Spur in anderer Sprache: andere Zeilenaufteilung (30 % fehlen, 20 % zusätzlich),
     * leicht abweichende Startzeiten.
     */
    private fun references(from: Long, until: Long): List<Long> {
        val matched = speech.filter { it in from until until && random.nextDouble() > 0.3 }
            .map { it + random.nextLong(-150, 150) }
        val extra = List(matched.size / 5) { random.nextLong(from, until) }
        return (matched + extra).sorted()
    }

    @Test
    fun findsConstantOffsetAfterFewMinutes() {
        val file = speech.map { it + 2_400 } // andere Fassung: Untertitel 2,4 s zu spät
        val fit = requireFit(SubtitleSync.estimate(references(0, 240_000), file))
        assertEquals(1.0, fit.timing.ratio, 0.0)
        assertTrue("Versatz ${fit.timing.offsetMs}", abs(fit.timing.offsetMs + 2_400) < 150)
    }

    @Test
    fun correctsPalSpeedup() {
        // Untertitel für die 25-fps-Fassung, der Film läuft mit 23,976 fps (4,3 % länger).
        val ratio = 25.0 / 23.976
        val file = speech.map { (it / ratio).toLong() }
        val fit = requireFit(SubtitleSync.estimate(references(600_000, 1_500_000), file))
        assertEquals(ratio, fit.timing.ratio, 0.0001)
        val at = 1_200_000L
        assertTrue("Abweichung bei 20 min", abs(fit.timing.toVideo((at / ratio).toLong()) - at) < 300)
    }

    @Test
    fun rejectsUnrelatedReferences() {
        val unrelated = List(80) { random.nextLong(0, 600_000) }.sorted()
        assertNull(SubtitleSync.estimate(unrelated, speech))
    }

    @Test
    fun waitsForEnoughReferences() {
        assertNull(SubtitleSync.estimate(references(0, 15_000).take(4), speech))
    }

    @Test
    fun timingRoundTrip() {
        val timing = SubtitleTiming(25.0 / 23.976, -1_500)
        assertTrue(abs(timing.toSubtitle(timing.toVideo(3_600_000)) - 3_600_000) <= 1)
    }

    /** Schnellprüfung: Schon die ersten ~10 vorausgelesenen Einblendungen zeigen, ob eine Datei passt. */
    @Test
    fun fewLinesTellFitAndMiss() {
        val first = speech.filter { it < 45_000 }.map { it + random.nextLong(-120, 120) }
        assertTrue("zu wenige Einblendungen: ${first.size}", first.size in 6..20)
        // Passende Datei einer anderen Fassung (3,1 s später): Versatz sofort gefunden, fast alles trifft.
        val file = speech.map { it + 3_100 }
        val fit = requireFit(SubtitleSync.estimate(first, file))
        assertTrue("Versatz ${fit.timing.offsetMs}", abs(fit.timing.offsetMs + 3_100) < 150)
        assertTrue(SubtitleSync.coverage(first, file, fit.timing) >= 90)
        // Datei eines anderen Films: kein eindeutiger Versatz, und mit jedem Versatz trifft kaum etwas.
        val other = Random(99)
        var t = 0L
        val wrong = List(800) { t += other.nextLong(1_500, 7_000); t }
        val miss = SubtitleSync.estimate(first, wrong)
        assertTrue(miss == null || SubtitleSync.coverage(first, wrong, miss.timing) < 60)
        assertTrue(SubtitleSync.coverage(first, wrong, SubtitleTiming()) < 40)
    }

    private fun requireFit(fit: SubtitleSync.Fit?): SubtitleSync.Fit {
        assertNotNull("kein eindeutiges Ergebnis", fit)
        return fit!!
    }
}

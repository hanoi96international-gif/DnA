package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Untertitel am Ton ausrichten: künstlicher Filmton mit Sätzen aus Silben (Rauschen mit Silbenrhythmus),
 * Pausen, Musikszenen und Grundrauschen. Die Untertitel sind die Satzzeiten, ungenau wie echte.
 */
class SpeechSyncTest {
    private val rate = 16_000

    private class Film(val pcm: FloatArray, val cues: List<Pair<Long, Long>>)

    private fun film(seed: Int, minutes: Int): Film {
        val random = Random(seed)
        val pcm = FloatArray(minutes * 60 * rate)
        for (i in pcm.indices) pcm[i] = (random.nextFloat() - 0.5f) * 0.01f
        // Musikszenen: gehaltene Akkorde, mal leise, mal laut
        var m = random.nextInt(20, 90) * rate
        while (m < pcm.size) {
            val length = random.nextInt(20, 60) * rate
            val level = random.nextFloat() * 0.3f
            val notes = listOf(220.0, 277.0, 330.0, 440.0).shuffled(random).take(3)
            for (i in m until minOf(pcm.size, m + length)) {
                pcm[i] += level * notes.sumOf { sin(2 * PI * it * i / rate) }.toFloat() / 3
            }
            m += length + random.nextInt(30, 120) * rate
        }
        // Sätze: 2–12 Silben à 120–260 ms, dazwischen 0,4–6 s Pause
        val cues = ArrayList<Pair<Long, Long>>()
        var t = random.nextInt(2, 8) * rate
        while (t < pcm.size - 5 * rate) {
            val start = t
            repeat(random.nextInt(2, 13)) {
                val syllable = random.nextInt(120, 260) * rate / 1000
                val loud = 0.15f + random.nextFloat() * 0.35f
                for (k in 0 until syllable) {
                    if (t + k >= pcm.size) break
                    val envelope = (0.5 - 0.5 * cos(2 * PI * k / syllable)).toFloat()
                    pcm[t + k] += loud * envelope * (random.nextFloat() - 0.5f) * 2
                }
                t += syllable + random.nextInt(10, 80) * rate / 1000
            }
            val end = t
            // Untertitel: etwas früher an, länger stehen, manche Sätze fehlen
            if (random.nextDouble() > 0.1) {
                cues += (start * 1000L / rate - random.nextLong(0, 300)) to (end * 1000L / rate + random.nextLong(200, 800))
            }
            t += random.nextInt(400, 6_000) * rate / 1000
        }
        return Film(pcm, cues)
    }

    private fun listen(film: Film, fromMinute: Int, minutes: Int): SpeechActivity {
        val heard = SpeechActivity()
        heard.restart(fromMinute * 60_000L, rate)
        val from = fromMinute * 60 * rate
        val to = minOf(film.pcm.size, from + minutes * 60 * rate)
        heard.add(film.pcm.copyOfRange(from, to), to - from)
        return heard
    }

    /** Untertiteldatei einer anderen Fassung: Filmzeit = Dateizeit × ratio + offset. */
    private fun shifted(cues: List<Pair<Long, Long>>, ratio: Double, offsetMs: Long) =
        cues.map { (a, b) -> ((a - offsetMs) / ratio).toLong() to ((b - offsetMs) / ratio).toLong() }.filter { it.first >= 0 }

    private val film = film(seed = 3, minutes = 20)

    @Test
    fun findsOffsetFromSpeech() {
        val heard = listen(film, fromMinute = 2, minutes = 6)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 1.0, 7_300))
        assertNotNull("keine Ausrichtung gefunden", fit)
        assertEquals(1.0, fit!!.timing.ratio, 0.0)
        assertTrue("Versatz ${fit.timing.offsetMs}", abs(fit.timing.offsetMs - 7_300) <= 200)
    }

    @Test
    fun findsOffsetAfterTwoMinutes() {
        // Schnell: zwei Minuten Ton reichen (früher waren es mehrere Minuten, bis der Treffer eindeutig war).
        val found = listOf(1, 4, 7, 10, 13).count { minute ->
            val fit = SpeechSync.estimate(listen(film, fromMinute = minute, minutes = 2).signal(), shifted(film.cues, 1.0, -45_000))
            fit != null && abs(fit.timing.offsetMs + 45_000) <= 300
        }
        assertTrue("nur $found von 5 Stellen nach 2 Minuten ausgerichtet", found >= 4)
    }

    @Test
    fun loudNoisesDoNotWin() {
        // Laute Geräusche (Türen, Schüsse, Autos) ohne Untertitel dürfen den Takt nicht verschieben.
        val noisy = film.pcm.copyOf()
        val random = Random(9)
        var e = 3 * rate
        while (e < noisy.size) {
            val length = random.nextInt(100, 1_500) * rate / 1000
            for (k in 0 until length) if (e + k < noisy.size) noisy[e + k] += 0.6f * (random.nextFloat() - 0.5f) * 2
            e += random.nextInt(3, 12) * rate
        }
        val heard = listen(Film(noisy, film.cues), fromMinute = 2, minutes = 5)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 1.0, 12_000))
        assertNotNull("keine Ausrichtung trotz Geräuschen", fit)
        assertTrue("Versatz ${fit!!.timing.offsetMs}", abs(fit.timing.offsetMs - 12_000) <= 300)
    }

    @Test
    fun findsFrameRateAndOffset() {
        val heard = listen(film, fromMinute = 0, minutes = 10)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 25.0 / 23.976, -20_000))
        assertNotNull("keine Ausrichtung gefunden", fit)
        assertEquals(25.0 / 23.976, fit!!.timing.ratio, 1e-9)
        val middle = 5 * 60_000L
        val error = fit.timing.toVideo(((middle + 20_000) / (25.0 / 23.976)).toLong()) - middle
        assertTrue("Abweichung $error ms", abs(error) <= 200)
    }

    @Test
    fun alreadyFittingSubtitlesStayAtZero() {
        val heard = listen(film, fromMinute = 10, minutes = 5)
        val fit = SpeechSync.estimate(heard.signal(), film.cues)
        assertNotNull(fit)
        assertTrue("Versatz ${fit!!.timing.offsetMs}", abs(fit.timing.offsetMs) <= 200)
    }

    @Test
    fun windowFollowsCutVersion() {
        // Gekürzte Fassung: ab Minute 9 liegen die Untertitel 6 s später als vorher.
        val heard = listen(film, fromMinute = 0, minutes = 20)
        val cues = film.cues.map { (a, b) -> if (a < 540_000) (a - 1_000) to (b - 1_000) else (a - 7_000) to (b - 7_000) }
        val late = SpeechSync.estimate(heard.signal(13 * 60_000L, 19 * 60_000L), cues)
        assertNotNull(late)
        assertTrue("Versatz ${late!!.timing.offsetMs}", abs(late.timing.offsetMs - 7_000) <= 200)
    }

    @Test
    fun rejectsSubtitlesOfAnotherFilm() {
        val heard = listen(film, fromMinute = 0, minutes = 12)
        for (seed in listOf(11, 12, 13)) {
            assertNull("fremder Film $seed passt angeblich", SpeechSync.estimate(heard.signal(), film(seed, 20).cues))
        }
    }

    @Test
    fun needsEnoughSound() {
        val heard = listenSeconds(film, fromMinute = 0, seconds = 30)
        assertNull(SpeechSync.estimate(heard.signal(), film.cues))
        assertNull(SpeechSync.estimate(SpeechActivity().signal(), film.cues))
    }

    @Test
    fun seekKeepsFilmTime() {
        val heard = SpeechActivity()
        for ((from, minutes) in listOf(0 to 3, 12 to 3)) {
            heard.restart(from * 60_000L, rate)
            val a = from * 60 * rate
            val b = a + minutes * 60 * rate
            heard.add(film.pcm.copyOfRange(a, b), b - a)
        }
        assertEquals(360, heard.seconds(), 2)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 1.0, -2_500))
        assertNotNull(fit)
        assertTrue("Versatz ${fit!!.timing.offsetMs}", abs(fit.timing.offsetMs + 2_500) <= 200)
    }

    @Test
    fun picksTheMatchingFileAmongSeveralAfterTwoMinutes() {
        // Automatisch statt „andere Datei laden“: Die richtige Datei (andere Fassung, 7,3 s versetzt) liegt nach zwei
        // Minuten Gespräch klar vor Dateien anderer Filme; ihre Lage wird gleich mitgeliefert.
        val others = listOf(film(51, 20).cues, film(52, 20).cues)
        val chosen = listOf(1, 6, 11).count { minute ->
            val signal = listen(film, fromMinute = minute, minutes = 2).signal()
            val files = listOf(others[0], shifted(film.cues, 1.0, 7_300), others[1])
            val evaluations = files.map { SpeechSync.evaluate(signal, it) }
            val best = SpeechSync.pickBest(evaluations)
            best == 1 && abs(evaluations[1]!!.timing.offsetMs - 7_300) <= 300
        }
        assertEquals(3, chosen)
    }

    @Test
    fun picksNothingWhenNoFileBelongsToTheFilm() {
        val signal = listen(film, fromMinute = 3, minutes = 2).signal()
        val evaluations = (53..56).map { SpeechSync.evaluate(signal, film(it, 20).cues) }
        assertNull(SpeechSync.pickBest(evaluations))
    }

    @Test
    fun speechOnsetsFindCutVersionSectionBySection() {
        // Datei einer Fassung, in der ab Minute 9 eine Szene von 40 s fehlt: Global passt nur die erste Hälfte.
        val heard = listen(film, fromMinute = 2, minutes = 14)
        val signal = heard.signal()
        val onsets = SpeechSync.onsets(signal)
        assertTrue("nur ${onsets.size} Einsätze", onsets.size >= 40)
        val cut = 9 * 60_000L
        val file = film.cues.map { (a, b) -> if (a < cut) a to b else (a + 40_000) to (b + 40_000) }
        // Global gibt es keine eindeutige Lage (zwei Hälften mit verschiedenem Versatz) – die beste dient als Ausgangspunkt.
        assertNull(SpeechSync.estimate(signal, file))
        val global = SpeechSync.evaluate(signal, file)
        assertNotNull(global)
        val result = SubtitleAlign.align(onsets, file.map { it.first }, global!!.timing.ratio, global.timing.offsetMs)
        assertNotNull("kein Zeilen-Abgleich", result)
        assertTrue("passt nur ${result!!.percent} %", result.percent >= 40)
        // Vor und nach dem Schnitt landet jede Zeile an der richtigen Filmzeit.
        var good = 0
        var total = 0
        for ((index, cue) in film.cues.withIndex()) {
            val (start, _) = cue
            if (start < 3 * 60_000L || start > 15 * 60_000L) continue
            total++
            if (abs(result!!.timing.toVideo(file[index].first) - start) <= 1_000) good++
        }
        assertTrue("nur $good von $total Zeilen richtig", good >= total * 8 / 10)
    }

    @Test
    fun speechOnsetsDoNotFitSubtitlesOfAnotherFilm() {
        val signal = listen(film, fromMinute = 2, minutes = 14).signal()
        val onsets = SpeechSync.onsets(signal)
        val other = film(seed = 11, minutes = 20).cues
        val center = SpeechSync.evaluate(signal, other)
        val result = SubtitleAlign.align(onsets, other.map { it.first }, center?.timing?.ratio ?: 1.0, center?.timing?.offsetMs ?: 0L)
        assertTrue("fremder Film passt ${result?.percent} %", (result?.percent ?: 0) < 40)
    }

    @Test
    fun matchPercentSeparatesTheRightFileFromAnotherFilm() {
        val signal = listen(film, fromMinute = 2, minutes = 6).signal()
        val right = SpeechSync.matchPercent(signal, film.cues, SubtitleTiming())
        val other = SpeechSync.matchPercent(signal, film(seed = 11, minutes = 20).cues, SubtitleTiming())
        val shifted = SpeechSync.matchPercent(signal, film.cues, SubtitleTiming(1.0, 4_000))
        assertNotNull(right)
        assertTrue("richtige Datei nur $right %", right!! >= 60)
        assertTrue("fremder Film $other % (richtig $right %)", (other ?: 0) <= right - 30)
        assertTrue("um 4 s verschoben $shifted % (richtig $right %)", (shifted ?: 0) <= right - 30)
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertTrue("$actual statt $expected", abs(expected - actual) <= delta)

    private fun listenSeconds(film: Film, fromMinute: Int, seconds: Int): SpeechActivity {
        val heard = SpeechActivity()
        heard.restart(fromMinute * 60_000L, rate)
        val from = fromMinute * 60 * rate
        val to = minOf(film.pcm.size, from + seconds * rate)
        heard.add(film.pcm.copyOfRange(from, to), to - from)
        return heard
    }

    @Test
    fun shortSoundGivesNoWrongResult() {
        // Schon ab 45 s Ton wird ausgerichtet: Das darf nie eine falsche Lage oder die Datei eines anderen Films ergeben.
        var right = 0
        for (seed in listOf(3, 5)) {
            val f = film(seed, 20)
            val others = listOf(film(21, 20).cues, film(22, 20).cues)
            for (minute in listOf(1, 5, 11, 17)) {
                val signal = listenSeconds(f, minute, 45).signal()
                for (offset in listOf(-45_000L, 7_300L, 120_000L)) {
                    val fit = SpeechSync.estimate(signal, shifted(f.cues, 1.0, offset)) ?: continue
                    assertTrue("falsche Lage ${fit.timing.offsetMs} statt $offset", abs(fit.timing.offsetMs - offset) <= 300)
                    right++
                }
                for (other in others) assertNull("fremder Film passt angeblich", SpeechSync.estimate(signal, other))
            }
        }
        println("45 s Ton: $right richtige Ergebnisse")
    }
}

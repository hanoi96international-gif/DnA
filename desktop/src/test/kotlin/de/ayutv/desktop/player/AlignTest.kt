package de.ayutv.desktop.player

import de.ayutv.player.SpeechActivity
import de.ayutv.player.SpeechSync
import de.ayutv.player.SubtitleTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

/** Ausrichten unter Windows: Ton kommt wie von der DNA Engine (16 Bit Stereo, 48 kHz) über [AudioTap] in die Auswertung. */
class AlignTest {
    private val rate = AudioTap.RATE

    @Test
    fun srtTimesRoundTripAndShift() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHallo\n\n2\n01:02:03,4 --> 01:02:05,000\nWelt\n"
        assertEquals(listOf(1_500L to 3_000L, 3_723_400L to 3_725_000L), SubtitleFiles.cues(srt))
        val moved = SubtitleFiles.shifted(srt, SubtitleTiming(1.0, -2_000))
        // Die erste Einblendung begänne vor dem Film: Sie steht ab 0:00, solange sie noch läuft.
        assertEquals(listOf(0L to 1_000L, 3_721_400L to 3_723_000L), SubtitleFiles.cues(moved))
        assertEquals(emptyList<Pair<Long, Long>>(), SubtitleFiles.cues(SubtitleFiles.shifted(srt, SubtitleTiming(1.0, -4_000_000))))
        assertEquals("01:02:01,400", SubtitleFiles.formatMs(3_721_400))
    }

    @Test
    fun findsOffsetFromTappedSound() {
        val random = Random(7)
        val minutes = 4
        val start = 120_000L
        val frames = minutes * 60 * rate
        val pcm = FloatArray(frames) { (random.nextFloat() - 0.5f) * 0.01f }
        // Sätze aus Silben (Rauschen mit Silbenrhythmus), dazwischen Pausen.
        val cues = ArrayList<Pair<Long, Long>>()
        var t = 3 * rate
        while (t < frames - 5 * rate) {
            val from = t
            repeat(random.nextInt(2, 13)) {
                val syllable = random.nextInt(120, 260) * rate / 1000
                val loud = 0.15f + random.nextFloat() * 0.35f
                for (k in 0 until syllable) {
                    if (t + k >= frames) break
                    pcm[t + k] += loud * (0.5 - 0.5 * cos(2 * PI * k / syllable)).toFloat() * (random.nextFloat() - 0.5f) * 2
                }
                t += syllable + random.nextInt(10, 80) * rate / 1000
            }
            if (random.nextDouble() > 0.1) cues += (start + from * 1000L / rate - random.nextLong(0, 300)) to (start + t * 1000L / rate + random.nextLong(200, 800))
            t += random.nextInt(400, 6_000) * rate / 1000
        }
        // Untertitel-Datei 2,5 s zu früh: Filmzeit = Dateizeit + 2500.
        val file = cues.map { (a, b) -> (a - 2_500) to (b - 2_500) }

        val speech = SpeechActivity()
        val tap = AudioTap(speech, { start })
        tap.restart()
        val chunk = 4_096
        val bytes = ByteArray(chunk * 4)
        var i = 0
        while (i < frames) {
            val n = minOf(chunk, frames - i)
            for (k in 0 until n) {
                val v = (pcm[i + k] * 32_000).toInt().coerceIn(-32_768, 32_767)
                for (c in 0 until 2) {
                    bytes[k * 4 + c * 2] = v.toByte()
                    bytes[k * 4 + c * 2 + 1] = (v shr 8).toByte()
                }
            }
            tap.consume(bytes, n)
            i += n
        }
        assertTrue("gehört: ${speech.seconds()} s", speech.seconds() >= SpeechSync.MIN_SECONDS)
        val fit = SpeechSync.estimate(speech.signal(), file)
        assertNotNull("keine Ausrichtung", fit)
        assertEquals(1.0, fit!!.timing.ratio, 0.0)
        assertTrue("Versatz ${fit.timing.offsetMs}", abs(fit.timing.offsetMs - 2_500) <= 200)
    }
}

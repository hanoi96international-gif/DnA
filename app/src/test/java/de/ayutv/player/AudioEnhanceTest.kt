package de.ayutv.player

import de.ayutv.data.AudioMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioEnhanceTest {
    private val rate = 48_000

    private fun tone(frames: Int, channels: Int, amplitude: Float, perChannel: (Int) -> Float = { 1f }) =
        FloatArray(frames * channels) { i -> amplitude * perChannel(i % channels) * sin(2 * PI * 440 * (i / channels) / rate).toFloat() }

    private fun peak(s: FloatArray, from: Int = 0) = s.drop(from).maxOf { abs(it) }

    @Test
    fun normalLeavesSoundUntouched() {
        val s = tone(4_800, 2, 0.5f)
        val before = s.copyOf()
        AudioEnhance().process(s, 4_800, 2, rate)
        assertArrayEquals(before, s, 0f)
    }

    @Test
    fun nightModeLiftsQuietAndTamesLoud() {
        val e = AudioEnhance().apply { mode = AudioMode.NIGHT }
        val quiet = tone(rate, 2, 0.02f)
        e.process(quiet, rate, 2, rate)
        // Nach dem Einschwingen deutlich lauter als vorher.
        assertTrue(peak(quiet, rate) > 0.05f)
        e.reset()
        val loud = tone(rate, 2, 0.95f)
        e.process(loud, rate, 2, rate)
        // Laut bleibt unter Vollaussteuerung und ist nicht lauter als vorher.
        assertTrue(peak(loud, rate) <= 0.95f)
        // Abstand zwischen leise und laut schrumpft.
        assertTrue(peak(loud, rate) / peak(quiet, rate) < 0.95f / 0.02f / 3)
    }

    @Test
    fun dialogBoostsCenterOf51() {
        val e = AudioEnhance().apply { mode = AudioMode.DIALOG }
        val s = tone(1_000, 6, 0.3f)
        e.process(s, 1_000, 6, rate)
        val center = (0 until 1_000).maxOf { abs(s[it * 6 + 2]) }
        val left = (0 until 1_000).maxOf { abs(s[it * 6]) }
        assertTrue(center > 0.45f)
        assertTrue(left < 0.2f)
    }

    @Test
    fun dialogKeepsStereoMidLouderThanSide() {
        val e = AudioEnhance().apply { mode = AudioMode.DIALOG }
        // Nur Mitte (beide Kanäle gleich): lauter.
        val mid = tone(1_000, 2, 0.3f)
        e.process(mid, 1_000, 2, rate)
        assertTrue(peak(mid) > 0.4f)
        // Nur Seite (gegenphasig): leiser.
        val side = tone(1_000, 2, 0.3f) { c -> if (c == 0) 1f else -1f }
        e.process(side, 1_000, 2, rate)
        assertTrue(peak(side) < 0.2f)
    }

    @Test
    fun limiterNeverClips() {
        for (x in listOf(0.5f, 0.8f, 1f, 2f, 10f, -3f)) {
            val y = AudioEnhance.limit(x)
            assertTrue(abs(y) <= 1f)
            assertEquals(x < 0, y < 0)
        }
        assertEquals(0.5f, AudioEnhance.limit(0.5f), 0f)
    }

    @Test
    fun levelingBringsLoudAndQuietChannelsCloser() {
        val e = AudioEnhance().apply { leveling = true }
        // 10 s laute Werbung: wird deutlich leiser
        val loud = tone(10 * rate, 2, 0.9f)
        e.process(loud, 10 * rate, 2, rate)
        val loudEnd = peak(loud, 18 * rate)
        assertTrue("laut nicht leiser: $loudEnd", loudEnd < 0.4f)
        // danach 20 s leiser Sender: wird lauter (langsamer)
        val quiet = tone(20 * rate, 2, 0.03f)
        e.process(quiet, 20 * rate, 2, rate)
        val quietEnd = peak(quiet, 38 * rate)
        assertTrue("leise nicht lauter: $quietEnd", quietEnd > 0.06f)
        // Stille wird nicht angehoben
        val silence = FloatArray(2 * rate)
        e.process(silence, rate, 2, rate)
        assertTrue(silence.all { it == 0f })
    }
}

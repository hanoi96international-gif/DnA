package de.ayutv.desktop.player

import de.ayutv.data.parse.Hid

import de.ayutv.player.dna.engine.Codec
import de.ayutv.player.dna.engine.Track
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows-Engine (DNA Engine mit FFmpeg statt VLC) unter Linux: Testvideos abspielen, Bilder, Ton und Untertitel
 * (Text aus MKV, Bild aus DVB im TS) kommen an, Spulen landet an der richtigen Stelle.
 */
class DesktopEngineTest {
    private class Collect : DesktopEngine.Output {
        val frames = AtomicInteger()
        val lastWidth = AtomicInteger()
        val lastHeight = AtomicInteger()
        val lastTimeUs = AtomicLong(-1)
        val pcmFrames = AtomicLong()
        @Volatile var error: String? = null
        @Volatile var ended = false
        @Volatile var text: List<Track> = emptyList()
        @Volatile var video: Track? = null
        val infos = CopyOnWriteArrayList<String>()

        override fun onPrepared(durationMs: Long, seekable: Boolean, live: Boolean) = Unit
        override fun onTracks(video: Track?, audio: List<Track>, text: List<Track>, selectedAudio: Track?) {
            this.video = video
            this.text = text
        }
        override fun onFrame(frame: DesktopEngine.Frame) {
            lastWidth.set(frame.yuv.width)
            lastHeight.set(frame.yuv.height)
            lastTimeUs.set(frame.timeUs)
            frames.incrementAndGet()
            frame.yuv.close()
        }
        override fun onBuffering(buffering: Boolean) = Unit
        override fun onEnded() { ended = true }
        override fun onError(message: String) { error = message }
        override fun onInfo(text: String) { infos += text }
        override fun onPcm(data: ByteArray, frames: Int, rate: Int, timeUs: Long) { pcmFrames.addAndGet(frames.toLong()) }
    }

    private val out = Collect()
    private val engine = DesktopEngine(OkHttpClient(), out).apply { muted = true }

    @After
    fun release() = engine.release()

    private fun resource(path: String): String {
        val file = File.createTempFile("dna-", "-" + path.substringAfterLast('/'))
        file.deleteOnExit()
        javaClass.getResourceAsStream(path)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    /** Testmedien der Android-App (gleiche Engine-Dateien). */
    private fun appMedia(name: String): String = File("../app/src/test/resources/dna/$name").absoluteFile.also { assertTrue(it.isFile) }.path

    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun playsH264AacTs() {
        engine.open(resource("/selftest/blue.ts"), null, live = false, startUs = 0)
        assertTrue("Bilder: ${out.frames.get()} ${out.error} ${out.infos}", waitFor(15_000) { out.frames.get() >= 25 })
        assertNull(out.error)
        assertEquals(640, out.lastWidth.get())
        assertEquals(360, out.lastHeight.get())
        assertTrue("Ton: ${out.pcmFrames.get()}", waitFor(5_000) { out.pcmFrames.get() > 48_000 })
        assertTrue(engine.decoderName, engine.decoderName.isNotBlank())
        assertTrue(engine.audioDecoderName, engine.audioDecoderName.isNotBlank())
        // Takt läuft mit (nicht schneller als die Wanduhr, nicht stehen geblieben).
        val p1 = engine.positionUs
        Thread.sleep(1_000)
        val moved = engine.positionUs - p1
        assertTrue("Takt: $moved µs je Sekunde", moved in 600_000..1_400_000)
    }

    @Test
    fun playsHevc10Ac3InP010() {
        engine.config = DesktopEngine.Config(layout = "P010")
        engine.open(resource("/selftest/blue10.ts"), null, live = false, startUs = 0)
        assertTrue("Bilder: ${out.frames.get()} ${out.error} ${out.infos}", waitFor(15_000) { out.frames.get() >= 10 })
        assertNull(out.error)
        assertEquals(YuvVideo.Layout.P010, engine.layout)
        assertTrue("Ton (AC-3): ${out.pcmFrames.get()}", waitFor(5_000) { out.pcmFrames.get() > 24_000 })
    }

    @Test
    fun mkvTextSubtitlesAndSeek() {
        engine.open(appMedia("h264_aac_srt.mkv"), null, live = false, startUs = 0)
        assertTrue("Bilder: ${out.frames.get()} ${out.error}", waitFor(15_000) { out.frames.get() >= 5 })
        val srt = out.text.firstOrNull { it.codec == Codec.SRT }
        assertNotNull("Untertitel-Spur", srt)
        assertTrue(waitFor(5_000) { engine.subtitles.at(srt!!.id, 700_000)?.text != null })
        assertEquals("Hallo Welt", engine.subtitles.at(srt!!.id, 700_000)!!.text)
        // Spulen auf 1,5 s: das nächste Bild liegt dort (Schlüsselbild davor, bis zur Stelle vorgelaufen).
        val before = out.frames.get()
        engine.seekTo(1_500_000)
        assertTrue(waitFor(10_000) { out.frames.get() > before && out.lastTimeUs.get() >= 1_400_000 })
        assertTrue("Position ${engine.positionUs}", engine.positionUs in 1_300_000..2_200_000)
    }

    @Test
    fun dvbSubtitlesFromTs() {
        engine.open(appMedia("h264_dvbsub.ts"), null, live = false, startUs = 0)
        assertTrue("Bilder: ${out.frames.get()} ${out.error}", waitFor(15_000) { out.frames.get() >= 5 })
        val dvb = out.text.firstOrNull { it.codec == Codec.DVBSUB }
        assertNotNull("DVB-Spur", dvb)
        assertEquals("deu", dvb!!.language)
        val shown = waitFor(10_000) {
            (0L..4_000_000L step 250_000).any { t -> engine.subtitles.at(dvb.id, t)?.images?.any { it.opaquePixels > 100 } == true }
        }
        assertTrue("DVB-Bild-Untertitel nicht dekodiert", shown)
    }

    @Test
    fun plays4kHevc10() {
        // 4K HEVC 10 Bit, 25 fps – hier ohne Grafikkarte, also in Software (der härteste Fall).
        engine.open(resource(Hid.s("axspSXsjfz7yOWoaMg==")), null, live = false, startUs = 0)
        assertTrue("4K: kein Bild ${out.error} ${out.infos}", waitFor(30_000) { out.frames.get() >= 1 })
        assertNull(out.error)
        assertEquals(3840, out.lastWidth.get())
        assertEquals(2160, out.lastHeight.get())
        val f0 = out.frames.get()
        val p0 = engine.positionUs
        Thread.sleep(3_000)
        val shown = out.frames.get() - f0
        val moved = engine.positionUs - p0
        println("4K: $shown Bilder in 3 s, Uhr ${moved / 1000} ms, verworfen ${engine.droppedFrames}, Decoder ${engine.decoderName}")
        assertTrue("4K: Uhr steht ($moved µs)", moved > 2_000_000)
        assertTrue("4K: zu wenige Bilder ($shown in 3 s)", shown >= 15)
    }
}

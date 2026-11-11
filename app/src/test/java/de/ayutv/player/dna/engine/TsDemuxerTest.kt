package de.ayutv.player.dna.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * DNA Engine, eigener TS-Leser: mit ffmpeg erzeugte Ströme (2 s, 25 fps) mit allen Rundfunk-Formaten. Geprüft: Spuren,
 * jedes Bild, Schlüsselbilder, lückenlose Tonzeiten, Decoder-Konfiguration – und dass kaputte Ströme nie abstürzen.
 */
class TsDemuxerTest {
    private class Result(val tracks: List<Track>, val video: List<Sample>, val audio: List<Sample>, val corrupt: Int)

    private fun read(name: String, data: ByteArray = resource(name), seed: Long = 1): Result {
        var tracks = emptyList<Track>()
        val video = ArrayList<Sample>()
        val audio = ArrayList<Sample>()
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(found: List<Track>) {
                tracks = found
            }

            override fun onSample(sample: Sample) {
                if (sample.track.codec.kind == Kind.VIDEO) video += sample else audio += sample
            }
        })
        // In unregelmäßigen Stücken füttern, wie sie aus dem Netz kommen.
        val random = Random(seed)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(data.size - pos, 1 + random.nextInt(3_000))
            demuxer.feed(data, pos, n)
            pos += n
        }
        demuxer.flush()
        return Result(tracks, video, audio, demuxer.corruptPackets)
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/dna/$name")!!.readBytes()

    private fun assertContinuous(audio: List<Sample>) {
        for (i in 1 until audio.size) {
            val format = audio[i - 1].audio!!
            val expected = format.samplesPerFrame * 1_000_000L / format.sampleRate
            val gap = audio[i].timeUs - audio[i - 1].timeUs
            assertTrue("Tonlücke $gap statt $expected µs bei Rahmen $i", kotlin.math.abs(gap - expected) <= 2_000)
        }
    }

    @Test
    fun h264WithAdtsAac() {
        val r = read("h264_aac.ts")
        assertEquals(listOf(Codec.H264, Codec.AAC_ADTS), r.tracks.map { it.codec })
        assertEquals(50, r.video.size)
        assertTrue(r.video.first().keyframe)
        assertEquals(2, r.video.count { it.keyframe })
        // AAC-LC, 48 kHz, Stereo → AudioSpecificConfig 0x11 0x90
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), r.audio.first().audio!!.config)
        assertEquals(48_000, r.audio.first().audio!!.sampleRate)
        assertTrue("Tonrahmen: ${r.audio.size}", r.audio.size in 90..100)
        assertContinuous(r.audio)
        // Bildzeiten steigen in Darstellungsreihenfolge nicht zwingend, liegen aber im Abstand von 40 ms beieinander.
        val span = r.video.maxOf { it.timeUs } - r.video.minOf { it.timeUs }
        assertEquals(1_960_000.0, span.toDouble(), 1_000.0)
    }

    @Test
    fun aacInLatmLikeDvb() {
        val r = read("h264_latm.ts")
        assertEquals(Codec.AAC_LATM, r.tracks[1].codec)
        val format = r.audio.first().audio!!
        assertEquals(48_000, format.sampleRate)
        assertEquals(2, format.channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), format.config)
        assertTrue(r.audio.size in 90..100)
        assertContinuous(r.audio)
    }

    @Test
    fun hevc10BitWithAc3Surround() {
        val r = read("hevc10_ac3.ts")
        assertEquals(listOf(Codec.HEVC, Codec.AC3), r.tracks.map { it.codec })
        assertEquals(50, r.video.size)
        assertTrue(r.video.first().keyframe)
        assertEquals(6, r.audio.first().audio!!.channels)
        assertEquals(1536, r.audio.first().audio!!.samplesPerFrame)
        assertContinuous(r.audio)
    }

    @Test
    fun eac3AndMpegAudio() {
        val eac3 = read("h264_eac3.ts")
        assertEquals(Codec.EAC3, eac3.tracks[1].codec)
        assertContinuous(eac3.audio)
        val mp2 = read("h264_mp2.ts")
        assertEquals(Codec.MPEG_AUDIO, mp2.tracks[1].codec)
        assertEquals(1152, mp2.audio.first().audio!!.samplesPerFrame)
        assertContinuous(mp2.audio)
    }

    @Test
    fun brokenStreamsNeverCrashAndRecover() {
        val clean = resource("hevc10_ac3.ts")
        val random = Random(3)
        // Mitten im Strom einsteigen (Senderwechsel)
        val middle = read("x", clean.copyOfRange(clean.size / 3 + 77, clean.size))
        assertTrue(middle.video.isNotEmpty() && middle.audio.isNotEmpty())
        // Bitfehler
        val flipped = clean.copyOf().also { d -> repeat(200) { val i = random.nextInt(d.size); d[i] = (d[i].toInt() xor 0x10).toByte() } }
        assertTrue(read("x", flipped).video.size > 30)
        // Müll davor und danach
        val garbage = ByteArray(5_000) { random.nextInt(256).toByte() } + clean + ByteArray(2_000) { random.nextInt(256).toByte() }
        assertEquals(50, read("x", garbage).video.size)
    }
}

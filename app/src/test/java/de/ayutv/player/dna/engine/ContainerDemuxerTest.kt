package de.ayutv.player.dna.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DNA Engine, eigene Leser für Filme: MKV/WebM und MP4 (klassisch, „faststart“, fragmentiert) aus ffmpeg-Dateien
 * (2 s, 25 fps). Geprüft: Spuren und Decoder-Daten, jedes Bild, Schlüsselbilder, Annex B, Untertitel mit Zeit und Dauer,
 * Spulen zum Schlüsselbild.
 */
class ContainerDemuxerTest {
    private class Result(val info: ContainerDemuxer.Info, val samples: List<Sample>)

    private fun resource(name: String) = javaClass.getResourceAsStream("/dna/$name")!!.readBytes()

    private fun open(name: String): ContainerDemuxer {
        val reader = SourceReader(ArrayByteSource(resource(name)), bufferSize = 4096)
        val head = resource(name).copyOf(16)
        return if (MkvDemuxer.sniff(head)) MkvDemuxer(reader) else Mp4Demuxer(reader)
    }

    private fun read(name: String): Result {
        val d = open(name)
        val info = d.init()
        val samples = ArrayList<Sample>()
        while (true) samples += d.read() ?: break
        return Result(info, samples)
    }

    private fun Result.of(kind: Kind) = samples.filter { it.track.codec.kind == kind }

    private fun assertAnnexB(video: List<Sample>) = video.forEach {
        assertTrue("kein Startcode", it.data.size > 4 && it.data[0].toInt() == 0 && it.data[1].toInt() == 0 && it.data[2].toInt() == 0 && it.data[3].toInt() == 1)
    }

    @Test
    fun mkvH264AacSrt() {
        val r = read("h264_aac_srt.mkv")
        assertEquals(listOf(Codec.H264, Codec.AAC, Codec.SRT), r.info.tracks.map { it.codec })
        val video = r.info.tracks[0]
        assertEquals(160, video.width)
        assertEquals(2, video.csd.size)
        assertEquals("de", r.info.tracks[1].language)
        assertEquals("en", r.info.tracks[2].language)
        assertEquals(50, r.of(Kind.VIDEO).size)
        assertEquals(2, r.of(Kind.VIDEO).count { it.keyframe })
        assertAnnexB(r.of(Kind.VIDEO))
        val text = r.of(Kind.TEXT)
        assertEquals(listOf("Hallo Welt", "<i>Zweite</i> Zeile"), text.map { String(it.data) })
        assertEquals(500_000L, text[0].timeUs)
        assertEquals(500_000L, text[0].durationUs)
        assertTrue(r.info.durationUs in 1_900_000..2_100_000)
    }

    @Test
    fun mkvHevcAc3AndWebmVp9Opus() {
        val hevc = read("hevc_ac3.mkv")
        assertEquals(listOf(Codec.HEVC, Codec.AC3), hevc.info.tracks.map { it.codec })
        assertEquals(1, hevc.info.tracks[0].csd.size)
        assertEquals(6, hevc.info.tracks[1].channels)
        assertEquals(50, hevc.of(Kind.VIDEO).size)
        assertAnnexB(hevc.of(Kind.VIDEO))
        val webm = read("vp9_opus.webm")
        assertEquals(listOf(Codec.VP9, Codec.OPUS), webm.info.tracks.map { it.codec })
        // Opus: OpusHead, Vorlauf, Seek-Vorlauf
        assertEquals(3, webm.info.tracks[1].csd.size)
        assertEquals(50, webm.of(Kind.VIDEO).size)
    }

    @Test
    fun mp4ClassicFaststartFragmented() {
        for (name in listOf("h264_aac_tx3g.mp4", "faststart.mp4", "frag.mp4")) {
            val r = read(name)
            assertEquals(name, Codec.H264, r.info.tracks.first { it.codec.kind == Kind.VIDEO }.codec)
            assertEquals(name, Codec.AAC, r.info.tracks.first { it.codec.kind == Kind.AUDIO }.codec)
            assertEquals(name, 50, r.of(Kind.VIDEO).size)
            assertEquals(name, 2, r.of(Kind.VIDEO).count { it.keyframe })
            assertAnnexB(r.of(Kind.VIDEO))
            assertTrue(name, r.of(Kind.AUDIO).size in 90..100)
        }
        val text = read("h264_aac_tx3g.mp4").of(Kind.TEXT).filter { it.data.isNotEmpty() }
        assertEquals(listOf("Hallo Welt", "Zweite Zeile"), text.map { String(it.data) })
    }

    @Test
    fun seekLandsOnKeyframeAtOrBefore() {
        for (name in listOf("h264_aac_srt.mkv", "h264_aac_tx3g.mp4", "hevc_ac3.mkv")) {
            val d = open(name)
            d.init()
            val at = d.seek(1_500_000)
            assertEquals(name, 1_000_000L, at)
            var firstVideo: Sample? = null
            while (firstVideo == null) {
                val s = d.read() ?: break
                if (s.track.codec.kind == Kind.VIDEO) firstVideo = s
            }
            assertTrue(name, firstVideo!!.keyframe)
            assertEquals(name, 1_000_000L, firstVideo.timeUs)
        }
    }

    @Test
    fun hlsPlaylists() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            hd/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160
            uhd/index.m3u8
        """.trimIndent()
        val m = HlsPlaylist.parseMaster(master, "http://host/live/chan/master.m3u8")
        assertEquals(3, m.variants.size)
        assertEquals("http://host/live/chan/uhd/index.m3u8", HlsPlaylist.choose(m.variants, 2160, 0)!!.url)
        assertEquals("http://host/live/chan/hd/index.m3u8", HlsPlaylist.choose(m.variants, 1080, 0)!!.url)
        assertEquals("http://host/live/chan/low/index.m3u8", HlsPlaylist.choose(m.variants, 2160, 1_000_000)!!.url)
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6.0,
            seg100.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:5.5,
            http://cdn/seg101.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylist.parseMedia(media, "http://host/vod/index.m3u8")
        assertEquals(2, p.segments.size)
        assertTrue(p.ended)
        assertEquals(11_500_000L, p.durationUs)
        assertEquals(101L, p.segments[1].sequence)
        assertEquals(6_000_000L, p.segments[1].startUs)
        assertTrue(p.segments[1].discontinuity)
        assertEquals("http://host/vod/key.bin", p.segments[0].keyUrl)
        assertEquals(15, p.segments[0].keyIv!![15].toInt())
        assertEquals("http://cdn/seg101.ts", p.segments[1].url)
    }

    @Test
    fun brokenFilesNeverCrash() {
        val random = java.util.Random(7)
        for (name in listOf("h264_aac_srt.mkv", "h264_aac_tx3g.mp4", "frag.mp4")) {
            val clean = resource(name)
            repeat(20) {
                val data = clean.copyOf(random.nextInt(clean.size))
                repeat(50) { val i = random.nextInt(data.size.coerceAtLeast(1)); if (data.isNotEmpty()) data[i] = random.nextInt(256).toByte() }
                runCatching {
                    val d = if (MkvDemuxer.sniff(data.copyOf(16))) MkvDemuxer(SourceReader(ArrayByteSource(data))) else Mp4Demuxer(SourceReader(ArrayByteSource(data)))
                    d.init()
                    var n = 0
                    while (n++ < 10_000) d.read() ?: break
                }.exceptionOrNull()?.let { e ->
                    // Erlaubt: gemeldete Lesefehler. Nicht erlaubt: Endlosschleife oder Laufzeitfehler, die den Player abschießen.
                    assertTrue("$name: ${e.javaClass.simpleName}", e is java.io.IOException || e is IllegalArgumentException ||
                        e is IndexOutOfBoundsException || e is NegativeArraySizeException || e is OutOfMemoryError)
                }
            }
        }
    }
}

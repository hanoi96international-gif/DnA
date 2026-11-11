package de.ayutv.data.net

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Umverpacken für den Fernseher: alles außer dem nativen Teil (der wird in der CI auf dem Rechner getestet). */
class RemuxTest {
    private val file = ByteArray(3_000_000) { (it * 7 % 253).toByte() }
    private val ranges = ArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/film.mkv") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges += range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            if (start >= file.size) {
                ex.responseHeaders.add("Content-Range", "bytes */${file.size}")
                ex.sendResponseHeaders(416, -1)
                ex.close()
                return@createContext
            }
            ex.responseHeaders.add("Content-Range", "bytes $start-${file.size - 1}/${file.size}")
            ex.sendResponseHeaders(206, file.size - start)
            runCatching { ex.responseBody.use { it.write(file, start.toInt(), (file.size - start).toInt()) } }
        }
        // Anbieter ohne Range: immer die ganze Datei.
        createContext("/plain.mkv") { ex ->
            ex.sendResponseHeaders(200, file.size.toLong())
            runCatching { ex.responseBody.use { it.write(file) } }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = OkHttpClient()

    @After
    fun stop() = server.stop(0)

    private fun Remux.Source.readFully(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65_536)
        while (out.size() < n) {
            val got = read(buf, minOf(buf.size, n - out.size()))
            if (got <= 0) break
            out.write(buf, 0, got)
        }
        return out.toByteArray()
    }

    @Test
    fun readsSeeksAndKnowsSize() {
        val source = HttpRangeSource(client, "$base/film.mkv", "DnA")
        assertArrayEquals(file.copyOfRange(0, 100_000), source.readFully(100_000))
        assertEquals(file.size.toLong(), source.seek(0, Remux.SEEK_SIZE))
        // Kleiner Sprung nach vorn: überlesen, keine neue Anfrage.
        assertEquals(300_000L, source.seek(300_000, 0))
        assertArrayEquals(file.copyOfRange(300_000, 310_000), source.readFully(10_000))
        assertEquals(1, synchronized(ranges) { ranges.size })
        // Zurück und ans Ende: neue Anfrage mit Range.
        assertEquals(1_000L, source.seek(1_000, 0))
        assertArrayEquals(file.copyOfRange(1_000, 2_000), source.readFully(1_000))
        assertEquals(file.size - 10L, source.seek(-10, 2))
        assertArrayEquals(file.copyOfRange(file.size - 10, file.size), source.readFully(100))
        assertEquals(0, source.read(ByteArray(10), 10))
        assertTrue(synchronized(ranges) { ranges.contains("bytes=1000-") })
        // AVSEEK_FORCE (0x20000) zusammen mit SEEK_SET.
        assertEquals(5L, source.seek(5, 0x20000))
        assertArrayEquals(file.copyOfRange(5, 15), source.readFully(10))
        source.close()
        assertEquals(-1, source.read(ByteArray(10), 10))
    }

    @Test
    fun providerWithoutRangeStillWorksForShortSkips() {
        val source = HttpRangeSource(client, "$base/plain.mkv", null)
        assertArrayEquals(file.copyOfRange(0, 1_000), source.readFully(1_000))
        source.seek(2_000_000, 0)
        assertArrayEquals(file.copyOfRange(2_000_000, 2_001_000), source.readFully(1_000))
        source.seek(500, 0)
        assertArrayEquals(file.copyOfRange(500, 600), source.readFully(100))
        source.close()
    }

    @Test
    fun parsesDescription() {
        val d = Remux.parseDescription(
            "video 0 hevc 3840x2160\naudio 1 dts 6 ger DTS-HD MA 5.1\naudio 2 aac 2 eng \naudio 3 ac3 6 - \ndefault 0 1\n",
        )
        assertEquals(Remux.Video("hevc", 3840, 2160), d.video)
        assertEquals(3, d.audio.size)
        assertEquals(Remux.Track(1, "dts", 6, "ger", "DTS-HD MA 5.1"), d.audio[0])
        assertEquals(Remux.Track(2, "aac", 2, "eng", null), d.audio[1])
        assertEquals(null, d.audio[2].language)
        assertEquals(1, d.defaultAudio)
    }

    @Test
    fun embeddedSubtitleTracks() {
        val d = Remux.parseDescription(
            "video 0 h264 1920x1080\naudio 1 aac 2 ger \nsubtitle 2 subrip ger 0 \nsubtitle 3 ass eng 1 Forced\n" +
                "subtitle 4 hdmv_pgs_subtitle ger 0 PGS\ndefault 0 1\n",
        )
        assertEquals(3, d.subtitles.size)
        assertEquals(Remux.SubTrack(2, "subrip", "ger", false, null), d.subtitles[0])
        assertTrue(d.subtitles[1].forced)
        // Bild-Untertitel (PGS) kann der Fernseher als WebVTT nicht bekommen.
        assertEquals(listOf(2, 3), d.subtitles.filter { it.text }.map { it.index })
        assertTrue(d.subtitles[0].label().startsWith("Deutsch"))
        assertTrue(d.subtitles[1].label().contains("erzwungen"))
    }

    @Test
    fun subtitleTextForTv() {
        assertEquals("Hallo\nWelt", Remux.cueText("ass", "3,0,Default,,0,0,0,,{\\i1}Hallo{\\i0}\\NWelt".toByteArray()))
        assertEquals("Komma, im Text", Remux.cueText("ass", "1,0,Default,,0,0,0,,Komma, im Text".toByteArray()))
        assertEquals("<i>kursiv</i> und rot", Remux.cueText("subrip", "<i>kursiv</i> und <font color=\"red\">rot</font>\r\n".toByteArray()))
        val movText = byteArrayOf(0, 5) + "Hallo".toByteArray() + byteArrayOf(1, 2, 3)
        assertEquals("Hallo", Remux.cueText("mov_text", movText))
    }

    @Test
    fun languagesAndLabels() {
        assertEquals("de", Remux.isoLanguage("ger"))
        assertEquals("de", Remux.isoLanguage("deu"))
        assertEquals("en", Remux.isoLanguage("eng"))
        assertEquals("fr", Remux.isoLanguage("fre"))
        assertEquals("de", Remux.isoLanguage("de-DE"))
        val label = Remux.Track(1, "dts", 6, "ger", null).label()
        assertTrue(label, label.startsWith("Deutsch") && label.contains("5.1") && label.contains("DTS"))
        assertTrue(Remux.Track(4, "truehd", 8, null, "Kommentar").label().let { it.contains("Spur 5") && it.contains("7.1") && it.contains("Kommentar") })
        val tracks = listOf(Remux.Track(1, "aac", 2, "eng", null), Remux.Track(2, "dts", 6, "ger", null))
        assertEquals(2, RemuxSession.pickAudio(tracks, listOf("de")))
        assertEquals(1, RemuxSession.pickAudio(tracks, listOf("fr", "en")))
        assertEquals(-1, RemuxSession.pickAudio(tracks, listOf("it")))
    }

    @Test
    fun masterListWithResolutionAndSubtitles() {
        val subs = DlnaRelay.HlsSubtitles({ emptyList() }, "de", "Deutsch", 100)
        val small = DlnaRelay.remuxMaster("http://h:1", Remux.Video("h264", 1280, 720), subs)
        assertTrue(small, small.contains("RESOLUTION=1920x1080,SUBTITLES=\"subs\""))
        assertTrue(small, small.contains("TYPE=SUBTITLES") && small.trim().endsWith("http://h:1/remux.m3u8"))
        val uhd = DlnaRelay.remuxMaster("http://h:1", Remux.Video("hevc", 3840, 2160), null)
        assertTrue(uhd, uhd.contains("RESOLUTION=3840x2160"))
        assertFalse(uhd, uhd.contains("SUBTITLES"))
    }

    @Test
    fun vttWithTimestampMap() {
        val text = DlnaRelay.vtt(listOf(Triple(1_000L, 2_500L, "Hallo")), 900_000)
        assertTrue(text, text.startsWith("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.500\nHallo"))
        assertTrue(DlnaRelay.vtt(emptyList()).startsWith("WEBVTT\n\n"))
    }
}

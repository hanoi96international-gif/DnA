package de.ayutv.data.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder

class DlnaRelayHlsTest {
    private val base = "http://192.168.1.20:40000"
    private fun seg(url: String) = "$base/seg?u=" + URLEncoder.encode(url, "UTF-8")

    @Test
    fun segmentsAndKeysPointToThePhone() {
        val list = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            /hls/abc/100.ts
            #EXTINF:6.0,
            101.ts
            #EXTINF:6.0,
            http://cdn.example.com/x/102.ts
        """.trimIndent()
        val out = DlnaRelay.rewritePlaylist(list, "http://provider.tv:8080/hls/abc/index.m3u8?token=1", base).lines()
        assertEquals("#EXTM3U", out[0])
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"${seg("http://provider.tv:8080/hls/abc/key.bin")}\"", out[2])
        assertEquals(seg("http://provider.tv:8080/hls/abc/100.ts"), out[4])
        assertEquals(seg("http://provider.tv:8080/hls/abc/101.ts"), out[6])
        assertEquals(seg("http://cdn.example.com/x/102.ts"), out[8])
    }

    @Test
    fun variantPlaylistsToo() {
        val list = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000\nhigh/index.m3u8\n"
        val out = DlnaRelay.rewritePlaylist(list, "http://p.tv/live/1.m3u8", base).lines()
        assertEquals("#EXT-X-STREAM-INF:BANDWIDTH=5000000", out[1])
        assertEquals(seg("http://p.tv/live/high/index.m3u8"), out[2])
    }

    @Test
    fun onlyTheBestVariantGoesToTheTv() {
        val list = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            mid.m3u8
        """.trimIndent()
        val out = DlnaRelay.bestVariantOnly(list).lines().filter { it.isNotBlank() }
        assertEquals(3, out.size)
        assertEquals("high.m3u8", out[2])
    }

    @Test
    fun mediaPlaylistStaysAsIs() {
        val list = "#EXTM3U\n#EXTINF:6,\n1.ts\n"
        assertEquals(list, DlnaRelay.bestVariantOnly(list))
    }

    @Test
    fun subtitleTrackWrapsMediaPlaylist() {
        val subs = DlnaRelay.HlsSubtitles({ emptyList() }, "de", "Deutsch", 5400)
        val out = DlnaRelay.withSubtitles("#EXTM3U\n#EXTINF:6,\n$base/seg?u=x\n", base, "http://p.tv/movie/u/p/1.m3u8", subs)
        assertEquals(true, out.contains("TYPE=SUBTITLES"))
        assertEquals(true, out.contains("URI=\"$base/subs.m3u8\""))
        assertEquals(true, out.contains(seg("http://p.tv/movie/u/p/1.m3u8")))
        val subsList = DlnaRelay.subtitlePlaylist(base, 5430)
        assertEquals(91, subsList.lines().count { it.startsWith("#EXTINF:") })
        assertEquals(true, subsList.contains("$base/subs/90.vtt"))
        assertEquals(true, subsList.contains("#EXTINF:30.0,"))
    }

    @Test
    fun vttTimes() {
        val vtt = DlnaRelay.vtt(listOf(Triple(3_723_456L, 3_725_000L, "Hallo")))
        assertEquals(true, vtt.contains("01:02:03.456 --> 01:02:05.000\nHallo"))
    }

    @Test
    fun vodHlsAddress() {
        assertEquals("http://p.tv:8080/movie/u/p/123.m3u8", DlnaCast.vodHlsUrl("http://p.tv:8080/movie/u/p/123.mkv"))
        assertEquals("http://p.tv/series/u/p/9.m3u8", DlnaCast.vodHlsUrl("http://p.tv/series/u/p/9.mp4"))
        assertEquals(null, DlnaCast.vodHlsUrl("http://p.tv/live/u/p/9.ts"))
    }

    @Test
    fun resumeDropsEarlierSegments() {
        val list = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10,\na.ts\n#EXTINF:10,\nb.ts\n#EXTINF:10,\nc.ts\n#EXT-X-ENDLIST"
        val (out, skipped) = DlnaRelay.startAt(list, 15.0)!!
        assertEquals(10.0, skipped, 0.001)
        assertEquals(false, out.contains("a.ts"))
        assertEquals(true, out.contains("b.ts"))
        assertEquals(true, out.contains("#EXT-X-MEDIA-SEQUENCE:1"))
        assertEquals(null, DlnaRelay.startAt(list.replace("#EXT-X-ENDLIST", ""), 15.0))
    }

    @Test
    fun mediaPlaylistGetsFullHdMaster() {
        val out = DlnaRelay.masterFor("#EXTM3U\n#EXTINF:6,\nx\n", base, "http://p.tv/live/u/p/1.m3u8", null)
        assertEquals(true, out.contains("RESOLUTION=1920x1080"))
        assertEquals(true, out.contains(seg("http://p.tv/live/u/p/1.m3u8")))
        val master = DlnaRelay.masterFor("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n", base, "s", null)
        assertEquals(true, master.contains("BANDWIDTH=1,RESOLUTION=1920x1080"))
    }
}

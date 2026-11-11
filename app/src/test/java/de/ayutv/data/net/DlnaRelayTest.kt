package de.ayutv.data.net

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Weiterleitung zum Fernseher gegen einen kleinen Anbieter-Server im Test. */
class DlnaRelayTest {
    private val provider = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base get() = "http://127.0.0.1:${provider.address.port}"
    private val client = OkHttpClient()
    private var seenAgent: String? = null
    private val movie = ByteArray(100_000) { (it % 251).toByte() }

    init {
        // Live-Strom (TS): verlangt die Kennung der App, sonst 403 (wie manche Anbieter).
        provider.createContext("/live/u/p/381.ts") { ex ->
            seenAgent = ex.requestHeaders.getFirst("User-Agent")
            if (seenAgent != "DnA-Agent") { ex.sendResponseHeaders(403, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("Content-Type", "video/mp2t")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(ByteArray(4_000) { 0x47 }) }
        }
        // Film mit Byte-Bereichen.
        provider.createContext("/movie/u/p/9.mp4") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${movie.size - 1}/${movie.size}")
                ex.sendResponseHeaders(206, (movie.size - from).toLong())
                ex.responseBody.use { it.write(movie, from, movie.size - from) }
            } else {
                ex.sendResponseHeaders(200, movie.size.toLong())
                ex.responseBody.use { it.write(movie) }
            }
        }
        // HLS: Hauptliste → Fassung → drei Segmente, abgeschlossen.
        provider.createContext("/hls/master.m3u8") { ex ->
            val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000\nhigh.m3u8\n"
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(text.toByteArray()) }
        }
        provider.createContext("/hls/high.m3u8") { ex ->
            val text = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg1.ts\n#EXTINF:2,\nseg2.ts\n#EXTINF:2,\nseg3.ts\n#EXT-X-ENDLIST\n"
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(text.toByteArray()) }
        }
        provider.createContext("/hls/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            val n = name.removePrefix("seg").removeSuffix(".ts").toIntOrNull()
            if (n == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(ByteArray(1_000) { n.toByte() }) }
        }
        provider.start()
    }

    @After
    fun stop() = provider.stop(0)

    @Test
    fun liveStreamWithAppAgent() {
        DlnaRelay(client, "$base/live/u/p/381.ts", "DnA-Agent", live = true, InetAddress.getLoopbackAddress()).use { relay ->
            assertTrue(relay.url.endsWith("/stream.ts"))
            client.newCall(Request.Builder().url(relay.url).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("video/mp2t", r.header("Content-Type"))
                assertEquals("Streaming", r.header("transferMode.dlna.org"))
                assertTrue(r.header("contentFeatures.dlna.org")!!.startsWith("DLNA.ORG_OP=00"))
                assertEquals(4_000, r.body.bytes().size)
            }
            assertEquals("DnA-Agent", seenAgent)
        }
    }

    @Test
    fun movieWithRanges() {
        DlnaRelay(client, "$base/movie/u/p/9.mp4", null, live = false, InetAddress.getLoopbackAddress()).use { relay ->
            assertEquals("video/mp4", relay.mime)
            client.newCall(Request.Builder().url(relay.url).header("Range", "bytes=1000-").build()).execute().use { r ->
                assertEquals(206, r.code)
                assertEquals("bytes 1000-99999/100000", r.header("Content-Range"))
                assertArrayEquals(movie.copyOfRange(1000, movie.size), r.body.bytes())
            }
            // HEAD ohne Abruf beim Anbieter.
            client.newCall(Request.Builder().url(relay.url).head().build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals("bytes", r.header("Accept-Ranges"))
            }
        }
    }

    @Test
    fun hlsStitchedToTs() {
        DlnaRelay(client, "$base/hls/master.m3u8", null, live = true, InetAddress.getLoopbackAddress()).use { relay ->
            assertEquals("video/mp2t", relay.mime)
            client.newCall(Request.Builder().url(relay.url).build()).execute().use { r ->
                val bytes = r.body.bytes()
                assertEquals(3_000, bytes.size)
                assertEquals(1, bytes[0].toInt()); assertEquals(3, bytes[2_999].toInt())
            }
        }
    }

    @Test
    fun providerRefusesShowsReason() {
        DlnaRelay(client, "$base/live/u/p/381.ts", "Fremd", live = true, InetAddress.getLoopbackAddress()).use { relay ->
            client.newCall(Request.Builder().url(relay.url).build()).execute().use { r -> assertEquals(502, r.code) }
            assertEquals("Anbieter antwortet mit 403", relay.lastError)
        }
    }

    @Test
    fun hlsParsing() {
        val enc = DlnaRelay.Hls.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n", "http://x/p.m3u8")
        assertTrue(enc.unsupported!!.contains("Verschlüsselt"))
        val ok = DlnaRelay.Hls.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n/a/b.ts\n", "http://x/p/q.m3u8")
        assertNull(ok.unsupported)
        assertEquals(listOf("http://x/a/b.ts"), ok.segments)
        assertEquals(4, ok.targetSeconds)
        assertTrue(ok.live)
    }
}

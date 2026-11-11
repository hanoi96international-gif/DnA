package de.ayutv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * LG webOS live über die eigene Liste: Das Handy holt das erste Stück, bevor der Fernseher geöffnet wird. Der Fernseher
 * bekommt so sofort eine Liste mit einem spielbaren Teil (vorher wartete er bis 15 s und bekam über den Proxy oft eine leere).
 */
class DlnaLiveWindowTest {
    /** Anbieter, der jedes Stück langsam liefert (wie über den Proxy der App): 300 KB in [segmentMs]. */
    private fun provider(segmentMs: Long): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            var seq = 100
            val started = System.currentTimeMillis()
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine() ?: return@use
                        val path = line.split(" ")[1]
                        val out = sock.getOutputStream()
                        if (path.endsWith(".m3u8")) {
                            val now = 100 + ((System.currentTimeMillis() - started) / 6000).toInt()
                            val body = buildString {
                                append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:${now - 2}\n")
                                for (k in now - 2..now) append("#EXTINF:6.0,\n/seg/$k.ts\n")
                            }.toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        } else {
                            val size = 300_000
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                            val chunk = size / 20
                            repeat(20) { out.write(ByteArray(chunk)); out.flush(); Thread.sleep(segmentMs / 20) }
                        }
                        out.flush()
                    }
                }
            }
        }
        return server
    }

    @Test
    fun windowIsReadyBeforeTvIsOpenedEvenWithSlowFirstSegment() {
        val server = provider(segmentMs = 3_000)
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val base = "http://127.0.0.1:${server.localPort}"
        DlnaRelay(client, "$base/live/index.m3u8", null, live = true, InetAddress.getLoopbackAddress(), hlsToTv = true).use { relay ->
            assertTrue(relay.liveWindow)
            val t0 = System.currentTimeMillis()
            relay.prepareWindow()
            while (!relay.windowReady && System.currentTimeMillis() - t0 < 45_000) Thread.sleep(200)
            val waited = System.currentTimeMillis() - t0
            assertTrue("nicht bereit", relay.windowReady)
            // Jetzt erst „öffnet“ der Fernseher: Liste kommt sofort und enthält Teile.
            val t1 = System.currentTimeMillis()
            val list = client.newCall(Request.Builder().url(relay.url.replace("stream.m3u8", "live.m3u8")).build()).execute().use { it.body.string() }
            val listMs = System.currentTimeMillis() - t1
            assertTrue(list.contains("/part/"))
            assertTrue("Liste kam erst nach $listMs ms", listMs < 2_000)
            // Das Stück selbst
            val part = list.lines().first { it.contains("/part/") }
            val bytes = client.newCall(Request.Builder().url(part).build()).execute().use { it.body.bytes() }
            assertTrue(bytes.size == 300_000)
        }
        server.close()
    }
}

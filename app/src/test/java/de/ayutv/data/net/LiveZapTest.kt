package de.ayutv.data.net

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/** LG-Live über die eigene Liste: Umschalten im laufenden Strom (schnell, lückenlos) und Aufholen nach Hängern. */
class LiveZapTest {
    private fun segment(index: Long, marker: Int, frames: Int = 150, gop: Int = 25, fps: Int = 25): ByteArray {
        val out = ByteArrayOutputStream()
        fun packet(pid: Int, start: Boolean, cc: Int, adaptation: ByteArray?, payload: ByteArray) {
            val p = ByteArray(188) { 0xff.toByte() }
            p[0] = 0x47; p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte(); p[2] = (pid and 0xff).toByte()
            var o = 4
            if (adaptation != null) { p[3] = (0x30 or (cc and 0xf)).toByte(); p[4] = adaptation.size.toByte(); adaptation.copyInto(p, 5); o = 5 + adaptation.size }
            else p[3] = (0x10 or (cc and 0xf)).toByte()
            payload.copyInto(p, o, 0, minOf(payload.size, 188 - o)); out.write(p)
        }
        val pat = byteArrayOf(0, 0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0x00, 0, 0, 0, 0)
        val pmt = byteArrayOf(0, 0x02, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0,
            0x1b, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0, 0, 0, 0, 0)
        packet(0, true, 0, null, pat); packet(0x1000, true, 0, null, pmt)
        var cc = 0
        for (i in 0 until frames) {
            val pts = 900_000L + (index * frames + i) * 90_000L / fps
            val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
                (0x21 or ((pts shr 29) and 0x0e).toInt()).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xfe) or 1).toByte(),
                (pts shr 7).toByte(), (((pts shl 1) and 0xfe) or 1).toByte(), 0, 0, 0, 1, 0x65)
            packet(0x100, true, cc++, if (i % gop == 0) byteArrayOf(0x40) else null, pes)
            packet(0x100, false, cc++, null, ByteArray(100) { marker.toByte() })
        }
        return out.toByteArray()
    }

    /** Anbieter: zwei Sender, 6-s-Stücke, Liste mit den letzten 3; Stücke langsam (wie über den Proxy: 3 s je Stück). */
    private fun provider(): Pair<HttpServer, String> {
        val t0 = System.currentTimeMillis() - 30_000
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val ch = path.substring(1, 2)
            val marker = if (ch == "a") 0xA1 else 0xB2
            if (path.endsWith(".m3u8")) {
                val cur = (System.currentTimeMillis() - t0) / 6_000 - 1
                val text = buildString {
                    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:${cur - 2}\n")
                    for (n in cur - 2..cur) append("#EXTINF:6.000,\n/$ch/$n.ts\n")
                }.toByteArray()
                ex.sendResponseHeaders(200, text.size.toLong()); ex.responseBody.use { it.write(text) }
            } else {
                val n = path.substringAfterLast('/').removeSuffix(".ts").toLong()
                val data = segment(n, marker)
                ex.sendResponseHeaders(200, data.size.toLong())
                runCatching {
                    ex.responseBody.use { out ->
                        var o = 0
                        while (o < data.size) { val k = minOf(1880, data.size - o); out.write(data, o, k); out.flush(); o += k; Thread.sleep(3_000L * k / data.size) }
                    }
                }
            }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun channelOf(part: ByteArray): Char? {
        val a = part.count { it == 0xA1.toByte() }; val b = part.count { it == 0xB2.toByte() }
        return when { a > b && a > 50 -> 'A'; b > a && b > 50 -> 'B'; else -> null }
    }

    @Test
    fun zapInTheRunningStreamIsFastAndContinuous() {
        val (server, base) = provider()
        val http = OkHttpClient()
        val relay = DlnaRelay(http, "$base/a/live.m3u8", null, true, InetAddress.getLoopbackAddress(), hlsToTv = true)
        try {
            assertTrue(relay.liveWindow)
            relay.prepareWindow()
            val t0 = System.currentTimeMillis()
            while (!relay.windowReady && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(50)
            val firstMs = System.currentTimeMillis() - t0
            val listUrl = relay.url.substringBeforeLast('/') + "/live.m3u8"
            // Fernseher: Liste neu laden, nächsten Teil in Reihenfolge holen, in Echtzeit „abspielen“.
            var next = -1L
            val played = ArrayList<Pair<Long, Char?>>()
            var switchedAt = 0L
            var firstBAt = 0L
            var stallMs = 0L
            val end = System.currentTimeMillis() + 22_000
            while (System.currentTimeMillis() < end) {
                val list = http.newCall(Request.Builder().url(listUrl).build()).execute().use { it.body.string() }
                val seqStart = Regex("MEDIA-SEQUENCE:(\\d+)").find(list)!!.groupValues[1].toLong()
                val parts = list.lines().filter { it.contains("/part/") }.map { it.substringAfter("/part/").substringBefore('.').toLong() }
                if (next < 0) next = parts.getOrElse(maxOf(0, parts.size - 3)) { seqStart }
                val durations = Regex("#EXTINF:([0-9.]+)").findAll(list).map { it.groupValues[1].toDouble() }.toList()
                if (next !in parts) {
                    // Nicht da: kurz warten (Fernseher wartet auf den nächsten Teil).
                    assertTrue("Teil $next fehlt, Liste ab $seqStart: ${parts}", parts.isEmpty() || next >= parts.first() && next <= parts.last() + 1)
                    continue
                }
                val i = parts.indexOf(next)
                val data = http.newCall(Request.Builder().url(relay.url.substringBeforeLast('/') + "/part/$next.ts").build()).execute().use { it.body.bytes() }
                val ch = channelOf(data)
                played += next to ch
                if (switchedAt > 0 && ch == 'B' && firstBAt == 0L) firstBAt = System.currentTimeMillis()
                next++
                if (switchedAt == 0L && played.size >= 4) {
                    switchedAt = System.currentTimeMillis()
                    relay.switchTo("$base/b/live.m3u8")
                }
                Thread.sleep((durations[i] * 1000).toLong())
            }
            assertTrue("erster Teil zu spät: $firstMs", firstMs < 2_500)
            assertTrue("B kam nicht", firstBAt > 0)
            assertTrue("Umschalten zu langsam: ${firstBAt - switchedAt}", firstBAt - switchedAt < 3_500)
            // Nach dem ersten B nur noch B, Nummern lückenlos.
            val afterB = played.dropWhile { it.second != 'B' }
            assertTrue(afterB.all { it.second == 'B' })
            played.zipWithNext().forEach { (x, y) -> assertTrue("Lücke ${x.first}->${y.first}", y.first == x.first + 1) }
        } finally {
            relay.close(); server.stop(0)
        }
    }

    @Test
    fun tvThatFellBehindCatchesUp() {
        val (server, base) = provider()
        val http = OkHttpClient()
        val relay = DlnaRelay(http, "$base/a/live.m3u8", null, true, InetAddress.getLoopbackAddress(), hlsToTv = true)
        try {
            relay.prepareWindow()
            while (!relay.windowReady) Thread.sleep(50)
            val root = relay.url.substringBeforeLast('/')
            fun list() = http.newCall(Request.Builder().url("$root/live.m3u8").build()).execute().use { it.body.string() }
                .lines().filter { it.contains("/part/") }.map { it.substringAfter("/part/").substringBefore('.').toLong() }
            fun get(n: Long) = http.newCall(Request.Builder().url("$root/part/$n.ts").build()).execute().use { it.code }
            var next = list().first()
            repeat(3) { assertTrue(get(next) == 200); next++; Thread.sleep(1_000) }
            Thread.sleep(22_000)  // Fernseher hängt
            val before = list()
            assertTrue(get(next) == 200); next++
            val after = list()
            val ahead = after.count { it >= next }
            assertTrue("noch $ahead Teile hinterher", ahead in 1..6)
            assertTrue("nächster Teil fehlt", next in after)
        } finally {
            relay.close(); server.stop(0)
        }
    }
}

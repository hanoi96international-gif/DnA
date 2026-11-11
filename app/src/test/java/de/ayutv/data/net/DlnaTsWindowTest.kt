package de.ayutv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * LG webOS live mit Sendern ohne HLS: Der durchgehende TS-Strom wird auf dem Handy zur eigenen Liste (der LG-Mediaplayer
 * holt einen endlosen TS-Strom nicht ab). Umschalten im laufenden Mediaplayer, mit Bruchstelle.
 */
class DlnaTsWindowTest {
    /** TS-Strom: PAT, PMT (H.264 auf PID 0x100), je Bild ein PES mit Zeitstempel, Schlüsselbild alle [gop] Bilder. */
    private fun stream(frames: Int, gop: Int, fps: Int = 25, firstFrame: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun packet(pid: Int, start: Boolean, cc: Int, adaptation: ByteArray?, payload: ByteArray) {
            val p = ByteArray(188) { 0xff.toByte() }
            p[0] = 0x47
            p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xff).toByte()
            var o = 4
            if (adaptation != null) {
                p[3] = (0x30 or (cc and 0xf)).toByte()
                p[4] = adaptation.size.toByte()
                adaptation.copyInto(p, 5)
                o = 5 + adaptation.size
            } else p[3] = (0x10 or (cc and 0xf)).toByte()
            payload.copyInto(p, o, 0, minOf(payload.size, 188 - o))
            out.write(p)
        }
        val pat = byteArrayOf(0, 0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0x00, 0, 0, 0, 0)
        val pmt = byteArrayOf(0, 0x02, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0,
            0x1b, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0, 0, 0, 0, 0)
        packet(0, true, 0, null, pat)
        packet(0x1000, true, 0, null, pmt)
        var cc = 0
        for (f in firstFrame until firstFrame + frames) {
            val pts = 900_000L + f * 90_000L / fps
            val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
                (0x21 or ((pts shr 29) and 0x0e).toInt()).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xfe) or 1).toByte(),
                (pts shr 7).toByte(), (((pts shl 1) and 0xfe) or 1).toByte(), 0, 0, 0, 1, 0x65)
            packet(0x100, true, cc++, if (f % gop == 0) byteArrayOf(0x40) else null, pes)
            packet(0x100, false, cc++, null, ByteArray(100))
        }
        return out.toByteArray()
    }

    /** Anbieter: liefert unter jedem Pfad einen endlosen TS-Strom in Echtzeit (je 1 s ein Happen). */
    private fun provider(): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            sock.getInputStream().bufferedReader().readLine() ?: return@use
                            val out = sock.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            var second = 0
                            while (!server.isClosed) {
                                out.write(stream(frames = 25, gop = 25, firstFrame = second * 25))
                                out.flush()
                                second++
                                Thread.sleep(1_000)
                            }
                        }
                    }
                }
            }
        }
        return server
    }

    private fun get(client: OkHttpClient, url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.bytes() }

    @Test
    fun continuousTsBecomesOwnListAndSwitchesWithDiscontinuity() {
        val server = provider()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val base = "http://127.0.0.1:${server.localPort}"
        DlnaRelay(client, "$base/live/u/p/1.ts", null, live = true, InetAddress.getLoopbackAddress(), hlsToTv = false, liveTsWindow = true).use { relay ->
            assertTrue(relay.liveWindow)
            assertEquals("application/x-mpegURL", relay.mime)
            assertTrue(relay.url.endsWith("/stream.m3u8"))
            relay.prepareWindow()
            val t0 = System.currentTimeMillis()
            while (!relay.windowReady && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(100)
            assertTrue("erster Teil nach ${System.currentTimeMillis() - t0} ms nicht da (${relay.summary()})", relay.windowReady)
            val master = String(get(client, relay.url))
            assertTrue(master, master.contains("/live.m3u8"))
            val list = String(get(client, relay.url.replace("stream.m3u8", "live.m3u8")))
            val part = list.lines().first { it.contains("/part/") }
            val bytes = get(client, part)
            assertTrue(bytes.size >= 188 * 10)
            assertEquals(0x47.toByte(), bytes[0])
            // Beginnt mit der PAT: Der Fernseher kann mit diesem Teil anfangen.
            assertEquals(0, ((bytes[1].toInt() and 0x1f) shl 8) or (bytes[2].toInt() and 0xff))
            // Der Server zählt erst nach dem Senden: kurz warten (sonst Wettlauf mit dem Abruf, auf der CI gesehen).
            val counted = System.currentTimeMillis()
            while (relay.bytes.get() == 0L && System.currentTimeMillis() - counted < 2_000) Thread.sleep(20)
            assertTrue("Weiterleitung hat nichts gezählt", relay.bytes.get() > 0)

            // Umschalten: neuer Strom in dieselbe Liste, mit Bruchstelle.
            relay.switchTo("$base/live/u/p/2.ts")
            val t1 = System.currentTimeMillis()
            var after = ""
            while (System.currentTimeMillis() - t1 < 8_000) {
                after = String(get(client, relay.url.replace("stream.m3u8", "live.m3u8")))
                if (after.contains("#EXT-X-DISCONTINUITY\n")) break
                Thread.sleep(200)
            }
            assertTrue("keine Bruchstelle nach dem Umschalten:\n$after", after.contains("#EXT-X-DISCONTINUITY\n"))
            // Die App erkennt, ob der Fernseher dem Umschalten folgt: erst wenn er ein Stück nach der Bruchstelle holt.
            assertTrue(relay.switchPartReady)
            assertTrue("vor dem Abholen schon „gefolgt“", !relay.switchFollowed)
            val newPart = after.lines().dropWhile { it != "#EXT-X-DISCONTINUITY" }.first { it.contains("/part/") }
            get(client, newPart)
            val fetched = System.currentTimeMillis()
            while (!relay.switchFollowed && System.currentTimeMillis() - fetched < 2_000) Thread.sleep(20)
            assertTrue("Abholen nach dem Umschalten nicht erkannt", relay.switchFollowed)
        }
        server.close()
    }

    @Test
    fun progressiveOnEndlessStreamKeepsPartsIdenticalWhileCompacting() {
        // Lang genug, dass vorn abgeschnitten wird (mehr als 10 000 Pakete).
        val data = stream(frames = 8_000, gop = 25)
        val whole = TsSplitter.split(data, 320.0)
        val splitter = TsSplitter.Progressive(320.0)
        val parts = ArrayList<TsSplitter.Part>()
        var offset = 0
        while (offset < data.size) {
            val n = minOf(7_777, data.size - offset)
            parts += splitter.feed(data, offset, n)
            offset += n
        }
        parts += splitter.finish()
        assertEquals(whole.size, parts.size)
        for (k in whole.indices) assertArrayEquals("Teil $k", whole[k].data, parts[k].data)
    }
}

package de.ayutv.desktop

import de.ayutv.data.parse.Hid

import de.ayutv.desktop.remote.CastTarget
import de.ayutv.desktop.remote.TvCast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCastTest {
    @Test
    fun fireTvAddressGetsPort() {
        assertEquals("http://192.168.178.40:8096", TvCast.normalize("192.168.178.40"))
        assertEquals("http://192.168.178.40:8096", TvCast.normalize("http://192.168.178.40:8096/"))
    }

    /** Nachgebauter Fire TV: entschlüsselt mit der PIN, prüft den Auftrag „/play“ und antwortet verschlüsselt. */
    @Test
    fun playsOnFireTvWithPin() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        var received = ""
        val channel = de.ayutv.data.setup.LocalChannel.Server { "1234" }
        server.createContext("/s") { ex ->
            val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val request = runCatching { channel.open(text) }.getOrNull()
            if (request == null) {
                ex.sendResponseHeaders(403, -1); ex.close(); return@createContext
            }
            received = request.path + "\n" + request.body
            val reply = channel.reply(request, "200\nok").toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong()); ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val tv = CastTarget.FireTv("Fire TV", "http://127.0.0.1:${server.address.port}", "1234")
            val cast = TvCast(okhttp3.OkHttpClient())
            assertNull(cast.playOnFireTv(tv, live = true, url = "http://p.tv/live/u/p/1.ts", title = Hid.s("FwU4DQcmeCi3"), key = "1:c:1", positionMs = 0, userAgent = "DnA-TV/Windows"))
            assertTrue(received, received.startsWith("/play\n") && received.contains("\"url\":\"http://p.tv/live/u/p/1.ts\"") && received.contains("\"live\":true"))
            val wrong = CastTarget.FireTv("Fire TV", tv.address, "9999")
            assertTrue(cast.playOnFireTv(wrong, true, "http://x", "x", "", 0, null) != null)
        } finally {
            server.stop(0)
        }
    }
}

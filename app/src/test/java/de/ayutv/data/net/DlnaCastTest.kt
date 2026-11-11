package de.ayutv.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaCastTest {
    // Gekürzte Gerätebeschreibung eines LG webOS-Fernsehers (so liefert ihn der „LOCATION“-Link)
    private val lg = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[LG] webOS TV OLED55C3</friendlyName>
            <manufacturer>LG Electronics</manufacturer>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun readsLgDescription() {
        val r = DlnaCast.parseDescription(lg, "http://192.168.178.40:1663/")!!
        assertEquals("[LG] webOS TV OLED55C3", r.name)
        assertEquals("http://192.168.178.40:1663/upnp/control/AVTransport1", r.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", r.serviceType)
        assertEquals("http://192.168.178.40:1663/upnp/control/RenderingControl1", r.renderingUrl)
    }

    @Test
    fun deviceWithoutAvTransportIsNoRenderer() {
        assertNull(DlnaCast.parseDescription(lg.replace("AVTransport", "ConnectionManager"), "http://192.168.178.40:1663/"))
    }

    @Test
    fun ssdpLocationHeader() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.178.40:1663/\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.178.40:1663/", DlnaCast.header(answer, "LOCATION"))
    }

    @Test
    fun metadataEscapedAndTyped() {
        val meta = DlnaCast.didl("http://host/live/u/p/123.ts?token=a&b=1", "Mainz – Bayern", live = true)
        assertTrue(meta.contains("http-get:*:video/mp2t:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=017"))
        assertTrue(meta.contains("token=a&amp;b=1"))
        assertTrue(DlnaCast.didl("http://host/movie/u/p/9.mp4", "Film", live = false).contains("video/mp4"))
        assertTrue(DlnaCast.didl("http://host/x/index.m3u8", "HLS", live = true).contains("application/x-mpegURL"))
    }

    @Test
    fun liveStreamAsRawTsFirst() {
        assertEquals(
            listOf("http://p.tv:8080/live/u/p/381.ts", "http://p.tv:8080/live/u/p/381.m3u8"),
            DlnaCast.liveUrls("http://p.tv:8080/live/u/p/381.m3u8"),
        )
        assertEquals(listOf("http://x/stream.m3u8"), DlnaCast.liveUrls("http://x/stream.m3u8"))
    }

    @Test
    fun sameSubnetForRelayAddress() {
        fun ip(s: String) = java.net.InetAddress.getByName(s) as java.net.Inet4Address
        assertTrue(DlnaCast.sameSubnet(ip("192.168.178.20"), ip("192.168.178.40"), 24))
        assertTrue(!DlnaCast.sameSubnet(ip("10.8.0.2"), ip("192.168.178.40"), 24))
    }

    /** Nachgebauter Fernseher: meldet erst TRANSITIONING, dann PLAYING; Lautstärke 10. */
    @Test
    fun fakeTvPlaysAndChangesVolume() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        var polls = 0
        var volume = 10
        val actions = mutableListOf<String>()
        server.createContext("/") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").orEmpty().substringAfter('#').trim('"')
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            actions += action
            val reply = when (action) {
                "GetTransportInfo" -> "<CurrentTransportState>${if (++polls < 2) "TRANSITIONING" else "PLAYING"}</CurrentTransportState>"
                "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
                "SetVolume" -> { volume = Regex("<DesiredVolume>(\\d+)</DesiredVolume>").find(body)!!.groupValues[1].toInt(); "" }
                else -> ""
            }
            val bytes = "<s:Envelope><s:Body>$reply</s:Body></s:Envelope>".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val tv = DlnaCast.Renderer("LG", "$base/", "$base/av", "urn:schemas-upnp-org:service:AVTransport:1", "$base/rc")
            val http = okhttp3.OkHttpClient()
            assertNull(DlnaCast.play(http, tv, "http://x/live/u/p/1.ts", "Sender", live = true))
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), actions.take(3))
            assertEquals(DlnaCast.Playback.PLAYING, DlnaCast.awaitPlaying(http, tv, 8_000))
            assertEquals(12, DlnaCast.changeVolume(http, tv, 2))
            assertEquals(12, volume)
        } finally {
            server.stop(0)
        }
    }
}

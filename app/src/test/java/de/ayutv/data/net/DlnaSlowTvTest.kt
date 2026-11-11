package de.ayutv.data.net

import de.ayutv.data.parse.Hid

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG (webOS) antwortet auf „Adresse setzen“ erst, wenn er den Stream geöffnet hat. Bisher galt das nach 6 s als Fehler,
 * die App versuchte die nächste Adresse und brach den anlaufenden Stream ab („geht kurz, dann SocketTimeoutException“).
 */
class DlnaSlowTvTest {
    private val tv = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val setUris = AtomicInteger()
    private val stops = AtomicInteger()

    init {
        tv.executor = Executors.newFixedThreadPool(4)
        tv.createContext("/ctl") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").orEmpty()
            when {
                "SetAVTransportURI" in action -> { setUris.incrementAndGet(); Thread.sleep(2_500) } // antwortet zu spät
                "Stop" in action -> stops.incrementAndGet()
            }
            val body = if ("GetTransportInfo" in action) "<CurrentTransportState>TRANSITIONING</CurrentTransportState>" else "<ok/>"
            val bytes = body.toByteArray()
            runCatching { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
            ex.close()
        }
        tv.start()
    }

    @After
    fun close() {
        tv.stop(0)
        DlnaCast.startReadSeconds = 12
    }

    @Test
    fun slowTvThatIsLoadingCountsAsStarted() = runBlocking {
        DlnaCast.startReadSeconds = 1
        DlnaCast.clearTrace()
        val renderer = DlnaCast.Renderer("[LG] webOS TV", "http://127.0.0.1:${tv.address.port}/", "http://127.0.0.1:${tv.address.port}/ctl",
            "urn:schemas-upnp-org:service:AVTransport:1")
        val error = DlnaCast.play(OkHttpClient(), renderer, "http://p.tv/live/u/p/381.ts", Hid.s("FwU4DQcmeCi3"), live = true)
        assertNull("lädt schon: kein Fehler, nicht abbrechen", error)
        // Kein zweiter Versuch (der hätte den anlaufenden Stream abgebrochen), nur das Stop vor dem Start.
        assertEquals(1, setUris.get())
        assertEquals(1, stops.get())
        // Für die Fehlersuche: je Schritt die Antwort des Fernsehers.
        val steps = DlnaCast.traceSummary()
        org.junit.Assert.assertTrue(steps, steps.startsWith("Stop: ok") && "Adresse: keine Antwort nach" in steps && "Zustand: ok" in steps)
    }
}

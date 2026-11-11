package de.ayutv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AppProxyTest {
    @After
    fun reset() = AppProxy.configure(ProxyConfig(), emptySet())

    @Test
    fun hostOfUrls() {
        assertEquals("example.com", AppProxy.hostOf("http://user:pw@Example.com:8080/get.php?x=1"))
        assertEquals("line.tv", AppProxy.hostOf("line.tv:80"))
        assertEquals("::1", AppProxy.hostOf("http://[::1]:8096/play"))
        assertNull(AppProxy.hostOf(""))
        assertNull(AppProxy.hostOf(null))
    }

    @Test
    fun homeNetworkNeverProxied() {
        listOf("192.168.1.20", "10.0.0.5", "172.20.1.1", "127.0.0.1", "localhost", "firetv.local", "nas.fritz.box", "::1")
            .forEach { assertTrue(it, AppProxy.isLocal(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "1.2.3.4").forEach { assertFalse(it, AppProxy.isLocal(it)) }
        AppProxy.configure(ProxyConfig(ProxyMode.ALL, host = "proxy.example", port = 8080), emptySet())
        assertFalse(AppProxy.proxies("192.168.178.30"))
        assertTrue(AppProxy.proxies("provider.example"))
        // Der Proxy selbst wird direkt angesprochen.
        assertFalse(AppProxy.proxies("proxy.example"))
    }

    @Test
    fun selectedPlaylistsOnly() {
        AppProxy.configure(ProxyConfig(ProxyMode.SELECTED, host = "proxy.example", port = 8080, playlists = setOf(1)), setOf("TR-Line.example"))
        assertTrue(AppProxy.proxies("tr-line.example"))
        assertFalse(AppProxy.proxies("other-provider.example"))
        // Unvollständig oder aus: alles direkt.
        AppProxy.configure(ProxyConfig(ProxyMode.SELECTED, host = "", port = 8080), setOf("tr-line.example"))
        assertFalse(AppProxy.proxies("tr-line.example"))
        AppProxy.configure(ProxyConfig(ProxyMode.OFF, host = "proxy.example", port = 8080), setOf("tr-line.example"))
        assertFalse(AppProxy.proxies("tr-line.example"))
    }

    @Test
    fun proxyIsTheOnlyRoute() {
        AppProxy.configure(ProxyConfig(ProxyMode.ALL, ProxyType.SOCKS5, host = "proxy.example", port = 1080), emptySet())
        val routes = AppProxy.selector.select(URI("http://provider.example/live/1.ts"))
        // Kein Proxy.NO_PROXY dahinter: Fällt der Proxy aus, geht nichts direkt raus.
        assertEquals(1, routes.size)
        assertEquals(Proxy.Type.SOCKS, routes[0].type())
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("http://192.168.1.5:8096/play")))
    }

    @Test
    fun httpProxyWithLoginAndRedirects() {
        val server = FakeProxy(user = "dna", password = "geheim")
        try {
            AppProxy.configure(
                ProxyConfig(ProxyMode.SELECTED, ProxyType.HTTP, "127.0.0.1", server.port, "dna", "geheim", setOf(1)),
                setOf("panel.example"),
            )
            val client = OkHttpClient.Builder()
                .proxySelector(AppProxy.selector)
                .proxyAuthenticator(AppProxy.authenticator)
                .addNetworkInterceptor(AppProxy.interceptor)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            // Der Anbieter leitet auf einen Lastverteiler um: der läuft dann auch über den Proxy.
            client.newCall(Request.Builder().url("http://panel.example/live/u/p/1.ts").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("stream", response.body.string())
            }
            assertTrue(AppProxy.proxies("cdn7.example"))
            assertEquals(listOf("http://panel.example/live/u/p/1.ts", "http://cdn7.example/1.ts"), server.requests)
            // Gleich mit Anmeldung: keine abgelehnte Anfrage je Video-Stück.
            assertEquals(0, server.unauthorized)
            assertNull(AppProxy.recentFailure())
        } finally {
            server.close()
        }
    }

    @Test
    fun wrongPasswordIsReported() {
        val server = FakeProxy(user = "dna", password = "richtig")
        try {
            AppProxy.configure(ProxyConfig(ProxyMode.ALL, ProxyType.HTTP, "127.0.0.1", server.port, "dna", "falsch"), emptySet())
            val client = OkHttpClient.Builder()
                .proxySelector(AppProxy.selector)
                .proxyAuthenticator(AppProxy.authenticator)
                .addNetworkInterceptor(AppProxy.interceptor)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url("http://provider.example/x").build()).execute().use { assertEquals(407, it.code) }
            assertTrue(AppProxy.recentFailure().orEmpty().contains("Anmeldung"))
        } finally {
            server.close()
        }
    }

    @Test
    fun proxyCheckParsesServices() {
        assertEquals(ProxyCheck.Seen("1.2.3.4", "Türkiye (TR)"), ProxyCheck.parse("""{"ip":"1.2.3.4","success":true,"country":"Türkiye","country_code":"TR"}"""))
        assertEquals(ProxyCheck.Seen("5.6.7.8", "ID"), ProxyCheck.parse("""{"ip":"5.6.7.8","country":"ID"}"""))
        assertNull(ProxyCheck.parse("<html>"))
    }

    /** Minimaler HTTP-Proxy: verlangt Basic-Anmeldung, leitet /live/… auf cdn7.example um. */
    private class FakeProxy(user: String, password: String) : AutoCloseable {
        private val socket = ServerSocket(0)
        private val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val port: Int get() = socket.localPort
        val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var unauthorized = 0

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val target = head.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
                        val auth = head.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
                        val out = c.getOutputStream()
                        val response = when {
                            auth != expected -> {
                                unauthorized++
                                "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"p\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            }
                            target.contains("/live/") -> {
                                requests += target
                                "HTTP/1.1 302 Found\r\nLocation: http://cdn7.example/1.ts\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            }
                            else -> {
                                requests += target
                                "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nstream"
                            }
                        }
                        out.write(response.toByteArray())
                        out.flush()
                    }
                }
            }
        }

        override fun close() = socket.close()
    }
}

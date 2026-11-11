package de.ayutv.data.net

import okhttp3.Authenticator
import okhttp3.Interceptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ProxyMode(val label: String) {
    OFF("Aus"),
    ALL("Alle Verbindungen"),
    SELECTED("Nur ausgewählte Playlists"),
}

enum class ProxyType(val label: String) {
    HTTP("HTTP"),
    SOCKS5("SOCKS5"),
}

data class ProxyConfig(
    val mode: ProxyMode = ProxyMode.OFF,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    /** Playlists (IDs), deren Verbindungen über den Proxy laufen ([ProxyMode.SELECTED]). */
    val playlists: Set<Long> = emptySet(),
) {
    val complete: Boolean get() = host.isNotBlank() && port in 1..65535
    val active: Boolean get() = mode != ProxyMode.OFF && complete

    fun toProxy(): Proxy = Proxy(
        if (type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(host.trim(), port),
    )
}

/**
 * Proxy nur für DnA-TV (keine VPN-App nötig): Alle Verbindungen laufen über den gemeinsamen OkHttp-Client,
 * dort entscheidet [selector] je Server, ob es über den Proxy geht. Bei „Nur ausgewählte Playlists“ sind das
 * die Server dieser Playlists und die, auf die sie weiterleiten (Lastverteiler der Anbieter). Geräte im
 * Heimnetz (Handy-Fernbedienung) laufen nie über den Proxy.
 *
 * Fällt der Proxy aus, geht nichts heimlich direkt raus: OkHttp bekommt nur den Proxy als Weg, die Verbindung
 * scheitert, und [lastFailure] sagt dem Player, warum.
 */
object AppProxy {
    @Volatile private var config: ProxyConfig? = null
    @Volatile private var hosts: Set<String> = emptySet()
    private val learned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loaded = CountDownLatch(1)

    class Failure(val at: Long, val message: String)

    /** Letzter Fehler am Proxy (nicht erreichbar, Anmeldung abgelehnt). */
    @Volatile var lastFailure: Failure? = null
        private set

    val current: ProxyConfig get() = config ?: ProxyConfig()

    /** Neue Einstellungen; [playlistHosts] = Server der gewählten Playlists (nur bei [ProxyMode.SELECTED]). */
    fun configure(next: ProxyConfig, playlistHosts: Set<String>) {
        val changed = next != config
        hosts = playlistHosts.map { it.lowercase() }.toSet()
        if (changed) {
            learned.clear()
            lastFailure = null
        }
        config = next
        loaded.countDown()
    }

    /** Läuft eine Verbindung zu [host] über den Proxy? */
    fun proxies(host: String?): Boolean {
        val c = config ?: return false
        if (!c.active || host.isNullOrBlank() || isLocal(host)) return false
        val h = host.lowercase()
        if (h == c.host.trim().lowercase()) return false
        return when (c.mode) {
            ProxyMode.OFF -> false
            ProxyMode.ALL -> true
            ProxyMode.SELECTED -> h in hosts || h in learned
        }
    }

    /** Für OkHttp: der Proxy als einziger Weg (kein Ausweichen auf direkt) oder direkt. */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            // Gleich nach dem Start: kurz auf die Einstellungen warten, damit nichts vorher direkt rausgeht.
            if (config == null) runCatching { loaded.await(5, TimeUnit.SECONDS) }
            val c = config
            return if (c != null && proxies(uri?.host)) listOf(c.toProxy()) else listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Selbst abgebrochen (Umschalten, Neustart des Streams): kein Fehler des Proxys. Sonst hieß es fälschlich
            // „Proxy nicht erreichbar (Socket closed)“, und der eigentliche Grund ging unter.
            val text = ioe?.message.orEmpty()
            if (text.contains("closed", ignoreCase = true) || text.contains("canceled", ignoreCase = true) || ioe is java.io.InterruptedIOException && text.isEmpty()) return
            if (config?.active == true) fail("Proxy nicht erreichbar (${ioe?.message ?: "keine Antwort"})")
        }
    }

    /** HTTP-Proxy mit Anmeldung (Antwort 407). */
    val authenticator = Authenticator { _, response ->
        val c = config ?: return@Authenticator null
        if (response.code != 407) return@Authenticator null
        if (c.user.isEmpty()) {
            fail("Proxy verlangt eine Anmeldung (Benutzer und Passwort eintragen)")
            return@Authenticator null
        }
        // Schon mit Anmeldung abgelehnt: falsche Zugangsdaten, nicht endlos wiederholen.
        if (response.request.header("Proxy-Authorization") != null && response.challenges().none { it.scheme == "OkHttp-Preemptive" }) {
            fail("Proxy lehnt die Anmeldung ab (Benutzer oder Passwort falsch)")
            return@Authenticator null
        }
        response.request.newBuilder().header("Proxy-Authorization", basic(c)).build()
    }

    /**
     * Netzwerk-Interceptor: meldet sich bei einfachem HTTP über einen HTTP-Proxy gleich an (sonst kostet jedes
     * Video-Stück eine abgelehnte Anfrage) und merkt sich Weiterleitungen gewählter Playlists auf andere Server.
     */
    val interceptor = Interceptor { chain ->
        var request = chain.request()
        val c = config
        // Bei aktivem Proxy: identifizierenden User-Agent durch einen generischen ersetzen
        if (c != null && c.active && proxies(request.url.host)) {
            val ua = request.header("User-Agent")
            if (ua == null || ua.contains("DnATV", ignoreCase = true) || ua.contains("ExoPlayer", ignoreCase = true)) {
                request = request.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
            }
        }
        val route = chain.connection()?.route()
        if (c != null && c.user.isNotEmpty() && route?.proxy?.type() == Proxy.Type.HTTP && !request.isHttps &&
            request.header("Proxy-Authorization") == null
        ) {
            request = request.newBuilder().header("Proxy-Authorization", basic(c)).build()
        }
        val response = chain.proceed(request)
        if (c?.mode == ProxyMode.SELECTED && response.isRedirect && proxies(request.url.host)) {
            response.header("Location")?.let { request.url.resolve(it)?.host }?.let { target ->
                if (!isLocal(target)) learned += target.lowercase()
            }
        }
        if (route?.proxy?.type() == Proxy.Type.HTTP && response.code == 407) fail("Proxy lehnt die Anmeldung ab (Benutzer oder Passwort falsch)")
        response
    }

    /** SOCKS5 mit Anmeldung: Java fragt die Zugangsdaten über den systemweiten Authenticator ab. */
    val socksAuthenticator = object : java.net.Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val c = config ?: return null
            if (c.type != ProxyType.SOCKS5 || c.user.isEmpty()) return null
            if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
            if (!requestingHost.equals(c.host.trim(), ignoreCase = true)) return null
            return PasswordAuthentication(c.user, c.password.toCharArray())
        }
    }

    /** Fehler in der letzten halben Minute (für die Meldung im Player). */
    fun recentFailure(now: Long = System.currentTimeMillis()): String? =
        lastFailure?.takeIf { now - it.at < 30_000 }?.message

    private fun fail(message: String) {
        lastFailure = Failure(System.currentTimeMillis(), message)
    }

    private fun basic(c: ProxyConfig) = okhttp3.Credentials.basic(c.user, c.password)

    /** Server einer Adresse (ohne Port und Zugangsdaten), klein geschrieben; null bei Unsinn. */
    fun hostOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = if ("://" in text) text else "http://$text"
        val afterScheme = withScheme.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotBlank() }
    }

    /** Heimnetz und Gerät selbst: nie über den Proxy (Handy-Fernbedienung, Übersetzung per Handy). */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".fritz.box")) return true
        if (':' in h) return h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts[0] to parts[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || a == 0
    }
}

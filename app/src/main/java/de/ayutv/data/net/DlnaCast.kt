package de.ayutv.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Abspielen auf einem Fernseher im WLAN (LG webOS, Samsung, Sony, Philips, Receiver …) über DLNA/UPnP: Das Handy sucht
 * per SSDP nach „MediaRenderer“-Geräten und sagt dem Fernseher, welche Adresse er abspielen soll. Kann der Fernseher die
 * Adresse des Anbieters nicht selbst abspielen (Proxy, Kennung der App, Format), leitet das Handy den Stream über
 * [DlnaRelay] im WLAN weiter.
 */
object DlnaCast {
    data class Renderer(
        val name: String,
        val location: String,
        val controlUrl: String,
        val serviceType: String,
        /** Steueradresse für die Lautstärke (RenderingControl), falls der Fernseher sie anbietet. */
        val renderingUrl: String? = null,
        val renderingType: String = "urn:schemas-upnp-org:service:RenderingControl:1",
    ) {
        /** IP-Adresse des Fernsehers (aus der Beschreibungs-Adresse). */
        val host: String get() = runCatching { URI(location).host }.getOrNull().orEmpty()
    }

    /** Ergebnis der Prüfung nach dem Start. */
    enum class Playback { PLAYING, FAILED, UNKNOWN }

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private val TARGETS = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AV_TRANSPORT, "upnp:rootdevice")

    /** Fernseher stehen im eigenen WLAN: nie über den Proxy der App, kurze Wartezeiten. */
    private fun local(http: OkHttpClient, readSeconds: Long = 6) = http.newBuilder().proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).readTimeout(readSeconds, java.util.concurrent.TimeUnit.SECONDS).build()

    /**
     * LG (webOS) antwortet auf „Adresse setzen“ und „Play“ erst, wenn er den Stream geöffnet hat – bei Live-TV oft nach
     * 8–15 Sekunden. Mit 6 Sekunden Wartezeit galt das als „antwortet nicht“, die App versuchte die nächste Adresse und
     * brach so den gerade anlaufenden Stream ab („geht ganz kurz, dann Fehler“).
     */
    @Volatile internal var startReadSeconds = 12L

    /**
     * Was der Fernseher beim letzten Start auf jeden Befehl geantwortet hat („Stop: 200 in 0,3 s · Adresse: keine Antwort
     * nach 12 s …“). Steht bei einem Fehler in der Meldung: zeigt, an welchem Schritt es hängt.
     */
    private val trace = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun traceSummary(): String = trace.joinToString(" · ")

    /** Vor einem neuen Start: alte Antworten vergessen. */
    fun clearTrace() = trace.clear()

    private val ACTION_NAMES = mapOf(
        "Stop" to "Stop", "SetAVTransportURI" to "Adresse", "Play" to "Play", "GetTransportInfo" to "Zustand",
        "Pause" to "Pause", "GetVolume" to "Lautstärke", "SetVolume" to "Lautstärke",
    )

    /**
     * Netzwerke im Haus (WLAN, LAN) mit ihrer IPv4-Adresse. Ohne VPN-Tunnel (Punkt-zu-Punkt): Läuft ein VPN, ginge die
     * Suche sonst in den Tunnel statt ins WLAN und fände keinen Fernseher.
     */
    fun lanInterfaces(): List<Pair<NetworkInterface, Inet4Address>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual && it.supportsMulticast() }.getOrDefault(false) }
            .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") || it.name.startsWith("rmnet") || it.name.startsWith("ccmni") }
            .mapNotNull { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.let { ni to it } }
    }.getOrDefault(emptyList())

    /** Eigene Adresse, unter der der Fernseher [host] das Handy erreicht (gleiches Netz), sonst die erste im Haus. */
    fun localAddressFor(host: String): InetAddress? {
        val target = runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()
        val lan = lanInterfaces()
        if (target != null) {
            for ((ni, _) in lan) {
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (sameSubnet(addr, target, ia.networkPrefixLength.toInt())) return addr
                }
            }
            // Sonst wie das System den Fernseher erreicht.
            runCatching {
                DatagramSocket().use { s -> s.connect(target, 9); s.localAddress.takeIf { !it.isAnyLocalAddress } }
            }.getOrNull()?.let { return it }
        }
        return lan.firstOrNull()?.second
    }

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix !in 1..32) return false
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        fun Inet4Address.bits() = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        return (a.bits() and mask) == (b.bits() and mask)
    }

    /** Fernseher im WLAN suchen (etwa [timeoutMs] lang). Auf Android vorher einen MulticastLock halten. */
    suspend fun discover(client: OkHttpClient, timeoutMs: Int = 3_000): List<Renderer> = withContext(Dispatchers.IO) {
        val http = local(client)
        val locations = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val lan = lanInterfaces()
        coroutineScope {
            // Je Netzwerk im Haus ein eigener Socket, fest an dieses Netzwerk gebunden; ohne erkennbares Netzwerk wie bisher.
            val searches = if (lan.isEmpty()) listOf(async { search(null, null, timeoutMs, locations) })
            else lan.map { (ni, addr) -> async { search(ni, addr, timeoutMs, locations) } }
            searches.awaitAll()
        }
        coroutineScope {
            locations.toList().map { location ->
                async {
                    runCatching {
                        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { if (it.isSuccessful) it.body.string() else null }
                        xml?.let { parseDescription(it, location) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull().distinctBy { it.controlUrl }
        }
    }

    private fun search(ni: NetworkInterface?, addr: InetAddress?, timeoutMs: Int, locations: MutableSet<String>) {
        runCatching {
            val socket = if (ni != null && addr != null) {
                MulticastSocket(InetSocketAddress(addr, 0)).apply { networkInterface = ni; timeToLive = 4 }
            } else DatagramSocket()
            socket.use {
                it.soTimeout = 300
                val group = InetAddress.getByName(SSDP_HOST)
                for (target in TARGETS) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n".toByteArray()
                    repeat(2) { _ -> it.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)) }
                }
                val until = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        it.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    header(String(packet.data, 0, packet.length), "LOCATION")?.let { l -> locations += l }
                }
            }
        }
    }

    /** Antwortzeile „LOCATION: http://…“ einer SSDP-Antwort. */
    fun header(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** Gerätebeschreibung lesen: Name und Steueradresse des AVTransport-Dienstes (null ohne den Dienst). */
    fun parseDescription(xml: String, location: String): Renderer? {
        val name = tag(xml, "friendlyName") ?: "Fernseher"
        val service = Regex("""<service>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { tag(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:AVTransport:") == true } ?: return null
        val control = tag(service, "controlURL") ?: return null
        val base = tag(xml, "URLBase") ?: location
        val rendering = Regex("""<service>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { tag(it, "serviceType")?.startsWith("urn:schemas-upnp-org:service:RenderingControl:") == true }
        val renderingUrl = rendering?.let { tag(it, "controlURL") }?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }
        return Renderer(
            unescape(name), location, URI(base).resolve(control).toString(), tag(service, "serviceType")!!,
            renderingUrl, rendering?.let { tag(it, "serviceType") } ?: "urn:schemas-upnp-org:service:RenderingControl:1",
        )
    }

    private fun tag(xml: String, name: String): String? =
        Regex("""<(?:\w+:)?$name>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    /**
     * Stream abspielen: Adresse setzen, dann Play. Liefert null bei Erfolg, sonst den Grund. Lehnt der Fernseher die
     * Beschreibung ab (manche LG-Modelle), noch einmal ohne.
     */
    suspend fun play(http: OkHttpClient, renderer: Renderer, url: String, title: String, live: Boolean, mime: String = mimeOf(url, live)): String? =
        withContext(Dispatchers.IO) {
            runCatching { soap(http, renderer, "Stop", "<InstanceID>0</InstanceID>") }
            var set = setUri(http, renderer, url, didl(url, title, live, mime))
            // Keine Antwort, obwohl er schon lädt oder spielt: nicht abbrechen (das stoppte den anlaufenden Stream).
            if (set.timedOut && startedAnyway(http, renderer)) return@withContext null
            if (set.error != null && !set.timedOut) set = setUri(http, renderer, url, "")
            if (set.error != null) return@withContext set.error
            val play = soap(http, renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>", startReadSeconds)
            if (play.timedOut && startedAnyway(http, renderer)) null else play.error
        }

    /** Lädt oder spielt der Fernseher trotz fehlender Antwort? (Zustand bis zu 4 Sekunden lang abfragen.) */
    private suspend fun startedAnyway(http: OkHttpClient, renderer: Renderer): Boolean {
        repeat(4) {
            when (state(http, renderer)?.uppercase()) {
                "PLAYING", "TRANSITIONING" -> return true
            }
            delay(1_000)
        }
        return false
    }

    private fun setUri(http: OkHttpClient, renderer: Renderer, url: String, meta: String) = soap(
        http, renderer, "SetAVTransportURI",
        "<InstanceID>0</InstanceID><CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(meta)}</CurrentURIMetaData>",
        startReadSeconds,
    )

    suspend fun stop(http: OkHttpClient, renderer: Renderer): String? = withContext(Dispatchers.IO) {
        soap(http, renderer, "Stop", "<InstanceID>0</InstanceID>").error
    }

    suspend fun pause(http: OkHttpClient, renderer: Renderer): String? = withContext(Dispatchers.IO) {
        soap(http, renderer, "Pause", "<InstanceID>0</InstanceID>").error
    }

    suspend fun resume(http: OkHttpClient, renderer: Renderer): String? = withContext(Dispatchers.IO) {
        soap(http, renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").error
    }

    /** Lautstärke um [delta] ändern (0–100); liefert die neue Lautstärke oder null, wenn der Fernseher das nicht kann. */
    suspend fun changeVolume(http: OkHttpClient, renderer: Renderer, delta: Int): Int? = withContext(Dispatchers.IO) {
        val url = renderer.renderingUrl ?: return@withContext null
        val current = soapTo(http, url, renderer.renderingType, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
            .body?.let { tag(it, "CurrentVolume")?.toIntOrNull() } ?: return@withContext null
        val next = (current + delta).coerceIn(0, 100)
        val set = soapTo(http, url, renderer.renderingType, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$next</DesiredVolume>")
        if (set.error == null) next else null
    }

    /** Zustand des Fernsehers („PLAYING“, „TRANSITIONING“, „STOPPED“, „NO_MEDIA_PRESENT“ …), null ohne Antwort. */
    suspend fun state(http: OkHttpClient, renderer: Renderer): String? = withContext(Dispatchers.IO) {
        val result = soap(http, renderer, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        if (result.error != null) null else result.body?.let { tag(it, "CurrentTransportState") }
    }

    /**
     * Nach dem Start prüfen, ob der Fernseher wirklich abspielt: Er meldet „PLAYING“ (gut), bleibt bei „STOPPED“/„NO_MEDIA“
     * oder fällt nach dem Laden darauf zurück (er kann die Adresse nicht abspielen), oder er gibt keine Auskunft.
     */
    suspend fun awaitPlaying(http: OkHttpClient, renderer: Renderer, timeoutMs: Long = 14_000): Playback {
        val until = System.currentTimeMillis() + timeoutMs
        var answered = false
        var playingSince = 0L
        while (System.currentTimeMillis() < until) {
            delay(1_000)
            val state = state(http, renderer)?.uppercase()
            if (state != null) answered = true
            when (state) {
                // Zweimal hintereinander „spielt“: Manche Fernseher melden kurz PLAYING und brechen dann ab.
                "PLAYING" -> if (playingSince == 0L) playingSince = System.currentTimeMillis() else return Playback.PLAYING
                "STOPPED", "NO_MEDIA_PRESENT" -> if (playingSince != 0L || System.currentTimeMillis() > until - timeoutMs / 2) return Playback.FAILED
                else -> playingSince = 0L
            }
        }
        return when {
            playingSince != 0L -> Playback.PLAYING
            answered -> Playback.FAILED
            else -> Playback.UNKNOWN
        }
    }

    private class SoapResult(val error: String?, val body: String?, val timedOut: Boolean = false)

    private fun soap(client: OkHttpClient, renderer: Renderer, action: String, args: String, readSeconds: Long = 6): SoapResult =
        soapTo(client, renderer.controlUrl, renderer.serviceType, action, args, readSeconds)

    private fun soapTo(client: OkHttpClient, controlUrl: String, serviceType: String, action: String, args: String, readSeconds: Long = 6): SoapResult {
        val started = System.currentTimeMillis()
        val result = soapCall(client, controlUrl, serviceType, action, args, readSeconds)
        val seconds = "%.1f".format(java.util.Locale.GERMANY, (System.currentTimeMillis() - started) / 1000.0)
        val name = ACTION_NAMES[action] ?: action
        trace.addLast(
            when {
                result.timedOut -> "$name: keine Antwort nach $seconds s"
                result.error != null && result.body == null -> "$name: ${result.error.substringAfter('(').removeSuffix(")")}"
                result.error != null -> "$name: ${result.error.removePrefix("Fernseher lehnt ab ")}"
                else -> "$name: ok ($seconds s)"
            }
        )
        while (trace.size > 12) trace.pollFirst()
        return result
    }

    private fun soapCall(client: OkHttpClient, controlUrl: String, serviceType: String, action: String, args: String, readSeconds: Long): SoapResult = runCatching {
        val http = local(client, readSeconds)
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$serviceType">$args</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder().url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            // Manche Fernseher (LG) beenden die Antwort erst, wenn die Verbindung geschlossen wird.
            .header("Connection", "close")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.isSuccessful) SoapResult(null, text)
            else SoapResult("Fernseher lehnt ab (${response.code}" + (tag(text, "errorDescription")?.let { ": $it" } ?: "") + ")", text)
        }
    }.getOrElse {
        SoapResult("Fernseher antwortet nicht (${it.javaClass.simpleName})", null, timedOut = it is java.io.InterruptedIOException)
    }

    /** Videotyp für den Fernseher: Live-Streams (MPEG-TS) als video/mp2t, HLS als application/x-mpegURL, sonst nach Endung. */
    fun mimeOf(url: String, live: Boolean): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".avi") -> "video/x-msvideo"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            live || path.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * DLNA-Angaben zum Stream: Streaming-Übertragung, bei Dateien Spulen per Byte-Bereich. LG und Samsung spielen ohne
     * diese Angaben manche Streams nicht ab.
     */
    fun features(seekable: Boolean): String =
        "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** Beschreibung der Sendung für den Fernseher (DIDL-Lite). */
    fun didl(url: String, title: String, live: Boolean, mime: String = mimeOf(url, live)): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:$mime:${features(!live)}">${escape(url)}</res></item></DIDL-Lite>"""

    /**
     * Adressen eines Live-Senders für den Fernseher, beste zuerst: der rohe MPEG-TS-Strom (volle Qualität, keine
     * Qualitätsstufen, die der Fernseher herunterregeln könnte), danach HLS, falls der Fernseher TS ablehnt.
     * Bei Xtream gibt es beide unter derselben Adresse mit anderer Endung.
     */
    /**
     * HLS-Fassung eines Films oder einer Folge beim Xtream-Anbieter („…/movie/user/pass/123.mkv“ → „…/123.m3u8“).
     * Viele Anbieter liefern sie; der Mediaplayer des LG spielt HLS zuverlässig und zeigt dazu Untertitel. Sonst null.
     */
    fun vodHlsUrl(url: String): String? {
        val m = Regex("""^(.*/(?:movie|series)/[^/]+/[^/]+/\d+)\.(\w+)(\?.*)?$""").find(url) ?: return null
        val (base, ext, query) = m.destructured
        return if (ext.equals("m3u8", true)) null else "$base.m3u8$query"
    }

    fun liveUrls(url: String): List<String> {
        val xtream = Regex("""^(.*/live/[^/]+/[^/]+/\d+)\.(ts|m3u8)(\?.*)?$""").find(url) ?: return listOf(url)
        val (base, _, query) = xtream.destructured
        return listOf("$base.ts$query", "$base.m3u8$query")
    }

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}

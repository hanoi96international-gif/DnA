package de.ayutv.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Verschlüsselte Namensauflösung (DNS über HTTPS): Bevor DnA-TV einen Server anspricht, fragt es dessen Adresse
 * verschlüsselt bei Cloudflare bzw. Google ab statt im Klartext beim Router bzw. Internetanbieter. Wer die Leitung
 * mitliest, sieht so nicht mehr, welche Server (IPTV-Anbieter, Untertitel, TV-Guide) DnA-TV anspricht.
 *
 * Gefragt wird direkt über die IP-Adresse des Dienstes (kein Henne-Ei-Problem). Antwortet keiner (gesperrt, kein
 * Internet), gilt wie bisher die normale Namensauflösung – es geht nie schlechter als vorher (danach 5 Minuten lang
 * gleich normal, damit nicht jeder Name erneut wartet). Abgelaufene Einträge gelten sofort weiter und werden im
 * Hintergrund erneuert, damit Umschalten nie auf eine Abfrage wartet. Namen im Heimnetz
 * (ohne Punkt, .local, .lan, .box, .home, .internal) fragt DnA-TV wie bisher beim Router. Ergebnisse werden so lange
 * gemerkt, wie der Dienst es erlaubt (höchstens eine Stunde).
 */
object SecureDns : Dns {
    /** Einstellung „Verschlüsselte Namensauflösung“ (Standard: an). */
    @Volatile var enabled = true

    private data class Entry(val addresses: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Zuletzt benutzter Weg, für Stream-Info und Fehlerbericht. */
    @Volatile var lastPath: String = "–"
        private set

    private val resolvers = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
        "https://1.0.0.1/dns-query",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Bis wann die verschlüsselte Abfrage übersprungen wird, weil zuletzt kein Dienst antwortete. */
    @Volatile private var downUntil = 0L
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val refresher by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "SecureDns").apply { isDaemon = true } }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!enabled || isLocal(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        val cached = cache[hostname]
        if (cached != null && cached.until > now) return cached.addresses
        // Abgelaufen, aber noch frisch genug: sofort die bekannte Adresse nehmen und im Hintergrund neu fragen.
        // So kostet das Umschalten nie eine Abfrage (Sender, Logos, TV-Guide).
        if (cached != null && now - cached.until < STALE_MS) {
            if (refreshing.add(hostname)) refresher.execute {
                try { resolve(hostname) } finally { refreshing.remove(hostname) }
            }
            return cached.addresses
        }
        return resolve(hostname) ?: Dns.SYSTEM.lookup(hostname)
    }

    /** Verschlüsselt abfragen und merken; null = nicht möglich (dann normale Namensauflösung). */
    private fun resolve(hostname: String): List<InetAddress>? {
        val now = System.currentTimeMillis()
        // Antwortete zuletzt keiner (gesperrt, Netz ohne Internet), nicht bei jedem Namen erneut warten.
        if (now < downUntil) {
            lastPath = "normal (verschlüsselt nicht erreichbar)"
            return null
        }
        var reached = false
        for (resolver in resolvers) {
            val answer = runCatching { query(resolver, hostname, "A") }.getOrNull() ?: continue
            reached = true
            val (addresses, ttl) = answer
            if (addresses.isEmpty()) continue
            val resolved = addresses.map { InetAddress.getByAddress(hostname, InetAddress.getByName(it).address) }
            cache[hostname] = Entry(resolved, System.currentTimeMillis() + ttl.coerceIn(60, 3_600) * 1000L)
            lastPath = "verschlüsselt (${resolver.substringAfter("://").substringBefore('/')})"
            return resolved
        }
        // Kein Dienst erreichbar: 5 Minuten lang gleich normal fragen. Nur keine IPv4-Adresse: einfach normal.
        if (!reached) downUntil = System.currentTimeMillis() + DOWN_MS
        lastPath = if (reached) "normal (keine IPv4-Adresse verschlüsselt)" else "normal (verschlüsselt nicht erreichbar)"
        return null
    }

    private const val STALE_MS = 6 * 60 * 60 * 1000L
    private const val DOWN_MS = 5 * 60 * 1000L

    private fun query(resolver: String, hostname: String, type: String): Pair<List<String>, Int>? {
        val url = "$resolver?name=${java.net.URLEncoder.encode(hostname, "UTF-8")}&type=$type"
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else parseAnswer(response.body.string())
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** JSON-Antwort (Cloudflare/Google): IPv4-Adressen (Typ 1) und kürzeste Gültigkeit. null bei Fehler. Öffentlich für Tests. */
    fun parseAnswer(body: String): Pair<List<String>, Int>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root["Status"]?.jsonPrimitive?.intOrNull != 0) return null
        val answers = (root["Answer"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.intOrNull == 1 }
        val addresses = answers.mapNotNull { it["data"]?.jsonPrimitive?.contentOrNull?.takeIf(::isIpv4) }
        val ttl = answers.mapNotNull { it["TTL"]?.jsonPrimitive?.intOrNull }.minOrNull() ?: 300
        return addresses to ttl
    }

    private fun isIpv4(text: String) = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(text)

    /** Namen im Heimnetz und IP-Adressen: nicht nach außen fragen. Öffentlich für Tests. */
    fun isLocal(hostname: String): Boolean {
        val h = hostname.lowercase().trimEnd('.')
        if ('.' !in h || ':' in h || isIpv4(h)) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".box", ".internal", ".localdomain", ".home.arpa", ".intranet")
}

/**
 * Dienste, die HTTPS können, nie unverschlüsselt ansprechen: Eine `http://`-Adresse (etwa ein Poster von TMDB in der
 * Playlist) wird vor dem Senden auf `https://` umgestellt. Nur für bekannte Dienste – viele IPTV-Anbieter haben gar
 * kein HTTPS, dort bliebe der Stream sonst stehen.
 */
object HttpsUpgrade : Interceptor {
    private val HOSTS = listOf(
        "image.tmdb.org", "api.themoviedb.org", "www.themoviedb.org",
        "epgshare01.online", "open-epg.com", "i.mjh.nz", "api.openligadb.de",
        "github.com", "githubusercontent.com", "opensubtitles.com", "opensubtitles.org", "deepl.com",
        "upload.wikimedia.org", "i.imgur.com", "m.media-amazon.com",
    )

    /** Die verschlüsselte Adresse, wenn der Dienst HTTPS kann, sonst [url] unverändert. Öffentlich für Tests. */
    fun upgrade(url: HttpUrl): HttpUrl {
        if (url.scheme != "http") return url
        val host = url.host.lowercase()
        if (HOSTS.none { host == it || host.endsWith(".$it") }) return url
        return url.newBuilder().scheme("https").port(if (url.port == 80) 443 else url.port).build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val secure = upgrade(request.url)
        val builder = if (secure == request.url) null else request.newBuilder().url(secure)
        // Wikimedia (Vereinswappen) lehnt Abrufe ohne aussagekräftige Kennung ab; ohne eigene gilt die von OkHttp.
        if (secure.host.endsWith("wikimedia.org") && request.header("User-Agent") == null) {
            return chain.proceed((builder ?: request.newBuilder()).header("User-Agent", WIKIMEDIA_AGENT).build())
        }
        return chain.proceed(builder?.build() ?: request)
    }

    private const val WIKIMEDIA_AGENT = "DnA-TV/1.0 (IPTV-Player; https://github.com/hanoi96international-gif/DnA)"
}

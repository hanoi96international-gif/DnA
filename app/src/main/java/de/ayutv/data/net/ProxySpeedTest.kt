package de.ayutv.data.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Speedtest für den Proxy: Antwortzeit und Geschwindigkeit über den Proxy und zum Vergleich direkt, gegen einen
 * neutralen Messserver (nicht den TV-Anbieter: der erlaubt oft nur eine Verbindung und liefert Live-Sender nur in
 * Echtzeit). Gemessen wird mit einer einzigen Verbindung, wie ein Videostream sie nutzt. Nichts wird gespeichert außer
 * dem letzten Ergebnis im Speicher (für den Kurzbericht); keine Zugangsdaten verlassen das Gerät außer an den Proxy selbst.
 */
object ProxySpeedTest {
    data class Route(val pingMs: Long?, val jitterMs: Long?, val download: SpeedStats.Download?, val error: String?)

    data class Result(val at: Long, val proxied: Route, val direct: Route?) {
        fun summary(): String = buildString {
            append("Über den Proxy: ").append(line(proxied))
            direct?.let { append("\nDirekt: ").append(line(it)) }
            val p = proxied.download
            val d = direct?.download
            if (p != null) append("\nÜber den Proxy ${SpeedStats.verdict(p)}.")
            if (p != null && d != null && d.lowKbps > p.lowKbps * 2) {
                append(" Direkt ist die Leitung mehr als doppelt so schnell – der Proxy bremst.")
            }
        }

        /** Eine Zeile für den Kurzbericht. */
        fun report(): String = "Proxy-Speedtest ${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(at))}: " +
            "über Proxy ${line(proxied)}" + (direct?.let { " · direkt ${line(it)}" } ?: "")

        private fun line(r: Route): String {
            r.error?.let { if (r.download == null) return "Fehler ($it)" }
            val parts = ArrayList<String>()
            r.pingMs?.let { parts += "Ping $it ms" + (r.jitterMs?.let { j -> " (±$j)" } ?: "") }
            r.download?.let { parts += "${SpeedStats.mbit(it.kbps)}, schwächste Sekunde ${SpeedStats.mbit(it.lowKbps)}" }
            return parts.joinToString(", ").ifEmpty { "keine Messung" }
        }
    }

    /** Letztes Ergebnis, nur im Speicher. */
    @Volatile var last: Result? = null
        private set

    private const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
    /** Je Abruf höchstens 100 MB; ist er vor Ablauf der Messzeit fertig, folgt der nächste. */
    private val DOWNLOAD_URLS = listOf("https://speed.cloudflare.com/__down?bytes=100000000", "https://proof.ovh.net/files/100Mb.dat")
    private const val SECONDS = 8
    /** Obergrenze je Weg, damit eine schnelle direkte Leitung nicht Hunderte MB verbraucht. */
    private const val MAX_BYTES = 150L * 1024 * 1024

    /** [onPhase]: was gerade gemessen wird (für die Anzeige). */
    suspend fun run(http: OkHttpClient, config: ProxyConfig, onPhase: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val base = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        onPhase("über den Proxy …")
        val proxied = route(ProxyCheck.proxiedClient(base, config))
        onPhase("direkt …")
        val direct = route(base.proxy(Proxy.NO_PROXY).build())
        Result(System.currentTimeMillis(), proxied, direct).also { last = it }
    }

    private fun route(client: OkHttpClient): Route {
        val times = ArrayList<Long>()
        var failure: String? = null
        repeat(5) {
            val started = SystemClock.elapsedRealtime()
            runCatching {
                client.newCall(Request.Builder().url(PING_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body.bytes()
                }
                times += SystemClock.elapsedRealtime() - started
            }.onFailure { if (failure == null) failure = ProxyCheck.explain(it) }
        }
        val ping = SpeedStats.ping(times)
        var speed: SpeedStats.Download? = null
        for (url in DOWNLOAD_URLS) {
            val measured = runCatching { measure(client, url) }
            speed = measured.getOrNull()
            if (speed != null) break
            measured.exceptionOrNull()?.let { if (failure == null) failure = ProxyCheck.explain(it) }
        }
        if (speed == null && failure == null) failure = "keine Daten"
        return Route(ping?.first, ping?.second, speed, failure.takeIf { speed == null || ping == null })
    }

    /** Einige Sekunden so schnell wie möglich laden, über eine Verbindung (wie ein Videostream). */
    private fun measure(client: OkHttpClient, url: String): SpeedStats.Download? {
        val reads = ArrayList<Pair<Long, Int>>(4096)
        val buffer = ByteArray(64 * 1024)
        var first = -1L
        var bytes = 0L
        val end = SystemClock.elapsedRealtime() + SECONDS * 1000L + 2_000
        // Fertig nach SECONDS Sekunden ab dem ersten Byte, spätestens nach SECONDS + 2 (langsamer Aufbau).
        fun done() = SystemClock.elapsedRealtime().let { now -> now >= end || first >= 0 && now - first >= SECONDS * 1000L } || bytes >= MAX_BYTES
        while (!done()) {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val stream = r.body.byteStream()
                while (!done()) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    if (first < 0) first = now
                    reads += (now - first) to n
                    bytes += n
                }
            }
        }
        return SpeedStats.download(reads)
    }
}

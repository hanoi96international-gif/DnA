package de.ayutv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Weiterleitung für den Fernseher: Das Handy holt den Stream beim Anbieter (über den Proxy der App, mit ihrer Kennung,
 * Umleitungen folgend) und gibt ihn im WLAN als einfache Adresse weiter, die jeder DLNA-Fernseher abspielt. Für den
 * Fall, dass der Fernseher die Adresse des Anbieters selbst nicht abspielen kann (Anbieter nur über Proxy erreichbar,
 * verlangt die Kennung der App, HLS, das der Fernseher nicht kann).
 *
 * - Live (MPEG-TS): durchgereicht. Live-HLS (TS-Segmente): Segment für Segment zu einem durchgehenden TS-Strom
 *   zusammengesetzt.
 * - Filme/Folgen: durchgereicht mit Byte-Bereichen, damit der Fernseher spulen kann.
 * - Live: Holt der Fernseher den Stream neu (z. B. nach einer kurzen Prüfung), wird der alte Abruf beendet, damit
 *   Anbieter mit nur einer Verbindung nicht ablehnen.
 */
class DlnaRelay(
    client: OkHttpClient,
    source: String,
    private val userAgent: String?,
    private val live: Boolean,
    bind: InetAddress,
    /**
     * HLS als HLS weitergeben (Wiedergabeliste mit Adressen auf das Handy, jedes Stück über den Proxy der App geholt)
     * statt als ein endloser TS-Strom. Der Mediaplayer von LG webOS spielt HLS, holt einen endlosen TS-Strom vom Handy
     * aber gar nicht erst ab.
     */
    hlsToTv: Boolean = false,
    /** Untertitel für den Fernseher (nur mit [hlsToTv]): als WebVTT-Spur in der Wiedergabeliste. */
    subtitles: HlsSubtitles? = null,
    /** Filme (nur mit [hlsToTv]): ab hier abspielen – die Stücke davor fehlen in der Liste, der LG startet dort. */
    private val startSeconds: Double = 0.0,
    /**
     * Film-Datei (MKV, MP4) in eine HLS-Liste mit einem einzigen Stück verpacken, Länge [wrapSeconds]: Der Mediaplayer des
     * LG öffnet vom Handy nur HLS-Adressen. Das Stück selbst ist die Datei (mit Springen per Range).
     */
    private val wrapSeconds: Long = 0,
    /**
     * Film umverpackt ([RemuxSession]): echte HLS-Liste mit Stücken an Schlüsselbildern, Ton für den Fernseher
     * umgerechnet. Der Fernseher kann so spulen, hat Ton und Untertitel. Die Weiterleitung schließt die Sitzung mit.
     */
    val remux: RemuxSession? = null,
    /**
     * 4K-Sender: in den Hauptlisten für den Fernseher als 3840×2160 angeben (sonst 1920×1080). Der Mediaplayer des LG
     * richtet sich danach ein – mit 1080p angekündigt, spielte er 4K nicht in voller Auflösung.
     */
    private val uhd: Boolean = false,
    /**
     * Live-Sender als durchgehender TS-Strom (kein HLS beim Anbieter): trotzdem als eigene, fortlaufende HLS-Liste an den
     * Fernseher (siehe [liveWindow]) – der LG-Mediaplayer holt einen endlosen TS-Strom vom Handy nicht ab, eine Liste
     * schon. Eine einzige Verbindung zum Anbieter, Senderwechsel ohne Neustart des Mediaplayers ([switchTo]).
     */
    liveTsWindow: Boolean = false,
) : Closeable {
    private val resolution get() = if (uhd) "3840x2160" else "1920x1080"
    private val bandwidth get() = if (uhd) 25_000_000 else 8_000_000

    /** Untertitel für den Fernseher; austauschbar (gilt ab dem nächsten Öffnen der Liste, siehe [reopenUrl]). */
    @Volatile var subtitles: HlsSubtitles? = subtitles

    /** Untertitel (Beginn, Ende in ms, Text), mit Sprache und Namen (wie sie der Fernseher in seiner Auswahl zeigt). */
    class HlsSubtitles(
        /** Laufend abgefragt: Übersetzungen, die während der Übertragung fertig werden, kommen so noch beim Fernseher an. */
        val cues: () -> List<Triple<Long, Long, String>>,
        val language: String,
        val name: String,
        val durationSeconds: Long,
    )

    /** So viele Sekunden fehlen am Anfang der Liste (Weiterschauen): Untertitel rücken um so viel nach vorn. */
    @Volatile private var skippedSeconds = 0.0

    private val http = client.newBuilder().readTimeout(20, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()
    private val server = ServerSocket(0, 16, bind)
    /** Adresse beim Anbieter; bei Live über HLS beim Umschalten geändert ([switchTo]). */
    @Volatile private var source: String = source
    private val hls = source.substringBefore('?').lowercase().endsWith(".m3u8")
    private val passHls = hlsToTv && hls
    private val tsWindow = liveTsWindow && live && !hls && remux == null

    /** Gestartet mit einer HLS-Adresse des Anbieters (sonst TS): beim Umschalten dieselbe Art nehmen. */
    val isHlsSource: Boolean get() = hls
    private val wrap = wrapSeconds > 0 && !hls && remux == null
    private val base = "http://${bind.hostAddress}:${server.localPort}"

    /** Typ, den der Fernseher bekommt: HLS wird als TS weitergegeben (außer mit hlsToTv). */
    val mime: String = if (remux != null || passHls || wrap || tsWindow) "application/x-mpegURL" else if (hls) "video/mp2t" else DlnaCast.mimeOf(source, live)
    val url: String = if (remux != null || passHls || wrap || tsWindow) "$base/stream.m3u8" else "$base/stream.${extension(mime)}"

    /** Zähler fürs Neu-Öffnen (eigene Adresse, damit der Fernseher nichts Altes aus seinem Speicher nimmt). */
    private val opened = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Umverpackter Film: Liste ab [seconds] neu beginnen lassen (Springen per Handy, andere Tonspur, andere
     * Untertitel). Liefert die Adresse, die der Fernseher neu öffnen soll.
     */
    fun reopenUrl(seconds: Double): String {
        val session = remux ?: return url
        skippedSeconds = session.startAt(seconds)
        return "$base/stream.m3u8?v=${opened.incrementAndGet()}"
    }

    /**
     * Wiedergabeliste für den Player auf dem Handy, der beim Übertragen ohne Bild und Ton mitliest (Untertitel automatisch
     * anpassen): dieselben Stücke wie der Fernseher, aus dem Zwischenspeicher – keine zweite Verbindung zum Anbieter.
     */
    val shadowUrl: String = "$base/full.m3u8"

    /** Zuletzt geholte Stücke (Fernseher und mitlesender Player teilen sie), und gerade laufende Abrufe. */
    private val segments = object : LinkedHashMap<String, Pair<ByteArray, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String>>?) = size > 40
    }
    private val loading = HashMap<String, java.util.concurrent.CompletableFuture<Pair<ByteArray, String>?>>()

    /** Jede Verbindung des Fernsehers, auch nur Nachfragen (HEAD): zeigt, ob er das Handy überhaupt erreicht. */
    val contacts = java.util.concurrent.atomic.AtomicInteger()

    /** Bisher weitergegebene Bytes (zeigt, ob der Fernseher wirklich abruft). */
    val bytes = AtomicLong()
    /** Abrufe des Fernsehers und Bytes vom Anbieter (für die Anzeige „was passiert gerade“). */
    val requests = java.util.concurrent.atomic.AtomicInteger()
    val fetched = AtomicLong()

    /** Kurzer Stand für die Anzeige: Abrufe, an den Fernseher, vom Anbieter, letzter Fehler. */
    fun summary(): String {
        fun mb(n: Long) = String.format(java.util.Locale.GERMANY, "%.1f MB", n / 1_048_576.0)
        return "Fernseher hat ${contacts.get()}× angefragt, ${requests.get()}× abgerufen, ${mb(bytes.get())} bekommen · vom Anbieter ${mb(fetched.get() + (remux?.fetched?.get() ?: 0L))}" +
            (if (caughtUpSeconds >= 1) " · ${caughtUpSeconds.toInt()} s aufgeholt" else "") +
            (lastError?.let { " · $it" } ?: "")
    }

    /** Letzter Fehler beim Abruf vom Anbieter (für die Meldung an den Nutzer). */
    @Volatile var lastError: String? = null
        private set

    private val clients = CopyOnWriteArrayList<Socket>()
    @Volatile private var closed = false

    init {
        remux?.let { skippedSeconds = it.firstSeconds }
        thread(isDaemon = true, name = "dlna-relay") {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "dlna-relay-client") { serve(socket) }
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(window) { (window as Object).notifyAll() }
        synchronized(feedLock) { feed?.stop(); feed = null }
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        remux?.close()
    }

    private fun serve(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = 20_000
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val method = requestLine.substringBefore(' ').uppercase()
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                contacts.incrementAndGet()
                if (remux != null) {
                    serveRemux(remux, method, requestLine.split(' ').getOrNull(1).orEmpty(), out)
                    return
                }
                if (wrap) {
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    val head = method == "HEAD"
                    when {
                        path.startsWith("/stream.m3u8") -> {
                            requests.incrementAndGet()
                            val subs = subtitles
                            val list = if (subs != null) {
                                "#EXTM3U\n#EXT-X-VERSION:3\n" + subtitleMedia(base, subs) +
                                    "\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,SUBTITLES=\"subs\"\n$base/wrap.m3u8\n"
                            } else wrapPlaylist(base, wrapSeconds, extension(DlnaCast.mimeOf(source, false)))
                            sendText(out, list, "application/vnd.apple.mpegurl", head)
                            return
                        }
                        path.startsWith("/wrap.m3u8") -> {
                            sendText(out, wrapPlaylist(base, wrapSeconds, extension(DlnaCast.mimeOf(source, false))), "application/vnd.apple.mpegurl", head)
                            return
                        }
                        path.startsWith("/subs") -> {
                            if (serveSubtitles(path, out, head)) return
                        }
                    }
                    // Sonst: die Datei selbst (Range erlaubt), unten wie bei DLNA.
                }
                if (passHls || tsWindow) {
                    servePassHls(method, requestLine.split(' ').getOrNull(1).orEmpty(), out)
                    return
                }
                when (method) {
                    "GET" -> Unit
                    // Fernseher fragen oft erst nach Typ und Größe: ohne Abruf beim Anbieter beantworten.
                    "HEAD" -> {
                        respond(out, "200 OK", headers(null, seekable = !live))
                        return
                    }
                    else -> {
                        respond(out, "405 Method Not Allowed", mapOf("Content-Length" to "0", "Connection" to "close"))
                        return
                    }
                }
                requests.incrementAndGet()
                if (live) clients.forEach { old -> runCatching { old.close() } }
                clients += socket
                try {
                    when {
                        hls -> relayHls(out)
                        // Live: alle Abrufe des Fernsehers aus EINER Verbindung zum Anbieter (siehe LiveFeed).
                        live -> relayLive(out)
                        else -> relayDirect(out, headers["range"])
                    }
                } finally {
                    clients -= socket
                }
            }
        }
    }

    private fun request(url: String, range: String? = null): Request = Request.Builder().url(url).apply {
        userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        range?.let { header("Range", it) }
    }.build()

    private fun relayDirect(out: OutputStream, range: String?) {
        http.newCall(request(source, if (live) null else range)).execute().use { response ->
            if (!response.isSuccessful) {
                lastError = "Anbieter antwortet mit ${response.code}"
                respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
                return
            }
            val partial = response.code == 206
            val length = response.body.contentLength().takeIf { it >= 0 && !live }
            val seekable = !live && (partial || response.header("Accept-Ranges")?.contains("bytes") == true)
            val headers = headers(length, seekable).toMutableMap()
            response.header("Content-Range")?.takeIf { partial }?.let { headers["Content-Range"] = it }
            respond(out, if (partial) "206 Partial Content" else "200 OK", headers)
            copy(response.body.byteStream(), out)
        }
    }

    /** Live-HLS als ein durchgehender TS-Strom: Wiedergabeliste laufend neu laden, neue Segmente der Reihe nach senden. */
    // ------------------------------------------------------------------ Live: eine Verbindung für alle Abrufe

    /**
     * Fernseher rufen einen Live-Stream oft mehrmals kurz hintereinander ab (erst prüfen, dann abspielen; nach einem
     * Hänger neu). Mit je einer neuen Verbindung zum Anbieter lehnt ein Konto mit nur einer Verbindung die zweite ab –
     * das Bild blieb schwarz. Darum holt EINE Verbindung den Strom in einen Ringpuffer; jeder Abruf des Fernsehers liest
     * daraus (ab kurz vor dem neuesten Stand). Ohne Abrufer bleibt sie noch [IDLE_MS] offen, für den nächsten Abruf.
     */
    private inner class LiveFeed {
        private val ring = ByteArray(RING_BYTES)
        private val lock = Object()
        @Volatile var written = 0L
            private set
        @Volatile var failed: String? = null
            private set
        @Volatile var ended = false
            private set
        @Volatile private var stopped = false
        private val readers = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var idleSince = System.currentTimeMillis()

        init {
            thread(isDaemon = true, name = "dlna-relay-feed") { pump() }
        }

        fun stop() {
            stopped = true
            synchronized(lock) { lock.notifyAll() }
        }

        private fun pump() {
            try {
                http.newCall(request(source)).execute().use { response ->
                    if (!response.isSuccessful) {
                        failed = "Anbieter antwortet mit ${response.code}"
                        lastError = failed
                        return
                    }
                    val input = response.body.byteStream()
                    val chunk = ByteArray(64 * 1024)
                    while (!stopped && !closed) {
                        if (readers.get() == 0 && System.currentTimeMillis() - idleSince > IDLE_MS) break
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(lock) {
                            var off = 0
                            while (off < n) {
                                val at = ((written + off) % RING_BYTES).toInt()
                                val len = minOf(n - off, RING_BYTES - at)
                                System.arraycopy(chunk, off, ring, at, len)
                                off += len
                            }
                            written += n
                            fetched.addAndGet(n.toLong())
                            lock.notifyAll()
                        }
                    }
                }
            } catch (e: Exception) {
                if (written == 0L) {
                    failed = "Anbieter: ${e.javaClass.simpleName}"
                    lastError = failed
                }
            } finally {
                ended = true
                synchronized(lock) { lock.notifyAll() }
            }
        }

        /** Für einen Abruf: Strom ab kurz vor dem neuesten Stand an [out] geben, bis der Fernseher abbricht. */
        fun serve(out: OutputStream) {
            readers.incrementAndGet()
            try {
                // Auf die ersten Daten warten (bis 15 s).
                synchronized(lock) {
                    val until = System.currentTimeMillis() + 40_000
                    while (written == 0L && failed == null && !ended && !closed && System.currentTimeMillis() < until) lock.wait(250)
                }
                if (written == 0L) {
                    respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
                    return
                }
                respond(out, "200 OK", headers(null, seekable = false))
                // Etwas vor dem neuesten Stand beginnen (bis zu 1 MB), damit der Fernseher sofort genug hat; auf
                // TS-Pakete (188 Byte) ausgerichtet.
                var pos = maxOf(0L, written - BACKLOG_BYTES)
                pos -= pos % 188
                val chunk = ByteArray(64 * 1024)
                while (!closed && !stopped) {
                    var len: Int
                    synchronized(lock) {
                        while (written == pos && !ended && !closed && !stopped) lock.wait(500)
                        // Zu langsam gelesen, der Ring ist weiter: auf den ältesten noch vorhandenen Stand springen.
                        if (written - pos > RING_BYTES - chunk.size) {
                            pos = written - (RING_BYTES - chunk.size)
                            pos -= pos % 188
                        }
                        len = minOf((written - pos).toInt(), chunk.size)
                        var off = 0
                        while (off < len) {
                            val at = ((pos + off) % RING_BYTES).toInt()
                            val part = minOf(len - off, RING_BYTES - at)
                            System.arraycopy(ring, at, chunk, off, part)
                            off += part
                        }
                    }
                    if (len <= 0) {
                        if (ended) break else continue
                    }
                    out.write(chunk, 0, len)
                    out.flush()
                    bytes.addAndGet(len.toLong())
                    pos += len
                }
            } finally {
                if (readers.decrementAndGet() == 0) idleSince = System.currentTimeMillis()
            }
        }
    }

    private val feedLock = Any()
    private var feed: LiveFeed? = null

    private fun relayLive(out: OutputStream) {
        val current = synchronized(feedLock) {
            feed?.takeIf { !it.ended && it.failed == null } ?: LiveFeed().also { feed = it }
        }
        current.serve(out)
    }

    private fun relayHls(out: OutputStream) {
        var playlist = source
        val sent = LinkedHashSet<String>()
        var headerSent = false
        var first = true
        while (!closed) {
            val text = fetchText(playlist) ?: run {
                lastError = lastError ?: "Wiedergabeliste nicht erreichbar"
                if (!headerSent) respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
                return
            }
            val parsed = Hls.parse(text, playlist)
            if (parsed.variant != null) {
                playlist = parsed.variant
                continue
            }
            if (parsed.unsupported != null) {
                lastError = parsed.unsupported
                if (!headerSent) respond(out, "415 Unsupported Media Type", mapOf("Content-Length" to "0", "Connection" to "close"))
                return
            }
            if (!headerSent) {
                respond(out, "200 OK", headers(null, seekable = false))
                headerSent = true
            }
            // Beim Einstieg nur die letzten drei Segmente (Live-Kante), sonst liefe der Fernseher weit hinterher.
            val fresh = parsed.segments.filter { it !in sent }.let { if (first && parsed.live) it.takeLast(3) else it }
            first = false
            for (segment in fresh) {
                if (closed) return
                http.newCall(request(segment)).execute().use { response ->
                    if (response.isSuccessful) copy(response.body.byteStream(), out, closeAtEnd = false)
                }
                sent += segment
                if (sent.size > 200) sent.remove(sent.first())
            }
            out.flush()
            if (!parsed.live) return
            if (fresh.isEmpty()) Thread.sleep((parsed.targetSeconds * 500L).coerceIn(500L, 5_000L))
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        http.newCall(request(url)).execute().use { if (it.isSuccessful) it.body.string() else { lastError = "Anbieter antwortet mit ${it.code}"; null } }
    }.getOrElse { lastError = "Anbieter nicht erreichbar (${it.javaClass.simpleName})"; null }

    // ------------------------------------------------------------------ HLS als HLS (für LG webOS)

    private fun servePassHls(method: String, path: String, out: OutputStream) {
        if (method != "GET" && method != "HEAD") {
            respond(out, "405 Method Not Allowed", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        if (liveWindow && path.startsWith("/part/")) {
            if (method == "GET") requests.incrementAndGet()
            servePart(path, out, method == "HEAD")
            return
        }
        if (liveWindow && (path.startsWith("/stream.m3u8") || path.startsWith("/live.m3u8"))) {
            if (method == "GET") requests.incrementAndGet()
            val text = if (path.startsWith("/stream.m3u8")) {
                // Hauptliste mit fester Adresse der eigenen Liste und Auflösung (Vollbild auf dem LG).
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=$resolution\n$base/live.m3u8\n"
            } else windowPlaylist()
            sendText(out, text, "application/vnd.apple.mpegurl", method == "HEAD")
            return
        }
        if (tsWindow) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        val shadow = path.startsWith("/full.m3u8") || path.startsWith("/seg?s=1&")
        val target = if (path.startsWith("/seg")) {
            path.substringAfter("u=", "").substringBefore('&').let { java.net.URLDecoder.decode(it, "UTF-8") }
        } else source
        if (target.isEmpty() || !target.startsWith("http")) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        if (method == "GET" && !shadow) requests.incrementAndGet()
        val head = method == "HEAD"
        if (serveSubtitles(path, out, head)) return
        val playlist = target == source || target.substringBefore('?').lowercase().endsWith(".m3u8")
        if (playlist) servePlaylist(target, out, head, top = !path.startsWith("/seg") && !shadow, shadow = shadow)
        else serveSegment(target, out, head = head, forTv = !shadow)
    }

    /** Untertitel-Liste und -Stücke; false, wenn [path] keine Untertitel betrifft. */
    private fun serveSubtitles(path: String, out: OutputStream, head: Boolean): Boolean {
        remux?.let { return serveRemuxSubtitles(it, path, out, head) }
        subtitles?.let { subs ->
            when {
                path.startsWith("/subs/") -> {
                    // Ein Stück der Untertitel (je eine Minute), erst beim Abruf zusammengestellt: enthält, was bis
                    // dahin übersetzt ist.
                    val index = path.removePrefix("/subs/").substringBefore('.').toIntOrNull() ?: 0
                    val shift = (skippedSeconds * 1000).toLong()
                    val from = index * SUB_SEGMENT_MS
                    val until = from + SUB_SEGMENT_MS
                    val lines = runCatching { subs.cues() }.getOrDefault(emptyList())
                        .map { (a, b, t) -> Triple(a - shift, b - shift, t) }
                        .filter { it.second > from && it.first < until }
                    sendText(out, vtt(lines), "text/vtt; charset=utf-8", head)
                    return true
                }
                path.startsWith("/subs.m3u8") -> {
                    sendText(out, subtitlePlaylist(base, (subs.durationSeconds - skippedSeconds.toLong()).coerceAtLeast(1)), "application/vnd.apple.mpegurl", head)
                    return true
                }
            }
        }
        return false
    }

    // ------------------------------------------------------------------ Film umverpackt (RemuxSession)

    /**
     * Untertitel zum umverpackten Film: je Bild-Stück ein WebVTT-Stück (gleiche Grenzen). Beim Abruf zusammengestellt
     * aus dem, was gerade gewählt ist ([subtitles], laufend abgefragt) – ein Wechsel auf dem Handy gilt so ab den
     * nächsten Stücken, ohne dass der Fernseher neu öffnen muss.
     */
    private fun serveRemuxSubtitles(session: RemuxSession, path: String, out: OutputStream, head: Boolean): Boolean {
        when {
            path.startsWith("/subs.m3u8") -> {
                val first = session.first
                val text = buildString {
                    val target = (first until session.count).maxOfOrNull { session.boundaries[it + 1] - session.boundaries[it] } ?: 4.0
                    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(kotlin.math.ceil(target).toLong().coerceAtLeast(1)).append('\n')
                    append("#EXT-X-MEDIA-SEQUENCE:").append(first).append("\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                    for (i in first until session.count) {
                        append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", session.boundaries[i + 1] - session.boundaries[i])).append(",\n")
                        append("$base/subs/$i.vtt\n")
                    }
                    append("#EXT-X-ENDLIST\n")
                }
                sendText(out, text, "application/vnd.apple.mpegurl", head)
                return true
            }
            path.startsWith("/subs/") -> {
                val index = path.removePrefix("/subs/").substringBefore('.').toIntOrNull() ?: return false
                if (index !in 0 until session.count) return false
                if (!head) session.ensureCues(index)
                val from = ((session.boundaries[index] - session.origin) * 1000).toLong()
                val until = ((session.boundaries[index + 1] - session.origin) * 1000).toLong()
                val shift = (skippedSeconds * 1000).toLong()
                // Jede Zeile nur im Stück, in dem sie beginnt (sonst zeigten manche Fernseher sie an der Grenze doppelt);
                // im ersten Stück der Liste auch die, die schon laufen (Start nach dem Springen mitten in einer Zeile).
                val lines = runCatching { subtitles?.cues?.invoke() }.getOrNull().orEmpty()
                    .filter { if (index == session.first) it.second > from && it.first < until else it.first in from until until }
                    .map { (a, b, t) -> Triple(a - shift, b - shift, t) }
                val map = ((REMUX_PTS_OFFSET + session.origin + skippedSeconds) * 90_000).toLong()
                sendText(out, vtt(lines, map), "text/vtt; charset=utf-8", head)
                return true
            }
        }
        return false
    }

    private fun serveRemux(session: RemuxSession, method: String, path: String, out: OutputStream) {
        if (method != "GET" && method != "HEAD") {
            respond(out, "405 Method Not Allowed", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        val head = method == "HEAD"
        when {
            path.startsWith("/stream.m3u8") -> {
                if (!head) requests.incrementAndGet()
                sendText(out, remuxMaster(base, session.video, subtitles), "application/vnd.apple.mpegurl", head)
            }
            path.startsWith("/remux.m3u8") ->
                sendText(out, session.playlist { g, i -> "$base/r/$g/$i.ts" }, "application/vnd.apple.mpegurl", head)
            path.startsWith("/r/") -> {
                val parts = path.removePrefix("/r/").substringBefore('?').removeSuffix(".ts").split('/')
                val gen = parts.getOrNull(0)?.toIntOrNull()
                val index = parts.getOrNull(1)?.toIntOrNull()
                if (gen == null || index == null) {
                    respond(out, "404 Not Found", mapOf("Content-Length" to "0", "Connection" to "close"))
                    return
                }
                if (head) {
                    respond(out, "200 OK", mapOf("Content-Type" to "video/mp2t", "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0"))
                    return
                }
                requests.incrementAndGet()
                val data = session.segment(gen, index)
                if (data == null) {
                    lastError = session.lastError ?: "Stück ${index + 1} nicht erstellt"
                    respond(out, if (gen != session.generation) "410 Gone" else "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
                    return
                }
                lastError = null
                respond(
                    out, "200 OK",
                    mapOf(
                        "Content-Type" to "video/mp2t", "Content-Length" to data.size.toString(),
                        "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0",
                    ),
                )
                out.write(data)
                out.flush()
                bytes.addAndGet(data.size.toLong())
            }
            serveSubtitles(path, out, head) -> Unit
            else -> respond(out, "404 Not Found", mapOf("Content-Length" to "0", "Connection" to "close"))
        }
    }

    // ------------------------------------------------------------------ Live: eigene Liste, Senderwechsel ohne Neustart

    /**
     * Live über HLS zum Fernseher: Das Handy führt eine eigene, fortlaufende Liste (`/live.m3u8`) aus den Stücken des
     * Senders. Beim Umschalten ([switchTo]) kommen die Stücke des neuen Senders mit einer Bruchstelle dazu – der
     * Mediaplayer des Fernsehers läuft weiter, mit denselben Einstellungen (Vollbild), ohne neu geöffnet zu werden.
     */
    val liveWindow: Boolean get() = (passHls || tsWindow) && live

    /** Veröffentlichter Teil der eigenen Liste (etwa 1 s, siehe [TsSplitter]). */
    private class WindowPart(val seq: Long, val duration: Double, val data: ByteArray, val discontinuity: Boolean)
    private val window = ArrayList<WindowPart>()
    /** Schon geholt, noch nicht veröffentlicht: geht im Takt der Echtzeit in die Liste (siehe [feedLoop]). */
    private val backlog = ArrayDeque<Pair<TsSplitter.Part, Boolean>>()
    /** Zuletzt veröffentlichte Teile nach Nummer (auch kurz nach dem Herausfallen aus der Liste noch abrufbar). */
    private val recentParts = object : LinkedHashMap<Long, WindowPart>(64, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, WindowPart>?) = size > 48
    }
    private var nextSeq = 0L
    private var discontinuitySeq = 0L
    private var pendingDiscontinuity = false
    @Volatile private var switchGeneration = 0
    @Volatile private var feeder: Thread? = null
    private val tableCounters = HashMap<Int, Int>()
    /** Höchste Nummer, die der Fernseher abgeholt hat (-1 = noch keine). */
    private var lastServedSeq = -1L
    /** Gerade laufender Abruf eines Stücks (beim Umschalten abgebrochen: die eine Verbindung gehört dem neuen Sender). */
    @Volatile private var feedCall: okhttp3.Call? = null

    /**
     * Anderen Sender in dieselbe Liste: Der Mediaplayer des Fernsehers läuft weiter und spielt nach etwa 1–3 s den neuen.
     *
     * Teile, die der Fernseher schon abgeholt hat, bleiben (er spielt sie noch zu Ende). Die übrigen des alten Senders
     * fallen weg, und ihre Nummern bekommt der neue Sender: So fragt der Fernseher als Nächstes genau nach dessen erstem
     * Teil. Vorher liefen die Nummern weiter, und der LG wartete auf einen Teil, den es nicht mehr gab – er hing, bis man
     * ihn neu startete.
     */
    /** Erstes Stück des Senders nach dem letzten [switchTo] (-1: noch nie umgeschaltet). */
    private var switchFirstSeq = -1L

    /** Nach [switchTo]: das erste Stück des neuen Senders liegt für den Fernseher bereit. */
    val switchPartReady: Boolean get() = synchronized(window) { switchFirstSeq >= 0 && window.any { it.seq >= switchFirstSeq } }

    /** Nach [switchTo]: Der Fernseher hat schon ein Stück des neuen Senders abgeholt (er folgt dem Umschalten). */
    val switchFollowed: Boolean get() = synchronized(window) { switchFirstSeq >= 0 && lastServedSeq >= switchFirstSeq }

    fun switchTo(newSource: String) {
        synchronized(window) {
            source = newSource
            feedCall?.cancel()
            val served = lastServedSeq
            val drop = window.filter { it.seq > served }
            window.removeAll(drop.toSet())
            drop.forEach { recentParts.remove(it.seq) }
            drop.minOfOrNull { it.seq }?.let { nextSeq = it }
            backlog.clear()
            pendingDiscontinuity = true
            switchFirstSeq = nextSeq
            switchGeneration++
            (window as Object).notifyAll()
        }
        lastError = null
    }

    /**
     * Läuft der Fernseher zu weit hinter dem Rand der Liste (nach Hängern holt er nur noch nach, statt aufzuholen), die
     * noch nicht abgeholten Teile bis auf die letzten [KEEP_AHEAD_SECONDS] überspringen – mit Bruchstelle und den Nummern
     * direkt nach dem zuletzt abgeholten. Unter [window] gesperrt.
     */
    private fun catchUp() {
        val ahead = window.filter { it.seq > lastServedSeq }
        if (lastServedSeq < 0 || ahead.sumOf { it.duration } <= MAX_TV_LAG_SECONDS) return
        val keep = ArrayList<WindowPart>()
        var kept = 0.0
        for (part in ahead.asReversed()) {
            if (kept >= KEEP_AHEAD_SECONDS) break
            keep.add(0, part)
            kept += part.duration
        }
        window.removeAll(ahead.toSet())
        ahead.forEach { recentParts.remove(it.seq) }
        var seq = lastServedSeq + 1
        for ((k, part) in keep.withIndex()) {
            val moved = WindowPart(seq++, part.duration, part.data, discontinuity = k == 0 || part.discontinuity)
            window += moved
            recentParts[moved.seq] = moved
        }
        nextSeq = seq
        caughtUpSeconds += ahead.sumOf { it.duration } - kept
    }

    /** So viele Sekunden hat [catchUp] übersprungen (für die Anzeige). */
    @Volatile var caughtUpSeconds = 0.0; private set

    /**
     * Live über die eigene Liste: schon vor dem Öffnen am Fernseher anfangen zu laden. Wird der Fernseher erst geöffnet,
     * wenn [windowReady], bekommt er sofort eine spielbare Liste (über den Proxy dauert das erste Stück bei HD/4K
     * 5–30 s; eine leere oder lange hängende Liste nimmt der LG-Mediaplayer als Fehler).
     */
    fun prepareWindow() {
        if (liveWindow) ensureFeeder()
    }

    val isClosed: Boolean get() = closed

    /** Erster Teil der eigenen Liste ist veröffentlicht. */
    val windowReady: Boolean get() = synchronized(window) { window.isNotEmpty() }

    private fun ensureFeeder() {
        synchronized(window) {
            if (feeder != null || closed) return
            feeder = thread(isDaemon = true, name = "dlna-relay-live") { feedLoop() }
        }
    }

    /**
     * Holt laufend die neuen Stücke des Senders, teilt sie in kurze Teile und gibt diese im Takt der Echtzeit frei.
     * So bleibt der Fernseher nur wenige Sekunden hinter dem Rand der Liste (sein Puffer ist klein) – beim Umschalten
     * spielt er nur noch diese wenigen Sekunden des alten Senders. Den Takt der Stücke des Anbieters (6–10 s) fängt der
     * zurückgehaltene Vorrat auf, ohne dass der Fernseher hängt.
     */
    private fun feedLoop() {
        var generation = -1
        var mediaUrl: String? = null
        val seen = LinkedHashSet<String>()
        var lastList = 0L
        var listInterval = 1_000L
        while (!closed) {
            if (generation != switchGeneration) {
                generation = switchGeneration
                mediaUrl = null
                seen.clear()
                lastList = 0L
                synchronized(window) { nextPublish = 0L }
            }
            // Sender ohne HLS (durchgehender TS-Strom): eine Verbindung, laufend in Teile zerlegt.
            if (!source.substringBefore('?').lowercase().endsWith(".m3u8")) {
                feedTs(generation)
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastList >= listInterval) {
                lastList = now
                val currentSource = source
                var url = mediaUrl ?: currentSource
                var loaded = fetchList(url)
                if (loaded != null && loaded.first.contains("#EXT-X-STREAM-INF")) {
                    val best = bestVariantOnly(loaded.first).lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    url = best?.let { runCatching { java.net.URI(loaded!!.second).resolve(it.trim()).toString() }.getOrNull() } ?: url
                    loaded = fetchList(url)
                }
                if (loaded != null && generation == switchGeneration) {
                    mediaUrl = url
                    val parent = runCatching { java.net.URI(loaded.second) }.getOrNull()
                    val found = ArrayList<Pair<Double, String>>()
                    var duration = 0.0
                    for (line in loaded.first.lines()) {
                        when {
                            line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                            line.startsWith("#EXT-X-TARGETDURATION:") ->
                                listInterval = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 2.0) * 500).toLong().coerceIn(1_000, 4_000)
                            line.isNotBlank() && !line.startsWith("#") -> {
                                val absolute = runCatching { parent?.resolve(line.trim())?.toString() }.getOrNull() ?: line.trim()
                                found += duration to absolute
                            }
                        }
                    }
                    val starting = seen.isEmpty()
                    val fresh = found.filter { it.second !in seen }
                    fresh.forEach { seen += it.second }
                    while (seen.size > 500) seen.remove(seen.first())
                    // Beim Start und nach dem Umschalten: die letzten zwei Stücke. Das erste geht sofort raus, sobald es
                    // ganz geholt ist (über den Proxy bei HD/4K 5–20 s – so lange wartet der Start jetzt), das zweite
                    // lädt, während der Fernseher das erste spielt.
                    val take = if (starting) fresh.takeLast(2) else fresh
                    for ((index, segment) in take.withIndex()) {
                        if (closed || generation != switchGeneration) break
                        // Schon beim Laden in Teile (je etwa 1 s): Beim Start geht jeder gleich raus, sobald er da ist –
                        // der Fernseher bekommt nach dem Umschalten so nach gut einer Sekunde Bild das erste.
                        downloadParts(segment.second, segment.first, generation) { part ->
                            synchronized(window) {
                                if (generation != switchGeneration) return@synchronized
                                val discontinuity = pendingDiscontinuity
                                pendingDiscontinuity = false
                                if (starting && index == 0) publish(part, discontinuity) else backlog.addLast(part to discontinuity)
                                (window as Object).notifyAll()
                            }
                        }
                    }
                }
            }
            publishDue()
            runCatching { Thread.sleep(150) }
        }
    }

    /**
     * Live-TS in die eigene Liste: eine Verbindung zum Anbieter, der Strom wird beim Lesen in Teile (~1 s, an
     * Schlüsselbildern) zerlegt. Der erste Teil geht sofort raus, die weiteren im Takt der Echtzeit. Bricht der Strom ab,
     * wird neu verbunden (mit Bruchstelle). Kehrt zurück, wenn umgeschaltet oder geschlossen wurde.
     */
    private fun feedTs(generation: Int) {
        var starting = true
        while (!closed && generation == switchGeneration) {
            val ok = runCatching {
                val call = http.newCall(request(source))
                feedCall = call
                if (generation != switchGeneration) {
                    call.cancel()
                    return
                }
                call.execute().use { r ->
                    if (!r.isSuccessful) {
                        lastError = "Anbieter antwortet mit ${r.code}"
                        return@use false
                    }
                    lastError = null
                    val splitter = TsSplitter.Progressive(TsSplitter.TARGET_SECONDS)
                    val input = r.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (!closed && generation == switchGeneration) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        fetched.addAndGet(n.toLong())
                        for (part in splitter.feed(buffer, 0, n)) synchronized(window) {
                            if (generation == switchGeneration) {
                                val discontinuity = pendingDiscontinuity
                                pendingDiscontinuity = false
                                if (starting) {
                                    publish(part, discontinuity)
                                    starting = false
                                } else backlog.addLast(part to discontinuity)
                                (window as Object).notifyAll()
                            }
                        }
                        publishDue()
                    }
                    true
                }
            }.getOrElse {
                if (generation == switchGeneration && !closed) lastError = "Strom abgebrochen (${it.javaClass.simpleName})"
                false
            }
            if (closed || generation != switchGeneration) return
            // Abgebrochen: neu verbinden, der neue Strom setzt mit einer Bruchstelle an.
            synchronized(window) { pendingDiscontinuity = true }
            runCatching { Thread.sleep(if (ok) 300L else 1_500L) }
        }
    }

    /** Wann der nächste zurückgehaltene Teil frei wird (unter [window]). */
    private var nextPublish = 0L

    /**
     * Zurückgehaltene Teile im Takt der Echtzeit freigeben. Wächst der Vorrat zu sehr (Anbieter lieferte stoßweise),
     * schneller. Auch während ein Stück lädt (aus [downloadParts]): Sonst stand der Fernseher so lange ohne neuen Teil da.
     */
    private fun publishDue() {
        synchronized(window) {
            val t = System.currentTimeMillis()
            val waiting = backlog.sumOf { it.first.seconds }
            while (backlog.isNotEmpty() && (t >= nextPublish || waiting > MAX_BACKLOG_SECONDS)) {
                val (part, discontinuity) = backlog.removeFirst()
                publish(part, discontinuity)
                nextPublish = maxOf(t, nextPublish) + (part.seconds * 1000).toLong()
                if (waiting <= MAX_BACKLOG_SECONDS) break
            }
            (window as Object).notifyAll()
        }
    }

    /** Teil in die Liste (unter [window] gesperrt). */
    private fun publish(part: TsSplitter.Part, discontinuity: Boolean) {
        TsSplitter.renumberTables(part.data, tableCounters)
        val entry = WindowPart(nextSeq++, part.seconds, part.data, discontinuity)
        window += entry
        recentParts[entry.seq] = entry
        while (window.size > 24) {
            if (window.first().discontinuity) discontinuitySeq++
            window.removeAt(0)
        }
    }

    /**
     * Stück laden und schon dabei in Teile zerlegen ([TsSplitter.Progressive]); jeden fertigen Teil an [onPart]. Wird
     * umgeschaltet ([generation] veraltet), bricht der Abruf sofort ab, statt das alte Stück zu Ende zu laden.
     */
    private fun downloadParts(url: String, seconds: Double, generation: Int, onPart: (TsSplitter.Part) -> Unit): Boolean = runCatching {
        val call = http.newCall(request(url))
        feedCall = call
        if (generation != switchGeneration) {
            call.cancel()
            return false
        }
        call.execute().use { r ->
            if (!r.isSuccessful) {
                lastError = "Anbieter antwortet mit ${r.code}"
                return@use false
            }
            val splitter = TsSplitter.Progressive(seconds)
            val input = r.body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (closed || generation != switchGeneration) return@use false
                val n = input.read(buffer)
                if (n < 0) break
                // Mitzählen: Die Anzeige zeigt so schon während des Ladens, dass Daten kommen.
                fetched.addAndGet(n.toLong())
                splitter.feed(buffer, 0, n).forEach(onPart)
                publishDue()
            }
            if (closed || generation != switchGeneration) return@use false
            splitter.finish().forEach(onPart)
            true
        }
    }.getOrElse {
        if (generation == switchGeneration && !closed) lastError = "Stück nicht geladen (${it.javaClass.simpleName})"
        false
    }.also { if (feedCall != null && generation == switchGeneration) feedCall = null }

    /** Ein Teil der eigenen Liste an den Fernseher. */
    private fun servePart(path: String, out: OutputStream, head: Boolean) {
        val seq = path.removePrefix("/part/").substringBefore('.').toLongOrNull()
        val part = seq?.let {
            synchronized(window) {
                recentParts[it]?.also { found ->
                    if (!head && found.seq > lastServedSeq) {
                        lastServedSeq = found.seq
                        catchUp()
                    }
                }
            }
        }
        if (part == null) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        respond(
            out, "200 OK",
            mapOf("Content-Type" to "video/mp2t", "Content-Length" to part.data.size.toString(), "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0"),
        )
        if (head) return
        out.write(part.data)
        out.flush()
        bytes.addAndGet(part.data.size.toLong())
    }

    private fun fetchList(url: String): Pair<String, String>? = runCatching {
        http.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) {
                lastError = "Anbieter antwortet mit ${r.code}"
                null
            } else r.body.string().also { fetched.addAndGet(it.length.toLong()) } to r.request.url.toString()
        }
    }.getOrElse {
        lastError = "Anbieter nicht erreichbar (${it.javaClass.simpleName})"
        null
    }

    private fun windowPlaylist(): String {
        ensureFeeder()
        return synchronized(window) {
            // Erster Abruf: kurz warten, bis die ersten Teile da sind (eine leere Liste nähme der Fernseher als Ende).
            // Ganz am Anfang länger (das Handy öffnet den Fernseher erst nach dem ersten Stück, das ist nur die Reserve),
            // nach dem Umschalten wie bisher: Der Fernseher fragt dann ohnehin gleich wieder.
            val until = System.currentTimeMillis() + if (nextSeq == 0L) 25_000 else 15_000
            while (window.isEmpty() && !closed && System.currentTimeMillis() < until) (window as Object).wait(250)
            val target = (window.maxOfOrNull { it.duration } ?: 2.0).let { kotlin.math.ceil(it).toLong().coerceAtLeast(1) }
            buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n")
                append("#EXT-X-MEDIA-SEQUENCE:${window.firstOrNull()?.seq ?: nextSeq}\n")
                append("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitySeq\n")
                for (part in window) {
                    if (part.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                    append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", part.duration)).append(",\n")
                    append("$base/part/${part.seq}.ts\n")
                }
            }
        }
    }

    private fun sendText(out: OutputStream, text: String, type: String, head: Boolean) {
        val body = text.toByteArray(Charsets.UTF_8)
        respond(
            out, "200 OK",
            mapOf("Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0"),
        )
        if (!head) {
            out.write(body)
            out.flush()
        }
    }

    private fun servePlaylist(url: String, out: OutputStream, head: Boolean, top: Boolean = false, shadow: Boolean = false) {
        val loaded: Pair<String, String>? = runCatching {
            http.newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) {
                    lastError = "Anbieter antwortet mit ${r.code}"
                    null
                } else r.body.string() to r.request.url.toString()
            }
        }.getOrElse {
            lastError = "Anbieter nicht erreichbar (${it.javaClass.simpleName})"
            null
        }
        if (loaded == null) {
            respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        fetched.addAndGet(loaded.first.length.toLong())
        val trimmed = if (startSeconds > 0 && !shadow) startAt(loaded.first, startSeconds)?.also { (_, skipped) -> skippedSeconds = skipped }?.first ?: loaded.first else loaded.first
        val rewritten = rewritePlaylist(trimmed, loaded.second, base, shadow)
        // Erste Liste mit Untertiteln: Untertitelspur dazu (bei einer reinen Stückliste eine Hauptliste darum herum).
        // Erste Liste: immer als Hauptliste mit Auflösung (mindestens Full HD) – der Mediaplayer des LG zeigte reine
        // Stücklisten in 720p in Originalgröße, mit breiten Rändern; mit Angabe zieht er das Bild auf den Bildschirm.
        // Dazu, falls vorhanden, die Untertitelspur.
        val text = if (top) masterFor(rewritten, base, source, subtitles, resolution) else rewritten
        val body = text.toByteArray(Charsets.UTF_8)
        respond(
            out, "200 OK",
            mapOf(
                "Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0",
            ),
        )
        if (!head) {
            out.write(body)
            out.flush()
        }
    }

    /**
     * Ein Stück an den Fernseher oder den mitlesenden Player: einmal beim Anbieter geholt (ganz, in den Speicher), dann
     * für beide aus dem Zwischenspeicher. Wer zuerst fragt, holt; der andere wartet auf denselben Abruf.
     */
    private fun serveSegment(url: String, out: OutputStream, head: Boolean, forTv: Boolean = true) {
        val (future, mine) = synchronized(segments) {
            segments[url]?.let { return@synchronized java.util.concurrent.CompletableFuture.completedFuture<Pair<ByteArray, String>?>(it) to false }
            loading[url]?.let { return@synchronized it to false }
            val created = java.util.concurrent.CompletableFuture<Pair<ByteArray, String>?>()
            loading[url] = created
            created to true
        }
        if (mine) {
            val result = runCatching {
                http.newCall(request(url)).execute().use { r ->
                    if (!r.isSuccessful) {
                        lastError = "Anbieter antwortet mit ${r.code}"
                        null
                    } else {
                        val data = r.body.bytes()
                        fetched.addAndGet(data.size.toLong())
                        data to (r.header("Content-Type")?.takeIf { it.isNotBlank() } ?: "video/mp2t")
                    }
                }
            }.onFailure { lastError = "Stück nicht geladen (${it.javaClass.simpleName})" }.getOrNull()
            synchronized(segments) {
                loading.remove(url)
                if (result != null) segments[url] = result
            }
            future.complete(result)
        }
        val segment = runCatching { future.get(30, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        if (segment == null) {
            respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        respond(
            out, "200 OK",
            mapOf(
                "Content-Type" to segment.second, "Content-Length" to segment.first.size.toString(),
                "Cache-Control" to "no-cache", "Connection" to "close", "Server" to "DnA-TV/1.0",
            ),
        )
        if (head) return
        out.write(segment.first)
        out.flush()
        if (forTv) bytes.addAndGet(segment.first.size.toLong())
    }

    private fun headers(length: Long?, seekable: Boolean): Map<String, String> = buildMap {
        // Verpackte Datei: die Datei selbst mit ihrem echten Typ (die Liste ist HLS).
        put("Content-Type", if (wrap) DlnaCast.mimeOf(source, live) else mime)
        length?.let { put("Content-Length", it.toString()) }
        put("Accept-Ranges", if (seekable) "bytes" else "none")
        put("transferMode.dlna.org", "Streaming")
        put("contentFeatures.dlna.org", DlnaCast.features(seekable))
        put("Cache-Control", "no-cache")
        put("Connection", "close")
        put("Server", "DnA-TV DLNA/1.0")
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, closeAtEnd: Boolean = true) {
        val buffer = ByteArray(64 * 1024)
        while (!closed) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            bytes.addAndGet(n.toLong())
        }
        if (closeAtEnd) out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8_192) return null
            line.append(c.toChar())
        }
    }

    private fun extension(mime: String) = when (mime) {
        "video/mp4" -> "mp4"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        else -> "ts"
    }

    companion object {
        /**
         * Feste Stückliste (Film, mit #EXT-X-ENDLIST) ab [seconds]: Stücke, die davor enden, fallen weg. Liefert die Liste
         * und wie viele Sekunden weggefallen sind; null bei Live-Listen, Hauptlisten oder wenn nichts wegfällt.
         */
        internal fun startAt(text: String, seconds: Double): Pair<String, Double>? {
            if (!text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) return null
            val lines = text.lines()
            val out = ArrayList<String>()
            var time = 0.0
            var skipped = 0.0
            var dropped = 0
            var pendingInf: String? = null
            var pendingDuration = 0.0
            var started = false
            for (line in lines) {
                when {
                    line.startsWith("#EXTINF:") -> {
                        pendingInf = line
                        pendingDuration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    }
                    pendingInf != null && line.isNotBlank() && !line.startsWith("#") -> {
                        val end = time + pendingDuration
                        if (!started && end <= seconds) {
                            skipped = end
                            dropped++
                        } else {
                            started = true
                            out += pendingInf
                            out += line
                        }
                        time = end
                        pendingInf = null
                    }
                    pendingInf != null -> if (started) out += line // Zusatzzeilen eines Stücks (#EXT-X-BYTERANGE …)
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> out += line // unten angepasst
                    else -> out += line
                }
            }
            if (dropped == 0) return null
            val withSequence = out.map { l ->
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) "#EXT-X-MEDIA-SEQUENCE:" + ((l.substringAfter(':').trim().toLongOrNull() ?: 0) + dropped) else l
            }
            return withSequence.joinToString("\n") to skipped
        }

        /** Untertitelspur für eine Hauptliste. */
        internal fun subtitleMedia(base: String, subs: HlsSubtitles): String {
            fun clean(v: String) = v.replace("\"", "'")
            return "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${clean(subs.name)}\",LANGUAGE=\"${clean(subs.language)}\"," +
                "DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,URI=\"$base/subs.m3u8\""
        }

        /** HLS-Liste mit der ganzen Film-Datei als einzigem Stück. */
        internal fun wrapPlaylist(base: String, seconds: Long, ext: String): String =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$seconds\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
                "#EXTINF:$seconds.0,\n$base/film.$ext\n#EXT-X-ENDLIST\n"

        /**
         * Hauptliste für den Fernseher: Eine reine Stückliste bekommt eine Hauptliste mit Auflösung 1920×1080 darum; in
         * einer Hauptliste bekommt die Fassung ohne Auflösung diese Angabe. Mit [subs] auch die Untertitelspur.
         */
        internal fun masterFor(playlist: String, base: String, source: String, subs: HlsSubtitles?, resolution: String = "1920x1080"): String {
            if (!playlist.contains("#EXT-X-STREAM-INF")) {
                val bandwidth = if (resolution.startsWith("3840")) 25_000_000 else 8_000_000
                return "#EXTM3U\n#EXT-X-VERSION:3\n" + (subs?.let { subtitleMedia(base, it) + "\n" } ?: "") +
                    "#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=$resolution" + (if (subs != null) ",SUBTITLES=\"subs\"" else "") + "\n" +
                    "$base/seg?u=" + java.net.URLEncoder.encode(source, "UTF-8") + "\n"
            }
            val withResolution = playlist.lines().joinToString("\n") { line ->
                if (line.startsWith("#EXT-X-STREAM-INF") && !line.contains("RESOLUTION=")) "$line,RESOLUTION=$resolution" else line
            }
            return if (subs != null) withSubtitles(withResolution, base, source, subs) else withResolution
        }

        /** Wiedergabeliste mit Untertitelspur: Hauptliste ergänzen oder eine Hauptliste um die Stückliste legen. */
        internal fun withSubtitles(playlist: String, base: String, source: String, subs: HlsSubtitles): String {
            fun clean(v: String) = v.replace("\"", "'")
            val media = "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${clean(subs.name)}\",LANGUAGE=\"${clean(subs.language)}\"," +
                "DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,URI=\"$base/subs.m3u8\""
            return if (playlist.contains("#EXT-X-STREAM-INF")) {
                playlist.lines().joinToString("\n") { line ->
                    when {
                        line.startsWith("#EXTM3U") -> "$line\n$media"
                        line.startsWith("#EXT-X-STREAM-INF") -> "$line,SUBTITLES=\"subs\""
                        else -> line
                    }
                }
            } else {
                // Stückliste: die Hauptliste zeigt auf sie (über das Handy) und auf die Untertitel.
                "#EXTM3U\n#EXT-X-VERSION:3\n$media\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,SUBTITLES=\"subs\"\n" +
                    "$base/seg?u=" + java.net.URLEncoder.encode(source, "UTF-8") + "\n"
            }
        }

        /** Länge eines Untertitel-Stücks. */
        internal const val SUB_SEGMENT_MS = 60_000L

        /** Untertitel-Liste: je Minute ein WebVTT-Stück (der Fernseher holt sie nach und nach, passend zum Bild). */
        internal fun subtitlePlaylist(base: String, durationSeconds: Long): String {
            val length = durationSeconds.coerceAtLeast(1)
            val step = SUB_SEGMENT_MS / 1000
            val count = ((length + step - 1) / step).toInt()
            return buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$step\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                for (i in 0 until count) {
                    val seconds = minOf(step, length - i * step)
                    append("#EXTINF:$seconds.0,\n$base/subs/$i.vtt\n")
                }
                append("#EXT-X-ENDLIST\n")
            }
        }

        /**
         * Hauptliste für einen umverpackten Film: eine Fassung (mit Auflösung, mindestens Full HD, siehe [masterFor]),
         * dazu die Untertitelspur. Die Tonspur steckt in den Stücken.
         */
        internal fun remuxMaster(base: String, video: Remux.Video?, subs: HlsSubtitles?): String {
            val w = video?.width ?: 0
            val h = video?.height ?: 0
            val resolution = if (w >= 1920 || h >= 1080) "${w}x$h" else "1920x1080"
            return buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n")
                subs?.let { append(subtitleMedia(base, it)).append('\n') }
                append("#EXT-X-STREAM-INF:BANDWIDTH=").append(if (w >= 3000) 40_000_000 else 12_000_000)
                append(",RESOLUTION=").append(resolution)
                if (subs != null) append(",SUBTITLES=\"subs\"")
                append('\n')
                append("$base/remux.m3u8\n")
            }
        }

        /** Untertitelzeilen (Beginn, Ende in ms, Text) als WebVTT; mit [mpegts] die Zuordnung zu den Zeitstempeln der Stücke. */
        fun vtt(cues: List<Triple<Long, Long, String>>, mpegts: Long? = null): String = buildString {
            append("WEBVTT\n")
            mpegts?.let { append("X-TIMESTAMP-MAP=MPEGTS:").append(it).append(",LOCAL:00:00:00.000\n") }
            append('\n')
            fun time(ms: Long): String {
                val t = ms.coerceAtLeast(0)
                return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
            }
            for ((start, end, text) in cues) {
                if (text.isBlank() || end <= start) continue
                append(time(start)).append(" --> ").append(time(end)).append('\n')
                append(text.replace("-->", "->").trim()).append("\n\n")
            }
        }

        /**
         * Hauptliste mit mehreren Fassungen (1080p, 720p, 360p …): nur die beste behalten (höchste Auflösung, dann Bitrate).
         * Der Mediaplayer des LG nahm sonst bei manchen Sendern eine kleine Fassung und zeigte sie mit breiten schwarzen
         * Rändern. Tonspuren und Untertitel (#EXT-X-MEDIA) bleiben.
         */
        internal fun bestVariantOnly(text: String): String {
            val lines = text.lines()
            val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
            if (variants.size < 2) return text
            fun attr(line: String, name: String) = Regex("""(?:^|[:,])$name=([^,]+)""").find(line)?.groupValues?.get(1)
            fun pixels(line: String) = attr(line, "RESOLUTION")?.split('x')?.let { (it.getOrNull(0)?.toLongOrNull() ?: 0) * (it.getOrNull(1)?.toLongOrNull() ?: 0) } ?: 0L
            fun bandwidth(line: String) = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
            val best = variants.maxWith(compareBy<Int>({ pixels(lines[it]) }, { bandwidth(lines[it]) }))
            // Adresse der Fassung: die nächste Zeile, die kein Kommentar ist.
            val uriOf = { i: Int -> (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].startsWith("#") } }
            val drop = HashSet<Int>()
            for (v in variants) if (v != best) {
                drop += v
                uriOf(v)?.let { drop += it }
            }
            // Auch die Nur-Bild-Fassungen (#EXT-X-I-FRAME-STREAM-INF) braucht der Fernseher nicht.
            lines.indices.filter { lines[it].startsWith("#EXT-X-I-FRAME-STREAM-INF") }.forEach { drop += it }
            return lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n")
        }

        /**
         * Wiedergabeliste für den Fernseher: Jede Adresse (Stücke, Unterlisten, Schlüssel, `URI="…"`) zeigt auf das Handy
         * ([base]/seg?u=…), das sie über den Proxy der App beim Anbieter holt. Relative Adressen gelten zu [listUrl].
         */
        fun rewritePlaylist(text: String, listUrl: String, base: String, shadow: Boolean = false): String {
            val source = bestVariantOnly(text)
            val parent = runCatching { java.net.URI(listUrl) }.getOrNull()
            fun proxied(ref: String): String {
                val absolute = runCatching { parent?.resolve(ref.trim())?.toString() }.getOrNull() ?: ref.trim()
                return "$base/seg?" + (if (shadow) "s=1&" else "") + "u=" + java.net.URLEncoder.encode(absolute, "UTF-8")
            }
            val uriAttr = Regex("URI=\"([^\"]+)\"")
            return source.lineSequence().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> uriAttr.replace(line) { "URI=\"" + proxied(it.groupValues[1]) + "\"" }
                    else -> proxied(line)
                }
            }
        }
    }

    /** Lesen einer HLS-Wiedergabeliste (nur, was die Weiterleitung braucht). */
    object Hls {
        class Parsed(
            /** Bei einer Hauptliste: die Fassung mit der höchsten Bitrate. */
            val variant: String?,
            val segments: List<String>,
            val targetSeconds: Int,
            val live: Boolean,
            /** Grund, warum diese Liste nicht als TS weitergegeben werden kann (verschlüsselt, fMP4). */
            val unsupported: String?,
        )

        fun parse(text: String, base: String): Parsed {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            fun resolve(path: String) = runCatching { java.net.URI(base).resolve(path).toString() }.getOrDefault(path)
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                var best: Pair<Long, String>? = null
                lines.forEachIndexed { i, line ->
                    if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                    if (best == null || bandwidth > best!!.first) best = bandwidth to resolve(uri)
                }
                return Parsed(best?.second, emptyList(), 0, true, if (best == null) "Keine abspielbare Fassung in der Liste" else null)
            }
            val unsupported = when {
                lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") } -> "Verschlüsselter Stream (HLS mit Schlüssel)"
                lines.any { it.startsWith("#EXT-X-MAP") } -> "Stream im Format fMP4 (nicht als TS weiterzugeben)"
                else -> null
            }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toDoubleOrNull()?.toInt() ?: 6
            val segments = lines.filter { !it.startsWith("#") }.map { resolve(it) }
            return Parsed(null, segments, target, lines.none { it.startsWith("#EXT-X-ENDLIST") }, unsupported)
        }
    }
}

/** Fester Versatz der Zeitstempel in umverpackten Stücken (wie DNA_PTS_OFFSET in dnaremux.h). */
internal const val REMUX_PTS_OFFSET = 10.0

/** Live über HLS (LG): so viele Sekunden Vorrat höchstens zurückhalten, danach schneller freigeben. */
private const val MAX_BACKLOG_SECONDS = 20.0
/** Liegt der Fernseher mehr als so viele Sekunden hinter dem Rand der Liste, springt er nach vorn ([DlnaRelay.catchUp]). */
private const val MAX_TV_LAG_SECONDS = 15.0
private const val KEEP_AHEAD_SECONDS = 4.0

/** Ringpuffer für Live-Weiterleitung: 8 MB (bei 20 Mbit/s gut 3 s), neue Abrufe beginnen bis 1 MB vor dem neuesten Stand. */
private const val RING_BYTES = 8 * 1024 * 1024
private const val BACKLOG_BYTES = 1024 * 1024L
/** So lange bleibt die Verbindung zum Anbieter ohne Abrufer offen (der Fernseher ruft gleich wieder ab). */
private const val IDLE_MS = 20_000L

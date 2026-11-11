package de.ayutv.player.dna.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Lesen für die DNA Engine – gleich auf Android und Windows: Live-TV als MPEG-TS (Neuverbinden, Zeit lückenlos), TS-Dateien,
 * MKV/WebM und MP4 (HTTP-Range, Spulen über das Verzeichnis), HLS (Live und Filme, AES-128, TS- und fMP4-Abschnitte).
 * Alle Zeiten werden auf eine Medienzeit ab 0 gebracht. Die Proben gehen an den [Sink] (die Wiedergabe der Plattform).
 */
class DnaReader(private val http: OkHttpClient, private val config: Config, private val sink: Sink) {
    class Config(
        val liveBufferUs: Long = 8_000_000,
        val vodBufferUs: Long = 30_000_000,
        val maxVideoHeight: Int = 2160,
        val maxBandwidth: Long = 0,
        /** Live: Netz-Puffer vor dem TS-Leser (Bytes), siehe [NetworkPrefetch]. */
        val prefetchBytes: Int = 16 * 1024 * 1024,
    )

    interface Sink {
        fun alive(gen: Int): Boolean
        fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean)
        fun onTracks(tracks: List<Track>)
        /** Probe in Medienzeit. */
        fun onSample(sample: Sample, gen: Int)
        fun onEnded()
        fun onError(message: String, fatal: Boolean)
        fun onInfo(text: String)
        /** Live neu verbunden: Decoder frisch ab dem nächsten Schlüsselbild. */
        fun onReconnect()
        /** Blockiert, solange der Vorrat voll ist. */
        fun awaitRoom(gen: Int, limitUs: Long)
        /** Ende der bisher gelesenen Medienzeit. */
        val lastMediaUs: Long
    }

    private enum class SourceKind { LIVE_TS, TS_FILE, CONTAINER, HLS }

    @Volatile private var url: String? = null
    @Volatile private var reconnectUrl: String? = null
    @Volatile private var userAgent: String? = null
    @Volatile private var liveHint = true
    @Volatile private var call: okhttp3.Call? = null
    private val sourceLock = Object()
    private var container: ContainerDemuxer? = null
    @Volatile private var byteSource: ByteSource? = null
    private var sourceKind: SourceKind? = null
    private var hls: HlsLoader? = null
    @Volatile private var tsFileBaseUs = Long.MIN_VALUE
    /** Zeitbasis der Quelle (TS/HLS): Medienzeit = PTS − Basis. */
    @Volatile private var timeBaseUs = Long.MIN_VALUE
    @Volatile private var rebaseToUs = Long.MIN_VALUE
    private var thread: Thread? = null

    @Volatile var durationUs: Long = -1
        private set
    @Volatile var seekable = false
        private set
    @Volatile var live = true
        private set

    /** Neue Quelle (alles Vorherige wird geschlossen). */
    fun open(url: String, userAgent: String?, reconnectUrl: String?, live: Boolean) {
        stop()
        close()
        this.url = url
        this.reconnectUrl = reconnectUrl
        this.userAgent = userAgent
        this.liveHint = live
        this.live = live
        durationUs = -1
        seekable = false
        timeBaseUs = Long.MIN_VALUE
        rebaseToUs = Long.MIN_VALUE
        tsFileBaseUs = Long.MIN_VALUE
    }

    val hasUrl: Boolean get() = url != null

    /** Lesen ab [startUs] auf einem eigenen Thread (für Generation [gen] des Aufrufers). */
    fun start(gen: Int, startUs: Long, first: Boolean, guard: (String, () -> Unit) -> Unit) {
        thread = thread(name = "dna-net", isDaemon = true) { guard("Lesen") { readerLoop(gen, startUs, first) } }
    }

    /** Laufenden Abruf abbrechen (Spulen, Stopp); die Quelle eines Films bleibt für das nächste [start]. */
    fun stop() {
        call?.cancel()
        call = null
        (byteSource as? HttpByteSource)?.abort()
        thread?.interrupt()
        thread = null
    }

    /** Quelle schließen. */
    fun close() {
        val source = byteSource
        byteSource = null
        synchronized(sourceLock) {
            container = null
            sourceKind = null
            hls = null
        }
        source?.let { runCatching { it.close() } }
    }

    private fun readerLoop(gen: Int, startUs: Long, first: Boolean) {
        val kind = synchronized(sourceLock) { sourceKind } ?: detect(gen) ?: return
        when (kind) {
            SourceKind.LIVE_TS -> readLiveTs(gen)
            SourceKind.TS_FILE -> synchronized(sourceLock) { readTsFile(gen, startUs) }
            SourceKind.CONTAINER -> synchronized(sourceLock) { readContainer(gen, startUs, first) }
            SourceKind.HLS -> synchronized(sourceLock) { readHls(gen, startUs) }
        }
    }

    /** Art der Quelle: an der Adresse, sonst an den ersten Bytes. */
    private fun detect(gen: Int): SourceKind? {
        val target = url ?: return null
        val path = target.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        val kind = when {
            ext == "m3u8" || ext == "m3u" -> SourceKind.HLS
            ext in setOf("mkv", "webm", "mk3d", "mka", "mp4", "m4v", "mov", "m4a", "3gp") -> SourceKind.CONTAINER
            ext in setOf("ts", "mts", "m2ts", "trp") -> if (liveHint) SourceKind.LIVE_TS else SourceKind.TS_FILE
            liveHint && target.startsWith("http://127.0.0.1") -> SourceKind.LIVE_TS
            else -> sniff(gen, target, liveHint) ?: return null
        }
        synchronized(sourceLock) { sourceKind = kind }
        return kind
    }

    private fun sniff(gen: Int, target: String, liveHint: Boolean): SourceKind? {
        return try {
            val request = Request.Builder().url(target).header("Range", "bytes=0-1023").apply { userAgent?.let { header("User-Agent", it) } }.build()
            val c = http.newCall(request)
            call = c
            c.execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val head = ByteArray(1024)
                val input = r.body.byteStream()
                var n = 0
                while (n < head.size) {
                    val k = input.read(head, n, head.size - n)
                    if (k < 0) break
                    n += k
                }
                val text = String(head, 0, n.coerceAtMost(16), Charsets.ISO_8859_1)
                when {
                    text.startsWith("#EXTM3U") -> SourceKind.HLS
                    MkvDemuxer.sniff(head) || Mp4Demuxer.sniff(head) -> SourceKind.CONTAINER
                    else -> if (liveHint) SourceKind.LIVE_TS else SourceKind.TS_FILE
                }
            }
        } catch (e: Exception) {
            if (!sink.alive(gen)) return null
            // Unbekannt: Live wie bisher als TS versuchen.
            if (liveHint) SourceKind.LIVE_TS else {
                sink.onError("Stream nicht erreichbar (${e.message})", fatal = true)
                null
            }
        }
    }

    /** Medienzeit aus einer Quellzeit (TS/HLS). */
    private fun rebase(sample: Sample): Sample {
        if (timeBaseUs == Long.MIN_VALUE || rebaseToUs != Long.MIN_VALUE) {
            val to = if (rebaseToUs != Long.MIN_VALUE) rebaseToUs else 0L
            timeBaseUs = sample.timeUs - to
            rebaseToUs = Long.MIN_VALUE
        }
        return Sample(sample.track, sample.data, sample.timeUs - timeBaseUs, sample.keyframe, sample.audio, sample.durationUs)
    }

    /**
     * Naht beim Neuverbinden eines Live-Streams. Echte IPTV-Server schicken nach dem Neuverbinden die letzten Sekunden
     * noch einmal (aus ihrem Puffer) und die Zeitstempel laufen weiter: Dann wird nahtlos angeschlossen und Doppeltes
     * verworfen (Bild nach Zeitstempel, auch mit B-Bildern; Ton bis zum letzten gespielten). Springt die Uhr (anderer
     * Server, Datei von vorn), geht es lückenlos mit neuer Zeitbasis ab dem nächsten Schlüsselbild weiter.
     */
    private inner class LiveSeam {
        private var afterReconnect = false
        private var dedupe = false
        private var needKey = false
        private val recentVideo = ArrayDeque<Long>()
        private val recentVideoSet = HashSet<Long>()
        private var videoDeduping = false
        private val lastAudio = HashMap<Int, Long>()

        fun reconnected() {
            afterReconnect = true
        }

        fun accept(raw: Sample): Sample? {
            val kind = raw.track.codec.kind
            if (kind == Kind.TEXT) return rebase(raw)
            if (afterReconnect) {
                afterReconnect = false
                val expected = if (timeBaseUs == Long.MIN_VALUE) Long.MIN_VALUE else raw.timeUs - timeBaseUs
                val last = sink.lastMediaUs
                if (expected != Long.MIN_VALUE && expected in (last - SEAM_BACK_US)..(last + SEAM_AHEAD_US)) {
                    dedupe = true
                    videoDeduping = true
                    needKey = false
                    DnaLog.i(TAG, "Neu verbunden: Zeit läuft weiter (${(expected - last) / 1000} ms), schließe nahtlos an")
                } else {
                    dedupe = false
                    needKey = true
                    rebaseToUs = last
                    DnaLog.i(TAG, "Neu verbunden: Zeitsprung, neue Zeitbasis ab dem nächsten Schlüsselbild")
                }
            }
            if (kind == Kind.VIDEO) {
                if (dedupe && videoDeduping) {
                    if (raw.timeUs in recentVideoSet) return null
                    videoDeduping = false
                }
                if (needKey) {
                    if (!raw.keyframe) return null
                    needKey = false
                }
                recentVideo.addLast(raw.timeUs)
                recentVideoSet.add(raw.timeUs)
                while (recentVideo.size > RECENT_VIDEO) recentVideoSet.remove(recentVideo.removeFirst())
            } else if (kind == Kind.AUDIO) {
                val previous = lastAudio[raw.track.id]
                if (dedupe && previous != null && raw.timeUs <= previous) return null
                lastAudio[raw.track.id] = raw.timeUs
            }
            return rebase(raw)
        }
    }

    private fun readLiveTs(gen: Int) {
        var attempt = 0
        val seam = LiveSeam()
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(tracks: List<Track>) = sink.onTracks(tracks)
            override fun onSample(sample: Sample) {
                seam.accept(sample)?.let { sink.onSample(it, gen) }
            }
        })
        val buffer = ByteArray(64 * 1024)
        var first = true
        live = true
        sink.onPrepared(-1, false, true)
        while (sink.alive(gen)) {
            val target = (if (first) url else reconnectUrl ?: url) ?: return
            first = false
            try {
                val request = Request.Builder().url(target).apply { userAgent?.let { header("User-Agent", it) } }.build()
                val current = http.newCall(request)
                call = current
                if (!sink.alive(gen)) return
                current.execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        // 509 / 429 / 5xx: kurzer Backoff, dann neu versuchen (Provider-Limits / temporäre Fehler)
                        if (code in setOf(509, 429) || code in 500..599) {
                            DnaLog.w(TAG, "HTTP $code – warte kurz und verbinde neu")
                            Thread.sleep(if (code == 509) 4500L else if (code == 429) 2500L else 1200L)
                        }
                        throw IOException("HTTP $code")
                    }
                    // Eigener Netz-Thread liest die Verbindung ständig leer (4K: kein Stau beim Anbieter, keine Trennung).
                    val input = NetworkPrefetch(response.body.byteStream(), config.prefetchBytes)
                    attempt = 0
                    while (sink.alive(gen)) {
                        sink.awaitRoom(gen, if (live) config.liveBufferUs else config.vodBufferUs)
                        val n = input.read(buffer)
                        if (n < 0) break
                        demuxer.feed(buffer, 0, n)
                    }
                    input.close()
                }
                demuxer.flush()
                if (!sink.alive(gen)) return
                sink.onInfo("Anbieter hat die Verbindung beendet – verbinde neu …")
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!sink.alive(gen)) return
                attempt++
                DnaLog.i(TAG, "Netz: ${e.message}")
                if (attempt >= MAX_ATTEMPTS) {
                    sink.onError("Stream nicht erreichbar (${e.message})", fatal = true)
                    return
                }
                sink.onInfo("Verbindung unterbrochen – verbinde neu ($attempt/$MAX_ATTEMPTS) …")
            }
            // Neu verbinden: Läuft die Uhr des Senders weiter, nahtlos anschließen (doppelt Geliefertes fällt weg),
            // sonst lückenlos neue Zeitbasis ab dem nächsten Schlüsselbild. Der Bild-Decoder bleibt in beiden Fällen.
            demuxer.reset()
            seam.reconnected()
            runCatching { Thread.sleep(minOf(500L * attempt, 3_000L)) }
        }
    }

    private fun openByteSource(): ByteSource {
        byteSource?.let { return it }
        val target = url ?: throw IOException("keine Adresse")
        // Datei: „file:…“, „/…“ oder unter Windows „C:\…“.
        val source = if (target.startsWith("file:") || target.startsWith("/") || WINDOWS_PATH.matches(target.take(3))) {
            FileByteSource(java.io.File(if (target.startsWith("file:")) java.net.URI(target).path else target))
        } else HttpByteSource(http, target, userAgent).also { it.connect() }
        byteSource = source
        return source
    }

    private fun readContainer(gen: Int, startUs: Long, first: Boolean) {
        try {
            var demuxer = container
            if (demuxer == null) {
                val reader = SourceReader(openByteSource())
                val head = ByteArray(16)
                reader.readFully(head)
                reader.seek(0)
                demuxer = if (MkvDemuxer.sniff(head)) MkvDemuxer(reader) else Mp4Demuxer(reader)
                val info = demuxer.init()
                container = demuxer
                durationUs = info.durationUs
                seekable = info.seekable
                live = false
                sink.onTracks(info.tracks)
                sink.onPrepared(info.durationUs, info.seekable, false)
                if (startUs > 0) demuxer.seek(startUs)
            } else {
                demuxer.seek(startUs)
            }
            while (sink.alive(gen)) {
                sink.awaitRoom(gen, if (live) config.liveBufferUs else config.vodBufferUs)
                if (!sink.alive(gen)) return
                val sample = demuxer.read() ?: break
                sink.onSample(sample, gen)
            }
            if (sink.alive(gen)) sink.onEnded()
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            if (!sink.alive(gen)) return
            DnaLog.i(TAG, "Datei: ${e.message}")
            if (first && container == null) sink.onError("Datei lässt sich nicht lesen (${e.message})", fatal = true)
            else {
                sink.onInfo("Lesefehler (${e.message}) – Ende")
                sink.onEnded()
            }
        }
    }

    private fun readTsFile(gen: Int, startUs: Long) {
        try {
            val source = openByteSource()
            val reader = SourceReader(source)
            if (tsFileBaseUs == Long.MIN_VALUE) {
                val (firstUs, lastUs) = tsTimes(reader)
                tsFileBaseUs = firstUs
                durationUs = if (lastUs > firstUs) lastUs - firstUs else -1
                seekable = source.length > 0 && durationUs > 0
                live = false
                sink.onPrepared(durationUs, seekable, false)
            }
            val length = source.length
            val startByte = if (startUs > 0 && durationUs > 0 && length > 0) {
                ((length * (startUs.toDouble() / durationUs)).toLong() / 188) * 188
            } else 0L
            reader.seek(startByte)
            val demuxer = TsDemuxer(object : TsDemuxer.Output {
                override fun onTracks(tracks: List<Track>) = sink.onTracks(tracks)
                override fun onSample(sample: Sample) =
                    sink.onSample(Sample(sample.track, sample.data, sample.timeUs - tsFileBaseUs, sample.keyframe, sample.audio, sample.durationUs), gen)
            })
            val buffer = ByteArray(188 * 256)
            while (sink.alive(gen)) {
                sink.awaitRoom(gen, if (live) config.liveBufferUs else config.vodBufferUs)
                if (!sink.alive(gen)) return
                var n = 0
                while (n < buffer.size) {
                    val b = reader.readByte()
                    if (b < 0) break
                    buffer[n++] = b.toByte()
                }
                if (n == 0) break
                demuxer.feed(buffer, 0, n)
                if (n < buffer.size) break
            }
            demuxer.flush()
            if (sink.alive(gen)) sink.onEnded()
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            if (!sink.alive(gen)) return
            sink.onError("Datei lässt sich nicht lesen (${e.message})", fatal = durationUs < 0)
            sink.onEnded()
        }
    }

    /** Erste und letzte Zeit einer TS-Datei (Anfang und Ende lesen). */
    private fun tsTimes(reader: SourceReader): Pair<Long, Long> {
        var firstUs = Long.MIN_VALUE
        var lastUs = Long.MIN_VALUE
        fun scan(from: Long, bytes: Int, keepFirst: Boolean) {
            reader.seek(from)
            val data = ByteArray(bytes)
            var n = 0
            while (n < bytes) {
                val b = reader.readByte()
                if (b < 0) break
                data[n++] = b.toByte()
            }
            TsDemuxer(object : TsDemuxer.Output {
                override fun onTracks(tracks: List<Track>) = Unit
                override fun onSample(sample: Sample) {
                    if (keepFirst && firstUs == Long.MIN_VALUE) firstUs = sample.timeUs
                    if (sample.timeUs > lastUs) lastUs = sample.timeUs
                }
            }).apply { feed(data, 0, n); flush() }
        }
        scan(0, 2 * 1024 * 1024, keepFirst = true)
        val length = reader.length
        if (length > 4 * 1024 * 1024) {
            lastUs = Long.MIN_VALUE
            scan(((length - 3 * 1024 * 1024) / 188) * 188, 3 * 1024 * 1024, keepFirst = false)
        }
        return (if (firstUs == Long.MIN_VALUE) 0 else firstUs) to lastUs
    }

    private fun readHls(gen: Int, startUs: Long) {
        try {
            val loader = hls ?: HlsLoader(http, userAgent, { sink.alive(gen) }, config.maxVideoHeight, config.maxBandwidth).also {
                it.open(url ?: return)
                hls = it
            }
            loader.running = { sink.alive(gen) }
            live = loader.live
            durationUs = if (loader.live) -1 else loader.media?.durationUs ?: -1
            seekable = !loader.live && durationUs > 0
            sink.onPrepared(durationUs, seekable, loader.live)
            var segment = loader.firstSegment(startUs) ?: run { sink.onEnded(); return }
            val tsDemuxer = TsDemuxer(object : TsDemuxer.Output {
                override fun onTracks(tracks: List<Track>) = sink.onTracks(tracks)
                override fun onSample(sample: Sample) = sink.onSample(rebase(sample), gen)
            })
            // Medienzeit = Stelle des Abschnitts in der Liste (Filme) bzw. lückenlos (Live).
            rebaseToUs = if (loader.live) sink.lastMediaUs.coerceAtLeast(0) else segment.startUs
            var failures = 0
            var fmp4Tracks = false
            while (sink.alive(gen)) {
                sink.awaitRoom(gen, if (live) config.liveBufferUs else config.vodBufferUs)
                if (!sink.alive(gen)) return
                val data = try {
                    loader.load(segment)
                } catch (e: IOException) {
                    if (!sink.alive(gen)) return
                    if (++failures > 6) throw e
                    sink.onInfo("Abschnitt nicht geladen (${e.message}) – neuer Versuch")
                    Thread.sleep(minOf(500L * failures, 3_000L))
                    continue
                }
                failures = 0
                if (segment.discontinuity) {
                    tsDemuxer.reset()
                    rebaseToUs = if (loader.live) sink.lastMediaUs else segment.startUs
                }
                val init = loader.init(segment)
                if (init != null || Mp4Demuxer.sniff(data)) {
                    // fMP4-Abschnitt: Kopf + Abschnitt zusammen lesen.
                    val demuxer = Mp4Demuxer(SourceReader(ArrayByteSource((init ?: ByteArray(0)) + data)))
                    val info = demuxer.init()
                    if (!fmp4Tracks) { sink.onTracks(info.tracks); fmp4Tracks = true }
                    while (sink.alive(gen)) {
                        val s = demuxer.read() ?: break
                        sink.onSample(rebase(s), gen)
                    }
                } else {
                    tsDemuxer.feed(data, 0, data.size)
                }
                segment = loader.nextSegment() ?: break
            }
            tsDemuxer.flush()
            if (sink.alive(gen)) sink.onEnded()
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            if (!sink.alive(gen)) return
            DnaLog.i(TAG, "HLS: ${e.message}")
            sink.onError("HLS nicht abspielbar (${e.message})", fatal = true)
        }
    }

    companion object {
        private const val TAG = "DnaReader"
        private const val MAX_ATTEMPTS = 8
        /** Neu verbunden: so weit darf die Uhr zurück (Server-Puffer) bzw. vor (Lücke) liegen, um nahtlos anzuschließen. */
        private const val SEAM_BACK_US = 60_000_000L
        private const val SEAM_AHEAD_US = 15_000_000L
        private const val RECENT_VIDEO = 600
        private val WINDOWS_PATH = Regex("""[A-Za-z]:[\\/]""")
    }
}

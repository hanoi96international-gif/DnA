package de.ayutv.data.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Film für den Fernseher als echte HLS-Liste: Stücke von etwa [TARGET_SECONDS] s an Schlüsselbildern, jedes beim
 * Abruf aus der Datei beim Anbieter umverpackt ([Remux]). Der Fernseher spult so selbst (er holt das Stück an der
 * Stelle), hat Ton (DTS/TrueHD → AC3) und bekommt eine Untertitelspur dazu.
 *
 * Immer nur ein Stück zugleich (eine Verbindung zum Anbieter); das nächste wird schon geholt, während der Fernseher das
 * aktuelle abspielt. Die letzten Stücke bleiben im Speicher (der Fernseher fragt manchmal doppelt).
 */
class RemuxSession(
    private val film: Remux.Film,
    private val source: HttpRangeSource,
    /** Tonspur (Index in der Datei, -1 = Standard). */
    audio: Int = -1,
    /** Bytes vom Anbieter (zählt [source] mit). */
    val fetched: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(),
) : Closeable {
    val boundaries: DoubleArray = film.boundaries(TARGET_SECONDS)
    val count: Int get() = boundaries.size - 1
    /** Zeitstempel des Filmanfangs in der Datei (meist 0). Alle Zeiten nach außen zählen ab hier. */
    val origin: Double get() = boundaries[0]
    val duration: Double get() = boundaries[count] - origin
    val tracks: List<Remux.Track> get() = film.audio
    val video: Remux.Video? get() = film.video
    /** Eingebettete Text-Untertitel (Bild-Untertitel wie PGS kann der Fernseher als WebVTT nicht bekommen). */
    val subtitleTracks: List<Remux.SubTrack> get() = film.subtitles.filter { it.text }

    /**
     * Zeilen der eingebetteten Untertitel je Spur (Beginn ms ab Filmanfang → Ende, Text): kommen beim Umverpacken
     * der Stücke mit (keine zweite Verbindung zum Anbieter).
     */
    private val cues = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentSkipListMap<Long, Pair<Long, String>>>()
    /** Stücke, deren Untertitel schon gesammelt sind. */
    private val cueSegments = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Tonspur; ändern = neue Stücke (der Fernseher muss die Liste neu öffnen). */
    @Volatile var audio: Int = if (audio >= 0) audio else film.defaultAudio
        private set

    /** Zähler für die Adressen der Stücke: ändert sich mit der Tonspur, damit der Fernseher nichts Altes nimmt. */
    @Volatile var generation = 0
        private set

    /** Ab diesem Stück beginnt die Liste (Weiterschauen, Springen per Handy). */
    @Volatile var first = 0
        private set

    @Volatile var lastError: String? = null
        private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dna-remux").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > CACHE_SEGMENTS
    }
    private val loading = HashMap<String, CompletableFuture<ByteArray?>>()
    @Volatile private var closed = false

    init {
        require(count >= 1) { "keine Stücke" }
    }

    /** Stück, in dem [seconds] (ab Filmanfang) liegt. */
    fun indexAt(seconds: Double): Int {
        if (seconds <= 0) return 0
        val at = origin + seconds
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (boundaries[mid] <= at + 0.001) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Beginn der Liste (ab Filmanfang). */
    val firstSeconds: Double get() = boundaries[first] - origin

    /** Liste ab [seconds] beginnen lassen (der Fernseher muss sie neu öffnen). Liefert den Beginn des ersten Stücks. */
    fun startAt(seconds: Double): Double {
        first = indexAt(seconds)
        anchor(first)
        return firstSeconds
    }

    /** Andere Tonspur (die schon geholten Stücke gelten nicht mehr). */
    fun selectAudio(index: Int) {
        synchronized(cache) {
            audio = index
            generation++
            cache.clear()
        }
    }

    /** Stückliste für den Fernseher; [segmentUrl] baut die Adresse eines Stücks. */
    fun playlist(segmentUrl: (generation: Int, index: Int) -> String): String {
        val gen = generation
        val start = first
        val target = (start until count).maxOf { boundaries[it + 1] - boundaries[it] }.let { kotlin.math.ceil(it).toLong().coerceAtLeast(1) }
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(start).append('\n')
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            for (i in start until count) {
                append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", boundaries[i + 1] - boundaries[i])).append(",\n")
                append(segmentUrl(gen, i)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    /**
     * Stück [index] (TS-Daten), notfalls erst erzeugen; danach wird das nächste vorbereitet. null bei Fehler oder wenn
     * [generation] nicht mehr gilt.
     */
    fun segment(generation: Int, index: Int): ByteArray? {
        if (index !in 0 until count || generation != this.generation) return null
        noteRequest(index)
        val data = load(generation, index).let { runCatching { it.get(90, TimeUnit.SECONDS) }.getOrNull() }
        if (data != null && index + 1 < count) load(generation, index + 1)
        return data
    }

    private fun load(gen: Int, index: Int): CompletableFuture<ByteArray?> {
        val key = "$gen/$index"
        synchronized(cache) {
            cache[key]?.let { return CompletableFuture.completedFuture(it) }
            loading[key]?.let { return it }
            val future = CompletableFuture<ByteArray?>()
            loading[key] = future
            worker.execute {
                val result = if (closed || gen != generation) null else produce(index)
                synchronized(cache) {
                    loading.remove(key)
                    if (result != null && gen == generation) cache[key] = result
                }
                future.complete(result)
            }
            return future
        }
    }

    private fun produce(index: Int): ByteArray? {
        val out = ByteArrayOutputStream(4 * 1024 * 1024)
        val codecs = film.subtitles.associate { it.index to it.codec }
        val sink = object : Remux.Sink {
            override fun write(buf: ByteArray, len: Int): Boolean {
                if (closed) return false
                out.write(buf, 0, len)
                return true
            }

            override fun cue(stream: Int, start: Double, end: Double, text: ByteArray) {
                val codec = codecs[stream] ?: return
                val line = Remux.cueText(codec, text).takeIf { it.isNotBlank() } ?: return
                val from = ((start - origin) * 1000).toLong()
                val until = ((end - origin) * 1000).toLong().coerceAtLeast(from + 300)
                cues.getOrPut(stream) { java.util.concurrent.ConcurrentSkipListMap() }[from] = until to line
            }
        }
        val error = runCatching { film.segment(boundaries[index], boundaries[index + 1], audio, -1, sink, cues = subtitleTracks.isNotEmpty()) }
            .getOrElse { it.javaClass.simpleName }
        if (error != null) {
            lastError = "Stück ${index + 1}: $error" + (source.lastError?.let { " ($it)" } ?: "")
            return null
        }
        cueSegments += index
        return out.toByteArray()
    }

    /**
     * Für die Untertitel von Stück [index]: Ist es noch nicht umverpackt worden, jetzt (der Fernseher holt die
     * Untertitel kurz vor dem Bild). Weit voraus (mehr als [CUE_AHEAD] Stücke vor dem, was der Fernseher gerade holt)
     * nicht – das kostete Leitung für Bild, das noch lange nicht gebraucht wird.
     */
    fun ensureCues(index: Int) {
        if (subtitleTracks.isEmpty() || index !in 0 until count || index in cueSegments) return
        val ahead = synchronized(this) { furthest }
        if (ahead >= 0 && index > ahead + CUE_AHEAD) return
        val gen = generation
        runCatching { load(gen, index).get(90, TimeUnit.SECONDS) }
    }

    /** Gesammelte Zeilen der eingebetteten Spur [stream] (Beginn, Ende in ms ab Filmanfang, Text). */
    fun embeddedCues(stream: Int): List<Triple<Long, Long, String>> =
        cues[stream]?.map { (start, v) -> Triple(start, v.first, v.second) } ?: emptyList()

    // ------------------------------------------------------------------ Wo steht der Fernseher?

    private var anchorSeconds = 0.0
    private var anchorAt = System.currentTimeMillis()
    private var lastRequested = -1
    private var furthest = -1
    @Volatile private var pausedAt = 0L

    private fun anchor(index: Int) {
        synchronized(this) {
            anchorSeconds = boundaries[index]
            anchorAt = System.currentTimeMillis()
            // Die Zeit zählt ab dem ersten Abruf des Fernsehers.
            lastRequested = -1
            furthest = index - 1
        }
    }

    private fun noteRequest(index: Int) = synchronized(this) {
        // Nicht der Reihe nach (nicht das nächste, nicht dasselbe nochmal): am Fernseher gespult.
        if (lastRequested >= 0 && index != lastRequested && index != lastRequested + 1) {
            anchorSeconds = boundaries[index]
            anchorAt = System.currentTimeMillis()
            furthest = index
        } else if (lastRequested < 0) {
            anchorSeconds = boundaries[index]
            anchorAt = System.currentTimeMillis()
        }
        lastRequested = index
        if (index > furthest) furthest = index
    }

    /** Angehalten (vom Handy aus): die Zeit läuft nicht weiter. */
    fun paused(paused: Boolean) = synchronized(this) {
        if (paused && pausedAt == 0L) pausedAt = System.currentTimeMillis()
        else if (!paused && pausedAt != 0L) {
            anchorAt += System.currentTimeMillis() - pausedAt
            pausedAt = 0L
        }
    }

    /**
     * Geschätzte Stelle auf dem Fernseher (s): ab dem letzten Sprung mitgezählt, höchstens bis zum Ende des zuletzt
     * geholten Stücks (weiter kann er nicht sein – hält er an, holt er bald nichts mehr).
     */
    fun position(): Double = synchronized(this) {
        val now = if (pausedAt != 0L) pausedAt else System.currentTimeMillis()
        val estimate = anchorSeconds + (now - anchorAt) / 1000.0
        val limit = if (furthest >= 0) boundaries[minOf(furthest + 1, count)] else boundaries[first]
        estimate.coerceIn(boundaries[first], limit) - origin
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        runCatching { source.close() }
        // Der Film wird erst geschlossen, wenn kein Stück mehr entsteht (sonst liefe nativer Code auf freigegebenem Speicher).
        Thread({
            runCatching { worker.awaitTermination(30, TimeUnit.SECONDS) }
            film.close()
        }, "dna-remux-close").apply { isDaemon = true }.start()
    }

    companion object {
        /**
         * Film [url] beim Anbieter öffnen (blockiert: liest Kopf und Verzeichnis der Datei). Tonspur: die in
         * [preferredLanguages] zuerst passende, sonst die Standardspur der Datei.
         */
        fun open(client: okhttp3.OkHttpClient, url: String, userAgent: String?, preferredLanguages: List<String> = emptyList()): Result<RemuxSession> {
            val fetched = java.util.concurrent.atomic.AtomicLong()
            val source = HttpRangeSource(client, url, userAgent, fetched)
            val film = Remux.open(source).getOrElse {
                source.close()
                return Result.failure(java.io.IOException(source.lastError ?: it.message ?: "Datei nicht lesbar"))
            }
            val audio = pickAudio(film.audio, preferredLanguages)
            return runCatching { RemuxSession(film, source, audio, fetched) }.onFailure {
                source.close()
                film.close()
            }
        }

        /** Erste Tonspur in einer der Sprachen (in dieser Reihenfolge), sonst -1 (Standardspur der Datei). */
        internal fun pickAudio(tracks: List<Remux.Track>, preferredLanguages: List<String>): Int {
            for (wanted in preferredLanguages) {
                val code = Remux.isoLanguage(wanted)
                tracks.firstOrNull { t -> t.language?.let { Remux.isoLanguage(it) } == code }?.let { return it.index }
            }
            return -1
        }

        /** Länge der Stücke: kurz genug für schnelles Spulen und Starten, lang genug für wenig Anfragen. */
        const val TARGET_SECONDS = 4.0
        const val CACHE_SEGMENTS = 3
        /** So viele Stücke vor dem Fernseher werden für Untertitel höchstens umverpackt. */
        const val CUE_AHEAD = 6
    }
}

package de.ayutv.player.dna.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * DNA Engine: eigener Wiedergabe-Kern direkt auf den Bausteinen von Android – ohne ExoPlayer, ohne VLC.
 *
 * - **Quellen**: Live-TV als MPEG-TS (mit Neuverbinden), TS-Dateien, MKV/WebM und MP4 (HTTP-Range, Spulen über das
 *   Verzeichnis der Datei), HLS (Live und Filme, AES-128, TS- und fMP4-Abschnitte). Alle Zeiten werden auf eine Medienzeit
 *   ab 0 gebracht (Live: lückenlos über Neuverbindungen hinweg).
 * - **Bild**: MediaCodec asynchron, vor dem ersten Bild mit echter Größe und Parametersätzen eingerichtet (4K HDR).
 *   Nie flush: Bei Sprüngen wird der Decoder frisch angelegt (MediaTek). Scheitert ein Decoder, kommt der nächste dran
 *   (Hardware zuerst, Software nur bis Full HD). Jedes Bild geht mit seinem Darstellungszeitpunkt an die Bildfläche.
 * - **Ton**: MediaCodec → PCM → [PcmProcessor] (Auswertung der Sprache, Nachtmodus) → AudioTrack; Dolby/DTS ohne
 *   eigenen Decoder werden an Receiver/Soundbar durchgereicht. Der Ton ist der Taktgeber, das Bild folgt.
 * - **Untertitel**: alle Text- und Bildspuren gehen schon beim Lesen an [Listener.onText] (Vorlauf für Übersetzung).
 *
 * Kein Fehler in Decoder, Netz oder Tonausgabe lässt die App abstürzen: Er wird gemeldet ([Listener.onError]).
 */
class DnaEngineCore(
    private val http: OkHttpClient,
    private val listener: Listener,
    private val config: Config = Config(),
) {
    class Config(
        /** Live: so viel wird höchstens vorgeladen (µs). */
        val liveBufferUs: Long = 8_000_000,
        /** Filme: so viel wird höchstens vorgeladen (µs) … */
        val vodBufferUs: Long = 30_000_000,
        /** … und höchstens so viele Bytes (4K). */
        val maxBufferBytes: Long = 96L * 1024 * 1024,
        /** So viel muss vorliegen, bevor es losgeht (µs). */
        val startBufferUs: Long = 1_200_000,
        /** Nach einem leeren Vorrat geht es weiter, sobald so viel vorliegt (µs). */
        val rebufferUs: Long = 2_500_000,
        /** HLS: höchste Bildhöhe, die gewählt wird. */
        val maxVideoHeight: Int = 2160,
        /** HLS: höchste Datenrate (bit/s, 0 = beliebig). */
        val maxBandwidth: Long = 0,
        /** Dolby/DTS durchreichen statt dekodieren (Einstellung „Ton an Receiver“). */
        val preferPassthrough: Boolean = false,
        /** Halbbilder (1080i) auf 50 Bilder/s umrechnen. */
        val deinterlace: Boolean = true,
        /** Ton minimal an die Bildwechsel des Displays angleichen (nur dekodierter Ton). */
        val displaySync: Boolean = true,
        /** Live: bei knappem Vorrat kurz langsamer statt anzuhalten; Puffer lernt je Sender mit. */
        val liveCatchUp: Boolean = true,
        /** Tunneling: Bild und Ton gleicht das Gerät selbst ab (experimentell). */
        val tunneling: Boolean = false,
    )

    /** Bearbeitet dekodierten Ton (PCM 16 Bit, verschachtelt), bevor er zur Tonausgabe geht. */
    interface PcmProcessor {
        fun configure(sampleRate: Int, channels: Int)
        /** Neuer Abschnitt ab Medienzeit [positionUs] (Start, Spulen). */
        fun flush(positionUs: Long)
        /** Gibt den bearbeiteten Ton zurück (darf derselbe Puffer sein). */
        fun process(input: ByteBuffer): ByteBuffer
        fun reset()
    }

    interface Listener {
        fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onTracks(video: Track?, audio: List<Track>, text: List<Track>, selectedAudio: Track?)
        fun onVideoSize(width: Int, height: Int)
        fun onFirstFrame()
        /** Untertitel-Probe (alle Spuren), Medienzeit; kommt beim Lesen, also vor der Einblendung. */
        fun onText(sample: Sample)
        fun onEnded()
        fun onError(message: String, fatal: Boolean)
        fun onInfo(text: String)
    }

    @Volatile private var surface: Surface? = null
    private val running = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var buffering = false
    @Volatile private var volume = 1f
    @Volatile private var speed = 1f
    @Volatile private var generation = 0

    /** Lesen (gemeinsam mit Windows); für Filme bleiben Leser und Datenquelle über Spulvorgänge erhalten. */
    private val source = DnaReader(
        http,
        DnaReader.Config(
            config.liveBufferUs, config.vodBufferUs, config.maxVideoHeight, config.maxBandwidth,
            // Netz-Puffer: bei 4K / STABLE mehr vorausladen (bis 48 MB), sonst ein Viertel des Vorrats.
            prefetchBytes = (config.maxBufferBytes / 3).coerceIn(6L * 1024 * 1024, 48L * 1024 * 1024).toInt(),
        ),
        object : DnaReader.Sink {
            override fun alive(gen: Int) = this@DnaEngineCore.alive(gen)
            override fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean) {
                this@DnaEngineCore.durationUs = durationUs
                this@DnaEngineCore.seekable = seekable
                this@DnaEngineCore.live = live
                listener.onPrepared(durationUs, seekable, live)
            }
            override fun onTracks(tracks: List<Track>) = handleTracks(tracks)
            override fun onSample(sample: Sample, gen: Int) = handleSample(sample, gen)
            override fun onEnded() = markEnded()
            override fun onError(message: String, fatal: Boolean) = listener.onError(message, fatal)
            override fun onInfo(text: String) = listener.onInfo(text)
            override fun onReconnect() = restartVideoDecoder()
            override fun awaitRoom(gen: Int, limitUs: Long) = waitForRoom(gen, limitUs)
            override val lastMediaUs: Long get() = this@DnaEngineCore.lastMediaUs
        },
    )

    private val lock = Object()
    private val videoQueue = ArrayDeque<Sample>()
    private val audioQueue = ArrayDeque<Sample>()
    private var queuedBytes = 0L
    @Volatile private var inputEnded = false
    @Volatile private var lastMediaUs = 0L
    @Volatile private var endedSent = false

    @Volatile private var videoTrack: Track? = null
    @Volatile private var audioTracks: List<Track> = emptyList()
    @Volatile private var textTracks: List<Track> = emptyList()
    @Volatile private var audioTrack: Track? = null
    @Volatile private var preferredAudioLanguage: String? = null

    /** Ab hier zeigen (nach dem Spulen); davor wird dekodiert, aber nicht gezeigt bzw. gespielt. */
    @Volatile private var seekTargetUs = 0L
    /** Zeit des ersten gezeigten Bildes: Der Ton beginnt genau dort (lippensynchron, keine Uhr, die zurückspringt). */
    @Volatile private var videoStartUs = Long.MIN_VALUE
    @Volatile var durationUs: Long = -1
        private set
    @Volatile var seekable = false
        private set
    @Volatile var live = true
        private set

    private var audioThread: Thread? = null
    private var videoThread: HandlerThread? = null
    private var videoHandler: Handler? = null
    private var video: VideoDecoder? = null

    /** Auf dem Video-Thread: Decoder-Kandidaten und wie oft sie ohne ein Bild scheiterten. */
    private var videoCandidates: List<String> = emptyList()
    private var videoCandidateIndex = 0
    private var videoFailures = 0
    private var videoConfig: VideoConfig? = null


    val pcmProcessors = java.util.concurrent.CopyOnWriteArrayList<PcmProcessor>()

    /** Jede Probe der gewählten Tonspur, beim Lesen (Auswertung des Tons nebenbei, wenn er durchgereicht wird). */
    @Volatile var audioTap: ((Sample) -> Unit)? = null

    /** Name des laufenden Ton-Decoders (Anzeige, Fehlerbericht). */
    @Volatile var audioDecoderName: String? = null
        private set

    /** Bild aus (nur Ton und Untertitel, z. B. Mitlesen beim Übertragen): Bildproben werden verworfen. */
    @Volatile var videoEnabled = true

    private val clock = Clock()

    /** Medienzeit (µs) der Wiedergabe. */
    val positionUs: Long
        get() = if (!clock.started) seekTargetUs else clock.nowUs().coerceAtLeast(0)

    /** Vorrat in µs ab der Wiedergabestelle. */
    val bufferedUs: Long get() = synchronized(lock) { bufferedAheadLocked() }

    @Volatile var droppedFrames = 0
        private set
    @Volatile var renderedFrames = 0
        private set
    /** Name des laufenden Bild-Decoders (Anzeige). */
    @Volatile var videoDecoderName: String? = null
        private set
    /** Ton wird durchgereicht (Receiver dekodiert). */
    @Volatile var passthrough = false
        private set

    // ================================================================ Steuerung

    /**
     * [reconnectUrl]: Adresse fürs Neuverbinden, wenn sie sich von [url] unterscheidet (Zeitversatz: wieder ab Live).
     * [live]: Hinweis, ob es ein Live-Sender ist (sonst entscheidet die Quelle). [startUs]: Startstelle bei Filmen.
     */
    fun open(url: String, userAgent: String?, reconnectUrl: String? = null, live: Boolean = true, startUs: Long = 0) {
        // Ohne Pfad (Xtream-Adressen tragen Benutzer und Passwort im Pfad): nur Rechner und Dateiendung.
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "Datei"
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        DnaLog.i(TAG, "— Neuer Stream: $host${ext.takeIf { it.isNotEmpty() && it.length <= 5 }?.let { " (.$it)" }.orEmpty()}, ${if (live) "live" else "Film"}" +
            (if (startUs > 0) ", ab ${startUs / 1_000_000} s" else "") + " · Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        stop()
        streamKey = url
        stalls = if (live) synchronized(stallMemory) { stallMemory[url] ?: 0 } else 0
        highBitrate = false
        rateBytes = 0
        rateFirstUs = Long.MIN_VALUE
        rateMeasured = false
        lastStallMs = SystemClock.elapsedRealtime()
        catchUp = 1f
        source.open(url, userAgent, reconnectUrl, live)
        this.live = live
        durationUs = -1
        seekable = false
        videoTrack = null
        audioTracks = emptyList()
        textTracks = emptyList()
        droppedFrames = 0
        renderedFrames = 0
        start(startUs.coerceAtLeast(0), first = true)
    }

    /** Filme: an [us] springen (Schlüsselbild davor, dann genau ab [us] zeigen). */
    fun seekTo(us: Long) {
        if (!source.hasUrl) return
        stop()
        start(us.coerceIn(0, if (durationUs > 0) durationUs else Long.MAX_VALUE), first = false)
    }

    private fun start(startUs: Long, first: Boolean) {
        running.set(true)
        val gen = ++generation
        seekTargetUs = startUs
        videoStartUs = Long.MIN_VALUE
        audioStarted = false
        audioLate = false
        parameterSets = null
        parameterSetsTrack = null
        // Anzeige: nicht den Decoder des vorigen Senders zeigen, solange für diesen noch keiner läuft.
        videoDecoderName = null
        videoNoConfigLogged = 0
        inputEnded = false
        endedSent = false
        lastMediaUs = startUs
        clock.reset()
        videoFailures = 0
        videoCandidateIndex = 0
        buffering = false
        setBuffering(true)
        VsyncSampler.add()
        videoThread = HandlerThread("dna-video").apply { start() }
        videoHandler = Handler(videoThread!!.looper)
        source.start(gen, startUs, first) { what, block -> safely(what, block) }
        audioThread = thread(name = "dna-audio", isDaemon = true) { safely("Ton") { audioLoop(gen) } }
        processorNextUs = Long.MIN_VALUE
    }

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            // beendet
        } catch (e: Throwable) {
            DnaLog.w(TAG, "$what: ${e.javaClass.simpleName} ${e.message}")
            if (running.get()) listener.onError("$what gestört: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}", fatal = false)
        }
    }

    fun setSurface(surface: Surface?) {
        this.surface = surface
        videoHandler?.post {
            // Neue Bildfläche: Decoder neu (ein Wechsel per setOutputSurface ist auf manchen Geräten unzuverlässig).
            releaseVideo()
            feedVideo()
        }
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        applyClockPause()
        currentAudioTrack?.let { runCatching { if (paused || buffering) it.pause() else it.play() } }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun setBuffering(value: Boolean) {
        if (buffering == value) return
        buffering = value
        applyClockPause()
        currentAudioTrack?.let { runCatching { if (paused || value) it.pause() else it.play() } }
        listener.onBuffering(value)
    }

    private fun applyClockPause() = clock.setPaused(paused || buffering)

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        currentAudioTrack?.let { runCatching { it.setVolume(this.volume) } }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 4f)
        if (s == this.speed) return
        this.speed = s
        applyPlaySpeed()
    }

    /** Live: kurz langsamer, um den Vorrat aufzufüllen (1 = normal). Wirkt zusätzlich zum gewählten Tempo. */
    @Volatile private var catchUp = 1f
    /** Tatsächliches Tempo: gewählt × Live-Ausgleich. */
    private val playSpeed: Float get() = speed * catchUp

    private fun applyPlaySpeed() {
        val s = playSpeed
        clock.setSpeed(s)
        currentAudioTrack?.let { t -> runCatching { t.playbackParams = PlaybackParams().setSpeed(s).setPitch(1f) } }
    }

    // ---------------------------------------------------------------- Live: Tempo statt Anhalten, Puffer lernt mit

    /** Hänger beim jetzigen Sender (aus früheren Sitzungen gemerkt), bestimmt Start- und Nachlade-Vorrat. */
    @Volatile private var stalls = 0
    private var streamKey: String? = null
    private var lastStallMs = 0L
    private var lastCatchUpCheckMs = 0L

    /** Vorrat leer gelaufen (Live): beim Sender merken, nächstes Mal mehr puffern. */
    private fun noteStall() {
        if (!live || !config.liveCatchUp) return
        stalls = (stalls + 1).coerceAtMost(MAX_STALL_LEVEL)
        lastStallMs = SystemClock.elapsedRealtime()
        streamKey?.let { key -> synchronized(stallMemory) { stallMemory[key] = stalls } }
        DnaLog.i(TAG, "Live: Vorrat leer – Stufe $stalls (Start ${startBufferUs() / 1000} ms, weiter ab ${resumeBufferUs() / 1000} ms)")
    }

    /**
     * Live mit dekodiertem Ton: Wird der Vorrat knapp, mit [CATCH_UP_SLOW] weiter (Ton in gleicher Tonhöhe), bis wieder
     * doppelt so viel da ist – wie ExoPlayer/Streaming-App live. So füllt sich der Vorrat, ohne dass das Bild steht. Läuft ein
     * Sender 10 Minuten ohne Hänger, wird eine Stufe zurückgenommen.
     */
    private fun updateCatchUp() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCatchUpCheckMs < 500) return
        lastCatchUpCheckMs = now
        if (stalls > 0 && now - lastStallMs > STALL_DECAY_MS) {
            stalls--
            lastStallMs = now
            streamKey?.let { key -> synchronized(stallMemory) { stallMemory[key] = stalls } }
        }
        // Nur Stereo-PCM (Mehrkanal läuft auf manchen Geräten über einen direkten HDMI-Weg ohne Tempo-Änderung).
        val allowed = live && config.liveCatchUp && !passthrough && speed == 1f && audioStarted &&
            (currentAudioTrack?.channelCount ?: 0) in 1..2
        if (!allowed) {
            if (catchUp != 1f) setCatchUp(1f)
            return
        }
        if (buffering) return
        val ahead = synchronized(lock) { bufferedAheadLocked() }
        val low = catchUpLowUs()
        if (catchUp == 1f && ahead < low) setCatchUp(CATCH_UP_SLOW)
        else if (catchUp != 1f && ahead >= low * 2) setCatchUp(1f)
    }

    private fun setCatchUp(factor: Float) {
        catchUp = factor
        applyPlaySpeed()
        DnaLog.i(TAG, if (factor == 1f) "Live: Vorrat wieder voll – normales Tempo" else "Live: Vorrat knapp – Tempo ${(factor * 100).toInt()} %")
    }

    private fun startBufferUs(): Long {
        val base = when {
            !live || !config.liveCatchUp -> config.startBufferUs
            stalls == 0 -> minOf(config.startBufferUs, LIVE_FAST_START_US)
            else -> minOf(config.startBufferUs * (2 + stalls) / 2, 5_000_000L)
        }
        // 4K (hohe Datenrate): mehr Vorlauf – jede Schwankung der Leitung kostet dort viel mehr Sekunden Bild.
        return if (highBitrate) maxOf(base, UHD_START_US) else base
    }

    // ---------------------------------------------------------------- Datenrate (4K erkennen)

    /** Hohe Datenrate (4K): größere Puffer-Ziele. Aus den ersten Sekunden Bild gemessen, je Strom. */
    @Volatile private var highBitrate = false
    private var rateBytes = 0L
    private var rateFirstUs = Long.MIN_VALUE
    @Volatile private var rateMeasured = false

    private fun noteVideoRate(sample: Sample) {
        if (rateMeasured) return
        if (rateFirstUs == Long.MIN_VALUE) rateFirstUs = sample.timeUs
        rateBytes += sample.data.size
        val span = sample.timeUs - rateFirstUs
        if (span >= 500_000) {
            rateMeasured = true
            val mbit = rateBytes * 8.0 / span // Bit je µs = Mbit/s
            highBitrate = mbit >= UHD_MBIT || (videoTrack?.height ?: 0) >= 2000
            DnaLog.i(TAG, "Datenrate Bild: ${"%.1f".format(mbit)} Mbit/s" + if (highBitrate) " – 4K-Puffer" else "")
        } else if (span < 0) {
            rateFirstUs = sample.timeUs
            rateBytes = 0
        }
    }

    private fun catchUpLowUs(): Long = LIVE_CATCH_UP_LOW_US * (2 + stalls) / 2 * (if (highBitrate) 2 else 1)

    // ---------------------------------------------------------------- Ton an die Bildwechsel angleichen

    /**
     * Wie mpv „display-resample“ und Kodi „Wiedergabe mit Anzeige synchronisieren“: Uhr (Ton) und Display laufen nie
     * exakt gleich schnell; alle paar Minuten muss dann ein Bild doppelt gezeigt oder ausgelassen werden (Hüpfer). Hier
     * wird gemessen, wo die Bilder relativ zum Bildwechsel liegen, und der Ton um höchstens [MAX_RESAMPLE] schneller oder
     * langsamer abgespielt (Abtastrate der Tonausgabe), sodass jedes Bild dauerhaft mittig auf einem Bildwechsel liegt.
     * Nur mit dekodiertem Ton (nicht beim Durchreichen) und normalem Tempo. Läuft auf dem Video-Thread.
     */
    private inner class DisplaySync {
        private var sumSin = 0.0
        private var sumCos = 0.0
        private var count = 0
        private var lastUpdateMs = 0L
        private var integral = 0.0
        private var applied = 0.0
        private var appliedTrack: AudioTrack? = null
        /** Tonausgabe, die eine geänderte Abtastrate abgelehnt hat. */
        private var refusedTrack: AudioTrack? = null

        fun onFrame(targetNs: Long) {
            val track = currentAudioTrack
            // Nur Stereo-PCM: Mehrkanal geht auf manchen Geräten (Fire TV, HDMI) über einen direkten Weg, der eine
            // geänderte Abtastrate nicht verträgt – dort blieb der Ton (DD+ dekodiert) stumm.
            if (!config.displaySync || passthrough || playSpeed != 1f || paused || buffering || track == null ||
                track.channelCount > 2 || track === refusedTrack
            ) {
                reset(track)
                return
            }
            val phase = VsyncSampler.phase(targetNs) ?: return
            // Kreisförmig mitteln: Lagen knapp vor und knapp nach einem halben Bildwechsel heben sich sonst auf.
            sumSin += kotlin.math.sin(2 * Math.PI * phase)
            sumCos += kotlin.math.cos(2 * Math.PI * phase)
            count++
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdateMs < 1_000 || count < 10) return
            lastUpdateMs = now
            val error = kotlin.math.atan2(sumSin, sumCos) / (2 * Math.PI) // −0,5 … 0,5 Bildwechsel
            sumSin = 0.0
            sumCos = 0.0
            count = 0
            val periodS = VsyncSampler.periodNs / 1e9
            if (periodS <= 0) return
            // Bilder liegen nach dem Bildwechsel (error > 0): Uhr etwas schneller, damit sie früher fällig werden.
            val proportional = error * periodS / 10.0
            integral = (integral + error * periodS / 120.0).coerceIn(-MAX_RESAMPLE, MAX_RESAMPLE)
            val wanted = (proportional + integral).coerceIn(-MAX_RESAMPLE, MAX_RESAMPLE)
            if (track !== appliedTrack || kotlin.math.abs(wanted - applied) > 0.00002) {
                val base = track.sampleRate
                // Android meldet Ablehnung nur über den Rückgabewert: dann für diese Tonausgabe nicht mehr versuchen.
                val result = if (base > 0) runCatching { track.setPlaybackRate((base * (1 + wanted)).roundToIntSafe()) }.getOrDefault(-1) else -1
                if (result == AudioTrack.SUCCESS) {
                    applied = wanted
                    appliedTrack = track
                } else {
                    refusedTrack = track
                    runCatching { track.setPlaybackRate(base) }
                    DnaLog.w(TAG, "Ton an Bildwechsel angleichen: Tonausgabe lehnt ab ($result) – aus")
                }
            }
        }

        private fun reset(track: AudioTrack?) {
            if (applied != 0.0 && track != null && track === appliedTrack) runCatching { track.setPlaybackRate(track.sampleRate) }
            applied = 0.0
            integral = 0.0
            appliedTrack = null
            sumSin = 0.0
            sumCos = 0.0
            count = 0
        }

        private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
    }

    private val displaySync = DisplaySync()

    fun selectAudio(track: Track) {
        audioTrack = track
        preferredAudioLanguage = track.language
        if (!live && seekable) {
            // Film: Der Vorrat hat nur Ton der alten Spur – an derselben Stelle neu lesen.
            seekTo(positionUs)
        } else synchronized(lock) {
            audioQueue.forEach { queuedBytes -= it.data.size }
            audioQueue.clear()
        }
        listener.onTracks(videoTrack, audioTracks, textTracks, track)
    }

    fun setPreferredAudioLanguage(language: String?) {
        preferredAudioLanguage = language
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        VsyncSampler.remove()
        generation++
        synchronized(lock) {
            videoQueue.clear()
            audioQueue.clear()
            queuedBytes = 0
            lock.notifyAll()
        }
        source.stop()
        audioThread?.interrupt()
        audioThread = null
        val vt = videoThread
        val vh = videoHandler
        vh?.post {
            releaseVideo()
            vt?.quitSafely()
        }
        if (vh == null) vt?.quitSafely()
        videoThread = null
        videoHandler = null
    }

    /** Alles freigeben (auch die Datenquelle eines Films). */
    fun release() {
        stop()
        closeSource()
    }

    private fun closeSource() = source.close()

    private fun alive(gen: Int) = running.get() && gen == generation

    /** Ende des Films erreicht (vom Player regelmäßig abgefragt). */
    fun checkEnded(): Boolean {
        if (!inputEnded || endedSent) return endedSent
        val empty = synchronized(lock) { videoQueue.isEmpty() && audioQueue.isEmpty() }
        if (empty && (!clock.started || positionUs >= lastMediaUs - 150_000)) {
            endedSent = true
            listener.onEnded()
        }
        return endedSent
    }

    private fun markEnded() {
        inputEnded = true
        synchronized(lock) { lock.notifyAll() }
        videoHandler?.post { feedVideo() }
    }

    /** Vorrat voll: warten statt weiterlesen (begrenzter Speicher, auch bei 4K). */
    /**
     * Filme: vorübergehend weiter vorausladen (µs, 0 = wie eingestellt), z. B. solange Untertitel am Ton ausgerichtet
     * werden – der vorausgeladene Ton wird nebenbei ausgewertet. Die Grenze in Bytes ([Config.maxBufferBytes]) gilt weiter.
     */
    @Volatile var extraReadAheadUs = 0L
        set(value) {
            field = value
            synchronized(lock) { lock.notifyAll() }
        }

    /** Eine Prüfung für die ganze Wiedergabe (fragt das System höchstens alle 2 s – nicht bei jeder Probe). */
    private val memoryHeadroom by lazy { appContext?.let { de.ayutv.player.MemoryHeadroom(it) } }

    private fun waitForRoom(gen: Int, limitUs: Long) {
        val headroomOk = memoryHeadroom?.ok() ?: true
        val effectiveMaxBytes = if (headroomOk) config.maxBufferBytes else (config.maxBufferBytes * 2 / 3)
        synchronized(lock) {
            while (alive(gen) && (bufferedAheadLocked() > (if (live) limitUs else maxOf(limitUs, extraReadAheadUs)) ||
                    queuedBytes > effectiveMaxBytes)) lock.wait(50)
        }
    }

    private fun handleTracks(tracks: List<Track>) {
        val v = tracks.firstOrNull { it.codec.kind == Kind.VIDEO }
        val audios = tracks.filter { it.codec.kind == Kind.AUDIO }
        val texts = tracks.filter { it.codec.kind == Kind.TEXT }
        videoTrack = v
        audioTracks = audios
        textTracks = texts
        val current = audioTrack
        if (current == null || current !in audios) audioTrack = chooseAudio(audios)
        decideTunneling(v)
        listener.onTracks(v, audios, texts, audioTrack)
    }

    // ---------------------------------------------------------------- Tunneling (Option)

    /**
     * Tunneling in diesem Strom: Der Bild-Decoder zeigt die Bilder selbst, im Takt der Tonausgabe (gemeinsame
     * Hardware-Uhr) – wie Kodi/ExoPlayer „Tunneling“. Weniger Rechenlast bei 4K, sehr gleichmäßig. Nur, wenn eingeschaltet,
     * ein Decoder des Geräts es kann und es in dieser Sitzung nicht schon gescheitert ist.
     */
    @Volatile private var tunnelActive = false
    private var audioSessionId = 0

    private fun decideTunneling(video: Track?) {
        tunnelActive = false
        if (!config.tunneling || tunnelBroken || android.os.Build.VERSION.SDK_INT < 23 || video == null) return
        if (!supportsTunneling(video.codec.mime)) return

        // MediaTek + 4K HEVC: Tunneling ist oft instabil und führt zu Decoder-Crashes
        val isMtk = android.os.Build.HARDWARE.contains("mt", true) ||
                android.os.Build.BOARD.contains("mt", true)
        val isProblematic = isMtk && video.codec == Codec.HEVC &&
                (video.width >= 3000 || video.height >= 2000)
        if (isProblematic) {
            DnaLog.i(TAG, "Tunneling für MediaTek 4K HEVC deaktiviert")
            return
        }

        if (audioSessionId == 0) {
            audioSessionId = runCatching {
                (appContext?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.generateAudioSessionId() ?: 0
            }.getOrDefault(0)
        }
        tunnelActive = audioSessionId > 0
        if (tunnelActive) DnaLog.i(TAG, "Tunneling an (Sitzung $audioSessionId)")
    }

    /** Tunneling gescheitert: für die Sitzung aus und ohne neu starten. */
    private fun tunnelFailed(reason: String) {
        if (tunnelBroken) return
        tunnelBroken = true
        tunnelActive = false
        DnaLog.w(TAG, "Tunneling gescheitert ($reason) – weiter ohne")
        listener.onInfo("Tunneling klappt hier nicht ($reason) – weiter ohne")
        main.post {
            if (!running.get()) return@post
            val at = positionUs
            stop()
            start(at, first = false)
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun audioAttributes(): AudioAttributes = if (tunnelActive) {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(AudioAttributes.FLAG_HW_AV_SYNC).build()
    } else ATTRIBUTES

    private fun AudioTrack.Builder.withSession(): AudioTrack.Builder = apply { if (tunnelActive) setSessionId(audioSessionId) }

    /** Ton: bevorzugte Sprache, dann ein Format, das dieses Gerät spielt (dekodiert oder durchreicht). */
    private fun chooseAudio(audios: List<Track>): Track? {
        val playable = audios.filter { canPlayAudio(it.codec) }.ifEmpty { audios }
        val language = preferredAudioLanguage?.lowercase()
        return playable.firstOrNull { language != null && sameLanguage(it.language, language) }
            ?: playable.firstOrNull { it.isDefault } ?: playable.firstOrNull()
    }

    private fun handleSample(sample: Sample, gen: Int) {
        if (gen != generation) return
        val track = sample.track
        when (track.codec.kind) {
            Kind.TEXT -> {
                runCatching { listener.onText(sample) }
                return
            }
            Kind.VIDEO -> if (track != videoTrack || !videoEnabled) return
            Kind.AUDIO -> {
                if (track != audioTrack) return
                audioTap?.let { tap -> runCatching { tap(sample) } }
            }
        }
        if (track.codec.kind == Kind.VIDEO) noteVideoRate(sample)
        synchronized(lock) {
            if (track.codec.kind == Kind.VIDEO) videoQueue.addLast(sample) else audioQueue.addLast(sample)
            queuedBytes += sample.data.size
            val end = sample.timeUs + sample.durationUs
            if (end > lastMediaUs) lastMediaUs = end
            lock.notifyAll()
        }
        if (track.codec.kind == Kind.VIDEO) videoHandler?.post { feedVideo() }
    }

    /**
     * Für den eigenen Ton-Decoder: an [first] die direkt folgenden kleinen Pakete derselben Spur anhängen (bis ~20 ms
     * bzw. 64 KB), solange sie schon im Vorrat liegen. Nur für Formate, deren Rahmen der Parser im Decoder trennt.
     */
    private fun gatherSmall(first: Sample): ByteArray {
        if (first.data.size > GATHER_BYTES / 4) return first.data
        val parts = ArrayList<ByteArray>()
        parts += first.data
        var total = first.data.size
        synchronized(lock) {
            while (true) {
                val next = audioQueue.firstOrNull() ?: break
                if (next.track != first.track || next.timeUs - first.timeUs >= GATHER_US || total + next.data.size > GATHER_BYTES) break
                audioQueue.removeFirst()
                queuedBytes -= next.data.size
                parts += next.data
                total += next.data.size
            }
            lock.notifyAll()
        }
        if (parts.size == 1) return first.data
        val out = ByteArray(total)
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    private fun bufferedAheadLocked(): Long {
        val now = if (clock.started) clock.nowUs() else seekTargetUs
        fun ahead(q: ArrayDeque<Sample>) = q.lastOrNull()?.let { (it.timeUs - now).coerceAtLeast(0) } ?: 0L
        val v = ahead(videoQueue)
        val a = ahead(audioQueue)
        return when {
            audioTrack == null -> v
            videoTrack == null -> a
            else -> maxOf(v, a)
        }
    }

    private fun startBufferReached(): Boolean = synchronized(lock) { startBufferReachedLocked() }

    private fun startBufferReachedLocked(): Boolean {
        if (clock.started || inputEnded) return true
        val first = minOf(videoQueue.firstOrNull()?.timeUs ?: Long.MAX_VALUE, audioQueue.firstOrNull()?.timeUs ?: Long.MAX_VALUE)
        val last = maxOf(videoQueue.lastOrNull()?.timeUs ?: Long.MIN_VALUE, audioQueue.lastOrNull()?.timeUs ?: Long.MIN_VALUE)
        val from = maxOf(first, seekTargetUs)
        // Live mit Bild: erst starten, wenn die Datenrate gemessen ist (4K braucht mehr Vorlauf als die schnelle Vorgabe).
        if (live && videoTrack != null && videoEnabled && !rateMeasured && queuedBytes < config.maxBufferBytes / 2 && audioQueue.size < 200) return false
        val frameLimit = if (highBitrate) 180 else 90
        return (last != Long.MIN_VALUE && last - from >= startBufferUs()) || videoQueue.size > frameLimit || audioQueue.size > 400 ||
            queuedBytes > config.maxBufferBytes / 2
    }

    // ================================================================ Bild

    private fun restartVideoDecoder() {
        videoHandler?.post { releaseVideo() }
    }

    /** Auf dem Video-Thread: Proben an den Decoder geben, ihn bei Bedarf anlegen. Wirft nie. */
    private fun feedVideo() {
        try {
            feedVideoUnsafe()
        } catch (e: Exception) {
            DnaLog.w(TAG, "Bild: ${e.message}")
            releaseVideo()
            listener.onInfo("Bild-Decoder gestört (${e.javaClass.simpleName})")
        }
    }

    private fun feedVideoUnsafe() {
        if (!running.get()) return
        val target = surface ?: return
        // Software-Decoder läuft: er holt sich die Bilder selbst (eigener Thread).
        if (softVideo != null) return
        var decoder = video
        if (decoder == null) {
            val first = synchronized(lock) {
                // Bis zum ersten Schlüsselbild alles verwerfen (Parametersätze darin merken).
                while (videoQueue.isNotEmpty() && !videoQueue.first().keyframe) {
                    val dropped = videoQueue.removeFirst()
                    queuedBytes -= dropped.data.size
                    noteParameterSets(dropped)
                }
                videoQueue.firstOrNull()
            } ?: return
            if (!startBufferReached()) return
            // Schlüsselbild ohne eigene Parametersätze: mit den zuletzt gesehenen (der Decoder bekommt sie als csd).
            val cfg = VideoConfig.forTrack(first.track, first.data)
                ?: parameterSets?.takeIf { parameterSetsTrack == first.track }?.let { VideoConfig.forTrack(first.track, it + first.data) }
                ?: run {
                    synchronized(lock) { videoQueue.removeFirstOrNull()?.let { queuedBytes -= it.data.size; noteParameterSets(it) } }
                    if (videoNoConfigLogged++ == 0) DnaLog.w(TAG, "Bild: Schlüsselbild ohne Parametersätze (${first.track.codec}) – warte auf das nächste")
                    videoHandler?.post { feedVideo() }
                    return
                }
            if (cfg != videoConfig) {
                videoConfig = cfg
                videoCandidates = decoderCandidates(cfg)
                videoCandidateIndex = 0
                videoFailures = 0
                // Scheiterten die Decoder des Geräts an genau dieser Art Bild schon einmal (etwa 4K HDR10 auf MediaTek):
                // gleich der eigene – sonst kostet jeder Senderwechsel wieder mehrere Sekunden Fehlversuche.
                useSoftwareVideo = softwareKey(cfg) in hardwareFailed && softVideoSupported(cfg.mime)
                if (useSoftwareVideo) DnaLog.i(TAG, "Bild: Decoder des Geräts scheiterten hieran schon – gleich eigener Software-Decoder")
                val sps = if (cfg.mime == Codec.HEVC.mime) cfg.csd0?.let { runCatching { de.ayutv.player.HevcSps.find(it)?.describe() }.getOrNull() } else null
                DnaLog.i(TAG, "Bild: ${cfg.mime} ${cfg.width}×${cfg.height} ${cfg.bitDepth} Bit" + (sps?.let { " · HEVC-Kopf: $it" } ?: "") +
                    " · Decoder-Reihenfolge: ${videoCandidates.joinToString()}")
                if (videoCandidates.isEmpty() && !softVideoSupported(cfg.mime)) {
                    listener.onError("Kein Bild-Decoder für ${cfg.mime} ${cfg.width}×${cfg.height} auf diesem Gerät", fatal = true)
                    return
                }
            }
            if (useSoftwareVideo) {
                startSoftVideo(cfg, target)
                return
            }
            val name = videoCandidates.getOrNull(videoCandidateIndex) ?: run {
                if (softVideoSupported(cfg.mime)) {
                    useSoftwareVideo = true
                    startSoftVideo(cfg, target)
                } else listener.onError("Bild-Decoder scheitern an ${cfg.mime} ${cfg.width}×${cfg.height}", fatal = true)
                return
            }
            decoder = try {
                VideoDecoder(cfg, target, name, generation)
            } catch (e: Exception) {
                DnaLog.w(TAG, "Video-Decoder $name: ${e.message}")
                nextCandidate("$name startet nicht (${e.javaClass.simpleName})")
                videoHandler?.post { feedVideo() }
                return
            }
            video = decoder
            listener.onVideoSize(cfg.width, cfg.height)
        }
        decoder.guarded { decoder.pump() }
    }

    /** Nötige Größe des Eingabepuffers, wenn ein Bild größer war als vorgesehen (bleibt für die Sitzung). */
    @Volatile private var inputSizeHint = 0

    /** Zuletzt im Strom gesehene Parametersätze (Annex B) und ihre Spur; nur auf dem Video-Thread bzw. unter [lock]. */
    private var parameterSets: ByteArray? = null
    private var parameterSetsTrack: Track? = null
    private var videoNoConfigLogged = 0

    private fun noteParameterSets(sample: Sample) {
        val found = runCatching { VideoConfig.parameterSets(sample.track.codec, sample.data) }.getOrNull() ?: return
        // SPS und PPS können getrennt kommen: zusammenführen, solange es dieselbe Spur ist.
        val previous = parameterSets?.takeIf { parameterSetsTrack == sample.track }
        parameterSets = if (previous == null || found.size > 2_000) found else mergeParameterSets(sample.track.codec, previous, found)
        parameterSetsTrack = sample.track
    }

    /** Je Art (VPS/SPS/PPS) den neuesten Satz behalten. */
    private fun mergeParameterSets(codec: Codec, old: ByteArray, new: ByteArray): ByteArray {
        fun type(nal: ByteArray) = if (codec == Codec.H264) nal[0].toInt() and 0x1F else (nal[0].toInt() shr 1) and 0x3F
        val byType = LinkedHashMap<Int, ByteArray>()
        (VideoConfig.nalUnits(old) + VideoConfig.nalUnits(new)).forEach { if (it.isNotEmpty()) byType[type(it)] = it }
        val out = java.io.ByteArrayOutputStream()
        byType.values.forEach { out.write(byteArrayOf(0, 0, 0, 1)); out.write(it) }
        return out.toByteArray()
    }

    /** Zum nächsten Decoder-Kandidaten (auf dem Video-Thread). */
    private fun nextCandidate(reason: String) {
        videoCandidateIndex++
        videoFailures = 0
        if (videoCandidateIndex >= videoCandidates.size) {
            // Wie VLC: Scheitern alle Decoder des Geräts, dekodiert die App selbst (FFmpeg, alle Kerne).
            val cfg = videoConfig
            if (cfg != null && softVideoSupported(cfg.mime)) {
                useSoftwareVideo = true
                rememberHardware(softwareKey(cfg), failed = true)
                listener.onInfo("$reason – eigener Software-Decoder")
            } else listener.onError("Bild-Decoder scheitern an diesem Stream ($reason)", fatal = true)
        } else listener.onInfo("$reason – nächster Decoder")
    }

    /** Alle Decoder des Geräts scheiterten: eigener Software-Decoder für diese Konfiguration. */
    @Volatile private var useSoftwareVideo = false
    @Volatile private var softVideo: SoftVideo? = null

    /** Beide Bild-Decoder (Gerät, eigener) freigeben – auf dem Video-Thread. */
    private fun releaseVideo() {
        video?.release()
        video = null
        softVideo?.release()
        softVideo = null
    }

    private fun startSoftVideo(cfg: VideoConfig, target: Surface) {
        if (softVideo != null) return
        val soft = runCatching { SoftVideo(cfg, target, generation) }
            .onFailure { DnaLog.w(TAG, "Software-Decoder: ${it.message}") }.getOrNull()
        if (soft == null) {
            listener.onError("Bild-Decoder scheitern an ${cfg.mime} ${cfg.width}×${cfg.height}", fatal = true)
            return
        }
        softVideo = soft
        listener.onVideoSize(cfg.width, cfg.height)
    }

    /**
     * Eigener Software-Decoder (FFmpeg), wie VLC, wenn kein Decoder des Geräts ein Bild liefert (etwa 4K HDR10 auf dem
     * MediaTek-Decoder des Xiaomi 11T). Zwei Threads: einer dekodiert ohne Pause (Proben aus [videoQueue] hinein, fertige
     * Bilder verkleinert auf höchstens Full HD in einen kleinen Vorrat), der andere zeigt sie zur Uhr. So dekodiert
     * FFmpeg weiter, während ein Bild auf seinen Zeitpunkt wartet, und ein schweres Bild (Schlüsselbild) verbraucht
     * den Vorrat statt zu ruckeln. Kommt er trotzdem nicht mit, spart er stufenweise (Entblockung, dann Bilder ohne
     * Bezug) – mit Geduld beim Zurückschalten, damit es nicht im Takt von Sekunden hin und her springt.
     */
    private inner class SoftVideo(val config: VideoConfig, private val surface: Surface, val gen: Int) {
        private val decoder: NativeVideo.Decoder
        @Volatile private var released = false
        @Volatile private var firstFrameShown = false
        private val frameLock = Object()
        /** Fertige Bilder für die Anzeige, nach Zeit. */
        private val ready = ArrayDeque<NativeVideo.Frame>()
        /** Gezeigte Bilder zum Wiederverwenden (keine neuen Puffer je Bild). */
        private val spare = ArrayDeque<NativeVideo.Frame>()
        /** Von der Anzeige verworfene (zu späte) Bilder, noch nicht beim Sparen berücksichtigt. */
        private val lateAtDisplay = java.util.concurrent.atomic.AtomicInteger()

        // Sparstufe (nur auf dem Dekodier-Thread): 4K ab Stufe 1 (Entblockung nur bei Bildern ohne Bezug aus) –
        // so macht es VLC auf schnellen Handys immer, ohne sichtbaren Unterschied.
        private val minHurry = if (config.width.toLong() * config.height > 1920L * 1088) 1 else 0
        private var hurry = minHurry
        private var lateInWindow = 0
        private var lateWindowMs = 0L
        private var lastLateMs = 0L
        private var lastHurryChangeMs = 0L
        private var lastRelaxMs = 0L
        private var relaxAfterMs = HURRY_RELAX_MS

        private val decodeThread: Thread
        private val presentThread: Thread

        init {
            // Höchstens Full HD: reicht fürs Handy, und Fernseher rechnen ohnehin hoch – 4K in Software umzurechnen
            // kostet mehr, als die Kerne neben dem Dekodieren übrig haben. Alle Kerne zum Dekodieren (wie VLC).
            val extra = ((config.csd0 ?: ByteArray(0)) + (config.csd1 ?: ByteArray(0))).takeIf { it.isNotEmpty() }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            decoder = NativeVideo.open(config.mime, extra, 1920, 1080, threads)
                ?: throw IllegalStateException("FFmpeg ${config.mime} öffnet nicht")
            videoDecoderName = "FFmpeg-Software"
            DnaLog.i(TAG, "Video: eigener Software-Decoder (FFmpeg) ${config.mime} ${config.width}×${config.height} ${config.bitDepth} Bit, $threads Threads")
            listener.onInfo("DNA Engine · FFmpeg-Software")
            presentThread = Thread({ presentLoop() }, "dna-soft-present").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
            decodeThread = Thread({ decodeLoop() }, "dna-soft-video").apply {
                priority = Thread.MAX_PRIORITY - 1
                isDaemon = true
                start()
            }
        }

        private fun alive() = !released && gen == generation && running.get()

        // ------------------------------------------------------------ Dekodieren

        private fun decodeLoop() {
            var pending: Sample? = null
            var needKey = true
            try {
                while (alive()) {
                    val pts = decoder.receive()
                    if (pts != NativeVideo.NO_FRAME) {
                        take(pts)
                        continue
                    }
                    val sample = pending ?: synchronized(lock) {
                        videoQueue.removeFirstOrNull()?.also { queuedBytes -= it.data.size; lock.notifyAll() }
                            ?: run { lock.wait(10); null }
                    } ?: continue
                    pending = null
                    if (needKey && !sample.keyframe) {
                        droppedFrames++
                        continue
                    }
                    needKey = false
                    adjustHurry()
                    decoder.hurry(if (sample.timeUs < seekTargetUs - 1_000) NativeVideo.HURRY_SEEK else hurry)
                    when (decoder.send(sample.data, sample.timeUs)) {
                        NativeVideo.SEND_FULL -> pending = sample // erst Bilder abholen, dann dieselbe Probe noch einmal
                        NativeVideo.SEND_ERROR -> {
                            DnaLog.w(TAG, "FFmpeg-Software: Probe nicht dekodierbar – weiter ab dem nächsten Schlüsselbild")
                            decoder.flush()
                            needKey = true
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // beendet
            } catch (e: Exception) {
                DnaLog.w(TAG, "FFmpeg-Software: ${e.javaClass.simpleName} ${e.message}")
                if (alive()) listener.onError("Software-Decoder gestört (${e.javaClass.simpleName})", fatal = true)
            } finally {
                released = true
                synchronized(frameLock) { frameLock.notifyAll() }
                decoder.release()
            }
        }

        /** Fertiges Bild übernehmen: zu spät gleich verwerfen (spart das Umrechnen), sonst umrechnen in den Vorrat. */
        private fun take(ptsUs: Long) {
            if (ptsUs < seekTargetUs - 1_000) {
                decoder.drop()
                return
            }
            if (firstFrameShown && clock.started) {
                val earlyUs = ((ptsUs - clock.nowUs()) / playSpeed).toLong()
                // Weit daneben (Sprung im Live-Strom) entscheidet die Anzeige (stellt die Uhr neu).
                if (earlyUs < -LATE_US && !(live && kotlin.math.abs(earlyUs) > JUMP_US)) {
                    decoder.drop()
                    droppedFrames++
                    noteLate(1)
                    return
                }
            }
            // Vorrat voll: warten (der Decoder ist voraus – genau richtig).
            val target = synchronized(frameLock) {
                while (alive() && ready.size >= SOFT_QUEUE_FRAMES) frameLock.wait(20)
                spare.removeFirstOrNull()
            }
            if (!alive()) {
                decoder.drop()
                return
            }
            val frame = decoder.convert(target)
            if (frame == null) {
                if (target != null) synchronized(frameLock) { spare.addLast(target) }
                return
            }
            frame.ptsUs = ptsUs
            synchronized(frameLock) {
                ready.addLast(frame)
                frameLock.notifyAll()
            }
        }

        private fun noteLate(count: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lateWindowMs > HURRY_WINDOW_MS) {
                lateWindowMs = now
                lateInWindow = 0
            }
            lateInWindow += count
            lastLateMs = now
            // Mehrere zu späte Bilder kurz nacheinander: eine Stufe mehr sparen – aber erst, wenn die letzte Stufe
            // Zeit hatte zu wirken (die Bilder in Arbeit kommen sonst alle noch zu spät und treiben die Stufe hoch).
            if (lateInWindow >= 3 && hurry < NativeVideo.HURRY_MAX && now - lastHurryChangeMs >= HURRY_SETTLE_MS) {
                // Gerade erst zurückgeschaltet und schon wieder zu langsam: beim nächsten Mal länger warten.
                if (lastRelaxMs > 0 && now - lastRelaxMs < relaxAfterMs) relaxAfterMs = (relaxAfterMs * 2).coerceAtMost(HURRY_RELAX_MAX_MS)
                hurry++
                lastHurryChangeMs = now
                lateInWindow = 0
                DnaLog.i(TAG, "FFmpeg-Software: zu langsam – " + when (hurry) {
                    1 -> "Entblockung nur bei Bildern ohne Bezug aus"
                    2 -> "Entblockung ganz aus"
                    else -> "lasse Bilder ohne Bezug aus"
                })
            }
        }

        private fun adjustHurry() {
            val late = lateAtDisplay.getAndSet(0)
            if (late > 0) noteLate(late)
            if (hurry <= minHurry) return
            val now = SystemClock.elapsedRealtime()
            if (now - maxOf(lastLateMs, lastHurryChangeMs) >= relaxAfterMs) {
                hurry--
                lastHurryChangeMs = now
                lastRelaxMs = now
                DnaLog.i(TAG, "FFmpeg-Software: wieder schnell genug – Sparstufe $hurry")
            }
        }

        // ------------------------------------------------------------ Anzeige

        private fun presentLoop() {
            var renderer: GlYuvRenderer? = null
            try {
                // Die Bildfläche kann noch kurz am vorigen Decoder hängen (wird gerade freigegeben): dann meldet EGL
                // „belegt“ (12291, EGL_BAD_ALLOC). Ein paar Mal kurz warten statt gleich mit Fehler abzubrechen.
                var attempt = 0
                while (renderer == null && alive()) {
                    try {
                        renderer = GlYuvRenderer(surface)
                    } catch (e: Exception) {
                        if (++attempt >= 15) {
                            DnaLog.w(TAG, "FFmpeg-Software: ${e.message}")
                            if (alive()) listener.onError("Bild lässt sich nicht anzeigen (${e.message})", fatal = true)
                            return
                        }
                        Thread.sleep(100)
                    }
                }
                if (attempt > 0 && renderer != null) DnaLog.i(TAG, "FFmpeg-Software: Bildfläche nach ${attempt * 100} ms frei")
                while (alive()) {
                    val frame = synchronized(frameLock) {
                        while (alive() && ready.isEmpty()) frameLock.wait(20)
                        ready.removeFirstOrNull()
                    } ?: continue
                    try {
                        show(renderer ?: break, frame)
                    } finally {
                        synchronized(frameLock) {
                            spare.addLast(frame)
                            frameLock.notifyAll()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // beendet
            } catch (e: Exception) {
                DnaLog.w(TAG, "FFmpeg-Software (Anzeige): ${e.javaClass.simpleName} ${e.message}")
                if (alive()) listener.onError("Software-Decoder gestört (${e.javaClass.simpleName})", fatal = true)
            } finally {
                released = true
                synchronized(lock) { lock.notifyAll() }
                synchronized(frameLock) { frameLock.notifyAll() }
                renderer?.release()
            }
        }

        /** Ein fertiges Bild zur Uhr zeigen – oder verwerfen, wenn es zu spät ist. */
        private fun show(renderer: GlYuvRenderer, frame: NativeVideo.Frame) {
            val ptsUs = frame.ptsUs
            if (ptsUs < seekTargetUs - 1_000) return
            val audioPlayable = audioTrack?.let { canPlayAudio(it.codec) } == true
            if (!clock.started) clock.startFromVideo(ptsUs, hasAudio = audioPlayable)
            while (alive() && firstFrameShown && (paused || buffering)) Thread.sleep(20)
            if (!alive()) return
            var earlyUs = ((ptsUs - clock.nowUs()) / playSpeed).toLong()
            if (live && kotlin.math.abs(earlyUs) > JUMP_US) {
                clock.reset()
                clock.startFromVideo(ptsUs, hasAudio = audioPlayable)
                earlyUs = 0
            }
            if (firstFrameShown && earlyUs < -LATE_US) {
                droppedFrames++
                lateAtDisplay.incrementAndGet()
                return
            }
            // Bis kurz vor der Anzeigezeit warten (nicht länger als 0,5 s am Stück, dann neu rechnen).
            while (firstFrameShown && alive() && earlyUs > 2_000) {
                Thread.sleep((earlyUs / 1000).coerceIn(1, 500))
                if (paused || buffering) return show(renderer, frame)
                earlyUs = ((ptsUs - clock.nowUs()) / playSpeed).toLong()
            }
            if (!alive()) return
            renderer.draw(frame, System.nanoTime() + earlyUs.coerceAtLeast(0) * 1000)
            renderedFrames++
            if (!firstFrameShown) {
                firstFrameShown = true
                DnaLog.i(TAG, "FFmpeg-Software: erstes Bild gezeigt (${frame.width}×${frame.height}, ${if (frame.transfer == 16) "HDR10 → SDR" else "SDR"})")
                if (videoStartUs == Long.MIN_VALUE) videoStartUs = ptsUs
                listener.onFirstFrame()
                if (!audioPlayable) setBuffering(false)
                else videoHandler?.postDelayed({
                    if (gen == generation && running.get() && !audioStarted && buffering && !paused) {
                        DnaLog.w(TAG, "Ton startet nicht (${audioDecoderName ?: "kein Decoder"}) – Bild läuft ohne Ton los")
                        audioLate = true
                        setBuffering(false)
                    }
                }, AUDIO_START_GRACE_MS)
            }
        }

        fun release() {
            if (released) return
            released = true
            synchronized(lock) { lock.notifyAll() }
            synchronized(frameLock) { frameLock.notifyAll() }
            // Die Threads geben Decoder und Anzeige selbst frei (nie während sie arbeiten).
        }
    }

    /**
     * Ein Video-Decoder für eine Konfiguration. Eingabe aus [videoQueue], Ausgabe zum Darstellungszeitpunkt an die
     * Bildfläche. Läuft auf dem Video-Thread (Rückrufe kommen dort an). Jeder Aufruf an MediaCodec ist abgesichert.
     */
    private inner class VideoDecoder(val config: VideoConfig, surface: Surface, val name: String, val gen: Int) {
        private val surfaceForRate: Surface? = surface
        private val codec: MediaCodec
        private val freeInputs = ArrayDeque<Int>()
        private val syncMode = android.os.Build.VERSION.SDK_INT < 31
        private var dropRasl = config.mime == Codec.HEVC.mime
        /** Weit hinter der Uhr: Eingaben bis zum nächsten Schlüsselbild auslassen (Decoder holt auf). */
        private var skipToKey = false
        private var lastRenderNs = 0L
        /** Tunneling für diesen Decoder (eingeschaltet und von ihm unterstützt). */
        private val tunneled: Boolean = tunnelActive && decoderSupportsTunneling(name, config.mime)

        /** Tunneling: Der Decoder hat ein Bild gezeigt. */
        private fun tunneledFrameShown(ptsUs: Long) {
            if (released) return
            renderedFrames++
            if (!firstFrameShown) {
                DnaLog.i(TAG, "$name: erstes Bild gezeigt (Tunneling)")
                firstFrameShown = true
                if (videoStartUs == Long.MIN_VALUE) videoStartUs = ptsUs
                videoFailures = 0
                listener.onFirstFrame()
            }
        }

        /** GPU-Umrechnung der Halbbilder (nur 1080i, wenn eingeschaltet). */
        private var deinterlacer: GlDeinterlacer? = null
        /** Über die GPU-Umrechnung erst kurz vor der Zeit freigeben (sie nimmt nur ein Bild auf einmal an). */
        private val holdUs get() = if (deinterlacer != null) DEINTERLACE_HOLD_US else HOLD_US
        // Gleichmäßige Anzeigezeiten: Bezugspunkt (Bildzeit → Systemzeit), an dem die Bilder im festen Abstand ihrer
        // Zeitstempel ausgerichtet werden (wie Media3).
        private var anchorPtsUs = Long.MIN_VALUE
        private var anchorNs = 0L

        /**
         * Anzeigezeit eines Bildes: im festen Abstand der Zeitstempel ab einem Bezugspunkt statt direkt aus der Uhr (deren
         * kleine Schwankungen sonst jedes Bild etwas verschieben), dann auf den nächsten Bildwechsel des Displays gelegt.
         * Bei 25 Bildern/s auf 50 Hz steht so jedes Bild genau zwei Bildwechsel. Langsames Auseinanderlaufen von Uhr und
         * Display wird nachgeführt; größere Abweichungen (Sprung, Hänger) setzen den Bezugspunkt neu.
         */
        // Bildrate aus den Zeitstempeln (für die Bildwechselrate des Displays, Android 11+).
        private var rateFirstPts = Long.MIN_VALUE
        private var rateFrames = 0
        private var announcedFps = 0f

        /**
         * Nach 50 gezeigten Bildern die Bildrate an die Bildfläche melden (Surface.setFrameRate, wie Media3): Handys mit
         * 60/90/120 Hz schalten dann, wo möglich, auf eine passende Rate (etwa 50/100 Hz bei 25 Bildern/s) – sonst steht
         * jedes Bild mal 2, mal 3 Bildwechsel (Ruckeln). Fernseher stellt die App ohnehin selbst um.
         */
        private fun noteFrameRate(ptsUs: Long) {
            if (android.os.Build.VERSION.SDK_INT < 30 || isTv) return
            if (rateFirstPts == Long.MIN_VALUE || ptsUs < rateFirstPts) {
                rateFirstPts = ptsUs
                rateFrames = 0
                return
            }
            if (++rateFrames < 50) return
            val fps = (rateFrames * 1_000_000.0 / (ptsUs - rateFirstPts).coerceAtLeast(1)).toFloat()
            rateFirstPts = ptsUs
            rateFrames = 0
            val rounded = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f).minBy { kotlin.math.abs(it - fps) }
                .takeIf { kotlin.math.abs(it - fps) < 1f } ?: return
            if (rounded == announcedFps) return
            announcedFps = rounded
            runCatching {
                surfaceForRate?.setFrameRate(rounded, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                DnaLog.i(TAG, "Bildrate $rounded an die Bildfläche gemeldet")
            }
        }

        private fun releaseTimeNs(ptsUs: Long, wantedNs: Long, snap: Boolean = true): Long {
            var target = wantedNs
            if (anchorPtsUs != Long.MIN_VALUE) {
                val regular = anchorNs + ((ptsUs - anchorPtsUs) * 1000 / playSpeed).toLong()
                val drift = wantedNs - regular
                if (kotlin.math.abs(drift) < SMOOTH_FRAME_NS) {
                    anchorNs += drift / 32
                    target = regular + drift / 32
                } else {
                    anchorPtsUs = ptsUs
                    anchorNs = wantedNs
                }
            } else {
                anchorPtsUs = ptsUs
                anchorNs = wantedNs
            }
            displaySync.onFrame(target)
            return if (snap) VsyncSampler.adjust(target) else target
        }
        private var lateSumUs = 0L
        private var lateCount = 0
        private var lateLogAt = 0L
        @Volatile private var released = false
        private var firstFrameShown = false
        private var endOfStreamQueued = false
        /** Schlicht eingerichtet (wie VLC) statt wie Media3. */
        private var plainFormat = false
        /** Einrichtung dieses Versuchs (siehe [VARIANT_TUNED] …). */
        private var variant = VARIANT_TUNED

        init {
            /**
             * [tuned]: wie Media3 (Platz für Auflösungswechsel, Echtzeit-Vorrang, bei 4K Arbeitstakt 60 Bilder/s).
             * Sonst schlicht wie VLC: nur Art, Größe, Parametersätze, Eingabepuffer. Der MediaTek-Decoder des Xiaomi 11T
             * brach bei 4K HDR10 mit der Media3-Einrichtung ab (auch mit ExoPlayer), VLC spielte. Bei 10 Bit daher erst
             * schlicht, beim zweiten Versuch wie Media3; bei 8 Bit umgekehrt.
             */
            fun format(tuned: Boolean) = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
                config.csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                config.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                // Eingabepuffer genau wie Media3 (Pixel × 3/2 ÷ Mindest-Kompression: H.264 2, HEVC 4; H.264 auf Makroblöcke
                // gerundet). Größere Bilder legen den Decoder mit größerem Puffer neu an (siehe pump).
                val w = maxOf(config.width, 1920)
                val h = maxOf(config.height, 1080)
                val pixels = if (config.mime == Codec.H264.mime) ((w + 15) / 16) * ((h + 15) / 16) * 256 else w * h
                val minCompression = if (config.mime == Codec.HEVC.mime) 4 else 2
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(pixels * 3 / (2 * minCompression), inputSizeHint))
                if (tunneled) {
                    setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true)
                    setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, audioSessionId)
                }
                if (tuned) {
                    // Platz für Auflösungswechsel im Strom (HD ↔ 4K), ohne neu anzulegen.
                    setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(config.width, 1920))
                    setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(config.height, 1080))
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    // Nie gedrosselt (ein Arbeitstakt unter der Bildrate lässt 50-Bilder-Sender ruckeln): nur bei 4K mehr.
                    if (config.width * config.height > 1920 * 1088) setFloat(MediaFormat.KEY_OPERATING_RATE, 60f)
                }
            }
            // Versuche je Decoder: 8 Bit erst wie Media3, dann schlicht. 10 Bit erst schlicht, dann wie Media3, dann über
            // die GPU (Decoder → SurfaceTexture → Anzeige per OpenGL): Der MediaTek-Decoder des 11T bricht genau beim
            // Umstellen auf HDR-Ausgabe an die Anzeige ab – mit der GPU als Abnehmer bekommt er andere Puffer.
            val variants = variantsFor(config)
            val order = workingVariant[name]?.takeIf { it in variants }?.let { listOf(it) + (variants - it) } ?: variants
            variant = order[videoFailures % order.size]
            val tunedNow = variant == VARIANT_TUNED
            plainFormat = !tunedNow
            // Wie Media3: Echtzeit-Vorrang und, bei 4K, ein Arbeitstakt für 60 Bilder/s. Manche Decoder (MediaTek in
            // Handys) laufen sonst gedrosselt – 4K ruckelt oder kommt zu spät. Lehnt ein Decoder das ab, ohne diese Angaben.
            // Halbbilder (1080i): Der Decoder schreibt in die Fläche der GPU-Umrechnung, die 50 Bilder/s zeigt. Klappt das
            // Einrichten nicht, direkt (wie bisher). Nicht mit Tunneling (dort zeigt der Decoder selbst).
            val viaGpu = variant == VARIANT_GPU && !tunneled
            deinterlacer = if (viaGpu || config.interlaced && this@DnaEngineCore.config.deinterlace && !tunneled) {
                runCatching { GlDeinterlacer(surface, config.height, deinterlace = !viaGpu) }
                    .onFailure { DnaLog.w(TAG, "Deinterlacing aus: ${it.message}") }.getOrNull()
            } else null
            val target = deinterlacer?.inputSurface ?: surface
            codec = try {
                if (tunedNow) startCodec(name, format(tuned = true), target) ?: startCodec(name, format(tuned = false), target, last = true)!!
                else startCodec(name, format(tuned = false), target, last = true)!!
            } catch (e: Exception) {
                deinterlacer?.release()
                if (tunneled) tunnelFailed("Decoder startet nicht")
                throw e
            }
            // Erst nach dem Konstruktor (auf demselben Thread) – und nur, wenn er durchlief: Scheiterte das Anlegen danach
            // noch, war syncLoop nie gesetzt und der Aufruf beendete die App (Absturz in 0.60.1 auf dem Fire TV).
            if (syncMode) videoHandler?.post { if (!released && video === this@VideoDecoder) syncLoop.run() }
            videoDecoderName = name + if (deinterlacer != null) " · Halbbilder → 50 B/s" else ""
            DnaLog.i(TAG, "Video: $name ${config.mime} ${config.width}×${config.height} ${config.bitDepth} Bit" +
                (when (variant) { VARIANT_GPU -> " (über die GPU)"; VARIANT_PLAIN -> " (schlicht wie VLC)"; else -> "" }) +
                (if (config.interlaced) ", Halbbilder" + (if (deinterlacer != null) " (GPU-Deinterlacing)" else "") else ""))
            listener.onInfo("DNA Engine · $name")
        }

        /** Decoder anlegen und starten; null bei Fehler (mit [last] = wirft den Fehler weiter). */
        private fun startCodec(name: String, format: MediaFormat, surface: Surface, last: Boolean = false): MediaCodec? {
            val codec = MediaCodec.createByCodecName(name)
            try {
                // Wie Media3: asynchron erst ab Android 12. Davor (alle Fire-OS-Versionen, ältere Handys) synchron im
                // Takt abfragen – die Rückrufe sind dort bei manchen Decodern (MediaTek, Amlogic) unzuverlässig.
                if (!syncMode) codec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) = inputAvailable(index)
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                        outputAvailable(index, info.presentationTimeUs, info.flags)
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                        DnaLog.w(TAG, "Video-Decoder-Fehler $name: ${e.diagnosticInfo}")
                        if (released) return
                        failed(e.diagnosticInfo ?: "Fehler")
                    }
                    override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = formatChanged(format)
                }, videoHandler)
                // Tunneling: Bilder zeigt der Decoder selbst – gezählt wird über diese Meldung.
                if (tunneled && android.os.Build.VERSION.SDK_INT >= 23) {
                    codec.setOnFrameRenderedListener({ _, presentationTimeUs, _ -> tunneledFrameShown(presentationTimeUs) }, videoHandler)
                }
                codec.configure(format, surface, null, 0)
                codec.start()
                return codec
            } catch (e: Exception) {
                runCatching { codec.release() }
                if (last) throw e
                DnaLog.w(TAG, "Video-Decoder $name mit Echtzeit-Angaben: ${e.javaClass.simpleName} – ohne")
                return null
            }
        }

        /** Mindestens ein Schlüsselbild ging an den Decoder (ab dann zählt die Frist für das erste Bild). */
        private var fedKeyframe = false
        private var fedAtMs = 0L
        private var inputsQueued = 0
        private var outputsSeen = 0

        private fun inputAvailable(index: Int) {
            if (released) return
            freeInputs.addLast(index)
            guarded { pump() }
        }

        private fun outputAvailable(index: Int, ptsUs: Long, flags: Int) {
            if (released) return
            if (outputsSeen++ == 0) DnaLog.i(TAG, "$name: erste Ausgabe nach ${SystemClock.elapsedRealtime() - fedAtMs} ms ($inputsQueued Proben hinein)")
            if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                guarded { codec.releaseOutputBuffer(index, false) }
                return
            }
            guarded { render(index, ptsUs) }
        }

        private fun formatChanged(format: MediaFormat) {
            guarded {
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                val cw = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else w
                val ch = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else h
                deinterlacer?.videoHeight = ch
                // HDR10 über die GPU: dort auf SDR abbilden (sonst blass und dunkel).
                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    deinterlacer?.pq = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_ST2084
                listener.onVideoSize(cw, ch)
            }
        }

        /** Synchroner Betrieb: freie Eingänge holen, Proben hinein, fertige Bilder heraus – alle paar Millisekunden. */
        private val syncInfo = MediaCodec.BufferInfo()
        private val syncLoop: Runnable = object : Runnable {
            override fun run() {
                if (released) return
                var busy = false
                try {
                    while (!released) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index < 0) break
                        freeInputs.addLast(index)
                        busy = true
                    }
                    if (!released) pump()
                    while (!released) {
                        val out = codec.dequeueOutputBuffer(syncInfo, 0)
                        when {
                            out >= 0 -> {
                                outputAvailable(out, syncInfo.presentationTimeUs, syncInfo.flags)
                                busy = true
                            }
                            out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> formatChanged(codec.outputFormat)
                            out == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            else -> Unit // Ausgabepuffer geändert (alte Geräte): nichts zu tun
                        }
                    }
                } catch (e: Exception) {
                    DnaLog.w(TAG, "Video-Decoder-Fehler $name: ${e.javaClass.simpleName} ${e.message}")
                    if (!released) failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
                    return
                }
                if (!released) videoHandler?.postDelayed(this, if (busy) 1L else SYNC_POLL_MS)
            }
        }

        /**
         * Decoder, die stumm bleiben (nehmen Daten, liefern nie ein Bild – kommt bei 4K/10 Bit vor): nicht bis zum
         * Abbruch des Players warten, sondern gleich den nächsten Kandidaten nehmen.
         */
        private fun watchFirstFrame() {
            // Hardware liefert das erste Bild in Bruchteilen einer Sekunde; Software-Decoder brauchen bei 4K länger.
            val timeout = if (isHardwareName(name)) HARDWARE_SILENT_MS else SILENT_DECODER_MS
            videoHandler?.postDelayed({
                if (!released && !firstFrameShown && gen == generation && video === this && tunneled) {
                    // Manche Geräte melden gezeigte Bilder im Tunneling nicht: läuft der Ton, gilt das Bild als da.
                    if (audioStarted) tunneledFrameShown(videoStartUs.coerceAtLeast(0)) else tunnelFailed("$name zeigt kein Bild")
                } else if (!released && !firstFrameShown && gen == generation && video === this) {
                    DnaLog.w(TAG, "Video-Decoder $name: kein Bild nach ${timeout / 1000} s ($inputsQueued Proben hinein, $outputsSeen Ausgaben)")
                    release()
                    video = null
                    // Einmal frisch mit demselben Decoder in der anderen Einrichtung (schlicht/Media3) – erst dann der nächste.
                    videoFailures++
                    if (videoFailures >= variantsFor(config).size) nextCandidate("$name liefert kein Bild")
                    else listener.onInfo("$name liefert kein Bild – neu eingerichtet")
                    videoHandler?.post { feedVideo() }
                }
            }, timeout)
        }

        /** Nie die App abstürzen lassen: ein Fehler macht den Decoder unbrauchbar, es geht mit einem frischen weiter. */
        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                DnaLog.w(TAG, "Video-Decoder $name: ${e.javaClass.simpleName} ${e.message}")
                if (!released) failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        }

        private fun failed(reason: String) {
            // Nach einem Fehler meldet MediaCodec oft zweimal (Rückruf und der nächste Aufruf am schon freigegebenen
            // Decoder): nur einmal zählen – sonst wurde die zweite Einrichtung übersprungen.
            if (failureHandled) return
            failureHandled = true
            release()
            if (video === this@VideoDecoder) video = null
            if (gen != generation) return
            if (!firstFrameShown) {
                videoFailures++
                if (videoFailures >= variantsFor(config).size) nextCandidate("$name: $reason")
                else listener.onInfo("Bild-Decoder neu eingerichtet ($reason)")
            } else listener.onInfo("Bild-Decoder neu gestartet ($reason)")
            videoHandler?.post { feedVideo() }
        }
        private var failureHandled = false

        /** Proben an freie Eingabepuffer geben. */
        fun pump() {
            while (!released && freeInputs.isNotEmpty()) {
                val sample = synchronized(lock) { videoQueue.removeFirstOrNull()?.also { queuedBytes -= it.data.size; lock.notifyAll() } }
                if (sample == null) {
                    if (inputEnded && !endOfStreamQueued) {
                        endOfStreamQueued = true
                        codec.queueInputBuffer(freeInputs.removeFirst(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    return
                }
                if (skipToKey) {
                    if (!sample.keyframe) {
                        droppedFrames++
                        continue
                    }
                    skipToKey = false
                }
                if (dropRasl) {
                    when (firstVclType(sample.data)) {
                        8, 9 -> continue // RASL nach dem Einstieg: nicht dekodierbar, verwerfen
                        in 0..31 -> if (!sample.keyframe) dropRasl = false
                    }
                }
                val index = freeInputs.removeFirst()
                val buffer = codec.getInputBuffer(index) ?: continue
                if (sample.data.size > buffer.capacity()) {
                    if (sample.data.size <= MAX_INPUT_BYTES && sample.data.size > inputSizeHint) {
                        // Bild größer als der Eingabepuffer (4K mit sehr hoher Datenrate): Decoder mit größerem Puffer neu,
                        // ab diesem Bild – statt es (oft ein Schlüsselbild) zu verwerfen und Bildfehler zu haben.
                        DnaLog.i(TAG, "$name: Bild mit ${sample.data.size / 1024} KB größer als der Eingabepuffer – größerer Puffer")
                        inputSizeHint = sample.data.size + 256 * 1024
                        freeInputs.addFirst(index)
                        synchronized(lock) {
                            videoQueue.addFirst(sample)
                            queuedBytes += sample.data.size
                        }
                        release()
                        if (video === this) video = null
                        videoHandler?.post { feedVideo() }
                        return
                    }
                    // Kaputter Strom: auslassen.
                    codec.queueInputBuffer(index, 0, 0, sample.timeUs, 0)
                    continue
                }
                buffer.clear()
                buffer.put(sample.data)
                codec.queueInputBuffer(index, 0, sample.data.size, sample.timeUs, if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                inputsQueued++
                if (sample.keyframe && !fedKeyframe) {
                    // Tunneling: Das Bild erscheint erst, wenn der Ton läuft – der Ton darf ab diesem Schlüsselbild beginnen.
                    if (tunneled && videoStartUs == Long.MIN_VALUE) videoStartUs = maxOf(sample.timeUs, seekTargetUs)
                    fedKeyframe = true
                    fedAtMs = SystemClock.elapsedRealtime()
                    DnaLog.i(TAG, "$name: erstes Schlüsselbild hinein (${sample.data.size / 1024} KB, ${if (syncMode) "synchron" else "asynchron"})")
                    watchFirstFrame()
                }
            }
        }

        /** Ausgabe zum passenden Zeitpunkt zeigen (oder verwerfen, wenn zu spät bzw. vor der Spulstelle). */
        private fun render(index: Int, ptsUs: Long) {
            if (released) return
            if (ptsUs < seekTargetUs - 1_000) {
                // Vor der Spulstelle: dekodiert, aber nicht gezeigt.
                codec.releaseOutputBuffer(index, false)
                return
            }
            val audioPlayable = audioTrack?.let { canPlayAudio(it.codec) } == true
            if (!clock.started) clock.startFromVideo(ptsUs, hasAudio = audioPlayable)
            if ((paused || buffering) && firstFrameShown) {
                // Angehalten: Bild bis zum Weiterspielen zurückhalten (das erste wird trotzdem gezeigt).
                videoHandler?.postDelayed({ if (!released) guarded { render(index, ptsUs) } }, 20)
                return
            }
            val earlyUs = ((ptsUs - clock.nowUs()) / playSpeed).toLong()
            if (live && kotlin.math.abs(earlyUs) > JUMP_US) {
                // Zeitsprung im Bild (Live): Uhr neu ab diesem Bild.
                clock.reset()
                clock.startFromVideo(ptsUs, hasAudio = audioPlayable)
                videoHandler?.post { if (!released) guarded { render(index, ptsUs) } }
                return
            }
            if (firstFrameShown) noteLateness(earlyUs)
            val nowNs = System.nanoTime()
            when {
                // Weit zurück (Decoder schafft das Tempo nicht): zum nächsten Schlüsselbild springen, falls eins
                // im Vorrat liegt – sonst wird der Rückstand nie kleiner.
                firstFrameShown && earlyUs < -CATCH_UP_US && keyframeQueued() -> {
                    droppedFrames++
                    skipToKey = true
                    codec.releaseOutputBuffer(index, false)
                }
                // Zu spät, aber gerade erst ein Bild gezeigt: verwerfen. Sonst lieber spät zeigen als stehen bleiben.
                firstFrameShown && earlyUs < -LATE_US && nowNs - lastRenderNs < MIN_FRAME_GAP_NS -> {
                    droppedFrames++
                    codec.releaseOutputBuffer(index, false)
                }
                firstFrameShown && earlyUs > holdUs ->
                    videoHandler?.postDelayed({ if (!released) guarded { render(index, ptsUs) } }, ((earlyUs - holdUs) / 1000).coerceIn(1, 500))
                else -> {
                    val wantedNs = nowNs + earlyUs.coerceIn(0, HOLD_US) * 1000
                    val deint = deinterlacer
                    if (deint != null) {
                        // Über die GPU: jetzt freigeben, die Umrechnung zeigt beide Halbbilder zu ihren Zeitpunkten.
                        deint.expect(ptsUs, releaseTimeNs(ptsUs, wantedNs, snap = false))
                        codec.releaseOutputBuffer(index, true)
                    } else codec.releaseOutputBuffer(index, releaseTimeNs(ptsUs, wantedNs))
                    noteFrameRate(ptsUs)
                    lastRenderNs = nowNs
                    renderedFrames++
                    if (!firstFrameShown) {
                        DnaLog.i(TAG, "$name: erstes Bild gezeigt (${earlyUs / 1000} ms vor/nach der Uhr)")
                        firstFrameShown = true
                        if (videoStartUs == Long.MIN_VALUE) videoStartUs = ptsUs
                        videoFailures = 0
                        rememberVariant(name, variant)
                        rememberHardware(softwareKey(config), failed = false)
                        listener.onFirstFrame()
                        if (!audioPlayable) setBuffering(false)
                        else videoHandler?.postDelayed({
                            // Ton kommt nicht (Decoder hängt, Durchreichen klappt nicht): Das Bild wartet nicht länger,
                            // der Ton steigt ein, sobald er läuft.
                            if (gen == generation && running.get() && !audioStarted && buffering && !paused) {
                                DnaLog.w(TAG, "Ton startet nicht (${audioDecoderName ?: "kein Decoder"}) – Bild läuft ohne Ton los")
                                audioLate = true
                                setBuffering(false)
                            }
                        }, AUDIO_START_GRACE_MS)
                    }
                }
            }
        }

        /** Wie spät die Bilder aus dem Decoder kommen (alle 5 s ins Protokoll, zur Fehlersuche auf Geräten). */
        private fun noteLateness(earlyUs: Long) {
            lateSumUs += -earlyUs
            lateCount++
            val now = SystemClock.elapsedRealtime()
            if (now - lateLogAt >= 5_000 && lateCount > 0) {
                DnaLog.i(TAG, "Bild: im Mittel ${lateSumUs / lateCount / 1000} ms spät, gezeigt $renderedFrames, verworfen $droppedFrames")
                lateLogAt = now
                lateSumUs = 0
                lateCount = 0
            }
        }

        private fun keyframeQueued(): Boolean = synchronized(lock) { videoQueue.any { it.keyframe } }

        fun release() {
            if (released) return
            released = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
            deinterlacer?.release()
        }
    }

    // ================================================================ Ton

    @Volatile private var currentAudioTrack: AudioTrack? = null
    /** Der Ton spielt (in diesem Strom). */
    @Volatile private var audioStarted = false
    /** Das Bild lief los, ohne dass der Ton kam: Ton steigt später an der laufenden Uhr ein. */
    @Volatile private var audioLate = false

    /** Der gewählte Tonweg versagt (Decoder stumm, Tonausgabe nimmt nichts an): auf den nächsten wechseln. */
    /** [hard]: die Tonausgabe meldet einen echten Fehler (nicht nur „spielt noch nicht“ – das kann auch ein langsames HDMI sein). */
    private class AudioPathFailed(message: String, val hard: Boolean = false) : Exception(message)

    private fun audioLoop(gen: Int) {
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        var format: AudioInfo? = null
        var codecTrack: Track? = null
        var passthroughMode = false
        /** Eigener Ton-Decoder (DTS, TrueHD, Formate ohne Decoder im Gerät). */
        var native: NativeAudio.Decoder? = null
        val info = MediaCodec.BufferInfo()
        var firstPtsUs = Long.MIN_VALUE
        var outRate = 0
        var outChannels = 0
        var epoch = clock.epoch
        var lastSampleUs = Long.MIN_VALUE
        var lastSampleDurUs = 0L
        var started = false
        // Tonweg-Wechsel: Was in diesem Strom schon versagt hat, kommt nicht wieder dran.
        var avoidPassthrough = false
        var avoidDevice = false
        /** Nach einem Wechsel bzw. verspätetem Start: Ton, der hinter der laufenden Uhr liegt, überspringen. */
        var joinLate = false
        // Überwachung des laufenden Tonwegs.
        var pathSinceMs = 0L
        var pathInputs = 0
        var pathOutputs = 0
        var playingSinceMs = 0L
        fun releaseAll() {
            // Erst die Uhr lösen, dann freigeben (sonst fragt die Anzeige eine freigegebene Tonausgabe ab).
            clock.audio = null
            currentAudioTrack = null
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { track?.stop(); track?.release() }
            runCatching { native?.release() }
            decoder = null
            track = null
            native = null
        }
        fun newTrack(t: AudioTrack) {
            runCatching { t.setVolume(volume) }
            if (playSpeed != 1f) runCatching { t.playbackParams = PlaybackParams().setSpeed(playSpeed).setPitch(1f) }
            currentAudioTrack = t
            track = t
            playingSinceMs = 0L
        }
        /** Mehrkanalton, den die Tonausgabe nicht annimmt (5.1/7.1 auf manchen Handys): so viele Kanäle auf Stereo. */
        var downmixFrom = 0
        /** PCM-Tonausgabe anlegen; schafft das Gerät die Kanalzahl nicht, Stereo mit Heruntermischen. */
        fun openPcm(rate: Int, channels: Int) {
            downmixFrom = 0
            val t = try {
                createPcmTrack(rate, channels)
            } catch (e: Exception) {
                if (channels <= 2) throw e
                DnaLog.w(TAG, "Tonausgabe mit $channels Kanälen nicht möglich (${e.javaClass.simpleName}) – Stereo")
                downmixFrom = channels
                createPcmTrack(rate, 2)
            }
            newTrack(t)
            configureProcessors(rate, if (downmixFrom > 0) 2 else channels)
        }
        fun mix(pcm: ByteBuffer): ByteBuffer = if (downmixFrom > 2) downmixToStereo(pcm, downmixFrom) else pcm
        /**
         * Durchreichen: Soundbar/Receiver brauchen zum Umschalten auf Dolby (DD+) oft ein paar Sekunden. Umgekehrt genauso:
         * Am Fernseher/Stick schaltet HDMI auch von Dolby zurück auf normalen Ton um – bis die Tonausgabe das erste Mal
         * spielt, dieselbe Frist (früher 3 s: der Tonweg galt als kaputt, der Senderwechsel wurde langsam und unsauber).
         */
        fun stallLimitMs(t: AudioTrack? = track): Long {
            val notStartedYet = t != null && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) == 0L
            return if (passthroughMode || (isTv && notStartedYet)) PASSTHROUGH_STALL_MS else OUTPUT_STALL_MS
        }
        fun write(t: AudioTrack, data: ByteBuffer, ptsUs: Long, rate: Int) {
            if (firstPtsUs == Long.MIN_VALUE) {
                firstPtsUs = ptsUs
                clock.audio = AudioClock(t, rate, firstPtsUs)
                clock.startFromAudio(firstPtsUs)
            }
            // Angehalten oder Vorrat leer: nicht weiter schreiben (AudioTrack ist pausiert).
            while ((paused || (buffering && started)) && alive(gen)) Thread.sleep(20)
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING && !paused) {
                t.play()
                if (playingSinceMs == 0L) playingSinceMs = SystemClock.elapsedRealtime()
                if (!started) {
                    started = true
                    audioStarted = true
                    setBuffering(false)
                }
            }
            pathOutputs++
            // Nicht blockierend schreiben: Eine Tonausgabe, die nichts annimmt (Durchreichen ohne Receiver), darf den
            // Ton nicht für immer festhalten – sie wird erkannt und der nächste Tonweg genommen.
            var stalledSinceMs = 0L
            while (data.hasRemaining() && alive(gen)) {
                // Tunneling: mit Zeitstempel (die Tonausgabe gibt ihn an den Bild-Decoder weiter).
                val n = if (tunnelActive && android.os.Build.VERSION.SDK_INT >= 23) {
                    t.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING, ptsUs * 1000)
                } else t.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) throw AudioPathFailed("Tonausgabe meldet Fehler $n", hard = true)
                if (n > 0) {
                    stalledSinceMs = 0L
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING && !paused && !buffering) {
                    if (stalledSinceMs == 0L) stalledSinceMs = now
                    else if (now - stalledSinceMs > stallLimitMs(t)) throw AudioPathFailed("Tonausgabe nimmt nichts an")
                } else stalledSinceMs = 0L
                Thread.sleep(5)
            }
            // Spielt die Tonausgabe überhaupt? (Durchreichen an ein Gerät, das das Format doch nicht kann: Daten gehen
            // hinein, aber die Wiedergabe steht.)
            val since = playingSinceMs
            if (since > 0 && !paused && !buffering && SystemClock.elapsedRealtime() - since > stallLimitMs(t) &&
                t.playState == AudioTrack.PLAYSTATE_PLAYING && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) == 0L
            ) throw AudioPathFailed("Tonausgabe spielt nicht")
        }
        fun drainAudio(d: MediaCodec) {
            while (alive(gen)) {
                val out = d.dequeueOutputBuffer(info, 0)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = d.outputFormat
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    clock.audio = null
                    currentAudioTrack = null
                    runCatching { track?.stop(); track?.release() }
                    track = null
                    openPcm(outRate, outChannels)
                    firstPtsUs = Long.MIN_VALUE
                    continue
                }
                if (out < 0) break
                val t = track
                if (t == null || info.size == 0 || info.presentationTimeUs < seekTargetUs - 50_000) {
                    d.releaseOutputBuffer(out, false)
                    continue
                }
                val pcm = d.getOutputBuffer(out)!!
                pcm.position(info.offset)
                pcm.limit(info.offset + info.size)
                try {
                    write(t, processPcm(mix(pcm), info.presentationTimeUs), info.presentationTimeUs, outRate)
                } finally {
                    runCatching { d.releaseOutputBuffer(out, false) }
                }
            }
        }
        /** Kein Ton möglich: Bild läuft allein weiter (die Uhr läuft dann ohne Ton). */
        fun giveUp(message: String) {
            releaseAll()
            listener.onInfo(message)
            DnaLog.w(TAG, message)
            synchronized(lock) { audioQueue.forEach { queuedBytes -= it.data.size }; audioQueue.clear() }
            audioTrack = null
            audioDecoderName = null
            passthrough = false
            clock.audio = null
            setBuffering(false)
        }
        /** Der laufende Tonweg versagt: den nächsten nehmen (Durchreichen → Decoder des Geräts → eigener Decoder → ohne Ton). */
        fun switchPath(reason: String, hard: Boolean) {
            val codec = codecTrack?.codec
            val wasPassthrough = passthroughMode
            val wasDevice = decoder != null
            val name = audioDecoderName
            releaseAll()
            format = null
            joinLate = clock.started
            DnaLog.w(TAG, "Ton: $reason ($name)")
            when {
                wasPassthrough -> {
                    avoidPassthrough = true
                    // Für die ganze Sitzung abschalten nur bei echtem Fehler oder wiederholtem Hängen – nicht wegen eines
                    // einzelnen langsamen HDMI-Umschaltens (sonst kam danach bis zum App-Neustart nie wieder Dolby).
                    codec?.let { c ->
                        passthroughEncoding(c)?.let { enc ->
                            val stalls = (passthroughStalls[enc] ?: 0) + 1
                            passthroughStalls[enc] = stalls
                            if (hard || stalls >= 2) passthroughCache[enc] = false
                        }
                    }
                    listener.onInfo("Durchreichen an den Receiver klappt nicht ($reason) – Ton wird im Gerät dekodiert")
                }
                wasDevice -> {
                    avoidDevice = true
                    codec?.let { brokenDeviceDecoders += it }
                    listener.onInfo("Ton-Decoder des Geräts versagt ($reason) – eigener Decoder")
                }
                else -> giveUp("Ton nicht abspielbar ($reason) – Bild läuft ohne Ton")
            }
        }
        try {
            while (alive(gen)) {
                val sample = synchronized(lock) {
                    var emptySince = 0L
                    while (alive(gen) && (audioQueue.isEmpty() || !startBufferReachedLocked())) {
                        if (inputEnded && audioQueue.isEmpty()) break
                        // Vorrat leer (nicht am Ende): anhalten, bis wieder genug da ist – aber erst, wenn die Lücke
                        // länger dauert, als die Tonausgabe noch Vorrat hat. Kurze Lücken im Datenstrom (Netz-Schwankung)
                        // überbrückt sie; früher stand das Bild dann gleich für Sekunden.
                        if (started && audioQueue.isEmpty() && !buffering) {
                            val now = SystemClock.elapsedRealtime()
                            if (emptySince == 0L) emptySince = now
                            else if (now - emptySince >= UNDERRUN_GRACE_MS) {
                                setBuffering(true)
                                noteStall()
                            }
                        }
                        lock.wait(if (emptySince > 0L && !buffering) 20 else 50)
                    }
                    if (buffering && started && (bufferedAheadLocked() >= resumeBufferUs() || inputEnded)) setBuffering(false)
                    audioQueue.removeFirstOrNull()?.also { queuedBytes -= it.data.size; lock.notifyAll() }
                }
                if (sample == null) {
                    if (inputEnded) Thread.sleep(50)
                    continue
                }
                updateCatchUp()
                if (sample.track != audioTrack) continue // Ton aufgegeben oder Spur gewechselt
                val f = sample.audio ?: AudioInfo(sample.track.sampleRate, sample.track.channels, 0, sample.track.csd.firstOrNull())
                val durUs = if (f.samplesPerFrame > 0 && f.sampleRate > 0) f.samplesPerFrame * 1_000_000L / f.sampleRate else sample.durationUs
                // Vor der Spulstelle: Ton überspringen.
                if (sample.timeUs + durUs < seekTargetUs) continue
                // Start mit Bild: Ton erst ab dem ersten gezeigten Bild (davor liegt Ton ohne Bild, bis zum ersten
                // Schlüsselbild). Kommt kein Bild (Decoder gestört, Bild aus), nach kurzer Zeit ohne warten.
                if (!started && videoTrack != null && videoEnabled && surface != null) {
                    val waitUntil = SystemClock.elapsedRealtime() + VIDEO_START_WAIT_MS
                    while (videoStartUs == Long.MIN_VALUE && alive(gen) && SystemClock.elapsedRealtime() < waitUntil) Thread.sleep(10)
                    val from = videoStartUs
                    if (from != Long.MIN_VALUE && sample.timeUs + durUs <= from) continue
                }
                // Bild läuft schon ohne Ton (Wechsel des Tonwegs, Ton kam zu spät): an der laufenden Uhr einsteigen.
                if ((joinLate || audioLate) && clock.started && clock.audio == null) {
                    if (sample.timeUs + durUs < clock.nowUs()) continue
                }
                joinLate = false
                if (buffering && started) {
                    val enough = synchronized(lock) { bufferedAheadLocked() >= resumeBufferUs() || inputEnded }
                    if (enough) setBuffering(false)
                }
                // Zeitsprung im Ton (Live: Neuverbinden, Werbeeinblendung): Uhr neu, Ton neu anlegen.
                if (live && lastSampleUs != Long.MIN_VALUE && kotlin.math.abs(sample.timeUs - (lastSampleUs + lastSampleDurUs)) > JUMP_US) clock.reset()
                lastSampleUs = sample.timeUs
                lastSampleDurUs = durUs
                if (epoch != clock.epoch) {
                    epoch = clock.epoch
                    firstPtsUs = Long.MIN_VALUE
                    track?.let { t -> runCatching { t.pause(); t.flush() } }
                    playingSinceMs = 0L
                }
                try {
                    // Spur oder Format gewechselt: Decoder bzw. Tonausgabe neu.
                    if ((decoder == null && track == null && native == null) || f != format || sample.track != codecTrack) {
                        releaseAll()
                        format = f
                        codecTrack = sample.track
                        firstPtsUs = Long.MIN_VALUE
                        outRate = 0
                        val codec = sample.track.codec
                        passthroughMode = false
                        pathSinceMs = SystemClock.elapsedRealtime()
                        pathInputs = 0
                        pathOutputs = 0
                        when {
                            codec == Codec.PCM -> {
                                audioDecoderName = "PCM"
                                outRate = f.sampleRate.takeIf { it > 0 } ?: 48_000
                                outChannels = f.channels.coerceAtLeast(1)
                                openPcm(outRate, outChannels)
                            }
                            !avoidPassthrough && shouldPassThrough(codec) -> {
                                passthroughMode = true
                                audioDecoderName = "Durchreichen an Receiver ($codec)"
                                outRate = f.sampleRate.takeIf { it > 0 } ?: 48_000
                                newTrack(createPassthroughTrack(codec, outRate, f.channels))
                            }
                            else -> {
                                val layer = mpegLayer(codec, sample.data)
                                val mime = mimeFor(codec, layer)
                                val csd = if (codec == Codec.AAC_ADTS || codec == Codec.AAC_LATM) listOfNotNull(f.config) else sample.track.csd.ifEmpty { listOfNotNull(f.config) }
                                val mf = MediaFormat.createAudioFormat(mime, f.sampleRate.takeIf { it > 0 } ?: 48_000, f.channels.coerceAtLeast(1)).apply {
                                    csd.forEachIndexed { i, c -> setByteBuffer("csd-$i", ByteBuffer.wrap(c)) }
                                    if (codec == Codec.AAC_ADTS || codec == Codec.AAC_LATM || codec == Codec.AAC) setInteger(MediaFormat.KEY_IS_ADTS, 0)
                                }
                                // Erst der Decoder des Geräts; hat es keinen (DTS, TrueHD …) oder versagt er, der eigene.
                                // Wie bis 0.58.1: Durchreichen, sonst der Decoder des Geräts, sonst der eigene – unabhängig davon,
                                // wie viel Speicher gerade frei ist. Das hing davon ab und gab „mal Ton, mal keinen“ (Fire TV).
                                val useDevice = !avoidDevice && codec !in brokenDeviceDecoders
                                decoder = decoderName(mime, mf)?.takeIf { useDevice }?.let { name ->
                                    runCatching {
                                        MediaCodec.createByCodecName(name).apply {
                                            try {
                                                configure(mf, null, null, 0)
                                                start()
                                            } catch (e: Exception) {
                                                release()
                                                throw e
                                            }
                                        }
                                    }.onFailure { DnaLog.w(TAG, "Ton-Decoder $name: ${it.message}") }.getOrNull()
                                        ?.also { audioDecoderName = name }
                                }
                                if (decoder == null) {
                                    native = NativeAudio.open(codec, f.sampleRate, f.channels, csd.firstOrNull(), layer)
                                    if (native != null) {
                                        audioDecoderName = "DNA-Decoder (${NativeAudio.decoderFor(codec, layer)})"
                                        listener.onInfo("Ton: eigener Decoder für $codec")
                                    } else {
                                        giveUp("Kein Ton-Decoder für $codec – Bild ohne Ton")
                                        continue
                                    }
                                }
                            }
                        }
                        DnaLog.i(TAG, "Ton: $codec ${f.sampleRate} Hz ${f.channels} Kanäle über ${audioDecoderName ?: "?"}")
                        passthrough = passthroughMode
                    }
                    // Eigener Decoder: PCM direkt; Tonausgabe nach dem ersten Rahmen (Format steht erst dann fest).
                    native?.let { nd ->
                        // Winzige Pakete (TrueHD in MKV: 1200 je Sekunde) gesammelt dekodieren: Der Parser im Decoder
                        // trennt die Rahmen wieder, aber JNI, Ton-Verarbeitung und Tonausgabe laufen nur einmal je ~20 ms.
                        val data = if (NativeAudio.parsesFrames(sample.track.codec)) gatherSmall(sample) else sample.data
                        val pcm = nd.decode(data)
                        pathInputs++
                        if (pcm != null && pcm.isNotEmpty()) {
                            val rate = nd.sampleRate
                            val channels = nd.channels
                            if (track == null || rate != outRate || channels != outChannels) {
                                clock.audio = null
                                currentAudioTrack = null
                                runCatching { track?.stop(); track?.release() }
                                track = null
                                outRate = rate
                                outChannels = channels
                                openPcm(outRate, outChannels)
                                firstPtsUs = Long.MIN_VALUE
                            }
                            write(track!!, processPcm(mix(ByteBuffer.wrap(pcm)), sample.timeUs), sample.timeUs, outRate)
                        } else if (pathOutputs == 0 && pathInputs >= SILENT_AUDIO_INPUTS && SystemClock.elapsedRealtime() - pathSinceMs > SILENT_AUDIO_MS) {
                            throw AudioPathFailed("eigener Decoder liefert nichts")
                        }
                        continue
                    }
                    // Durchreichen bzw. PCM: direkt zur Tonausgabe.
                    val direct = track
                    if (decoder == null && direct != null) {
                        val buf = ByteBuffer.wrap(sample.data)
                        write(direct, if (passthroughMode) buf else processPcm(mix(buf), sample.timeUs), sample.timeUs, outRate)
                        continue
                    }
                    val d = decoder ?: continue
                    // Jedes Paket muss hinein: Ist kein Eingang frei, erst Fertiges ausgeben und weiter warten. (Früher
                    // fiel das Paket dann weg – unter Last fehlte Ton, und die Uhr, die sich nach dem Ton richtet, lief zu
                    // langsam: 3 s in 5 s.) Nimmt der Decoder gar nichts mehr an, ist er hängen geblieben.
                    var queued = false
                    var waitSince = SystemClock.elapsedRealtime()
                    while (!queued && alive(gen)) {
                        val inIndex = d.dequeueInputBuffer(5_000)
                        if (inIndex >= 0) {
                            val buf = d.getInputBuffer(inIndex)!!
                            buf.clear()
                            if (sample.data.size <= buf.capacity()) {
                                buf.put(sample.data)
                                d.queueInputBuffer(inIndex, 0, sample.data.size, sample.timeUs, 0)
                            } else d.queueInputBuffer(inIndex, 0, 0, sample.timeUs, 0)
                            queued = true
                        } else {
                            val before = pathOutputs
                            drainAudio(d)
                            val now = SystemClock.elapsedRealtime()
                            if (pathOutputs != before || paused || buffering) waitSince = now
                            else if (now - waitSince > SILENT_AUDIO_MS) throw AudioPathFailed("Decoder nimmt nichts an")
                        }
                    }
                    pathInputs++
                    // Alles Fertige ausgeben.
                    drainAudio(d)
                    // Decoder, die alles annehmen und nie Ton liefern (kommt bei Dolby-Decodern vor): nicht ewig warten.
                    if (pathOutputs == 0 && pathInputs >= SILENT_AUDIO_INPUTS && SystemClock.elapsedRealtime() - pathSinceMs > SILENT_AUDIO_MS) {
                        throw AudioPathFailed("Decoder liefert keinen Ton")
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (!alive(gen)) break
                    // Jeder Fehler im Tonweg (Decoder, Tonausgabe, Format) führt zum nächsten Weg, nie zum Stillstand.
                    switchPath(if (e is AudioPathFailed) e.message ?: "gestört" else "${e.javaClass.simpleName}${e.message?.let { " $it" } ?: ""}",
                        hard = e !is AudioPathFailed || e.hard)
                }
            }
        } catch (e: InterruptedException) {
            // beendet
        } catch (e: Exception) {
            DnaLog.w(TAG, "Ton: ${e.message}")
            clock.audio = null
            if (alive(gen)) {
                listener.onInfo("Ton gestört (${e.message}) – Bild läuft weiter")
                setBuffering(false)
            }
        } finally {
            releaseAll()
        }
    }

    /** Medienzeit, an der der nächste PCM-Block für die Ton-Bearbeitung erwartet wird (MIN = neu ansetzen). */
    @Volatile private var processorNextUs = Long.MIN_VALUE
    private var processorRate = 0
    private var processorChannels = 0

    /** Nach einem leeren Vorrat weiter, sobald so viel da ist: Live kürzer (der Sender liefert in Echtzeit nach). */
    private fun resumeBufferUs(): Long {
        val value = if (!live) config.rebufferUs else {
            val base = minOf(config.rebufferUs, LIVE_REBUFFER_US)
            if (config.liveCatchUp) minOf(base * (2 + stalls) / 2, 6_000_000L) else base
        }
        // 4K: nach einem leeren Vorrat erst mit genug Polster weiter, sonst folgt der nächste Hänger gleich.
        return if (highBitrate) maxOf(value, UHD_RESUME_US) else value
    }

    private fun configureProcessors(rate: Int, channels: Int) {
        processorRate = rate
        processorChannels = channels
        processorNextUs = Long.MIN_VALUE
        pcmProcessors.forEach { p -> runCatching { p.configure(rate, channels) } }
    }

    /**
     * PCM durch die Ton-Bearbeitung. [ptsUs]: Medienzeit des Blocks. Die Bearbeitung (vor allem die Sprach-Auswertung für
     * die Untertitel-Ausrichtung) zählt die Zeit an den Proben weiter; sie wird an der echten Zeit des Blocks neu
     * angesetzt, wenn er nicht nahtlos an den vorigen anschließt – nicht an der gewünschten Spulstelle. Nach einem Sprung
     * landet die Engine oft Sekunden daneben (TS-Dateien, MKV ohne genaues Verzeichnis), und Ton vor dem ersten Bild fällt
     * weg: Mit der Spulstelle als Anfang lag das ganze Sprachsignal um diesen Betrag falsch, keine Untertitel passten.
     */
    private fun processPcm(input: ByteBuffer, ptsUs: Long): ByteBuffer {
        if (pcmProcessors.isEmpty()) return input
        val rate = processorRate
        val frameBytes = 2 * processorChannels.coerceAtLeast(1)
        if (rate > 0) {
            val expected = processorNextUs
            if (expected == Long.MIN_VALUE || kotlin.math.abs(ptsUs - expected) > PROCESSOR_JUMP_US) {
                pcmProcessors.forEach { runCatching { it.flush(ptsUs) } }
                processorNextUs = ptsUs
            }
            processorNextUs += input.remaining() / frameBytes * 1_000_000L / rate
        }
        var buf = input.order(ByteOrder.LITTLE_ENDIAN)
        for (p in pcmProcessors) {
            buf = try {
                p.process(buf)
            } catch (e: Exception) {
                buf
            }
        }
        return buf
    }

    /** Dolby/DTS durchreichen? Wenn eingestellt oder ohne eigenen Decoder – und die Ausgabe es kann. */
    private fun shouldPassThrough(codec: Codec): Boolean {
        val encoding = passthroughEncoding(codec) ?: return false
        if (!config.preferPassthrough && hasDecoder(codec.mime)) return false
        return passthroughSupported(encoding)
    }

    private fun canPlayAudio(codec: Codec): Boolean = playable(codec)

    /**
     * PCM 16 Bit, [channels] Kanäle in Android-Reihenfolge (vorn links, rechts, Mitte, Bass, hinten links, rechts, Seite
     * links, rechts) auf Stereo: Mitte und Surround mit −3 dB dazu, Bass weg, so skaliert, dass nichts übersteuert.
     */
    private fun downmixToStereo(input: ByteBuffer, channels: Int): ByteBuffer {
        val inp = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = inp.remaining() / (2 * channels)
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        // Gewichte je Eingangskanal für links/rechts.
        val c = 0.7071f
        val (left, right) = when (channels) {
            3 -> floatArrayOf(1f, 0f, c) to floatArrayOf(0f, 1f, c)
            4 -> floatArrayOf(1f, 0f, c, 0f) to floatArrayOf(0f, 1f, 0f, c)
            5 -> floatArrayOf(1f, 0f, c, c, 0f) to floatArrayOf(0f, 1f, c, 0f, c)
            6 -> floatArrayOf(1f, 0f, c, 0f, c, 0f) to floatArrayOf(0f, 1f, c, 0f, 0f, c)
            8 -> floatArrayOf(1f, 0f, c, 0f, c, 0f, c, 0f) to floatArrayOf(0f, 1f, c, 0f, 0f, c, 0f, c)
            else -> FloatArray(channels).also { it[0] = 1f } to FloatArray(channels).also { if (channels > 1) it[1] = 1f }
        }
        val norm = 1f / left.sum().coerceAtLeast(1f)
        val base = inp.position()
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            val at = base + f * channels * 2
            for (ch in 0 until channels) {
                val v = inp.getShort(at + ch * 2).toFloat()
                l += v * left[ch]
                r += v * right[ch]
            }
            out.putShort((l * norm).coerceIn(-32768f, 32767f).toInt().toShort())
            out.putShort((r * norm).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        out.flip()
        return out
    }

    private fun createPcmTrack(rate: Int, channels: Int): AudioTrack {
        val mask = channelMask(channels)
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes()).withSession()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate * channels * 2 / 4)) // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun createPassthroughTrack(codec: Codec, rate: Int, channels: Int): AudioTrack {
        val encoding = passthroughEncoding(codec) ?: throw IOException("kein Durchreichen für $codec")
        val mask = channelMask(channels.coerceAtLeast(2))
        val size = (AudioTrack.getMinBufferSize(rate, mask, encoding).takeIf { it > 0 } ?: 32 * 1024) * 2
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes()).withSession()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(encoding).build())
            .setBufferSizeInBytes(maxOf(size, 64 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ================================================================ Uhr

    /** Medienzeit aus dem Ton (was der Lautsprecher gerade spielt). Wirft nie. */
    private class AudioClock(private val track: AudioTrack, private val rate: Int, private val firstPtsUs: Long) {
        private val stamp = AudioTimestamp()
        fun nowUs(speed: Float): Long? = try {
            setSpeedHint(speed)
            read(speed)
        } catch (e: Exception) {
            null
        }

        // Letzter glaubwürdiger Zeitstempel der Tonausgabe (abgespielte Proben, Zeitpunkt).
        private var hadStamp = false
        private var stampFrames = 0L
        private var stampNanos = 0L
        private var lastPollNanos = 0L
        // Anhalten/Weiter: bis ein frischer Zeitstempel kommt, von der Stelle beim Anhalten weiterzählen.
        private var wasPaused = false
        private var frozenUs = Long.MIN_VALUE
        private var resumedNanos = 0L

        /**
         * Wie Media3: Zeitstempel nur alle 10 ms abfragen, nur glaubwürdige übernehmen (höchstens 5 s vom Abspielkopf
         * entfernt) und dazwischen weiterrechnen. Liefert die Tonausgabe zwischendurch keinen Zeitstempel, wird vom letzten
         * weitergerechnet – früher fiel die Uhr dann auf den Abspielkopf zurück, der um die Latenz der Tonausgabe
         * (100–200 ms) hinterherläuft: Die Uhr sprang hin und her, Bilder wurden zurückgehalten oder verworfen (Ruckler).
         */
        @Synchronized private fun read(speed: Float): Long? {
            if (rate <= 0) return null
            val now = System.nanoTime()
            val playing = track.playState == AudioTrack.PLAYSTATE_PLAYING
            if (!playing) {
                current(now, playing = false)?.let { frozenUs = it }
                wasPaused = true
                return frozenUs.takeIf { it != Long.MIN_VALUE }
            }
            if (wasPaused) {
                wasPaused = false
                resumedNanos = now
            }
            if (!hadStamp || now - lastPollNanos >= 10_000_000L) {
                lastPollNanos = now
                if (track.getTimestamp(stamp) && stamp.nanoTime > resumedNanos) {
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    val plausible = !hadStamp || kotlin.math.abs(stamp.framePosition - head) * 1_000_000L / rate < 5_000_000L
                    if (plausible) {
                        hadStamp = true
                        stampFrames = stamp.framePosition
                        stampNanos = stamp.nanoTime
                        resumedNanos = 0L
                    }
                }
            }
            // Gerade weiter: noch kein frischer Zeitstempel – ab der Stelle beim Anhalten im Takt der Zeit.
            if (resumedNanos > 0L && frozenUs != Long.MIN_VALUE) return frozenUs + ((now - resumedNanos) / 1000 * speed).toLong()
            return current(now, playing = true)
        }

        private fun current(now: Long, playing: Boolean): Long? {
            if (hadStamp) {
                val ageUs = if (playing) ((now - stampNanos) / 1000 * speed0).toLong() else 0L
                return firstPtsUs + stampFrames * 1_000_000L / rate + ageUs
            }
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return if (head == 0L) null else firstPtsUs + head * 1_000_000L / rate
        }

        /** Tempo der letzten Abfrage (für [current]). */
        private var speed0 = 1f

        fun setSpeedHint(speed: Float) {
            speed0 = speed
        }
    }

    /** Taktgeber: Ton, wenn er läuft; sonst eine Uhr ab dem ersten Bild. */
    private class Clock {
        /** Ton-Uhr; eine neue wird erst geprüft, bevor ihr ganz vertraut wird. */
        @Volatile var audio: AudioClock? = null
            set(value) {
                field = value
                synchronized(this) {
                    checkAudioUs = Long.MIN_VALUE
                    audioDistrusted = false
                    smoothUs = Long.MIN_VALUE
                }
            }
        /** Die Ton-Uhr läuft nicht im Takt der Zeit (falsch gemeldete Position, etwa beim Durchreichen): Uhr ohne Ton. */
        @Volatile private var audioDistrusted = false
        private var checkAudioUs = Long.MIN_VALUE
        private var checkRealUs = 0L
        @Volatile var started = false
        @Volatile var firstUs = Long.MIN_VALUE
        /** Zählt Neuanfänge (Zeitsprung): Der Ton fängt dann ebenfalls neu an. */
        @Volatile var epoch = 0
        private var anchorUs = 0L
        private var anchorRealtime = 0L
        private var pausedAt = 0L
        @Volatile private var speed = 1f

        fun reset() {
            started = false
            firstUs = Long.MIN_VALUE
            audio = null
            epoch++
        }

        private fun realtimeUs() = SystemClock.elapsedRealtimeNanos() / 1000

        @Synchronized fun startFromVideo(ptsUs: Long, hasAudio: Boolean) {
            if (started) return
            // Mit Ton etwas Vorlauf lassen, bis der Ton anläuft.
            anchorUs = ptsUs - if (hasAudio) 150_000 else 0
            anchorRealtime = realtimeUs()
            if (pausedAt > 0) pausedAt = anchorRealtime
            firstUs = ptsUs
            started = true
        }

        @Synchronized fun startFromAudio(ptsUs: Long) {
            if (started) return
            anchorUs = ptsUs
            anchorRealtime = realtimeUs()
            if (pausedAt > 0) pausedAt = anchorRealtime
            firstUs = ptsUs
            started = true
        }

        @Synchronized fun setPaused(paused: Boolean) {
            checkAudioUs = Long.MIN_VALUE
            smoothUs = Long.MIN_VALUE
            val now = realtimeUs()
            if (paused) {
                if (pausedAt == 0L) pausedAt = now
            } else if (pausedAt > 0) {
                anchorRealtime += now - pausedAt
                pausedAt = 0
            }
        }

        @Synchronized fun setSpeed(s: Float) {
            checkAudioUs = Long.MIN_VALUE
            smoothUs = Long.MIN_VALUE
            val now = if (pausedAt > 0) pausedAt else realtimeUs()
            anchorUs += ((now - anchorRealtime) * speed).toLong()
            anchorRealtime = now
            speed = s
        }

        fun nowUs(): Long {
            if (!audioDistrusted) audio?.nowUs(speed)?.let { us ->
                if (pausedAt == 0L) verify(us)
                if (!audioDistrusted) return smooth(us)
            }
            val now = if (pausedAt > 0) pausedAt else realtimeUs()
            return anchorUs + ((now - anchorRealtime) * speed).toLong()
        }

        // Geglättete Ton-Uhr: läuft im Takt der echten Zeit und wird sanft an die Messwerte der Tonausgabe herangeführt.
        private var smoothUs = Long.MIN_VALUE
        private var smoothRealUs = 0L

        /**
         * Die Tonausgabe meldet ihre Position in Sprüngen (Zeitstempel alle paar Millisekunden, mit Unschärfe). Bilder
         * direkt danach getaktet stehen mal etwas zu lang, mal zu kurz – sichtbar als leichtes Ruckeln. Hier läuft die Uhr
         * gleichmäßig mit der echten Zeit und gleicht Abweichungen in kleinen Schritten aus; nur große Sprünge
         * (Spulen, Neuanfang) werden sofort übernommen.
         */
        @Synchronized private fun smooth(rawUs: Long): Long {
            val real = realtimeUs()
            if (pausedAt > 0 || smoothUs == Long.MIN_VALUE) {
                smoothUs = rawUs
                smoothRealUs = real
                return rawUs
            }
            val predicted = smoothUs + ((real - smoothRealUs) * speed).toLong()
            val diff = rawUs - predicted
            val out = if (kotlin.math.abs(diff) > SMOOTH_SNAP_US) rawUs else predicted + diff / SMOOTH_STEPS
            smoothUs = out
            smoothRealUs = real
            return out
        }

        /**
         * Läuft die Ton-Uhr über einige Sekunden deutlich schneller oder langsamer als die echte Zeit, meldet die
         * Tonausgabe ihre Position falsch (kommt beim Durchreichen von Dolby vor). Dann würde das Bild ewig warten oder
         * nur verworfen: ab hier die Uhr ohne Ton, nahtlos von der letzten glaubwürdigen Stelle.
         */
        @Synchronized private fun verify(audioUs: Long) {
            val real = realtimeUs()
            if (checkAudioUs == Long.MIN_VALUE) {
                checkAudioUs = audioUs
                checkRealUs = real
                return
            }
            val elapsed = real - checkRealUs
            if (elapsed < CLOCK_CHECK_US) return
            val ratio = (audioUs - checkAudioUs).toDouble() / (elapsed * speed)
            // Weit gefasst: kurze Aussetzer im Ton sollen nicht zählen, nur grundfalsche Einheiten (doppelt, vierfach …).
            if (ratio in 0.6..1.6) {
                checkAudioUs = audioUs
                checkRealUs = real
                return
            }
            audioDistrusted = true
            anchorUs = checkAudioUs + (elapsed * speed).toLong()
            anchorRealtime = real
            DnaLog.w(TAG, "Ton-Uhr läuft im Verhältnis ${"%.2f".format(ratio)} zur echten Zeit – Uhr ohne Ton")
        }
    }

    companion object {
        /** Gesammelte Dekodierung für winzige Ton-Pakete (siehe gatherSmall). */
        private const val GATHER_US = 20_000L
        private const val GATHER_BYTES = 64 * 1024

        private const val TAG = "DnaEngine"
        /** So oft darf ein Bild-Decoder vor dem ersten Bild scheitern, dann ist der nächste dran. */
        private const val VARIANT_TUNED = 0
        private const val VARIANT_PLAIN = 1
        private const val VARIANT_GPU = 2
        // Über die GPU nur auf Handys/Tablets: Fernseher-Sticks haben dafür bei 4K HDR zu wenig Grafikleistung und Speicher.
        private fun variantsFor(cfg: VideoConfig): List<Int> =
            if (cfg.bitDepth >= 10 && !isTv) listOf(VARIANT_PLAIN, VARIANT_TUNED, VARIANT_GPU)
            else if (cfg.bitDepth >= 10) listOf(VARIANT_PLAIN, VARIANT_TUNED)
            else listOf(VARIANT_TUNED, VARIANT_PLAIN)

        /**
         * Eigener Software-Decoder nur auf Geräten mit Luft: nicht auf Fernseher-Sticks und nicht unter 2,5 GB Arbeitsspeicher.
         * Auf dem Fire TV Stick (1,6 GB) hätte 4K in Software Speicher und Grafik so ausgelastet, dass das ganze Gerät
         * hängen kann (Streifenbild, Absturz) – dort lieber eine klare Meldung als ein abgestürzter Stick.
         */
        private val softVideoAllowed: Boolean by lazy {
            if (isTv) return@lazy false
            val am = appContext?.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return@lazy false
            val info = android.app.ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(info) }
            !am.isLowRamDevice && info.totalMem >= 2_560L * 1024 * 1024
        }

        fun softVideoSupported(mime: String): Boolean = softVideoAllowed && NativeVideo.supports(mime)
        /** Synchroner Decoder-Betrieb: so oft nachsehen, wenn gerade nichts zu tun war. */
        private const val SYNC_POLL_MS = 4L
        /** So lange darf ein Bild-Decoder nach dem ersten Schlüsselbild ohne Bild bleiben. */
        private const val SILENT_DECODER_MS = 6_000L
        /** Hardware-Decoder: so lange ohne erstes Bild nach dem ersten Schlüsselbild. */
        private const val HARDWARE_SILENT_MS = 4_000L
        /** Je Decoder: ob die schlichte Einrichtung (wie VLC) ein Bild brachte (true) oder die wie Media3 (false). */
        private val workingVariant: MutableMap<String, Int> by lazy {
            java.util.concurrent.ConcurrentHashMap<String, Int>().also { map ->
                runCatching { decoderPrefs()?.all?.forEach { (k, v) -> if (k.startsWith("v:") && v is Int) map[k.removePrefix("v:")] = v } }
            }
        }
        /** Arten Bild (Format, Bittiefe, 4K oder nicht), an denen alle Decoder des Geräts scheiterten (bis zum App-Neustart). */
        private val hardwareFailed: MutableSet<String> by lazy {
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { set ->
                runCatching { decoderPrefs()?.getStringSet("failed", null)?.let { set.addAll(it) } }
            }
        }

        /**
         * Was je Gerät klappt, über App-Neustarts hinweg merken (sonst kostet der erste 4K-Sender nach jedem Start wieder
         * mehrere Fehlversuche). Mit der Versionsnummer im Namen: Eine neue App-Version probiert alles frisch.
         */
        private fun decoderPrefs(): android.content.SharedPreferences? = appContext?.let { ctx ->
            val version = runCatching {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            }.getOrDefault(0L)
            ctx.getSharedPreferences("dna_decoders_$version", android.content.Context.MODE_PRIVATE)
        }

        private fun rememberVariant(name: String, variant: Int) {
            if (workingVariant[name] == variant) return
            workingVariant[name] = variant
            runCatching { decoderPrefs()?.edit()?.putInt("v:$name", variant)?.apply() }
        }

        private fun rememberHardware(key: String, failed: Boolean) {
            val changed = if (failed) hardwareFailed.add(key) else hardwareFailed.remove(key)
            if (changed) runCatching { decoderPrefs()?.edit()?.putStringSet("failed", HashSet(hardwareFailed))?.apply() }
        }
        private fun softwareKey(cfg: VideoConfig) = "${cfg.mime}/${cfg.bitDepth}/${if (cfg.width >= 3000) "4k" else "hd"}"
        /** Später als das ist ein Bild verworfen. */
        /** Bis so spät wird ein Bild noch gezeigt; danach nur, wenn seit dem letzten gezeigten [MIN_FRAME_GAP_NS] vergangen ist. */
        private const val LATE_US = 120_000L
        /** Eigener Software-Decoder: so viele fertige Bilder Vorrat (Full HD, je ~3 MB) gegen schwere Einzelbilder. */
        private const val SOFT_QUEUE_FRAMES = 6
        /** Zu späte Bilder werden in diesem Fenster gezählt (ab 3 eine Sparstufe mehr). */
        private const val HURRY_WINDOW_MS = 2_000L
        /** Nach einem Stufenwechsel so lange warten, bis er wirkt. */
        private const val HURRY_SETTLE_MS = 1_500L
        /** So lange ohne zu spätes Bild, bevor eine Stufe zurückgeschaltet wird (verdoppelt sich bei Hin und Her). */
        private const val HURRY_RELAX_MS = 20_000L
        private const val HURRY_RELAX_MAX_MS = 160_000L
        private const val MIN_FRAME_GAP_NS = 100_000_000L
        /** So weit hinter der Uhr: zum nächsten Schlüsselbild springen. */
        private const val CATCH_UP_US = 600_000L
        /** Früher als das wird ein Bild zurückgehalten (sonst mit Zeitpunkt an die Bildfläche gegeben). */
        private const val HOLD_US = 80_000L
        /** Größer als das ist ein Zeitsprung (kein normales Vor- oder Nacheilen). */
        private const val JUMP_US = 3_000_000L
        /** So lange wartet der Ton beim Start höchstens auf das erste Bild. */
        private const val VIDEO_START_WAIT_MS = 4_000L
        /** Ton-Bearbeitung: Abstand, ab dem ein PCM-Block als Sprung gilt (dann an seiner Zeit neu ansetzen). */
        private const val PROCESSOR_JUMP_US = 150_000L
        /** Bis zu dieser Abweichung von der gleichmäßigen Folge werden Anzeigezeiten geglättet (sonst neu ausgerichtet). */
        private const val SMOOTH_FRAME_NS = 20_000_000L
        /** Mit Deinterlacing: so früh wird ein Bild an die GPU gegeben (sie legt die Zeitpunkte selbst fest). */
        private const val DEINTERLACE_HOLD_US = 8_000L
        /** Ton-Uhr: größere Abweichungen sofort übernehmen, kleinere über so viele Abfragen ausgleichen. */
        private const val SMOOTH_SNAP_US = 250_000L
        private const val SMOOTH_STEPS = 16
        /** So lange darf der Ton-Vorrat leer sein, bevor gepuffert wird (die Tonausgabe hat ~250 ms Vorrat). */
        private const val UNDERRUN_GRACE_MS = 200L
        /** Live: nach einem leeren Vorrat weiter ab so viel Vorrat. */
        private const val LIVE_REBUFFER_US = 1_500_000L
        /** Live ohne bekannte Hänger: Start ab so viel Vorrat (schnelles Umschalten). */
        private const val LIVE_FAST_START_US = 800_000L
        /** Ab dieser Datenrate des Bildes gilt ein Strom als 4K (Mbit/s). */
        private const val UHD_MBIT = 14.0
        /** 4K: Start- und Nachlade-Vorrat mindestens so groß. */
        private const val UHD_START_US = 2_500_000L
        private const val UHD_RESUME_US = 3_500_000L
        /** Live: unter so viel Vorrat (Stufe 0) langsamer weiter. */
        private const val LIVE_CATCH_UP_LOW_US = 700_000L
        private const val CATCH_UP_SLOW = 0.97f
        private const val MAX_STALL_LEVEL = 6
        /** Nach so langer Zeit ohne Hänger eine Stufe zurück. */
        private const val STALL_DECAY_MS = 10 * 60_000L
        /** Ton an die Bildwechsel angleichen: höchstens so viel schneller/langsamer (0,3 %). */
        private const val MAX_RESAMPLE = 0.003

        /** Hänger je Sender (Adresse), für die ganze App-Sitzung. */
        private val stallMemory = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 300
        }
        /** Größer ist kein echtes Bild (kaputter Strom). */
        private const val MAX_INPUT_BYTES = 24 * 1024 * 1024
        /** Tonausgabe nimmt so lange nichts an bzw. spielt nicht: nächster Tonweg. */
        private const val OUTPUT_STALL_MS = 3_000L
        private const val PASSTHROUGH_STALL_MS = 8_000L
        /** Ton-Decoder ohne jede Ausgabe nach so vielen Paketen und so langer Zeit: nächster Tonweg. */
        private const val SILENT_AUDIO_INPUTS = 25
        private const val SILENT_AUDIO_MS = 3_000L
        /** So lange nach dem ersten Bild wartet das Bild höchstens auf den Ton, dann läuft es allein los. */
        private const val AUDIO_START_GRACE_MS = 2_500L
        /** Prüfzeitraum der Ton-Uhr gegen die echte Zeit. */
        private const val CLOCK_CHECK_US = 4_000_000L

        /** Formate, bei denen der Decoder des Geräts in dieser Sitzung versagt hat (dann gleich der eigene). */
        private val brokenDeviceDecoders: MutableSet<Codec> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()

        private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }

        private fun channelMask(channels: Int) = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        /** Spielt die DNA Engine diesen Ton (Decoder des Geräts, eigener Decoder oder Durchreichen)? */
        fun playable(codec: Codec): Boolean =
            codec == Codec.PCM || hasDecoder(codec.mime) || NativeAudio.supports(codec) || passthroughEncoding(codec)?.let(::passthroughSupported) == true

        /** MPEG-Audio: Layer aus dem Rahmenkopf (1, 2, 3); sonst 3. */
        fun mpegLayer(codec: Codec, frame: ByteArray): Int =
            if (codec == Codec.MPEG_AUDIO && frame.size > 1 && (frame[0].toInt() and 0xFF) == 0xFF) (4 - ((frame[1].toInt() shr 1) and 3)).coerceIn(1, 3) else 3

        /** MIME für MediaCodec (MP2 hat einen eigenen Typ). */
        fun mimeFor(codec: Codec, layer: Int): String = when {
            codec != Codec.MPEG_AUDIO -> codec.mime
            layer == 1 -> "audio/mpeg-L1"
            layer == 2 -> "audio/mpeg-L2"
            else -> codec.mime
        }

        fun passthroughEncoding(codec: Codec): Int? = when (codec) {
            Codec.AC3 -> AudioFormat.ENCODING_AC3
            Codec.EAC3 -> AudioFormat.ENCODING_E_AC3
            Codec.DTS -> AudioFormat.ENCODING_DTS
            else -> null
        }

        private val passthroughCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        /** Je Format: wie oft das Durchreichen in dieser Sitzung nur hing (ohne Fehler). */
        private val passthroughStalls = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        /** Die letzten Meldungen der Engine (für den Fehlerbericht). */
        fun journal(): List<String> = DnaLog.journal()

        /** Tunneling ist in dieser App-Sitzung schon gescheitert: nicht wieder versuchen. */
        @Volatile var tunnelBroken = false

        /** Kann irgendein Bild-Decoder des Geräts für [mime] Tunneling? */
        fun supportsTunneling(mime: String): Boolean = codecList.any { info ->
            info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
            }.getOrDefault(false)
        }

        fun decoderSupportsTunneling(name: String, mime: String): Boolean = codecList.firstOrNull { it.name == name }?.let { info ->
            runCatching { info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) }.getOrDefault(false)
        } ?: false

        /** Fernseher/Stick (Leanback): Dort stellt die App die Bildwechselrate selbst um (FrameRateMatcher). */
        private val isTv: Boolean by lazy {
            runCatching { appContext?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) == true }.getOrDefault(false)
        }

        /** App-Kontext (setzt die App): für die HDMI-Fähigkeiten vor Android 10. */
        @Volatile var appContext: android.content.Context? = null
            set(value) {
                field = value
                passthroughCache.clear()
            }

        /**
         * Vor Android 10 (Fire OS 6/7) wie Media3: Auf Amazon-Geräten zählt die Einstellung „Surround-Sound“ (Dolby an den
         * Receiver), sonst die Formate, die der Fernseher bzw. Receiver über HDMI meldet.
         */
        private fun legacyPassthrough(encoding: Int): Boolean {
            val context = appContext ?: return false
            if (android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) {
                val surround = runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "external_surround_sound_enabled", 0) }.getOrDefault(0)
                if (surround == 1 && encoding in setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3)) return true
            }
            val plug = runCatching {
                context.registerReceiver(null, android.content.IntentFilter(android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG))
            }.getOrNull() ?: return false
            if (plug.getIntExtra(android.media.AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) != 1) return false
            return plug.getIntArrayExtra(android.media.AudioManager.EXTRA_ENCODINGS)?.contains(encoding) == true
        }

        fun passthroughSupported(encoding: Int): Boolean = passthroughCache.getOrPut(encoding) {
            if (android.os.Build.VERSION.SDK_INT < 29) return@getOrPut legacyPassthrough(encoding)
            runCatching {
                AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    ATTRIBUTES,
                )
            }.getOrDefault(false)
        }

        /**
         * Bild-Decoder für eine Konfiguration, in Reihenfolge: Hardware, die das Format laut Gerät kann; Hardware ohne
         * passende Angabe (viele Geräte geben 4K oder 10 Bit falsch an und können es doch); dann Software des Geräts.
         * Scheitern alle, übernimmt der eigene Software-Decoder ([SoftVideo]).
         */
        fun decoderCandidates(config: VideoConfig): List<String> {
            val probe = MediaFormat.createVideoFormat(config.mime, config.width, config.height)
            val matching = codecList.filter { info -> info.supportedTypes.any { it.equals(config.mime, true) } }
            val supported = matching.filter { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(config.mime)
                    caps.isFormatSupported(probe) || caps.videoCapabilities?.isSizeSupported(config.width, config.height) == true
                }.getOrDefault(false)
            }
            val hardware = supported.filter(::isHardware)
            val hardwareUnlisted = matching.filter { isHardware(it) && it !in supported }
            val software = supported.filter { !isHardware(it) } + matching.filter { !isHardware(it) && it !in supported }

            // Wie Media3/VLC: Hardware zuerst. Software-Decoder des Geräts nur, wenn sie das Profil können – die
            // Android-eigenen HEVC-Decoder (c2.android, OMX.google) können kein 10 Bit (Main10) und nehmen solche Ströme an,
            // ohne je ein Bild zu liefern; dafür gibt es den eigenen Software-Decoder (FFmpeg) danach.
            val tenBit = config.mime == Codec.HEVC.mime && config.bitDepth >= 10
            fun canMain10(info: MediaCodecInfo): Boolean = runCatching {
                val levels = info.getCapabilitiesForType(config.mime).profileLevels
                levels.isEmpty() || levels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
            }.getOrDefault(true)
            val ordered = (hardware + hardwareUnlisted).let { hw -> if (tenBit) hw.sortedBy { if (canMain10(it)) 0 else 1 } else hw } +
                software.filter { !tenBit || canMain10(it) }
            // Zweitnamen desselben Decoders (OMX.MTK… = c2.mtk… auf Android 10+) nur einmal: sonst scheitert er doppelt.
            return ordered.distinctBy { if (android.os.Build.VERSION.SDK_INT >= 29) it.canonicalName else it.name }.map { it.name }
        }

        /** Ton-Decoder: Hardware bevorzugt, notfalls einer ohne passende Angaben (die sind dort oft unvollständig). */
        fun decoderName(mime: String, format: MediaFormat): String? {
            val candidates = codecList.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }
            val supported = candidates.filter { runCatching { it.getCapabilitiesForType(mime).isFormatSupported(format.withoutCsd()) }.getOrDefault(false) }
            return (supported.ifEmpty { candidates }).sortedBy { if (isHardware(it)) 0 else 1 }.firstOrNull()?.name
        }

        private fun MediaFormat.withoutCsd(): MediaFormat = MediaFormat().also { copy ->
            listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT).forEach { key ->
                if (containsKey(key)) when (key) {
                    MediaFormat.KEY_MIME -> copy.setString(key, getString(key))
                    else -> copy.setInteger(key, getInteger(key))
                }
            }
        }

        fun isHardwareName(name: String): Boolean =
            codecList.firstOrNull { it.name == name }?.let(::isHardware) ?: !(name.startsWith("OMX.google.") || name.startsWith("c2.android."))

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

        fun hasDecoder(mime: String): Boolean = codecList.any { it.supportedTypes.any { t -> t.equals(mime, true) } }

        /** NAL-Typ des ersten Bild-NALs (HEVC), -1 wenn keiner. */
        fun firstVclType(data: ByteArray): Int {
            var i = 0
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    val type = (data[i + 3].toInt() shr 1) and 0x3F
                    if (type <= 31) return type
                    i += 3
                } else i++
            }
            return -1
        }

        fun sameLanguage(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            fun norm(s: String) = when (val l = s.lowercase().take(3)) { "deu", "ger" -> "de"; "eng" -> "en"; "tur" -> "tr"; "fra", "fre" -> "fr"; else -> l.take(2) }
            return norm(a) == norm(b)
        }
    }
}

private fun Any.wait(ms: Long) = (this as Object).wait(ms)
private fun Any.notifyAll() = (this as Object).notifyAll()

package de.ayutv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import de.ayutv.player.SpeechActivity
import de.ayutv.player.dna.engine.SubtitleImage
import de.ayutv.player.dna.engine.Track
import java.io.File
import javax.swing.SwingUtilities

/**
 * DNA Player für Windows auf der eigenen DNA Engine ([DesktopEngine]). Das Bild wird Bild für Bild an die Oberfläche
 * übergeben ([currentFrame], YUV-Ebenen + Shader); so liegen Senderinfo, Untertitel und Steuerleiste über dem Video.
 * Untertitel (aus dem Stream oder als Datei) zeichnet die Oberfläche selbst ([subtitleText], [subtitleImages]).
 */
class VideoPlayer(initialOptions: List<String> = emptyList()) {
    /** RGB-Bild (nur wenn die Grafik den Shader nicht kann; die DNA Engine liefert sonst YUV). */
    var frame by mutableStateOf<ImageBitmap?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var volume by mutableStateOf(100); private set
    /** Untertitel- und Tonspuren (Kennung, Name). */
    var audioTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set
    /** Gewählte Spuren (−1 = keine). */
    var audioTrack by mutableStateOf(-1); private set
    var subtitleTrack by mutableStateOf(-1); private set
    /** Gerade fällige Untertitel (Text) bzw. Bild-Untertitel (PGS, DVB). */
    var subtitleText by mutableStateOf<String?>(null); private set
    var subtitleImages by mutableStateOf<List<SubtitleImage>>(emptyList()); private set

    /** Früher: VLC kannte eine Einstellung nicht. Die DNA Engine kennt alle – immer null. */
    var optionsProblem by mutableStateOf<String?>(null); private set

    /** Früher Direktausgabe über VLC; die DNA Engine zeichnet immer über die Oberfläche (GPU-Shader). */
    @Volatile var directMode = "AUTO"
    @Volatile var expectUhd = false
    val isDirect: Boolean get() = false
    fun coverDirect(@Suppress("UNUSED_PARAMETER") covered: Boolean) = Unit
    fun osd(@Suppress("UNUSED_PARAMETER") text: String) = Unit

    /** Sprechpausen aus dem Ton (nur, solange [tapAudio] an ist). */
    val speech = SpeechActivity()
    /** Ton für die Auswertung mitlesen (gilt ab dem nächsten [play]; der Ton läuft dabei unverändert). */
    var tapAudio = false

    /** Version der Engine (für Starttest und Fehlerbericht). */
    val engineVersion: String = runCatching { org.bytedeco.ffmpeg.global.avcodec.avcodec_version().let { "DNA Engine (FFmpeg ${it shr 16}.${(it shr 8) and 0xFF}.${it and 0xFF})" } }.getOrNull() ?: "DNA Engine"
    /** Gezeigte Bilder (für den Selbsttest). */
    val framesRendered = java.util.concurrent.atomic.AtomicLong()
    /** Zeitpunkt des letzten gezeigten Bildes (Watchdog: steht das Bild?). */
    @Volatile var lastFrameAt = 0L; private set
    @Volatile private var startedAt = 0L
    var startupMs by mutableLongStateOf(0L); private set
    /** Der Stream hat ein Bild (Radiosender nicht). */
    var hasVideo by mutableStateOf(true); private set
    /** Pausiert vom Nutzer (nicht: puffert). */
    var paused by mutableStateOf(false); private set
    /** Puffer für Netz-Streams in ms (Einstellungen: Schnell, Ausgewogen, Stabil). */
    var networkCacheMs = NETWORK_CACHE_MS

    /** Wird am Ende eines Films aufgerufen. */
    var onFinished: (() -> Unit)? = null

    private var engineTracks: List<Track> = emptyList()
    private var textTracksNow: List<Track> = emptyList()
    private var externalCues: List<Triple<Long, Long, String>> = emptyList()
    @Volatile private var subtitleDelayUs = 0L
    @Volatile private var speechRestart = true

    private val engine: DesktopEngine? = runCatching { DesktopEngine(de.ayutv.desktop.data.Network.http, Output()) }
        .onFailure { error = "DNA Engine startet nicht: ${it.message}" }.getOrNull()

    val available: Boolean get() = engine != null

    init {
        reconfigure(initialOptions)
        // Position, Untertitel und Ende 10× je Sekunde in die Oberfläche.
        val ticker = javax.swing.Timer(100) { tick() }
        ticker.isRepeats = true
        ticker.start()
    }

    private fun tick() {
        val e = engine ?: return
        if (playing || paused) {
            positionMs = e.positionUs / 1000
            durationMs = if (e.durationUs > 0) e.durationUs / 1000 else durationMs
        }
        updateSubtitle(e.positionUs)
    }

    private fun updateSubtitle(positionUs: Long) {
        val e = engine ?: return
        val at = positionUs - subtitleDelayUs
        when {
            subtitleTrack == EXTERNAL -> {
                val ms = at / 1000
                val cue = externalCues.lastOrNull { it.first <= ms }?.takeIf { ms < it.second }
                if (subtitleText != cue?.third) subtitleText = cue?.third
                if (subtitleImages.isNotEmpty()) subtitleImages = emptyList()
            }
            subtitleTrack >= 0 -> {
                val entry = e.subtitles.at(subtitleTrack, at)
                if (subtitleText != entry?.text) subtitleText = entry?.text
                val images = entry?.images.orEmpty()
                if (subtitleImages !== images) subtitleImages = images
            }
            else -> {
                if (subtitleText != null) subtitleText = null
                if (subtitleImages.isNotEmpty()) subtitleImages = emptyList()
            }
        }
    }

    private inner class Output : DesktopEngine.Output {
        override fun onPrepared(durationMs: Long, seekable: Boolean, live: Boolean) = ui { this@VideoPlayer.durationMs = durationMs }

        override fun onTracks(video: Track?, audio: List<Track>, text: List<Track>, selectedAudio: Track?) = ui {
            engineTracks = audio
            textTracksNow = text
            hasVideo = video != null
            audioTracks = audio.map { it.id to trackName(it) }
            audioTrack = selectedAudio?.id ?: -1
            subtitleTracks = text.map { it.id to trackName(it) } + (if (externalCues.isNotEmpty()) listOf(EXTERNAL to "Datei") else emptyList())
        }

        override fun onFrame(frame: DesktopEngine.Frame) {
            val old = yuv.getAndSet(frame.yuv)
            framesRendered.incrementAndGet()
            val now = System.currentTimeMillis()
            lastFrameAt = now
            ui {
                if (old != null) retire(old)
                frameTick++
                if (!hasFrame) hasFrame = true
                if (startupMs == 0L && startedAt > 0) startupMs = (now - startedAt).coerceAtLeast(1)
                if (!playing && !paused) playing = true
                buffering = false
            }
        }

        override fun onBuffering(buffering: Boolean) = ui {
            this@VideoPlayer.buffering = buffering
            if (!buffering) {
                playing = !paused
                error = null
            }
        }

        override fun onEnded() = ui {
            playing = false
            onFinished?.invoke()
        }

        override fun onError(message: String) = ui {
            error = "Der Stream lässt sich nicht abspielen ($message)"
            playing = false
            de.ayutv.desktop.Log.warn("DNA Engine: $message")
        }

        override fun onInfo(text: String) = de.ayutv.desktop.Log.info("DNA Engine: $text")

        override fun onPcm(data: ByteArray, frames: Int, rate: Int, timeUs: Long) {
            if (!tapAudio) return
            pcmRate = rate
            pcmPositionMs = timeUs / 1000
            if (speechRestart) {
                speechRestart = false
                tap.restart()
            }
            tap.consume(data, frames)
        }
    }

    @Volatile private var pcmRate = AudioTap.RATE
    @Volatile private var pcmPositionMs = 0L
    private val tap = AudioTap(speech, { pcmPositionMs }, { pcmRate })

    private fun trackName(t: Track): String {
        val language = t.language?.let { de.ayutv.desktop.data.languageName(it) } ?: ""
        val label = t.label?.takeIf { it.isNotBlank() }
        val codec = t.codec.name.replace('_', ' ')
        return listOfNotNull(language.ifBlank { null }, label, codec.takeIf { t.codec.kind != de.ayutv.player.dna.engine.Kind.TEXT || label == null })
            .joinToString(" · ").ifBlank { "Spur ${t.id}" }
    }

    fun play(url: String, userAgent: String? = null, startMs: Long = 0, @Suppress("UNUSED_PARAMETER") vararg extra: String) {
        val e = engine ?: return
        clearFrames()
        if (tapAudio) {
            speech.clear()
            speechRestart = true
        }
        error = null
        buffering = true
        paused = false
        playing = false
        hasVideo = true
        positionMs = startMs
        durationMs = 0
        startupMs = 0
        startedAt = System.currentTimeMillis()
        lastFrameAt = startedAt
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        audioTrack = -1
        subtitleTrack = -1
        externalCues = emptyList()
        subtitleText = null
        subtitleImages = emptyList()
        val live = isLiveUrl(url)
        e.config = DesktopEngine.Config(
            liveBufferUs = (networkCacheMs * 3_000L).coerceIn(4_000_000, 20_000_000),
            vodBufferUs = (networkCacheMs * 12_000L).coerceIn(20_000_000, 90_000_000),
            startBufferUs = (networkCacheMs * 500L).coerceIn(600_000, 3_000_000),
            layout = videoLayout,
        )
        e.hdrMode = hdrMode
        e.setPaused(false)
        val path = if (File(url).isFile) File(url).absolutePath else url
        e.open(path, userAgent, live, startMs * 1000)
    }

    /** Live-Sender (kein Film): Xtream „/live/“, „.m3u8“/„.ts“ ohne Filmordner, Adressen ohne Dateiendung. */
    private fun isLiveUrl(url: String): Boolean {
        if (File(url).isFile) return false
        val path = url.substringBefore('?').lowercase()
        if ("/movie/" in path || "/series/" in path) return false
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return ext !in setOf("mkv", "mp4", "m4v", "mov", "avi", "webm")
    }

    /** Technische Angaben zum laufenden Stream (für die Stream-Info, Taste ▶ bzw. Info). */
    fun streamInfo(): StreamInfo? {
        val e = engine ?: return null
        val frame = yuv.get()
        val v = e.videoTrack
        val a = e.audioTrack
        return StreamInfo(
            width = frame?.width ?: v?.width ?: 0,
            height = frame?.height ?: v?.height ?: 0,
            fps = e.frameRate,
            videoCodec = listOfNotNull(v?.codec?.name, e.decoderName.ifBlank { null }).joinToString(" · "),
            audioCodec = listOfNotNull(a?.codec?.name, e.audioDecoderName.ifBlank { null }).joinToString(" · "),
            audioChannels = a?.channels ?: 0,
            inputKbps = e.inputKbps,
            demuxKbps = e.inputKbps,
            displayed = e.renderedFrames.toInt(),
            lost = e.droppedFrames.toInt(),
            corrupted = 0,
            startupMs = startupMs,
            cacheMs = networkCacheMs,
            output = "DNA Engine · " + (if (e.hardwareDecoding) "Hardware-Dekodierung (D3D11VA)" else "Dekodierung auf dem Prozessor") +
                (if (e.deinterlacing) " · Halbbilder entflochten" else "") + " · Grafikkarte (" + when (e.layout) {
                    YuvVideo.Layout.I420 -> "I420"
                    YuvVideo.Layout.NV12 -> "NV12"
                    YuvVideo.Layout.P010 -> "P010, 10 Bit"
                    YuvVideo.Layout.I010 -> "I010, 10 Bit"
                } + ")" + when (e.transfer) {
                    YuvVideo.Transfer.PQ -> ", HDR10 angepasst"
                    YuvVideo.Transfer.HLG -> ", HLG angepasst"
                    YuvVideo.Transfer.SDR -> ""
                },
        )
    }

    fun togglePause() = if (paused) resume() else pause()

    fun pause() {
        val e = engine ?: return
        e.setPaused(true)
        paused = true
        playing = false
    }

    fun resume() {
        val e = engine ?: return
        e.setPaused(false)
        paused = false
        playing = true
    }

    fun seekBy(deltaMs: Long) {
        val e = engine ?: return
        val target = (e.positionUs / 1000 + deltaMs).coerceIn(0, maxOf(0, durationMs - 1_000))
        seekTo(target)
    }

    fun seekTo(ms: Long) {
        val e = engine ?: return
        if (!e.seekable) return
        speechRestart = true
        positionMs = ms
        e.seekTo(ms.coerceAtLeast(0) * 1000)
    }

    fun changeVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, 150)
        engine?.volume = volume / 100f
    }

    fun toggleMute() {
        muted = !muted
    }

    /** Stumm (Multiview: nur ein Feld hat Ton; Mini-Vorschau). Gilt sofort und für jeden weiteren Start. */
    @Volatile var muted = false
        set(value) {
            field = value
            engine?.muted = value
        }

    fun selectAudio(id: Int) {
        val e = engine ?: return
        val track = engineTracks.firstOrNull { it.id == id } ?: return
        speechRestart = true
        e.selectAudio(track)
        audioTrack = id
    }

    fun selectSubtitle(id: Int) {
        subtitleTrack = id
        updateSubtitle(engine?.positionUs ?: 0)
    }

    /**
     * Klang-Einstellungen ([PlayerOptions.engine]): gelten sofort, ein Neustart ist nie nötig (anders als früher mit VLC).
     */
    fun reconfigure(extra: List<String>): Boolean {
        engine?.compressor = "compressor" in extra
        engine?.normalize = "normvol" in extra
        return false
    }

    /** Ton-Versatz in ms (positiv = Ton später); gilt sofort. */
    var audioDelayMs = 0L
        set(value) {
            field = value
            engine?.audioDelayMs = value
        }

    /** Klangregelung (dB je Band, 10 Bänder 60 Hz … 16 kHz); null = aus. Gilt sofort. */
    var equalizerBands: List<Float>? = null
        set(value) {
            field = value
            engine?.equalizer = value?.toFloatArray()
        }

    /** Externe Untertitel-Datei (SRT) einblenden. */
    fun addSubtitleFile(file: File) {
        val cues = runCatching { de.ayutv.data.parse.Srt.parse(file.readText()) }.getOrDefault(emptyList())
        externalCues = cues.mapNotNull { c ->
            val start = srtMs(c.start) ?: return@mapNotNull null
            val end = srtMs(c.end) ?: return@mapNotNull null
            Triple(start, end, de.ayutv.data.parse.Srt.plainText(c.text))
        }.sortedBy { it.first }
        if (subtitleTracks.none { it.first == EXTERNAL }) subtitleTracks = subtitleTracks + (EXTERNAL to "Datei")
        subtitleTrack = EXTERNAL
    }

    private fun srtMs(t: String): Long? {
        val m = Regex("""(\d+):(\d+):(\d+)[,.](\d+)""").find(t) ?: return null
        val (h, min, s, ms) = m.destructured
        return h.toLong() * 3_600_000 + min.toLong() * 60_000 + s.toLong() * 1_000 + ms.padEnd(3, '0').take(3).toLong()
    }

    /** Untertitel verschieben (ms, positiv = später). */
    fun subtitleDelay(ms: Long) {
        subtitleDelayUs = ms * 1000
    }

    /** Aufgeben (Watchdog): anhalten und [message] zeigen. */
    fun stopWithError(message: String) {
        engine?.stop()
        playing = false
        buffering = false
        error = message
    }

    fun stop() {
        engine?.stop()
        clearFrames()
        playing = false
    }

    fun release() {
        clearFrames()
        engine?.release()
    }

    // ---------------------------------------------------------------- Bilder

    /** Schneller Weg (YUV + Shader auf der Grafikkarte). */
    val gpuVideo: Boolean = YuvVideo.available
    private val yuv = java.util.concurrent.atomic.AtomicReference<YuvFrame?>(null)
    fun currentFrame(): YuvFrame? = yuv.get()
    /** Zählt jedes neue Bild (die Oberfläche zeichnet dann neu, ohne alles neu aufzubauen). */
    var frameTick by mutableLongStateOf(0L); private set
    /** Es gibt ein Bild. */
    var hasFrame by mutableStateOf(false); private set

    /** Videoweg: AUTO = wie dekodiert (NV12 bei Hardware, P010 bei 10 Bit), I420/NV12/P010 fest. Gilt ab dem nächsten [play]. */
    @Volatile var videoLayout = "AUTO"
    val layout: YuvVideo.Layout get() = engine?.layout ?: YuvVideo.Layout.I420
    val transfer: YuvVideo.Transfer get() = engine?.transfer ?: YuvVideo.Transfer.SDR
    /** Einstellung: AUTO (aus dem Stream), PQ, HLG, OFF. */
    @Volatile var hdrMode = "AUTO"
        set(value) {
            field = value
            engine?.hdrMode = value
        }

    /** Zuletzt ersetzte Bilder (nur auf dem Oberflächen-Thread angefasst). */
    private val retired = ArrayDeque<YuvFrame>()

    private fun retire(frame: YuvFrame) {
        retired.addLast(frame)
        while (retired.size > RETIRED_FRAMES) retired.removeFirst().close()
    }

    /** Bilder des vorigen Streams verwerfen (kein altes Standbild beim nächsten). */
    private fun clearFrames() {
        val old = yuv.getAndSet(null)
        ui {
            if (old != null) retire(old)
            while (retired.isNotEmpty()) retired.removeFirst().close()
        }
        frame = null
        hasFrame = false
    }

    private inline fun ui(crossinline block: () -> Unit) = SwingUtilities.invokeLater { block() }

    companion object {
        const val NETWORK_CACHE_MS = 2_500
        /** Ab dieser Bildhöhe gilt ein Stream als 4K. */
        const val UHD_HEIGHT = 1_600
        /** Kennung der geladenen Untertitel-Datei in [subtitleTracks]. */
        const val EXTERNAL = 1_000_000
        private const val RETIRED_FRAMES = 3
    }
}

/** Stream-Info: was gerade läuft und wie gut. */
data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val videoCodec: String,
    val audioCodec: String,
    val audioChannels: Int,
    /** Gelesene Daten (Leitung) und Video-/Ton-Daten in kbit/s. */
    val inputKbps: Int,
    val demuxKbps: Int,
    val displayed: Int,
    val lost: Int,
    val corrupted: Int,
    val startupMs: Long,
    val cacheMs: Int,
    /** Videoweg und HDR („Grafikkarte, HDR10 angepasst“). */
    val output: String = "–",
) {
    val resolution: String get() = when {
        height >= 2000 -> "4K (${width}×$height)"
        height >= 1000 -> "Full HD (${width}×$height)"
        height >= 700 -> "HD (${width}×$height)"
        height > 0 -> "SD (${width}×$height)"
        else -> "–"
    }

    /** Zeilen für die Anzeige. */
    fun lines(): List<Pair<String, String>> = listOf(
        "Auflösung" to resolution,
        "Ausgabe" to output,
        "Bildrate" to if (fps > 0) "%.3f fps".format(java.util.Locale.GERMAN, fps).replace(",000", "") else "–",
        "Video" to videoCodec.ifBlank { "–" },
        "Ton" to (audioCodec.ifBlank { "–" } + if (audioChannels > 0) ", ${channelsLabel(audioChannels)}" else ""),
        "Empfangen" to if (inputKbps > 0) mbit(inputKbps) else "–",
        "Datenrate" to if (demuxKbps > 0) mbit(demuxKbps) else "–",
        "Bilder" to "$displayed gezeigt, $lost verworfen" + if (corrupted > 0) ", $corrupted beschädigt" else "",
        "Startzeit" to if (startupMs > 0) "%.2f s".format(java.util.Locale.GERMAN, startupMs / 1000.0) else "–",
        "Puffer" to "%.1f s".format(java.util.Locale.GERMAN, cacheMs / 1000.0),
    )

    private fun mbit(kbps: Int) = "%.1f Mbit/s".format(java.util.Locale.GERMAN, kbps / 1000.0)
    private fun channelsLabel(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n Kanäle" }
}

/** Pufferprofile wie auf dem Fire TV. */
enum class BufferProfile(val label: String, val cacheMs: Int, val hint: String) {
    FAST("Schnell", 1_200, "kurze Umschaltzeit, für gute Leitungen"),
    BALANCED("Ausgewogen", VideoPlayer.NETWORK_CACHE_MS, "Standard"),
    STABLE("Stabil (4K)", 6_000, "großer Puffer gegen Ruckeln bei 4K und schwankender Leitung"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

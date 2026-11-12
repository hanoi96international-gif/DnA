@file:OptIn(UnstableApi::class)

package de.ayutv.player.dna.engine

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import java.util.TreeMap

/**
 * Der DNA Player auf der eigenen DNA Engine, hinter der gemeinsamen Player-Schnittstelle der App (Bildfläche,
 * Steuerung, Spuren, Untertitel-Anzeige bleiben, wie sie sind). Spielt Live-TV (TS, HLS) und Filme (MKV, MP4, TS, HLS)
 * mit Spulen, Tempo, Ton- und Untertitelspuren.
 */
class DnaEnginePlayer(
    http: OkHttpClient,
    private val userAgent: () -> String?,
    /**
     * Adresse, von der die Engine liest, und die fürs Neuverbinden bzw. „wieder Live“ (Zeitversatz: der Ringspeicher
     * auf dem Gerät; ohne: direkt der Anbieter, dann beides gleich).
     */
    private val resolve: (MediaItem) -> Pair<String, String>? = { item -> item.localConfiguration?.uri?.toString()?.let { it to it } },
    /** Die Engine liest nicht mehr (gestoppt, freigegeben): Zeitversatz-Speicher und Verbindung freigeben. */
    private val onStopped: () -> Unit = {},
    /** Live-Sender? (sonst Film/Folge: spulbar, mit Ende). */
    private val isLive: (MediaItem) -> Boolean = { true },
    config: DnaEngineCore.Config = DnaEngineCore.Config(),
    /** Ton-Bearbeitung (Sprach-Auswertung, Nachtmodus) im PCM-Weg. */
    processors: List<DnaEngineCore.PcmProcessor> = emptyList(),
    /** Ohne erstes Bild nach so langer Zeit gilt der Start als gescheitert (die App reagiert darauf). */
    private val firstFrameTimeoutMs: Long = 25_000,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var liveUrl: String? = null
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var item: MediaItem? = null
    private var itemUid = 0
    private var startPositionMs = 0L
    private var live = true
    private var durationUs = -1L
    private var seekable = false
    private var videoSize = VideoSize.UNKNOWN
    private var firstFrame = false
    private var shownFirstFrame = false
    private var volume = 1f
    private var speed = 1f
    private var trackParameters = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    private var videoTrackNow: Track? = null
    private var audioTracks: List<Track> = emptyList()
    private var textTracks: List<Track> = emptyList()
    private var selectedAudio: Track? = null
    private var selectedText: Track? = null
    private var videoOutput: Any? = null
    private var holder: SurfaceHolder? = null
    private var texture: TextureView? = null
    private var textureSurface: Surface? = null
    private var released = false
    private var cues = CueGroup.EMPTY_TIME_ZERO

    /** Untertitel je Spur (Medienzeit µs → Einblendung), gefüllt beim Lesen. */
    private val textEntries = HashMap<Int, TreeMap<Long, SubtitleCues.Entry>>()
    private val textDecoders = HashMap<Int, SubtitleCues>()

    /** Was die Engine zuletzt meldete (Decoder, Neuverbinden …) – für Anzeige und Fehlerbericht. */
    var lastInfo: String = ""
        private set

    /** Ton-Proben der gewählten Spur beim Lesen (Format mit Decoder-Daten, Medienzeit, Bytes) – Ton-Auswertung nebenbei. */
    var audioSink: ((format: Format, timeUs: Long, data: ByteArray) -> Unit)? = null
        set(value) {
            field = value
            core.audioTap = if (value == null) null else { sample -> value(audioFormat(sample), sample.timeUs, sample.data) }
        }

    private var lastAudioKey: Any? = null
    private var lastAudioFormat: Format? = null

    /** Format einer Ton-Probe (für Decoder nebenbei): Spur plus Rahmen-Format (AAC: AudioSpecificConfig). */
    private fun audioFormat(sample: Sample): Format {
        val key = sample.track to sample.audio
        lastAudioFormat?.let { if (lastAudioKey == key) return it }
        val a = sample.track
        val info = sample.audio
        val csd = if (a.codec == Codec.AAC_ADTS || a.codec == Codec.AAC_LATM) listOfNotNull(info?.config) else a.csd.ifEmpty { listOfNotNull(info?.config) }
        val format = Format.Builder().setId("$AUDIO_PREFIX${a.id}").setSampleMimeType(DnaEngineCore.mimeFor(a.codec, DnaEngineCore.mpegLayer(a.codec, sample.data)))
            .setSampleRate(info?.sampleRate?.takeIf { it > 0 } ?: a.sampleRate.takeIf { it > 0 } ?: 48_000)
            .setChannelCount(info?.channels?.takeIf { it > 0 } ?: a.channels.takeIf { it > 0 } ?: 2)
            .setInitializationData(csd).setLanguage(a.language).build()
        lastAudioKey = key
        lastAudioFormat = format
        return format
    }

    /** Name des laufenden Ton-Decoders. */
    val audioDecoderName: String? get() = core.audioDecoderName

    /** Verbindung zum Anbieter abgerissen (die Engine verbindet selbst neu) – für Stream-Info und Fehlerbericht. */
    var onDrop: ((reason: String) -> Unit)? = null

    /** Eingebettete Untertitel für die App (Übersetzung mit Vorlauf): Format der Spur, Zeit, Dauer, Einblendungen. */
    var textSink: ((format: Format, timeUs: Long, durationUs: Long, cues: List<Cue>) -> Unit)? = null

    private val core = DnaEngineCore(http, object : DnaEngineCore.Listener {
        override fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean) = onMain {
            this@DnaEnginePlayer.durationUs = durationUs
            this@DnaEnginePlayer.seekable = seekable
            this@DnaEnginePlayer.live = live
        }

        override fun onBuffering(buffering: Boolean) = onMain {
            if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
                playbackState = if (buffering) Player.STATE_BUFFERING else Player.STATE_READY
            }
        }

        override fun onTracks(video: Track?, audio: List<Track>, text: List<Track>, selectedAudio: Track?) = onMain {
            videoTrackNow = video
            audioTracks = audio
            textTracks = text
            this@DnaEnginePlayer.selectedAudio = selectedAudio
            selectedText = chooseText()
            tracks = buildTracks()
        }

        override fun onVideoSize(width: Int, height: Int) = onMain {
            if (width > 0 && height > 0) {
                videoSize = VideoSize(width, height)
                tracks = buildTracks()
            }
        }

        override fun onFirstFrame() = onMain {
            firstFrame = true
            shownFirstFrame = true
            main.removeCallbacks(firstFrameTimeout)
        }

        override fun onText(sample: Sample) = handleText(sample)

        override fun onEnded() = onMain {
            if (!live) playbackState = Player.STATE_ENDED
        }

        override fun onError(message: String, fatal: Boolean) = onMain {
            DnaLog.w("DnaEnginePlayer", (if (fatal) "Abbruch: " else "Fehler: ") + message)
            lastInfo = message
            if (fatal) fail(message)
        }

        override fun onInfo(text: String) = onMain {
            DnaLog.i("DnaEnginePlayer", text)
            lastInfo = text
            if (text.startsWith("Anbieter hat") || text.startsWith("Verbindung unterbrochen")) runCatching { onDrop?.invoke(text) }
        }
    }, config).also { it.pcmProcessors.addAll(processors) }

    init {
        DnaLog.sink = { tag, text -> android.util.Log.i(tag, text) }
    }

    private val firstFrameTimeout = Runnable {
        if (!shownFirstFrame && item != null && playbackState != Player.STATE_IDLE && videoTrackNow != null) {
            fail("DNA Engine: kein Bild nach ${firstFrameTimeoutMs / 1000} s" + lastInfo.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty())
        }
    }

    /** Untertitel und Ende: alle 100 ms nachsehen. */
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (item != null && playbackState != Player.STATE_IDLE) {
                core.checkEnded()
                val newCues = currentCuesAt(core.positionUs)
                if (newCues.cues != cues.cues) {
                    cues = newCues
                    invalidateState()
                }
            }
            main.postDelayed(this, 100)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) = core.setSurface(h.surface)
        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(h: SurfaceHolder) = core.setSurface(null)
    }

    /** Engine-Name für Anzeige und Fehlerbericht. */
    val engineName: String get() = "DNA Engine"

    val droppedFrames: Int get() = core.droppedFrames
    val renderedFrames: Int get() = core.renderedFrames
    /** Bildrate des Stroms aus den Zeitstempeln (0 = noch unbekannt). */
    val streamFrameRate: Float get() = core.streamFrameRate
    /** Zustand der Engine für Protokoll und Tests (Vorrat, Uhr, Ton). */
    fun engineState(): String = core.stateLine()
    /** Laufender Bild-Decoder (Anzeige, Fehlerbericht). */
    val videoDecoderName: String? get() = core.videoDecoderName
    /** Ton geht an Receiver/Soundbar (nicht im Gerät dekodiert). */
    val audioPassthrough: Boolean get() = core.passthrough

    /** Filme: vorübergehend weiter vorausladen (µs, 0 = normal), etwa für die Untertitel-Ausrichtung am Ton. */
    var extraReadAheadUs: Long
        get() = core.extraReadAheadUs
        set(value) { core.extraReadAheadUs = value }

    private fun onMain(block: () -> Unit) {
        main.post {
            if (released) return@post
            block()
            invalidateState()
        }
    }

    private fun fail(message: String) {
        // Fehlerart wie bei Media3, damit die App die passenden Ausweichwege nimmt (andere Fassung, neuer Versuch …).
        val http = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val code = when {
            http != null -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            message.contains("nicht erreichbar") || message.contains("Verbindung") -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            message.contains("Kein Bild-Decoder") || message.contains("scheitern an") -> PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            message.contains("lässt sich nicht lesen") || message.contains("HLS nicht abspielbar") -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            message.contains("kein Bild nach") -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            else -> PlaybackException.ERROR_CODE_DECODING_FAILED
        }
        error = PlaybackException(message, null, code)
        rememberPosition()
        playbackState = Player.STATE_IDLE
        core.stop()
        invalidateState()
    }

    // ---------------------------------------------------------------- Untertitel

    private fun handleText(sample: Sample) {
        val track = sample.track
        val decoder = synchronized(textDecoders) { textDecoders.getOrPut(track.id) { SubtitleCues(track) } }
        val entry = runCatching { decoder.decode(sample) }.getOrNull() ?: return
        main.post {
            if (released) return@post
            val map = textEntries.getOrPut(track.id) { TreeMap() }
            // Bild-Untertitel ohne Dauer: die vorige endet hier.
            map.floorEntry(entry.startUs - 1)?.value?.let { previous -> if (previous.endUs == Long.MAX_VALUE) previous.endUs = entry.startUs }
            if (entry.cues.isEmpty()) {
                map.floorEntry(entry.startUs)?.value?.let { if (it.endUs > entry.startUs) it.endUs = entry.startUs }
            } else map[entry.startUs] = entry
            // Begrenzt halten (lange Filme).
            while (map.size > 4000) map.pollFirstEntry()
            val format = textFormat(track)
            textSink?.let { sink ->
                val duration = if (entry.endUs == Long.MAX_VALUE) 0 else entry.endUs - entry.startUs
                runCatching { sink(format, entry.startUs, duration, entry.cues) }
            }
        }
    }

    private fun currentCuesAt(positionUs: Long): CueGroup {
        val track = selectedText ?: return CueGroup.EMPTY_TIME_ZERO
        val map = textEntries[track.id] ?: return CueGroup.EMPTY_TIME_ZERO
        val active = ArrayList<Cue>()
        // Überlappende Einblendungen (zwei Zeilen gleichzeitig) mitnehmen.
        for (e in map.headMap(positionUs, true).descendingMap().values) {
            if (e.startUs < positionUs - 30_000_000) break
            if (positionUs < e.endUs) active.addAll(0, e.cues)
        }
        return if (active.isEmpty()) CueGroup.EMPTY_TIME_ZERO else CueGroup(active, positionUs)
    }

    /** Untertitelspur: ausdrücklich gewählt, sonst bevorzugte Sprache, sonst eine erzwungene (Fremdsprachen-Teile). */
    private fun chooseText(): Track? {
        if (trackParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
        trackParameters.overrides.keys.firstOrNull { it.id.startsWith(TEXT_PREFIX) }?.let { group ->
            val id = group.id.removePrefix(TEXT_PREFIX).toIntOrNull()
            return textTracks.firstOrNull { it.id == id }
        }
        val wanted = trackParameters.preferredTextLanguages
        for (language in wanted) textTracks.firstOrNull { DnaEngineCore.sameLanguage(it.language, language) && !it.forced }?.let { return it }
        return textTracks.firstOrNull { it.forced && (selectedAudio == null || DnaEngineCore.sameLanguage(it.language, selectedAudio?.language)) }
    }

    // ---------------------------------------------------------------- Zustand

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (item == null) Player.STATE_IDLE else playbackState)
            .setPlayerError(error)
            .setVolume(volume)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParameters)
            .setVideoSize(videoSize)
            .setCurrentCues(cues)
            .setNewlyRenderedFirstFrame(firstFrame.also { firstFrame = false })
        val current = item
        if (current != null) {
            builder.setPlaylist(
                listOf(
                    MediaItemData.Builder(itemUid).setMediaItem(current).setTracks(tracks)
                        .setIsDynamic(live).setIsSeekable(seekable && !live)
                        .setLiveConfiguration(if (live) MediaItem.LiveConfiguration.UNSET else null)
                        .setDurationUs(if (!live && durationUs > 0) durationUs else C.TIME_UNSET)
                        .build(),
                ),
            )
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs { core.positionUs / 1000 }
                .setContentBufferedPositionMs { (core.positionUs + core.bufferedUs) / 1000 }
                .setTotalBufferedDurationMs { core.bufferedUs / 1000 }
        }
        return builder.build()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        core.release()
        currentUrl = null
        liveUrl = null
        item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        this.startPositionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        itemUid++
        error = null
        tracks = Tracks.EMPTY
        textEntries.clear()
        synchronized(textDecoders) { textDecoders.clear() }
        cues = CueGroup.EMPTY_TIME_ZERO
        videoSize = VideoSize.UNKNOWN
        shownFirstFrame = false
        durationUs = -1
        seekable = false
        live = item?.let(isLive) ?: true
        if (playbackState != Player.STATE_IDLE) playbackState = Player.STATE_BUFFERING
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val current = item ?: return Futures.immediateVoidFuture()
        core.stop()
        val (url, liveAddress) = resolve(current) ?: return Futures.immediateVoidFuture()
        liveUrl = liveAddress
        live = isLive(current)
        start(url, if (live) 0 else startPositionMs * 1000)
        main.removeCallbacks(ticker)
        main.post(ticker)
        return Futures.immediateVoidFuture()
    }

    private fun start(url: String, startUs: Long) {
        currentUrl = url
        error = null
        shownFirstFrame = false
        playbackState = Player.STATE_BUFFERING
        core.setPaused(!playWhenReady)
        core.setSpeed(speed)
        core.open(url, userAgent(), liveUrl, live, startUs)
        main.removeCallbacks(firstFrameTimeout)
        main.postDelayed(firstFrameTimeout, firstFrameTimeoutMs)
    }

    /** Zeitversatz: ab einer anderen Stelle des Ringspeichers lesen (gleicher Sender, gleiche Spuren). */
    fun openUrl(url: String) {
        if (item == null || released) return
        start(url, 0)
        invalidateState()
    }

    /** Wieder ab Live lesen (ohne neue Verbindung zum Anbieter). */
    fun goLive() {
        val target = liveUrl ?: currentUrl ?: return
        openUrl(target)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        core.setPaused(!playWhenReady)
        return Futures.immediateVoidFuture()
    }

    /**
     * Film: die jetzige Stelle als Startstelle merken, bevor der Kern anhält. Nach einem Abbruch (Anbieter trennt,
     * Fehler) setzt die App mit stop() → seekTo() → prepare() neu an; ohne das begann der Film wieder von vorn.
     */
    private fun rememberPosition() {
        if (live || currentUrl == null) return
        val ms = core.positionUs / 1000
        if (ms > 0) startPositionMs = ms
    }

    override fun handleStop(): ListenableFuture<*> {
        rememberPosition()
        core.release()
        main.removeCallbacks(firstFrameTimeout)
        playbackState = Player.STATE_IDLE
        onStopped()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacks(firstFrameTimeout)
        main.removeCallbacks(ticker)
        detach()
        core.release()
        onStopped()
        return Futures.immediateVoidFuture()
    }

    /** Film: an die Stelle springen. Live: „an den Rand“ = ab dem Neuesten lesen (Zeitversatz-Speicher bzw. Anbieter neu). */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (item == null) return Futures.immediateVoidFuture()
        if (playbackState == Player.STATE_IDLE) {
            // Angehalten (nach stop() oder Fehler): Die Stelle gilt für das nächste prepare().
            if (!live && positionMs != C.TIME_UNSET) startPositionMs = positionMs.coerceAtLeast(0)
            return Futures.immediateVoidFuture()
        }
        if (live || !seekable) {
            goLive()
        } else {
            val target = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_BUFFERING
            cues = CueGroup.EMPTY_TIME_ZERO
            core.seekTo(target * 1000)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        core.setSpeed(speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        core.setVolume(volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParameters = trackSelectionParameters
        core.videoEnabled = !trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        core.setPreferredAudioLanguage(trackSelectionParameters.preferredAudioLanguages.firstOrNull())
        trackSelectionParameters.overrides.keys.firstOrNull { it.id.startsWith(AUDIO_PREFIX) }?.let { group ->
            val id = group.id.removePrefix(AUDIO_PREFIX).toIntOrNull()
            audioTracks.firstOrNull { it.id == id }?.takeIf { it != selectedAudio }?.let(core::selectAudio)
        }
        selectedText = chooseText()
        cues = currentCuesAt(core.positionUs)
        tracks = buildTracks()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput === this.videoOutput) return Futures.immediateVoidFuture()
        detach()
        this.videoOutput = videoOutput
        when (videoOutput) {
            is SurfaceView -> attachHolder(videoOutput.holder)
            is SurfaceHolder -> attachHolder(videoOutput)
            is TextureView -> attachTexture(videoOutput)
            is Surface -> core.setSurface(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === this.videoOutput) detach()
        return Futures.immediateVoidFuture()
    }

    private fun attachHolder(h: SurfaceHolder) {
        holder = h
        h.addCallback(surfaceCallback)
        h.surface?.takeIf { it.isValid }?.let(core::setSurface)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
            textureSurface?.release()
            textureSurface = Surface(st).also(core::setSurface)
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
            core.setSurface(null)
            textureSurface?.release()
            textureSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
    }

    private fun attachTexture(view: TextureView) {
        texture = view
        view.surfaceTextureListener = textureListener
        view.surfaceTexture?.let { textureListener.onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    private fun detach() {
        texture?.let { if (it.surfaceTextureListener === textureListener) it.surfaceTextureListener = null }
        texture = null
        textureSurface?.release()
        textureSurface = null
        holder?.removeCallback(surfaceCallback)
        holder = null
        videoOutput = null
        core.setSurface(null)
    }

    // ---------------------------------------------------------------- Spuren


    private fun buildTracks(): Tracks {
        val groups = ArrayList<Tracks.Group>()
        videoTrackNow?.let { v ->
            val format = Format.Builder().setId("dna-video-${v.id}").setSampleMimeType(v.codec.mime)
                .apply {
                    val w = videoSize.width.takeIf { it > 0 } ?: v.width
                    val h = videoSize.height.takeIf { it > 0 } ?: v.height
                    if (w > 0) setWidth(w).setHeight(h)
                    if (v.hdr) setColorInfo(androidx.media3.common.ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
                }.build()
            groups += Tracks.Group(TrackGroup("dna-video-${v.id}", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        }
        audioTracks.forEach { a ->
            val format = Format.Builder().setId("$AUDIO_PREFIX${a.id}").setSampleMimeType(a.codec.mime).setLanguage(a.language)
                .setLabel(a.label ?: a.codec.name.replace('_', ' '))
                .apply { if (a.channels > 0) setChannelCount(a.channels); if (a.sampleRate > 0) setSampleRate(a.sampleRate) }
                .build()
            val supported = if (DnaEngineCore.playable(a.codec)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
            groups += Tracks.Group(TrackGroup("$AUDIO_PREFIX${a.id}", format), false, intArrayOf(supported), booleanArrayOf(a == selectedAudio))
        }
        textTracks.forEach { t ->
            val supported = if (t.codec in TEXT_SUPPORTED) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
            groups += Tracks.Group(TrackGroup("$TEXT_PREFIX${t.id}", textFormat(t)), false, intArrayOf(supported), booleanArrayOf(t == selectedText))
        }
        return Tracks(groups)
    }

    companion object {
        private const val AUDIO_PREFIX = "dna-audio-"
        private const val TEXT_PREFIX = "dna-text-"
        private val TEXT_SUPPORTED = setOf(Codec.SRT, Codec.ASS, Codec.WEBVTT, Codec.TX3G, Codec.PGS, Codec.DVBSUB)

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_GET_TEXT,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_VIDEO_SURFACE,
        ).build()

        /** Format einer Untertitelspur – gleiche Kennung im Player und beim nächtlichen Lesen (gespeicherte Übersetzungen). */
        fun textFormat(t: Track): Format = Format.Builder().setId("$TEXT_PREFIX${t.id}").setSampleMimeType(t.codec.mime).setCodecs(t.codec.mime)
            .setLanguage(t.language).setLabel(t.label)
            .setSelectionFlags((if (t.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or (if (t.forced) C.SELECTION_FLAG_FORCED else 0))
            .build()

        /** Stumme Vorschau (Senderliste, kleines Bild): kleiner Vorrat, höchstens 720p bei HLS. */
        fun preview(http: OkHttpClient, userAgent: () -> String?): DnaEnginePlayer =
            DnaEnginePlayer(
                http, userAgent,
                config = DnaEngineCore.Config(liveBufferUs = 3_000_000, startBufferUs = 600_000, maxVideoHeight = 720, maxBufferBytes = 24L * 1024 * 1024),
            ).apply { volume = 0f }

        /** Kann die DNA Engine diesen Stream? Alles außer DASH (.mpd). */
        fun canPlay(url: String, live: Boolean): Boolean {
            val path = url.substringBefore('?').lowercase()
            return !path.endsWith(".mpd")
        }
    }
}

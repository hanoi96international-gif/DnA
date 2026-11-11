package de.ayutv.desktop.player

import de.ayutv.player.dna.engine.Codec
import de.ayutv.player.dna.engine.DnaReader
import de.ayutv.player.dna.engine.Kind
import de.ayutv.player.dna.engine.Sample
import de.ayutv.player.dna.engine.SubtitleImage
import de.ayutv.player.dna.engine.Track
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avfilter.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * DNA Engine für Windows: dieselben eigenen Leser wie auf Android ([DnaReader]: Live-TS mit Neuverbinden, MKV, MP4, HLS,
 * TS-Dateien), dekodiert mit FFmpeg – Bild auf der Grafikkarte (D3D11VA, sonst auf dem Prozessor mit mehreren Kernen),
 * Halbbilder (1080i/576i) mit voller Bildrate entflochten, Ton über Java Sound. Der Ton ist der Taktgeber, jedes Bild
 * kommt zu seiner Zeit; zu späte werden verworfen.
 *
 * Bilder gehen als YUV-Ebenen an [Output.onFrame] (die Oberfläche rechnet die Farben per Shader um, auch HDR).
 */
class DesktopEngine(private val http: OkHttpClient, private val output: Output) {
    interface Output {
        fun onPrepared(durationMs: Long, seekable: Boolean, live: Boolean)
        fun onTracks(video: Track?, audio: List<Track>, text: List<Track>, selectedAudio: Track?)
        fun onFrame(frame: Frame)
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(message: String)
        fun onInfo(text: String)
        /** Dekodierter Ton (Stereo, 16 Bit), Medienzeit des ersten Samples – für die Auswertung der Sprache. */
        fun onPcm(data: ByteArray, frames: Int, rate: Int, timeUs: Long) = Unit
    }

    /** Ein Bild (Ebenen schon als Bilder für den Shader) und seine Medienzeit. */
    class Frame(val yuv: YuvFrame, val timeUs: Long)

    class Config(
        val liveBufferUs: Long = 8_000_000,
        val vodBufferUs: Long = 30_000_000,
        val maxBufferBytes: Long = 256L * 1024 * 1024,
        val startBufferUs: Long = 1_200_000,
        /** AUTO = wie dekodiert, sonst eine feste Ablage (I420, NV12, P010). */
        val layout: String = "AUTO",
        val hardware: Boolean = true,
    )

    @Volatile var config = Config()
    /** HDR: AUTO (aus dem Stream), PQ, HLG, OFF. */
    @Volatile var hdrMode = "AUTO"
    /** HDR-Art des zuletzt gezeigten Bildes. */
    @Volatile var transfer = YuvVideo.Transfer.SDR; private set
    @Volatile var layout = YuvVideo.Layout.I420; private set
    private val running = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile var volume = 1f
    @Volatile var muted = false
    /** Ton später (positiv) bzw. früher, ms. */
    @Volatile var audioDelayMs = 0L
    /** Klangregelung je Band (dB, 10 Bänder 31 Hz … 16 kHz), null = aus. */
    @Volatile var equalizer: FloatArray? = null
    /** Nachtmodus/Dialoge: Dynamik zusammendrücken. */
    @Volatile var compressor = false
    /** Lautstärke angleichen (langsame Regelung). */
    @Volatile var normalize = false

    private val lock = Object()
    private val videoQueue = ArrayDeque<Sample>()
    private val audioQueue = ArrayDeque<Sample>()
    private var queuedBytes = 0L
    @Volatile private var inputEnded = false
    @Volatile private var lastMediaUs = 0L
    @Volatile private var seekTargetUs = 0L
    @Volatile private var endedSent = false
    @Volatile var durationUs = -1L; private set
    @Volatile var seekable = false; private set
    @Volatile var live = true; private set

    @Volatile var videoTrack: Track? = null; private set
    @Volatile var audioTracks: List<Track> = emptyList(); private set
    @Volatile var textTracks: List<Track> = emptyList(); private set
    @Volatile var audioTrack: Track? = null; private set
    @Volatile private var preferredAudioLanguage: String? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** Untertitel aller Spuren (beim Lesen, also mit Vorlauf). */
    val subtitles = DesktopSubtitles()

    // Zahlen für die Stream-Info.
    @Volatile var renderedFrames = 0L; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var decoderName = ""; private set
    @Volatile var audioDecoderName = ""; private set
    @Volatile var hardwareDecoding = false; private set
    @Volatile var deinterlacing = false; private set
    @Volatile var frameRate = 0.0; private set
    private val bytesWindow = ArrayDeque<Pair<Long, Int>>()
    val inputKbps: Int
        get() = synchronized(bytesWindow) {
            val now = System.currentTimeMillis()
            while (bytesWindow.isNotEmpty() && now - bytesWindow.first().first > 3_000) bytesWindow.removeFirst()
            (bytesWindow.sumOf { it.second.toLong() } * 8 / 3_000).toInt()
        }

    private val clock = Clock()
    val positionUs: Long get() = if (!clock.started) seekTargetUs else clock.nowUs().coerceAtLeast(0)
    val bufferedUs: Long get() = synchronized(lock) { bufferedAheadLocked() }

    private val reader = DnaReader(http, DnaReader.Config(), object : DnaReader.Sink {
        override fun alive(gen: Int) = this@DesktopEngine.alive(gen)
        override fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean) {
            this@DesktopEngine.durationUs = durationUs
            this@DesktopEngine.seekable = seekable
            this@DesktopEngine.live = live
            output.onPrepared(if (durationUs > 0) durationUs / 1000 else 0, seekable, live)
        }
        override fun onTracks(tracks: List<Track>) = handleTracks(tracks)
        override fun onSample(sample: Sample, gen: Int) = handleSample(sample, gen)
        override fun onEnded() {
            inputEnded = true
            synchronized(lock) { (lock as Object).notifyAll() }
        }
        override fun onError(message: String, fatal: Boolean) = if (fatal) output.onError(message) else output.onInfo(message)
        override fun onInfo(text: String) = output.onInfo(text)
        override fun onReconnect() {
            resetVideo = true
        }
        override fun awaitRoom(gen: Int, limitUs: Long) {
            val limit = if (live) config.liveBufferUs else config.vodBufferUs
            synchronized(lock) {
                while (alive(gen) && (bufferedAheadLocked() > limit || queuedBytes > config.maxBufferBytes)) (lock as Object).wait(50)
            }
        }
        override val lastMediaUs: Long get() = this@DesktopEngine.lastMediaUs
    })

    @Volatile private var resetVideo = false

    private fun alive(gen: Int) = running.get() && gen == generation

    // ================================================================ Steuerung

    fun open(url: String, userAgent: String?, live: Boolean, startUs: Long) {
        stop()
        reader.open(url, userAgent, null, live)
        this.live = live
        durationUs = -1
        seekable = false
        videoTrack = null
        audioTracks = emptyList()
        textTracks = emptyList()
        subtitles.clear()
        renderedFrames = 0
        droppedFrames = 0
        frameRate = 0.0
        start(startUs.coerceAtLeast(0), first = true)
    }

    fun seekTo(us: Long) {
        if (!reader.hasUrl) return
        stop()
        start(us.coerceIn(0, if (durationUs > 0) durationUs else Long.MAX_VALUE), first = false)
    }

    private fun start(startUs: Long, first: Boolean) {
        running.set(true)
        val gen = ++generation
        seekTargetUs = startUs
        inputEnded = false
        endedSent = false
        lastMediaUs = startUs
        clock.reset()
        resetVideo = false
        output.onBuffering(true)
        reader.start(gen, startUs, first) { what, block -> guard(what, block) }
        videoThread = thread(name = "dna-video", isDaemon = true) { guard("Bild") { videoLoop(gen) } }
        audioThread = thread(name = "dna-audio", isDaemon = true) { guard("Ton") { audioLoop(gen) } }
    }

    private fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            // beendet
        } catch (e: Throwable) {
            de.ayutv.desktop.Log.warn("DNA Engine, $what: $e")
            if (running.get()) output.onInfo("$what gestört: ${e.javaClass.simpleName}")
        }
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        clock.setPaused(paused)
        synchronized(lock) { (lock as Object).notifyAll() }
    }

    fun selectAudio(track: Track) {
        audioTrack = track
        preferredAudioLanguage = track.language
        if (!live && seekable) seekTo(positionUs) else synchronized(lock) {
            audioQueue.forEach { queuedBytes -= it.data.size }
            audioQueue.clear()
        }
        output.onTracks(videoTrack, audioTracks, textTracks, track)
    }

    fun setPreferredAudioLanguage(language: String?) {
        preferredAudioLanguage = language
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        generation++
        synchronized(lock) {
            videoQueue.clear()
            audioQueue.clear()
            queuedBytes = 0
            (lock as Object).notifyAll()
        }
        reader.stop()
        videoThread?.interrupt()
        audioThread?.interrupt()
        runCatching { videoThread?.join(1_000) }
        runCatching { audioThread?.join(1_000) }
        videoThread = null
        audioThread = null
    }

    fun release() {
        stop()
        reader.close()
    }

    // ================================================================ Proben

    private fun handleTracks(tracks: List<Track>) {
        val v = tracks.firstOrNull { it.codec.kind == Kind.VIDEO }
        val audios = tracks.filter { it.codec.kind == Kind.AUDIO && audioCodecId(it.codec) != AV_CODEC_ID_NONE }
        val texts = tracks.filter { it.codec.kind == Kind.TEXT }
        videoTrack = v
        audioTracks = audios
        textTracks = texts
        val current = audioTrack
        if (current == null || current !in audios) {
            val language = preferredAudioLanguage
            audioTrack = audios.firstOrNull { language != null && sameLanguage(it.language, language) }
                ?: audios.firstOrNull { it.isDefault } ?: audios.firstOrNull()
        }
        output.onTracks(v, audios, texts, audioTrack)
    }

    private fun handleSample(sample: Sample, gen: Int) {
        if (gen != generation) return
        synchronized(bytesWindow) { bytesWindow.addLast(System.currentTimeMillis() to sample.data.size) }
        val track = sample.track
        when (track.codec.kind) {
            Kind.TEXT -> {
                subtitles.add(sample)
                return
            }
            Kind.VIDEO -> if (track != videoTrack) return
            Kind.AUDIO -> if (track != audioTrack) return
        }
        synchronized(lock) {
            if (track.codec.kind == Kind.VIDEO) videoQueue.addLast(sample) else audioQueue.addLast(sample)
            queuedBytes += sample.data.size
            val end = sample.timeUs + sample.durationUs
            if (end > lastMediaUs) lastMediaUs = end
            (lock as Object).notifyAll()
        }
    }

    private fun bufferedAheadLocked(): Long {
        val now = if (clock.started) clock.nowUs() else seekTargetUs
        fun ahead(q: ArrayDeque<Sample>) = q.lastOrNull()?.let { (it.timeUs - now).coerceAtLeast(0) } ?: 0L
        return maxOf(ahead(videoQueue), ahead(audioQueue))
    }

    private fun startBufferReachedLocked(): Boolean {
        if (clock.started || inputEnded) return true
        val first = minOf(videoQueue.firstOrNull()?.timeUs ?: Long.MAX_VALUE, audioQueue.firstOrNull()?.timeUs ?: Long.MAX_VALUE)
        val last = maxOf(videoQueue.lastOrNull()?.timeUs ?: Long.MIN_VALUE, audioQueue.lastOrNull()?.timeUs ?: Long.MIN_VALUE)
        return (last != Long.MIN_VALUE && last - maxOf(first, seekTargetUs) >= config.startBufferUs) || videoQueue.size > 120 || audioQueue.size > 500
    }

    private fun take(gen: Int, queue: ArrayDeque<Sample>): Sample? = synchronized(lock) {
        while (alive(gen) && (queue.isEmpty() || !startBufferReachedLocked())) {
            if (inputEnded && queue.isEmpty()) return null
            (lock as Object).wait(50)
        }
        queue.removeFirstOrNull()?.also { queuedBytes -= it.data.size; (lock as Object).notifyAll() }
    }

    /** Ende erreicht: alles gelesen, gespielt und gezeigt. */
    private fun checkEnded() {
        if (!inputEnded || endedSent || live) return
        val empty = synchronized(lock) { videoQueue.isEmpty() && audioQueue.isEmpty() }
        if (empty && (!clock.started || positionUs >= lastMediaUs - 200_000)) {
            endedSent = true
            output.onEnded()
        }
    }

    // ================================================================ Bild

    private fun videoLoop(gen: Int) {
        var decoder: VideoDecoder? = null
        var firstShown = false
        var lastPts = Long.MIN_VALUE
        try {
            while (alive(gen)) {
                val sample = take(gen, videoQueue)
                if (sample == null) {
                    checkEnded()
                    if (!alive(gen)) break
                    Thread.sleep(50)
                    continue
                }
                if (resetVideo) {
                    resetVideo = false
                    decoder?.flush()
                }
                if (decoder == null || decoder.track != sample.track) {
                    if (!sample.keyframe) continue
                    decoder?.release()
                    decoder = VideoDecoder(sample.track, sample.data)
                    decoderName = decoder.name
                    hardwareDecoding = decoder.hardware
                }
                val d = decoder
                for (frame in d.decode(sample)) {
                    if (!alive(gen)) {
                        frame.yuv.close()
                        continue
                    }
                    if (frame.timeUs < seekTargetUs - 1_000) {
                        frame.yuv.close()
                        continue
                    }
                    if (lastPts != Long.MIN_VALUE && frame.timeUs > lastPts) {
                        val delta = (frame.timeUs - lastPts) / 1e6
                        if (delta in 0.005..0.2) frameRate = if (frameRate == 0.0) 1 / delta else frameRate * 0.95 + 0.05 / delta
                    }
                    lastPts = frame.timeUs
                    if (!clock.started) clock.startFromVideo(frame.timeUs, hasAudio = audioTrack != null)
                    // Zeitsprung (Live): Uhr neu ab diesem Bild.
                    if (live && kotlin.math.abs(frame.timeUs - clock.nowUs()) > JUMP_US) {
                        clock.reset()
                        clock.startFromVideo(frame.timeUs, hasAudio = audioTrack != null)
                    }
                    // Bis zum Zeitpunkt des Bildes warten (angehalten: Bild zurückhalten).
                    while (alive(gen)) {
                        if (paused && firstShown) {
                            Thread.sleep(20)
                            continue
                        }
                        val early = frame.timeUs - clock.nowUs()
                        if (early <= 4_000) break
                        Thread.sleep((early / 1000).coerceIn(1, 20))
                    }
                    val late = clock.nowUs() - frame.timeUs
                    if (firstShown && late > LATE_US) {
                        droppedFrames++
                        frame.yuv.close()
                        continue
                    }
                    output.onFrame(frame)
                    renderedFrames++
                    if (!firstShown) {
                        firstShown = true
                        output.onBuffering(false)
                    }
                }
            }
        } finally {
            decoder?.release()
        }
    }

    /** FFmpeg-Bilddecoder für eine Spur: Hardware (D3D11VA) wenn möglich, Halbbilder entflochten, Ausgabe als Ebenen. */
    private inner class VideoDecoder(val track: Track, firstKeyframe: ByteArray) {
        private val ctx: AVCodecContext
        private val packet: AVPacket = av_packet_alloc()
        private val frame: AVFrame = av_frame_alloc()
        private val swFrame: AVFrame = av_frame_alloc()
        private val converted: AVFrame = av_frame_alloc()
        private val filtered: AVFrame = av_frame_alloc()
        private var hwDevice: AVBufferRef? = null
        private var sws: SwsContext? = null
        private var swsKey = ""
        private var graph: AVFilterGraph? = null
        private var src: AVFilterContext? = null
        private var sink: AVFilterContext? = null
        private var graphKey = ""
        val name: String
        var hardware = false; private set

        init {
            val id = videoCodecId(track.codec)
            val codec = avcodec_find_decoder(id) ?: error("kein Decoder für ${track.codec}")
            ctx = avcodec_alloc_context3(codec)
            ctx.pkt_timebase(av_make_q(1, 1_000_000))
            val extra = extradata(track, firstKeyframe)
            if (extra != null) {
                val p = BytePointer(av_malloc((extra.size + AV_INPUT_BUFFER_PADDING_SIZE).toLong()))
                p.position(0).put(*extra)
                for (i in extra.size until extra.size + AV_INPUT_BUFFER_PADDING_SIZE) p.put(i.toLong(), 0)
                ctx.extradata(p)
                ctx.extradata_size(extra.size)
            }
            if (track.width > 0) {
                ctx.width(track.width)
                ctx.height(track.height)
            }
            if (config.hardware && isWindows) {
                val device = AVBufferRef()
                if (av_hwdevice_ctx_create(device, AV_HWDEVICE_TYPE_D3D11VA, null as String?, null, 0) >= 0) {
                    hwDevice = device
                    ctx.hw_device_ctx(av_buffer_ref(device))
                }
            }
            // Software: alle Kerne (4K HEVC auf dem Prozessor).
            ctx.thread_count(0)
            ctx.thread_type(3) // FF_THREAD_FRAME | FF_THREAD_SLICE
            val r = avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (r < 0) error("Decoder startet nicht (${errorText(r)})")
            name = "FFmpeg ${codec.name().string}" + if (hwDevice != null) " (D3D11VA)" else ""
        }

        fun flush() = avcodec_flush_buffers(ctx)

        fun decode(sample: Sample): List<Frame> {
            val out = ArrayList<Frame>()
            if (av_new_packet(packet, sample.data.size) < 0) return out
            packet.data().put(*sample.data)
            packet.pts(sample.timeUs)
            packet.dts(AV_NOPTS_VALUE)
            if (sample.keyframe) packet.flags(packet.flags() or AV_PKT_FLAG_KEY)
            avcodec_send_packet(ctx, packet)
            av_packet_unref(packet)
            while (avcodec_receive_frame(ctx, frame) == 0) {
                runCatching { emit(frame, out) }.onFailure { de.ayutv.desktop.Log.warn("Bild: $it") }
                av_frame_unref(frame)
            }
            return out
        }

        private fun emit(decoded: AVFrame, out: MutableList<Frame>) {
            var f = decoded
            if (f.format() == AV_PIX_FMT_D3D11 || f.format() == AV_PIX_FMT_DXVA2_VLD) {
                av_frame_unref(swFrame)
                if (av_hwframe_transfer_data(swFrame, f, 0) < 0) return
                av_frame_copy_props(swFrame, f)
                f = swFrame
                hardware = true
            }
            val interlaced = (f.flags() and 8) != 0 // AV_FRAME_FLAG_INTERLACED
            deinterlacing = interlaced
            if (interlaced) {
                // Halbbilder: yadif mit voller Bildrate (50 statt 25 Bilder bei 1080i).
                if (!deinterlace(f)) return
                while (av_buffersink_get_frame(sink, filtered) >= 0) {
                    out += toFrame(filtered)
                    av_frame_unref(filtered)
                }
                return
            }
            out += toFrame(f)
        }

        private fun deinterlace(f: AVFrame): Boolean {
            val key = "${f.width()}x${f.height()}:${f.format()}"
            if (graph == null || key != graphKey) {
                graph?.let { avfilter_graph_free(it) }
                val g = avfilter_graph_alloc()
                val srcCtx = AVFilterContext()
                val sinkCtx = AVFilterContext()
                val sar = f.sample_aspect_ratio()
                val args = "video_size=${f.width()}x${f.height()}:pix_fmt=${f.format()}:time_base=1/1000000:pixel_aspect=${maxOf(sar.num(), 1)}/${maxOf(sar.den(), 1)}"
                if (avfilter_graph_create_filter(srcCtx, avfilter_get_by_name("buffer"), "in", args, null, g) < 0) return false
                if (avfilter_graph_create_filter(sinkCtx, avfilter_get_by_name("buffersink"), "out", null as String?, null, g) < 0) return false
                val yadif = AVFilterContext()
                if (avfilter_graph_create_filter(yadif, avfilter_get_by_name("yadif"), "deint", "mode=send_field:parity=auto:deint=interlaced", null, g) < 0) return false
                if (avfilter_link(srcCtx, 0, yadif, 0) < 0 || avfilter_link(yadif, 0, sinkCtx, 0) < 0) return false
                if (avfilter_graph_config(g, null) < 0) return false
                graph = g
                src = srcCtx
                sink = sinkCtx
                graphKey = key
            }
            return av_buffersrc_add_frame_flags(src, f, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0
        }

        /** Bild in eine Ablage bringen, die der Shader kann (I420, NV12, P010), und die Ebenen kopieren. */
        private fun toFrame(f: AVFrame): Frame {
            val format = f.format()
            val tenBit = format == AV_PIX_FMT_P010LE || format == AV_PIX_FMT_YUV420P10LE
            val wanted = when (config.layout) {
                "I420" -> YuvVideo.Layout.I420
                "NV12" -> YuvVideo.Layout.NV12
                "P010" -> YuvVideo.Layout.P010
                "I010" -> YuvVideo.Layout.I010
                else -> when (format) {
                    AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P -> YuvVideo.Layout.I420
                    AV_PIX_FMT_NV12 -> YuvVideo.Layout.NV12
                    // 10 Bit aus dem Software-Decoder: so lassen (der Shader liest es direkt, keine Umwandlung).
                    AV_PIX_FMT_YUV420P10LE -> YuvVideo.Layout.I010
                    else -> if (tenBit) YuvVideo.Layout.P010 else YuvVideo.Layout.NV12
                }
            }
            val native = when (wanted) {
                YuvVideo.Layout.I420 -> format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P
                YuvVideo.Layout.NV12 -> format == AV_PIX_FMT_NV12
                YuvVideo.Layout.P010 -> format == AV_PIX_FMT_P010LE
                YuvVideo.Layout.I010 -> format == AV_PIX_FMT_YUV420P10LE
            }
            var source = f
            if (!native) {
                val target = when (wanted) {
                    YuvVideo.Layout.I420 -> AV_PIX_FMT_YUV420P
                    YuvVideo.Layout.NV12 -> AV_PIX_FMT_NV12
                    YuvVideo.Layout.P010 -> AV_PIX_FMT_P010LE
                    YuvVideo.Layout.I010 -> AV_PIX_FMT_YUV420P10LE
                }
                val key = "${f.width()}x${f.height()}:$format>$target"
                if (sws == null || key != swsKey) {
                    sws?.let { sws_freeContext(it) }
                    sws = sws_getContext(f.width(), f.height(), format, f.width(), f.height(), target, SWS_BILINEAR, null, null, null as org.bytedeco.javacpp.DoublePointer?)
                    swsKey = key
                }
                av_frame_unref(converted)
                converted.format(target)
                converted.width(f.width())
                converted.height(f.height())
                if (av_frame_get_buffer(converted, 32) < 0) error("kein Speicher")
                sws_scale_frame(sws, converted, f)
                converted.pts(f.pts())
                converted.best_effort_timestamp(f.best_effort_timestamp())
                source = converted
            }
            val w = source.width()
            val h = source.height()
            val ch = (h + 1) / 2
            fun plane(i: Int): org.jetbrains.skia.Image {
                val rows = if (i == 0) h else ch
                val pitch = source.linesize(i)
                val buffer = source.data(i).capacity(pitch.toLong() * rows).asByteBuffer()
                return YuvVideo.copyPlane(buffer, pitch, YuvVideo.byteWidth(wanted, i, w), rows)
            }
            val sar = source.sample_aspect_ratio()
            val aspect = if (h > 0) w.toFloat() / h * (if (sar.num() > 0 && sar.den() > 0) sar.num().toFloat() / sar.den() else 1f) else 16f / 9f
            val pts = source.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: source.pts()
            val trc = f.color_trc()
            val hdr = when (hdrMode) {
                "PQ" -> YuvVideo.Transfer.PQ
                "HLG" -> YuvVideo.Transfer.HLG
                "OFF" -> YuvVideo.Transfer.SDR
                else -> when (trc) {
                    AVCOL_TRC_SMPTE2084 -> YuvVideo.Transfer.PQ
                    AVCOL_TRC_ARIB_STD_B67 -> YuvVideo.Transfer.HLG
                    else -> YuvVideo.Transfer.SDR
                }
            }
            transfer = hdr
            layout = wanted
            val full = f.color_range() == AVCOL_RANGE_JPEG || format == AV_PIX_FMT_YUVJ420P
            // Eine Kopie je Ebene in den Speicher des Bildes (der Puffer gehört FFmpeg und wird gleich wiederverwendet).
            val yuv = YuvFrame(plane(0), plane(1), if (wanted == YuvVideo.Layout.I420 || wanted == YuvVideo.Layout.I010) plane(2) else null, w, h, aspect, hdr, fullRange = full, layout = wanted)
            return Frame(yuv, pts)
        }

        fun release() {
            graph?.let { avfilter_graph_free(it) }
            sws?.let { sws_freeContext(it) }
            av_frame_free(frame)
            av_frame_free(swFrame)
            av_frame_free(converted)
            av_frame_free(filtered)
            av_packet_free(packet)
            avcodec_free_context(ctx)
            hwDevice?.let { av_buffer_unref(it) }
        }
    }

    // ================================================================ Ton

    private fun audioLoop(gen: Int) {
        var decoder: AudioDecoder? = null
        var line: SourceDataLine? = null
        var lineRate = 0
        var firstPtsUs = Long.MIN_VALUE
        var framesWritten = 0L
        val eq = Equalizer()
        val dynamics = Dynamics()
        fun closeLine() {
            clock.audio = null
            runCatching { line?.stop(); line?.close() }
            line = null
            lineRate = 0
        }
        try {
            while (alive(gen)) {
                val sample = take(gen, audioQueue)
                if (sample == null) {
                    checkEnded()
                    if (!alive(gen)) break
                    Thread.sleep(50)
                    continue
                }
                if (decoder == null || decoder.track != sample.track) {
                    decoder?.release()
                    decoder = runCatching { AudioDecoder(sample) }.onFailure {
                        output.onInfo("Ton nicht abspielbar (${sample.track.codec}): ${it.message}")
                    }.getOrNull()
                    audioDecoderName = decoder?.name.orEmpty()
                    if (decoder == null) {
                        synchronized(lock) { audioQueue.forEach { queuedBytes -= it.data.size }; audioQueue.clear() }
                        audioTrack = null
                        continue
                    }
                }
                val d = decoder ?: continue
                if (sample.timeUs + 50_000 < seekTargetUs) {
                    d.decode(sample) // Decoder warmhalten, Ausgabe verwerfen
                    continue
                }
                // Mit Bild: Ton erst ab dem ersten gezeigten Bild (lippensynchron, keine Uhr, die zurückspringt).
                if (firstPtsUs == Long.MIN_VALUE && videoTrack != null) {
                    val until = System.currentTimeMillis() + 4_000
                    while (!clock.started && alive(gen) && System.currentTimeMillis() < until) Thread.sleep(10)
                    if (clock.started && sample.timeUs + 30_000 < clock.firstUs) continue
                }
                for (pcm in d.decode(sample)) {
                    if (!alive(gen)) break
                    if (lineRate != pcm.rate) {
                        closeLine()
                        line = openLine(pcm.rate)
                        lineRate = pcm.rate
                        framesWritten = 0
                        firstPtsUs = Long.MIN_VALUE
                    }
                    val l = line
                    if (firstPtsUs == Long.MIN_VALUE) {
                        firstPtsUs = pcm.timeUs
                        if (l != null) clock.audio = AudioClock(l, pcm.rate, firstPtsUs) { audioDelayMs * 1000 }
                        clock.startFromAudio(firstPtsUs)
                    }
                    output.onPcm(pcm.data, pcm.frames, pcm.rate, pcm.timeUs)
                    if (l == null) {
                        // Kein Tonausgang (kein Lautsprecher, Gerät belegt): Bild läuft nach der Wanduhr weiter, der
                        // Ton wird im Takt verworfen (die Auswertung für die Untertitel bekommt ihn trotzdem).
                        while (alive(gen) && (paused || pcm.timeUs - clock.nowUs() > 200_000)) Thread.sleep(10)
                        continue
                    }
                    eq.apply(pcm.data, pcm.frames, pcm.rate, equalizer)
                    if (compressor || normalize) dynamics.apply(pcm.data, pcm.frames, compressor, normalize)
                    applyGain(pcm.data, if (muted) 0f else volume)
                    while (paused && alive(gen)) {
                        if (l.isRunning) l.stop()
                        Thread.sleep(20)
                    }
                    if (!l.isRunning) l.start()
                    var off = 0
                    while (off < pcm.data.size && alive(gen)) off += l.write(pcm.data, off, pcm.data.size - off)
                    framesWritten += pcm.frames
                }
            }
        } finally {
            closeLine()
            decoder?.release()
        }
    }

    private fun openLine(rate: Int): SourceDataLine? = runCatching {
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        AudioSystem.getSourceDataLine(format).apply {
            open(format, rate * 4 / 5) // ~200 ms
            start()
        }
    }.onFailure { output.onInfo("Kein Tonausgang: ${it.message}") }.getOrNull()

    private fun applyGain(data: ByteArray, gain: Float) {
        if (gain == 1f) return
        var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8))
            val s = (v * gain).toInt().coerceIn(-32768, 32767)
            data[i] = s.toByte()
            data[i + 1] = (s shr 8).toByte()
            i += 2
        }
    }

    private class Pcm(val data: ByteArray, val frames: Int, val rate: Int, val timeUs: Long)

    /** FFmpeg-Tondecoder → Stereo 16 Bit (Raumklang wird heruntergemischt, Dialoge bleiben verständlich). */
    private inner class AudioDecoder(first: Sample) {
        val track = first.track
        private val ctx: AVCodecContext
        private val packet = av_packet_alloc()
        private val frame = av_frame_alloc()
        private val out = av_frame_alloc()
        private var swr: SwrContext? = null
        private var swrKey = ""
        val name: String

        init {
            // MPEG-Audio: Layer aus dem ersten Rahmen (Layer II ist bei Radio und SD-Sendern üblich).
            val id = if (track.codec == Codec.MPEG_AUDIO && first.data.size > 1 && (first.data[1].toInt() shr 1 and 3) == 2) AV_CODEC_ID_MP2 else audioCodecId(track.codec)
            val codec = avcodec_find_decoder(id) ?: error("kein Decoder")
            ctx = avcodec_alloc_context3(codec)
            ctx.pkt_timebase(av_make_q(1, 1_000_000))
            val info = first.audio
            val rate = info?.sampleRate?.takeIf { it > 0 } ?: track.sampleRate
            val channels = info?.channels?.takeIf { it > 0 } ?: track.channels
            if (rate > 0) ctx.sample_rate(rate)
            if (channels > 0) av_channel_layout_default(ctx.ch_layout(), channels)
            val extra = if (track.codec == Codec.AAC_ADTS || track.codec == Codec.AAC_LATM) info?.config else track.csd.firstOrNull() ?: info?.config
            if (extra != null) {
                val p = BytePointer(av_malloc((extra.size + AV_INPUT_BUFFER_PADDING_SIZE).toLong()))
                p.position(0).put(*extra)
                for (i in extra.size until extra.size + AV_INPUT_BUFFER_PADDING_SIZE) p.put(i.toLong(), 0)
                ctx.extradata(p)
                ctx.extradata_size(extra.size)
            }
            val r = avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (r < 0) error(errorText(r))
            name = "FFmpeg ${codec.name().string}"
        }

        fun decode(sample: Sample): List<Pcm> {
            val result = ArrayList<Pcm>()
            if (av_new_packet(packet, sample.data.size) < 0) return result
            packet.data().put(*sample.data)
            packet.pts(sample.timeUs)
            avcodec_send_packet(ctx, packet)
            av_packet_unref(packet)
            var t = sample.timeUs
            while (avcodec_receive_frame(ctx, frame) == 0) {
                val pts = frame.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: t
                val rate = frame.sample_rate()
                val key = "${frame.format()}:$rate:${frame.ch_layout().nb_channels()}"
                if (swr == null || key != swrKey) {
                    swr?.let { swr_free(it) }
                    swr = swr_alloc()
                    swrKey = key
                }
                av_frame_unref(out)
                av_channel_layout_default(out.ch_layout(), 2)
                out.format(AV_SAMPLE_FMT_S16)
                out.sample_rate(rate)
                if (swr_convert_frame(swr, out, frame) >= 0 && out.nb_samples() > 0) {
                    val n = out.nb_samples()
                    val bytes = ByteArray(n * 4)
                    out.data(0).get(bytes)
                    result += Pcm(bytes, n, rate, pts)
                    t = pts + n * 1_000_000L / rate.coerceAtLeast(1)
                }
                av_frame_unref(frame)
            }
            return result
        }

        fun release() {
            swr?.let { swr_free(it) }
            av_frame_free(frame)
            av_frame_free(out)
            av_packet_free(packet)
            avcodec_free_context(ctx)
        }
    }

    // ================================================================ Uhr

    private class AudioClock(private val line: SourceDataLine, private val rate: Int, private val firstPtsUs: Long, private val delayUs: () -> Long) {
        fun nowUs(): Long = firstPtsUs + line.longFramePosition * 1_000_000L / rate - delayUs()
    }

    private class Clock {
        @Volatile var audio: AudioClock? = null
        @Volatile var started = false
        @Volatile var firstUs = Long.MIN_VALUE
        private var anchorUs = 0L
        private var anchorNs = 0L
        private var pausedAt = 0L

        fun reset() {
            started = false
            firstUs = Long.MIN_VALUE
            audio = null
        }

        @Synchronized fun startFromVideo(ptsUs: Long, hasAudio: Boolean) {
            if (started) return
            anchorUs = ptsUs - if (hasAudio) 150_000 else 0
            anchorNs = System.nanoTime()
            if (pausedAt > 0) pausedAt = anchorNs
            firstUs = ptsUs
            started = true
        }

        @Synchronized fun startFromAudio(ptsUs: Long) {
            if (started) return
            anchorUs = ptsUs
            anchorNs = System.nanoTime()
            if (pausedAt > 0) pausedAt = anchorNs
            firstUs = ptsUs
            started = true
        }

        @Synchronized fun setPaused(paused: Boolean) {
            val now = System.nanoTime()
            if (paused) {
                if (pausedAt == 0L) pausedAt = now
            } else if (pausedAt > 0) {
                anchorNs += now - pausedAt
                pausedAt = 0
            }
        }

        fun nowUs(): Long {
            audio?.let { return runCatching { it.nowUs() }.getOrNull() ?: wall() }
            return wall()
        }

        private fun wall(): Long {
            val now = if (pausedAt > 0) pausedAt else System.nanoTime()
            return anchorUs + (now - anchorNs) / 1000
        }
    }

    companion object {
        private const val LATE_US = 80_000L
        private const val JUMP_US = 3_000_000L
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        fun videoCodecId(codec: Codec): Int = when (codec) {
            Codec.H264 -> AV_CODEC_ID_H264
            Codec.HEVC -> AV_CODEC_ID_HEVC
            Codec.MPEG2_VIDEO -> AV_CODEC_ID_MPEG2VIDEO
            Codec.MPEG4_VIDEO -> AV_CODEC_ID_MPEG4
            Codec.VP8 -> AV_CODEC_ID_VP8
            Codec.VP9 -> AV_CODEC_ID_VP9
            Codec.AV1 -> AV_CODEC_ID_AV1
            else -> AV_CODEC_ID_NONE
        }

        fun audioCodecId(codec: Codec): Int = when (codec) {
            Codec.AAC, Codec.AAC_ADTS, Codec.AAC_LATM -> AV_CODEC_ID_AAC
            Codec.AC3 -> AV_CODEC_ID_AC3
            Codec.EAC3 -> AV_CODEC_ID_EAC3
            Codec.MPEG_AUDIO -> AV_CODEC_ID_MP3
            Codec.OPUS -> AV_CODEC_ID_OPUS
            Codec.VORBIS -> AV_CODEC_ID_VORBIS
            Codec.FLAC -> AV_CODEC_ID_FLAC
            Codec.DTS -> AV_CODEC_ID_DTS
            Codec.TRUEHD -> AV_CODEC_ID_TRUEHD
            Codec.PCM -> AV_CODEC_ID_PCM_S16LE
            else -> AV_CODEC_ID_NONE
        }

        /** Decoder-Daten: aus dem Container (Annex B) bzw. für H.264/HEVC im Strom selbst (nichts nötig). */
        private fun extradata(track: Track, keyframe: ByteArray): ByteArray? = when {
            track.csd.isNotEmpty() -> track.csd.fold(ByteArray(0)) { a, b -> a + b }
            else -> null
        }

        fun errorText(code: Int): String {
            val buf = ByteArray(128)
            av_strerror(code, buf, buf.size.toLong())
            return String(buf).trimEnd('\u0000')
        }

        fun sameLanguage(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            fun norm(s: String) = when (val l = s.lowercase().take(3)) { "deu", "ger" -> "de"; "eng" -> "en"; "tur" -> "tr"; "fra", "fre" -> "fr"; else -> l.take(2) }
            return norm(a) == norm(b)
        }
    }
}

/** Untertitel aller Spuren (Text und Bild), nach Zeit; die Oberfläche fragt die gerade fällige ab. */
class DesktopSubtitles {
    class Entry(val startUs: Long, var endUs: Long, val text: String?, val images: List<SubtitleImage>)

    private val tracks = HashMap<Int, java.util.TreeMap<Long, Entry>>()
    private val pgs = HashMap<Int, de.ayutv.player.dna.engine.PgsDecoder>()
    private val dvb = HashMap<Int, de.ayutv.player.dna.engine.DvbSubtitleDecoder>()

    @Synchronized fun clear() {
        tracks.clear()
        pgs.clear()
        dvb.clear()
    }

    @Synchronized fun add(sample: Sample) {
        val track = sample.track
        val start = sample.timeUs
        val end = if (sample.durationUs > 0) start + sample.durationUs else Long.MAX_VALUE
        val entry: Entry = when (track.codec) {
            Codec.SRT, Codec.WEBVTT, Codec.TX3G -> Entry(start, end, clean(String(sample.data, Charsets.UTF_8)), emptyList())
            Codec.ASS -> Entry(start, end, clean(ass(String(sample.data, Charsets.UTF_8))), emptyList())
            Codec.PGS -> pgs.getOrPut(track.id) { de.ayutv.player.dna.engine.PgsDecoder() }.decodeImages(sample.data)?.let { Entry(start, end, null, it) } ?: return
            Codec.DVBSUB -> {
                val pages = track.extra ?: ByteArray(4)
                dvb.getOrPut(track.id) {
                    de.ayutv.player.dna.engine.DvbSubtitleDecoder(
                        ((pages[0].toInt() and 0xFF) shl 8) or (pages[1].toInt() and 0xFF),
                        ((pages[2].toInt() and 0xFF) shl 8) or (pages[3].toInt() and 0xFF),
                    )
                }.decodeImages(sample.data)?.let { Entry(start, end, null, it) } ?: return
            }
            else -> return
        }
        val map = tracks.getOrPut(track.id) { java.util.TreeMap() }
        map.floorEntry(start - 1)?.value?.let { if (it.endUs == Long.MAX_VALUE) it.endUs = start }
        if (entry.text.isNullOrBlank() && entry.images.isEmpty()) {
            map.floorEntry(start)?.value?.let { if (it.endUs > start) it.endUs = start }
            return
        }
        map[start] = entry
        while (map.size > 4000) map.pollFirstEntry()
    }

    /** Einblendung der Spur [trackId] bei [positionUs]. */
    @Synchronized fun at(trackId: Int, positionUs: Long): Entry? {
        val map = tracks[trackId] ?: return null
        val e = map.floorEntry(positionUs)?.value ?: return null
        return e.takeIf { positionUs < it.endUs && positionUs - it.startUs < 30_000_000 }
    }

    companion object {
        fun ass(line: String): String {
            var commas = 0
            var i = 0
            val fields = if (line.startsWith("Dialogue:")) 9 else 8
            while (i < line.length && commas < fields) {
                if (line[i] == ',') commas++
                i++
            }
            return if (commas == fields) line.substring(i) else line
        }

        fun clean(text: String): String = text
            .replace(Regex("""\{[^}]*\}"""), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }
}

/** Klangregelung: 10 Glockenfilter (31 Hz … 16 kHz) auf Stereo 16 Bit. */
private class Equalizer {
    private val freqs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private var coeffs: Array<FloatArray>? = null
    private var key = ""
    private val state = Array(10) { FloatArray(8) }

    fun apply(data: ByteArray, frames: Int, rate: Int, gains: FloatArray?) {
        if (gains == null || gains.all { it == 0f }) return
        val k = "$rate:${gains.joinToString()}"
        if (k != key) {
            key = k
            coeffs = Array(10) { b -> peaking(freqs[b].coerceAtMost(rate / 2.2f), gains.getOrElse(b) { 0f }, 1.1f, rate.toFloat()) }
        }
        val c = coeffs ?: return
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val o = (i * 2 + ch) * 2
                var x = ((data[o].toInt() and 0xFF) or (data[o + 1].toInt() shl 8)) / 32768f
                for (b in 0 until 10) {
                    val q = c[b]
                    if (q[5] == 0f) continue
                    val s = state[b]
                    val base = ch * 4
                    val y = q[0] * x + q[1] * s[base] + q[2] * s[base + 1] - q[3] * s[base + 2] - q[4] * s[base + 3]
                    s[base + 1] = s[base]; s[base] = x
                    s[base + 3] = s[base + 2]; s[base + 2] = y
                    x = y
                }
                val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
                data[o] = v.toByte()
                data[o + 1] = (v shr 8).toByte()
            }
        }
    }

    /** RBJ-Glockenfilter; [5] = 1, wenn aktiv. */
    private fun peaking(f: Float, db: Float, q: Float, rate: Float): FloatArray {
        if (db == 0f) return FloatArray(6)
        val a = Math.pow(10.0, db / 40.0).toFloat()
        val w = (2 * Math.PI * f / rate).toFloat()
        val alpha = kotlin.math.sin(w) / (2 * q)
        val cos = kotlin.math.cos(w)
        val a0 = 1 + alpha / a
        return floatArrayOf((1 + alpha * a) / a0, (-2 * cos) / a0, (1 - alpha * a) / a0, (-2 * cos) / a0, (1 - alpha / a) / a0, 1f)
    }
}

/** Nachtmodus (laute Stellen leiser, leise lauter) und Lautstärke angleichen. */
private class Dynamics {
    private var envelope = 0f
    private var level = 0.1f

    fun apply(data: ByteArray, frames: Int, compress: Boolean, normalize: Boolean) {
        for (i in 0 until frames * 2) {
            val o = i * 2
            var x = ((data[o].toInt() and 0xFF) or (data[o + 1].toInt() shl 8)) / 32768f
            val a = kotlin.math.abs(x)
            envelope = if (a > envelope) envelope + (a - envelope) * 0.01f else envelope + (a - envelope) * 0.0005f
            if (compress && envelope > 0.1f) x *= (0.1f / envelope).toDouble().pow(0.6).toFloat()
            if (normalize) {
                level += (envelope - level) * 0.00002f
                x *= (0.15f / level.coerceAtLeast(0.02f)).coerceIn(0.5f, 3f)
            }
            val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
            data[o] = v.toByte()
            data[o + 1] = (v shr 8).toByte()
        }
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}

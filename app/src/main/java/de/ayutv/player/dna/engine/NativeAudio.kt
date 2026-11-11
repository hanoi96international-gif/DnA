package de.ayutv.player.dna.engine

/**
 * Eigener Ton-Decoder der DNA Engine (app/src/main/cpp/remux/dnaaudio.c, FFmpeg in der App-eigenen Bibliothek):
 * DTS, Dolby TrueHD und – wo das Gerät keinen Decoder hat – AC-3, E-AC-3, MP2, MP3, FLAC, Opus, AAC. Ausgabe PCM 16 Bit.
 */
internal object NativeAudio {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("dnaremux")
            nativeVersion() >= 1
        }.getOrDefault(false)
    }

    /** FFmpeg-Decoder für eine Spur, null = nicht möglich. */
    fun decoderFor(codec: Codec, mpegLayer: Int = 3): String? = when (codec) {
        Codec.DTS -> "dca"
        Codec.TRUEHD -> "truehd"
        Codec.AC3 -> "ac3"
        Codec.EAC3 -> "eac3"
        Codec.MPEG_AUDIO -> if (mpegLayer == 3) "mp3" else "mp2"
        Codec.FLAC -> "flac"
        Codec.OPUS -> "opus"
        Codec.AAC, Codec.AAC_ADTS, Codec.AAC_LATM -> "aac"
        else -> null
    }

    fun supports(codec: Codec): Boolean = available && decoderFor(codec) != null

    /** Der Decoder trennt Rahmen selbst (Parser): mehrere Pakete dürfen auf einmal kommen (wie dnaaudio.c). */
    fun parsesFrames(codec: Codec): Boolean = when (codec) {
        Codec.DTS, Codec.TRUEHD, Codec.AC3, Codec.EAC3, Codec.MPEG_AUDIO -> true
        // AAC kommt ohne ADTS-Kopf (roh), FLAC/Opus je Paket ein Rahmen: kein Parser.
        else -> false
    }

    class Decoder internal constructor(private var handle: Long) {
        /** PCM 16 Bit verschachtelt, null = (noch) nichts. */
        fun decode(data: ByteArray): ByteArray? = if (handle == 0L) null else nativeDecode(handle, data, data.size)
        val sampleRate: Int get() = if (handle == 0L) 0 else nativeRate(handle)
        val channels: Int get() = if (handle == 0L) 0 else nativeChannels(handle)
        fun release() {
            if (handle != 0L) nativeClose(handle)
            handle = 0
        }
    }

    fun open(codec: Codec, sampleRate: Int, channels: Int, extra: ByteArray?, mpegLayer: Int = 3): Decoder? {
        if (!available) return null
        val name = decoderFor(codec, mpegLayer) ?: return null
        val handle = runCatching { nativeOpen(name, sampleRate, channels, extra) }.getOrDefault(0L)
        return if (handle == 0L) null else Decoder(handle)
    }

    @JvmStatic private external fun nativeVersion(): Int
    @JvmStatic private external fun nativeOpen(codec: String, rate: Int, channels: Int, extra: ByteArray?): Long
    @JvmStatic private external fun nativeDecode(handle: Long, data: ByteArray, len: Int): ByteArray?
    @JvmStatic private external fun nativeRate(handle: Long): Int
    @JvmStatic private external fun nativeChannels(handle: Long): Int
    @JvmStatic private external fun nativeClose(handle: Long)
}

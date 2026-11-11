package de.ayutv.player.dna.engine

/** Art einer Spur. */
enum class Kind { VIDEO, AUDIO, TEXT }

/** Formate, die die DNA Engine aus Containern liest (MIME-Typ für MediaCodec bzw. die eigene Untertitel-Auswertung). */
enum class Codec(val kind: Kind, val mime: String) {
    H264(Kind.VIDEO, "video/avc"),
    HEVC(Kind.VIDEO, "video/hevc"),
    MPEG2_VIDEO(Kind.VIDEO, "video/mpeg2"),
    MPEG4_VIDEO(Kind.VIDEO, "video/mp4v-es"),
    VP8(Kind.VIDEO, "video/x-vnd.on2.vp8"),
    VP9(Kind.VIDEO, "video/x-vnd.on2.vp9"),
    AV1(Kind.VIDEO, "video/av01"),
    AAC_ADTS(Kind.AUDIO, "audio/mp4a-latm"),
    AAC_LATM(Kind.AUDIO, "audio/mp4a-latm"),
    /** Rohes AAC aus MP4/MKV (AudioSpecificConfig in [Track.csd]). */
    AAC(Kind.AUDIO, "audio/mp4a-latm"),
    AC3(Kind.AUDIO, "audio/ac3"),
    EAC3(Kind.AUDIO, "audio/eac3"),
    MPEG_AUDIO(Kind.AUDIO, "audio/mpeg"),
    OPUS(Kind.AUDIO, "audio/opus"),
    VORBIS(Kind.AUDIO, "audio/vorbis"),
    FLAC(Kind.AUDIO, "audio/flac"),
    DTS(Kind.AUDIO, "audio/vnd.dts"),
    TRUEHD(Kind.AUDIO, "audio/true-hd"),
    /** PCM 16 Bit little-endian. */
    PCM(Kind.AUDIO, "audio/raw"),
    SRT(Kind.TEXT, "application/x-subrip"),
    ASS(Kind.TEXT, "text/x-ssa"),
    WEBVTT(Kind.TEXT, "text/vtt"),
    TX3G(Kind.TEXT, "application/x-quicktime-tx3g"),
    PGS(Kind.TEXT, "application/pgs"),
    DVBSUB(Kind.TEXT, "application/dvbsubs"),
    VOBSUB(Kind.TEXT, "application/vobsub"),
    ;

    /** Bild-Untertitel (Texterkennung nötig). */
    val bitmap: Boolean get() = this == PGS || this == DVBSUB || this == VOBSUB
}

/**
 * Eine Spur. [id] ist im TS die PID, in MKV/MP4 die Spurnummer. Aus Containern kommen Decoder-Daten ([csd], Annex B bzw.
 * so, wie MediaCodec sie erwartet), Bildgröße bzw. Tonformat; im TS stehen sie im Strom selbst.
 */
class Track(
    val id: Int,
    val codec: Codec,
    val language: String?,
    val label: String? = null,
    val csd: List<ByteArray> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    /** H.264/HEVC aus MP4/MKV: Länge der Längenfelder vor jedem NAL (meist 4); 0 = schon Annex B. */
    val nalLengthSize: Int = 0,
    val isDefault: Boolean = false,
    val forced: Boolean = false,
    /** Container-eigene Zusatzdaten (MKV: Header-Stripping-Präfix; ASS: Kopf mit Formatzeile; DVB: Seiten-IDs). */
    val extra: ByteArray? = null,
    /** HDR (aus dem Container), für die Anzeige. */
    val hdr: Boolean = false,
) {
    override fun equals(other: Any?) = other is Track && id == other.id && codec == other.codec && language == other.language
    override fun hashCode() = (id * 31 + codec.hashCode()) * 31 + (language?.hashCode() ?: 0)
    override fun toString() = "Track($id, $codec, $language)"
}

/** Tonformat eines Rahmens; [config] = AudioSpecificConfig bei AAC (csd-0). */
class AudioInfo(val sampleRate: Int, val channels: Int, val samplesPerFrame: Int, val config: ByteArray? = null) {
    override fun equals(other: Any?) = other is AudioInfo && sampleRate == other.sampleRate && channels == other.channels &&
        samplesPerFrame == other.samplesPerFrame && config.contentEquals(other.config)
    override fun hashCode() = (sampleRate * 31 + channels) * 31 + samplesPerFrame
}

/**
 * Zugriffseinheit. Bild: Annex-B-Daten mit Startcodes, [keyframe] bei IDR/IRAP. Ton: genau ein Rahmen ohne
 * ADTS/LATM-Kopf (rohes AAC) bzw. vollständiger AC-3/E-AC-3/MPEG-Rahmen; [audio] beschreibt das Format (aus Containern
 * kann es fehlen – dann gilt das der Spur). Untertitel: Text (UTF-8) bzw. das Bild-Untertitel-Paket; [durationUs] wo bekannt.
 */
class Sample(
    val track: Track,
    val data: ByteArray,
    val timeUs: Long,
    val keyframe: Boolean,
    val audio: AudioInfo? = null,
    val durationUs: Long = 0,
)

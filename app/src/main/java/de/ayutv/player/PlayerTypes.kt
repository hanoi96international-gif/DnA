package de.ayutv.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import de.ayutv.data.SubtitleBackground
import de.ayutv.data.SubtitleColor
import de.ayutv.data.SubtitlePosition
import de.ayutv.data.db.HistoryKind

/** Film oder Folge, die der Player abspielt. */
data class VodRequest(
    val key: String,
    val kind: HistoryKind,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
)

/** Wie Untertitel aussehen (aus den Einstellungen). */
data class SubtitleLook(
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.OUTLINE,
    val position: SubtitlePosition = SubtitlePosition.NORMAL,
)

data class StreamStats(
    /** Aktuelle Bildwiederholrate des Fernsehers. */
    val displayHz: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String = "",
    val videoBitrate: Int = 0,
    val audioCodec: String = "",
    val audioChannels: Int = 0,
    val decoder: String = "",
    val droppedFrames: Int = 0,
    val bandwidth: Long = 0,
    val startupMs: Long = 0,
    val hdr: String = "",
    val frameRateMeasured: Boolean = false,
    val audioDecoder: String = "",
) {
    val resolutionLabel: String
        get() = when {
            height >= 2000 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            height > 0 -> "SD"
            else -> ""
        }
}

internal fun PlaybackException.isFormatError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

internal fun androidx.media3.common.Format.isForcedSubtitle(): Boolean =
    selectionFlags and C.SELECTION_FLAG_FORCED != 0 || label?.contains("forced", ignoreCase = true) == true ||
        label?.contains("erzwungen", ignoreCase = true) == true

internal fun androidx.media3.common.Format.isSdhSubtitle(): Boolean =
    roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || label?.contains("SDH", ignoreCase = true) == true

/** Bild-Untertitel (PGS, DVB, VobSub) enthalten keinen Text und lassen sich nicht übersetzen. */
internal fun androidx.media3.common.Format.isBitmapSubtitle(): Boolean {
    val bitmapTypes = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)
    // Seit Media3 1.4 werden Untertitel beim Einlesen umgewandelt; der ursprüngliche Typ steht dann in codecs.
    return sampleMimeType in bitmapTypes || codecs in bitmapTypes
}

internal fun PlaybackException.isUnsupportedFormat(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

internal fun PlaybackException.isContainerError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
)

internal fun PlaybackException.isAudioOutputError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
)

internal fun PlaybackException.isVideoDecoderError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
)

internal fun PlaybackException.isDecoderOrAudioError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
)


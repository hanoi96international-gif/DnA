@file:OptIn(UnstableApi::class)

package de.ayutv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hört beim dekodierten Ton mit und reicht ihn unverändert weiter: Für [SpeechActivity] zählt nur die
 * Sprache, bei 5.1 also der Center-Kanal (dort liegen die Dialoge), sonst die Mitte aus links und rechts.
 * Media3 meldet vor jedem neuen Abschnitt (Start, Spulen) die Filmposition; so passt jede Probe zur Filmzeit.
 * Ton, der an Soundbar/Receiver durchgereicht wird (Dolby, DTS), kommt hier nicht vorbei.
 */
class SpeechTapProcessor(private val activity: SpeechActivity) : BaseAudioProcessor() {
    private var mono = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        activity.restart(streamMetadata.positionOffsetUs / 1000, inputAudioFormat.sampleRate)
    }

    override fun onReset() {
        activity.restart(0, 0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val frames = remaining / format.bytesPerFrame
        if (mono.size < frames) mono = FloatArray(frames)
        val data = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val base = data.position()
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        fun sample(frame: Int, channel: Int): Float {
            val at = base + (frame * channels + channel) * bytes
            return if (float) data.getFloat(at) else data.getShort(at) / 32768f
        }
        // Kanalfolge bei Android: links, rechts, Center, Bass, …
        val center = channels == 3 || channels >= 6
        for (i in 0 until frames) {
            mono[i] = when {
                center -> sample(i, 2)
                channels >= 2 -> (sample(i, 0) + sample(i, 1)) * 0.5f
                else -> sample(i, 0)
            }
        }
        activity.add(mono, frames)
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }
}

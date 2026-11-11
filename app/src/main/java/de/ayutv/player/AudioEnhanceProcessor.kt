@file:OptIn(UnstableApi::class)

package de.ayutv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import de.ayutv.data.AudioMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wendet [AudioEnhance] auf den dekodierten Ton an. Bleibt immer eingehängt, damit sich der Modus während
 * der Wiedergabe umschalten lässt; bei „Normal“ wird nur durchgereicht. Ton, der an Soundbar/Receiver
 * durchgereicht wird (Dolby, DTS), kommt hier nicht vorbei.
 */
class AudioEnhanceProcessor(private val enhance: AudioEnhance) : BaseAudioProcessor() {
    private var samples = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = enhance.reset()

    override fun onReset() = enhance.reset()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        // Nur durchreichen, wenn wirklich nichts zu tun ist („Lautstärke angleichen“ wirkt auch bei „Normal“).
        if (enhance.mode == AudioMode.NORMAL && !enhance.leveling) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val format = inputAudioFormat
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val count = remaining / if (float) 4 else 2
        if (samples.size < count) samples = FloatArray(count)
        val data = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until count) samples[i] = data.getFloat()
        else for (i in 0 until count) samples[i] = data.getShort() / 32768f
        enhance.process(samples, count / format.channelCount, format.channelCount, format.sampleRate)
        out.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until count) out.putFloat(samples[i])
        else for (i in 0 until count) out.putShort((samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        out.flip()
    }
}

@file:OptIn(UnstableApi::class)

package de.ayutv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import de.ayutv.player.dna.engine.DnaEngineCore
import java.nio.ByteBuffer

/**
 * Hängt die Ton-Bearbeitung der App ([SpeechTapProcessor]: Sprache für die Untertitel-Ausrichtung,
 * [AudioEnhanceProcessor]: Nachtmodus, Lautstärke-Ausgleich) in den PCM-Weg der DNA Engine.
 */
class PcmAdapter(private val processor: AudioProcessor) : DnaEngineCore.PcmProcessor {
    @Volatile private var active = false

    override fun configure(sampleRate: Int, channels: Int) {
        active = runCatching {
            processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.isActive
        }.getOrDefault(false)
    }

    override fun flush(positionUs: Long) {
        if (active) runCatching { processor.flush(AudioProcessor.StreamMetadata(positionUs)) }
    }

    override fun process(input: ByteBuffer): ByteBuffer {
        if (!active || !input.hasRemaining()) return input
        processor.queueInput(input)
        val out = processor.getOutput()
        return if (out.hasRemaining()) out else input
    }

    override fun reset() {
        active = false
        runCatching { processor.reset() }
    }
}

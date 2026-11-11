package de.ayutv.desktop.player

import de.ayutv.player.SpeechActivity

/**
 * Ton nebenbei auswerten: Die DNA Engine reicht jeden ausgegebenen Ton-Block (16 Bit, Stereo) hierher, daraus wird
 * [speech] gefüttert (Sprechpausen für das Ausrichten der Untertitel). Ausgegeben wird der Ton von der Engine selbst.
 */
class AudioTap(val speech: SpeechActivity, private val positionMs: () -> Long, private val rate: () -> Int = { RATE }) {
    @Volatile private var restartPending = true
    private var mono = FloatArray(0)

    /** Nach Start oder Sprung: Position neu nehmen. */
    fun restart() {
        restartPending = true
    }

    /** [sampleCount] Stereo-Samples (16 Bit, little endian) auswerten. */
    fun consume(data: ByteArray, sampleCount: Int) {
        if (restartPending) {
            restartPending = false
            speech.restart(positionMs().coerceAtLeast(0), rate())
        }
        if (mono.size < sampleCount) mono = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val o = i * BYTES_PER_FRAME
            val left = (data[o].toInt() and 0xFF) or (data[o + 1].toInt() shl 8)
            val right = (data[o + 2].toInt() and 0xFF) or (data[o + 3].toInt() shl 8)
            mono[i] = (left + right) / 65_536f
        }
        speech.add(mono, sampleCount)
    }

    companion object {
        const val RATE = 48_000
        private const val BYTES_PER_FRAME = 4
    }
}

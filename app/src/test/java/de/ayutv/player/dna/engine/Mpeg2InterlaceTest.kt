package de.ayutv.player.dna.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SD-Sender in MPEG-2 (576i): Halbbilder aus der Sequenz-Erweiterung erkennen, damit das Deinterlacing greift. */
class Mpeg2InterlaceTest {
    /** Sequenzkopf 720×576, danach optional Sequenz-Erweiterung, dann Bild-Startcode. */
    private fun stream(progressive: Boolean?): ByteArray {
        val header = byteArrayOf(0, 0, 1, 0xB3.toByte(), 0x2D, 0x02, 0x40, 0x33, 0, 0, 0, 0)
        val ext = when (progressive) {
            null -> byteArrayOf()
            // Kennung 1, Main@Main (0x48), progressive_sequence im 5. Bit des zweiten Bytes, Chroma 4:2:0.
            else -> byteArrayOf(0, 0, 1, 0xB5.toByte(), 0x14, (0x82 or (if (progressive) 0x08 else 0)).toByte(), 0, 0x01, 0, 0)
        }
        val picture = byteArrayOf(0, 0, 1, 0, 0, 0x0F, 0xFF.toByte(), 0xF8.toByte())
        return header + ext + picture
    }

    @Test fun interlacedSequence() {
        assertTrue(VideoConfig.mpeg2Interlaced(stream(progressive = false)))
        val config = VideoConfig.from(Codec.MPEG2_VIDEO, stream(progressive = false))!!
        assertEquals(720, config.width)
        assertEquals(576, config.height)
        assertTrue(config.interlaced)
    }

    @Test fun progressiveSequence() {
        assertFalse(VideoConfig.mpeg2Interlaced(stream(progressive = true)))
        assertFalse(VideoConfig.from(Codec.MPEG2_VIDEO, stream(progressive = true))!!.interlaced)
    }

    @Test fun mpeg1WithoutExtension() {
        assertFalse(VideoConfig.mpeg2Interlaced(stream(progressive = null)))
    }
}

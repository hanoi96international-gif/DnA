package de.ayutv.player.dna.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender ohne IDR-Bilder (Sender, Sender & Co. in HD): Ein I-Bild muss als Einstieg zählen, sonst startet der
 * Bild-Decoder nie (Ton läuft, Bild steht). Parametersätze aus früheren Bildern werden gemerkt.
 */
class H264KeyframeTest {
    private val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0xD9.toByte(), 0x40, 0x78)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte(), 0x22, 0xC0.toByte())
    /** Nicht-IDR-Scheibe: first_mb_in_slice = 0 (Bit 1), slice_type 7 = I (0001000). */
    private val iSlice = byteArrayOf(0, 0, 0, 1, 0x21, 0x88.toByte(), 0x84.toByte(), 0x00, 0x10)
    /** Nicht-IDR-Scheibe: first_mb_in_slice = 0, slice_type 5 = P (00110). */
    private val pSlice = byteArrayOf(0, 0, 0, 1, 0x21, 0x9A.toByte(), 0x24, 0x00, 0x10)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00)

    @Test
    fun nonIdrIFrameIsKeyframe() {
        assertTrue(TsDemuxer.isKeyframe(Codec.H264, aud + sps + pps + iSlice))
        assertTrue(TsDemuxer.isKeyframe(Codec.H264, aud + iSlice))
        assertTrue(TsDemuxer.isKeyframe(Codec.H264, aud + sps + pps + idr))
    }

    @Test
    fun pFrameIsNoKeyframe() {
        assertFalse(TsDemuxer.isKeyframe(Codec.H264, aud + pSlice))
        assertFalse(TsDemuxer.isKeyframe(Codec.H264, aud + sps + pps + pSlice))
    }

    @Test
    fun parameterSetsBeforeFirstPicture() {
        val found = VideoConfig.parameterSets(Codec.H264, aud + sps + pps + pSlice)
        assertNotNull(found)
        assertArrayEquals(sps + pps, found)
        assertNull(VideoConfig.parameterSets(Codec.H264, aud + pSlice))
        // Ohne eigene Parametersätze lässt sich das Schlüsselbild mit gemerkten einrichten.
        val track = Track(0x100, Codec.H264, null)
        assertNull(VideoConfig.forTrack(track, aud + iSlice))
        val cfg = VideoConfig.forTrack(track, found!! + aud + iSlice)
        assertNotNull(cfg)
        assertEquals("video/avc", cfg!!.mime)
    }
}

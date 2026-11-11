package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HEVC-Kopf für den Fehlerbericht: Werte mit ffmpeg (trace_headers) gegengeprüft. */
class HevcSpsTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/hevc/$name")!!.readBytes()

    @Test
    fun level51TenBitWithReordering() {
        val info = HevcSps.find(resource("sps_l51_10bit.hevc"))
        assertNotNull(info)
        info!!
        assertEquals(153, info.levelIdc)
        assertEquals(640, info.width)
        assertEquals(360, info.height)
        assertEquals(10, info.bitDepth)
        assertEquals(5, info.decPicBuffering)
        assertEquals(2, info.numReorder)
        assertEquals(5, info.latencyIncreasePlus1)
        assertTrue(info.describe(), info.describe().startsWith("Level 5.1, 640×360, 10 Bit"))
    }

    @Test
    fun level2EightBit() {
        val info = HevcSps.find(resource("sps_l2_8bit.hevc"))!!
        assertEquals(60, info.levelIdc)
        assertEquals(8, info.bitDepth)
        assertEquals(3, info.decPicBuffering)
        assertEquals(0, info.numReorder)
    }

    @Test
    fun dpbLimitsFor4k() {
        // 3840×2160 bei Level 5.1: höchstens 6 Bilder; Full HD bei 5.1: 16.
        assertEquals(6, HevcSps.maxDpbSize(153, 3840L * 2160))
        assertEquals(16, HevcSps.maxDpbSize(153, 1920L * 1080))
        val tooMany = HevcSps.Info(153, 3840, 2160, 10, 1, 16, 15, 0)
        assertTrue(tooMany.describe(), tooMany.describe().endsWith("mehr als erlaubt"))
    }

    @Test
    fun noSps() {
        assertNull(HevcSps.find(byteArrayOf(0, 0, 1, 0x40, 1, 2, 3)))
        assertNull(HevcSps.find(ByteArray(0)))
    }
}

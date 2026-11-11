package de.ayutv.data.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Teilen von Live-Stücken für LG webOS (kurze Teile, schnelles Umschalten). */
class TsSplitterTest {
    /** Kleiner TS-Strom: PAT, PMT (H.264 auf PID 0x100), je Bild ein PES mit Zeitstempel, Schlüsselbild alle [gop] Bilder. */
    private fun stream(frames: Int, gop: Int, fps: Int = 25): ByteArray {
        val out = ByteArrayOutputStream()
        fun packet(pid: Int, start: Boolean, cc: Int, adaptation: ByteArray?, payload: ByteArray) {
            val p = ByteArray(188) { 0xff.toByte() }
            p[0] = 0x47
            p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xff).toByte()
            var o = 4
            if (adaptation != null) {
                p[3] = (0x30 or (cc and 0xf)).toByte()
                p[4] = adaptation.size.toByte()
                adaptation.copyInto(p, 5)
                o = 5 + adaptation.size
            } else p[3] = (0x10 or (cc and 0xf)).toByte()
            payload.copyInto(p, o, 0, minOf(payload.size, 188 - o))
            out.write(p)
        }
        val pat = byteArrayOf(0, 0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0x00, 0, 0, 0, 0)
        val pmt = byteArrayOf(0, 0x02, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0,
            0x1b, 0xe1.toByte(), 0x00, 0xf0.toByte(), 0, 0, 0, 0, 0)
        packet(0, true, 0, null, pat)
        packet(0x1000, true, 0, null, pmt)
        var cc = 0
        for (i in 0 until frames) {
            val pts = 900_000L + i * 90_000L / fps
            val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
                (0x21 or ((pts shr 29) and 0x0e).toInt()).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xfe) or 1).toByte(),
                (pts shr 7).toByte(), (((pts shl 1) and 0xfe) or 1).toByte(), 0, 0, 0, 1, 0x65)
            val key = i % gop == 0
            packet(0x100, true, cc++, if (key) byteArrayOf(0x40) else null, pes)
            packet(0x100, false, cc++, null, ByteArray(100))
        }
        return out.toByteArray()
    }

    @Test
    fun splitsAtKeyframesAboutEverySecond() {
        val data = stream(frames = 150, gop = 25)
        val parts = TsSplitter.split(data, 6.0)
        assertEquals(6, parts.size)
        parts.forEach { assertEquals(1.0, it.seconds, 0.01) }
        // Jeder Teil beginnt mit PAT (PID 0) und kann so für sich anfangen.
        parts.forEach { assertEquals(0x47.toByte(), it.data[0]); assertEquals(0, ((it.data[1].toInt() and 0x1f) shl 8) or (it.data[2].toInt() and 0xff)) }
        // Ohne die vorangestellten Tabellen ergeben die Teile wieder genau das Stück.
        val joined = ByteArrayOutputStream().apply { parts.forEachIndexed { k, p -> write(p.data, if (k == 0) 0 else 2 * 188, p.data.size - if (k == 0) 0 else 2 * 188) } }
        assertArrayEquals(data, joined.toByteArray())
    }

    @Test
    fun progressiveGivesTheSamePartsWhileLoading() {
        val data = stream(frames = 150, gop = 25)
        val whole = TsSplitter.split(data, 6.0)
        val splitter = TsSplitter.Progressive(6.0)
        val parts = ArrayList<TsSplitter.Part>()
        // In kleinen, krummen Happen wie aus dem Netz; die ersten Teile müssen vor dem Ende herauskommen.
        var firstAt = -1
        var offset = 0
        while (offset < data.size) {
            val n = minOf(1_000, data.size - offset)
            val out = splitter.feed(data, offset, n)
            if (out.isNotEmpty() && firstAt < 0) firstAt = offset + n
            parts += out
            offset += n
        }
        parts += splitter.finish()
        assertTrue("erster Teil erst bei $firstAt von ${data.size}", firstAt in 1 until data.size / 4)
        assertEquals(whole.size, parts.size)
        for (k in whole.indices) {
            assertArrayEquals("Teil $k", whole[k].data, parts[k].data)
            assertEquals(whole[k].seconds, parts[k].seconds, 0.001)
        }
    }

    @Test
    fun progressiveNotTsStaysWhole() {
        val splitter = TsSplitter.Progressive(6.0)
        val junk = ByteArray(5_000) { 1 }
        assertTrue(splitter.feed(junk).isEmpty())
        val parts = splitter.finish()
        assertEquals(1, parts.size)
        assertEquals(5_000, parts[0].data.size)
        assertEquals(6.0, parts[0].seconds, 0.0)
    }

    @Test
    fun longGopFallsBackToAnyFrameStart() {
        val parts = TsSplitter.split(stream(frames = 150, gop = 250), 6.0)
        assertTrue("${parts.size} Teile", parts.size in 3..6)
        assertEquals(6.0, parts.sumOf { it.seconds }, 0.05)
        parts.dropLast(1).forEach { assertTrue("${it.seconds}", it.seconds in 1.0..2.01) }
    }

    @Test
    fun notTsStaysWhole() {
        val junk = ByteArray(5_000) { 1 }
        val parts = TsSplitter.split(junk, 6.0)
        assertEquals(1, parts.size)
        assertEquals(6.0, parts[0].seconds, 0.0)
    }

    @Test
    fun tableCountersContinueAcrossParts() {
        val parts = TsSplitter.split(stream(frames = 75, gop = 25), 3.0)
        val counters = HashMap<Int, Int>()
        val seen = ArrayList<Int>()
        for (p in parts) {
            TsSplitter.renumberTables(p.data, counters)
            var o = 0
            while (o < p.data.size) {
                val pid = ((p.data[o + 1].toInt() and 0x1f) shl 8) or (p.data[o + 2].toInt() and 0xff)
                if (pid == 0) seen += p.data[o + 3].toInt() and 0xf
                o += 188
            }
        }
        assertEquals((0 until seen.size).toList(), seen)
    }
}

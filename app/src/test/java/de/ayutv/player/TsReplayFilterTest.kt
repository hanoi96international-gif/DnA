package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class TsReplayFilterTest {
    private val video = 0x100
    private val audio = 0x101
    private val cc = HashMap<Int, Int>()

    private fun packet(pid: Int, pts: Long?): ByteArray {
        val p = ByteArray(TsReplayFilter.PACKET) { 0xFF.toByte() }
        val counter = cc.getOrDefault(pid, 0)
        cc[pid] = (counter + 1) and 0x0F
        p[0] = 0x47
        p[1] = ((if (pts != null) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = (0x10 or counter).toByte()
        if (pts != null) {
            val header = intArrayOf(
                0, 0, 1, if (pid == video) 0xE0 else 0xC0, 0, 0, 0x80, 0x80, 5,
                0x21 or (((pts shr 30) and 0x07).toInt() shl 1),
                ((pts shr 22) and 0xFF).toInt(),
                (((pts shr 15) and 0x7F).toInt() shl 1) or 1,
                ((pts shr 7) and 0xFF).toInt(),
                ((pts and 0x7F).toInt() shl 1) or 1,
            )
            header.forEachIndexed { k, v -> p[4 + k] = v.toByte() }
        }
        return p
    }

    /** Ein Bild = 3 Pakete (Beginn + 2 Fortsetzungen), dazu ein Tonpaket; alle 10 Bilder eine PAT. */
    private fun stream(frames: IntRange, base: Long = 900_000): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in frames) {
            if (f % 10 == 0) out.write(packet(0, null))
            out.write(packet(video, base + f * 3600L))
            out.write(packet(video, null))
            out.write(packet(video, null))
            out.write(packet(audio, base + f * 3600L + 100))
        }
        return out.toByteArray()
    }

    private fun feed(filter: TsReplayFilter, data: ByteArray, out: ByteArrayOutputStream, random: Random) {
        var i = 0
        while (i < data.size) {
            val n = minOf(data.size - i, 1 + random.nextInt(5000))
            filter.process(data, i, n) { b, o, l -> out.write(b, o, l) }
            i += n
        }
    }

    private fun starts(data: ByteArray, pid: Int): List<Long> {
        assertEquals("nur ganze Pakete", 0, data.size % TsReplayFilter.PACKET)
        val result = ArrayList<Long>()
        for (i in data.indices step TsReplayFilter.PACKET) {
            assertEquals(0x47.toByte(), data[i])
            val packetPid = ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)
            if (packetPid != pid || data[i + 1].toInt() and 0x40 == 0) continue
            fun b(k: Int) = data[i + 13 + k].toLong() and 0xFF
            result += (((b(0) shr 1) and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
        }
        return result
    }

    @Test
    fun replayAfterReconnectIsSkipped() {
        val random = Random(7)
        val filter = TsReplayFilter()
        val out = ByteArrayOutputStream()
        val first = stream(0..60)
        // Verbindung reißt mitten in einem Paket von Bild 60 ab.
        feed(filter, first.copyOf(first.size - 300), out, random)
        filter.reconnected()
        // Der Server schickt ab Bild 40 noch einmal, dann neu.
        feed(filter, stream(40..120), out, random)

        val frames = starts(out.toByteArray(), video).map { ((it - 900_000) / 3600).toInt() }
        // Bild 60 war nur halb da und kommt ganz noch einmal, sonst nichts doppelt und nichts fehlt.
        assertEquals((0..60).toList() + (60..120).toList(), frames)
        val sounds = starts(out.toByteArray(), audio).map { ((it - 900_100) / 3600).toInt() }
        assertEquals((0..120).toList(), sounds.distinct())
        assertTrue(filter.skippedBytes > 0)
    }

    @Test
    fun newTimeBaseAfterRestartPassesImmediately() {
        val random = Random(3)
        val filter = TsReplayFilter()
        val out = ByteArrayOutputStream()
        feed(filter, stream(0..50, base = 5_000_000_000), out, random)
        filter.reconnected()
        feed(filter, stream(0..20, base = 90_000), out, random)
        assertEquals(51 + 21, starts(out.toByteArray(), video).size)
    }

    @Test
    fun firstConnectionAndNonTsDataStayUntouched() {
        val filter = TsReplayFilter()
        val out = ByteArrayOutputStream()
        val data = stream(0..30)
        feed(filter, data, out, Random(1))
        assertEquals(data.toList(), out.toByteArray().toList())

        val text = "#EXTM3U\n#EXT-X-VERSION:3\nsegment1.ts\n".toByteArray()
        val playlist = TsReplayFilter()
        val textOut = ByteArrayOutputStream()
        playlist.process(text, 0, text.size) { b, o, l -> textOut.write(b, o, l) }
        playlist.flush { b, o, l -> textOut.write(b, o, l) }
        assertEquals(String(text), String(textOut.toByteArray()))
    }

    @Test
    fun clearlyOlderStreamIsANewTimeBase() {
        val filter = TsReplayFilter()
        val out = ByteArrayOutputStream()
        feed(filter, stream(0..50, base = 9_000_000), out, Random(4))
        filter.reconnected()
        feed(filter, stream(0..20, base = 90_000), out, Random(4))
        assertEquals(51 + 21, starts(out.toByteArray(), video).size)
        assertTrue(filter.takeNewTimeBase())
        assertFalse(filter.takeNewTimeBase())
    }

    @Test
    fun oldDataInRealTimeIsNotDiscarded() {
        // Ein Server, der nach dem Neuverbinden 10 s früher wieder anfängt, aber in Echtzeit liefert.
        var now = 0L
        val filter = TsReplayFilter { now }
        val out = ByteArrayOutputStream()
        feed(filter, stream(0..300), out, Random(5))
        filter.reconnected()
        for (f in 50..120) {
            val frame = stream(f..f)
            filter.process(frame, 0, frame.size) { b, o, l -> out.write(b, o, l) }
            now += 40
        }
        val frames = starts(out.toByteArray(), video).map { ((it - 900_000) / 3600).toInt() }
        // Nach gut einer Sekunde Echtzeit geht es weiter, statt 10 s lang zu verwerfen.
        assertTrue("erst ab ${frames.drop(301).firstOrNull()} weiter", frames.drop(301).first() <= 80)
        assertTrue(filter.takeNewTimeBase())
    }

    @Test
    fun ptsDifferenceWrapsAround() {
        val max = (1L shl 33) - 1
        assertEquals(2L, TsReplayFilter.diff(1, max))
        assertEquals(-2L, TsReplayFilter.diff(max, 1))
        assertEquals(-3600L, TsReplayFilter.diff(0, 3600))
    }
}

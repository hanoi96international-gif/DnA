package de.ayutv.data.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Film-Vorrat auf dem Gerätespeicher: Ringdatei, eigener Lese-Thread, Fehler erst nach den Daten davor. */
class ReadAheadBufferTest {
    private val dir: File = Files.createTempDirectory("vorrat").toFile()

    private fun data(size: Int) = ByteArray(size) { (it * 31 + it / 7).toByte() }

    private fun drain(buffer: ReadAheadBuffer, piece: Int = 10_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(piece)
        while (true) {
            val n = buffer.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun deliversEverythingThroughSmallRing() {
        val source = data(3_000_000)
        val input = ByteArrayInputStream(source)
        // Ring kleiner als die Datei: muss mehrfach umlaufen.
        val buffer = ReadAheadBuffer(File(dir, "vorrat-1.bin"), capacity = 300_000, upstream = input::read, chunk = 64 * 1024)
        assertArrayEquals(source, drain(buffer, piece = 7_777))
        buffer.close()
        assertFalse(File(dir, "vorrat-1.bin").exists())
    }

    @Test
    fun readsAheadWhileReaderWaits() {
        val source = data(1_000_000)
        val buffer = ReadAheadBuffer(File(dir, "vorrat-2.bin"), capacity = 400_000, upstream = ByteArrayInputStream(source)::read, chunk = 50_000)
        val deadline = System.currentTimeMillis() + 5_000
        while (buffer.buffered < 350_000 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        // Voll bis knapp unter die Größe, nicht darüber.
        assertTrue("${buffer.buffered}", buffer.buffered in 350_000..400_000)
        assertArrayEquals(source, drain(buffer))
        buffer.close()
    }

    @Test
    fun errorComesAfterBufferedData() {
        val source = data(200_000)
        var sent = 0
        val upstream = { b: ByteArray, off: Int, len: Int ->
            if (sent >= source.size) throw IOException("Leitung weg")
            val n = minOf(len, source.size - sent)
            source.copyInto(b, off, sent, sent + n)
            sent += n
            n
        }
        val buffer = ReadAheadBuffer(File(dir, "vorrat-3.bin"), capacity = 1_000_000, upstream = upstream, chunk = 32 * 1024)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        try {
            while (true) {
                val n = buffer.read(buf, 0, buf.size)
                if (n < 0) fail("Ende statt Fehler")
                out.write(buf, 0, n)
            }
        } catch (e: IOException) {
            assertEquals("Leitung weg", e.message)
        }
        assertArrayEquals(source, out.toByteArray())
        buffer.close()
    }

    @Test
    fun stopUnblocksReader() {
        val gate = Object()
        val upstream = { _: ByteArray, _: Int, _: Int -> synchronized(gate) { gate.wait(10_000) }; -1 }
        val buffer = ReadAheadBuffer(File(dir, "vorrat-4.bin"), capacity = 1_000_000, upstream = upstream, chunk = 32 * 1024)
        Thread { Thread.sleep(100); buffer.stop(); synchronized(gate) { gate.notifyAll() } }.start()
        try {
            buffer.read(ByteArray(10), 0, 10)
            fail("Lesen nach stop")
        } catch (_: IOException) {
        }
        buffer.close()
    }

    @Test
    fun sizes() {
        val mb = 1024L * 1024
        assertEquals(256 * mb, ReadAheadBuffer.sizeFor(256 * mb, 4_000 * mb))
        assertEquals(100 * mb, ReadAheadBuffer.sizeFor(256 * mb, 400 * mb))
        assertEquals(0, ReadAheadBuffer.sizeFor(256 * mb, 150 * mb))
    }
}

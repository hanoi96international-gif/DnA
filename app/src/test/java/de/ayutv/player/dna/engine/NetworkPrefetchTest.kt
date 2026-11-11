package de.ayutv.player.dna.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

/** Netz-Puffer vor dem TS-Leser: liest die Verbindung ständig leer, gibt alles unverändert und in Reihenfolge weiter. */
class NetworkPrefetchTest {
    /** Quelle, die in unregelmäßigen Happen liefert und am Ende optional einen Fehler wirft. */
    private class Source(private val data: ByteArray, private val failAtEnd: Boolean = false) : InputStream() {
        private var pos = 0
        private val random = Random(5)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) {
                if (failAtEnd) throw IOException("Verbindung abgerissen")
                return -1
            }
            if (random.nextInt(10) == 0) Thread.sleep(1)
            val n = minOf(len, data.size - pos, 1 + random.nextInt(70_000))
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun readAll(input: InputStream, chunk: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun passesEverythingThroughInOrderEvenWithSmallRing() {
        val data = Random(1).nextBytes(3_000_000)
        // Ring kleiner als die Daten: der Netz-Thread muss warten und weiterschreiben, ohne etwas zu verlieren.
        val prefetch = NetworkPrefetch(Source(data), 100_000)
        assertArrayEquals(data, readAll(prefetch, 65_536))
        prefetch.close()
    }

    @Test
    fun readsAheadWhileTheConsumerIsBusy() {
        val data = Random(2).nextBytes(500_000)
        val prefetch = NetworkPrefetch(Source(data), 1_000_000)
        // Der Leser „zerlegt“ gerade (tut nichts) – die Verbindung wird trotzdem leer gelesen.
        val until = System.currentTimeMillis() + 2_000
        while (prefetch.buffered < data.size && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(data.size, prefetch.buffered)
        assertArrayEquals(data, readAll(prefetch, 4_096))
        prefetch.close()
    }

    @Test
    fun errorComesAfterTheDataAlreadyReceived() {
        val data = Random(3).nextBytes(200_000)
        val prefetch = NetworkPrefetch(Source(data, failAtEnd = true), 1_000_000)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var failed = false
        try {
            while (true) {
                val n = prefetch.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } catch (e: IOException) {
            failed = true
        }
        assertTrue("Fehler der Verbindung kommt beim Leser an", failed)
        assertArrayEquals("alles davor kam an", data, out.toByteArray())
        prefetch.close()
    }
}

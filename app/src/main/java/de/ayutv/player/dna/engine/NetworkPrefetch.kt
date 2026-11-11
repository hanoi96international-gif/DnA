package de.ayutv.player.dna.engine

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Netz-Puffer für Live-Ströme (wie der Netz-Cache von VLC): Ein eigener Thread liest die Verbindung ständig leer und legt
 * die Bytes in einen Ringpuffer; der Leser der Engine (TS zerlegen, Bilder trennen) holt sie dort ab.
 *
 * Vorher las derselbe Thread das Netz und zerlegte den Strom: War der Vorrat der Engine voll oder das Zerlegen bei 4K
 * (5–10 MB/s) gerade langsamer, wurde die Verbindung eine Weile nicht gelesen. Der Strom staute sich beim Anbieter bzw.
 * Proxy, kam danach stoßweise, und manche Server trennen Abnehmer, die nicht mitlesen („vom Anbieter beendet“).
 *
 * [capacity] Bytes höchstens; ist der Ring voll, wartet auch der Netz-Thread (nie unbegrenzt Speicher).
 */
class NetworkPrefetch(
    private val source: InputStream,
    capacity: Int,
    name: String = "dna-net-prefetch",
) : InputStream() {
    private val ring = ByteArray(capacity.coerceAtLeast(64 * 1024))
    private val lock = Object()
    private var readPos = 0L
    private var writePos = 0L
    private var ended = false
    private var error: IOException? = null
    @Volatile private var closed = false

    /** Bytes, die schon da, aber noch nicht abgeholt sind. */
    val buffered: Int get() = synchronized(lock) { (writePos - readPos).toInt() }

    private val thread = Thread({ pump() }, name).apply {
        isDaemon = true
        start()
    }

    private fun pump() {
        val chunk = ByteArray(64 * 1024)
        try {
            while (!closed) {
                val n = source.read(chunk)
                if (n < 0) break
                var done = 0
                while (done < n && !closed) {
                    synchronized(lock) {
                        while (!closed && writePos - readPos >= ring.size) lock.wait(50)
                        if (closed) return@synchronized
                        val free = ring.size - (writePos - readPos).toInt()
                        val at = (writePos % ring.size).toInt()
                        val count = minOf(n - done, free, ring.size - at)
                        System.arraycopy(chunk, done, ring, at, count)
                        writePos += count
                        done += count
                        lock.notifyAll()
                    }
                }
            }
        } catch (e: InterruptedException) {
            // beendet
        } catch (e: IOException) {
            synchronized(lock) { error = e }
        } catch (e: Throwable) {
            // Auch Errors: Okio wirft auf Android beim Lesen einer gerade geschlossenen Verbindung AssertionError –
            // ungefangen beendete das die ganze App (Fire TV, 0.60.1). Wie ein Lesefehler behandeln (neu verbinden).
            synchronized(lock) { error = IOException(e.message ?: e.javaClass.simpleName, e) }
        } finally {
            synchronized(lock) {
                ended = true
                lock.notifyAll()
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (writePos == readPos) {
                if (closed) throw IOException("geschlossen")
                if (ended) {
                    error?.let { throw it }
                    return -1
                }
                try {
                    lock.wait(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("unterbrochen")
                }
            }
            val available = (writePos - readPos).toInt()
            val at = (readPos % ring.size).toInt()
            val count = minOf(len, available, ring.size - at)
            System.arraycopy(ring, at, b, off, count)
            readPos += count
            lock.notifyAll()
            return count
        }
    }

    override fun available(): Int = buffered

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { source.close() }
        thread.interrupt()
    }
}

package de.ayutv.data.net

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Liest eine Quelle (eine Verbindung) in einem eigenen Thread voraus in eine Ringdatei auf dem Gerätespeicher.
 *
 * Für 4K-Filme auf Sticks mit wenig Arbeitsspeicher: Der Player hält nur seinen kleinen Puffer im RAM, der Rest
 * (bis [capacity], z. B. 256 MB ≈ 30 s bei 70 Mbit/s) liegt auf dem Speicher. Fällt die Leitung kurz aus, liest
 * der Player weiter aus der Datei. Nebenbei bleibt die Verbindung beim Anbieter in Bewegung, auch wenn der Player
 * gerade nicht lädt (manche Server trennen ruhende Verbindungen).
 *
 * [upstream] liefert wie InputStream.read: Anzahl Bytes oder -1 am Ende; Fehler als IOException. Er wird nur aus dem
 * Vorrats-Thread aufgerufen.
 */
class ReadAheadBuffer(
    private val file: File,
    val capacity: Long,
    private val upstream: (ByteArray, Int, Int) -> Int,
    private val chunk: Int = 256 * 1024,
) : Closeable {
    private val channel: FileChannel = RandomAccessFile(file, "rw").channel
    private val lock = Object()
    private var written = 0L
    private var consumed = 0L
    private var ended = false
    private var failure: IOException? = null
    private var closed = false
    private val thread = Thread(::pump, "DnA-Vorrat").apply { isDaemon = true }

    init {
        require(capacity >= chunk) { "Vorrat zu klein" }
        thread.start()
    }

    /** Bytes, die schon geladen, aber noch nicht gelesen sind. */
    val buffered: Long get() = synchronized(lock) { written - consumed }

    /** Insgesamt aus der Quelle gelesen. */
    val total: Long get() = synchronized(lock) { written }

    private fun pump() {
        val buf = ByteArray(chunk)
        try {
            while (true) {
                synchronized(lock) {
                    while (!closed && capacity - (written - consumed) < chunk) lock.wait()
                    if (closed) return
                }
                val n = upstream(buf, 0, chunk)
                if (n < 0) {
                    synchronized(lock) { ended = true; lock.notifyAll() }
                    return
                }
                if (n == 0) continue
                // Der Bereich hinter [written] gehört nur diesem Thread: Der Leser liest nie über [written] hinaus.
                writeRing(written, buf, n)
                synchronized(lock) { written += n; lock.notifyAll() }
            }
        } catch (e: InterruptedException) {
            synchronized(lock) { if (failure == null) failure = InterruptedIOException("Vorrat beendet"); lock.notifyAll() }
        } catch (e: Throwable) {
            synchronized(lock) {
                failure = e as? IOException ?: IOException(e)
                lock.notifyAll()
            }
        }
    }

    /** Wie InputStream.read: wartet, bis Daten da sind; -1 am Ende der Quelle; Fehler der Quelle erst nach den Daten davor. */
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val available: Long
        synchronized(lock) {
            while (!closed && written == consumed && !ended && failure == null) lock.wait()
            if (closed) throw IOException("Vorrat geschlossen")
            if (written == consumed) {
                failure?.let { throw it }
                return -1
            }
            available = written - consumed
        }
        val n = minOf(length.toLong(), available).toInt()
        readRing(consumed, target, offset, n)
        synchronized(lock) {
            consumed += n
            lock.notifyAll()
        }
        return n
    }

    private fun writeRing(at: Long, data: ByteArray, length: Int) {
        var done = 0
        while (done < length) {
            val pos = (at + done) % capacity
            val n = minOf((length - done).toLong(), capacity - pos).toInt()
            val bb = ByteBuffer.wrap(data, done, n)
            var p = pos
            while (bb.hasRemaining()) p += channel.write(bb, p)
            done += n
        }
    }

    private fun readRing(at: Long, target: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val pos = (at + done) % capacity
            val n = minOf((length - done).toLong(), capacity - pos).toInt()
            val bb = ByteBuffer.wrap(target, offset + done, n)
            var p = pos
            while (bb.hasRemaining()) {
                val r = channel.read(bb, p)
                if (r < 0) throw IOException("Vorratsdatei zu kurz")
                p += r
            }
            done += n
        }
    }

    /** Beendet den Vorrat. Die Quelle selbst schließt der Aufrufer (damit ein hängendes Lesen abbricht). */
    fun stop() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    /** Nach [stop] und dem Schließen der Quelle: Thread abwarten, Datei löschen. */
    override fun close() {
        stop()
        runCatching { thread.join(1_000) }
        if (thread.isAlive) thread.interrupt()
        runCatching { channel.close() }
        file.delete()
    }

    companion object {
        const val PREFIX = "vorrat-"

        /** Reste aus abgestürzten Sitzungen löschen. */
        fun cleanup(dir: File) {
            dir.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() }
        }

        /**
         * Größe des Vorrats: [wanted], aber höchstens ein Viertel des freien Speichers; unter 48 MB lohnt es nicht (0).
         */
        fun sizeFor(wanted: Long, usableBytes: Long): Long {
            val size = minOf(wanted, usableBytes / 4)
            return if (size < 48L * 1024 * 1024) 0 else size
        }
    }
}

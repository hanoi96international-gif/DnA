package de.ayutv.player.dna.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Daten mit wahlfreiem Zugriff: Datei, HTTP mit Range-Anfragen oder (für Tests) ein Array. */
interface ByteSource : Closeable {
    /** Gesamtlänge, -1 = unbekannt. */
    val length: Long
    val position: Long
    /** Liest bis zu [len] Bytes; -1 am Ende. */
    fun read(buffer: ByteArray, offset: Int, len: Int): Int
    fun seek(position: Long)
}

class ArrayByteSource(private val data: ByteArray) : ByteSource {
    override val length: Long get() = data.size.toLong()
    override var position = 0L
        private set

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(len.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        position += n
        return n
    }

    override fun seek(position: Long) {
        this.position = position.coerceIn(0, data.size.toLong())
    }

    override fun close() = Unit
}

class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()
    override var position = 0L
        private set

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        val n = raf.read(buffer, offset, len)
        if (n > 0) position += n
        return n
    }

    override fun seek(position: Long) {
        this.position = position
        raf.seek(position)
    }

    override fun close() = raf.close()
}

/**
 * HTTP mit Range: Spulen = neue Anfrage ab der Stelle (kurze Sprünge nach vorn werden überlesen, das ist schneller als
 * eine neue Verbindung). Bricht die Verbindung ab, geht es an derselben Stelle weiter (bis [retries] Mal hintereinander).
 * Beim Schließen wird der Abruf sofort abgebrochen (Konten mit nur einer Verbindung).
 */
class HttpByteSource(
    private val http: OkHttpClient,
    private val url: String,
    private val userAgent: String?,
    private val retries: Int = 10,
) : ByteSource {
    override var length: Long = -1
        private set
    override var position = 0L
        private set
    @Volatile private var call: okhttp3.Call? = null
    private var input: InputStream? = null
    private var inputAt = -1L
    @Volatile private var closed = false
    /** Der Server kann keine Range-Anfragen (dann nur vorwärts lesen). */
    var rangeSupported = true
        private set

    private fun open(at: Long) {
        closeInput()
        if (closed) throw IOException("geschlossen")
        val request = Request.Builder().url(url)
            .apply { userAgent?.let { header("User-Agent", it) } }
            .apply { if (at > 0) header("Range", "bytes=$at-") }
            .build()
        val c = http.newCall(request)
        call = c
        val response = c.execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        val body = response.body
        val stream = body.byteStream()
        if (at > 0 && response.code != 206) {
            // Server ignoriert Range: bis zur Stelle überlesen.
            rangeSupported = false
            skipFully(stream, at)
        }
        if (length < 0) {
            val range = response.header("Content-Range")
            length = range?.substringAfter('/')?.toLongOrNull()
                ?: body.contentLength().takeIf { it >= 0 }?.let { if (response.code == 206) it + at else it } ?: -1
        }
        input = stream
        inputAt = at
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val n = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException()
            left -= n
        }
    }

    /** Erste Anfrage (Länge, Erreichbarkeit). */
    fun connect() {
        if (input == null) open(position)
    }

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        var failures = 0
        while (true) {
            if (closed) throw IOException("geschlossen")
            try {
                if (input == null || inputAt != position) open(position)
                val n = input!!.read(buffer, offset, len)
                if (n < 0) {
                    if (length >= 0 && position < length) throw IOException("Verbindung vorzeitig zu Ende")
                    return -1
                }
                position += n
                inputAt = position
                return n
            } catch (e: IOException) {
                if (closed) throw e
                closeInput()
                if (Thread.currentThread().isInterrupted || ++failures > retries) throw e
                // Anbieter mit nur einer Verbindung geben die alte oft erst nach Sekunden frei (sonst HTTP 509/403):
                // geduldig weiter versuchen statt den Film abzubrechen (dann setzte die App neu an).
                Thread.sleep(minOf(400L * failures, 3_000L))
            }
        }
    }

    override fun seek(position: Long) {
        if (position == this.position) return
        val forward = position - this.position
        val stream = input
        if (stream != null && inputAt == this.position && forward in 1..SKIP_FORWARD) {
            runCatching { skipFully(stream, forward) }.onSuccess {
                this.position = position
                inputAt = position
                return
            }
            closeInput()
        }
        this.position = position
        if (!rangeSupported && position < (inputAt.takeIf { it >= 0 } ?: 0)) closeInput()
    }

    /** Laufenden Abruf abbrechen (Spulen, Stopp); die Quelle bleibt nutzbar, der nächste Zugriff fragt neu an. */
    fun abort() = closeInput()

    private fun closeInput() {
        call?.cancel()
        runCatching { input?.close() }
        input = null
        inputAt = -1
    }

    override fun close() {
        closed = true
        closeInput()
    }

    companion object {
        /** Bis so weit nach vorn wird überlesen statt neu angefragt. */
        private const val SKIP_FORWARD = 512L * 1024
    }
}

/**
 * Gepuffertes Lesen mit Position über einer [ByteSource] – für die Container-Leser (MKV, MP4). Kurze Sprünge im
 * Puffer kosten nichts.
 */
class SourceReader(val source: ByteSource, bufferSize: Int = 256 * 1024) {
    private val buffer = ByteArray(bufferSize)
    private var bufferStart = 0L
    private var bufferLength = 0
    private var offset = 0

    val position: Long get() = bufferStart + offset
    val length: Long get() = source.length

    private fun fill(): Boolean {
        bufferStart += bufferLength
        offset = 0
        bufferLength = 0
        if (source.position != bufferStart) source.seek(bufferStart)
        val n = source.read(buffer, 0, buffer.size)
        if (n <= 0) return false
        bufferLength = n
        return true
    }

    fun seek(to: Long) {
        if (to >= bufferStart && to <= bufferStart + bufferLength) {
            offset = (to - bufferStart).toInt()
            return
        }
        bufferStart = to
        bufferLength = 0
        offset = 0
        source.seek(to)
    }

    fun skip(count: Long) = seek(position + count)

    /** Ein Byte (0..255), -1 am Ende. */
    fun readByte(): Int {
        if (offset >= bufferLength && !fill()) return -1
        return buffer[offset++].toInt() and 0xFF
    }

    fun readFully(target: ByteArray, at: Int = 0, len: Int = target.size - at) {
        var done = 0
        while (done < len) {
            if (offset >= bufferLength && !fill()) throw EOFException()
            val n = minOf(len - done, bufferLength - offset)
            System.arraycopy(buffer, offset, target, at + done, n)
            offset += n
            done += n
        }
    }

    /** Liest [count] Bytes; unplausible Größen (kaputte Datei) sind ein Lesefehler statt Speicher-Überlauf. */
    fun readBytes(count: Int): ByteArray {
        if (count < 0 || count > MAX_ELEMENT) throw java.io.IOException("unplausible Größe $count")
        if (length in 0 until position + count) throw EOFException()
        return ByteArray(count).also { readFully(it) }
    }

    fun u8(): Int = readByte().also { if (it < 0) throw EOFException() }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u24(): Int = (u16() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun u64(): Long = (u32() shl 32) or u32()
    fun atEnd(): Boolean = (length in 0..position) || (offset >= bufferLength && !fill())

    companion object {
        /** Größte einzelne Einheit (ein 4K-Bild ist weit darunter). */
        const val MAX_ELEMENT = 64 * 1024 * 1024
    }
}

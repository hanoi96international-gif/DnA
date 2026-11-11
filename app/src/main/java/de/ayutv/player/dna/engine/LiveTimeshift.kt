package de.ayutv.player.dna.engine

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Zeitversatz der DNA Engine: zeichnet den laufenden Sender (MPEG-TS, direkt vom Anbieter über den Proxy der App) in
 * einen Ringspeicher auf dem Gerät auf und liefert ihn der Engine über 127.0.0.1 – ab jeder Stelle im Speicher. So lässt
 * sich Live-TV anhalten (die Aufzeichnung läuft weiter, beim Weiterschauen geht es genau dort weiter), zurückspulen und
 * wieder zu Live springen. Zum Anbieter besteht dabei nur die eine Verbindung der Aufzeichnung.
 *
 * Die Zeit einer Stelle kommt aus Marken (Uhrzeit des Empfangs ↔ Byte im Strom), alle halbe Sekunde gesetzt.
 */
class LiveTimeshift(
    /** Adresse des Senders beim Anbieter. */
    private val source: String,
    /** Abruf beim Anbieter (Proxy der App). */
    upstream: OkHttpClient,
    private val userAgent: String?,
    dir: File,
    /** Größe des Ringspeichers. */
    private val capacity: Long,
    /** Höchstens so weit zurück (ms). */
    private val maxBackMs: Long,
) : Closeable {
    private val http = upstream.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
    private val file = File(dir, "dna-timeshift-${System.nanoTime()}.ts")
    private val writer = RandomAccessFile(file, "rw")
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val lock = Object()
    /** Bisher insgesamt aufgezeichnete Bytes (Stelle im Strom, nicht in der Datei). */
    @Volatile private var written = 0L
    @Volatile private var closed = false
    /** Laufender Abruf beim Anbieter: beim Schließen sofort abbrechen (viele Konten erlauben nur eine Verbindung). */
    @Volatile private var call: okhttp3.Call? = null
    /** Marken: Empfangszeit (elapsedRealtime) und Stelle im Strom. */
    private val marks = ArrayDeque<LongArray>()
    @Volatile var lastError: String? = null; private set
    /** Verbindung zum Anbieter abgerissen (die Aufzeichnung verbindet selbst neu). */
    @Volatile var onDrop: ((reason: String) -> Unit)? = null

    init {
        thread(name = "dna-ts-rec", isDaemon = true) { record() }
        thread(name = "dna-ts-srv", isDaemon = true) { serve() }
    }

    /** Adresse für die Engine ab Stelle [offset] im Strom. */
    fun urlAt(offset: Long): String = "http://127.0.0.1:${server.localPort}/live.ts?from=$offset"

    /** Adresse für die Engine ab dem Neuesten (Stelle wird erst beim Abruf bestimmt: auch für Neuverbinden). */
    val liveUrl: String get() = "http://127.0.0.1:${server.localPort}/live.ts"

    /** Älteste noch gespeicherte Stelle. */
    private val oldest: Long get() = (written - capacity + KEEP_FREE).coerceAtLeast(0L)

    /** Neueste Stelle (Live), auf ein TS-Paket ausgerichtet. */
    fun liveOffset(): Long = align((written - LIVE_LEAD_BYTES).coerceAtLeast(oldest))

    /** Wie weit sich zurückspulen lässt (ms). */
    val rewindableMs: Long
        get() = synchronized(lock) {
            val first = marks.firstOrNull { it[1] >= oldest } ?: return 0L
            (SystemClock.elapsedRealtime() - first[0]).coerceIn(0L, maxBackMs)
        }

    /** Hat schon etwas aufgezeichnet. */
    val hasData: Boolean get() = written > 0

    /** Stelle im Strom, die um [atRealtime] (elapsedRealtime) empfangen wurde, und deren genaue Empfangszeit. */
    fun offsetAt(atRealtime: Long): Pair<Long, Long> = synchronized(lock) {
        val usable = marks.filter { it[1] >= oldest }
        if (usable.isEmpty()) return liveOffset() to SystemClock.elapsedRealtime()
        val mark = usable.lastOrNull { it[0] <= atRealtime } ?: usable.first()
        align(mark[1]) to mark[0]
    }

    private fun align(offset: Long) = offset - offset % TS_PACKET

    private fun record() {
        var failures = 0
        val buffer = ByteArray(64 * 1024)
        while (!closed) {
            try {
                val current = http.newCall(Request.Builder().url(source).apply { userAgent?.let { header("User-Agent", it) } }.build())
                call = current
                if (closed) break
                current.execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val input = response.body.byteStream()
                    while (!closed) {
                        val n = input.read(buffer)
                        if (n < 0) {
                            if (!closed) runCatching { onDrop?.invoke("Anbieter hat die Verbindung beendet") }
                            break
                        }
                        if (n == 0) continue
                        write(buffer, n)
                        failures = 0
                    }
                }
            } catch (e: Exception) {
                if (closed) break
                lastError = e.message ?: e.javaClass.simpleName
                failures++
                runCatching { onDrop?.invoke(if (e is java.net.SocketTimeoutException) "Zeitüberschreitung (keine Daten)" else "Verbindung unterbrochen: $lastError") }
                Log.w(TAG, "Aufzeichnung unterbrochen ($failures): $lastError")
            }
            if (!closed) Thread.sleep(minOf(500L * failures, 5_000L))
        }
    }

    private fun write(data: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val at = written % capacity
            val n = minOf((length - off).toLong(), capacity - at).toInt()
            writer.seek(at)
            writer.write(data, off, n)
            off += n
            synchronized(lock) {
                written += n
                val now = SystemClock.elapsedRealtime()
                if (marks.isEmpty() || now - marks.last()[0] >= 500) marks.addLast(longArrayOf(now, written - n))
                // Alte Marken: aus dem Speicher gefallen oder älter als erlaubt.
                while (marks.size > 2 && (marks[1][1] < oldest || now - marks[1][0] > maxBackMs)) marks.removeFirst()
                lock.notifyAll()
            }
        }
    }

    private fun serve() {
        while (!closed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(name = "dna-ts-client", isDaemon = true) { runCatching { handle(socket) } ; runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val from = Regex("""[?&]from=(\d+)""").find(requestLine)?.groupValues?.get(1)?.toLongOrNull()
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
        if (requestLine.startsWith("HEAD")) return
        RandomAccessFile(file, "r").use { reader ->
            var position = align(from ?: liveOffset())
            val chunk = ByteArray(64 * 1024)
            while (!closed) {
                // Zu weit zurück (überschrieben): beim Ältesten weiter.
                if (position < oldest) position = align(oldest + TS_PACKET * 64)
                var available = written - position
                if (available <= 0) {
                    synchronized(lock) {
                        if (written - position <= 0 && !closed) lock.wait(1_000)
                    }
                    continue
                }
                val at = position % capacity
                available = minOf(available, capacity - at, chunk.size.toLong())
                reader.seek(at)
                val n = reader.read(chunk, 0, available.toInt())
                if (n <= 0) continue
                out.write(chunk, 0, n)
                out.flush()
                position += n
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        call?.cancel()
        synchronized(lock) { lock.notifyAll() }
        runCatching { server.close() }
        runCatching { writer.close() }
        runCatching { file.delete() }
    }

    companion object {
        private const val TAG = "AyuDnaTs"
        private const val TS_PACKET = 188L
        /** Abstand zum Schreibkopf, damit die Engine nicht im gerade überschriebenen Bereich liest. */
        private const val KEEP_FREE = 8L * 1024 * 1024
        /** Beim Sprung zu Live etwas vor dem Neuesten beginnen (die Engine braucht ein Schlüsselbild). */
        private const val LIVE_LEAD_BYTES = 512L * 1024

        /** Speicher für den Zeitversatz: höchstens gut die Hälfte des freien Platzes, 200 MB bis 4 GB. */
        fun capacityFor(dir: File): Long {
            val usable = dir.usableSpace
            val bytes = minOf(4L * 1024 * 1024 * 1024, (usable - 200L * 1024 * 1024) * 6 / 10)
            return if (bytes < 200L * 1024 * 1024) 0L else bytes
        }
    }
}

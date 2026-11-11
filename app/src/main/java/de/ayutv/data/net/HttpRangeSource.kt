package de.ayutv.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Film-Datei beim Anbieter, gelesen wie eine Datei (für [Remux]): über den Proxy der App, mit ihrer Kennung, mit
 * Byte-Bereichen (Range). Immer nur EINE Verbindung zum Anbieter – viele Konten erlauben nicht mehr. Kleine Sprünge
 * nach vorn werden überlesen statt neu angefragt.
 */
class HttpRangeSource(
    client: OkHttpClient,
    private val url: String,
    private val userAgent: String?,
    /** Bytes vom Anbieter (für die Anzeige). */
    private val fetched: AtomicLong? = null,
) : Remux.Source, Closeable {
    private val http = client.newBuilder().readTimeout(25, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build()

    /** Größe der Datei (-1 = noch unbekannt). */
    @Volatile var size = -1L
        private set

    /** Letzter Fehler (für die Meldung). */
    @Volatile var lastError: String? = null
        private set

    private var pos = 0L
    private var response: Response? = null
    private var stream: InputStream? = null
    private var streamPos = 0L
    @Volatile private var closed = false

    override fun read(buf: ByteArray, len: Int): Int {
        if (closed) return -1
        if (size in 0..pos) return 0
        for (attempt in 0..2) {
            try {
                val input = streamAt(pos) ?: return if (size in 0..pos) 0 else -1
                val n = input.read(buf, 0, len.coerceAtMost(buf.size))
                if (n < 0) {
                    disconnect()
                    // Ende der Antwort vor dem Ende der Datei (Anbieter bricht ab): neu anfragen.
                    if (size < 0 || pos >= size) return 0
                    continue
                }
                pos += n
                streamPos = pos
                fetched?.addAndGet(n.toLong())
                return n
            } catch (e: IOException) {
                lastError = "Anbieter: ${e.javaClass.simpleName}"
                disconnect()
                if (closed) return -1
            }
        }
        return -1
    }

    override fun seek(offset: Long, whence: Int): Long {
        if (whence and Remux.SEEK_SIZE != 0) {
            if (size < 0) runCatching { streamAt(pos) }
            return size
        }
        val target = when (whence and 3) {
            0 -> offset
            1 -> pos + offset
            2 -> if (size >= 0) size + offset else return -1
            else -> return -1
        }
        if (target < 0) return -1
        pos = target
        return pos
    }

    /** Offene Antwort, die bei [at] steht: vorhandene weiterlesen (auch ein Stück überlesen) oder neu anfragen. */
    private fun streamAt(at: Long): InputStream? {
        stream?.let { input ->
            if (at == streamPos) return input
            if (at > streamPos && at - streamPos <= SKIP_BYTES) {
                var left = at - streamPos
                val scratch = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                    if (n < 0) break
                    left -= n
                    fetched?.addAndGet(n.toLong())
                }
                if (left == 0L) {
                    streamPos = at
                    return input
                }
            }
        }
        disconnect()
        if (closed) return null
        val request = Request.Builder().url(url).apply {
            userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            header("Range", "bytes=$at-")
        }.build()
        val r = http.newCall(request).execute()
        if (!r.isSuccessful) {
            lastError = "Anbieter antwortet mit ${r.code}"
            r.close()
            // Hinter dem Ende (416): Ende der Datei.
            return null
        }
        var input = r.body.byteStream()
        when (r.code) {
            206 -> {
                r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.let { size = it }
            }
            else -> {
                // Anbieter ohne Range: liefert alles ab Anfang. Kurze Strecken überlesen, sonst geht Springen nicht.
                r.body.contentLength().takeIf { it >= 0 }?.let { size = it }
                if (at > 0) {
                    if (at > NO_RANGE_SKIP_BYTES) {
                        lastError = "Anbieter erlaubt kein Springen in der Datei"
                        r.close()
                        return null
                    }
                    var left = at
                    val scratch = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                        if (n < 0) {
                            r.close()
                            return null
                        }
                        left -= n
                    }
                }
            }
        }
        if (size < 0) r.body.contentLength().takeIf { it >= 0 }?.let { size = at + it }
        response = r
        input = input.buffered(256 * 1024)
        stream = input
        streamPos = at
        return input
    }

    private fun disconnect() {
        runCatching { response?.close() }
        response = null
        stream = null
    }

    override fun close() {
        closed = true
        disconnect()
    }

    private companion object {
        /** Bis hierhin wird nach vorn überlesen statt neu angefragt (eine neue Anfrage kostet oft mehr). */
        const val SKIP_BYTES = 1024 * 1024L
        const val NO_RANGE_SKIP_BYTES = 16 * 1024 * 1024L
    }
}

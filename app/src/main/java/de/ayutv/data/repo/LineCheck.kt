package de.ayutv.data.repo

import android.os.SystemClock
import de.ayutv.data.BufferProfile
import de.ayutv.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Leitungs-Check: Wie gut kommt ein Sender beim Anbieter an? Lädt den Stream einige Sekunden (ohne Bild und Ton)
 * und misst Antwortzeit, Datenrate und Aussetzer (über eine Sekunde nichts). Daraus folgt eine Empfehlung für
 * den Puffer: schnell umschalten, ausgewogen oder stabil.
 */
class LineCheck(private val http: OkHttpClient) {
    data class Result(
        val channel: String,
        val firstByteMs: Long,
        val kbps: Int,
        val stalls: Int,
        val longestGapMs: Long,
        val failed: String? = null,
        /** Höchstgeschwindigkeit vom Anbieter (Film-Datei so schnell wie möglich geladen); null = nicht gemessen. */
        val providerKbps: Int? = null,
        /** Dasselbe am Proxy vorbei (nur gemessen, wenn ein Proxy aktiv ist). */
        val directKbps: Int? = null,
        /** Der gemessene Sender ist 4K (gemessen oder laut Name): dann nie „Schnell“ empfehlen. */
        val uhd: Boolean = false,
    ) {
        val recommendation: BufferProfile get() = recommend(firstByteMs, stalls, longestGapMs, kbps, failed, uhd)
        val summary: String get() = (failed?.let { "$channel: kein Empfang ($it)" }
            ?: "$channel: Antwort nach ${firstByteMs} ms, Datenrate des Senders ${"%.1f".format(kbps / 1000.0)} Mbit/s, " +
                (if (stalls == 0) "keine Aussetzer" else "$stalls Aussetzer (längster ${"%.1f".format(longestGapMs / 1000.0)} s)")) +
            (providerKbps?.let { p ->
                val direct = directKbps
                if (direct != null) "\nVom Anbieter (Film): über Proxy ${"%.0f".format(p / 1000.0)} Mbit/s · direkt ${"%.0f".format(direct / 1000.0)} Mbit/s" +
                    (if (direct > p * 2) " – der Proxy bremst: Einstellungen → Proxy → „Videostreams über den Proxy“ aus" else "")
                else "\nVom Anbieter geladen (Film): bis ${"%.0f".format(p / 1000.0)} Mbit/s – Live-Sender kommen in Echtzeit, nie schneller als ihre Datenrate (kein Limit der App)"
            } ?: "")
    }

    /**
     * Wie schnell liefert der Anbieter überhaupt? Eine Film-Datei wird nicht in Echtzeit geschickt, sondern so schnell
     * es geht: [seconds] Sekunden (höchstens [maxBytes]) laden und die Geschwindigkeit messen. null bei Fehler.
     */
    suspend fun provider(url: String, userAgent: String, seconds: Int = 6, maxBytes: Long = 300L * 1024 * 1024, direct: Boolean = false): Int? = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).apply { if (direct) proxy(java.net.Proxy.NO_PROXY) }.build()
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val stream = response.body.byteStream()
                val buffer = ByteArray(256 * 1024)
                var bytes = 0L
                var begin = -1L
                val end = SystemClock.elapsedRealtime() + seconds * 1000L
                while (SystemClock.elapsedRealtime() < end && bytes < maxBytes) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    // Ab dem ersten Byte messen (Antwortzeit des Servers zählt nicht zur Geschwindigkeit).
                    if (begin < 0) begin = SystemClock.elapsedRealtime() else bytes += n
                }
                val ms = (SystemClock.elapsedRealtime() - begin).coerceAtLeast(1)
                if (bytes < 1_000_000) null else (bytes * 8 / ms).toInt()
            }
        }.getOrNull()
    }

    suspend fun run(channel: ChannelEntity, userAgent: String, seconds: Int = 10): Result = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val url = if (channel.url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }) {
            firstSegment(client, channel.url, userAgent) ?: channel.url
        } else channel.url
        val hlsKbps = if (url != channel.url) hls(channel.url, userAgent) else null
        val started = SystemClock.elapsedRealtime()
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result(channel.name, 0, 0, 0, 0, "HTTP ${response.code}")
                val stream = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var firstByte = -1L
                var bytes = 0L
                var last = started
                var stalls = 0
                var longest = 0L
                val end = started + seconds * 1000L
                while (SystemClock.elapsedRealtime() < end) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    if (firstByte < 0) firstByte = now - started else {
                        val gap = now - last
                        if (gap > STALL_MS) stalls++
                        longest = maxOf(longest, gap)
                    }
                    last = now
                    bytes += n
                }
                val measuredMs = (SystemClock.elapsedRealtime() - started - firstByte.coerceAtLeast(0)).coerceAtLeast(1)
                // HLS: Ein Segment kommt so schnell wie möglich (das wäre die Leitung); die Datenrate des Senders ist
                // Segmentgröße je Sekunde Laufzeit.
                Result(channel.name, firstByte.coerceAtLeast(0), hlsKbps ?: (bytes * 8 / measuredMs).toInt(), stalls, longest)
            }
        }.getOrElse { Result(channel.name, 0, 0, 0, 0, it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Datenrate eines HLS-Senders (kbit/s): bei mehreren Qualitäten die beste, dann die letzten zwei Segmente laden und
     * Bytes je Sekunde Laufzeit (#EXTINF) rechnen. null, wenn es kein HLS ist oder nicht lädt.
     */
    suspend fun hls(url: String, userAgent: String): Int? = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        fun get(u: String) = client.newCall(Request.Builder().url(u).header("User-Agent", userAgent).build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            it.body.string()
        }
        runCatching {
            var media = url
            var text = get(media)
            if (!text.trimStart().startsWith("#EXTM3U")) return@runCatching null
            if ("#EXT-X-STREAM-INF" in text) {
                val lines = text.lines().map { it.trim() }
                val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.maxByOrNull { i ->
                    BANDWIDTH.find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                } ?: return@runCatching null
                val uri = lines.drop(best + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@runCatching null
                media = java.net.URI(media).resolve(uri).toString()
                text = get(media)
            }
            val segments = ArrayList<Pair<Double, String>>()
            var duration = -1.0
            for (line in text.lines().map { it.trim() }) {
                when {
                    line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: -1.0
                    line.isNotEmpty() && !line.startsWith("#") && duration > 0 -> { segments += duration to line; duration = -1.0 }
                }
            }
            var bytes = 0L
            var seconds = 0.0
            for ((d, uri) in segments.takeLast(2)) {
                val segment = java.net.URI(media).resolve(uri).toString()
                client.newCall(Request.Builder().url(segment).header("User-Agent", userAgent).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val stream = r.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        bytes += n
                    }
                    seconds += d
                }
            }
            if (seconds <= 0 || bytes <= 0) null else (bytes * 8 / 1000 / seconds).toInt()
        }.getOrNull()
    }

    /** Bei HLS: das erste Segment der (ersten) Wiedergabeliste. */
    private fun firstSegment(client: OkHttpClient, url: String, userAgent: String, depth: Int = 0): String? = runCatching {
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { it.body.string() }
        val line = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val next = java.net.URI(url).resolve(line).toString()
        if (depth == 0 && line.substringBefore('?').endsWith(".m3u8")) firstSegment(client, next, userAgent, 1) else next
    }.getOrNull()

    companion object {
        private const val STALL_MS = 1_000L
        private val BANDWIDTH = Regex("BANDWIDTH=(\\d+)")

        /** Puffer-Empfehlung aus den Messwerten. */
        fun recommend(firstByteMs: Long, stalls: Int, longestGapMs: Long, kbps: Int, failed: String?, uhd: Boolean = false): BufferProfile = when {
            failed != null -> BufferProfile.STABLE
            stalls >= 2 || longestGapMs > 3_000 || firstByteMs > 3_000 -> BufferProfile.STABLE
            // 4K: kurze Aussetzer der Leitung überbrückt nur ein großer Puffer.
            uhd -> BufferProfile.STABLE
            // Hohe Datenrate (Full HD in guter Qualität): mindestens ausgewogen.
            kbps >= 8_000 -> BufferProfile.BALANCED
            // Live-Streams kommen in Echtzeit: Die Datenrate zeigt die Stream-Qualität, nicht die Reserve der Leitung.
            stalls == 1 || firstByteMs > 1_200 -> BufferProfile.BALANCED
            else -> BufferProfile.FAST
        }
    }
}

package de.ayutv.player

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Decoder-Test: ein paar Sekunden des Senders laden und ohne Media3 direkt mit dem Android-Decoder
 * dekodieren, in mehreren Varianten. Zeigt, ob der Decoder des Geräts den Stream grundsätzlich schafft (dann liegt es
 * am Player-Unterbau oder an der Anzeige) oder nicht (dann am Gerät oder am Stream).
 *
 * Anlass: Xiaomi 11T, 4K Sender UHD (HEVC 10 Bit HDR) – c2.mtk.hevc.decoder gab im Player kein Bild aus und brach nach
 * ~2 s mit 0xe ab, ohne dass die App ihn zurücksetzte.
 */
object DecoderProbe {
    private class Variant(val label: String, val surface: Boolean, val frameRate: Int? = null, val realtime: Boolean = false)

    private val VARIANTS = listOf(
        Variant("Bildfläche (wie im Player)", surface = true),
        Variant("ohne Bildfläche", surface = false),
        Variant("Bildfläche, Bildrate 50 vorab", surface = true, frameRate = 50),
        Variant("Bildfläche, im Echtzeittakt", surface = true, realtime = true),
    )

    private const val DOWNLOAD_MS = 9_000L
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val VARIANT_MS = 8_000L

    /** Lädt [url] kurz in [dir], testet alle Varianten, löscht die Datei. Liefert den Bericht (mehrzeilig). */
    suspend fun run(http: OkHttpClient, url: String, userAgent: String?, dir: File, status: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val file = File(dir, "decoder-test.ts")
            try {
                status("Decoder-Test: lade einige Sekunden des Senders …")
                val bytes = download(http, url, userAgent, file)
                if (bytes < 256 * 1024) return@withContext "Sender lieferte zu wenig Daten (${bytes / 1024} KB)"
                val probe = MediaExtractor().apply { setDataSource(file.path) }
                val track = (0 until probe.trackCount).firstOrNull {
                    probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext "Kein Bild im Stream gefunden".also { probe.release() }
                val format = probe.getTrackFormat(track)
                probe.release()
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val lines = ArrayList<String>()
                lines += "%s %d×%d, %.1f MB in %d s".format(
                    mime.removePrefix("video/"), format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
                    bytes / 1_048_576.0, DOWNLOAD_MS / 1000,
                )
                format.csd()?.let { HevcSps.find(it) }?.let { lines += "HEVC-Kopf: ${it.describe()}" }
                for ((k, variant) in VARIANTS.withIndex()) {
                    if (variant.surface && Build.VERSION.SDK_INT < 26) continue
                    status("Decoder-Test ${k + 1}/${VARIANTS.size}: ${variant.label} …")
                    lines += "${variant.label}: " + runVariant(file, track, variant)
                }
                lines.joinToString("\n")
            } catch (e: Exception) {
                "Test abgebrochen: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            } finally {
                file.delete()
            }
        }

    private fun MediaFormat.csd(): ByteArray? = runCatching {
        getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
    }.getOrNull()

    private fun download(http: OkHttpClient, url: String, userAgent: String?, file: File): Long {
        val request = Request.Builder().url(url).apply { userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }.build()
        var total = 0L
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Anbieter antwortet mit ${response.code}" }
            val input = response.body.byteStream()
            val until = SystemClock.elapsedRealtime() + DOWNLOAD_MS
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (SystemClock.elapsedRealtime() < until && total < MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        return total
    }

    private fun runVariant(file: File, track: Int, variant: Variant): String {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var queued = 0
        var output = 0
        var firstOutputMs = -1L
        var outFormat = ""
        val t0 = SystemClock.elapsedRealtime()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            variant.frameRate?.let { format.setInteger(MediaFormat.KEY_FRAME_RATE, it) }
            // Erst ab einem Schlüsselbild (wie ein Player nach dem Einstieg).
            while (extractor.sampleTrackIndex >= 0 && (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) extractor.advance()
            if (extractor.sampleTrackIndex < 0) return "kein Schlüsselbild in den geladenen Daten"
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            if (variant.surface) {
                val st = SurfaceTexture(false)
                texture = st
                surface = Surface(st)
            }
            c.configure(format, surface, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var firstPts = -1L
            val playStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < VARIANT_MS) {
                if (!inputDone) {
                    val pts = extractor.sampleTime
                    val due = !variant.realtime || firstPts < 0 || pts < 0 ||
                        (pts - firstPts) / 1000 <= SystemClock.elapsedRealtime() - playStart + 500
                    if (due) {
                        val index = c.dequeueInputBuffer(5_000)
                        if (index >= 0) {
                            val buffer = c.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                if (firstPts < 0) firstPts = pts
                                c.queueInputBuffer(index, 0, size, pts, 0)
                                queued++
                                extractor.advance()
                            }
                        }
                    }
                }
                val out = c.dequeueOutputBuffer(info, 5_000)
                when {
                    out >= 0 -> {
                        if (info.size > 0 || variant.surface) {
                            output++
                            if (firstOutputMs < 0) firstOutputMs = SystemClock.elapsedRealtime() - t0
                        }
                        c.releaseOutputBuffer(out, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = c.outputFormat.let { f ->
                        val w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                        val h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                        "${w}×$h"
                    }
                }
            }
            val ok = if (output > 0) "✓ $output Bilder" else "✗ kein Bild"
            return "$ok aus $queued (${c.name}" +
                (if (firstOutputMs >= 0) ", erstes nach $firstOutputMs ms" else "") +
                (if (outFormat.isNotEmpty()) ", Ausgabe $outFormat" else "") + ")"
        } catch (e: MediaCodec.CodecException) {
            val at = SystemClock.elapsedRealtime() - t0
            return "✗ Decoder-Fehler 0x%x nach %d ms, %d Bilder aus %d (%s)".format(e.errorCode, at, output, queued, e.diagnosticInfo.orEmpty().take(60))
        } catch (e: Exception) {
            val at = SystemClock.elapsedRealtime() - t0
            return "✗ ${e.javaClass.simpleName} nach $at ms, $output Bilder aus $queued (${e.message.orEmpty().take(60)})"
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            runCatching { texture?.release() }
            runCatching { extractor.release() }
        }
    }
}

package de.ayutv.player.dna.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HLS-Wiedergabelisten (RFC 8216): Übersicht der Fassungen und Abschnittslisten. */
object HlsPlaylist {
    class Variant(val url: String, val bandwidth: Long, val width: Int, val height: Int, val codecs: String?, val audioGroup: String?)

    class Rendition(val type: String, val groupId: String?, val language: String?, val name: String?, val url: String?, val isDefault: Boolean)

    class Master(val variants: List<Variant>, val renditions: List<Rendition>)

    class Segment(
        val url: String,
        val durationUs: Long,
        /** Laufende Nummer (EXT-X-MEDIA-SEQUENCE + Index). */
        val sequence: Long,
        val startUs: Long,
        val keyUrl: String?,
        val keyIv: ByteArray?,
        val initUrl: String?,
        val discontinuity: Boolean,
        /** EXT-X-BYTERANGE: (Länge, Start). */
        val byteRange: Pair<Long, Long>?,
    )

    class Media(val segments: List<Segment>, val targetDurationUs: Long, val ended: Boolean, val mediaSequence: Long) {
        val durationUs: Long get() = segments.sumOf { it.durationUs }
    }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, base: String): Master {
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val a = attributes(line.substringAfter(':'))
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val res = a["RESOLUTION"]?.split('x')
                        variants += Variant(
                            resolve(base, lines[j]),
                            a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0,
                            res?.getOrNull(0)?.toIntOrNull() ?: 0,
                            res?.getOrNull(1)?.toIntOrNull() ?: 0,
                            a["CODECS"],
                            a["AUDIO"],
                        )
                    }
                    i = j
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    renditions += Rendition(
                        a["TYPE"] ?: "", a["GROUP-ID"], a["LANGUAGE"], a["NAME"], a["URI"]?.let { resolve(base, it) }, a["DEFAULT"] == "YES",
                    )
                }
            }
            i++
        }
        return Master(variants, renditions)
    }

    fun parseMedia(text: String, base: String): Media {
        val segments = ArrayList<Segment>()
        var target = 6_000_000L
        var sequence = 0L
        var ended = false
        var duration = 0L
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var initUrl: String? = null
        var discontinuity = false
        var start = 0L
        var range: Pair<Long, Long>? = null
        var nextRangeStart = 0L
        var index = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = (line.substringAfter(':').toDoubleOrNull() ?: 6.0).times(1_000_000).toLong()
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF:") -> duration = (line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0).times(1_000_000).toLong()
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> discontinuity = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["METHOD"] == "AES-128") {
                        keyUrl = a["URI"]?.let { resolve(base, it) }
                        keyIv = a["IV"]?.let(::hexIv)
                    } else {
                        keyUrl = null
                        keyIv = null
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> initUrl = attributes(line.substringAfter(':'))["URI"]?.let { resolve(base, it) }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':')
                    val length = v.substringBefore('@').toLongOrNull() ?: 0
                    val offset = if ('@' in v) v.substringAfter('@').toLongOrNull() ?: 0 else nextRangeStart
                    range = length to offset
                    nextRangeStart = offset + length
                }
                line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(resolve(base, line), duration, sequence + index, start, keyUrl, keyIv, initUrl, discontinuity, range)
                    start += duration
                    index++
                    discontinuity = false
                    range = null
                }
            }
        }
        return Media(segments, target, ended, sequence)
    }

    /** Attribute einer Zeile, auch mit Anführungszeichen und Kommas darin. */
    fun attributes(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq).trim().trimStart(',')
            var j = eq + 1
            val value: String
            if (j < text.length && text[j] == '"') {
                val close = text.indexOf('"', j + 1).let { if (it < 0) text.length else it }
                value = text.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = text.indexOf(',', j).let { if (it < 0) text.length else it }
                value = text.substring(j, comma)
                j = comma
            }
            out[key.trim()] = value
            i = j + 1
        }
        return out
    }

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private fun hexIv(hex: String): ByteArray? {
        val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return runCatching { ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv -> for (k in 0 until 8) iv[15 - k] = ((sequence shr (8 * k)) and 0xFF).toByte() }

    /**
     * Fassung wählen: die beste, die das Gerät flüssig dekodiert ([maxHeight]) und die die Leitung trägt ([maxBandwidth]);
     * ohne Angaben die höchste.
     */
    fun choose(variants: List<Variant>, maxHeight: Int, maxBandwidth: Long): Variant? {
        if (variants.isEmpty()) return null
        val fitting = variants.filter { (it.height == 0 || it.height <= maxHeight) && (maxBandwidth <= 0 || it.bandwidth <= maxBandwidth) }
        return (fitting.ifEmpty { listOf(variants.minBy { it.bandwidth }) }).maxBy { it.bandwidth * 10_000L + it.height }
    }
}

/**
 * Lädt die Abschnitte einer HLS-Liste nacheinander und gibt ihre Bytes bzw. Proben weiter. Live: hält Abstand zum Ende
 * (drei Abschnitte) und lädt die Liste nach; Filme: von der gewünschten Stelle an. AES-128 wird entschlüsselt. TS-Abschnitte
 * gehen als Strom an den TS-Leser, fMP4-Abschnitte (mit EXT-X-MAP) an den MP4-Leser.
 */
internal class HlsLoader(
    private val http: OkHttpClient,
    private val userAgent: String?,
    @Volatile var running: () -> Boolean,
    private val maxHeight: Int,
    private val maxBandwidth: Long,
) {
    var media: HlsPlaylist.Media? = null
        private set
    var mediaUrl: String? = null
        private set
    var live = true
        private set
    private var lastSequence = -1L
    private val keys = HashMap<String, ByteArray>()
    private val inits = HashMap<String, ByteArray>()

    fun get(url: String): ByteArray {
        val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.bytes()
        }
    }

    private fun getRange(url: String, range: Pair<Long, Long>?): ByteArray {
        if (range == null) return get(url)
        val request = Request.Builder().url(url).header("Range", "bytes=${range.second}-${range.second + range.first - 1}")
            .apply { userAgent?.let { header("User-Agent", it) } }.build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.bytes()
        }
    }

    /** Übersicht bzw. Abschnittsliste laden und eine Fassung wählen. */
    fun open(url: String) {
        val text = String(get(url), Charsets.UTF_8)
        if (HlsPlaylist.isMaster(text)) {
            val master = HlsPlaylist.parseMaster(text, url)
            val variant = HlsPlaylist.choose(master.variants, maxHeight, maxBandwidth) ?: throw IOException("HLS ohne Fassung")
            DnaLog.i(TAG, "HLS: ${variant.width}×${variant.height} ${variant.bandwidth / 1000} kbit/s von ${master.variants.size}")
            mediaUrl = variant.url
            reload()
        } else {
            mediaUrl = url
            media = HlsPlaylist.parseMedia(text, url)
        }
        live = media?.ended != true
    }

    fun reload() {
        val url = mediaUrl ?: return
        media = HlsPlaylist.parseMedia(String(get(url), Charsets.UTF_8), url)
        live = media?.ended != true
    }

    /** Erster Abschnitt: Live drei vor dem Ende, sonst der, in dem [startUs] liegt. */
    fun firstSegment(startUs: Long): HlsPlaylist.Segment? {
        val m = media ?: return null
        val s = if (live) m.segments.getOrNull((m.segments.size - 3).coerceAtLeast(0))
        else m.segments.lastOrNull { it.startUs <= startUs } ?: m.segments.firstOrNull()
        lastSequence = (s?.sequence ?: 0) - 1
        return s
    }

    /** Nächster Abschnitt; Live wird die Liste nachgeladen, bis es einen gibt. Null = Ende. */
    fun nextSegment(): HlsPlaylist.Segment? {
        while (running()) {
            val m = media ?: return null
            m.segments.firstOrNull { it.sequence > lastSequence }?.let {
                lastSequence = it.sequence
                return it
            }
            if (!live) return null
            Thread.sleep((m.targetDurationUs / 2000).coerceIn(500, 5_000))
            runCatching { reload() }.onFailure { DnaLog.i(TAG, "HLS-Liste: ${it.message}") }
            // Zu weit zurückgefallen (Liste ist weitergerückt): beim ältesten noch vorhandenen weiter.
            val now = media ?: continue
            if (now.segments.isNotEmpty() && lastSequence < now.segments.first().sequence - 1) lastSequence = now.segments.first().sequence - 1
        }
        return null
    }

    /** Abschnitt laden (entschlüsselt). */
    fun load(segment: HlsPlaylist.Segment): ByteArray {
        var data = getRange(segment.url, segment.byteRange)
        val keyUrl = segment.keyUrl
        if (keyUrl != null) {
            val key = keys.getOrPut(keyUrl) { get(keyUrl) }
            val iv = segment.keyIv ?: HlsPlaylist.sequenceIv(segment.sequence)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            data = cipher.doFinal(data)
        }
        return data
    }

    fun init(segment: HlsPlaylist.Segment): ByteArray? = segment.initUrl?.let { url -> inits.getOrPut(url) { get(url) } }

    companion object {
        private const val TAG = "DnaHls"
    }
}

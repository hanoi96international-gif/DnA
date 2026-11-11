package de.ayutv.player.dna.engine

/**
 * Leser für Dateien mit Inhaltsverzeichnis (MKV, MP4): erst Kopf lesen ([init]), dann Probe für Probe ([read]) in
 * Dateireihenfolge; Spulen ([seek]) springt zum Schlüsselbild an oder vor der Zeit.
 */
interface ContainerDemuxer {
    class Info(val tracks: List<Track>, val durationUs: Long, val seekable: Boolean)

    fun init(): Info

    /** Nächste Probe, null am Ende der Datei. */
    fun read(): Sample?

    /** Zum Schlüsselbild an oder vor [timeUs]; liefert dessen Zeit (bzw. [timeUs], wenn es kein Verzeichnis gibt). */
    fun seek(timeUs: Long): Long
}

/** Bausteine für H.264/HEVC aus Containern (avcC/hvcC, Längenfelder statt Startcodes). */
internal object NalUnits {
    private val START = byteArrayOf(0, 0, 0, 1)

    class Config(val csd: List<ByteArray>, val nalLengthSize: Int, val width: Int, val height: Int)

    /** avcC (ISO/IEC 14496-15): csd-0 = SPS, csd-1 = PPS (Annex B). */
    fun avcC(data: ByteArray): Config? {
        if (data.size < 7 || data[0].toInt() != 1) return null
        val lengthSize = (data[4].toInt() and 3) + 1
        var pos = 5
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        val spsCount = data[pos++].toInt() and 0x1F
        repeat(spsCount) {
            val len = u16(data, pos); pos += 2
            if (pos + len > data.size) return null
            sps += data.copyOfRange(pos, pos + len); pos += len
        }
        if (pos >= data.size) return null
        val ppsCount = data[pos++].toInt() and 0xFF
        repeat(ppsCount) {
            val len = u16(data, pos); pos += 2
            if (pos + len > data.size) return null
            pps += data.copyOfRange(pos, pos + len); pos += len
        }
        if (sps.isEmpty()) return null
        val (w, h) = runCatching { H264Sps.size(sps.first()) }.getOrDefault(0 to 0)
        return Config(listOf(join(sps), join(pps)), lengthSize, w, h)
    }

    /** hvcC (ISO/IEC 14496-15): csd-0 = VPS+SPS+PPS (Annex B). */
    fun hvcC(data: ByteArray): Config? {
        if (data.size < 23) return null
        val lengthSize = (data[21].toInt() and 3) + 1
        val arrays = data[22].toInt() and 0xFF
        var pos = 23
        val nals = ArrayList<ByteArray>()
        var sps: ByteArray? = null
        repeat(arrays) {
            if (pos + 3 > data.size) return null
            val type = data[pos].toInt() and 0x3F
            val count = u16(data, pos + 1)
            pos += 3
            repeat(count) {
                val len = u16(data, pos); pos += 2
                if (pos + len > data.size) return null
                val nal = data.copyOfRange(pos, pos + len)
                if (type in 32..34) nals += nal
                if (type == 33 && sps == null) sps = nal
                pos += len
            }
        }
        if (nals.isEmpty()) return null
        val info = sps?.let { runCatching { de.ayutv.player.HevcSps.parse(it) }.getOrNull() }
        return Config(listOf(join(nals)), lengthSize, info?.width ?: 0, info?.height ?: 0)
    }

    private fun join(nals: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        nals.forEach { out.write(START); out.write(it) }
        return out.toByteArray()
    }

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    /** Längenfelder ([lengthSize] Bytes) vor jedem NAL durch Startcodes ersetzen. */
    fun toAnnexB(data: ByteArray, lengthSize: Int): ByteArray {
        if (lengthSize == 4) {
            // Gleich lang: an Ort und Stelle.
            var pos = 0
            while (pos + 4 <= data.size) {
                val len = ((data[pos].toLong() and 0xFF) shl 24) or ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or (data[pos + 3].toLong() and 0xFF)
                data[pos] = 0; data[pos + 1] = 0; data[pos + 2] = 0; data[pos + 3] = 1
                if (len <= 0 || pos + 4 + len > data.size) break
                pos += 4 + len.toInt()
            }
            return data
        }
        val out = java.io.ByteArrayOutputStream(data.size + 64)
        var pos = 0
        while (pos + lengthSize <= data.size) {
            var len = 0
            repeat(lengthSize) { len = (len shl 8) or (data[pos + it].toInt() and 0xFF) }
            pos += lengthSize
            if (len <= 0 || pos + len > data.size) break
            out.write(START)
            out.write(data, pos, len)
            pos += len
        }
        return out.toByteArray()
    }

    /** Schlüsselbild nach NAL-Typ (Annex B). */
    fun isKeyframe(codec: Codec, data: ByteArray): Boolean = TsDemuxer.isKeyframe(codec, data)
}

/** Sprachkürzel aus Containern (ISO 639-2, „und“ = unbekannt) einheitlich. */
internal fun normalizeLanguage(code: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unk" } ?: return null
    return when (c) {
        "ger", "deu" -> "de"
        "eng" -> "en"
        "fre", "fra" -> "fr"
        "spa" -> "es"
        "ita" -> "it"
        "tur" -> "tr"
        "pol" -> "pl"
        "rus" -> "ru"
        "dut", "nld" -> "nl"
        "por" -> "pt"
        "ara" -> "ar"
        "jpn" -> "ja"
        "kor" -> "ko"
        "chi", "zho" -> "zh"
        else -> if (c.length == 3 || c.length == 2) c else c.substringBefore('-').take(3)
    }
}

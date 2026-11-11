package de.ayutv.player

/**
 * Liest aus dem HEVC-Kopf (SPS) die Werte, die bestimmen, wie viele Bilder ein Decoder gleichzeitig vorhalten muss –
 * für den Fehlerbericht. Hintergrund: Der MediaTek-Decoder im Xiaomi 11T stellt sich bei Sender UHD nach ~2 s von 12 auf
 * 16 Bildspeicher um und bricht dann ab (0xe). Verlangt der Stream mehr, als für 4K im Level erlaubt ist (6), liegt es am
 * Stream; sonst am Decoder.
 */
object HevcSps {
    data class Info(
        val levelIdc: Int,
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val maxSubLayers: Int,
        /** sps_max_dec_pic_buffering_minus1 + 1 (höchste Ebene). */
        val decPicBuffering: Int,
        val numReorder: Int,
        val latencyIncreasePlus1: Int,
    ) {
        /** Größter erlaubter Bildspeicher für Level und Bildgröße (HEVC A.4.2). */
        val maxDpbSize: Int get() = maxDpbSize(levelIdc, width.toLong() * height)

        fun describe(): String = "Level %d.%d, %d×%d, %d Bit, %d Ebene(n), Bildspeicher %d (erlaubt %d), Umsortierung %d".format(
            levelIdc / 30, (levelIdc % 30) / 3, width, height, bitDepth, maxSubLayers, decPicBuffering, maxDpbSize, numReorder,
        ) + if (decPicBuffering > maxDpbSize) " – mehr als erlaubt" else ""
    }

    fun maxDpbSize(levelIdc: Int, picSize: Long): Int {
        val maxLumaPs = when {
            levelIdc <= 30 -> 36_864L
            levelIdc <= 60 -> 122_880L
            levelIdc <= 63 -> 245_760L
            levelIdc <= 90 -> 552_960L
            levelIdc <= 93 -> 983_040L
            levelIdc <= 123 -> 2_228_224L
            levelIdc <= 156 -> 8_912_896L
            else -> 35_651_584L
        }
        return when {
            picSize <= maxLumaPs shr 2 -> 16
            picSize <= maxLumaPs shr 1 -> 12
            picSize <= maxLumaPs * 3 / 4 -> 8
            else -> 6
        }
    }

    /** Sucht in [data] (Annex B, z. B. VPS+SPS+PPS) das SPS und liest es; null, wenn keins da oder unlesbar. */
    fun find(data: ByteArray): Info? {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val type = (data[i + 3].toInt() shr 1) and 0x3F
                if (type == 33) {
                    var end = i + 3
                    while (end + 2 < data.size && !(data[end].toInt() == 0 && data[end + 1].toInt() == 0 &&
                            (data[end + 2].toInt() == 1 || data[end + 2].toInt() == 0))
                    ) end++
                    if (end + 2 >= data.size) end = data.size
                    return runCatching { parse(data.copyOfRange(i + 3, end)) }.getOrNull()
                }
                i += 3
            } else i++
        }
        return null
    }

    /** [nal]: SPS ab dem NAL-Kopf (2 Bytes), mit Emulationsschutz. */
    fun parse(nal: ByteArray): Info {
        val r = Bits(unescape(nal))
        r.skip(16) // NAL-Kopf
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.u(3)
        r.skip(1)
        // profile_tier_level
        r.skip(2 + 1 + 5 + 32 + 4 + 43 + 1)
        val level = r.u(8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (k in 0 until maxSubLayersMinus1) {
            profilePresent[k] = r.u(1) == 1
            levelPresent[k] = r.u(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (k in maxSubLayersMinus1 until 8) r.skip(2)
        for (k in 0 until maxSubLayersMinus1) {
            if (profilePresent[k]) r.skip(88)
            if (levelPresent[k]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        val width = r.ue()
        val height = r.ue()
        if (r.u(1) == 1) repeat(4) { r.ue() }
        val bitDepth = r.ue() + 8
        r.ue() // bit_depth_chroma
        r.ue() // log2_max_pic_order_cnt_lsb_minus4
        val allLayers = r.u(1) == 1
        var dpb = 0
        var reorder = 0
        var latency = 0
        for (k in (if (allLayers) 0 else maxSubLayersMinus1)..maxSubLayersMinus1) {
            dpb = r.ue() + 1
            reorder = r.ue()
            latency = r.ue()
        }
        return Info(level, width, height, bitDepth, maxSubLayersMinus1 + 1, dpb, reorder, latency)
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data[pos shr 3].toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) {
            pos += n
            require(pos <= data.size * 8) { "SPS zu kurz" }
        }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                require(zeros < 32) { "ue zu lang" }
            }
            return (1 shl zeros) - 1 + if (zeros > 0) u(zeros) else 0
        }
    }
}

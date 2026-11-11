package de.ayutv.player.dna.engine

import de.ayutv.player.HevcSps

/**
 * Was der Video-Decoder vor dem ersten Bild wissen muss: Parametersätze (csd) und Bildgröße aus dem Strom. So wird er
 * gleich für die echte Auflösung und Bildspeicher-Zahl eingerichtet (4K: kein Umschalten mitten im Strom – genau daran
 * scheiterte der MediaTek-Decoder des Xiaomi 11T).
 */
data class VideoConfig(
    val mime: String,
    val width: Int,
    val height: Int,
    /** csd-0 (HEVC: VPS+SPS+PPS; H.264: SPS) und csd-1 (H.264: PPS), jeweils Annex B mit Startcode. */
    val csd0: ByteArray?,
    val csd1: ByteArray?,
    val bitDepth: Int = 8,
    /** Halbbilder (H.264 mit frame_mbs_only_flag = 0, etwa 1080i der HD-Sender): für das Deinterlacing. */
    val interlaced: Boolean = false,
) {
    companion object {
        /**
         * Aus der Spur (MKV/MP4: Parametersätze im Container) oder, wenn dort nichts steht, aus dem Schlüsselbild.
         * VP8/VP9/AV1/MPEG-4 brauchen keine Parametersätze vorab (AV1/MPEG-4: optional aus dem Container).
         */
        fun forTrack(track: Track, keyframe: ByteArray): VideoConfig? {
            val inBand = if (track.codec == Codec.H264 || track.codec == Codec.HEVC || track.codec == Codec.MPEG2_VIDEO) from(track.codec, keyframe) else null
            return when {
                inBand != null && (track.csd.isEmpty() || track.width == 0) -> inBand
                track.csd.isNotEmpty() || track.codec !in setOf(Codec.H264, Codec.HEVC, Codec.MPEG2_VIDEO) -> VideoConfig(
                    track.codec.mime,
                    (track.width.takeIf { it > 0 } ?: inBand?.width ?: 1920),
                    (track.height.takeIf { it > 0 } ?: inBand?.height ?: 1080),
                    track.csd.getOrNull(0),
                    track.csd.getOrNull(1),
                    inBand?.bitDepth ?: 8,
                )
                else -> inBand
            }
        }

        /** Aus einem Schlüsselbild (Annex B) die Konfiguration lesen; null, wenn Parametersätze fehlen. */
        fun from(codec: Codec, keyframe: ByteArray): VideoConfig? {
            val nals = nalUnits(keyframe)
            return when (codec) {
                Codec.HEVC -> {
                    val vps = nals.firstOrNull { hevcType(it) == 32 }
                    val sps = nals.firstOrNull { hevcType(it) == 33 } ?: return null
                    val pps = nals.firstOrNull { hevcType(it) == 34 } ?: return null
                    val info = runCatching { HevcSps.parse(sps) }.getOrNull()
                    VideoConfig(
                        codec.mime, info?.width ?: 1920, info?.height ?: 1080,
                        listOfNotNull(vps, sps, pps).fold(ByteArray(0)) { acc, nal -> acc + START + nal }, null,
                        info?.bitDepth ?: 8,
                    )
                }
                Codec.H264 -> {
                    val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 } ?: return null
                    val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 } ?: return null
                    val (w, h) = runCatching { H264Sps.size(sps) }.getOrNull() ?: (1920 to 1080)
                    val interlaced = runCatching { H264Sps.interlaced(sps) }.getOrDefault(false)
                    VideoConfig(codec.mime, w, h, START + sps, START + pps, interlaced = interlaced)
                }
                Codec.MPEG2_VIDEO -> {
                    val i = keyframe.indexOfSequence(byteArrayOf(0, 0, 1, 0xB3.toByte()))
                    if (i < 0 || i + 7 >= keyframe.size) return null
                    val w = ((keyframe[i + 4].toInt() and 0xFF) shl 4) or ((keyframe[i + 5].toInt() and 0xF0) shr 4)
                    val h = ((keyframe[i + 5].toInt() and 0x0F) shl 8) or (keyframe[i + 6].toInt() and 0xFF)
                    VideoConfig(codec.mime, w, h, null, null)
                }
                else -> null
            }
        }

        private val START = byteArrayOf(0, 0, 0, 1)

        private fun hevcType(nal: ByteArray) = (nal[0].toInt() shr 1) and 0x3F

        /**
         * Parametersätze (H.264: SPS/PPS, HEVC: VPS/SPS/PPS) am Anfang einer Zugriffseinheit, vor dem ersten Bild-NAL, als
         * Annex B – oder null. Manche Sender schicken sie nicht in jedem Schlüsselbild mit; gemerkt aus vorigen Bildern
         * kann der Decoder trotzdem beim nächsten Schlüsselbild starten.
         */
        fun parameterSets(codec: Codec, data: ByteArray): ByteArray? {
            if (codec != Codec.H264 && codec != Codec.HEVC) return null
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            var start = -1
            fun close(end: Int) {
                if (start < 0) return
                var e = end
                while (e > start && data[e - 1].toInt() == 0) e--
                if (e > start) {
                    out.write(START)
                    out.write(data, start, e - start)
                }
                start = -1
            }
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    close(i)
                    val h = data[i + 3].toInt() and 0xFF
                    val param = if (codec == Codec.H264) (h and 0x1F) in 7..8 else ((h shr 1) and 0x3F) in 32..34
                    val vcl = if (codec == Codec.H264) (h and 0x1F) in 1..5 else ((h shr 1) and 0x3F) <= 31
                    if (vcl) break
                    if (param) start = i + 3
                    i += 3
                } else i++
            }
            if (start >= 0) close(minOf(i, data.size))
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        /** NAL-Einheiten ohne Startcode. */
        fun nalUnits(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    starts += i + 3
                    i += 3
                } else i++
            }
            return starts.mapIndexedNotNull { k, s ->
                var e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
                while (e > s && data[e - 1].toInt() == 0) e-- // nachfolgende Nullen (4-Byte-Startcode)
                if (e > s) data.copyOfRange(s, e) else null
            }
        }

        private fun ByteArray.indexOfSequence(seq: ByteArray): Int {
            outer@ for (i in 0..size - seq.size) {
                for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
                return i
            }
            return -1
        }
    }

    override fun equals(other: Any?) = other is VideoConfig && mime == other.mime && width == other.width && height == other.height &&
        csd0.contentEquals(other.csd0) && csd1.contentEquals(other.csd1)

    override fun hashCode() = (mime.hashCode() * 31 + width) * 31 + height
}

/** Bildgröße aus einem H.264-SPS (ITU-T H.264 7.3.2.1.1). */
internal object H264Sps {
    /**
     * Ist die Scheibe ab [payload] (Byte nach dem NAL-Kopf eines Nicht-IDR-NALs) eine I- oder SI-Scheibe, und zwar die
     * erste des Bildes? (slice_header: first_mb_in_slice, slice_type – 7.3.3)
     */
    fun isIntraSlice(data: ByteArray, payload: Int): Boolean {
        if (payload >= data.size) return false
        val head = unescape(data.copyOfRange(payload, minOf(data.size, payload + 16)))
        val r = ExpGolomb(head, 0)
        val firstMb = r.ue()
        val sliceType = r.ue()
        return firstMb == 0 && (sliceType % 5 == 2 || sliceType % 5 == 4)
    }

    /** Halbbilder erlaubt (frame_mbs_only_flag = 0)? */
    fun interlaced(nal: ByteArray): Boolean = parse(nal)[2] == 0

    fun size(nal: ByteArray): Pair<Int, Int> = parse(nal).let { it[0] to it[1] }

    /** Breite, Höhe, frame_mbs_only_flag. */
    private fun parse(nal: ByteArray): IntArray {
        val r = ExpGolomb(unescape(nal), 1)
        val profile = r.bits(8)
        r.bits(16) // constraint flags + level
        r.ue() // sps id
        var chromaFormat = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue(); r.ue() // bit depth
            r.bits(1)
            if (r.bits(1) == 1) { // scaling matrices
                repeat(if (chromaFormat != 3) 8 else 12) { k ->
                    if (r.bits(1) == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (k < 6) 16 else 64) {
                            if (next != 0) next = (last + r.se() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.bits(1)
        val widthMbs = r.ue() + 1
        val heightMaps = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMaps * 16
        if (r.bits(1) == 1) { // cropping
            val cropX = if (chromaFormat == 0) 1 else 2
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            width -= (left + right) * cropX
            height -= (top + bottom) * cropY
        }
        return intArrayOf(width, height, frameMbsOnly)
    }

    private fun unescape(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class ExpGolomb(private val d: ByteArray, startByte: Int) {
        private var bit = startByte * 8
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val b = if (bit / 8 < d.size) (d[bit / 8].toInt() shr (7 - bit % 8)) and 1 else 0
                v = (v shl 1) or b
                bit++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0 && zeros < 31) zeros++
            return (1 shl zeros) - 1 + bits(zeros)
        }
        fun se(): Int {
            val v = ue()
            return if (v % 2 == 1) (v + 1) / 2 else -(v / 2)
        }
    }
}

package de.ayutv.player.dna.engine

import java.nio.ByteBuffer

/**
 * Eigener Software-Video-Decoder der DNA Engine (FFmpeg HEVC/H.264, alle Kerne) – wie VLC, wenn kein Decoder des
 * Geräts ein Bild liefert. Liefert 8-Bit-YUV 4:2:0 (auf höchstens maxW × maxH verkleinert); angezeigt wird über
 * [GlYuvRenderer]. Nicht threadsicher: ein Decoder gehört einem Thread.
 */
internal object NativeVideo {
    const val NO_FRAME = Long.MIN_VALUE
    const val SEND_OK = 0
    const val SEND_FULL = 1
    const val SEND_ERROR = -1

    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("dnaremux")
            true
        }.getOrDefault(false)
    }

    fun supports(mime: String): Boolean = available && when (mime) {
        Codec.HEVC.mime, Codec.H264.mime -> true
        else -> false
    }

    /** Sparstufe bis zum Sprungziel (ohne Folgen für die Bilder danach), siehe [Decoder.hurry]. */
    const val HURRY_SEEK = 9
    /** Höchste Sparstufe für [Decoder.hurry]. */
    const val HURRY_MAX = 3
    /** AVColorTransferCharacteristic: SMPTE ST 2084 (PQ, HDR10). */
    const val TRC_PQ = 16

    /**
     * Ausgabe eines Bildes: Größe, Farbraum (AVColorSpace), Übertragung (AVColorTransferCharacteristic), Wertebereich.
     * Wiederverwendbar: [Decoder.convert] füllt ein übergebenes Bild, wenn seine Ebenen groß genug sind.
     */
    class Frame(var width: Int, var height: Int, var colorSpace: Int, var transfer: Int, var fullRange: Boolean,
                var y: ByteBuffer, var u: ByteBuffer, var v: ByteBuffer) {
        /** Darstellungszeit (µs), vom Aufrufer gesetzt. */
        var ptsUs: Long = 0L
        /** Ebenen als Halbfließkomma (10 Bit für HDR, 2 Bytes je Wert) statt 8 Bit. */
        var half: Boolean = false
    }

    class Decoder internal constructor(private var handle: Long) {
        private val info = IntArray(5)
        private val empty: ByteBuffer = ByteBuffer.allocateDirect(1)

        fun send(data: ByteArray, ptsUs: Long): Int = if (handle == 0L) SEND_ERROR else nativeSend(handle, data, data.size, ptsUs)

        /** Zeitstempel des nächsten fertigen Bildes (es bleibt gehalten), sonst [NO_FRAME]. */
        fun receive(): Long = if (handle == 0L) NO_FRAME else nativeReceive(handle)

        /**
         * Gehaltenes Bild in YUV-Ebenen umrechnen – in [into], wenn dessen Ebenen reichen (keine neuen Puffer je Bild),
         * sonst in ein neues Bild. null bei Fehler.
         */
        fun convert(into: Frame? = null): Frame? {
            if (handle == 0L) return null
            var y = into?.takeIf { !it.half }?.y ?: empty
            var u = into?.takeIf { !it.half }?.u ?: empty
            var v = into?.takeIf { !it.half }?.v ?: empty
            var ret = nativeConvert(handle, y, u, v, info)
            if (ret == 0) {
                val w = info[0]
                val h = info[1]
                y = ByteBuffer.allocateDirect(w * h)
                u = ByteBuffer.allocateDirect((w / 2) * (h / 2))
                v = ByteBuffer.allocateDirect((w / 2) * (h / 2))
                ret = nativeConvert(handle, y, u, v, info)
            }
            if (ret != 1) return null
            y.position(0); u.position(0); v.position(0)
            val frame = into ?: Frame(0, 0, 0, 0, false, y, u, v)
            frame.width = info[0]
            frame.height = info[1]
            frame.colorSpace = info[2]
            frame.transfer = info[3]
            frame.fullRange = info[4] == 1
            frame.y = y
            frame.u = u
            frame.v = v
            frame.half = false
            return frame
        }

        /** Gehaltenes Bild ist 10 Bit mit PQ (HDR10)? */
        fun heldIsPq10(): Boolean {
            if (handle == 0L) return false
            val f = IntArray(3)
            return nativeHeldFormat(handle, f) == 1 && f[0] == 10 && f[2] == TRC_PQ
        }

        /**
         * Wie [convert], aber 10 Bit als Halbfließkomma (für die HDR-Anzeige, ohne 8-Bit-Stufen). null, wenn das Bild
         * dafür nicht passt (kein 10 Bit 4:2:0 in voller/halber Größe) oder bei Fehler.
         */
        fun convertHalf(into: Frame? = null): Frame? {
            if (handle == 0L) return null
            var y = into?.takeIf { it.half }?.y ?: empty
            var u = into?.takeIf { it.half }?.u ?: empty
            var v = into?.takeIf { it.half }?.v ?: empty
            var ret = nativeConvertHalf(handle, y, u, v, info)
            if (ret == 0) {
                val w = info[0]
                val h = info[1]
                y = ByteBuffer.allocateDirect(w * h * 2)
                u = ByteBuffer.allocateDirect((w / 2) * (h / 2) * 2)
                v = ByteBuffer.allocateDirect((w / 2) * (h / 2) * 2)
                ret = nativeConvertHalf(handle, y, u, v, info)
            }
            if (ret != 1) return null
            y.position(0); u.position(0); v.position(0)
            val frame = into ?: Frame(0, 0, 0, 0, false, y, u, v)
            frame.width = info[0]
            frame.height = info[1]
            frame.colorSpace = info[2]
            frame.transfer = info[3]
            frame.fullRange = info[4] == 1
            frame.y = y
            frame.u = u
            frame.v = v
            frame.half = true
            return frame
        }

        fun drop() { if (handle != 0L) nativeDrop(handle) }
        fun hurry(level: Int) { if (handle != 0L) nativeHurry(handle, level) }
        fun flush() { if (handle != 0L) nativeFlush(handle) }

        fun release() {
            if (handle != 0L) nativeClose(handle)
            handle = 0
        }
    }

    fun open(mime: String, extra: ByteArray?, maxWidth: Int, maxHeight: Int, threads: Int): Decoder? {
        if (!available) return null
        val name = when (mime) {
            Codec.HEVC.mime -> "hevc"
            Codec.H264.mime -> "h264"
            else -> return null
        }
        val handle = runCatching { nativeOpen(name, extra, maxWidth, maxHeight, threads) }.getOrDefault(0L)
        return if (handle == 0L) null else Decoder(handle)
    }

    @JvmStatic private external fun nativeOpen(codec: String, extra: ByteArray?, maxW: Int, maxH: Int, threads: Int): Long
    @JvmStatic private external fun nativeSend(handle: Long, data: ByteArray, len: Int, pts: Long): Int
    @JvmStatic private external fun nativeReceive(handle: Long): Long
    @JvmStatic private external fun nativeConvert(handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, info: IntArray): Int
    @JvmStatic private external fun nativeConvertHalf(handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, info: IntArray): Int
    @JvmStatic private external fun nativeHeldFormat(handle: Long, info: IntArray): Int
    @JvmStatic private external fun nativeDrop(handle: Long)
    @JvmStatic private external fun nativeHurry(handle: Long, level: Int)
    @JvmStatic private external fun nativeFlush(handle: Long)
    @JvmStatic private external fun nativeClose(handle: Long)
}

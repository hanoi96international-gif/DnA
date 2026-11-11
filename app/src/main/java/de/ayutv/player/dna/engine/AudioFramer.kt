package de.ayutv.player.dna.engine




/**
 * Zerlegt die Nutzdaten der Ton-PES-Pakete in einzelne Rahmen, auch über Paketgrenzen hinweg, mit Zeitstempel je
 * Rahmen: Der erste Rahmen, der in einem PES beginnt, bekommt dessen PTS, jeder weitere die Zeit des vorigen plus
 * dessen Dauer. Kaputte Rahmen werden übersprungen (bis zum nächsten Syncwort).
 *
 * AAC kommt roh heraus (ohne ADTS-/LATM-Kopf) mit AudioSpecificConfig für den Decoder; AC-3, E-AC-3 und MPEG-Audio
 * vollständig (Decoder und Durchreichen an den Receiver brauchen den Rahmenkopf). Abhängige E-AC-3-Teilströme
 * (7.1-Erweiterung) bleiben am Rahmen, zu dem sie gehören.
 */
private val DTS_INFO = AudioInfo(0, 0, 0)

internal class AudioFramer(private val track: Track) {
    private var buffer = ByteArray(32 * 1024)
    private var length = 0
    private var lastTimeUs = TsDemuxer.NO_TIME
    private var lastDurationUs = 0L
    /** LATM: zuletzt gelesene Konfiguration (useSameStreamMux verweist darauf). */
    private var latmConfig: LatmConfig? = null
    /** E-AC-3: unabhängiger Rahmen, an den noch abhängige Teilströme angehängt werden können. */
    private var pendingEac3: Triple<ByteArray, Long, AudioInfo>? = null

    fun reset() {
        length = 0
        lastTimeUs = TsDemuxer.NO_TIME
        pendingEac3 = null
    }

    fun frames(payload: ByteArray, pesTimeUs: Long, emit: (ByteArray, Long, AudioInfo) -> Unit) {
        // DTS: das ganze PES-Paket als ein Stück (der eigene Ton-Decoder zerlegt es in Rahmen).
        if (track.codec == Codec.DTS) {
            if (pesTimeUs != TsDemuxer.NO_TIME && payload.isNotEmpty()) emit(payload, pesTimeUs, DTS_INFO)
            return
        }
        val newStart = length
        if (length + payload.size > buffer.size) buffer = buffer.copyOf(maxOf(length + payload.size, buffer.size * 2))
        System.arraycopy(payload, 0, buffer, length, payload.size)
        length += payload.size
        var ptsPending = pesTimeUs
        var pos = 0
        while (pos < length) {
            val frame = parse(pos) ?: break // Rahmen noch nicht vollständig
            if (frame.size <= 0) { // kein Syncwort hier
                pos++
                continue
            }
            if (pos + frame.size > length) break
            val time = if (ptsPending != TsDemuxer.NO_TIME && pos >= newStart - 0) {
                ptsPending.also { ptsPending = TsDemuxer.NO_TIME }
            } else if (lastTimeUs != TsDemuxer.NO_TIME) lastTimeUs + lastDurationUs else TsDemuxer.NO_TIME
            if (time != TsDemuxer.NO_TIME && frame.format != null) {
                val bytes = frame.extract()
                if (track.codec == Codec.EAC3) {
                    if (frame.dependent) {
                        pendingEac3?.let { (data, t, f) -> pendingEac3 = Triple(data + bytes, t, f) }
                    } else {
                        pendingEac3?.let { (data, t, f) -> emit(data, t, f) }
                        pendingEac3 = Triple(bytes, time, frame.format)
                        lastTimeUs = time
                        lastDurationUs = frame.format.samplesPerFrame * 1_000_000L / frame.format.sampleRate
                    }
                } else {
                    emit(bytes, time, frame.format)
                    lastTimeUs = time
                    lastDurationUs = frame.format.samplesPerFrame * 1_000_000L / frame.format.sampleRate
                }
            }
            pos += frame.size
        }
        // E-AC-3: Rahmen ausgeben, sobald sicher ist, dass kein abhängiger Teil mehr folgt (neuer Rahmen im Puffer).
        if (track.codec == Codec.EAC3 && pos >= length) pendingEac3?.let { (data, t, f) -> emit(data, t, f); pendingEac3 = null }
        // Verbrauchtes vorn abschneiden.
        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, length - pos)
            length -= pos
        }
        if (length > MAX_LEFTOVER) length = 0 // nie endlos sammeln (kaputter Strom)
    }

    /** Ein Rahmen ab [pos]: Größe (0 = kein Syncwort), Format, Nutzdaten. null = Kopf noch nicht vollständig. */
    private class Frame(val size: Int, val format: AudioInfo?, val extract: () -> ByteArray, val dependent: Boolean = false)

    private fun parse(pos: Int): Frame? {
        val b = buffer
        val left = length - pos
        return when (track.codec) {
            Codec.AAC_ADTS -> {
                if (left < 7) return null
                if (b[pos].toInt() and 0xFF != 0xFF || b[pos + 1].toInt() and 0xF0 != 0xF0) return NO_FRAME
                val protectionAbsent = b[pos + 1].toInt() and 1 == 1
                val profile = (b[pos + 2].toInt() shr 6) and 3
                val sfi = (b[pos + 2].toInt() shr 2) and 0x0F
                val channels = ((b[pos + 2].toInt() and 1) shl 2) or ((b[pos + 3].toInt() shr 6) and 3)
                val size = ((b[pos + 3].toInt() and 3) shl 11) or ((b[pos + 4].toInt() and 0xFF) shl 3) or ((b[pos + 5].toInt() shr 5) and 7)
                val header = if (protectionAbsent) 7 else 9
                val rate = AAC_RATES.getOrNull(sfi) ?: return NO_FRAME
                if (size <= header) return NO_FRAME
                val config = byteArrayOf(
                    (((profile + 1) shl 3) or (sfi shr 1)).toByte(),
                    (((sfi and 1) shl 7) or (channels shl 3)).toByte(),
                )
                Frame(size, AudioInfo(rate, channels.coerceAtLeast(1), 1024, config), { b.copyOfRange(pos + header, pos + size) })
            }
            Codec.AAC_LATM -> {
                if (left < 3) return null
                val sync = ((b[pos].toInt() and 0xFF) shl 3) or ((b[pos + 1].toInt() and 0xE0) shr 5)
                if (sync != 0x2B7) return NO_FRAME
                val muxLength = ((b[pos + 1].toInt() and 0x1F) shl 8) or (b[pos + 2].toInt() and 0xFF)
                val size = 3 + muxLength
                if (left < size) return null
                val parsed = runCatching { Latm.parse(b, pos + 3, muxLength, latmConfig) }.getOrNull() ?: return Frame(size, null, { ByteArray(0) })
                latmConfig = parsed.config
                val asc = parsed.config.asc
                Frame(size, AudioInfo(asc.sampleRate, asc.channels.coerceAtLeast(1), asc.samplesPerFrame, asc.bytes), { parsed.payload })
            }
            Codec.AC3, Codec.EAC3 -> {
                if (left < 6) return null
                if (b[pos].toInt() and 0xFF != 0x0B || b[pos + 1].toInt() and 0xFF != 0x77) return NO_FRAME
                val bsid = (b[pos + 5].toInt() and 0xFF) shr 3
                if (bsid > 10) {
                    // E-AC-3
                    val strmtyp = (b[pos + 2].toInt() shr 6) and 3
                    val frmsiz = ((b[pos + 2].toInt() and 7) shl 8) or (b[pos + 3].toInt() and 0xFF)
                    val size = (frmsiz + 1) * 2
                    val fscod = (b[pos + 4].toInt() shr 6) and 3
                    val rate: Int
                    val blocks: Int
                    if (fscod == 3) {
                        val fscod2 = (b[pos + 4].toInt() shr 4) and 3
                        rate = intArrayOf(24_000, 22_050, 16_000).getOrNull(fscod2) ?: return Frame(size, null, { ByteArray(0) })
                        blocks = 6
                    } else {
                        rate = AC3_RATES[fscod]
                        blocks = intArrayOf(1, 2, 3, 6)[(b[pos + 4].toInt() shr 4) and 3]
                    }
                    val acmod = (b[pos + 4].toInt() shr 1) and 7
                    val lfe = b[pos + 4].toInt() and 1
                    Frame(size, AudioInfo(rate, AC3_CHANNELS[acmod] + lfe, blocks * 256), { b.copyOfRange(pos, pos + size) }, dependent = strmtyp == 1)
                } else {
                    val fscod = (b[pos + 4].toInt() shr 6) and 3
                    val frmsizecod = b[pos + 4].toInt() and 0x3F
                    if (fscod == 3 || frmsizecod >= 38) return NO_FRAME
                    val size = ac3FrameSize(fscod, frmsizecod)
                    // acmod und lfeon liegen je nach acmod an verschiedenen Bitstellen (BSI).
                    val bits = BitReader(b, pos + 6, minOf(length, pos + 8))
                    val acmod = bits.read(3)
                    if (acmod and 1 != 0 && acmod != 1) bits.skip(2)
                    if (acmod and 4 != 0) bits.skip(2)
                    if (acmod == 2) bits.skip(2)
                    val lfe = bits.read(1)
                    Frame(size, AudioInfo(AC3_RATES[fscod], AC3_CHANNELS[acmod] + lfe, 1536), { b.copyOfRange(pos, pos + size) })
                }
            }
            Codec.MPEG_AUDIO -> {
                if (left < 4) return null
                val h = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                    ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
                if (h ushr 21 != 0x7FF) return NO_FRAME
                val version = (h shr 19) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val layer = 4 - ((h shr 17) and 3) // 1..3
                val bitrateIndex = (h shr 12) and 0x0F
                val rateIndex = (h shr 10) and 3
                val padding = (h shr 9) and 1
                val mode = (h shr 6) and 3
                if (version == 1 || layer == 4 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return NO_FRAME
                val rate = MPEG_RATES[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
                val bitrate = mpegBitrate(version == 3, layer, bitrateIndex) * 1000
                val samples = when {
                    layer == 1 -> 384
                    layer == 3 && version != 3 -> 576
                    else -> 1152
                }
                val size = if (layer == 1) (12 * bitrate / rate + padding) * 4 else samples / 8 * bitrate / rate + padding
                if (size < 4) return NO_FRAME
                Frame(size, AudioInfo(rate, if (mode == 3) 1 else 2, samples), { b.copyOfRange(pos, pos + size) })
            }
            else -> NO_FRAME
        }
    }

    companion object {
        private val NO_FRAME = Frame(0, null, { ByteArray(0) })
        private const val MAX_LEFTOVER = 256 * 1024
        val AAC_RATES = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
        private val AC3_RATES = intArrayOf(48_000, 44_100, 32_000)
        private val AC3_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
        private val MPEG_RATES = intArrayOf(44_100, 48_000, 32_000)

        /** Größe eines AC-3-Rahmens in Bytes (ATSC A/52 Tabelle 5.18). */
        fun ac3FrameSize(fscod: Int, frmsizecod: Int): Int {
            val bitrate = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)[frmsizecod / 2]
            return when (fscod) {
                0 -> bitrate * 2 * 2 // 48 kHz: 2 Wörter je kbit/s
                1 -> (bitrate * 2 * 48_000 / 44_100 + (frmsizecod and 1)) * 2 // 44,1 kHz (Näherung der Tabelle: +1 Wort bei ungeradem Code)
                else -> bitrate * 3 * 2 // 32 kHz
            }
        }

        private fun mpegBitrate(mpeg1: Boolean, layer: Int, index: Int): Int {
            val table = when {
                mpeg1 && layer == 1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
                mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
                mpeg1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
                else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
            }
            return table[index]
        }
    }
}

/** Liest Bits ab einem Byte-Offset (für Rahmenköpfe und LATM). */
internal class BitReader(private val data: ByteArray, start: Int, private val end: Int) {
    var bit = start * 8L
        private set

    fun read(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = (bit / 8).toInt()
            val b = if (byte < end) data[byte].toInt() and 0xFF else 0
            v = (v shl 1) or ((b shr (7 - (bit % 8).toInt())) and 1)
            bit++
        }
        return v
    }

    fun skip(n: Int) {
        bit += n
    }

    fun position(): Long = bit

    /** Bits [from] bis [to] als Bytes (links ausgerichtet, mit Nullen aufgefüllt). */
    fun copyBits(from: Long, to: Long): ByteArray {
        val count = (to - from).toInt()
        val out = ByteArray((count + 7) / 8)
        val saved = bit
        bit = from
        for (i in 0 until count) {
            if (read(1) == 1) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        bit = saved
        return out
    }
}

/** AudioSpecificConfig: was der AAC-Decoder als csd-0 braucht. */
internal class AudioSpecificConfig(val bytes: ByteArray, val sampleRate: Int, val channels: Int, val samplesPerFrame: Int)

internal class LatmConfig(val asc: AudioSpecificConfig, val frameLengthType: Int)

/** AAC in LATM/LOAS (DVB): StreamMuxConfig und Nutzdaten aus einem AudioMuxElement (ISO/IEC 14496-3, 1.7). */
internal object Latm {
    class Result(val config: LatmConfig, val payload: ByteArray)

    fun parse(b: ByteArray, start: Int, length: Int, previous: LatmConfig?): Result? {
        val r = BitReader(b, start, start + length)
        val useSame = r.read(1) == 1
        val config = if (useSame) previous ?: return null else streamMuxConfig(r) ?: return null
        if (config.frameLengthType != 0) return null
        // PayloadLengthInfo: Bytes in Schritten von 255.
        var payloadLength = 0
        while (true) {
            val tmp = r.read(8)
            payloadLength += tmp
            if (tmp != 255) break
        }
        if (payloadLength <= 0 || payloadLength > length) return null
        val payload = r.copyBits(r.position(), r.position() + payloadLength * 8L)
        return Result(config, payload)
    }

    private fun streamMuxConfig(r: BitReader): LatmConfig? {
        val audioMuxVersion = r.read(1)
        val versionA = if (audioMuxVersion == 1) r.read(1) else 0
        if (versionA != 0) return null
        if (audioMuxVersion == 1) latmValue(r) // taraBufferFullness
        r.skip(1) // allStreamsSameTimeFraming
        r.skip(6) // numSubFrames
        r.skip(4) // numProgram
        r.skip(3) // numLayer
        val asc = if (audioMuxVersion == 0) {
            audioSpecificConfig(r)
        } else {
            val ascLength = latmValue(r)
            val begin = r.position()
            val parsed = audioSpecificConfig(r)
            r.skip((ascLength - (r.position() - begin)).toInt().coerceAtLeast(0))
            parsed
        } ?: return null
        val frameLengthType = r.read(3)
        when (frameLengthType) {
            0 -> r.skip(8) // latmBufferFullness
            1 -> r.skip(9)
            3, 4, 5 -> r.skip(6)
            6, 7 -> r.skip(1)
        }
        if (r.read(1) == 1) { // otherDataPresent
            if (audioMuxVersion == 1) latmValue(r) else {
                var more: Int
                do {
                    more = r.read(1)
                    r.skip(8)
                } while (more == 1)
            }
        }
        if (r.read(1) == 1) r.skip(8) // crcCheckPresent + crcCheckSum
        return LatmConfig(asc, frameLengthType)
    }

    private fun latmValue(r: BitReader): Int {
        val bytes = r.read(2) + 1
        var v = 0
        repeat(bytes) { v = (v shl 8) or r.read(8) }
        return v
    }

    /** AudioSpecificConfig lesen und ihre Bits als csd-0 kopieren. */
    fun audioSpecificConfig(r: BitReader): AudioSpecificConfig? {
        val begin = r.position()
        fun objectType(): Int {
            val t = r.read(5)
            return if (t == 31) 32 + r.read(6) else t
        }
        var aot = objectType()
        val sfi = r.read(4)
        var rate = if (sfi == 15) r.read(24) else AudioFramer.AAC_RATES.getOrNull(sfi) ?: return null
        val channelConfig = r.read(4)
        var sbr = false
        if (aot == 5 || aot == 29) { // SBR/PS: echte Abtastrate am Ausgang doppelt
            sbr = true
            val extSfi = r.read(4)
            rate = if (extSfi == 15) r.read(24) else AudioFramer.AAC_RATES.getOrNull(extSfi) ?: rate
            aot = objectType()
        }
        when (aot) {
            1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23 -> {
                val frameLengthFlag = r.read(1)
                if (r.read(1) == 1) r.skip(14) // dependsOnCoreCoder + coreCoderDelay
                val extensionFlag = r.read(1)
                if (channelConfig == 0) return null // program_config_element: im Rundfunk unüblich
                if (aot == 6 || aot == 20) r.skip(3)
                if (extensionFlag == 1) {
                    if (aot == 22) r.skip(16)
                    if (aot in 17..23) r.skip(3)
                    r.skip(1)
                }
                val channels = when (channelConfig) { 7 -> 8; in 1..6 -> channelConfig; else -> 2 }
                val samples = (if (frameLengthFlag == 1) 960 else 1024) * if (sbr) 2 else 1
                return AudioSpecificConfig(r.copyBits(begin, r.position()), rate, channels, samples)
            }
            else -> return null
        }
    }
}

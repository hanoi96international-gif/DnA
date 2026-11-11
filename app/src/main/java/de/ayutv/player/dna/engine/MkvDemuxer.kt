package de.ayutv.player.dna.engine

import java.io.EOFException
import java.io.IOException

/**
 * DNA Engine: eigener Leser für Matroska/WebM (MKV) – Spuren, Proben in Dateireihenfolge, Spulen über das
 * Cue-Verzeichnis (ohne Verzeichnis: Schätzung nach Dateiposition und Suche des nächsten Clusters).
 *
 * Bild: H.264/HEVC (Längenfelder werden zu Startcodes), MPEG-2/-4, VP8/VP9, AV1. Ton: AAC, AC-3, E-AC-3, DTS, TrueHD,
 * MP3/MP2, Opus, Vorbis, FLAC, PCM. Untertitel: SRT, ASS/SSA, WebVTT, PGS, VobSub, DVB. Header-Stripping und zlib.
 */
class MkvDemuxer(private val reader: SourceReader) : ContainerDemuxer {
    private class TrackState(
        val track: Track,
        val stripPrefix: ByteArray?,
        val zlib: Boolean,
        /** Dauer eines Rahmens (ns), 0 = unbekannt. */
        val defaultDurationNs: Long,
    )

    private class Cue(val timeUs: Long, val clusterPosition: Long, val track: Int)

    private var segmentStart = 0L
    private var segmentEnd = Long.MAX_VALUE
    private var timecodeScale = 1_000_000L
    private var durationUs = -1L
    private val tracks = LinkedHashMap<Int, TrackState>()
    private val cues = ArrayList<Cue>()
    private var cuesPosition = -1L
    private var firstClusterPosition = -1L
    private var clusterTimecode = 0L
    private val pending = ArrayDeque<Sample>()

    override fun init(): ContainerDemuxer.Info {
        val header = readId()
        if (header != ID_EBML) throw IOException("kein Matroska")
        reader.skip(readSize())
        while (true) {
            val id = readId()
            val size = readSize()
            if (id == ID_SEGMENT) {
                segmentStart = reader.position
                segmentEnd = if (size == UNKNOWN) Long.MAX_VALUE else segmentStart + size
                break
            }
            reader.skip(size)
        }
        // Kopf bis zum ersten Cluster.
        while (true) {
            val at = reader.position
            if (at >= segmentEnd || reader.atEnd()) break
            val id = readId()
            val size = readSize()
            when (id) {
                ID_SEEK_HEAD -> parseSeekHead(reader.position + size)
                ID_INFO -> parseInfo(reader.position + size)
                ID_TRACKS -> parseTracks(reader.position + size)
                ID_CUES -> parseCues(reader.position + size)
                ID_CLUSTER -> {
                    firstClusterPosition = at
                    reader.seek(at)
                    break
                }
                else -> if (size == UNKNOWN) break else reader.skip(size)
            }
        }
        // Verzeichnis liegt meist am Ende: kurz hinspringen und zurück.
        if (cues.isEmpty() && cuesPosition > 0 && reader.length > 0 && firstClusterPosition > 0) {
            runCatching {
                reader.seek(cuesPosition)
                if (readId() == ID_CUES) {
                    val size = readSize()
                    parseCues(reader.position + size)
                }
            }
            reader.seek(firstClusterPosition)
        }
        // Nur Einträge des Bildes zählen (ffmpeg setzt auch welche für Untertitel – dort ist kein Schlüsselbild).
        tracks.values.firstOrNull { it.track.codec.kind == Kind.VIDEO }?.track?.id?.let { videoId ->
            val videoCues = cues.filter { it.track == videoId || it.track < 0 }
            if (videoCues.isNotEmpty()) {
                cues.clear()
                cues.addAll(videoCues)
            }
        }
        val list = tracks.values.map { it.track }
        return ContainerDemuxer.Info(list, durationUs, cues.isNotEmpty() || reader.length > 0)
    }

    override fun read(): Sample? {
        while (true) {
            pending.removeFirstOrNull()?.let { return it }
            if (reader.position >= segmentEnd) return null
            val id = try {
                readId()
            } catch (e: EOFException) {
                return null
            }
            if (id < 0) return null
            val size = try { readSize() } catch (e: EOFException) { return null }
            when (id) {
                ID_CLUSTER -> clusterTimecode = 0 // Inhalt folgt (bekannte oder unbekannte Größe)
                ID_TIMECODE -> clusterTimecode = readUInt(size.toInt())
                ID_SIMPLE_BLOCK -> readBlock(size.toInt(), simple = true, durationTicks = -1, reference = false)
                ID_BLOCK_GROUP -> readBlockGroup(reader.position + size)
                ID_SEGMENT -> Unit // verschachtelt (verkettete Dateien): weiterlesen
                else -> {
                    if (size == UNKNOWN) continue
                    reader.skip(size)
                }
            }
        }
    }

    override fun seek(timeUs: Long): Long {
        pending.clear()
        if (cues.isNotEmpty()) {
            val cue = cues.lastOrNull { it.timeUs <= timeUs } ?: cues.first()
            reader.seek(segmentStart + cue.clusterPosition)
            return cue.timeUs
        }
        // Ohne Verzeichnis: nach Anteil an der Datei schätzen, dann den nächsten Cluster suchen.
        val start = if (firstClusterPosition > 0) firstClusterPosition else segmentStart
        if (timeUs <= 0 || durationUs <= 0 || reader.length <= 0) {
            reader.seek(start)
            return 0
        }
        val target = start + ((reader.length - start) * (timeUs.toDouble() / durationUs)).toLong()
        reader.seek(target.coerceIn(start, reader.length - 4))
        if (!syncToCluster()) reader.seek(start)
        return timeUs
    }

    /** Bis zur nächsten Cluster-Kennung lesen (1F 43 B6 75). */
    private fun syncToCluster(): Boolean {
        var window = 0
        var scanned = 0L
        while (scanned < 32L * 1024 * 1024) {
            val b = reader.readByte()
            if (b < 0) return false
            window = (window shl 8) or b
            scanned++
            if (window == ID_CLUSTER) {
                reader.seek(reader.position - 4)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- Kopf

    private fun parseSeekHead(end: Long) {
        while (reader.position < end) {
            val id = readId()
            val size = readSize()
            if (id != ID_SEEK) {
                reader.skip(size); continue
            }
            val seekEnd = reader.position + size
            var target = -1L
            var position = -1L
            while (reader.position < seekEnd) {
                val cid = readId()
                val csize = readSize().toInt()
                when (cid) {
                    ID_SEEK_ID -> target = readUInt(csize)
                    ID_SEEK_POSITION -> position = readUInt(csize)
                    else -> reader.skip(csize.toLong())
                }
            }
            if (target == ID_CUES.toLong() && position >= 0) cuesPosition = segmentStart + position
        }
    }

    private fun parseInfo(end: Long) {
        var rawDuration = -1.0
        while (reader.position < end) {
            val id = readId()
            val size = readSize().toInt()
            when (id) {
                ID_TIMECODE_SCALE -> timecodeScale = readUInt(size)
                ID_DURATION -> rawDuration = readFloat(size)
                else -> reader.skip(size.toLong())
            }
        }
        if (rawDuration > 0) durationUs = (rawDuration * timecodeScale / 1000).toLong()
    }

    private fun parseTracks(end: Long) {
        while (reader.position < end) {
            val id = readId()
            val size = readSize()
            if (id == ID_TRACK_ENTRY) parseTrackEntry(reader.position + size)?.let { tracks[it.track.id] = it } else reader.skip(size)
        }
    }

    private fun parseTrackEntry(end: Long): TrackState? {
        var number = -1
        var type = 0
        var codecId = ""
        var private: ByteArray? = null
        var language: String? = "eng" // Matroska-Standard, wenn nichts angegeben ist
        var bcp47: String? = null
        var name: String? = null
        var isDefault = true
        var forced = false
        var width = 0
        var height = 0
        var rate = 8000.0
        var channels = 1
        var bitDepth = 0
        var hdr = false
        var defaultDurationNs = 0L
        var codecDelayNs = 0L
        var seekPreRollNs = 0L
        var stripPrefix: ByteArray? = null
        var zlib = false
        while (reader.position < end) {
            val id = readId()
            val size = readSize()
            val n = size.toInt()
            when (id) {
                ID_TRACK_NUMBER -> number = readUInt(n).toInt()
                ID_TRACK_TYPE -> type = readUInt(n).toInt()
                ID_CODEC_ID -> codecId = readString(n)
                ID_CODEC_PRIVATE -> private = reader.readBytes(n)
                ID_LANGUAGE -> language = readString(n)
                ID_LANGUAGE_BCP47 -> bcp47 = readString(n)
                ID_NAME -> name = readString(n)
                ID_FLAG_DEFAULT -> isDefault = readUInt(n) != 0L
                ID_FLAG_FORCED -> forced = readUInt(n) != 0L
                ID_DEFAULT_DURATION -> defaultDurationNs = readUInt(n)
                ID_CODEC_DELAY -> codecDelayNs = readUInt(n)
                ID_SEEK_PRE_ROLL -> seekPreRollNs = readUInt(n)
                ID_VIDEO -> {
                    val vEnd = reader.position + size
                    while (reader.position < vEnd) {
                        val vid = readId()
                        val vsize = readSize()
                        when (vid) {
                            ID_PIXEL_WIDTH -> width = readUInt(vsize.toInt()).toInt()
                            ID_PIXEL_HEIGHT -> height = readUInt(vsize.toInt()).toInt()
                            ID_COLOUR -> {
                                val cEnd = reader.position + vsize
                                while (reader.position < cEnd) {
                                    val cid = readId()
                                    val csize = readSize().toInt()
                                    if (cid == ID_TRANSFER) {
                                        val t = readUInt(csize).toInt()
                                        hdr = t == 16 || t == 18
                                    } else reader.skip(csize.toLong())
                                }
                            }
                            else -> reader.skip(vsize)
                        }
                    }
                }
                ID_AUDIO -> {
                    val aEnd = reader.position + size
                    var output = 0.0
                    while (reader.position < aEnd) {
                        val aid = readId()
                        val asize = readSize().toInt()
                        when (aid) {
                            ID_SAMPLING_FREQUENCY -> rate = readFloat(asize)
                            ID_OUTPUT_SAMPLING_FREQUENCY -> output = readFloat(asize)
                            ID_CHANNELS -> channels = readUInt(asize).toInt()
                            ID_BIT_DEPTH -> bitDepth = readUInt(asize).toInt()
                            else -> reader.skip(asize.toLong())
                        }
                    }
                    if (output > 0) rate = output
                }
                ID_CONTENT_ENCODINGS -> {
                    val eEnd = reader.position + size
                    while (reader.position < eEnd) {
                        val eid = readId()
                        val esize = readSize()
                        if (eid != ID_CONTENT_ENCODING) { reader.skip(esize); continue }
                        val ceEnd = reader.position + esize
                        while (reader.position < ceEnd) {
                            val cid = readId()
                            val csize = readSize()
                            if (cid != ID_CONTENT_COMPRESSION) { reader.skip(csize); continue }
                            val ccEnd = reader.position + csize
                            var algo = 0
                            var settings: ByteArray? = null
                            while (reader.position < ccEnd) {
                                val xid = readId()
                                val xsize = readSize().toInt()
                                when (xid) {
                                    ID_CONTENT_COMP_ALGO -> algo = readUInt(xsize).toInt()
                                    ID_CONTENT_COMP_SETTINGS -> settings = reader.readBytes(xsize)
                                    else -> reader.skip(xsize.toLong())
                                }
                            }
                            if (algo == 3) stripPrefix = settings else if (algo == 0) zlib = true
                        }
                    }
                }
                else -> reader.skip(size)
            }
        }
        if (number < 0) return null
        val lang = normalizeLanguage(bcp47 ?: language)
        val track = buildTrack(
            number, type, codecId, private, lang, name, isDefault, forced, width, height, rate.toInt(), channels, bitDepth,
            hdr, codecDelayNs, seekPreRollNs,
        ) ?: return null
        return TrackState(track, stripPrefix, zlib, defaultDurationNs)
    }

    private fun buildTrack(
        number: Int, type: Int, codecId: String, private: ByteArray?, language: String?, name: String?, isDefault: Boolean,
        forced: Boolean, width: Int, height: Int, rate: Int, channels: Int, bitDepth: Int, hdr: Boolean, codecDelayNs: Long,
        seekPreRollNs: Long,
    ): Track? {
        fun video(codec: Codec, csd: List<ByteArray> = emptyList(), nal: Int = 0, w: Int = width, h: Int = height) =
            Track(number, codec, language, name, csd, w.takeIf { it > 0 } ?: width, h.takeIf { it > 0 } ?: height, nalLengthSize = nal,
                isDefault = isDefault, forced = forced, hdr = hdr)
        fun audio(codec: Codec, csd: List<ByteArray> = emptyList(), extra: ByteArray? = null) =
            Track(number, codec, language, name, csd, sampleRate = rate, channels = channels, isDefault = isDefault, forced = forced, extra = extra)
        fun text(codec: Codec, extra: ByteArray? = private) = Track(number, codec, language, name, isDefault = isDefault, forced = forced, extra = extra)
        return when {
            codecId == "V_MPEG4/ISO/AVC" -> private?.let(NalUnits::avcC)?.let { video(Codec.H264, it.csd, it.nalLengthSize, it.width, it.height) }
            codecId == "V_MPEGH/ISO/HEVC" -> private?.let(NalUnits::hvcC)?.let { video(Codec.HEVC, it.csd, it.nalLengthSize, it.width, it.height) }
            codecId == "V_MPEG2" || codecId == "V_MPEG1" -> video(Codec.MPEG2_VIDEO)
            codecId.startsWith("V_MPEG4/ISO/") -> video(Codec.MPEG4_VIDEO, listOfNotNull(private))
            codecId == "V_VP8" -> video(Codec.VP8)
            codecId == "V_VP9" -> video(Codec.VP9, listOfNotNull(private))
            codecId == "V_AV1" -> video(Codec.AV1, listOfNotNull(private))
            codecId == "A_AAC" -> audio(Codec.AAC, listOfNotNull(private))
            codecId.startsWith("A_AAC/") -> audio(Codec.AAC, listOf(legacyAacConfig(codecId, rate, channels)))
            codecId == "A_AC3" -> audio(Codec.AC3)
            codecId == "A_EAC3" -> audio(Codec.EAC3)
            codecId.startsWith("A_DTS") -> audio(Codec.DTS)
            codecId == "A_TRUEHD" -> audio(Codec.TRUEHD)
            codecId == "A_MPEG/L3" || codecId == "A_MPEG/L2" || codecId == "A_MPEG/L1" -> audio(Codec.MPEG_AUDIO)
            codecId == "A_OPUS" -> audio(Codec.OPUS, listOfNotNull(private, le64(codecDelayNs), le64(seekPreRollNs)))
            codecId == "A_VORBIS" -> private?.let(::vorbisHeaders)?.let { audio(Codec.VORBIS, it) }
            codecId == "A_FLAC" -> audio(Codec.FLAC, listOfNotNull(private))
            codecId == "A_PCM/INT/LIT" && (bitDepth == 16 || bitDepth == 0) -> audio(Codec.PCM)
            codecId == "S_TEXT/UTF8" || codecId == "S_TEXT/ASCII" -> text(Codec.SRT, null)
            codecId == "S_TEXT/ASS" || codecId == "S_TEXT/SSA" || codecId == "S_ASS" || codecId == "S_SSA" -> text(Codec.ASS)
            codecId == "S_TEXT/WEBVTT" || codecId == "D_WEBVTT/SUBTITLES" -> text(Codec.WEBVTT)
            codecId == "S_HDMV/PGS" -> text(Codec.PGS, null)
            codecId == "S_VOBSUB" -> text(Codec.VOBSUB)
            codecId == "S_DVBSUB" -> text(Codec.DVBSUB)
            else -> null
        }.also { if (it == null && type in 1..2) DnaLog.i(TAG, "MKV: $codecId nicht unterstützt") }
    }

    private fun le64(v: Long): ByteArray = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }

    /** Vorbis: CodecPrivate = Xiph-Lacing aus Kennung, Kommentar, Setup → csd-0 Kennung, csd-1 Setup. */
    private fun vorbisHeaders(data: ByteArray): List<ByteArray>? {
        if (data.isEmpty() || data[0].toInt() != 2) return null
        var pos = 1
        fun lace(): Int {
            var v = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                v += b
                if (b != 255) break
            }
            return v
        }
        val idLength = lace()
        val commentLength = lace()
        if (pos + idLength + commentLength > data.size) return null
        val id = data.copyOfRange(pos, pos + idLength)
        val setup = data.copyOfRange(pos + idLength + commentLength, data.size)
        return listOf(id, setup)
    }

    private fun legacyAacConfig(codecId: String, rate: Int, channels: Int): ByteArray {
        val profile = when {
            codecId.contains("/MAIN") -> 1
            codecId.contains("/SSR") -> 3
            codecId.contains("/LTP") -> 4
            else -> 2
        }
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val index = rates.indexOfFirst { it == rate }.let { if (it < 0) 4 else it }
        val v = (profile shl 11) or (index shl 7) or (channels.coerceIn(0, 7) shl 3)
        return byteArrayOf((v shr 8).toByte(), v.toByte())
    }

    private fun parseCues(end: Long) {
        while (reader.position < end) {
            val id = readId()
            val size = readSize()
            if (id != ID_CUE_POINT) { reader.skip(size); continue }
            val pEnd = reader.position + size
            var time = -1L
            var cluster = -1L
            var cueTrack = -1
            while (reader.position < pEnd) {
                val cid = readId()
                val csize = readSize()
                when (cid) {
                    ID_CUE_TIME -> time = readUInt(csize.toInt())
                    ID_CUE_TRACK_POSITIONS -> {
                        val tEnd = reader.position + csize
                        while (reader.position < tEnd) {
                            val tid = readId()
                            val tsize = readSize().toInt()
                            when {
                                tid == ID_CUE_CLUSTER_POSITION && cluster < 0 -> cluster = readUInt(tsize)
                                tid == ID_CUE_TRACK && cueTrack < 0 -> cueTrack = readUInt(tsize).toInt()
                                else -> reader.skip(tsize.toLong())
                            }
                        }
                    }
                    else -> reader.skip(csize)
                }
            }
            if (time >= 0 && cluster >= 0) cues += Cue(time * timecodeScale / 1000, cluster, cueTrack)
        }
        cues.sortBy { it.timeUs }
    }

    // ---------------------------------------------------------------- Blöcke

    private fun readBlockGroup(end: Long) {
        var blockAt = -1L
        var blockSize = 0
        var duration = -1L
        var reference = false
        while (reader.position < end) {
            val id = readId()
            val size = readSize()
            when (id) {
                ID_BLOCK -> { blockAt = reader.position; blockSize = size.toInt(); reader.skip(size) }
                ID_BLOCK_DURATION -> duration = readUInt(size.toInt())
                ID_REFERENCE_BLOCK -> { reference = true; reader.skip(size) }
                else -> reader.skip(size)
            }
        }
        if (blockAt < 0) return
        reader.seek(blockAt)
        readBlock(blockSize, simple = false, durationTicks = duration, reference = reference)
        reader.seek(end)
    }

    private fun readBlock(size: Int, simple: Boolean, durationTicks: Long, reference: Boolean) {
        val start = reader.position
        val trackNumber = readVint(keepMarker = false).toInt()
        val state = tracks[trackNumber]
        if (state == null) {
            reader.seek(start + size)
            return
        }
        val relative = (reader.u16().toShort()).toLong()
        val flags = reader.u8()
        val keyframe = if (simple) (flags and 0x80) != 0 else !reference
        val timeUs = (clusterTimecode + relative) * timecodeScale / 1000
        val lacing = (flags shr 1) and 3
        val headerLength = (reader.position - start).toInt()
        val payload = size - headerLength
        if (payload < 0) {
            reader.seek(start + size)
            return
        }
        val sizes: IntArray = if (lacing == 0) intArrayOf(payload) else readLaces(lacing, payload)
        val durationUs = if (durationTicks > 0) durationTicks * timecodeScale / 1000 else 0
        val frameUs = if (state.defaultDurationNs > 0) state.defaultDurationNs / 1000 else 0
        var t = timeUs
        for (frameSize in sizes) {
            if (frameSize < 0) break
            var data = reader.readBytes(frameSize)
            data = unpack(state, data) ?: continue
            pending.addLast(makeSample(state.track, data, t, keyframe || state.track.codec.kind == Kind.AUDIO, durationUs))
            t += frameUs
        }
        reader.seek(start + size)
    }

    private fun readLaces(lacing: Int, payload: Int): IntArray {
        val start = reader.position
        val count = reader.u8() + 1
        val sizes = IntArray(count)
        when (lacing) {
            1 -> { // Xiph
                for (i in 0 until count - 1) {
                    var v = 0
                    while (true) {
                        val b = reader.u8()
                        v += b
                        if (b != 255) break
                    }
                    sizes[i] = v
                }
            }
            3 -> { // EBML
                var previous = readVint(keepMarker = false).toInt()
                sizes[0] = previous
                for (i in 1 until count - 1) {
                    val (raw, length) = readVintWithLength()
                    val bias = (1L shl (7 * length - 1)) - 1
                    previous += (raw - bias).toInt()
                    sizes[i] = previous
                }
            }
            2 -> { // feste Größe
                val laceHeader = (reader.position - start).toInt()
                val each = (payload - laceHeader) / count
                sizes.fill(each)
                return sizes
            }
        }
        val laceHeader = (reader.position - start).toInt()
        val known = sizes.take(count - 1).sum()
        sizes[count - 1] = payload - laceHeader - known
        return sizes
    }

    private fun unpack(state: TrackState, data: ByteArray): ByteArray? {
        var d = data
        state.stripPrefix?.let { d = it + d }
        if (state.zlib) {
            d = runCatching {
                val inflater = java.util.zip.Inflater()
                inflater.setInput(d)
                val out = java.io.ByteArrayOutputStream(d.size * 3)
                val buf = ByteArray(16 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                inflater.end()
                out.toByteArray()
            }.getOrNull() ?: return null
        }
        return d
    }

    private fun makeSample(track: Track, data: ByteArray, timeUs: Long, keyframe: Boolean, durationUs: Long): Sample = when (track.codec) {
        Codec.H264, Codec.HEVC -> {
            val annexB = if (track.nalLengthSize > 0) NalUnits.toAnnexB(data, track.nalLengthSize) else data
            Sample(track, annexB, timeUs, keyframe || NalUnits.isKeyframe(track.codec, annexB), durationUs = durationUs)
        }
        else -> Sample(track, data, timeUs, keyframe, durationUs = durationUs)
    }

    // ---------------------------------------------------------------- EBML

    private fun readId(): Int {
        val first = reader.readByte()
        if (first < 0) throw EOFException()
        val length = when {
            first and 0x80 != 0 -> 1
            first and 0x40 != 0 -> 2
            first and 0x20 != 0 -> 3
            first and 0x10 != 0 -> 4
            else -> return readId() // ungültig: Byte überspringen
        }
        var v = first
        repeat(length - 1) { v = (v shl 8) or reader.u8() }
        return v
    }

    private fun readSize(): Long {
        val (raw, length) = readVintWithLength()
        val allOnes = (1L shl (7 * length)) - 1
        return if (raw == allOnes) UNKNOWN else raw
    }

    private fun readVint(keepMarker: Boolean): Long = readVintWithLength().first

    private fun readVintWithLength(): Pair<Long, Int> {
        val first = reader.u8()
        var length = 1
        var mask = 0x80
        while (length <= 8 && first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (length > 8) throw IOException("EBML-Zahl ungültig")
        var v = (first and (mask - 1)).toLong()
        repeat(length - 1) { v = (v shl 8) or reader.u8().toLong() }
        return v to length
    }

    private fun readUInt(size: Int): Long {
        var v = 0L
        repeat(size) { v = (v shl 8) or reader.u8().toLong() }
        return v
    }

    private fun readFloat(size: Int): Double = when (size) {
        4 -> Float.fromBits(readUInt(4).toInt()).toDouble()
        8 -> Double.fromBits(readUInt(8))
        else -> { reader.skip(size.toLong()); 0.0 }
    }

    private fun readString(size: Int): String = String(reader.readBytes(size), Charsets.UTF_8).trimEnd('\u0000')

    companion object {
        private const val TAG = "DnaMkv"
        private const val UNKNOWN = Long.MAX_VALUE
        private const val ID_EBML = 0x1A45DFA3
        private const val ID_SEGMENT = 0x18538067
        private const val ID_SEEK_HEAD = 0x114D9B74
        private const val ID_SEEK = 0x4DBB
        private const val ID_SEEK_ID = 0x53AB
        private const val ID_SEEK_POSITION = 0x53AC
        private const val ID_INFO = 0x1549A966
        private const val ID_TIMECODE_SCALE = 0x2AD7B1
        private const val ID_DURATION = 0x4489
        private const val ID_TRACKS = 0x1654AE6B
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_TRACK_NUMBER = 0xD7
        private const val ID_TRACK_TYPE = 0x83
        private const val ID_CODEC_ID = 0x86
        private const val ID_CODEC_PRIVATE = 0x63A2
        private const val ID_LANGUAGE = 0x22B59C
        private const val ID_LANGUAGE_BCP47 = 0x22B59D
        private const val ID_NAME = 0x536E
        private const val ID_FLAG_DEFAULT = 0x88
        private const val ID_FLAG_FORCED = 0x55AA
        private const val ID_DEFAULT_DURATION = 0x23E383
        private const val ID_CODEC_DELAY = 0x56AA
        private const val ID_SEEK_PRE_ROLL = 0x56BB
        private const val ID_VIDEO = 0xE0
        private const val ID_PIXEL_WIDTH = 0xB0
        private const val ID_PIXEL_HEIGHT = 0xBA
        private const val ID_COLOUR = 0x55B0
        private const val ID_TRANSFER = 0x55BA
        private const val ID_AUDIO = 0xE1
        private const val ID_SAMPLING_FREQUENCY = 0xB5
        private const val ID_OUTPUT_SAMPLING_FREQUENCY = 0x78B5
        private const val ID_CHANNELS = 0x9F
        private const val ID_BIT_DEPTH = 0x6264
        private const val ID_CONTENT_ENCODINGS = 0x6D80
        private const val ID_CONTENT_ENCODING = 0x6240
        private const val ID_CONTENT_COMPRESSION = 0x5034
        private const val ID_CONTENT_COMP_ALGO = 0x4254
        private const val ID_CONTENT_COMP_SETTINGS = 0x4255
        private const val ID_CUES = 0x1C53BB6B
        private const val ID_CUE_POINT = 0xBB
        private const val ID_CUE_TIME = 0xB3
        private const val ID_CUE_TRACK_POSITIONS = 0xB7
        private const val ID_CUE_CLUSTER_POSITION = 0xF1
        private const val ID_CUE_TRACK = 0xF7
        private const val ID_CLUSTER = 0x1F43B675
        private const val ID_TIMECODE = 0xE7
        private const val ID_SIMPLE_BLOCK = 0xA3
        private const val ID_BLOCK_GROUP = 0xA0
        private const val ID_BLOCK = 0xA1
        private const val ID_BLOCK_DURATION = 0x9B
        private const val ID_REFERENCE_BLOCK = 0xFB

        /** Erkennung am Dateianfang. */
        fun sniff(head: ByteArray): Boolean =
            head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
    }
}

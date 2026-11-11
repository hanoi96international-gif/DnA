package de.ayutv.player.dna.engine

import java.io.EOFException
import java.io.IOException

/**
 * DNA Engine: eigener Leser für MP4/MOV (ISO-BMFF). Klassisch (Inhaltsverzeichnis in `moov`, auch hinter den Daten)
 * und fragmentiert (`moof`/`trun`, wie bei HLS mit fMP4). Spulen über die Schlüsselbild-Tabelle (`stss`), bei
 * Fragmenten über `sidx`.
 *
 * Bild: H.264, HEVC (auch Dolby-Vision-Kennungen mit HEVC-Basis), MPEG-4, VP9, AV1. Ton: AAC, MP3, AC-3, E-AC-3,
 * Opus, FLAC, DTS. Untertitel: tx3g, WebVTT.
 */
class Mp4Demuxer(private val reader: SourceReader) : ContainerDemuxer {
    private class Table(
        val track: Track,
        val timescale: Long,
        val offsets: LongArray,
        val sizes: IntArray,
        /** Darstellungszeit (µs), Edit-List eingerechnet. */
        val timesUs: LongArray,
        val keyframes: BooleanArray?,
    ) {
        var next = 0
    }

    private class FragmentTrack(val track: Track, val timescale: Long, val defaultDuration: Long, val defaultSize: Int, val defaultFlags: Int, val shiftUs: Long)

    private class Pending(val offset: Long, val size: Int, val sample: (ByteArray) -> Sample)

    private var movieTimescale = 1000L
    private var durationUs = -1L
    private val tables = ArrayList<Table>()
    private val fragmentTracks = HashMap<Int, FragmentTrack>()
    private var fragmented = false
    /** Fragmentiert: Proben des aktuellen `moof`, nach Dateiposition. */
    private val fragmentQueue = ArrayDeque<Pending>()
    private var nextBoxPosition = 0L
    private var firstFragmentPosition = -1L
    private val fragmentDecodeTime = HashMap<Int, Long>()
    /** sidx: (Zeit µs, Dateiposition) je Abschnitt. */
    private val segmentIndex = ArrayList<Pair<Long, Long>>()

    override fun init(): ContainerDemuxer.Info {
        var moovSeen = false
        var position = 0L
        reader.seek(0)
        while (!reader.atEnd()) {
            reader.seek(position)
            val (type, size, header) = readBoxHeader() ?: break
            val bodyStart = position + header
            val end = if (size == 0L) (reader.length.takeIf { it > 0 } ?: Long.MAX_VALUE) else position + size
            when (type) {
                "moov" -> {
                    parseMoov(bodyStart, end)
                    moovSeen = true
                    if (fragmented && firstFragmentPosition < 0) firstFragmentPosition = end
                }
                "sidx" -> parseSidx(bodyStart, end, position)
                "moof" -> if (firstFragmentPosition < 0) firstFragmentPosition = position
                "mdat" -> if (moovSeen && !fragmented) break
            }
            if (moovSeen && !fragmented && type == "mdat") break
            if (moovSeen && fragmented && firstFragmentPosition >= 0 && type == "moof") break
            if (end == Long.MAX_VALUE || end <= position) break
            position = end
            if (reader.length in 1..position) break
        }
        if (!moovSeen) throw IOException("MP4 ohne Inhaltsverzeichnis")
        if (fragmented) {
            nextBoxPosition = if (firstFragmentPosition >= 0) firstFragmentPosition else position
        }
        val tracks = if (fragmented) fragmentTracks.values.map { it.track } else tables.map { it.track }
        return ContainerDemuxer.Info(tracks, durationUs, !fragmented || segmentIndex.isNotEmpty())
    }

    override fun read(): Sample? = if (fragmented) readFragmented() else readTable()

    override fun seek(timeUs: Long): Long {
        if (fragmented) {
            fragmentQueue.clear()
            val target = segmentIndex.lastOrNull { it.first <= timeUs } ?: segmentIndex.firstOrNull()
            nextBoxPosition = target?.second ?: firstFragmentPosition.coerceAtLeast(0)
            return target?.first ?: 0
        }
        val video = tables.firstOrNull { it.track.codec.kind == Kind.VIDEO }
        var keyTime = timeUs
        if (video != null && video.timesUs.isNotEmpty()) {
            var best = 0
            for (i in video.timesUs.indices) {
                val key = video.keyframes?.get(i) ?: true
                if (key && video.timesUs[i] <= timeUs) best = i
                if (video.timesUs[i] > timeUs && key) break
            }
            video.next = best
            keyTime = video.timesUs[best]
        }
        for (table in tables) {
            if (table === video) continue
            // Ab dem ersten Rahmen kurz vor dem Schlüsselbild (Ton läuft dann gleich mit).
            val idx = table.timesUs.indexOfFirst { it >= keyTime - 100_000 }
            table.next = if (idx < 0) table.timesUs.size else idx
        }
        return keyTime
    }

    // ---------------------------------------------------------------- klassisch

    private fun readTable(): Sample? {
        var chosen: Table? = null
        for (t in tables) {
            if (t.next >= t.offsets.size) continue
            if (chosen == null || t.offsets[t.next] < chosen.offsets[chosen.next]) chosen = t
        }
        val t = chosen ?: return null
        val i = t.next++
        reader.seek(t.offsets[i])
        val data = try { reader.readBytes(t.sizes[i]) } catch (e: EOFException) { return null }
        return makeSample(t.track, data, t.timesUs[i], t.keyframes?.get(i) ?: true)
    }

    // ---------------------------------------------------------------- fragmentiert

    private fun readFragmented(): Sample? {
        while (true) {
            fragmentQueue.removeFirstOrNull()?.let { p ->
                reader.seek(p.offset)
                val data = try { reader.readBytes(p.size) } catch (e: EOFException) { return null }
                return p.sample(data)
            }
            if (reader.length in 1..nextBoxPosition) return null
            reader.seek(nextBoxPosition)
            val (type, size, header) = (try { readBoxHeader() } catch (e: EOFException) { null }) ?: return null
            val start = nextBoxPosition
            val end = if (size == 0L) Long.MAX_VALUE else start + size
            if (type == "moof") parseMoof(start, start + header, end)
            if (end == Long.MAX_VALUE) return null
            nextBoxPosition = end
        }
    }

    private fun parseMoof(moofStart: Long, bodyStart: Long, end: Long) {
        val collected = ArrayList<Pending>()
        forEachBox(bodyStart, end) { type, s, e ->
            if (type == "traf") parseTraf(moofStart, s, e, collected)
        }
        collected.sortBy { it.offset }
        fragmentQueue.addAll(collected)
    }

    private fun parseTraf(moofStart: Long, start: Long, end: Long, out: MutableList<Pending>) {
        var trackId = -1
        var baseOffset = moofStart
        var duration = 0L
        var sizeDefault = 0
        var flagsDefault = 0
        var decodeTime = -1L
        val runs = ArrayList<Pair<Long, Long>>()
        forEachBox(start, end) { type, s, e ->
            reader.seek(s)
            when (type) {
                "tfhd" -> {
                    val flags = reader.u32().toInt() and 0xFFFFFF
                    trackId = reader.u32().toInt()
                    val ft = fragmentTracks[trackId]
                    duration = ft?.defaultDuration ?: 0
                    sizeDefault = ft?.defaultSize ?: 0
                    flagsDefault = ft?.defaultFlags ?: 0
                    if (flags and 0x01 != 0) baseOffset = reader.u64()
                    if (flags and 0x02 != 0) reader.u32()
                    if (flags and 0x08 != 0) duration = reader.u32()
                    if (flags and 0x10 != 0) sizeDefault = reader.u32().toInt()
                    if (flags and 0x20 != 0) flagsDefault = reader.u32().toInt()
                }
                "tfdt" -> {
                    val version = reader.u8()
                    reader.u24()
                    decodeTime = if (version == 1) reader.u64() else reader.u32()
                }
                "trun" -> runs += s to e
            }
        }
        val ft = fragmentTracks[trackId] ?: return
        var dts = if (decodeTime >= 0) decodeTime else fragmentDecodeTime[trackId] ?: 0L
        for ((s, _) in runs) {
            reader.seek(s)
            val version = reader.u8()
            val flags = reader.u24()
            val count = reader.u32().toInt()
            var dataOffset = baseOffset
            if (flags and 0x01 != 0) dataOffset = baseOffset + reader.u32().toInt()
            val firstFlags = if (flags and 0x04 != 0) reader.u32().toInt() else -1
            for (i in 0 until count) {
                val d = if (flags and 0x100 != 0) reader.u32() else duration
                val size = if (flags and 0x200 != 0) reader.u32().toInt() else sizeDefault
                val sFlags = if (flags and 0x400 != 0) reader.u32().toInt() else if (i == 0 && firstFlags >= 0) firstFlags else flagsDefault
                val cts = if (flags and 0x800 != 0) (if (version == 0) reader.u32() else reader.u32().toInt().toLong()) else 0L
                val timeUs = (dts + cts) * 1_000_000 / ft.timescale - ft.shiftUs
                val keyframe = (sFlags and 0x10000) == 0
                val offset = dataOffset
                out += Pending(offset, size) { data -> makeSample(ft.track, data, timeUs, keyframe) }
                dataOffset += size
                dts += d
            }
        }
        fragmentDecodeTime[trackId] = dts
    }

    private fun parseSidx(start: Long, end: Long, boxStart: Long) {
        reader.seek(start)
        val version = reader.u8()
        reader.u24()
        reader.u32() // reference id
        val timescale = reader.u32()
        var time = if (version == 0) reader.u32() else reader.u64()
        var offset = end + if (version == 0) reader.u32() else reader.u64()
        reader.u16()
        val count = reader.u16()
        repeat(count) {
            val ref = reader.u32()
            val duration = reader.u32()
            reader.u32()
            if (timescale > 0) segmentIndex += time * 1_000_000 / timescale to offset
            offset += ref and 0x7FFFFFFF
            time += duration
        }
    }

    // ---------------------------------------------------------------- moov

    private fun parseMoov(start: Long, end: Long) {
        forEachBox(start, end) { type, s, e ->
            when (type) {
                "mvhd" -> {
                    reader.seek(s)
                    val version = reader.u8()
                    reader.u24()
                    if (version == 1) { reader.u64(); reader.u64() } else { reader.u32(); reader.u32() }
                    movieTimescale = reader.u32().coerceAtLeast(1)
                    val d = if (version == 1) reader.u64() else reader.u32()
                    if (d > 0 && d != 0xFFFFFFFFL) durationUs = d * 1_000_000 / movieTimescale
                }
                "trak" -> runCatching { parseTrak(s, e) }.onFailure { DnaLog.i(TAG, "Spur übersprungen: ${it.message}") }
                "mvex" -> {
                    fragmented = true
                    forEachBox(s, e) { t, ts, _ ->
                        if (t == "trex") {
                            reader.seek(ts)
                            reader.u32()
                            val id = reader.u32().toInt()
                            reader.u32()
                            trex[id] = Triple(reader.u32(), reader.u32().toInt(), reader.u32().toInt())
                        }
                    }
                }
            }
        }
        // Fragmentiert: Spuren mit den trex-Standardwerten.
        if (fragmented) {
            for (p in parsedTracks) {
                val (dur, size, flags) = trex[p.track.id] ?: Triple(0L, 0, 0)
                fragmentTracks[p.track.id] = FragmentTrack(p.track, p.timescale, dur, size, flags, p.shiftUs)
            }
        }
    }

    private val trex = HashMap<Int, Triple<Long, Int, Int>>()

    private class ParsedTrack(val track: Track, val timescale: Long, val shiftUs: Long)

    private val parsedTracks = ArrayList<ParsedTrack>()

    private fun parseTrak(start: Long, end: Long) {
        var trackId = 0
        var timescale = 1L
        var handler = ""
        var language: String? = null
        var editMediaTime = 0L
        var entry: SampleEntry? = null
        val stbl = HashMap<String, Pair<Long, Long>>()
        forEachBox(start, end) { type, s, e ->
            when (type) {
                "tkhd" -> {
                    reader.seek(s)
                    val version = reader.u8()
                    reader.u24()
                    if (version == 1) { reader.u64(); reader.u64() } else { reader.u32(); reader.u32() }
                    trackId = reader.u32().toInt()
                }
                "edts" -> forEachBox(s, e) { t, es, _ ->
                    if (t == "elst") {
                        reader.seek(es)
                        val version = reader.u8()
                        reader.u24()
                        val count = reader.u32().toInt()
                        for (i in 0 until count) {
                            val segmentDuration = if (version == 1) reader.u64() else reader.u32()
                            val mediaTime = if (version == 1) reader.u64() else reader.u32().toInt().toLong()
                            reader.u32()
                            // Leerer Abschnitt (-1) am Anfang verschiebt nach hinten; sonst zählt der erste Medienbeginn.
                            if (mediaTime >= 0) { editMediaTime = mediaTime; break }
                            if (segmentDuration == 0L) continue
                        }
                    }
                }
                "mdia" -> forEachBox(s, e) { t, ms, me ->
                    when (t) {
                        "mdhd" -> {
                            reader.seek(ms)
                            val version = reader.u8()
                            reader.u24()
                            if (version == 1) { reader.u64(); reader.u64() } else { reader.u32(); reader.u32() }
                            timescale = reader.u32().coerceAtLeast(1)
                            if (version == 1) reader.u64() else reader.u32()
                            val packed = reader.u16()
                            language = String(charArrayOf(
                                (((packed shr 10) and 0x1F) + 0x60).toChar(),
                                (((packed shr 5) and 0x1F) + 0x60).toChar(),
                                ((packed and 0x1F) + 0x60).toChar(),
                            ))
                        }
                        "hdlr" -> {
                            reader.seek(ms + 8)
                            handler = String(reader.readBytes(4), Charsets.ISO_8859_1)
                        }
                        "minf" -> forEachBox(ms, me) { mt, mis, mie ->
                            if (mt == "stbl") forEachBox(mis, mie) { st, ss, se -> stbl[st] = ss to se }
                        }
                    }
                }
            }
        }
        val stsd = stbl["stsd"] ?: return
        entry = parseStsd(stsd.first, stsd.second, handler) ?: return
        val lang = normalizeLanguage(language)
        val track = entry.toTrack(trackId, lang) ?: return
        val shiftUs = editMediaTime * 1_000_000 / timescale
        parsedTracks += ParsedTrack(track, timescale, shiftUs)
        if (fragmented && stbl["stsz"]?.let { it.second - it.first <= 12 } != false && stbl["stz2"] == null) return
        buildTable(track, timescale, shiftUs, stbl)?.let { tables += it }
    }

    private fun buildTable(track: Track, timescale: Long, shiftUs: Long, stbl: Map<String, Pair<Long, Long>>): Table? {
        // Größen
        val sizes: IntArray = stbl["stsz"]?.let { (s, _) ->
            reader.seek(s + 4)
            val fixed = reader.u32().toInt()
            val count = reader.u32().toInt()
            if (fixed != 0) IntArray(count) { fixed } else IntArray(count) { reader.u32().toInt() }
        } ?: stbl["stz2"]?.let { (s, _) ->
            reader.seek(s + 4)
            reader.u24()
            val fieldSize = reader.u8()
            val count = reader.u32().toInt()
            var nibble = -1
            IntArray(count) {
                when (fieldSize) {
                    4 -> if (nibble < 0) { val b = reader.u8(); nibble = b and 0x0F; b shr 4 } else nibble.also { nibble = -1 }
                    8 -> reader.u8()
                    else -> reader.u16()
                }
            }
        } ?: return null
        val count = sizes.size
        if (count == 0) return null
        // Abschnitte (chunks)
        val chunkOffsets: LongArray = stbl["stco"]?.let { (s, _) ->
            reader.seek(s + 4)
            val n = reader.u32().toInt()
            LongArray(n) { reader.u32() }
        } ?: stbl["co64"]?.let { (s, _) ->
            reader.seek(s + 4)
            val n = reader.u32().toInt()
            LongArray(n) { reader.u64() }
        } ?: return null
        val stsc = stbl["stsc"]?.let { (s, _) ->
            reader.seek(s + 4)
            val n = reader.u32().toInt()
            Array(n) { longArrayOf(reader.u32(), reader.u32(), reader.u32()) }
        } ?: return null
        val offsets = LongArray(count)
        var sample = 0
        for (i in stsc.indices) {
            val firstChunk = stsc[i][0].toInt() - 1
            val perChunk = stsc[i][1].toInt()
            val lastChunk = if (i + 1 < stsc.size) stsc[i + 1][0].toInt() - 1 else chunkOffsets.size
            for (chunk in firstChunk until lastChunk) {
                if (chunk >= chunkOffsets.size) break
                var offset = chunkOffsets[chunk]
                repeat(perChunk) {
                    if (sample < count) {
                        offsets[sample] = offset
                        offset += sizes[sample]
                        sample++
                    }
                }
            }
        }
        // Zeiten
        val dts = LongArray(count)
        stbl["stts"]?.let { (s, _) ->
            reader.seek(s + 4)
            val n = reader.u32().toInt()
            var i = 0
            var t = 0L
            repeat(n) {
                val c = reader.u32().toInt()
                val delta = reader.u32()
                repeat(c) {
                    if (i < count) { dts[i++] = t; t += delta }
                }
            }
        }
        val times = LongArray(count)
        val ctts = LongArray(count)
        stbl["ctts"]?.let { (s, _) ->
            reader.seek(s)
            val version = reader.u8()
            reader.u24()
            val n = reader.u32().toInt()
            var i = 0
            repeat(n) {
                val c = reader.u32().toInt()
                val raw = reader.u32()
                val offset = if (version == 1) raw.toInt().toLong() else raw
                repeat(c) { if (i < count) ctts[i++] = offset }
            }
        }
        for (i in 0 until count) times[i] = (dts[i] + ctts[i]) * 1_000_000 / timescale - shiftUs
        val keyframes: BooleanArray? = stbl["stss"]?.let { (s, _) ->
            reader.seek(s + 4)
            val n = reader.u32().toInt()
            BooleanArray(count).also { k -> repeat(n) { val idx = reader.u32().toInt() - 1; if (idx in 0 until count) k[idx] = true } }
        }
        if (track.codec.kind == Kind.VIDEO) {
            val end = times.lastOrNull() ?: 0
            if (durationUs <= 0) durationUs = end
        }
        return Table(track, timescale, offsets, sizes, times, keyframes)
    }

    // ---------------------------------------------------------------- stsd

    private class SampleEntry(
        val format: String,
        val handler: String,
        val width: Int,
        val height: Int,
        val channels: Int,
        val sampleRate: Int,
        val boxes: Map<String, ByteArray>,
    ) {
        fun toTrack(id: Int, language: String?): Track? {
            fun video(codec: Codec, csd: List<ByteArray> = emptyList(), nal: Int = 0, w: Int = width, h: Int = height, hdr: Boolean = false) =
                Track(id, codec, language, csd = csd, width = if (w > 0) w else width, height = if (h > 0) h else height, nalLengthSize = nal, hdr = hdr)
            fun audio(codec: Codec, csd: List<ByteArray> = emptyList()) = Track(id, codec, language, csd = csd, sampleRate = sampleRate, channels = channels)
            return when (format) {
                "avc1", "avc3" -> boxes["avcC"]?.let(NalUnits::avcC)?.let { video(Codec.H264, it.csd, it.nalLengthSize, it.width, it.height) }
                "hvc1", "hev1", "dvh1", "dvhe" -> boxes["hvcC"]?.let(NalUnits::hvcC)?.let { video(Codec.HEVC, it.csd, it.nalLengthSize, it.width, it.height) }
                "mp4v" -> video(Codec.MPEG4_VIDEO, listOfNotNull(boxes["esds"]?.let(::esdsConfig)))
                "vp09" -> video(Codec.VP9)
                "av01" -> video(Codec.AV1, listOfNotNull(boxes["av1C"]))
                "mp4a" -> {
                    val esds = boxes["esds"]
                    when (esds?.let(::esdsObjectType)) {
                        0x6B, 0x69 -> audio(Codec.MPEG_AUDIO)
                        0xA5 -> audio(Codec.AC3)
                        0xA6 -> audio(Codec.EAC3)
                        0xA9, 0xAC -> audio(Codec.DTS)
                        else -> audio(Codec.AAC, listOfNotNull(esds?.let(::esdsConfig)))
                    }
                }
                ".mp3" -> audio(Codec.MPEG_AUDIO)
                "ac-3" -> audio(Codec.AC3)
                "ec-3" -> audio(Codec.EAC3)
                "dtsc", "dtsh", "dtsl", "dtse" -> audio(Codec.DTS)
                "Opus" -> boxes["dOps"]?.let { audio(Codec.OPUS, listOf(opusHead(it, channels, sampleRate), le64(0), le64(80_000_000))) }
                "fLaC" -> boxes["dfLa"]?.let { audio(Codec.FLAC, listOf(byteArrayOf(0x66, 0x4C, 0x61, 0x43) + it.copyOfRange(4, it.size))) }
                "tx3g" -> Track(id, Codec.TX3G, language)
                "wvtt" -> Track(id, Codec.WEBVTT, language)
                else -> null
            }
        }
    }

    private fun parseStsd(start: Long, end: Long, handler: String): SampleEntry? {
        reader.seek(start + 8)
        val entryStart = reader.position
        val (format, size, header) = readBoxHeader() ?: return null
        val body = entryStart + header
        val entryEnd = entryStart + size
        var width = 0
        var height = 0
        var channels = 0
        var rate = 0
        val childStart: Long
        when (handler) {
            "vide" -> {
                reader.seek(body + 24)
                width = reader.u16()
                height = reader.u16()
                childStart = body + 78
            }
            "soun" -> {
                reader.seek(body + 8)
                val version = reader.u16()
                reader.skip(6)
                channels = reader.u16()
                reader.u16()
                reader.u32()
                rate = (reader.u32() shr 16).toInt()
                childStart = body + 28 + when (version) { 1 -> 16; 2 -> 36; else -> 0 }
                if (version == 2) {
                    reader.seek(body + 28 + 4)
                    rate = java.lang.Double.longBitsToDouble(reader.u64()).toInt()
                    channels = reader.u32().toInt()
                }
            }
            else -> childStart = body + if (format == "tx3g") 38 else 8
        }
        val boxes = HashMap<String, ByteArray>()
        if (childStart < entryEnd) forEachBox(childStart, entryEnd) { type, s, e ->
            if (e - s < 4 * 1024 * 1024) {
                reader.seek(s)
                boxes[type] = reader.readBytes((e - s).toInt())
            }
        }
        // Verschlüsselt (encv/enca): echtes Format steht in sinf/frma – nicht abspielbar ohne DRM.
        return SampleEntry(format, handler, width, height, channels, rate, boxes)
    }

    // ---------------------------------------------------------------- Proben

    private fun makeSample(track: Track, data: ByteArray, timeUs: Long, keyframe: Boolean): Sample = when (track.codec) {
        Codec.H264, Codec.HEVC -> {
            val annexB = if (track.nalLengthSize > 0) NalUnits.toAnnexB(data, track.nalLengthSize) else data
            Sample(track, annexB, timeUs, keyframe)
        }
        Codec.TX3G -> {
            val length = if (data.size >= 2) ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else 0
            Sample(track, data.copyOfRange(2, (2 + length).coerceAtMost(data.size)), timeUs, true)
        }
        Codec.WEBVTT -> Sample(track, webvttText(data).toByteArray(), timeUs, true)
        else -> Sample(track, data, timeUs, keyframe)
    }

    // ---------------------------------------------------------------- Boxen

    private fun readBoxHeader(): Triple<String, Long, Int>? {
        val size32 = try { reader.u32() } catch (e: EOFException) { return null }
        val type = String(reader.readBytes(4), Charsets.ISO_8859_1)
        return when (size32) {
            1L -> Triple(type, reader.u64(), 16)
            else -> Triple(type, size32, 8)
        }
    }

    private inline fun forEachBox(start: Long, end: Long, block: (type: String, bodyStart: Long, end: Long) -> Unit) {
        var position = start
        while (position + 8 <= end) {
            reader.seek(position)
            val (type, size, header) = readBoxHeader() ?: return
            val boxEnd = if (size == 0L) end else position + size
            if (size in 1 until header) return
            val next = boxEnd
            block(type, position + header, boxEnd.coerceAtMost(end))
            if (next <= position) return
            position = next
        }
    }

    companion object {
        private const val TAG = "DnaMp4"

        fun sniff(head: ByteArray): Boolean {
            if (head.size < 12) return false
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            return type in setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "styp", "moof", "sidx")
        }

        private fun le64(v: Long): ByteArray = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }

        /** esds: Objekttyp aus dem DecoderConfigDescriptor. */
        private fun esdsObjectType(esds: ByteArray): Int? = descriptor(esds, 0x04)?.let { (at, _) -> esds[at].toInt() and 0xFF }

        /** esds: DecoderSpecificInfo (bei AAC die AudioSpecificConfig). */
        private fun esdsConfig(esds: ByteArray): ByteArray? = descriptor(esds, 0x05)?.let { (at, len) -> esds.copyOfRange(at, at + len) }

        private fun descriptor(esds: ByteArray, wanted: Int): Pair<Int, Int>? {
            var pos = 4 // Version + Flags
            while (pos < esds.size) {
                val tag = esds[pos++].toInt() and 0xFF
                var len = 0
                for (k in 0 until 4) {
                    if (pos >= esds.size) return null
                    val b = esds[pos++].toInt() and 0xFF
                    len = (len shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (tag == wanted) return pos to len.coerceAtMost(esds.size - pos)
                pos += when (tag) {
                    0x03 -> { // ES_Descriptor: Kopf, dann Unterdeskriptoren
                        val flags = esds[pos + 2].toInt() and 0xFF
                        var skip = 3
                        if (flags and 0x80 != 0) skip += 2
                        if (flags and 0x40 != 0) skip += 1 + (esds[pos + skip].toInt() and 0xFF)
                        if (flags and 0x20 != 0) skip += 2
                        skip
                    }
                    0x04 -> 13
                    else -> len
                }
            }
            return null
        }

        /** dOps → OpusHead (wie in Ogg). */
        private fun opusHead(dops: ByteArray, channels: Int, rate: Int): ByteArray {
            val body = dops.copyOfRange(0, dops.size)
            val out = java.io.ByteArrayOutputStream()
            out.write("OpusHead".toByteArray())
            out.write(1)
            out.write(body.getOrElse(1) { channels.toByte() }.toInt())
            // pre-skip, Rate, Gain: in dOps big-endian, in OpusHead little-endian
            val preSkip = ((body.getOrElse(2) { 0 }.toInt() and 0xFF) shl 8) or (body.getOrElse(3) { 0 }.toInt() and 0xFF)
            out.write(preSkip and 0xFF); out.write(preSkip shr 8)
            val r = if (body.size >= 8) ((body[4].toLong() and 0xFF) shl 24) or ((body[5].toLong() and 0xFF) shl 16) or ((body[6].toLong() and 0xFF) shl 8) or (body[7].toLong() and 0xFF) else rate.toLong()
            for (k in 0 until 4) out.write(((r shr (8 * k)) and 0xFF).toInt())
            val gain = if (body.size >= 10) ((body[8].toInt() and 0xFF) shl 8) or (body[9].toInt() and 0xFF) else 0
            out.write(gain and 0xFF); out.write(gain shr 8)
            out.write(body.getOrElse(10) { 0 }.toInt())
            if (body.size > 11) out.write(body, 11, body.size - 11)
            return out.toByteArray()
        }

        /** WebVTT in MP4: Text aus den payl-Boxen der vttc-Einträge. */
        private fun webvttText(data: ByteArray): String {
            val lines = ArrayList<String>()
            var pos = 0
            while (pos + 8 <= data.size) {
                val size = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
                if (size < 8 || pos + size > data.size) break
                if (type == "vttc") {
                    var inner = pos + 8
                    while (inner + 8 <= pos + size) {
                        val s = ((data[inner].toInt() and 0xFF) shl 24) or ((data[inner + 1].toInt() and 0xFF) shl 16) or ((data[inner + 2].toInt() and 0xFF) shl 8) or (data[inner + 3].toInt() and 0xFF)
                        val t = String(data, inner + 4, 4, Charsets.ISO_8859_1)
                        if (s < 8) break
                        if (t == "payl") lines += String(data, inner + 8, s - 8, Charsets.UTF_8)
                        inner += s
                    }
                }
                pos += size
            }
            return lines.joinToString("\n")
        }
    }
}

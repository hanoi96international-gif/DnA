package de.ayutv.player.dna.engine

/**
 * DNA Engine: eigener Leser für MPEG-TS (ISO/IEC 13818-1) – ohne ExoPlayer- oder VLC-Code.
 *
 * Nimmt den Strom in beliebigen Stücken ([feed]), findet Programm (PAT/PMT) und Spuren, setzt die PES-Pakete zusammen
 * und gibt Zugriffseinheiten aus: ein Bild (H.264, HEVC, MPEG-2) bzw. einen Tonrahmen (AAC in ADTS oder LATM, AC-3,
 * E-AC-3, MPEG-Audio) mit Zeitstempel in Mikrosekunden. Kaputte Pakete werden übersprungen; ein Fehler in einer Spur
 * beendet nie den Strom. Zusatz-Tonspuren für Hör-/Sehbehinderte (ISO-639 audio_type 2/3) werden nicht ausgegeben.
 */
class TsDemuxer(private val output: Output) {

    interface Output {
        /** Spuren des Programms (nach jeder neuen PMT-Version). */
        fun onTracks(tracks: List<Track>)
        /** Eine Zugriffseinheit; [Sample.data] gehört dem Empfänger. */
        fun onSample(sample: Sample)
    }

    private val carry = ByteArray(PACKET)
    private var carried = 0
    private var pmtPid = -1
    private var pmtVersion = -1
    private val sections = HashMap<Int, SectionBuffer>()
    private val streams = HashMap<Int, Stream>()
    /** Kaputte oder übersprungene Pakete (für die Anzeige). */
    var corruptPackets = 0
        private set

    /** Neuer Abschnitt (Sprung, Neuverbinden): angefangene Pakete verwerfen, Programm behalten. */
    fun reset() {
        carried = 0
        sections.clear()
        streams.values.forEach { it.reset() }
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var pos = offset
        val end = offset + length
        // Rest vom letzten Mal zu einem ganzen Paket ergänzen.
        if (carried > 0) {
            val need = PACKET - carried
            val take = minOf(need, end - pos)
            System.arraycopy(data, pos, carry, carried, take)
            carried += take
            pos += take
            if (carried < PACKET) return
            carried = 0
            if (carry[0] == SYNC) packet(carry, 0) else corruptPackets++
        }
        while (pos < end) {
            if (data[pos] != SYNC) {
                // Synchronisation verloren: bis zum nächsten Syncbyte, das ein Paket weiter wieder eins hat.
                corruptPackets++
                pos = resync(data, pos + 1, end)
                continue
            }
            if (end - pos < PACKET) {
                System.arraycopy(data, pos, carry, 0, end - pos)
                carried = end - pos
                return
            }
            packet(data, pos)
            pos += PACKET
        }
    }

    /** Am Ende eines Stroms: angefangene PES ausgeben. */
    fun flush() {
        streams.values.forEach {
            it.finish()
            it.emitPending()
        }
    }

    private fun resync(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end) {
            if (data[i] == SYNC && (i + PACKET >= end || data[i + PACKET] == SYNC)) return i
            i++
        }
        return end
    }

    private fun packet(b: ByteArray, o: Int) {
        val b1 = b[o + 1].toInt() and 0xFF
        if (b1 and 0x80 != 0) { // transport_error_indicator
            corruptPackets++
            return
        }
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
        val b3 = b[o + 3].toInt() and 0xFF
        val afc = (b3 shr 4) and 3
        var payload = o + 4
        if (afc == 2 || afc == 0) return // nur Anpassungsfeld oder reserviert
        if (afc == 3) payload += 1 + (b[o + 4].toInt() and 0xFF)
        val payloadEnd = o + PACKET
        if (payload >= payloadEnd) return
        when {
            pid == PAT_PID -> section(pid, b, payload, payloadEnd, pusi)
            pid == pmtPid -> section(pid, b, payload, payloadEnd, pusi)
            else -> streams[pid]?.packet(b, payload, payloadEnd, pusi, b3 and 0x0F)
        }
    }

    // ------------------------------------------------------------------ PSI (PAT/PMT)

    private class SectionBuffer {
        var data = ByteArray(1024)
        var length = 0
    }

    private fun section(pid: Int, b: ByteArray, start: Int, end: Int, pusi: Boolean) {
        val buffer = sections.getOrPut(pid) { SectionBuffer() }
        var pos = start
        if (pusi) {
            val pointer = b[pos].toInt() and 0xFF
            pos += 1
            // Ende der vorigen Sektion (vor dem Zeiger) noch anhängen.
            if (buffer.length > 0 && pointer > 0) append(buffer, b, pos, minOf(pos + pointer, end))
            tryParse(pid, buffer)
            buffer.length = 0
            pos += pointer
        } else if (buffer.length == 0) return
        if (pos < end) append(buffer, b, pos, end)
        tryParse(pid, buffer)
    }

    private fun append(buffer: SectionBuffer, b: ByteArray, from: Int, to: Int) {
        val n = to - from
        if (n <= 0) return
        if (buffer.length + n > buffer.data.size) buffer.data = buffer.data.copyOf(maxOf(buffer.length + n, buffer.data.size * 2))
        System.arraycopy(b, from, buffer.data, buffer.length, n)
        buffer.length += n
    }

    private fun tryParse(pid: Int, buffer: SectionBuffer) {
        if (buffer.length < 3) return
        val d = buffer.data
        val sectionLength = ((d[1].toInt() and 0x0F) shl 8) or (d[2].toInt() and 0xFF)
        if (buffer.length < 3 + sectionLength) return
        val table = d[0].toInt() and 0xFF
        runCatching {
            when {
                pid == PAT_PID && table == 0x00 -> parsePat(d, sectionLength)
                pid == pmtPid && table == 0x02 -> parsePmt(d, sectionLength)
            }
        }
        buffer.length = 0
    }

    private fun parsePat(d: ByteArray, sectionLength: Int) {
        var i = 8
        val end = 3 + sectionLength - 4 // ohne CRC
        while (i + 4 <= end) {
            val program = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            val pid = ((d[i + 2].toInt() and 0x1F) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (program != 0) {
                if (pid != pmtPid) {
                    pmtPid = pid
                    pmtVersion = -1
                }
                return
            }
            i += 4
        }
    }

    private fun parsePmt(d: ByteArray, sectionLength: Int) {
        val version = (d[5].toInt() shr 1) and 0x1F
        if (version == pmtVersion) return
        pmtVersion = version
        val programInfoLength = ((d[10].toInt() and 0x0F) shl 8) or (d[11].toInt() and 0xFF)
        var i = 12 + programInfoLength
        val end = 3 + sectionLength - 4
        val tracks = ArrayList<Track>()
        val seen = HashSet<Int>()
        while (i + 5 <= end) {
            val type = d[i].toInt() and 0xFF
            val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
            val infoLength = ((d[i + 3].toInt() and 0x0F) shl 8) or (d[i + 4].toInt() and 0xFF)
            val descriptors = Descriptors.parse(d, i + 5, minOf(i + 5 + infoLength, end))
            i += 5 + infoLength
            val codec = codecFor(type, descriptors) ?: continue
            if (codec.kind == Kind.AUDIO && descriptors.audioType in 2..3) continue
            seen += pid
            val track = if (codec == Codec.DVBSUB) {
                val pages = byteArrayOf((descriptors.compositionPage shr 8).toByte(), descriptors.compositionPage.toByte(),
                    (descriptors.ancillaryPage shr 8).toByte(), descriptors.ancillaryPage.toByte())
                Track(pid, codec, descriptors.subtitleLanguage ?: descriptors.language,
                    label = if (descriptors.subtitlingType in 0x20..0x25) "Hörgeschädigte" else null, extra = pages)
            } else Track(pid, codec, descriptors.language)
            tracks += track
            val existing = streams[pid]
            if (existing == null || existing.track != track) streams[pid] = Stream(track)
        }
        streams.keys.retainAll(seen)
        output.onTracks(tracks)
    }

    private class Descriptors(
        val language: String?, val audioType: Int, val ac3: Boolean, val eac3: Boolean, val registration: String?,
        /** DVB-Untertitel (Deskriptor 0x59): Sprache, Art und Seiten. */
        val subtitle: Boolean = false, val subtitleLanguage: String? = null, val subtitlingType: Int = 0,
        val compositionPage: Int = 0, val ancillaryPage: Int = 0,
        val dts: Boolean = false,
    ) {
        companion object {
            fun parse(d: ByteArray, from: Int, to: Int): Descriptors {
                var language: String? = null
                var audioType = 0
                var ac3 = false
                var eac3 = false
                var registration: String? = null
                var subtitle = false
                var subtitleLanguage: String? = null
                var subtitlingType = 0
                var compositionPage = 0
                var ancillaryPage = 0
                var dts = false
                var i = from
                while (i + 2 <= to) {
                    val tag = d[i].toInt() and 0xFF
                    val len = d[i + 1].toInt() and 0xFF
                    val body = i + 2
                    if (body + len > to) break
                    when (tag) {
                        0x0A -> if (len >= 4) {
                            language = String(d, body, 3, Charsets.ISO_8859_1).trim().lowercase().takeIf { it.isNotBlank() }
                            audioType = d[body + 3].toInt() and 0xFF
                        }
                        0x6A -> ac3 = true
                        0x7B -> dts = true
                        0x7A -> eac3 = true
                        0x05 -> if (len >= 4) registration = String(d, body, 4, Charsets.ISO_8859_1)
                        0x59 -> if (len >= 8) {
                            subtitle = true
                            subtitleLanguage = String(d, body, 3, Charsets.ISO_8859_1).trim().lowercase().takeIf { it.isNotBlank() }
                            subtitlingType = d[body + 3].toInt() and 0xFF
                            compositionPage = ((d[body + 4].toInt() and 0xFF) shl 8) or (d[body + 5].toInt() and 0xFF)
                            ancillaryPage = ((d[body + 6].toInt() and 0xFF) shl 8) or (d[body + 7].toInt() and 0xFF)
                        }
                    }
                    i = body + len
                }
                return Descriptors(language, audioType, ac3, eac3, registration, subtitle, subtitleLanguage, subtitlingType, compositionPage, ancillaryPage, dts)
            }
        }
    }

    private fun codecFor(type: Int, desc: Descriptors): Codec? = when (type) {
        0x01, 0x02 -> Codec.MPEG2_VIDEO
        0x1B -> Codec.H264
        0x24 -> Codec.HEVC
        0x03, 0x04 -> Codec.MPEG_AUDIO
        0x0F -> Codec.AAC_ADTS
        0x11 -> Codec.AAC_LATM
        0x81 -> Codec.AC3
        0x87 -> Codec.EAC3
        0x82, 0x85, 0x86 -> Codec.DTS
        0x06 -> when {
            desc.dts || desc.registration?.startsWith("DTS") == true -> Codec.DTS
            desc.eac3 || desc.registration == "EAC3" -> Codec.EAC3
            desc.ac3 || desc.registration == "AC-3" -> Codec.AC3
            desc.subtitle -> Codec.DVBSUB
            else -> null
        }
        else -> null
    }

    // ------------------------------------------------------------------ PES und Zugriffseinheiten

    private inner class Stream(val track: Track) {
        /** Das laufende PES-Paket samt Kopf (der Kopf darf über mehrere TS-Pakete reichen). */
        private var pes = ByteArray(if (track.codec.kind == Kind.VIDEO) 512 * 1024 else 16 * 1024)
        private var length = 0
        private var started = false
        private var lastCc = -1
        private val audio = if (track.codec.kind == Kind.AUDIO) AudioFramer(track) else null
        /** H.264/HEVC: Bilder werden an den Bildgrenzen im Videostrom getrennt (wie VLC/ExoPlayer), nicht an den PES-Grenzen. */
        private val units = if (track.codec == Codec.H264 || track.codec == Codec.HEVC) AccessUnits(track) else null

        fun reset() {
            length = 0
            started = false
            lastCc = -1
            audio?.reset()
            units?.reset()
            pendingVideo = null
            pendingTimeUs = NO_TIME
        }

        /**
         * Andere Bildformate (MPEG-2): Bild, das noch weitergehen kann – Folgepakete ohne (oder mit gleichem) Zeitstempel
         * gehören noch dazu; ausgegeben wird es, sobald das nächste mit eigenem Zeitstempel beginnt.
         */
        private var pendingVideo: ArrayList<ByteArray>? = null
        private var pendingTimeUs = NO_TIME

        private fun addVideo(data: ByteArray, time: Long) {
            val pending = pendingVideo
            if (pending != null && (time == NO_TIME || time == pendingTimeUs)) {
                pending += data
                return
            }
            emitPending()
            if (time == NO_TIME) return // Anfang verpasst: ohne Zeitstempel nicht zuordenbar
            pendingVideo = arrayListOf(data)
            pendingTimeUs = time
        }

        /** Gesammeltes Bild ausgeben (am Ende des Stroms). */
        fun emitPending() {
            units?.flush()
            val pending = pendingVideo ?: return
            pendingVideo = null
            val data = if (pending.size == 1) pending[0] else {
                val out = ByteArray(pending.sumOf { it.size })
                var at = 0
                for (part in pending) {
                    System.arraycopy(part, 0, out, at, part.size)
                    at += part.size
                }
                out
            }
            output.onSample(Sample(track, data, pendingTimeUs, isKeyframe(track.codec, data)))
        }

        fun packet(b: ByteArray, start: Int, end: Int, pusi: Boolean, cc: Int) {
            // Doppelt gesendetes Paket (gleicher Zähler, erlaubt in MPEG-TS): verwerfen – angehängt verdarb es das Bild.
            if (lastCc >= 0 && cc == lastCc) return
            // Lücke im Zähler: angefangenes Paket ist unvollständig – verwerfen, beim nächsten Anfang weiter. Auch wenn
            // das Paket nach der Lücke ein neues PES beginnt: dann fehlt das Ende des vorigen (Bild unvollständig).
            if (lastCc >= 0 && cc != ((lastCc + 1) and 0x0F)) {
                corruptPackets++
                // Begann das verlorene PES selbst ein Bild, ist das Bild davor vollständig (nur dieses fehlt);
                // sonst setzte es das Bild davor fort, und auch das ist unbrauchbar.
                val previousComplete = units != null && started && pesStartsUnit()
                length = 0
                started = false
                audio?.reset()
                units?.gap(previousComplete)
            }
            lastCc = cc
            if (pusi) {
                finish()
                started = true
                length = 0
                append(b, start, end)
            } else if (started) append(b, start, end)
            // Untertitel: sofort, wenn die angekündigte Länge da ist (nicht erst mit dem nächsten Paket).
            if (track.codec.kind == Kind.TEXT && started && length >= 6) {
                val packetLength = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
                if (packetLength > 0 && length >= 6 + packetLength) finish()
            }
        }

        /** Das angefangene PES beginnt mit einer Bildgrenze (Trenner, Parametersätze, erste Scheibe). */
        private fun pesStartsUnit(): Boolean {
            if (length < 9 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) return false
            val at = 9 + (pes[8].toInt() and 0xFF)
            return units?.startsUnit(pes, at, length) == true
        }

        private fun append(b: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (n <= 0) return
            if (length + n > pes.size) {
                if (length + n > MAX_PES) { // nie unbegrenzt wachsen (kaputter Strom)
                    length = 0
                    started = false
                    corruptPackets++
                    return
                }
                pes = pes.copyOf(maxOf(length + n, pes.size * 2))
            }
            System.arraycopy(b, from, pes, length, n)
            length += n
        }

        /** PES fertig: Kopf lesen (auch wenn er über mehrere TS-Pakete ging), Nutzdaten weitergeben. */
        fun finish() {
            if (!started || length == 0) {
                length = 0
                started = false
                return
            }
            started = false
            val total = length
            length = 0
            if (total < 9 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) {
                corruptPackets++
                units?.lost()
                return
            }
            val flags = pes[7].toInt() and 0xFF
            val headerLength = pes[8].toInt() and 0xFF
            val payloadStart = 9 + headerLength
            if (payloadStart > total) {
                corruptPackets++
                units?.lost()
                return
            }
            val timeUs = if (flags and 0x80 != 0 && headerLength >= 5) pts(pes, 9) else NO_TIME
            // Angekündigte Länge (falls angegeben): nichts dahinter mitnehmen.
            val packetLength = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
            val end = if (packetLength > 0) minOf(total, 6 + packetLength) else total
            if (end <= payloadStart) return
            when {
                units != null -> units.feed(pes, payloadStart, end, timeUs)
                track.codec.kind == Kind.VIDEO -> addVideo(pes.copyOfRange(payloadStart, end), timeUs)
                track.codec.kind == Kind.TEXT -> if (timeUs != NO_TIME) output.onSample(Sample(track, pes.copyOfRange(payloadStart, end), timeUs, true))
                else -> audio?.frames(pes.copyOfRange(payloadStart, end), timeUs) { frame, time, format -> output.onSample(Sample(track, frame, time, true, format)) }
            }
        }
    }

    /**
     * Zugriffseinheiten (ein Bild) aus dem H.264/HEVC-Strom, getrennt an den Bildgrenzen im Strom selbst – wie VLC und
     * ExoPlayer: ein Trenner (AUD), Parametersätze oder SEI nach einem Bild, oder die erste Scheibe eines neuen Bildes
     * (first_mb_in_slice = 0 bzw. first_slice_segment_in_pic_flag). Die PES-Grenzen zählen dafür nicht: Viele Sender
     * (gerade 4K) verteilen ein Bild auf mehrere PES-Pakete oder geben nicht jedem Bild einen Zeitstempel – an den
     * PES-Grenzen getrennt kamen beim Decoder zerstückelte oder zusammengeklebte Bilder an; die Hardware-Decoder brachen
     * mit Fehler ab (4K-Sender auf dem Handy: kein Bild).
     *
     * Zeitstempel: Ein Bild bekommt den Zeitstempel des PES-Pakets, in dem es beginnt (nur das erste dort beginnende);
     * Bilder ohne eigenen Zeitstempel werden im Bildabstand weitergezählt.
     */
    private inner class AccessUnits(private val track: Track) {
        private val hevc = track.codec == Codec.HEVC
        private var es = ByteArray(1 shl 20)
        private var size = 0
        /** Ab hier noch nicht nach Startcodes durchsucht. */
        private var scanned = 0
        /** Beginn des laufenden Bildes im Puffer (-1 = noch keins). */
        private var auStart = -1
        private var auHasPicture = false
        private var auTimeUs = NO_TIME
        /** PES-Anfänge im Puffer mit ihrem Zeitstempel (noch nicht vergeben). */
        private val pesStarts = ArrayDeque<LongArray>()
        private var lastTimeUs = NO_TIME
        private var frameUs = 40_000L

        fun reset() {
            size = 0
            scanned = 0
            auStart = -1
            auHasPicture = false
            auTimeUs = NO_TIME
            pesStarts.clear()
            lastTimeUs = NO_TIME
        }

        /** Daten gingen verloren (Zähler-Lücke, kaputter Kopf): das laufende Bild ist unbrauchbar – verwerfen. */
        fun lost() {
            if (auStart >= 0) {
                // Ab der nächsten Bildgrenze weiter; was bis dahin kommt, gehört zum verworfenen Bild.
                size = 0
                scanned = 0
                auStart = -1
                auHasPicture = false
                auTimeUs = NO_TIME
                pesStarts.clear()
            }
        }

        fun feed(data: ByteArray, from: Int, to: Int, pesTimeUs: Long) {
            val n = to - from
            if (size + n > es.size) {
                if (size + n > MAX_PES) { // nie unbegrenzt (kein Bildende gefunden)
                    reset()
                    corruptPackets++
                    return
                }
                es = es.copyOf(maxOf(size + n, es.size * 2))
            }
            if (pesTimeUs != NO_TIME) pesStarts.addLast(longArrayOf(size.toLong(), pesTimeUs))
            System.arraycopy(data, from, es, size, n)
            size += n
            scan()
        }

        /**
         * Lücke im Strom (verlorene Pakete): Ist das laufende Bild vollständig ([previousComplete]), geht es jetzt hinaus,
         * sonst wird es verworfen. Danach geht es erst an der nächsten echten Bildgrenze weiter (nie mitten im Bild).
         */
        fun gap(previousComplete: Boolean) {
            if (previousComplete && auStart >= 0) emit(auStart, size)
            size = 0
            scanned = 0
            auStart = -1
            auHasPicture = false
            auTimeUs = NO_TIME
            pesStarts.clear()
        }

        /** Beginnen die Daten ab [from] (gleich mit einem Startcode) eine Zugriffseinheit? */
        fun startsUnit(data: ByteArray, from: Int, to: Int): Boolean {
            var i = from
            if (i + 4 < to && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) i++
            if (i + 5 >= to || data[i].toInt() != 0 || data[i + 1].toInt() != 0 || data[i + 2].toInt() != 1) return false
            val nal = i + 3
            return if (hevc) {
                val type = (data[nal].toInt() shr 1) and 0x3F
                type == 35 || type in 32..34 || type == 39 || type in 41..44 || type in 48..55 ||
                    (type <= 31 && (data[nal + 2].toInt() and 0x80) != 0)
            } else {
                val type = data[nal].toInt() and 0x1F
                type == 9 || type in 6..8 || type in 14..18 || (type in 1..5 && (data[nal + 1].toInt() and 0x80) != 0)
            }
        }

        private fun scan() {
            var i = maxOf(scanned, 0)
            // Startcode 00 00 01 + mindestens 3 Bytes dahinter (Kopf + erstes Bit der Scheibe).
            while (i + 5 < size) {
                if (es[i].toInt() != 0 || es[i + 1].toInt() != 0 || es[i + 2].toInt() != 1) {
                    i++
                    continue
                }
                val nal = i + 3
                val startsNew: Boolean
                val picture: Boolean
                /** Hier kann ein Bild beginnen (für den Einstieg nach einer Lücke). */
                val canStart: Boolean
                if (hevc) {
                    val type = (es[nal].toInt() shr 1) and 0x3F
                    picture = type <= 31
                    val prefix = type in 32..34 || type == 39 || type in 41..44 || type in 48..55
                    val firstSlice = picture && (es[nal + 2].toInt() and 0x80) != 0 // first_slice_segment_in_pic_flag
                    canStart = type == 35 || prefix || firstSlice
                    startsNew = when {
                        // Trenner (AUD): neues Bild – außer das laufende hat noch keins. Manche Sender schicken
                        // „AUD + Parametersätze“ als eigene Einheit vor „AUD + Bild“: die Parametersätze gehören zum Bild
                        // (früher fielen sie weg – „Schlüsselbild ohne Parametersätze“, kein Bild).
                        type == 35 -> auHasPicture
                        prefix -> auHasPicture
                        firstSlice -> auHasPicture
                        else -> false
                    }
                } else {
                    val type = es[nal].toInt() and 0x1F
                    picture = type in 1..5
                    val prefix = type in 6..8 || type in 14..18
                    val firstSlice = picture && (es[nal + 1].toInt() and 0x80) != 0 // first_mb_in_slice = 0
                    canStart = type == 9 || prefix || firstSlice
                    startsNew = when {
                        type == 9 -> auHasPicture // AUD (siehe HEVC)
                        prefix -> auHasPicture
                        firstSlice -> auHasPicture
                        else -> false
                    }
                }
                // 4-Byte-Startcode: die Null davor gehört zum neuen Bild.
                val codeStart = if (i > 0 && es[i - 1].toInt() == 0) i - 1 else i
                if (auStart < 0) {
                    // Einstieg (Anfang, nach einer Lücke): erst an einer echten Bildgrenze, nie mitten im Bild.
                    if (!canStart) {
                        i += 3
                        continue
                    }
                    beginAu(codeStart)
                } else if (startsNew) {
                    emit(auStart, codeStart)
                    beginAu(codeStart)
                }
                if (picture && !auHasPicture && auTimeUs == NO_TIME) {
                    // Einheit begann ohne Zeitstempel (Parametersätze in eigenem PES): den des Bildes nehmen.
                    var time = NO_TIME
                    while (pesStarts.isNotEmpty() && pesStarts.first()[0] <= codeStart) time = pesStarts.removeFirst()[1]
                    auTimeUs = time
                }
                if (picture) auHasPicture = true
                i += 3
            }
            scanned = i
            compact()
        }

        private fun beginAu(at: Int) {
            auStart = at
            auHasPicture = false
            // Zeitstempel des PES-Pakets, in dem das Bild beginnt (ältere, nicht vergebene verfallen).
            var time = NO_TIME
            while (pesStarts.isNotEmpty() && pesStarts.first()[0] <= at) time = pesStarts.removeFirst()[1]
            auTimeUs = time
        }

        private fun emit(from: Int, to: Int) {
            if (to <= from || !auHasPicture) return
            val data = es.copyOfRange(from, to)
            var time = auTimeUs
            if (time == NO_TIME) {
                if (lastTimeUs == NO_TIME) return // Anfang ohne Zeitstempel: nicht zuordenbar
                time = lastTimeUs + frameUs
            } else if (lastTimeUs != NO_TIME) {
                val d = time - lastTimeUs
                if (d in 5_000..200_000) frameUs = d
            }
            lastTimeUs = time
            output.onSample(Sample(track, data, time, isKeyframe(track.codec, data)))
        }

        /** Am Ende des Stroms: das letzte Bild ausgeben. */
        fun flush() {
            if (auStart >= 0) emit(auStart, size)
            reset()
        }

        /** Ausgegebenes vorn abschneiden. */
        private fun compact() {
            val keep = if (auStart >= 0) auStart else maxOf(0, scanned - 3)
            if (keep < 256 * 1024 && keep < size / 2) return
            System.arraycopy(es, keep, es, 0, size - keep)
            size -= keep
            scanned -= keep
            if (auStart >= 0) auStart -= keep
            for (p in pesStarts) p[0] -= keep
        }
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val PAT_PID = 0
        private const val MAX_PES = 16 * 1024 * 1024
        const val NO_TIME = Long.MIN_VALUE

        /** 33-Bit-PTS (90 kHz) in Mikrosekunden. */
        fun pts(b: ByteArray, o: Int): Long {
            val v = ((b[o].toLong() and 0x0E) shl 29) or
                ((b[o + 1].toLong() and 0xFF) shl 22) or
                ((b[o + 2].toLong() and 0xFE) shl 14) or
                ((b[o + 3].toLong() and 0xFF) shl 7) or
                ((b[o + 4].toLong() and 0xFE) shr 1)
            return v * 100 / 9
        }

        /**
         * Schlüsselbild: H.264 IDR (5) oder ein I-Bild ohne IDR (Scheibe vom Typ I/SI), HEVC IRAP (16–23), MPEG-2
         * Sequenzkopf. Viele Sender (Sender, Sender & Co. in HD) senden nie ein IDR-Bild, nur I-Bilder mit
         * Wiederherstellungspunkt – nur mit IDR als Einstieg startete der Bild-Decoder dort nie: Ton lief, das Bild stand
         * (wie ExoPlayer mit FLAG_ALLOW_NON_IDR_KEYFRAMES).
         */
        fun isKeyframe(codec: Codec, data: ByteArray): Boolean {
            var i = 0
            while (i + 4 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    val h = data[i + 3].toInt() and 0xFF
                    when (codec) {
                        Codec.H264 -> when (h and 0x1F) {
                            5 -> return true
                            // Erste Scheibe des Bildes entscheidet: I-Bild oder nicht.
                            1 -> return H264Sps.isIntraSlice(data, i + 4)
                        }
                        Codec.HEVC -> if (((h shr 1) and 0x3F) in 16..23) return true
                        Codec.MPEG2_VIDEO -> if (h == 0xB3) return true
                        else -> return true
                    }
                    i += 3
                } else i++
            }
            return false
        }
    }
}

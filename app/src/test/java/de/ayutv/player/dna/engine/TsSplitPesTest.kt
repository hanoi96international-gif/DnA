package de.ayutv.player.dna.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wie viele echte Sender (gerade 4K): Ein Bild ist auf mehrere PES-Pakete verteilt, nur das erste trägt den Zeitstempel.
 * Der TS-Leser muss jedes Bild vollständig und genau einmal liefern (früher fielen die Folgepakete weg – kaputte
 * Schlüsselbilder, kein Bild, nur Ton).
 */
class TsSplitPesTest {
    private fun demux(data: ByteArray): Pair<List<Track>, List<Sample>> {
        var tracks = emptyList<Track>()
        val video = ArrayList<Sample>()
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(found: List<Track>) { tracks = found }
            override fun onSample(sample: Sample) { if (sample.track.codec.kind == Kind.VIDEO) video += sample }
        })
        var pos = 0
        while (pos < data.size) {
            val n = minOf(data.size - pos, 1316)
            demuxer.feed(data, pos, n)
            pos += n
        }
        demuxer.flush()
        return tracks to video
    }

    /** Kleiner TS-Schreiber: PAT, PMT (eine Bildspur), PES-Pakete mit begrenzter Länge. */
    private class Muxer(
        private val streamType: Int,
        /** Jedes TS-Paket doppelt senden (gleicher Zähler, in MPEG-TS erlaubt). */
        private val duplicate: Boolean = false,
        /** Im ersten TS-Paket eines PES nur so viele Nutzbytes (großes Anpassungsfeld): der PES-Kopf reicht ins nächste. */
        private val firstPacketBytes: Int? = null,
    ) {
        val out = ByteArrayOutputStream()
        private val cc = HashMap<Int, Int>()

        fun tables() {
            section(0, byteArrayOf(0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, 0xE1.toByte(), 0x00))
            section(0x100, byteArrayOf(0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0xE2.toByte(), 0x00, 0xF0.toByte(), 0x00,
                streamType.toByte(), 0xE2.toByte(), 0x00, 0xF0.toByte(), 0x00))
        }

        private fun section(pid: Int, body: ByteArray) {
            val withPointer = byteArrayOf(0) + body + crc(body)
            packet(pid, withPointer, pusi = true)
        }

        private fun crc(data: ByteArray): ByteArray {
            var c = -1
            for (b in data) {
                c = c xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            }
            return byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte())
        }

        /** Ein PES-Paket (mit oder ohne Zeitstempel) über so viele TS-Pakete wie nötig. */
        fun pes(pid: Int, payload: ByteArray, ptsUs: Long?) {
            val header = ByteArrayOutputStream()
            header.write(byteArrayOf(0, 0, 1, 0xE0.toByte()))
            val headerData = if (ptsUs != null) 5 else 0
            val length = 3 + headerData + payload.size
            header.write(if (length <= 0xFFFF) byteArrayOf((length shr 8).toByte(), length.toByte()) else byteArrayOf(0, 0))
            header.write(0x80)
            header.write(if (ptsUs != null) 0x80 else 0x00)
            header.write(headerData)
            if (ptsUs != null) {
                val pts = ptsUs * 9 / 100
                header.write(byteArrayOf(
                    (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xFE) or 1).toByte(),
                    (pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte(),
                ))
            }
            header.write(payload)
            packet(pid, header.toByteArray(), pusi = true, video = true)
        }

        private fun packet(pid: Int, data: ByteArray, pusi: Boolean, video: Boolean = false) {
            var pos = 0
            var first = true
            while (pos < data.size) {
                val n = minOf(if (first && video && firstPacketBytes != null) firstPacketBytes else 184, data.size - pos)
                val c = cc.getOrDefault(pid, 0)
                cc[pid] = (c + 1) and 0x0F
                val p = ByteArray(188) { 0xFF.toByte() }
                p[0] = 0x47
                p[1] = (((if (first && pusi) 0x40 else 0) or (pid shr 8)) and 0xFF).toByte()
                p[2] = pid.toByte()
                if (n < 184) {
                    // Auffüllen per Anpassungsfeld.
                    p[3] = (0x30 or c).toByte()
                    val adaptation = 183 - n
                    p[4] = adaptation.toByte()
                    if (adaptation > 0) p[5] = 0
                    System.arraycopy(data, pos, p, 188 - n, n)
                } else {
                    p[3] = (0x10 or c).toByte()
                    System.arraycopy(data, pos, p, 4, n)
                }
                out.write(p)
                if (duplicate && video) out.write(p)
                pos += n
                first = false
            }
        }
    }

    private fun original(): Pair<List<Track>, List<Sample>> = demux(javaClass.getResourceAsStream("/dna/h264_aac.ts")!!.readBytes())

    @Test
    fun framesSplitOverSeveralPesComeOutWhole() {
        val (_, frames) = original()
        assertTrue(frames.size >= 40)
        val mux = Muxer(0x1B)
        mux.tables()
        for (frame in frames) {
            // In Stücke von höchstens 512 Bytes: das erste mit Zeitstempel, die übrigen ohne (wie Rundfunk-Encoder).
            val parts = frame.data.toList().chunked(512).map { it.toByteArray() }
            parts.forEachIndexed { i, part -> mux.pes(0x200, part, if (i == 0) frame.timeUs else null) }
        }
        val (tracks, again) = demux(mux.out.toByteArray())
        assertEquals(Codec.H264, tracks.single().codec)
        assertEquals("jedes Bild genau einmal", frames.size, again.size)
        frames.zip(again).forEachIndexed { i, (a, b) ->
            assertEquals("Zeit Bild $i", a.timeUs / 100, b.timeUs / 100)
            assertEquals("Schlüsselbild $i", a.keyframe, b.keyframe)
            assertArrayEquals("Inhalt Bild $i", a.data, b.data)
        }
        assertTrue("mehrteilige Bilder im Test", frames.count { it.data.size > 512 } > 10)
    }

    @Test
    fun twoFramesInOnePesComeOutSeparately() {
        // Manche Sender packen mehrere Bilder in ein PES (nur das erste hat dort einen Zeitstempel). An den PES-Grenzen
        // getrennt wurden sie zu einem Bild verklebt – der Hardware-Decoder brach ab (4K auf dem Handy).
        val (_, frames) = original()
        val mux = Muxer(0x1B)
        mux.tables()
        for (pair in frames.chunked(2)) mux.pes(0x200, pair.fold(ByteArray(0)) { acc, f -> acc + f.data }, pair.first().timeUs)
        val (_, again) = demux(mux.out.toByteArray())
        assertEquals("jedes Bild einzeln", frames.size, again.size)
        frames.zip(again).forEachIndexed { i, (a, b) ->
            assertArrayEquals("Inhalt Bild $i", a.data, b.data)
            assertEquals("Schlüsselbild $i", a.keyframe, b.keyframe)
        }
        // Bilder mit eigenem Zeitstempel behalten ihn.
        frames.zip(again).filterIndexed { i, _ -> i % 2 == 0 }.forEach { (a, b) -> assertEquals(a.timeUs / 100, b.timeUs / 100) }
    }

    @Test
    fun duplicatePacketsAndLongPesHeadersDoNotBreakFrames() {
        val (_, frames) = original()
        // Doppelt gesendete TS-Pakete und PES-Köpfe, die wegen eines großen Anpassungsfelds ins nächste Paket reichen.
        val mux = Muxer(0x1B, duplicate = true, firstPacketBytes = 6)
        mux.tables()
        for (frame in frames) mux.pes(0x200, frame.data, frame.timeUs)
        val (_, again) = demux(mux.out.toByteArray())
        assertEquals(frames.size, again.size)
        frames.zip(again).forEachIndexed { i, (a, b) ->
            assertEquals("Zeit Bild $i", a.timeUs / 100, b.timeUs / 100)
            assertArrayEquals("Inhalt Bild $i", a.data, b.data)
        }
    }

    @Test
    fun repeatedTimestampOnFollowUpPesBelongsToSameFrame() {
        val (_, frames) = original()
        val mux = Muxer(0x1B)
        mux.tables()
        for (frame in frames) {
            // Manche Encoder wiederholen den Zeitstempel in jedem Teil.
            frame.data.toList().chunked(400).forEach { mux.pes(0x200, it.toByteArray(), frame.timeUs) }
        }
        val (_, again) = demux(mux.out.toByteArray())
        assertEquals(frames.size, again.size)
        frames.zip(again).forEach { (a, b) -> assertArrayEquals(a.data, b.data) }
    }

    @Test
    fun lostPacketBeforeNextPesStartDropsOnlyTheIncompleteFrame() {
        // Fehlt das letzte TS-Paket eines Bildes und das nächste beginnt ein neues PES, war das unvollständige Bild früher
        // trotzdem an den Decoder gegangen (Lücke nur ohne PES-Anfang erkannt) – Hardware-Decoder brechen daran ab.
        val (_, frames) = original()
        val mux = Muxer(0x1B)
        mux.tables()
        for (frame in frames) mux.pes(0x200, frame.data, frame.timeUs)
        val data = mux.out.toByteArray()
        val packets = data.size / 188
        fun pid(i: Int) = ((data[i * 188 + 1].toInt() and 0x1F) shl 8) or (data[i * 188 + 2].toInt() and 0xFF)
        fun pusi(i: Int) = data[i * 188 + 1].toInt() and 0x40 != 0
        val starts = (0 until packets).filter { pid(it) == 0x200 && pusi(it) }
        // Ein Bild aus mehreren Paketen in der Mitte: dessen letztes Paket fällt weg.
        val k = (10 until starts.size - 2).first { starts[it + 1] - starts[it] >= 3 }
        val drop = starts[k + 1] - 1
        val broken = ByteArrayOutputStream().apply {
            for (i in 0 until packets) if (i != drop) write(data, i * 188, 188)
        }.toByteArray()
        val (_, again) = demux(broken)
        assertEquals("nur das unvollständige Bild fehlt", frames.size - 1, again.size)
        assertTrue("kein abgeschnittenes Bild", again.none { b -> frames.none { it.data.contentEquals(b.data) } })
        assertTrue(again.none { it.data.contentEquals(frames[k].data) })
    }

    @Test
    fun parameterSetsInOwnUnitStayWithTheNextFrame() {
        // Manche Sender schicken „AUD + SPS + PPS“ als eigene Einheit (eigenes PES) vor „AUD + Schlüsselbild“. Früher
        // fielen die Parametersätze weg (Einheit ohne Bild) – der Decoder bekam nie seine Einrichtung, kein Bild.
        val (_, frames) = original()
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val mux = Muxer(0x1B)
        mux.tables()
        var withParams = 0
        for (frame in frames) {
            val nals = VideoConfig.nalUnits(frame.data)
            val params = nals.filter { (it[0].toInt() and 0x1F) in 7..8 }
            val rest = nals.filter { (it[0].toInt() and 0x1F) !in 7..9 }
            fun join(list: List<ByteArray>) = list.fold(aud) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }
            if (params.isNotEmpty()) {
                withParams++
                mux.pes(0x200, join(params), frame.timeUs)
            }
            mux.pes(0x200, join(rest), frame.timeUs)
        }
        assertTrue(withParams > 0)
        val (_, again) = demux(mux.out.toByteArray())
        assertEquals("jedes Bild genau einmal", frames.size, again.size)
        frames.zip(again).forEachIndexed { i, (a, b) ->
            assertEquals("Zeit Bild $i", a.timeUs / 100, b.timeUs / 100)
            assertEquals("Schlüsselbild $i", a.keyframe, b.keyframe)
        }
        val keyframes = again.filter { it.keyframe }
        assertTrue(keyframes.isNotEmpty())
        keyframes.forEach { assertTrue("Parametersätze beim Schlüsselbild", VideoConfig.from(Codec.H264, it.data) != null) }
    }
}

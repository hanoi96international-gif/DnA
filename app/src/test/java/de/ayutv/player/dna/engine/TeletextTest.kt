package de.ayutv.player.dna.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Videotext-Untertitel (Tafel 150/777): Pakete wie im DVB-Strom (EN 300 472) – Bits in Sendereihenfolge, Adresse und
 * Tafelkopf Hamming-8/4-geschützt, Text mit ungerader Parität, sichtbar nur im „Kasten“.
 */
class TeletextTest {
    private fun ham(v: Int) = TeletextDecoder.hamByte(v)

    private fun char(c: Int): Byte {
        val withParity = if (Integer.bitCount(c) % 2 == 0) c or 0x80 else c
        return TeletextDecoder.reverse(withParity.toByte()).toByte()
    }

    private fun packet(magazine: Int, row: Int, data: ByteArray): ByteArray {
        val out = ByteArray(44)
        out[0] = 0x2C.toByte()
        out[1] = TeletextDecoder.reverse(0xE4.toByte()).toByte()
        out[2] = ham((magazine and 0x07) or ((row and 1) shl 3))
        out[3] = ham(row shr 1)
        data.copyInto(out, 4)
        return out
    }

    /** Tafelkopf: Seite (BCD), C4 löschen, C6 Untertitel, C11 seriell. */
    private fun header(page: Int, subtitle: Boolean = true, serial: Boolean = false): ByteArray {
        val d = ByteArray(40) { char(' '.code) }
        d[0] = ham(page and 0x0F)
        d[1] = ham((page shr 4) and 0x0F)
        d[2] = ham(0)
        d[3] = ham(0x08)
        d[4] = ham(0)
        d[5] = ham(if (subtitle) 0x08 else 0)
        d[6] = ham(0)
        d[7] = ham(if (serial) 1 else 0)
        return packet(page shr 8, 0, d)
    }

    /** Textzeile mit Kasten (Start Box doppelt, wie gesendet). */
    private fun row(magazine: Int, number: Int, text: String, boxed: Boolean = true): ByteArray {
        val codes = ArrayList<Int>()
        if (boxed) { codes += 0x0B; codes += 0x0B }
        text.forEach { codes += it.code }
        if (boxed) { codes += 0x0A; codes += 0x0A }
        val d = ByteArray(40) { k -> char(codes.getOrElse(k - 4) { 0x20 }.let { if (k < 4) 0x20 else it }) }
        return packet(magazine, number, d)
    }

    private fun pes(vararg packets: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x10)
        packets.forEach { p ->
            out.write(0x03)
            out.write(p.size)
            out.write(p)
        }
        return out.toByteArray()
    }

    @Test
    fun hammingRoundTripAndSingleBitError() {
        for (v in 0..15) {
            val b = ham(v)
            assertEquals(v, TeletextDecoder.ham(b))
            // Ein gekipptes Bit wird korrigiert.
            for (bit in 0..7) assertEquals(v, TeletextDecoder.ham((b.toInt() xor (1 shl bit)).toByte()))
        }
    }

    @Test
    fun signalledPageWithGermanCharacters() {
        val decoder = TeletextDecoder(0x150, "deu")
        // „Mädchen“ und „Grüße“: ä = 0x7B, ü = 0x7D, ß = 0x7E im deutschen Zeichensatz.
        val text = decoder.decode(pes(header(0x150), row(1, 20, "Das M{dchen sagt:"), row(1, 22, "Gr}~e aus K|ln")))
        assertEquals("Das Mädchen sagt:\nGrüße aus Köln", text)
    }

    @Test
    fun otherPagesAreIgnoredAndBlankPageClears() {
        val decoder = TeletextDecoder(0x150, "deu")
        // Tafel 100 im selben Magazin: deren Zeilen gehören nicht zu 150.
        assertNull(decoder.decode(pes(header(0x100, subtitle = false), row(1, 5, "Nachrichten"))))
        assertEquals("Hallo", decoder.decode(pes(header(0x150), row(1, 22, "Hallo"))))
        // Kopf einer anderen Tafel beendet die 150 – deren Zeile darf nicht mehr hinein.
        assertNull(decoder.decode(pes(header(0x101, subtitle = false), row(1, 22, "Wetter"))))
        // Dieselbe Tafel unverändert noch einmal: nichts Neues.
        assertNull(decoder.decode(pes(header(0x150), row(1, 22, "Hallo"))))
        // Leere Tafel: ausblenden.
        assertEquals("", decoder.decode(pes(header(0x150))))
    }

    @Test
    fun rowsInNextPacketStillCount() {
        val decoder = TeletextDecoder(0x150, "deu")
        assertEquals("", decoder.decode(pes(header(0x150))))
        assertEquals("Später", decoder.decode(pes(row(1, 22, "Sp{ter"))))
    }

    @Test
    fun withoutSignalledPageTheSubtitlePageIsFound() {
        val decoder = TeletextDecoder(0, "deu")
        assertNull(decoder.decode(pes(header(0x100, subtitle = false), row(1, 3, "Übersicht"))))
        assertEquals("Untertitel", decoder.decode(pes(header(0x777), row(7, 21, "Untertitel"))))
        assertEquals(0x777, decoder.page)
    }

    @Test
    fun signalledPageMissingFallsBackToSubtitlePage() {
        // Der Anbieter gibt Tafel 150 an, sendet die Untertitel aber auf 777.
        val decoder = TeletextDecoder(0x150, "deu")
        repeat(2000) { decoder.decode(pes(header(0x777), row(7, 22, "Noch nicht"))) }
        assertEquals(0x777, decoder.page)
        assertEquals("Jetzt", decoder.decode(pes(header(0x777), row(7, 22, "Jetzt"))))
    }

    @Test
    fun textOutsideBoxIsHidden() {
        val decoder = TeletextDecoder(0x150, "deu")
        val d = ByteArray(40) { char(' '.code) }
        "Kopf".forEachIndexed { k, c -> d[k] = char(c.code) }
        d[10] = char(0x0B); d[11] = char(0x0B)
        "Text".forEachIndexed { k, c -> d[12 + k] = char(c.code) }
        d[16] = char(0x0A)
        assertEquals("Text", decoder.decode(pes(header(0x150), packet(1, 22, d))))
    }

    // ------------------------------------------------------------------ im TS-Strom

    private fun tsPacket(pid: Int, payload: ByteArray, start: Boolean, cc: Int): ByteArray {
        val out = ByteArray(188) { 0xFF.toByte() }
        out[0] = 0x47
        out[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        out[2] = pid.toByte()
        out[3] = (0x10 or (cc and 0x0F)).toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun section(body: ByteArray): ByteArray = byteArrayOf(0) + body + byteArrayOf(0, 0, 0, 0)

    private fun pat(pmtPid: Int): ByteArray {
        val len = 5 + 4 + 4
        return section(byteArrayOf(0x00, (0xB0 or (len shr 8)).toByte(), len.toByte(), 0, 1, 0xC1.toByte(), 0, 0,
            0, 1, (0xE0 or (pmtPid shr 8)).toByte(), pmtPid.toByte()))
    }

    private fun pmt(ttxPid: Int, descriptor: ByteArray): ByteArray {
        val es = byteArrayOf(0x06, (0xE0 or (ttxPid shr 8)).toByte(), ttxPid.toByte(), 0xF0.toByte(), descriptor.size.toByte()) + descriptor
        val len = 9 + es.size + 4
        val head = byteArrayOf(0x02, (0xB0 or (len shr 8)).toByte(), len.toByte(), 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00)
        return byteArrayOf(0) + head + es + byteArrayOf(0, 0, 0, 0)
    }

    private fun pesPacket(payload: ByteArray, pts: Long): ByteArray {
        val length = 3 + 5 + payload.size
        val ptsBytes = byteArrayOf(
            (0x21 or (((pts shr 30).toInt() and 0x07) shl 1)).toByte(),
            ((pts shr 22) and 0xFF).toByte(),
            ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
            ((pts shr 7) and 0xFF).toByte(),
            (((pts and 0x7F).toInt() shl 1) or 1).toByte(),
        )
        return byteArrayOf(0, 0, 1, 0xBD.toByte(), (length shr 8).toByte(), length.toByte(), 0x80.toByte(), 0x80.toByte(), 5) + ptsBytes + payload
    }

    @Test
    fun teletextTrackFromTs() {
        val ttxPid = 0x105
        // Videotext-Deskriptor: deutsche Untertitel für Hörgeschädigte (Art 5) auf Magazin 1, Seite 50 → Tafel 150.
        val descriptor = byteArrayOf(0x56, 10, 'd'.code.toByte(), 'e'.code.toByte(), 'u'.code.toByte(), ((1 shl 3) or 1).toByte(), 0x00,
            'd'.code.toByte(), 'e'.code.toByte(), 'u'.code.toByte(), ((5 shl 3) or 1).toByte(), 0x50)
        val stream = java.io.ByteArrayOutputStream()
        stream.write(tsPacket(0, pat(0x100), true, 0))
        stream.write(tsPacket(0x100, pmt(ttxPid, descriptor), true, 0))
        stream.write(tsPacket(ttxPid, pesPacket(pes(header(0x150), row(1, 22, "Guten Abend")), 90_000L * 10), true, 0))
        val data = stream.toByteArray()
        var track: Track? = null
        val samples = ArrayList<Sample>()
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(tracks: List<Track>) { track = tracks.firstOrNull { it.codec == Codec.TELETEXT } }
            override fun onSample(sample: Sample) { if (sample.track.codec == Codec.TELETEXT) samples += sample }
        })
        demuxer.feed(data, 0, data.size)
        demuxer.flush()
        val t = track!!
        assertEquals("deu", t.language)
        assertEquals("Videotext 150 (Hörgeschädigte)", t.label)
        assertEquals(Kind.TEXT, t.codec.kind)
        assertEquals(1, samples.size)
        val page = ((t.extra!![0].toInt() and 0xFF) shl 8) or (t.extra!![1].toInt() and 0xFF)
        assertEquals("Guten Abend", TeletextDecoder(page, t.language).decode(samples[0].data))
    }

    @Test
    fun descriptorPage() {
        assertEquals(0x150, TeletextDecoder.pageFromDescriptor(1, 0x50))
        assertEquals(0x888, TeletextDecoder.pageFromDescriptor(0, 0x88))
        assertEquals("777", TeletextDecoder.pageLabel(0x777))
    }
}

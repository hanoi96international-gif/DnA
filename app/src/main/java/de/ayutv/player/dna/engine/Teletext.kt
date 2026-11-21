package de.ayutv.player.dna.engine

/**
 * Videotext-Untertitel (ETSI EN 300 472 / ETS 300 706): ARD, ZDF, RTL, ProSieben & Co. senden die Untertitel für
 * Hörgeschädigte im Videotext, meist auf Tafel 150 oder 777. Liest die Tafel [page] (z. B. 0x150 = 150, Magazin in der
 * Hunderterstelle, 8 statt 0) aus den PES-Paketen der Videotext-Spur und gibt bei jeder neu gesendeten Tafel ihren Text
 * zurück („" = ausblenden). [page] = 0: die erste Tafel nehmen, die als Untertitel gekennzeichnet ist (Bit C6).
 *
 * Text als Text (keine Texterkennung nötig) – darum auch gleich übersetzbar (DeepL, DnA-Übersetzer).
 */
class TeletextDecoder(page: Int, private val language: String? = null) {
    /** Gesuchte Tafel (BCD mit Magazin: 0x150, 0x777, 0x888); 0 = automatisch. */
    var page: Int = page
        private set

    private val rows = arrayOfNulls<String>(24)
    /** Gesuchte Tafel wird gerade übertragen (Zeilen gehören zu ihr). */
    private var receiving = false
    /** Seit dem letzten Ergebnis neu übertragen (Kopf oder Zeilen). */
    private var changed = false
    private var charset = Charset.forLanguage(language)
    private var lastText: String? = null

    /**
     * Ein PES-Paket (ohne PES-Kopf, beginnt mit data_identifier). Ergebnis: Text der Tafel, wenn sie in diesem Paket neu
     * gesendet wurde und sich vom zuletzt gezeigten unterscheidet; "" = ausblenden; null = nichts Neues.
     */
    fun decode(pes: ByteArray): String? {
        if (pes.isEmpty()) return null
        val id = pes[0].toInt() and 0xFF
        if (id !in 0x10..0x1F) return null
        var i = 1
        while (i + 2 <= pes.size) {
            val unitId = pes[i].toInt() and 0xFF
            val length = pes[i + 1].toInt() and 0xFF
            val start = i + 2
            if (start + length > pes.size) break
            if ((unitId == 0x02 || unitId == 0x03) && length >= 44) packet(pes, start)
            i = start + length
        }
        if (!changed) return null
        changed = false
        val text = text()
        if (text == lastText) return null
        lastText = text
        return text
    }

    /** Ein Videotext-Paket (44 Byte: Feld/Zeile, Rahmenkennung, Adresse, 40 Byte Inhalt) – Bits in Sendereihenfolge. */
    private fun packet(d: ByteArray, at: Int) {
        val a0 = ham(d[at + 2])
        val a1 = ham(d[at + 3])
        if (a0 < 0 || a1 < 0) return
        val magazine = (a0 and 0x07).let { if (it == 0) 8 else it }
        val row = (a0 shr 3) or (a1 shl 1)
        val data = at + 4
        if (row == 0) header(d, data, magazine)
        else if (row in 1..23 && receiving && magazine == page shr 8) {
            rows[row] = line(d, data)
            changed = true
        }
    }

    private fun header(d: ByteArray, data: Int, magazine: Int) {
        val units = ham(d[data])
        val tens = ham(d[data + 1])
        val c6 = ham(d[data + 5])
        val c11 = ham(d[data + 7])
        if (units < 0 || tens < 0) return
        val number = (magazine shl 8) or (tens shl 4) or units
        val serial = c11 >= 0 && (c11 and 0x01) == 1
        val subtitle = c6 >= 0 && (c6 and 0x08) != 0
        if (page == 0 && subtitle && tens <= 9 && units <= 9) page = number
        if (number == page) {
            // Jede Übertragung der Untertitel-Tafel ist eine ganze neue Tafel (C4 „löschen“ ist bei Untertiteln gesetzt).
            receiving = true
            rows.fill(null)
            changed = true
        } else if (receiving && (serial || magazine == page shr 8)) {
            // Die Übertragung der Tafel endet mit dem nächsten Kopf (seriell: jedem, parallel: im selben Magazin).
            receiving = false
        }
    }

    /** Eine Textzeile: Zeichen mit ungerader Parität, sichtbar ist nur, was in einem „Kasten“ steht (Untertitel). */
    private fun line(d: ByteArray, data: Int): String {
        val chars = CharArray(40)
        var boxed = false
        var anyBox = false
        val visible = BooleanArray(40)
        for (k in 0 until 40) {
            val c = reverse(d[data + k]) and 0x7F
            when (c) {
                0x0B -> { boxed = true; anyBox = true; chars[k] = ' ' }  // Start Box (zählt doppelt gesendet)
                0x0A -> { boxed = false; chars[k] = ' ' }                // End Box
                in 0x00..0x1F, 0x7F -> chars[k] = ' '
                else -> chars[k] = charset.map(c)
            }
            visible[k] = boxed
        }
        val sb = StringBuilder()
        for (k in 0 until 40) sb.append(if (!anyBox || visible[k]) chars[k] else ' ')
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun text(): String = rows.filterNotNull().filter { it.isNotBlank() }.joinToString("\n")

    /** Zeichensätze (G0 Latein mit Landesvariante, ETS 300 706 Tabelle 36) für die wichtigsten Sprachen. */
    internal enum class Charset(private val table: String) {
        // Positionen: 23 24 40 5B 5C 5D 5E 5F 60 7B 7C 7D 7E
        ENGLISH("£\$@←½→↑#—¼‖¾÷"),
        GERMAN("#\$§ÄÖÜ^_°äöüß"),
        FRENCH("éïàëêùî#èâôûç"),
        ITALIAN("£\$é°ç→↑#ùàòèì"),
        SPANISH("ç\$¡áéíóú¿üñèà"),
        PLAIN("#\$@[\\]^_`{|}~");

        fun map(c: Int): Char {
            val slot = SLOTS.indexOf(c)
            return if (slot >= 0) table[slot] else c.toChar()
        }

        companion object {
            private val SLOTS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)

            fun forLanguage(language: String?): Charset = when (language?.lowercase()?.take(3)) {
                null, "" -> GERMAN
                "deu", "ger", "de" -> GERMAN
                "eng", "en" -> ENGLISH
                "fra", "fre", "fr" -> FRENCH
                "ita", "it" -> ITALIAN
                "spa", "es", "por", "pt" -> SPANISH
                else -> PLAIN
            }
        }
    }

    companion object {
        /** Bitreihenfolge umkehren: Videotext wird mit dem niedrigsten Bit zuerst gesendet. */
        internal fun reverse(b: Byte): Int {
            var v = b.toInt() and 0xFF
            var r = 0
            repeat(8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r
        }

        /** Gültige Hamming-8/4-Codewörter (nach dem Umkehren) für die Werte 0–15 (ETS 300 706, 8.2). */
        private val HAMMING = intArrayOf(0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA)

        /** Hamming 8/4: Wert 0–15; ein falsches Bit wird korrigiert, bei mehr -1. */
        internal fun ham(b: Byte): Int {
            val v = reverse(b)
            val exact = HAMMING.indexOf(v)
            if (exact >= 0) return exact
            for (n in HAMMING.indices) if (Integer.bitCount(HAMMING[n] xor v) == 1) return n
            return -1
        }

        /** Für Tests und den Aufbau von Paketen: Wert 0–15 als Hamming-8/4-Byte in Sendereihenfolge. */
        internal fun hamByte(value: Int): Byte = reverse(HAMMING[value and 0x0F].toByte()).toByte()

        /** Tafelnummer aus dem Videotext-Deskriptor (0x56): Magazin (0 = 8) und BCD-Seite → 0x150. */
        fun pageFromDescriptor(magazine: Int, pageByte: Int): Int = ((if (magazine == 0) 8 else magazine) shl 8) or (pageByte and 0xFF)

        /** Anzeige: 0x150 → „150“. */
        fun pageLabel(page: Int): String = "%x".format(page)
    }
}

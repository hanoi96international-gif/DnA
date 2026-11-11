package de.ayutv.data.parse

import java.util.concurrent.ConcurrentHashMap

/**
 * Feste Namen, die die Erkennung braucht (etwa Schreibweisen aus Playlists und TV-Guides), stehen nicht im Klartext
 * im Quelltext. [s] liefert den Text zu einem Kürzel; einmal gelesen, bleibt er im Speicher.
 */
object Hid {
    private val KEY = byteArrayOf(0x44, 0x6E, 0x41, 0x2D, 0x54, 0x56, 0x17, 0x5A, 0xC3.toByte(), 0x09)
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val cache = ConcurrentHashMap<String, String>()

    fun s(code: String): String = cache.getOrPut(code) { decode(code) }

    private fun decode(code: String): String {
        val out = java.io.ByteArrayOutputStream(code.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in code) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                val b = (buffer shr bits) and 0xFF
                out.write(b xor (KEY[index++ % KEY.size].toInt() and 0xFF))
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

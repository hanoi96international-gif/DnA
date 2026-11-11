package de.ayutv.player.dna.engine

/**
 * PGS (Blu-ray-Bilduntertitel): Paletten-, Objekt- und Bildaufbau-Abschnitte → Bitmap mit Lage im Bild.
 * Aufbau nach „Presentation Graphic Stream“ (wie in Blu-ray-Remuxen als S_HDMV/PGS).
 */
class PgsDecoder {
    private val palette = IntArray(256)
    private var screenWidth = 1920
    private var screenHeight = 1080
    private class Composition(val objectId: Int, val x: Int, val y: Int)
    private class Object(var width: Int = 0, var height: Int = 0, var data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(), var expected: Int = 0)
    private val objects = HashMap<Int, Object>()

    /** Ein Anzeigesatz → Bilder (leer = Untertitel ausblenden), null = nichts Darstellbares. */
    fun decodeImages(data: ByteArray): List<SubtitleImage>? {
        var pos = 0
        val compositions = ArrayList<Composition>()
        var sawComposition = false
        while (pos + 3 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val length = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val body = pos + 3
            if (body + length > data.size) break
            when (type) {
                0x14 -> palette(data, body, length)
                0x15 -> objectData(data, body, length)
                0x16 -> {
                    sawComposition = true
                    composition(data, body, length, compositions)
                }
            }
            pos = body + length
        }
        if (!sawComposition) return null
        val images = ArrayList<SubtitleImage>()
        for (c in compositions) {
            val o = objects[c.objectId] ?: continue
            val pixels = render(o) ?: continue
            images += SubtitleImage(c.x, c.y, o.width, o.height, pixels, screenWidth, screenHeight)
        }
        return images
    }

    private fun palette(d: ByteArray, at: Int, length: Int) {
        var p = at + 2
        while (p + 5 <= at + length) {
            val index = d[p].toInt() and 0xFF
            val y = d[p + 1].toInt() and 0xFF
            val cr = d[p + 2].toInt() and 0xFF
            val cb = d[p + 3].toInt() and 0xFF
            val a = d[p + 4].toInt() and 0xFF
            val r = (y + 1.402 * (cr - 128)).toInt().coerceIn(0, 255)
            val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt().coerceIn(0, 255)
            val b = (y + 1.772 * (cb - 128)).toInt().coerceIn(0, 255)
            palette[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
            p += 5
        }
    }

    private fun objectData(d: ByteArray, at: Int, length: Int) {
        if (length < 4) return
        val id = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)
        val sequence = d[at + 3].toInt() and 0xFF
        var p = at + 4
        val o = objects.getOrPut(id) { Object() }
        if (sequence and 0x80 != 0) {
            // Erster Teil: Länge und Größe.
            if (length < 11) return
            o.expected = (((d[p].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)) - 4
            o.width = ((d[p + 3].toInt() and 0xFF) shl 8) or (d[p + 4].toInt() and 0xFF)
            o.height = ((d[p + 5].toInt() and 0xFF) shl 8) or (d[p + 6].toInt() and 0xFF)
            o.data = java.io.ByteArrayOutputStream()
            p += 7
        }
        o.data.write(d, p, at + length - p)
    }

    private fun composition(d: ByteArray, at: Int, length: Int, out: MutableList<Composition>) {
        if (length < 11) return
        screenWidth = (((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)).takeIf { it > 0 } ?: screenWidth
        screenHeight = (((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)).takeIf { it > 0 } ?: screenHeight
        val count = d[at + 10].toInt() and 0xFF
        var p = at + 11
        repeat(count) {
            if (p + 8 > at + length) return
            val objectId = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            val cropped = (d[p + 3].toInt() and 0x80) != 0
            val x = ((d[p + 4].toInt() and 0xFF) shl 8) or (d[p + 5].toInt() and 0xFF)
            val y = ((d[p + 6].toInt() and 0xFF) shl 8) or (d[p + 7].toInt() and 0xFF)
            out += Composition(objectId, x, y)
            p += 8 + if (cropped) 8 else 0
        }
    }

    /** Lauflängen-kodiertes Bild (PGS-RLE) → ARGB-Pixel. */
    private fun render(o: Object): IntArray? {
        if (o.width <= 0 || o.height <= 0 || o.width > 4096 || o.height > 4096) return null
        val rle = o.data.toByteArray()
        val pixels = IntArray(o.width * o.height)
        var i = 0
        var index = 0
        while (i < rle.size && index < pixels.size) {
            var color = rle[i++].toInt() and 0xFF
            if (color != 0) {
                pixels[index++] = palette[color]
                continue
            }
            if (i >= rle.size) break
            val flags = rle[i++].toInt() and 0xFF
            if (flags == 0) {
                // Zeilenende: auf den Zeilenanfang runden.
                if (index % o.width != 0) index = (index / o.width + 1) * o.width
                continue
            }
            var run = flags and 0x3F
            if (flags and 0x40 != 0) {
                if (i >= rle.size) break
                run = (run shl 8) or (rle[i++].toInt() and 0xFF)
            }
            color = if (flags and 0x80 == 0) 0 else {
                if (i >= rle.size) break
                rle[i++].toInt() and 0xFF
            }
            val argb = palette[color]
            val end = (index + run).coerceAtMost(pixels.size)
            if (argb != 0) java.util.Arrays.fill(pixels, index, end, argb)
            index = end
        }
        return pixels
    }
}

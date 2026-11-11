package de.ayutv.player.dna.engine

/** Ein Bild-Untertitel: Lage auf der Seite, Größe, ARGB-Pixel; Seitengröße zur Umrechnung in Anteile. */
class SubtitleImage(val x: Int, val y: Int, val width: Int, val height: Int, val pixels: IntArray, val displayWidth: Int, val displayHeight: Int) {
    val opaquePixels: Int get() = pixels.count { (it ushr 24) != 0 }
}

/**
 * DVB-Untertitel (ETSI EN 300 743, Bilduntertitel im Fernsehen): Seiten-, Regions-, Farbtabellen- und Objekt-Abschnitte
 * eines PES-Pakets → je Region ein Bild an seiner Stelle. [compositionPage]/[ancillaryPage] aus dem Untertitel-Deskriptor.
 * Reines Kotlin (Android und Windows).
 */
class DvbSubtitleDecoder(private val compositionPage: Int, private val ancillaryPage: Int) {
    private class RegionRef(val id: Int, val x: Int, val y: Int)
    private class ObjectRef(val id: Int, val x: Int, val y: Int)
    private class Region(
        val id: Int, val fill: Boolean, val width: Int, val height: Int, val depth: Int, val clutId: Int,
        val code8: Int, val code4: Int, val code2: Int, val objects: List<ObjectRef>,
    )
    private class Clut(val id: Int) {
        val c2 = default2()
        val c4 = default4()
        val c8 = default8()
    }
    private class ObjectData(val top: ByteArray, val bottom: ByteArray, val nonModifying: Boolean)

    private var displayWidth = 720
    private var displayHeight = 576
    private var regionRefs: List<RegionRef> = emptyList()
    private val regions = HashMap<Int, Region>()
    private val cluts = HashMap<Int, Clut>()
    private val objects = HashMap<Int, ObjectData>()
    private val ancillaryObjects = HashMap<Int, ObjectData>()
    private val defaultClut = Clut(-1)

    /** Ein PES-Paket (data_identifier 0x20) → Bilder; leere Liste = ausblenden; null = nichts Neues. */
    fun decodeImages(data: ByteArray): List<SubtitleImage>? {
        if (data.size < 2 || (data[0].toInt() and 0xFF) != 0x20) return null
        var pos = 2
        var sawPage = false
        while (pos + 6 <= data.size && (data[pos].toInt() and 0xFF) == 0x0F) {
            val type = data[pos + 1].toInt() and 0xFF
            val page = u16(data, pos + 2)
            val length = u16(data, pos + 4)
            val body = pos + 6
            if (body + length > data.size) break
            if (page == compositionPage || page == ancillaryPage) {
                val bits = Bits(data, body, body + length)
                runCatching {
                    when (type) {
                        0x10 -> if (page == compositionPage) { pageComposition(bits); sawPage = true }
                        0x11 -> if (page == compositionPage) regionComposition(bits)
                        0x12 -> clutDefinition(bits)
                        0x13 -> objectData(bits, page == ancillaryPage)
                        0x14 -> if (page == compositionPage) displayDefinition(bits)
                    }
                }
            }
            pos = body + length
        }
        if (!sawPage) return null
        return render()
    }

    private fun pageComposition(b: Bits) {
        b.skip(8) // Zeitlimit
        b.skip(4) // Version
        val state = b.read(2)
        b.skip(2)
        if (state != 0) {
            // Neuanfang (Erfassung, Moduswechsel): alles bisher Bekannte gilt nicht mehr.
            regions.clear()
            cluts.clear()
            objects.clear()
        }
        val refs = ArrayList<RegionRef>()
        while (b.left() >= 48) {
            val id = b.read(8)
            b.skip(8)
            refs += RegionRef(id, b.read(16), b.read(16))
        }
        regionRefs = refs
    }

    private fun regionComposition(b: Bits) {
        val id = b.read(8)
        b.skip(4)
        val fill = b.read(1) == 1
        b.skip(3)
        val width = b.read(16)
        val height = b.read(16)
        b.skip(3) // Kompatibilität
        val depth = b.read(3)
        b.skip(2)
        val clutId = b.read(8)
        val code8 = b.read(8)
        val code4 = b.read(4)
        val code2 = b.read(2)
        b.skip(2)
        val objs = ArrayList<ObjectRef>()
        while (b.left() >= 48) {
            val objectId = b.read(16)
            val objectType = b.read(2)
            b.skip(2)
            val x = b.read(12)
            b.skip(4)
            val y = b.read(12)
            if (objectType == 1 || objectType == 2) b.skip(16)
            objs += ObjectRef(objectId, x, y)
        }
        regions[id] = Region(id, fill, width, height, depth, clutId, code8, code4, code2, objs)
    }

    private fun clutDefinition(b: Bits) {
        val id = b.read(8)
        b.skip(8)
        val clut = cluts.getOrPut(id) { Clut(id) }
        while (b.left() >= 16) {
            val entry = b.read(8)
            val flags = b.read(8)
            var y: Int
            var cr: Int
            var cb: Int
            var t: Int
            if (flags and 0x01 != 0) {
                y = b.read(8); cr = b.read(8); cb = b.read(8); t = b.read(8)
            } else {
                y = b.read(6) shl 2; cr = b.read(4) shl 4; cb = b.read(4) shl 4; t = b.read(2) shl 6
            }
            if (y == 0) { cr = 0; cb = 0; t = 255 }
            val a = 255 - t
            val r = (y + 1.402 * (cr - 128)).toInt().coerceIn(0, 255)
            val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt().coerceIn(0, 255)
            val bl = (y + 1.772 * (cb - 128)).toInt().coerceIn(0, 255)
            val color = argb(a, r, g, bl)
            if (flags and 0x80 != 0 && entry < 4) clut.c2[entry] = color
            if (flags and 0x40 != 0 && entry < 16) clut.c4[entry] = color
            if (flags and 0x20 != 0) clut.c8[entry and 0xFF] = color
        }
    }

    private fun objectData(b: Bits, ancillary: Boolean) {
        val id = b.read(16)
        b.skip(4)
        val method = b.read(2)
        val nonModifying = b.read(1) == 1
        b.skip(1)
        if (method != 0) return // Zeichen-Objekte: selten, nicht unterstützt
        val topLength = b.read(16)
        val bottomLength = b.read(16)
        val top = b.bytes(topLength)
        val bottom = if (bottomLength > 0) b.bytes(bottomLength) else top
        (if (ancillary) ancillaryObjects else objects)[id] = ObjectData(top, bottom, nonModifying)
    }

    private fun displayDefinition(b: Bits) {
        b.skip(4)
        b.skip(4)
        displayWidth = b.read(16) + 1
        displayHeight = b.read(16) + 1
    }

    private fun render(): List<SubtitleImage> {
        val images = ArrayList<SubtitleImage>()
        for (ref in regionRefs) {
            val region = regions[ref.id] ?: continue
            if (region.width <= 0 || region.height <= 0 || region.width > 4096 || region.height > 4096) continue
            val clut = cluts[region.clutId] ?: defaultClut
            val palette = when (region.depth) { 3 -> clut.c8; 2 -> clut.c4; else -> clut.c2 }
            val pixels = IntArray(region.width * region.height)
            if (region.fill) {
                val fill = when (region.depth) { 3 -> clut.c8[region.code8]; 2 -> clut.c4[region.code4]; else -> clut.c2[region.code2] }
                if (fill != 0) java.util.Arrays.fill(pixels, fill)
            }
            for (o in region.objects) {
                val data = objects[o.id] ?: ancillaryObjects[o.id] ?: continue
                paintField(data.top, pixels, region, palette, o.x, o.y)
                paintField(data.bottom, pixels, region, palette, o.x, o.y + 1)
            }
            if (pixels.all { (it ushr 24) == 0 }) continue
            images += SubtitleImage(ref.x, ref.y, region.width, region.height, pixels, displayWidth, displayHeight)
        }
        return images
    }

    /** Pixeldaten eines Halbbilds (jede zweite Zeile) in die Region malen. */
    private fun paintField(data: ByteArray, pixels: IntArray, region: Region, palette: IntArray, x0: Int, y0: Int) {
        val b = Bits(data, 0, data.size)
        var map2to4: IntArray? = null
        var map2to8: IntArray? = null
        var map4to8: IntArray? = null
        var line = y0
        var column = x0
        fun paint(run: Int, index: Int, map: IntArray?) {
            if (run <= 0) return
            val color = palette.getOrElse(map?.getOrNull(index) ?: index) { 0 }
            if (line in 0 until region.height) {
                val from = column.coerceAtLeast(0)
                val to = (column + run).coerceAtMost(region.width)
                if (from < to && color != 0) java.util.Arrays.fill(pixels, line * region.width + from, line * region.width + to, color)
            }
            column += run
        }
        while (b.left() >= 8) {
            when (b.read(8)) {
                0x10 -> {
                    val map = when (region.depth) { 3 -> map2to8 ?: MAP_2_TO_8; 2 -> map2to4 ?: MAP_2_TO_4; else -> null }
                    while (column < region.width) {
                        val peek = b.read(2)
                        if (peek != 0) { paint(1, peek, map); continue }
                        if (b.read(1) == 1) { val run = 3 + b.read(3); paint(run, b.read(2), map); continue }
                        if (b.read(1) == 1) { paint(1, 0, map); continue }
                        when (b.read(2)) {
                            0 -> break
                            1 -> paint(2, 0, map)
                            2 -> { val run = 12 + b.read(4); paint(run, b.read(2), map) }
                            3 -> { val run = 29 + b.read(8); paint(run, b.read(2), map) }
                        }
                        if (b.left() <= 0) break
                    }
                    b.align()
                }
                0x11 -> {
                    val map = if (region.depth == 3) map4to8 ?: MAP_4_TO_8 else null
                    while (column < region.width) {
                        val peek = b.read(4)
                        if (peek != 0) { paint(1, peek, map); continue }
                        if (b.read(1) == 0) {
                            val n = b.read(3)
                            if (n != 0) paint(2 + n, 0, map) else break
                            continue
                        }
                        if (b.read(1) == 0) { val run = 4 + b.read(2); paint(run, b.read(4), map); continue }
                        when (b.read(2)) {
                            0 -> paint(1, 0, map)
                            1 -> paint(2, 0, map)
                            2 -> { val run = 9 + b.read(4); paint(run, b.read(4), map) }
                            3 -> { val run = 25 + b.read(8); paint(run, b.read(4), map) }
                        }
                        if (b.left() <= 0) break
                    }
                    b.align()
                }
                0x12 -> {
                    // Wie FFmpeg: an der Zeilenbreite aufhören (manche Encoder schreiben das Ende verkürzt); ein übrig
                    // bleibendes Null-Byte überspringt die Schleife unten als unbekannten Datentyp.
                    while (column < region.width) {
                        val peek = b.read(8)
                        if (peek != 0) { paint(1, peek, null); continue }
                        if (b.read(1) == 0) {
                            val n = b.read(7)
                            if (n != 0) paint(n, 0, null) else break
                        } else {
                            val run = b.read(7)
                            paint(run, b.read(8), null)
                        }
                        if (b.left() <= 0) break
                    }
                }
                0x20 -> map2to4 = IntArray(4) { b.read(4) }
                0x21 -> map2to8 = IntArray(4) { b.read(8) }
                0x22 -> map4to8 = IntArray(16) { b.read(8) }
                0xF0 -> {
                    column = x0
                    line += 2
                }
                else -> Unit
            }
        }
    }

    private class Bits(private val d: ByteArray, from: Int, private val end: Int) {
        private var bit = from * 8
        fun left() = end * 8 - bit
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val i = bit / 8
                val b = if (i < end) (d[i].toInt() shr (7 - bit % 8)) and 1 else 0
                v = (v shl 1) or b
                bit++
            }
            return v
        }
        fun skip(n: Int) { bit += n }
        fun align() { bit = (bit + 7) / 8 * 8 }
        fun bytes(n: Int): ByteArray {
            align()
            val start = bit / 8
            val stop = (start + n).coerceAtMost(end)
            bit = stop * 8
            return if (stop > start) d.copyOfRange(start, stop) else ByteArray(0)
        }
    }

    companion object {
        private val MAP_2_TO_4 = intArrayOf(0x00, 0x07, 0x08, 0x0F)
        private val MAP_2_TO_8 = intArrayOf(0x00, 0x77, 0x88, 0xFF)
        private val MAP_4_TO_8 = IntArray(16) { it * 0x11 }

        private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)
        private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

        private fun default2() = intArrayOf(0x00000000, argb(255, 255, 255, 255), argb(255, 0, 0, 0), argb(255, 127, 127, 127))

        private fun default4(): IntArray = IntArray(16) { i ->
            if (i == 0) 0
            else if (i < 8) argb(255, if (i and 1 != 0) 255 else 0, if (i and 2 != 0) 255 else 0, if (i and 4 != 0) 255 else 0)
            else argb(255, if (i and 1 != 0) 127 else 0, if (i and 2 != 0) 127 else 0, if (i and 4 != 0) 127 else 0)
        }

        private fun default8(): IntArray = IntArray(256) { i ->
            if (i == 0) 0
            else if (i < 8) argb(63, if (i and 1 != 0) 255 else 0, if (i and 2 != 0) 255 else 0, if (i and 4 != 0) 255 else 0)
            else {
                fun c(low: Int, high: Int, a: Int, b: Int) = (if (i and low != 0) a else 0) + (if (i and high != 0) b else 0)
                when (i and 0x88) {
                    0x00 -> argb(255, c(1, 0x10, 85, 170), c(2, 0x20, 85, 170), c(4, 0x40, 85, 170))
                    0x08 -> argb(127, c(1, 0x10, 85, 170), c(2, 0x20, 85, 170), c(4, 0x40, 85, 170))
                    0x80 -> argb(255, 127 + c(1, 0x10, 43, 85), 127 + c(2, 0x20, 43, 85), 127 + c(4, 0x40, 43, 85))
                    else -> argb(255, c(1, 0x10, 43, 85), c(2, 0x20, 43, 85), c(4, 0x40, 43, 85))
                }
            }
        }
    }
}

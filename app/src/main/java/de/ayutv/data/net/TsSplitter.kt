package de.ayutv.data.net

/**
 * Teilt ein MPEG-TS-Stück eines Live-Senders (meist 6–10 s) in kurze Teile von etwa [TARGET_SECONDS] s, an Anfängen
 * von Bildern (am liebsten an Schlüsselbildern). Für LG webOS: Der Mediaplayer puffert einige Stücke voraus – bei
 * kurzen Teilen sind das nur wenige Sekunden des alten Senders, das Umschalten im laufenden Strom geht entsprechend
 * schneller.
 *
 * Jeder Teil beginnt mit den Programmtabellen (PAT/PMT) des Stücks, damit der Fernseher an jedem Teil einsteigen
 * kann. Reines Kotlin, ohne Umkodieren.
 */
object TsSplitter {
    class Part(val data: ByteArray, val seconds: Double)

    const val TARGET_SECONDS = 1.0
    /** Progressive: ab so vielen herausgegebenen Paketen wird der Puffer verkleinert (~ 2 MB). */
    private const val COMPACT_PACKETS = 10_000
    /** Ohne Schlüsselbild-Kennung so lange höchstens warten, dann an einem beliebigen Bildanfang teilen. */
    private const val MAX_SECONDS = 2.0
    private const val PACKET = 188

    /**
     * [data] in Teile zerlegen; [seconds] ist die Länge des ganzen Stücks laut Wiedergabeliste. Ist es kein TS (oder
     * fehlen Zeitstempel), kommt es unverändert als ein Teil zurück.
     */
    fun split(data: ByteArray, seconds: Double): List<Part> {
        if (data.size < PACKET * 4 || data[0] != 0x47.toByte()) return listOf(Part(data, seconds))
        val packets = data.size / PACKET
        // Programmtabellen und Bild-PID finden.
        var pmtPid = -1
        var videoPid = -1
        val psi = ArrayList<Int>()  // Paket-Nummern von PAT/PMT am Anfang (für jeden Teil kopiert)
        for (i in 0 until packets) {
            val o = i * PACKET
            if (data[o] != 0x47.toByte()) return listOf(Part(data, seconds))
            val pid = pid(data, o)
            if (pid == 0) {
                if (psi.size < 4) psi += i
                if (pmtPid < 0) pmtPid = patPmtPid(data, o)
            } else if (pid == pmtPid) {
                if (psi.size < 4) psi += i
                if (videoPid < 0) videoPid = pmtVideoPid(data, o)
            }
            if (videoPid >= 0 && psi.size >= 2) break
        }
        // Kandidaten: Bildanfänge (PES-Start der Bild-PID) mit Zeitstempel.
        class Cut(val packet: Int, val pts: Long, val key: Boolean)
        val cuts = ArrayList<Cut>()
        for (i in 0 until packets) {
            val o = i * PACKET
            if (pid(data, o) != videoPid || data[o + 1].toInt() and 0x40 == 0) continue
            val pts = pesPts(data, o) ?: continue
            val adaptation = (data[o + 3].toInt() shr 4) and 0x3
            val key = adaptation >= 2 && (data[o + 4].toInt() and 0xff) > 0 && (data[o + 5].toInt() and 0x40) != 0
            cuts += Cut(i, pts, key)
        }
        if (cuts.size < 2) return listOf(Part(data, seconds))
        val anyKey = cuts.any { it.key }
        // Schnittstellen wählen: frühestens nach TARGET_SECONDS, an Schlüsselbildern; ohne solche spätestens nach MAX_SECONDS.
        val chosen = ArrayList<Cut>()
        var last = cuts[0]
        for (c in cuts.drop(1)) {
            val since = ptsSeconds(c.pts - last.pts)
            if (since < TARGET_SECONDS) continue
            if (c.key || !anyKey && since >= TARGET_SECONDS || since >= MAX_SECONDS) {
                chosen += c
                last = c
            }
        }
        if (chosen.isEmpty()) return listOf(Part(data, seconds))
        val starts = listOf(0) + chosen.map { it.packet }
        val parts = ArrayList<Part>()
        var used = 0.0
        val prefix = psi.flatMap { p -> (p * PACKET until p * PACKET + PACKET).map { data[it] } }.toByteArray()
        for ((k, from) in starts.withIndex()) {
            val to = starts.getOrNull(k + 1) ?: packets
            val duration = if (k + 1 < starts.size) {
                val a = if (k == 0) cuts[0].pts else chosen[k - 1].pts
                ptsSeconds(chosen[k].pts - a)
            } else (seconds - used).coerceAtLeast(0.2)
            used += duration
            val body = data.copyOfRange(from * PACKET, to * PACKET)
            parts += Part(if (k == 0) body else prefix + body, duration)
        }
        return parts
    }

    /**
     * Wie [split], aber schon beim Laden: [feed] gibt jeden Teil heraus, sobald er vollständig ist (der nächste Bildanfang
     * an einer Schnittstelle ist da), [finish] den Rest. So bekommt der Fernseher nach dem Umschalten den ersten Teil
     * nach etwa einer Sekunde Bild statt erst nach dem ganzen Stück des Anbieters (6–10 s Bild, über den Proxy oft
     * 5–20 s Ladezeit).
     *
     * Anders als [split] wird ohne Schlüsselbild-Kennung erst nach [MAX_SECONDS] geteilt (ob das Stück überhaupt
     * welche hat, ist beim Laden noch nicht bekannt).
     */
    class Progressive(private val seconds: Double) {
        private var data = ByteArray(1 shl 20)
        private var size = 0
        private var scanned = 0
        private var pmtPid = -1
        private var videoPid = -1
        private val psi = ArrayList<Int>()
        private var prefix: ByteArray? = null
        private var lastCutPts = -1L
        private var partStart = 0
        private var emitted = 0
        private var used = 0.0
        /** Kein TS: am Ende unverändert als ein Teil (bzw. der Rest als letzter). */
        private var plain = false

        fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): List<Part> {
            // Durchgehender Strom (Live-TS): Herausgegebenes vorn abschneiden, sonst wüchse der Puffer ohne Ende.
            if (prefix != null && partStart >= COMPACT_PACKETS) {
                val from = partStart * PACKET
                System.arraycopy(data, from, data, 0, size - from)
                size -= from
                scanned -= partStart
                partStart = 0
            }
            if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
            System.arraycopy(chunk, offset, data, size, length)
            size += length
            if (plain) return emptyList()
            val out = ArrayList<Part>()
            val packets = size / PACKET
            while (scanned < packets) {
                val o = scanned * PACKET
                if (data[o] != 0x47.toByte()) {
                    plain = true
                    return out
                }
                val pid = pid(data, o)
                val collecting = prefix == null && !(videoPid >= 0 && psi.size >= 2)
                if (pid == 0) {
                    if (collecting && psi.size < 4) psi += scanned
                    if (pmtPid < 0) pmtPid = patPmtPid(data, o)
                } else if (pid == pmtPid) {
                    if (collecting && psi.size < 4) psi += scanned
                    if (videoPid < 0) videoPid = pmtVideoPid(data, o)
                }
                if (videoPid >= 0 && pid == videoPid && data[o + 1].toInt() and 0x40 != 0) {
                    val pts = pesPts(data, o)
                    if (pts != null) {
                        if (lastCutPts < 0) lastCutPts = pts
                        else {
                            val since = ptsSeconds(pts - lastCutPts)
                            val adaptation = (data[o + 3].toInt() shr 4) and 0x3
                            val key = adaptation >= 2 && (data[o + 4].toInt() and 0xff) > 0 && (data[o + 5].toInt() and 0x40) != 0
                            if (since >= TARGET_SECONDS && (key || since >= MAX_SECONDS)) {
                                out += cut(scanned, since)
                                lastCutPts = pts
                            }
                        }
                    }
                }
                scanned++
            }
            return out
        }

        private fun cut(at: Int, duration: Double): Part {
            val pre = prefix ?: psi.flatMap { p -> (p * PACKET until p * PACKET + PACKET).map { data[it] } }.toByteArray().also { prefix = it }
            val body = data.copyOfRange(partStart * PACKET, at * PACKET)
            val part = Part(if (emitted == 0) body else pre + body, duration)
            emitted++
            used += duration
            partStart = at
            return part
        }

        /** Rest des Stücks (nach dem Laden), Länge = Stücklänge minus schon herausgegebene Teile. */
        fun finish(): List<Part> {
            if (size == 0) return emptyList()
            if (emitted == 0) return listOf(Part(data.copyOf(size), seconds))
            val end = if (plain) size else size / PACKET * PACKET
            if (end <= partStart * PACKET) return emptyList()
            val body = data.copyOfRange(partStart * PACKET, end)
            return listOf(Part((prefix ?: ByteArray(0)) + body, (seconds - used).coerceAtLeast(0.2)))
        }
    }

    /**
     * Zähler der Programmtabellen (PAT/PMT) über alle Teile fortführen: Die kopierten Tabellen am Anfang jedes Teils
     * bekommen fortlaufende Werte, damit der Demuxer des Fernsehers keine Lücke sieht. [counters] gehört dem Aufrufer
     * (eine Tabelle je Strom), Werte 0..15 je PID.
     */
    fun renumberTables(part: ByteArray, counters: HashMap<Int, Int>) {
        var pmtPid = -1
        var o = 0
        while (o + PACKET <= part.size) {
            if (part[o] == 0x47.toByte()) {
                val pid = pid(part, o)
                if (pid == 0 && pmtPid < 0) pmtPid = patPmtPid(part, o)
                if ((pid == 0 || pid == pmtPid) && part[o + 3].toInt() and 0x10 != 0) {
                    val next = ((counters[pid] ?: 15) + 1) and 0xf
                    counters[pid] = next
                    part[o + 3] = ((part[o + 3].toInt() and 0xf0) or next).toByte()
                }
            }
            o += PACKET
        }
    }

    private fun pid(d: ByteArray, o: Int) = ((d[o + 1].toInt() and 0x1f) shl 8) or (d[o + 2].toInt() and 0xff)

    /** Beginn der Nutzdaten eines Pakets (nach dem Anpassungsfeld), -1 = keine. */
    private fun payload(d: ByteArray, o: Int): Int {
        val adaptation = (d[o + 3].toInt() shr 4) and 0x3
        if (adaptation and 0x1 == 0) return -1
        val start = if (adaptation == 3) o + 5 + (d[o + 4].toInt() and 0xff) else o + 4
        return if (start < o + PACKET) start else -1
    }

    /** PID der ersten PMT aus einer PAT. */
    private fun patPmtPid(d: ByteArray, o: Int): Int {
        if (d[o + 1].toInt() and 0x40 == 0) return -1
        var p = payload(d, o)
        if (p < 0) return -1
        p += 1 + (d[p].toInt() and 0xff)  // pointer_field
        if (p + 8 >= o + PACKET || d[p].toInt() != 0x00) return -1
        val length = ((d[p + 1].toInt() and 0x0f) shl 8) or (d[p + 2].toInt() and 0xff)
        var e = p + 8
        val end = minOf(p + 3 + length - 4, o + PACKET - 4)
        while (e + 4 <= end) {
            val program = ((d[e].toInt() and 0xff) shl 8) or (d[e + 1].toInt() and 0xff)
            val pid = ((d[e + 2].toInt() and 0x1f) shl 8) or (d[e + 3].toInt() and 0xff)
            if (program != 0) return pid
            e += 4
        }
        return -1
    }

    /** PID der ersten Bildspur aus einer PMT (MPEG-2, H.264, HEVC). */
    private fun pmtVideoPid(d: ByteArray, o: Int): Int {
        if (d[o + 1].toInt() and 0x40 == 0) return -1
        var p = payload(d, o)
        if (p < 0) return -1
        p += 1 + (d[p].toInt() and 0xff)
        if (p + 12 >= o + PACKET || d[p].toInt() != 0x02) return -1
        val length = ((d[p + 1].toInt() and 0x0f) shl 8) or (d[p + 2].toInt() and 0xff)
        val infoLength = ((d[p + 10].toInt() and 0x0f) shl 8) or (d[p + 11].toInt() and 0xff)
        var e = p + 12 + infoLength
        val end = minOf(p + 3 + length - 4, o + PACKET)
        while (e + 5 <= end) {
            val type = d[e].toInt() and 0xff
            val pid = ((d[e + 1].toInt() and 0x1f) shl 8) or (d[e + 2].toInt() and 0xff)
            if (type == 0x01 || type == 0x02 || type == 0x1b || type == 0x24 || type == 0x10) return pid
            e += 5 + (((d[e + 3].toInt() and 0x0f) shl 8) or (d[e + 4].toInt() and 0xff))
        }
        return -1
    }

    /** Anzeigezeit (90 kHz) aus dem PES-Kopf am Anfang des Pakets. */
    private fun pesPts(d: ByteArray, o: Int): Long? {
        val p = payload(d, o)
        if (p < 0 || p + 14 > o + PACKET) return null
        if (d[p].toInt() != 0 || d[p + 1].toInt() != 0 || d[p + 2].toInt() != 1) return null
        if (d[p + 7].toInt() and 0x80 == 0) return null
        val b = p + 9
        return ((d[b].toLong() and 0x0e) shl 29) or ((d[b + 1].toLong() and 0xff) shl 22) or ((d[b + 2].toLong() and 0xfe) shl 14) or
            ((d[b + 3].toLong() and 0xff) shl 7) or ((d[b + 4].toLong() and 0xfe) shr 1)
    }

    /** Zeitspanne in Sekunden (90 kHz, auch über den Überlauf bei 2^33 hinweg). */
    private fun ptsSeconds(delta: Long): Double {
        var d = delta
        if (d < -(1L shl 32)) d += 1L shl 33
        return d / 90_000.0
    }
}

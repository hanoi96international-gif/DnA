package de.ayutv.player

/**
 * Nach dem Neuverbinden schicken viele IPTV-Server zuerst noch einmal die letzten Sekunden aus ihrem
 * Puffer. Im Bild ist das ein Sprung zurück. Der Filter merkt sich je Elementarstrom (PID) den
 * Zeitstempel (PTS) der zuletzt begonnenen Einheit. Nach [reconnected] lässt er jeden Strom erst ab
 * genau dieser Einheit wieder durch (sie war vor dem Abbruch womöglich nur halb angekommen), die
 * Wiederholung davor fällt weg. Tabellen (PAT, PMT …) gehen immer durch. Sind es keine TS-Daten,
 * bleibt alles unverändert.
 *
 * Ausgegeben werden nur ganze 188-Byte-Pakete. Ein angefangenes Paket am Ende der alten Verbindung
 * wird verworfen, sonst stünde der Extractor danach versetzt.
 */
class TsReplayFilter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private enum class Mode { DETECT, TS, PASS }

    private class Stream {
        var lastStart = -1L
        var maxPts = -1L
        var passing = true
    }

    private var mode = Mode.DETECT
    private var work = ByteArray(16 * 1024)
    private var workLen = 0
    private val streams = HashMap<Int, Stream>()
    private var skipping = false
    private var skippedThisTime = 0L
    private var windowPid = -1
    private var windowAt = 0L
    private var windowPts = 0L
    private var newTimeBase = false

    /** Bytes, die als Wiederholung weggefallen sind (seit Beginn). */
    var skippedBytes = 0L
        private set

    val isTs: Boolean get() = mode == Mode.TS

    /**
     * Eigener Filter mit demselben Stand, für eine zweite Verbindung zum selben Sender (Live-Pause:
     * der Zwischenspeicher lädt weiter, wo der Player aufgehört hat).
     */
    @Synchronized fun copy(): TsReplayFilter = TsReplayFilter(clock).also { c ->
        if (mode != Mode.TS) return@also
        c.mode = Mode.TS
        for ((pid, s) in streams) c.streams[pid] = Stream().apply {
            lastStart = s.lastStart
            maxPts = s.maxPts
        }
    }

    /** Neue Verbindung zum selben Stream: Rest der alten verwerfen, Wiederholung überspringen. */
    @Synchronized fun reconnected() {
        workLen = 0
        if (mode != Mode.TS) return
        skippedThisTime = 0
        skipping = false
        windowPid = -1
        newTimeBase = false
        for (s in streams.values) if (s.lastStart >= 0) {
            s.passing = false
            skipping = true
        }
    }

    /** Verarbeitet [len] Bytes ab [off]; was bleibt, geht in zusammenhängenden Stücken an [out]. */
    @Synchronized fun process(data: ByteArray, off: Int, len: Int, out: (ByteArray, Int, Int) -> Unit) {
        if (len <= 0) return
        if (mode == Mode.PASS) {
            out(data, off, len)
            return
        }
        if (work.size < workLen + len) work = work.copyOf(maxOf(work.size * 2, workLen + len))
        System.arraycopy(data, off, work, workLen, len)
        workLen += len
        if (mode == Mode.DETECT) {
            val start = findSync(0, strict = true)
            when {
                start >= 0 -> mode = Mode.TS
                workLen >= DETECT_LIMIT -> {
                    mode = Mode.PASS
                    out(work, 0, workLen)
                    workLen = 0
                    return
                }
                else -> return
            }
        }
        var i = 0
        var run = -1
        while (i + PACKET <= workLen) {
            if (work[i] != SYNC) {
                if (run >= 0) out(work, run, i - run)
                run = -1
                val next = findSync(i + 1, strict = false)
                if (next < 0) {
                    i = maxOf(i, workLen - (PACKET - 1))
                    break
                }
                i = next
                continue
            }
            if (keep(i)) {
                if (run < 0) run = i
            } else {
                if (run >= 0) out(work, run, i - run)
                run = -1
                skippedBytes += PACKET
            }
            i += PACKET
        }
        if (run >= 0) out(work, run, i - run)
        val rest = workLen - i
        if (rest > 0) System.arraycopy(work, i, work, 0, rest)
        workLen = rest
    }

    /**
     * Ging es nach dem Neuverbinden nicht nahtlos weiter (anderer Zeitstempel, Server fängt woanders an),
     * meldet das dieser Wert einmal: Dann soll der Player wie bei einem neuen Stream neu ansetzen,
     * sonst liefen die Zeitstempel rückwärts.
     */
    @Synchronized fun takeNewTimeBase(): Boolean = newTimeBase.also { newTimeBase = false }

    /** Ende des Streams: noch nicht erkannte Daten unverändert ausgeben. */
    @Synchronized fun flush(out: (ByteArray, Int, Int) -> Unit) {
        if (mode == Mode.DETECT && workLen > 0) out(work, 0, workLen)
        if (mode != Mode.TS) workLen = 0
    }

    private fun keep(i: Int): Boolean {
        // Fehlerhafte Pakete (Transport-Error-Bit) nicht auswerten, aber weitergeben.
        if (work[i + 1].toInt() and 0x80 != 0) return true
        val pid = ((work[i + 1].toInt() and 0x1F) shl 8) or (work[i + 2].toInt() and 0xFF)
        val pts = pesPts(i)
        if (!skipping) {
            if (pts != null) track(pid, pts)
            return true
        }
        val s = streams[pid]
        if (s == null || s.passing) {
            if (pts != null) track(pid, pts)
            return true
        }
        if (pts == null) return dropped()
        val ahead = diff(pts, s.maxPts)
        when {
            // Genau die zuletzt begonnene Einheit oder etwas Neueres: nahtlos weiter.
            pts == s.lastStart || ahead > 0 -> s.passing = true
            // Ganz andere Zeitbasis (Sender neu gestartet) oder keine schnelle Wiederholung: nicht verwerfen.
            ahead < -RESTART_WINDOW || !fastReplay(pid, pts) -> passAll(newBase = true)
            else -> return dropped()
        }
        if (skipping && streams.values.all { it.passing }) skipping = false
        track(pid, pts)
        return true
    }

    /**
     * Eine Wiederholung schickt der Server als Schub, viel schneller als Echtzeit. Kommt das Alte nur
     * in Echtzeit, fängt der Server einfach woanders an: dann nicht sekundenlang Daten verwerfen.
     */
    private fun fastReplay(pid: Int, pts: Long): Boolean {
        val now = clock()
        if (windowPid < 0) {
            windowPid = pid
            windowAt = now
            windowPts = pts
            return true
        }
        if (pid != windowPid) return true
        val elapsed = now - windowAt
        if (elapsed < RATE_WINDOW_MS) return true
        val advancedMs = diff(pts, windowPts) / 90
        windowAt = now
        windowPts = pts
        return advancedMs * 10 >= elapsed * 13
    }

    private fun passAll(newBase: Boolean) {
        for (s in streams.values) s.passing = true
        skipping = false
        if (newBase) newTimeBase = true
    }

    private fun dropped(): Boolean {
        skippedThisTime += PACKET
        // Sicherung: Wiederholt der Server mehr als üblich, nicht ewig verwerfen.
        if (skippedThisTime > MAX_SKIP_BYTES) passAll(newBase = true)
        return false
    }

    private fun track(pid: Int, pts: Long) {
        val s = streams.getOrPut(pid) { Stream() }
        s.lastStart = pts
        val ahead = if (s.maxPts < 0) 1 else diff(pts, s.maxPts)
        if (ahead > 0 || ahead < -RESTART_WINDOW) s.maxPts = pts
    }

    /** PTS, wenn mit diesem Paket eine Ton-, Bild- oder Untertitel-Einheit (PES) beginnt. */
    private fun pesPts(i: Int): Long? {
        if (work[i + 1].toInt() and 0x40 == 0) return null
        val control = (work[i + 3].toInt() shr 4) and 0x03
        if (control and 0x01 == 0) return null
        var p = i + 4
        if (control and 0x02 != 0) p += 1 + (work[p].toInt() and 0xFF)
        if (p + 14 > i + PACKET) return null
        if (work[p].toInt() != 0 || work[p + 1].toInt() != 0 || work[p + 2].toInt() != 1) return null
        val streamId = work[p + 3].toInt() and 0xFF
        if (streamId !in 0xC0..0xEF && streamId != 0xBD) return null
        if (work[p + 7].toInt() and 0x80 == 0) return null
        fun b(k: Int) = work[p + 9 + k].toLong() and 0xFF
        return (((b(0) shr 1) and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
    }

    /** Sync-Byte ab [from], bestätigt durch die nächsten Pakete (am Pufferende genügt, was da ist). */
    private fun findSync(from: Int, strict: Boolean): Int {
        for (j in from until workLen) {
            if (work[j] != SYNC) continue
            if (strict && j + 2 * PACKET >= workLen) return -1
            if (j + PACKET < workLen && work[j + PACKET] != SYNC) continue
            if (j + 2 * PACKET < workLen && work[j + 2 * PACKET] != SYNC) continue
            return j
        }
        return -1
    }

    companion object {
        const val PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val DETECT_LIMIT = 4096
        private const val MAX_SKIP_BYTES = 48L * 1024 * 1024
        /** 30 s in 90-kHz-Takt: Liegt ein Zeitstempel weiter zurück, hat der Sender eine neue Zeitbasis. */
        private const val RESTART_WINDOW = 30L * 90_000
        private const val RATE_WINDOW_MS = 1_000L
        private const val WRAP = 1L shl 33

        /** Abstand zweier PTS mit Überlauf nach 33 Bit (alle 26,5 Stunden). */
        fun diff(a: Long, b: Long): Long {
            val d = (a - b) and (WRAP - 1)
            return if (d >= WRAP / 2) d - WRAP else d
        }
    }
}

package de.ayutv.data.parse

import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Was über die Qualität jeder Senderfassung bekannt ist: gemessene Auflösung (beim Schauen oder vom
 * Hintergrund-Check), Startzeit und ob sie zuletzt ausfiel. Daraus ergibt sich, welche Fassung eines doppelt
 * geführten Senders die beste ist, und welche Sender eine Mindestqualität erfüllen. Ohne Messung zählt, was
 * der Name verrät („FHD“, „4K“, „SD“ …).
 */
object ChannelQuality {
    /** [watchedMs]/[drops]: wie lange insgesamt geschaut und wie oft dabei die Verbindung abbrach. */
    data class Info(
        val height: Int, val startupMs: Long, val failures: Int, val checkedAt: Long, val watchedMs: Long = 0, val drops: Int = 0,
        /** Gemessene Datenrate des Senders (kbit/s), 0 = unbekannt. Bei gleicher Auflösung ist mehr = schärferes Bild. */
        val kbps: Int = 0,
    ) {
        val broken: Boolean get() = failures >= 2
    }

    private val known = ConcurrentHashMap<String, Info>()
    @Volatile private var dirty = false

    /** 4K bevorzugen (sonst gilt Full HD als beste Fassung: stabiler, weniger Daten). */
    @Volatile var preferUhd = false
    /** Mindesthöhe in Pixeln (0 = alle). Sender mit bekannter, kleinerer Auflösung werden ausgeblendet. */
    @Volatile var minHeight = 0
    /** Sender, die im Check wiederholt nicht liefen, ausblenden. */
    @Volatile var hideBroken = false

    fun configure(minHeight: Int, preferUhd: Boolean, hideBroken: Boolean) {
        this.minHeight = minHeight
        this.preferUhd = preferUhd
        this.hideBroken = hideBroken
    }

    fun info(key: String): Info? = known[key]

    val size: Int get() = known.size

    /** Beim Schauen gemessen (kostet nichts extra). */
    fun recordPlayback(key: String, height: Int, startupMs: Long, now: Long = System.currentTimeMillis()) {
        if (height <= 0) return
        val previous = known[key]
        known[key] = Info(height, startupMs.takeIf { it > 0 } ?: previous?.startupMs ?: 0, 0, now, previous?.watchedMs ?: 0, previous?.drops ?: 0, previous?.kbps ?: 0)
        dirty = true
    }

    /**
     * Datenrate des Senders (beim Schauen oder im Leitungs-Check gemessen). Live-Sender kommen in Echtzeit, die Rate
     * ist also die Qualität, mit der der Anbieter sendet. Gleitend gemittelt, damit ein kurzer Ausreißer nicht zählt.
     */
    fun recordBitrate(key: String, kbps: Int, now: Long = System.currentTimeMillis()) {
        if (kbps <= 100) return
        val previous = known[key] ?: Info(0, 0, 0, now)
        val mixed = if (previous.kbps > 0) (previous.kbps * 2 + kbps) / 3 else kbps
        known[key] = previous.copy(kbps = mixed)
        dirty = true
    }

    /** Vom Check gemessen oder ausgefallen ([height] 0). */
    fun recordCheck(key: String, height: Int, startupMs: Long, now: Long = System.currentTimeMillis()) {
        val previous = known[key]
        known[key] = if (height > 0) Info(height, startupMs, 0, now, previous?.watchedMs ?: 0, previous?.drops ?: 0, previous?.kbps ?: 0)
        else Info(previous?.height ?: 0, previous?.startupMs ?: 0, (previous?.failures ?: 0) + 1, now, previous?.watchedMs ?: 0, previous?.drops ?: 0, previous?.kbps ?: 0)
        dirty = true
    }

    /**
     * Nach dem Schauen: [watchedMs] lang gelaufen, [drops] Verbindungsabbrüche. Ältere Erfahrung zählt mit der Zeit
     * weniger (ab 20 Stunden wird halbiert), damit ein Sender, der besser geworden ist, wieder nach vorn kommt.
     */
    fun recordWatch(key: String, watchedMs: Long, drops: Int, now: Long = System.currentTimeMillis()) {
        if (watchedMs <= 0) return
        val previous = known[key] ?: Info(0, 0, 0, now)
        var watched = previous.watchedMs + watchedMs
        var dropped = previous.drops + drops
        if (watched > 20 * HOUR) {
            watched /= 2
            dropped /= 2
        }
        known[key] = previous.copy(watchedMs = watched, drops = dropped, failures = if (drops == 0) 0 else previous.failures)
        dirty = true
    }

    /**
     * Wie stabil eine Fassung ist, kleiner = besser; null, wenn nichts bekannt ist oder sie ausgefallen ist.
     * Zählt Abbrüche je Stunde Schauen am meisten, dann Ausfälle im Check, Startzeit und geringe Auflösung.
     * Nur im Check Gemessenes (nie geschaut) kommt hinter erprobte Sender ohne Abbrüche.
     */
    fun stability(key: String): Int? {
        val info = known[key] ?: return null
        if (info.broken || (info.height <= 0 && info.watchedMs == 0L)) return null
        val hours = info.watchedMs / HOUR.toDouble()
        val dropsPerHour = if (hours >= 0.25) info.drops / hours else if (info.watchedMs > 0) info.drops * 2.0 else 0.0
        var score = (dropsPerHour * 120).toInt() + info.failures * 300 + (info.startupMs / 40).toInt().coerceAtMost(250)
        if (info.height in 1..719) score += 150
        if (info.watchedMs == 0L) score += 60
        return score
    }

    fun recordFailure(key: String, now: Long = System.currentTimeMillis()) = recordCheck(key, 0, 0, now)

    /**
     * Läuft (etwa mit der DNA Engine, die keine Messwerte wie Media3 liefert): frühere Ausfälle vergessen, der Rest bleibt.
     * So gilt ein Sender, an dem nur der Decoder des Handys scheiterte, wieder als gut (auch für den Fernseher).
     */
    fun recordWorks(key: String, now: Long = System.currentTimeMillis()) {
        val previous = known[key] ?: return
        if (previous.failures == 0) return
        known[key] = previous.copy(failures = 0, checkedAt = now)
        dirty = true
    }

    /** Auflösung aus dem Namen: „4K/UHD/2160“, „FHD/1080“, „HD/720“, „SD/576/480“; sonst 0 (unbekannt). */
    fun heightFromName(name: String): Int {
        val n = " " + Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase() + " "
        return when {
            UHD.containsMatchIn(n) -> 2160
            FHD.containsMatchIn(n) -> 1080
            HD.containsMatchIn(n) -> 720
            SD.containsMatchIn(n) -> 576
            else -> 0
        }
    }

    /** Gemessene oder aus dem Namen geschätzte Auflösung, 0 = unbekannt. */
    fun height(key: String, name: String): Int = known[key]?.height?.takeIf { it > 0 } ?: heightFromName(name)

    /**
     * Bewertung einer Fassung, kleiner = besser. Ausgefallene ganz hinten; sonst die höchste Auflösung bis zur
     * Wunschgrenze (Full HD, mit [preferUhd] 4K), Fassungen darüber nach den passenden; Ersatz-/Backup-Fassungen
     * und sehr langsam startende etwas weiter hinten. Unbekannte Auflösung zählt wie 720p, knapp dahinter.
     */
    fun score(key: String, name: String, uhd: Boolean = preferUhd): Int {
        val info = known[key]
        val cap = if (uhd) 2160 else 1080
        val measured = height(key, name)
        val h = if (measured > 0) measured else 700
        var score = 3000 - minOf(h, cap)
        if (h > cap) score += 900
        if (BACKUP.containsMatchIn(" " + name.lowercase() + " ")) score += 600
        if (info != null && info.startupMs > 4_000) score += 60
        // Gleiche Auflösung: höhere Datenrate des Senders zuerst (je Mbit/s ein Punkt, höchstens 40; Auflösung zählt
        // in Hunderten und bleibt deshalb vorrangig).
        if (info != null && info.kbps > 0) score -= minOf(info.kbps / 1000, 40)
        if (info != null && info.broken) score += 10_000
        return score
    }

    /**
     * Für Sport: mindestens Full HD (darunter nur, wenn es nichts anderes gibt), dann die stabilste Fassung (Abbrüche je
     * Stunde Schauen, Ausfälle im Check zählen doppelt), dann die beste Bildqualität (4K vor Full HD).
     */
    fun sportScore(key: String, name: String): Int {
        val h = height(key, name)
        val belowFullHd = if (h in 1..1079) 5_000 else 0
        return belowFullHd + 2 * (stability(key) ?: 100) + score(key, name, uhd = true)
    }

    /** Erfüllt die Fassung die Mindestqualität (und läuft sie, falls ausgefallene ausgeblendet werden)? */
    fun passes(key: String, name: String): Boolean {
        if (hideBroken && known[key]?.broken == true) return false
        if (minHeight <= 0) return true
        val h = height(key, name)
        // Unbekannt bleibt sichtbar: lieber einmal SD sehen als einen Sender nie finden
        return h == 0 || h >= minHeight * 9 / 10
    }

    // ---------------------------------------------------------------- Speichern

    fun load(file: File) {
        runCatching {
            file.takeIf { it.isFile }?.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) known[p[0]] = Info(
                    p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0,
                    p.getOrNull(5)?.toLongOrNull() ?: 0, p.getOrNull(6)?.toIntOrNull() ?: 0, p.getOrNull(7)?.toIntOrNull() ?: 0,
                )
            }
        }
    }

    fun saveIfChanged(file: File) {
        if (!dirty) return
        dirty = false
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { out ->
                for ((key, i) in known) out.append(key.replace('\t', ' ')).append('\t').append(i.height.toString()).append('\t')
                    .append(i.startupMs.toString()).append('\t').append(i.failures.toString()).append('\t').append(i.checkedAt.toString())
                    .append('\t').append(i.watchedMs.toString()).append('\t').append(i.drops.toString())
                    .append('\t').append(i.kbps.toString()).append('\n')
            }
            tmp.renameTo(file)
        }
    }

    /** Nur für Tests. */
    fun clear() = known.clear()

    private const val HOUR = 3_600_000L

    private val UHD = Regex("""\b(uhd|4k|2160p?)\b""")
    private val FHD = Regex("""\b(fhd|1080[ip]?|full ?hd)\b""")
    private val HD = Regex("""\b(hd|720p?)\b""")
    private val SD = Regex("""\b(sd|576[ip]?|480[ip]?)\b""")
    private val BACKUP = Regex("""\b(backup|alt|bkp|reserve|test|bar)\b|\(2\)""")
}

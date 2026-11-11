package de.ayutv.data.parse

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wiedergabe-Protokoll für den Kurzbericht: je geschautem Sender eine Zeile (Bild, Datenrate, Ruckler, Neuverbindungen
 * mit Grund, Puffer, Netz, Weg). Höchstens [MAX] Einträge der letzten 48 Stunden, nur auf dem Gerät, ohne Adressen
 * oder Zugangsdaten – nur so viel, dass man Ruckler und Abbrüche einordnen kann.
 */
object WatchLog {
    data class Entry(
        /** Ende des Schauens (Unix-Zeit, ms). */
        val at: Long,
        val channel: String,
        val watchedS: Int,
        val height: Int,
        val fps: Float,
        val displayHz: Float,
        val codec: String,
        /** Datenrate des Senders: Mittel und schwächster Moment (kbit/s, 0 = unbekannt). */
        val avgKbps: Int,
        val lowKbps: Int,
        /** Knappster Puffer während des Schauens (s, -1 = unbekannt). */
        val minBufferS: Int,
        /** Sichtbare Hänger (Puffer leer) und deren Dauer insgesamt. */
        val stalls: Int,
        val stallS: Int,
        /** Neuverbindungen nach Grund ([KIND_SILENT], [KIND_CLOSED], [KIND_OTHER]). */
        val drops: Map<String, Int>,
        val droppedFrames: Int,
        val startMs: Int,
        val errors: Int,
        val route: String,
        val net: String,
        /** Schwächstes WLAN-Signal (dBm, 0 = unbekannt oder LAN). */
        val worstRssi: Int,
        /** Ton, z. B. „eac3 6ch im Gerät“ oder „eac3 6ch durchgereicht“ (leer bei älteren Einträgen). */
        val audio: String = "",
        /** Letzter Player-Fehler mit Ursache (leer = keiner). */
        val lastError: String = "",
    ) {
        val dropCount: Int get() = drops.values.sum()
    }

    const val MAX = 60
    const val KEEP_MS = 48 * 60 * 60 * 1000L
    const val KIND_SILENT = "keine Daten"
    const val KIND_CLOSED = "vom Anbieter beendet"
    const val KIND_OTHER = "andere"

    /** Grund einer Trennung ([de.ayutv.player.ConnectionDrops]) in eine der drei Gruppen. */
    fun kindOf(reason: String): String = when {
        "Zeitüberschreitung" in reason || "timeout" in reason.lowercase() -> KIND_SILENT
        reason.startsWith("Anbieter hat") -> KIND_CLOSED
        else -> KIND_OTHER
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').trim()

    fun encode(e: Entry): String = listOf(
        "1", e.at, clean(e.channel), e.watchedS, e.height, e.fps, e.displayHz, clean(e.codec), e.avgKbps, e.lowKbps,
        e.minBufferS, e.stalls, e.stallS, e.drops.entries.joinToString(";") { "${clean(it.key).replace(";", ",").replace("=", "-")}=${it.value}" },
        e.droppedFrames, e.startMs, e.errors, clean(e.route), clean(e.net), e.worstRssi, clean(e.audio), clean(e.lastError).take(160),
    ).joinToString("\t")

    fun decode(line: String): Entry? = runCatching {
        val f = line.split('\t')
        if (f.size < 20 || f[0] != "1") return null
        Entry(
            at = f[1].toLong(), channel = f[2], watchedS = f[3].toInt(), height = f[4].toInt(), fps = f[5].toFloat(),
            displayHz = f[6].toFloat(), codec = f[7], avgKbps = f[8].toInt(), lowKbps = f[9].toInt(), minBufferS = f[10].toInt(),
            stalls = f[11].toInt(), stallS = f[12].toInt(),
            drops = f[13].split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toInt() },
            droppedFrames = f[14].toInt(), startMs = f[15].toInt(), errors = f[16].toInt(), route = f[17], net = f[18], worstRssi = f[19].toInt(),
            audio = f.getOrNull(20).orEmpty(),
            lastError = f.getOrNull(21).orEmpty(),
        )
    }.getOrNull()

    /** Neuen Eintrag anhängen, Altes (über 48 h, mehr als [MAX]) verwerfen. */
    fun append(lines: List<String>, entry: Entry, now: Long): List<String> =
        (lines.mapNotNull(::decode) + entry).filter { now - it.at <= KEEP_MS }.takeLast(MAX).map(::encode)

    /** 25/50 fps auf einem 60-Hz-Bild: Jedes fünfte Bild steht doppelt so lange, Schwenks laufen ungleichmäßig. */
    fun cadenceMismatch(e: Entry): Boolean {
        val pal = e.fps in 24.5f..25.5f || e.fps in 49.5f..50.5f
        return pal && e.displayHz in 59f..61f
    }

    /** Zusammenfassung für den Bericht: Summen, häufigste Gründe, die schlechtesten Sender. */
    fun summary(entries: List<Entry>, now: Long): String {
        val recent = entries.filter { now - it.at <= KEEP_MS }
        if (recent.isEmpty()) return "Noch nichts geschaut (gezählt wird ab 30 s je Sender).\n"
        val out = StringBuilder()
        val hours = recent.sumOf { it.watchedS } / 3600f
        val stalls = recent.sumOf { it.stalls }
        val stallS = recent.sumOf { it.stallS }
        val drops = recent.flatMap { it.drops.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val dropTotal = drops.values.sum()
        out.append("${recent.size} Sender, ${fmt(hours)} h geschaut\n")
        out.append("Ruckler (Bild hing, Puffer leer): $stalls, zusammen $stallS s" +
            (if (hours >= 0.1f) " (${fmt(stalls / hours)} pro Stunde)" else "") + "\n")
        out.append("Neu verbunden: $dropTotal" + (if (dropTotal > 0) " (" + drops.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" } + ")" else "") +
            (if (hours >= 0.1f) ", ${fmt(dropTotal / hours)} pro Stunde" else "") + "\n")
        val rates = recent.filter { it.avgKbps > 0 }
        if (rates.isNotEmpty()) {
            val avg = rates.sumOf { it.avgKbps.toLong() * it.watchedS } / rates.sumOf { it.watchedS }.coerceAtLeast(1)
            val low = rates.filter { it.lowKbps > 0 }.minByOrNull { it.lowKbps }
            out.append("Datenrate: Mittel ${mbit(avg.toInt())}" + (low?.let { ", tiefster Wert ${mbit(it.lowKbps)} (${it.channel}, ${time(it.at)})" } ?: "") + "\n")
        }
        recent.filter { it.minBufferS >= 0 }.minByOrNull { it.minBufferS }?.let { out.append("Puffer am knappsten: ${it.minBufferS} s (${it.channel}, ${time(it.at)})\n") }
        out.append("Verworfene Bilder: ${recent.sumOf { it.droppedFrames }}, Player-Fehler: ${recent.sumOf { it.errors }}\n")
        // Verworfene Bilder je Tonweg: zeigt, ob das Dekodieren von Dolby im Gerät das Bild bremst.
        val byAudio = recent.filter { it.audio.isNotBlank() }.groupBy { if (it.audio.endsWith("im Gerät")) "Ton im Gerät dekodiert" else "Ton durchgereicht" }
        if (byAudio.size > 1 || byAudio.values.any { list -> list.any { it.droppedFrames > 0 } }) {
            out.append("Verworfene Bilder je Ton: " + byAudio.entries.joinToString { (way, list) ->
                "$way ${list.sumOf { it.droppedFrames }} (${list.size} Sender)"
            } + "\n")
        }
        val starts = recent.filter { it.startMs > 0 }.map { it.startMs }.sorted()
        if (starts.isNotEmpty()) out.append("Startzeit: Mitte ${starts[starts.size / 2]} ms, langsamster ${starts.last()} ms\n")
        val mismatch = recent.count(::cadenceMismatch)
        if (mismatch > 0) out.append("Bildrate passt nicht zum Fernseher (25/50 fps auf 60 Hz): $mismatch von ${recent.size} Sendern\n")
        out.append("Netz: " + recent.map { it.net }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .take(3).joinToString { it.key }.ifEmpty { "unbekannt" })
        recent.filter { it.worstRssi < 0 }.minByOrNull { it.worstRssi }?.let { out.append(" · schwächstes WLAN ${it.worstRssi} dBm") }
        out.append("\nWeg: " + recent.map { it.route }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString { "${it.key} (${it.value}×)" }.ifEmpty { "unbekannt" } + "\n")
        // Je Weg: Neuverbindungen pro Stunde (zeigt, ob es am Proxy liegt).
        val byRoute = recent.groupBy { if (it.route.contains("Proxy")) "über Proxy" else "direkt" }
        if (byRoute.size > 1) out.append("Neu verbunden pro Stunde: " + byRoute.entries.joinToString { (route, list) ->
            val h = list.sumOf { it.watchedS } / 3600f
            "$route ${if (h > 0f) fmt(list.sumOf { it.dropCount } / h) else "-"}"
        } + "\n")
        val worst = recent.filter { it.stalls > 0 || it.dropCount > 0 || it.errors > 0 || it.droppedFrames >= 100 || cadenceMismatch(it) }
            .sortedByDescending {
                it.stalls * 10 + it.dropCount * 3 + it.errors * 5 + it.droppedFrames / 200 + (if (cadenceMismatch(it)) 1 else 0)
            }.take(12)
        if (worst.isNotEmpty()) {
            out.append("Auffällige Sender:\n")
            worst.forEach { out.append("  ").append(line(it)).append('\n') }
        }
        return out.toString()
    }

    fun line(e: Entry): String = buildString {
        append("${time(e.at)} ${e.channel} · ${e.watchedS / 60} min")
        if (e.height > 0) append(" · ${e.height}p" + (if (e.fps > 0f) fmt(e.fps).removeSuffix(",0") else "") + " ${e.codec}".trimEnd())
        if (e.displayHz > 0f) append(" auf ${fmt(e.displayHz).removeSuffix(",0")} Hz")
        if (e.avgKbps > 0) append(" · ${mbit(e.avgKbps)}" + (if (e.lowKbps > 0) " (Tief ${mbit(e.lowKbps)})" else ""))
        if (e.stalls > 0) append(" · Ruckler ${e.stalls} (${e.stallS} s)")
        if (e.dropCount > 0) append(" · neu verbunden ${e.dropCount} (" + e.drops.entries.joinToString { "${it.key} ${it.value}" } + ")")
        if (e.minBufferS >= 0) append(" · Puffer min ${e.minBufferS} s")
        if (e.audio.isNotBlank()) append(" · Ton ${e.audio}")
        if (e.droppedFrames > 0) append(" · verworfen ${e.droppedFrames}")
        if (e.errors > 0) append(" · Fehler ${e.errors}" + (if (e.lastError.isNotBlank()) " (zuletzt ${e.lastError})" else ""))
        if (e.route.isNotBlank()) append(" · ${e.route}")
        if (e.worstRssi < 0) append(" · WLAN bis ${e.worstRssi} dBm")
    }

    private fun fmt(v: Float) = String.format(Locale.GERMANY, "%.1f", v)
    private fun mbit(kbps: Int) = String.format(Locale.GERMANY, "%.1f Mbit/s", kbps / 1000f)
    private fun time(at: Long) = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(at))
}

package de.ayutv.data.net

import java.util.Locale

/**
 * Auswertung eines Speedtests (rein Kotlin, testbar): aus den Ankunftszeiten der Daten die mittlere Geschwindigkeit und
 * die schwächste volle Sekunde. Die schwächste Sekunde zählt fürs Ruckeln mehr als das Mittel: Ein Stream mit 40 Mbit/s
 * hängt, sobald die Leitung ein paar Sekunden darunter fällt.
 */
object SpeedStats {
    data class Download(
        /** Mittel ab dem ersten Byte (kbit/s). */
        val kbps: Int,
        /** Schwächste volle Sekunde nach der ersten (die erste ist Anlaufzeit der Verbindung). */
        val lowKbps: Int,
        val bytes: Long,
        val seconds: Double,
    )

    /**
     * [reads]: je gelesenem Block (Millisekunden seit dem ersten Byte, Bytes). Null, wenn zu wenig kam, um etwas zu sagen.
     */
    fun download(reads: List<Pair<Long, Int>>): Download? {
        if (reads.size < 2) return null
        val bytes = reads.sumOf { it.second.toLong() }
        val lastMs = reads.last().first.coerceAtLeast(1)
        if (bytes < 200_000) return null
        val perSecond = LongArray((lastMs / 1000).toInt() + 1)
        for ((ms, n) in reads) perSecond[(ms / 1000).toInt()] += n.toLong()
        // Volle Sekunden ohne die erste (Anlauf) und ohne die letzte (angebrochen).
        val full = perSecond.toList().dropLast(1).drop(1)
        val low = full.minOrNull()?.let { (it * 8 / 1000).toInt() }
        val kbps = (bytes * 8 / lastMs).toInt()
        return Download(kbps, low ?: kbps, bytes, lastMs / 1000.0)
    }

    /** Antwortzeiten (ms): die erste enthält den Verbindungsaufbau und zählt nicht; Mitte und Schwankung der übrigen. */
    fun ping(times: List<Long>): Pair<Long, Long>? {
        val rest = (if (times.size > 1) times.drop(1) else times).sorted()
        if (rest.isEmpty()) return null
        return rest[rest.size / 2] to (rest.last() - rest.first())
    }

    /**
     * Was die Leitung schafft, gemessen an der schwächsten Sekunde. Richtwerte aus den eigenen Kurzberichten: 4K-Sport
     * kommt mit 28–40 Mbit/s, Full HD mit 8–19 Mbit/s; zum Auffüllen des Puffers braucht es etwas Luft darüber.
     */
    fun verdict(d: Download): String = when {
        d.lowKbps >= 45_000 -> "reicht für 4K"
        d.lowKbps >= 25_000 -> "Full HD sicher, 4K knapp (4K-Sender mit 40 Mbit/s können hängen)"
        d.lowKbps >= 12_000 -> "reicht für Full HD, zu langsam für 4K"
        d.lowKbps >= 6_000 -> "Full HD knapp, kleinere Fassungen (HD/SD) laufen"
        else -> "zu langsam für Live-TV in HD"
    }

    fun mbit(kbps: Int): String = String.format(Locale.GERMANY, "%.1f Mbit/s", kbps / 1000f)
}

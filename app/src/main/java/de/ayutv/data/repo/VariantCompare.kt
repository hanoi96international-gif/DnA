package de.ayutv.data.repo

import de.ayutv.data.LiveFormat
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holt das Maximum aus einem Sender heraus: Alle Fassungen desselben Senders in allen Playlists (z. B. „UHD“, „UHD
 * (EVENT)“, „4K RAW“, ein zweiter Anbieter) und bei Xtream-Anbietern beide Formate (MPEG-TS und HLS) werden je einige
 * Sekunden gemessen. Live-Sender kommen in Echtzeit: Die gemessene Datenrate ist die Bildqualität, mit der der Anbieter
 * sendet. Die Ergebnisse fließen in die Wahl der besten Fassung ein (bei gleicher Auflösung gewinnt mehr Datenrate).
 */
class VariantCompare(private val db: AyuDatabase, private val check: LineCheck) {
    data class Candidate(val channel: ChannelEntity, val hls: Boolean, val kbps: Int) {
        val label: String get() = channel.name + if (hls) " (HLS)" else ""
    }

    data class Outcome(val candidates: List<Candidate>, val current: Candidate?) {
        val best: Candidate? get() = candidates.filter { it.kbps > 0 }.maxByOrNull { it.kbps }
        /** Die beste ist klar besser (mehr als 10 %) als die gerade geschaute. */
        val better: Candidate? get() = best?.takeIf { b -> current == null || b.kbps > current.kbps * 11 / 10 }
            ?.takeIf { b -> b.channel.key != current?.channel?.key || b.hls != current?.hls }
        /**
         * HLS ist beim selben Sender klar besser als MPEG-TS (mindestens 20 % mehr Datenrate, oder TS kommt gar nicht
         * an): Nur dann lohnt die Einstellung „Live-Format: HLS“ (sie kostet Pause/Zurückspulen im Live-Bild).
         */
        val hlsWins: Boolean get() {
            val ts = candidates.filter { !it.hls }.associateBy { it.channel.key }
            return candidates.any { h ->
                if (!h.hls || h.kbps <= 0) return@any false
                val plain = ts[h.channel.key]?.kbps ?: 0
                plain <= 0 || h.kbps.toLong() * 10 > plain.toLong() * 12
            }
        }

        fun text(): String = if (candidates.size <= 1) "" else
            "Fassungen: " + candidates.sortedByDescending { it.kbps }.joinToString(" · ") { c ->
                c.label + ": " + (if (c.kbps <= 0) "kein Empfang" else "%.1f Mbit/s".format(c.kbps / 1000.0)) + if (c === best) " ✓" else ""
            }
    }

    /** Alle Fassungen von [channel] (auch aus anderen Playlists), höchstens [limit] Sender. */
    suspend fun variants(channel: ChannelEntity, limit: Int = 6): List<ChannelEntity> = withContext(Dispatchers.Default) {
        val key = ChannelVariants.key(channel.name) ?: return@withContext listOf(channel)
        val playlists = db.playlists().all().filter { it.enabled }
        val others = playlists.flatMap { p -> db.channels().visibleInPlaylist(p.id) }
            .filter { it.key != channel.key && ChannelVariants.key(it.name) == key }
            .sortedBy { ChannelQuality.score(it.key, it.name) }
        listOf(channel) + others.take(limit - 1)
    }

    /**
     * Misst [channel] und seine Fassungen. [current] = schon gemessene Datenrate des Senders selbst (spart eine Messung).
     * [format]: Format, in dem der Player Live-TV gerade abspielt (dessen Werte zählen für die Fassungswahl).
     */
    suspend fun run(channel: ChannelEntity, userAgent: String, format: LiveFormat, current: Int? = null): Outcome {
        val list = ArrayList<Candidate>()
        for (v in variants(channel)) {
            val agent = v.userAgent ?: userAgent
            val ts = if (v.key == channel.key && current != null) current
            else check.run(v, agent, seconds = 5).let { if (it.failed == null) it.kbps else 0 }
            list += Candidate(v, hls = false, kbps = ts)
            // Xtream: derselbe Sender auch als HLS (manche Anbieter schicken dort eine andere Qualität).
            val hlsUrl = hlsForm(v.url)
            val hls = hlsUrl?.let { check.hls(it, agent) }
            if (hls != null) list += Candidate(v, hls = true, kbps = hls)
            // Für die Fassungswahl zählt das Format, in dem tatsächlich abgespielt wird.
            val played = if (format == LiveFormat.HLS && hls != null) hls else ts
            if (played > 0) ChannelQuality.recordBitrate(v.key, played)
        }
        val own = list.firstOrNull { it.channel.key == channel.key && it.hls == (format == LiveFormat.HLS && hlsForm(channel.url) != null) }
        return Outcome(list, own)
    }

    companion object {
        /** HLS-Adresse eines Xtream-Live-Senders (…/live/…/123.ts → …/123.m3u8), sonst null. */
        fun hlsForm(url: String): String? = url.takeIf { "/live/" in it && it.endsWith(".ts") }?.let { it.removeSuffix(".ts") + ".m3u8" }
    }
}

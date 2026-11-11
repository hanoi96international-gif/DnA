package de.ayutv.desktop.player

import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.desktop.Log
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import kotlinx.coroutines.delay
import java.io.File

/**
 * Sender-Check in der Ruhezeit (wie auf dem Fire TV): Fassungen doppelt geführter Sender und Favoriten kurz unsichtbar
 * anlesen (DNA Engine, [de.ayutv.player.dna.engine.DnaProbe]: bis zum ersten Schlüsselbild, ohne Bild und Ton). Gemessen werden Auflösung, Startzeit und ob die Fassung überhaupt läuft.
 * Danach zeigt die Liste je Sender die wirklich beste Fassung, und ausgefallene Fassungen landen hinten.
 * Läuft nur, wenn nichts abgespielt wird (Verbindungslimit beim Anbieter), höchstens alle 12 Stunden.
 */
class ChannelCheck(private val store: Store) {
    val file = File(store.dir, "channel-quality.txt")
    @Volatile var lastRun = 0L; private set
    @Volatile var running = false; private set

    init {
        runCatching { ChannelQuality.load(file) }
    }

    fun save() = runCatching { ChannelQuality.saveIfChanged(file) }

    /** Welche Sender geprüft werden: alle Fassungen doppelter Sender und die Favoriten, älteste Prüfung zuerst. */
    fun candidates(limit: Int = 40, now: Long = System.currentTimeMillis()): List<Channel> {
        val visible = store.channels()
        val variants = ChannelVariants.groups(visible, { it.name }, { it.playlistId }).flatten()
        val favorites = store.favorites()
        return (favorites + variants).distinctBy { it.key }
            .filter { now - (ChannelQuality.info(it.key)?.checkedAt ?: 0L) > RECHECK_MS }
            .sortedBy { ChannelQuality.info(it.key)?.checkedAt ?: 0L }
            .take(limit)
    }

    /** Eine Runde; [idle] wird vor jedem Sender gefragt (bricht ab, sobald jemand etwas abspielt). */
    suspend fun run(idle: () -> Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runChecks(idle) }

    private suspend fun runChecks(idle: () -> Boolean) {
        if (running) return
        running = true
        lastRun = System.currentTimeMillis()
        var checked = 0
        try {
            for (channel in candidates()) {
                if (!idle()) break
                val started = System.currentTimeMillis()
                // Bis zu 10 Sekunden auf das erste Schlüsselbild warten.
                val height = runCatching {
                    de.ayutv.player.dna.engine.DnaProbe.videoHeight(Network.http, channel.url, Sync.USER_AGENT) {
                        !idle() || System.currentTimeMillis() - started > 10_000
                    }
                }.getOrDefault(0).coerceAtLeast(0)
                if (!idle()) break
                ChannelQuality.recordCheck(channel.key, height, if (height > 0) System.currentTimeMillis() - started else 0)
                checked++
                delay(1_500)
            }
        } finally {
            running = false
            save()
            if (checked > 0) Log.info("Sender-Check: $checked Sender geprüft")
        }
    }

    fun release() = Unit

    companion object {
        const val EVERY_MS = 12 * 3_600_000L
        private const val RECHECK_MS = 3 * 24 * 3_600_000L
    }
}

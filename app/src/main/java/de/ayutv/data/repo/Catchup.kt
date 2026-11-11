package de.ayutv.data.repo

import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.parse.CatchupUrls

/** Archiv-Adressen für Sender aus der Datenbank (die Regeln stehen in [CatchupUrls]). */
object Catchup {
    /** Archiv-Adresse oder null, wenn der Sender kein Archiv hat oder die Sendung zu alt ist. */
    fun url(playlist: PlaylistEntity?, channel: ChannelEntity, start: Long, stop: Long, now: Long = System.currentTimeMillis()): String? =
        CatchupUrls.url(
            channel.url, channel.catchupDays, channel.catchupType, channel.catchupSource,
            xtreamPlaylist = playlist?.type == PlaylistType.XTREAM, timezone = playlist?.timezone,
            start = start, stop = stop, now = now,
        )
}

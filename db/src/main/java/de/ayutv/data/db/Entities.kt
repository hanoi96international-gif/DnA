package de.ayutv.data.db

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U, XTREAM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U: Playlist-URL. Xtream: Server-Basis-URL. */
    val url: String,
    val epgUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
    /** Ausgeschaltete Playlists bleiben gespeichert, erscheinen aber nirgends (Sender, Filme, Suche). */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Zeitzone des Xtream-Servers (für Archiv-Adressen, die in Serverzeit angegeben werden). */
    val timezone: String? = null,
)

/**
 * [key] ist über Synchronisierungen hinweg stabil (Playlist + Stream-ID bzw. URL), damit Favoriten
 * und Verlauf erhalten bleiben, obwohl die Zeilen bei jedem Sync neu geschrieben werden.
 */
@Entity(
    tableName = "channels",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("groupTitle"), Index("epgId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val name: String,
    val groupTitle: String,
    val logo: String?,
    val url: String,
    val epgId: String?,
    val number: Int,
    val catchupDays: Int = 0,
    val userAgent: String? = null,
    /** Archiv-Art aus der M3U (default, append, shift, flussonic, xc) und die Vorlage dafür. */
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

/** Zeilennummer und stabiler Schlüssel eines Senders. */
data class ChannelKeyId(val id: Long, val key: String)

/** Was der EPG-Abgleich von einem Sender braucht (kleiner als die ganze Zeile). */
data class EpgChannel(val id: Long, val name: String, val epgId: String?, val groupTitle: String = "")

/** Sender kurz gefasst, um Favoriten und eigene Gruppen nach neuem Laden wieder zuzuordnen. */
data class ChannelRef(val key: String, val name: String, val epgId: String?, val groupTitle: String)

@Entity(
    tableName = "programmes",
    indices = [Index(value = ["channelEpgId", "start"]), Index("playlistId"), Index("stop")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelEpgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    /** XMLTV-Untertitel: bei Fußball die Paarung, bei Serien der Folgentitel. */
    val subtitle: String? = null,
    /** Bild zur Sendung (XMLTV icon). */
    val icon: String? = null,
    /** [ProgrammeFlags]: Wiederholung, live, Erstausstrahlung. */
    @ColumnInfo(defaultValue = "0") val flags: Int = 0,
)

object ProgrammeFlags {
    const val REPEAT = 1
    const val LIVE = 2
    const val PREMIERE = 4
}

@Entity(
    tableName = "vod",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("category"), Index("added")],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val name: String,
    val category: String,
    val poster: String?,
    val rating: String?,
    val url: String,
    val added: Long,
)

@Entity(
    tableName = "series",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("category"), Index("added")],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val category: String,
    val cover: String?,
    val plot: String?,
    val rating: String?,
    val added: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val addedAt: Long,
    /** Reihenfolge in den Favoriten (von Hand sortiert); gleich: nach [addedAt]. */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)

/** Eigene Sendergruppe, Sender aus allen Playlists. */
@Entity(tableName = "custom_groups")
data class CustomGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
)

/** Sender ([key]) in einer eigenen Gruppe, an Stelle [position]. */
@Entity(tableName = "custom_group_items", primaryKeys = ["groupId", "key"], indices = [Index("key")])
data class CustomGroupItemEntity(
    val groupId: Long,
    val key: String,
    val position: Int,
)

/** Eigene Reihenfolge in einer Gruppe des Anbieters ([scope] = Gruppenname); übersteht neues Laden der Playlist. */
@Entity(tableName = "group_order", primaryKeys = ["scope", "key"])
data class GroupOrderEntity(
    val scope: String,
    val key: String,
    val position: Int,
)

/** Eigene Gruppe mit Anzahl der Sender (für die Gruppenspalte). */
data class CustomGroupCount(val id: Long, val name: String, val count: Int)

enum class HistoryKind { LIVE, VOD, EPISODE, CATCHUP }

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val key: String,
    val kind: HistoryKind,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val groupTitle: String?,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playCount: Int = 1,
    val watchedAt: Long,
)

data class GroupCount(val name: String, val count: Int)

/** Kopf einer Sendung (für die Prüfung, ob ein TV-Guide glaubwürdig ist). */
data class ProgrammeHead(val channelEpgId: String, val title: String, val subtitle: String?, val category: String?, val start: Long, val stop: Long)

enum class GroupKind { LIVE, VOD, SERIES }

/**
 * Vom Nutzer ausgeblendete Gruppe einer Playlist. Liegt bewusst in einer eigenen Tabelle:
 * Beim Sync werden Sender, Filme und Serien neu geschrieben, die Auswahl bleibt trotzdem erhalten.
 */
@Entity(tableName = "hidden_groups", primaryKeys = ["playlistId", "kind", "name"])
data class HiddenGroupEntity(
    val playlistId: Long,
    val kind: GroupKind,
    val name: String,
)

/**
 * Kindersicherung: Gruppen, die gerade gesperrt sind. Die Liste wird beim App-Start aus den
 * Einstellungen gefüllt und beim Entsperren mit der PIN geleert; die Views blenden sie aus.
 */
@Entity(tableName = "locked_groups", primaryKeys = ["playlistId", "kind", "name"])
data class LockedGroupEntity(
    val playlistId: Long,
    val kind: GroupKind,
    val name: String,
)

/** Gruppe mit Anzahl und Sichtbarkeit für die Gruppenverwaltung. */
data class GroupToggle(val name: String, val count: Int, val hidden: Boolean)

// Alle Listen der Oberfläche lesen aus diesen Views. Ausgeblendete Gruppen tauchen so nirgends auf
// (Listen, Suche, TV-Guide, Empfehlungen, Zapping), ohne dass jede Abfrage daran denken muss.
/*
 * Sichtbar ist, was zu einer eingeschalteten Playlist gehört und nicht in einer ausgeblendeten Gruppe liegt.
 * Die SQL-Texte sind Konstanten, weil die Migration die Views exakt so neu anlegen muss, wie Room sie erwartet.
 */
const val VISIBLE_CHANNELS_SQL = """SELECT * FROM channels c WHERE c.playlistId IN (SELECT id FROM playlists WHERE enabled = 1)
               AND NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = c.playlistId AND h.kind = 'LIVE' AND h.name = c.groupTitle)
               AND NOT EXISTS (SELECT 1 FROM locked_groups l
               WHERE l.playlistId = c.playlistId AND l.kind = 'LIVE' AND l.name = c.groupTitle)"""

const val VISIBLE_VOD_SQL = """SELECT * FROM vod v WHERE v.playlistId IN (SELECT id FROM playlists WHERE enabled = 1)
               AND NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = v.playlistId AND h.kind = 'VOD' AND h.name = v.category)
               AND NOT EXISTS (SELECT 1 FROM locked_groups l
               WHERE l.playlistId = v.playlistId AND l.kind = 'VOD' AND l.name = v.category)"""

const val VISIBLE_SERIES_SQL = """SELECT * FROM series s WHERE s.playlistId IN (SELECT id FROM playlists WHERE enabled = 1)
               AND NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = s.playlistId AND h.kind = 'SERIES' AND h.name = s.category)
               AND NOT EXISTS (SELECT 1 FROM locked_groups l
               WHERE l.playlistId = s.playlistId AND l.kind = 'SERIES' AND l.name = s.category)"""

/**
 * Favoriten und eigene Gruppen: Diese Sender bleiben da, auch wenn ihre Gruppe beim Anbieter ausgeblendet ist. Nur eine
 * ausgeschaltete Playlist und die Kindersicherung blenden sie aus.
 */
const val USABLE_CHANNEL = """c.playlistId IN (SELECT id FROM playlists WHERE enabled = 1)
               AND NOT EXISTS (SELECT 1 FROM locked_groups l
               WHERE l.playlistId = c.playlistId AND l.kind = 'LIVE' AND l.name = c.groupTitle)"""

/**
 * Sender, die irgendwo in der App erscheinen: sichtbare Gruppen des Anbieters, dazu Favoriten und eigene Gruppen –
 * auch wenn deren Sender aus einer ausgeblendeten Gruppe stammen (wer Sender in eigene Gruppen verschiebt und die
 * Gruppe des Anbieters dann ausblendet, will sie in Sport, Suche und Erinnerungen trotzdem finden). Gesperrte Gruppen
 * (Kindersicherung) und ausgeschaltete Playlists bleiben draußen.
 */
const val LISTED_CHANNELS_SQL = """SELECT * FROM visible_channels
               UNION SELECT c.* FROM channels c WHERE $USABLE_CHANNEL
               AND (c.`key` IN (SELECT `key` FROM favorites) OR c.`key` IN (SELECT `key` FROM custom_group_items))"""

@DatabaseView(viewName = "visible_channels", value = VISIBLE_CHANNELS_SQL)
data class VisibleChannelView(val id: Long)

@DatabaseView(viewName = "visible_vod", value = VISIBLE_VOD_SQL)
data class VisibleVodView(val id: Long)

@DatabaseView(viewName = "visible_series", value = VISIBLE_SERIES_SQL)
data class VisibleSeriesView(val id: Long)

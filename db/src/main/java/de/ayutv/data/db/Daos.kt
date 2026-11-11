package de.ayutv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET password = :password WHERE id = :id")
    suspend fun setPassword(id: Long, password: String?)

    @Query("UPDATE playlists SET lastEpgSync = :time WHERE id = :id")
    suspend fun setLastEpgSync(id: Long, time: Long)

    @Query("UPDATE playlists SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM playlists WHERE id = :id")
    fun delete(id: Long)
}

@Dao
interface ChannelDao {
    /** Je Server (Schema + Host + Port) eine Adresse der Playlist: für den Proxy je Playlist. */
    @Query("SELECT url FROM channels WHERE playlistId = :playlistId GROUP BY substr(url, 1, instr(substr(url, 9), '/') + 8)")
    suspend fun originsOf(playlistId: Long): List<String>

    // Blockierende Varianten für den Import, der komplett in runInTransaction läuft.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT groupTitle AS name, COUNT(*) AS count FROM visible_channels GROUP BY groupTitle ORDER BY MIN(id)")
    fun observeGroups(): Flow<List<GroupCount>>

    /** Gruppe des Anbieters, in eigener Reihenfolge, wenn von Hand sortiert (sonst wie beim Anbieter). */
    @Query(
        """SELECT c.* FROM visible_channels c LEFT JOIN group_order o ON o.scope = c.groupTitle AND o.`key` = c.`key`
           WHERE c.groupTitle = :group ORDER BY o.position IS NULL, o.position, c.playlistId, c.number, c.id"""
    )
    fun observeInGroup(group: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM visible_channels ORDER BY playlistId, number, id")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM visible_channels c LEFT JOIN group_order o ON o.scope = c.groupTitle AND o.`key` = c.`key`
           WHERE c.groupTitle = :group ORDER BY o.position IS NULL, o.position, c.playlistId, c.number, c.id"""
    )
    suspend fun inGroup(group: String): List<ChannelEntity>

    @Query("SELECT * FROM visible_channels ORDER BY playlistId, number, id")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE `key` = :key")
    suspend fun byKey(key: String): ChannelEntity?

    /** Für „Auf dem Fernseher abspielen“: Die Schlüssel unterscheiden sich je Gerät, die Adresse nicht. */
    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ChannelEntity?

    /** Sender einer Playlist nach Zeile, sonst (nach neuem Laden) nach Name. */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (id = :rowId OR name = :name) ORDER BY (id = :rowId) DESC, id LIMIT 1")
    suspend fun find(playlistId: Long, rowId: Long, name: String): ChannelEntity?

    @Query("SELECT * FROM visible_channels WHERE number = :number ORDER BY id LIMIT 1")
    suspend fun byNumber(number: Int): ChannelEntity?

    /** Eigene Sendernummer setzen (oder die der Playlist wiederherstellen). */
    @Query("UPDATE channels SET number = :number WHERE `key` = :key")
    suspend fun setNumber(key: String, number: Int): Int

    /** Favoriten bleiben, auch wenn ihre Gruppe beim Anbieter ausgeblendet ist ([USABLE_CHANNEL]). */
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.`key` = c.`key` WHERE $USABLE_CHANNEL ORDER BY f.position, f.addedAt")
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.`key` = c.`key` WHERE $USABLE_CHANNEL ORDER BY f.position, f.addedAt")
    suspend fun favorites(): List<ChannelEntity>

    /** Sender einer eigenen Gruppe, in ihrer Reihenfolge (auch aus ausgeblendeten Gruppen des Anbieters). */
    @Query(
        """SELECT c.* FROM channels c INNER JOIN custom_group_items i ON i.`key` = c.`key`
           WHERE i.groupId = :groupId AND $USABLE_CHANNEL ORDER BY i.position, c.number, c.id"""
    )
    fun observeInCustom(groupId: Long): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c INNER JOIN custom_group_items i ON i.`key` = c.`key`
           WHERE i.groupId = :groupId AND $USABLE_CHANNEL ORDER BY i.position, c.number, c.id"""
    )
    suspend fun inCustom(groupId: Long): List<ChannelEntity>

    @Query(
        """SELECT * FROM ($LISTED_CHANNELS_SQL) WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, length(name), number
           LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM visible_channels WHERE groupTitle IN (:groups) AND `key` NOT IN (:exclude) ORDER BY playlistId, number, id LIMIT :limit")
    suspend fun inGroupsExcluding(groups: List<String>, exclude: List<String>, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM ($LISTED_CHANNELS_SQL) WHERE epgId IN (:epgIds) GROUP BY epgId")
    suspend fun byEpgIds(epgIds: List<String>): List<ChannelEntity>

    /** Alle Sender (auch alle Fassungen) mit diesen EPG-Kennungen, auch aus Favoriten und eigenen Gruppen. */
    @Query("SELECT * FROM ($LISTED_CHANNELS_SQL) WHERE epgId IN (:epgIds)")
    suspend fun allByEpgIds(epgIds: List<String>): List<ChannelEntity>

    /** Sender dieser Playlist, auf die Favoriten, eigene Gruppen oder eigene Reihenfolgen zeigen (vor dem Neuladen). */
    @Query(
        """SELECT `key`, name, epgId, groupTitle FROM channels WHERE playlistId = :playlistId
           AND (`key` IN (SELECT `key` FROM favorites) OR `key` IN (SELECT `key` FROM custom_group_items) OR `key` IN (SELECT `key` FROM group_order))"""
    )
    fun referenced(playlistId: Long): List<ChannelRef>

    @Query("SELECT `key`, name, epgId, groupTitle FROM channels WHERE playlistId = :playlistId")
    fun refs(playlistId: Long): List<ChannelRef>

    /** Favorit, eigene Gruppen und Reihenfolge von Schlüssel [old] auf [new] umschreiben (schon vorhandene bleiben). */
    @Query("UPDATE OR IGNORE favorites SET `key` = :new WHERE `key` = :old")
    fun moveFavorite(old: String, new: String)

    @Query("UPDATE OR IGNORE custom_group_items SET `key` = :new WHERE `key` = :old")
    fun moveCustomItem(old: String, new: String)

    @Query("UPDATE OR IGNORE group_order SET `key` = :new WHERE `key` = :old")
    fun moveOrder(old: String, new: String)

    @Query("SELECT id, name, epgId, groupTitle FROM channels WHERE playlistId = :playlistId ORDER BY number, id")
    suspend fun forEpg(playlistId: Long): List<EpgChannel>

    /** Wie [forEpg], ohne Coroutine (innerhalb des TV-Guide-Imports). */
    @Query("SELECT id, name, epgId, groupTitle FROM channels WHERE playlistId = :playlistId ORDER BY number, id")
    fun forEpgNow(playlistId: Long): List<EpgChannel>

    @Query("SELECT * FROM visible_channels WHERE playlistId = :playlistId")
    suspend fun visibleInPlaylist(playlistId: Long): List<ChannelEntity>

    /** Sender, die irgendwo erscheinen: sichtbare Gruppen, dazu Favoriten und eigene Gruppen (für den TV-Guide). */
    @Query(
        """SELECT id FROM visible_channels WHERE playlistId = :playlistId
           UNION SELECT id FROM channels WHERE playlistId = :playlistId
           AND (`key` IN (SELECT `key` FROM favorites) OR `key` IN (SELECT `key` FROM custom_group_items))"""
    )
    suspend fun visibleIds(playlistId: Long): List<Long>

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    fun setEpgId(id: Long, epgId: String)

    /** Logo aus dem TV-Guide, nur wenn der Anbieter keines liefert. */
    @Query("UPDATE channels SET logo = :logo WHERE id = :id AND (logo IS NULL OR logo = '')")
    fun setLogoIfMissing(id: Long, logo: String)

    @Query("SELECT id, `key` FROM channels WHERE playlistId = :playlistId")
    suspend fun keysFor(playlistId: Long): List<ChannelKeyId>

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channels WHERE playlistId = :playlistId")
    fun maxCatchupDays(playlistId: Long): Int

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM visible_channels")
    suspend fun maxCatchupDaysAll(): Int


    @Query("SELECT COUNT(*) FROM visible_channels")
    fun observeCount(): Flow<Int>
}

@Dao
interface ProgrammeDao {
    @Insert
    fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    /** Sendungen je Kennung im Zeitraum (für die Wahl des besten TV-Guides unter den Fassungen eines Senders). */
    @Query(
        """SELECT channelEpgId AS name, COUNT(*) AS count FROM programmes
           WHERE playlistId = :playlistId AND stop > :from AND start < :to GROUP BY channelEpgId"""
    )
    fun countsByChannel(playlistId: Long, from: Long, to: Long): List<GroupCount>

    /** Wie [countsByChannel], ohne Platzhalter („Sendepause“, „Programmpause“, „Keine Sendung“). */
    @Query(
        """SELECT channelEpgId AS name, COUNT(*) AS count FROM programmes
           WHERE playlistId = :playlistId AND stop > :from AND start < :to
           AND title NOT LIKE 'Sendepause%' AND title NOT LIKE 'Programmpause%' AND title NOT LIKE 'Keine Sendung%'
           AND title NOT LIKE 'Kein Programm%' AND title NOT LIKE 'Sendeschluss%' GROUP BY channelEpgId"""
    )
    fun realCountsByChannel(playlistId: Long, from: Long, to: Long): List<GroupCount>

    /** Köpfe der Sendungen dieser Kennungen im Zeitraum (ohne Coroutine, innerhalb des TV-Guide-Imports). */
    @Query(
        """SELECT channelEpgId, title, subtitle, category, start, stop FROM programmes
           WHERE playlistId = :playlistId AND channelEpgId IN (:ids) AND stop > :from AND start < :to"""
    )
    fun headsFor(playlistId: Long, ids: List<String>, from: Long, to: Long): List<ProgrammeHead>

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query(
        """SELECT * FROM programmes WHERE channelEpgId IN (:epgIds) AND stop > :from AND start < :to
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start"""
    )
    suspend fun inRange(epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /** Alles, was gerade läuft: eine Abfrage statt vieler IN-Listen bei großen Senderlisten. */
    @Query("SELECT * FROM programmes WHERE stop > :now AND start <= :now AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1)")
    suspend fun airingAt(now: Long): List<ProgrammeEntity>

    /**
     * Für Abfragen, die Room beim Bauen nicht prüfen soll (lange OR-Ketten bringen dessen SQL-Parser auf über eine
     * Stunde). Aufruf über [sportBetween].
     */
    @RawQuery
    suspend fun rawProgrammes(query: SupportSQLiteQuery): List<ProgrammeEntity>

    @Query(
        """SELECT * FROM programmes WHERE channelEpgId = :epgId AND stop > :from
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start LIMIT :limit"""
    )
    suspend fun upcoming(epgId: String, from: Long, limit: Int): List<ProgrammeEntity>

    @Query("""SELECT * FROM programmes WHERE (title LIKE '%' || :query || '%' OR subtitle LIKE '%' || :query || '%') AND stop > :now
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start LIMIT :limit""")
    suspend fun search(query: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes")
    fun observeCount(): Flow<Int>
}

@Dao
interface VodDao {
    @Query("SELECT url FROM vod WHERE playlistId = :playlistId GROUP BY substr(url, 1, instr(substr(url, 9), '/') + 8)")
    suspend fun originsOf(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<VodEntity>)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT category AS name, COUNT(*) AS count FROM visible_vod GROUP BY category ORDER BY MIN(id)")
    fun observeCategories(): Flow<List<GroupCount>>

    @Query("SELECT * FROM visible_vod WHERE category = :category ORDER BY id")
    fun observeInCategory(category: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM visible_vod ORDER BY added DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): VodEntity?

    /** Merkliste: gemerkte Filme, zuletzt gemerkte zuerst. */
    @Query("SELECT v.* FROM visible_vod v INNER JOIN favorites f ON f.`key` = v.`key` ORDER BY f.addedAt DESC")
    fun observeFavorites(): Flow<List<VodEntity>>

    @Query(
        """SELECT * FROM visible_vod WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<VodEntity>
}

@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT category AS name, COUNT(*) AS count FROM visible_series GROUP BY category ORDER BY MIN(id)")
    fun observeCategories(): Flow<List<GroupCount>>

    @Query("SELECT * FROM visible_series WHERE category = :category ORDER BY id")
    fun observeInCategory(category: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM visible_series ORDER BY added DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SeriesEntity>>

    /** Merkliste: gemerkte Serien, zuletzt gemerkte zuerst. */
    @Query("SELECT s.* FROM visible_series s INNER JOIN favorites f ON f.`key` = s.`key` ORDER BY f.addedAt DESC")
    fun observeFavorites(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun byKey(key: String): SeriesEntity?

    @Query(
        """SELECT * FROM visible_series WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<SeriesEntity>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT `key` FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT `key` FROM favorites")
    suspend fun keys(): List<String>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun clear()

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE `key` = :key)")
    suspend fun isFavorite(key: String): Boolean

    @Query("SELECT COALESCE(MAX(position), 0) FROM favorites")
    suspend fun maxPosition(): Int

    @Query("UPDATE favorites SET position = :position WHERE `key` = :key")
    suspend fun setPosition(key: String, position: Int)
}

@Dao
interface CustomGroupDao {
    @Query(
        """SELECT g.id, g.name, (SELECT COUNT(*) FROM custom_group_items i INNER JOIN channels c ON c.`key` = i.`key`
           WHERE i.groupId = g.id) AS count FROM custom_groups g ORDER BY g.position, g.id"""
    )
    fun observeGroups(): Flow<List<CustomGroupCount>>

    @Query("SELECT * FROM custom_groups ORDER BY position, id")
    suspend fun groups(): List<CustomGroupEntity>

    @Query("SELECT * FROM custom_groups WHERE id = :id")
    suspend fun group(id: Long): CustomGroupEntity?

    @Insert
    suspend fun insert(group: CustomGroupEntity): Long

    @Query("UPDATE custom_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE custom_groups SET position = :position WHERE id = :id")
    suspend fun setGroupPosition(id: Long, position: Int)

    @Query("DELETE FROM custom_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("DELETE FROM custom_group_items WHERE groupId = :id")
    suspend fun deleteItems(id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM custom_groups")
    suspend fun maxGroupPosition(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: CustomGroupItemEntity)

    @Query("DELETE FROM custom_group_items WHERE groupId = :groupId AND `key` = :key")
    suspend fun removeItem(groupId: Long, key: String)

    @Query("SELECT COALESCE(MAX(position), 0) FROM custom_group_items WHERE groupId = :groupId")
    suspend fun maxItemPosition(groupId: Long): Int

    @Query("UPDATE custom_group_items SET position = :position WHERE groupId = :groupId AND `key` = :key")
    suspend fun setItemPosition(groupId: Long, key: String, position: Int)

    /** Eigene Gruppen, in denen der Sender steht. */
    @Query("SELECT groupId FROM custom_group_items WHERE `key` = :key")
    suspend fun groupsOf(key: String): List<Long>

    @Query("SELECT * FROM custom_group_items ORDER BY groupId, position")
    suspend fun allItems(): List<CustomGroupItemEntity>

    @Query("DELETE FROM custom_groups")
    suspend fun clearGroups()

    /** Einträge in eigenen Gruppen, deren Sender es nicht mehr gibt (für den Fehlerbericht). */
    @Query("SELECT COUNT(*) FROM custom_group_items WHERE `key` NOT IN (SELECT `key` FROM channels)")
    suspend fun orphanItems(): Int

    @Query("DELETE FROM custom_group_items")
    suspend fun clearItems()
}

@Dao
interface GroupOrderDao {
    @Upsert
    suspend fun upsert(order: List<GroupOrderEntity>)

    @Query("DELETE FROM group_order WHERE scope = :scope")
    suspend fun clear(scope: String)

    @Query("SELECT * FROM group_order")
    suspend fun all(): List<GroupOrderEntity>
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun byKey(key: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE kind = 'LIVE' ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecentLive(limit: Int): Flow<List<HistoryEntity>>

    /** Angefangen, aber nicht zu Ende geschaut (weniger als 95 %). */
    @Query(
        """SELECT * FROM history WHERE kind != 'LIVE' AND positionMs > 60000
           AND (durationMs = 0 OR positionMs < durationMs * 0.95)
           ORDER BY watchedAt DESC LIMIT :limit"""
    )
    fun observeContinueWatching(limit: Int): Flow<List<HistoryEntity>>

    /** Filme und Folgen, zuletzt geschaute zuerst (für „Weiter schauen“: je Serie nur die neueste Folge). */
    @Query("SELECT * FROM history WHERE kind IN ('VOD', 'EPISODE') ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecentVod(limit: Int): Flow<List<HistoryEntity>>

    @Query(
        """SELECT groupTitle FROM history WHERE kind = 'LIVE' AND groupTitle IS NOT NULL AND groupTitle != ''
           GROUP BY groupTitle ORDER BY SUM(playCount) DESC LIMIT :limit"""
    )
    suspend fun topLiveGroups(limit: Int): List<String>

    @Query("SELECT `key` FROM history WHERE kind = 'LIVE' ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recentLiveKeys(limit: Int): List<String>

    /** Verlauf aller Folgen einer Serie (Schlüssel „<Serie>:e:…“): gesehen, angefangen. */
    @Query("SELECT * FROM history WHERE `key` LIKE :prefix || '%'")
    fun observeWithPrefix(prefix: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    fun observeByKey(key: String): Flow<HistoryEntity?>

    /** Für den Abgleich mit anderen Geräten: die zuletzt gesehenen Einträge. */
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface HiddenGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(groups: List<HiddenGroupEntity>)

    @Query("DELETE FROM hidden_groups WHERE playlistId = :playlistId AND kind = :kind AND name IN (:names)")
    suspend fun show(playlistId: Long, kind: GroupKind, names: List<String>)

    @Query("DELETE FROM hidden_groups WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM hidden_groups")
    suspend fun all(): List<HiddenGroupEntity>

    /** Blendet eine Gruppe in allen Playlists aus, die sie enthalten (Schnellaktion in den Listen). */
    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'LIVE', groupTitle FROM channels WHERE groupTitle = :name"""
    )
    suspend fun hideLiveEverywhere(name: String)

    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'VOD', category FROM vod WHERE category = :name"""
    )
    suspend fun hideVodEverywhere(name: String)

    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'SERIES', category FROM series WHERE category = :name"""
    )
    suspend fun hideSeriesEverywhere(name: String)

    @Query(
        """SELECT c.groupTitle AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'LIVE' AND h.name = c.groupTitle) AS hidden
           FROM channels c WHERE c.playlistId = :playlistId GROUP BY c.groupTitle ORDER BY MIN(c.id)"""
    )
    fun observeLive(playlistId: Long): Flow<List<GroupToggle>>

    @Query(
        """SELECT v.category AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'VOD' AND h.name = v.category) AS hidden
           FROM vod v WHERE v.playlistId = :playlistId GROUP BY v.category ORDER BY MIN(v.id)"""
    )
    fun observeVod(playlistId: Long): Flow<List<GroupToggle>>

    @Query(
        """SELECT s.category AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'SERIES' AND h.name = s.category) AS hidden
           FROM series s WHERE s.playlistId = :playlistId GROUP BY s.category ORDER BY MIN(s.id)"""
    )
    fun observeSeries(playlistId: Long): Flow<List<GroupToggle>>

    @Query("SELECT COUNT(*) FROM hidden_groups")
    fun observeHiddenCount(): Flow<Int>
}

@Dao
interface LockedGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(groups: List<LockedGroupEntity>)

    @Query("DELETE FROM locked_groups")
    suspend fun clear()
}

/** Mögliche Sportsendungen im Zeitraum auf sichtbaren Sendern (auch aus Favoriten und eigenen Gruppen) (grob; genau prüft ProgrammeText.isSportEvent). */
suspend fun ProgrammeDao.sportBetween(from: Long, to: Long, limit: Int): List<ProgrammeEntity> =
    rawProgrammes(SimpleSQLiteQuery(SPORT_BETWEEN_SQL, arrayOf<Any>(from, to, limit)))

private const val SPORT_BETWEEN_SQL = """SELECT * FROM programmes WHERE stop > ? AND start < ? AND (
           category LIKE '%ball%' OR category LIKE '%sport%' OR category LIKE '%tennis%' OR category LIKE '%darts%'
           OR category LIKE '%hockey%' OR category LIKE '%formel%' OR category LIKE '%box%' OR category LIKE '%golf%'
           OR title LIKE '%ball%' OR title LIKE '%liga%' OR title LIKE '%league%' OR title LIKE '%sport%' OR title LIKE '%live%'
           OR title LIKE '%pokal%' OR title LIKE '%tennis%' OR title LIKE '%darts%' OR title LIKE '%formel%' OR title LIKE '%NFL%'
           OR title LIKE '%NBA%' OR title LIKE '%NHL%' OR title LIKE '%UFC%' OR title LIKE '%box%' OR subtitle LIKE '% – %'
           OR subtitle LIKE '% - %' OR title LIKE '% – %' OR title LIKE '% - %' OR title LIKE '% vs%' OR subtitle LIKE '% vs%'
           OR title LIKE '% v %' OR subtitle LIKE '% v %' OR title LIKE '% gegen %' OR subtitle LIKE '% gegen %'
           OR title LIKE '%konferenz%' OR subtitle LIKE '%konferenz%' OR title LIKE '%multiview%' OR title LIKE '%ᴸᶦᵛᵉ%'
           OR channelEpgId IN (SELECT epgId FROM ($LISTED_CHANNELS_SQL) WHERE name LIKE '%sport%'
              OR name LIKE '%bundesliga%' OR name LIKE '%liga%' OR name LIKE '%football%' OR name LIKE '%fussball%' OR name LIKE '%fußball%'))
           AND channelEpgId IN (SELECT epgId FROM ($LISTED_CHANNELS_SQL) WHERE epgId IS NOT NULL AND epgId != '')
           ORDER BY start LIMIT ?"""

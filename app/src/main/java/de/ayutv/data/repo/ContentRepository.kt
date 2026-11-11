package de.ayutv.data.repo

import de.ayutv.data.parse.Episodes
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.FavoriteEntity
import de.ayutv.data.db.CustomGroupEntity
import de.ayutv.data.db.CustomGroupItemEntity
import de.ayutv.data.db.GroupOrderEntity
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.GroupToggle
import de.ayutv.data.db.HiddenGroupEntity
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.db.VodEntity
import de.ayutv.data.db.sportBetween
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.db.ProgrammeFlags
import de.ayutv.data.parse.Countries
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.parse.Teams
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.data.parse.XtreamMovieInfo
import de.ayutv.data.parse.headline
import de.ayutv.data.parse.pairing
import de.ayutv.data.parse.sportHeadline
import de.ayutv.data.parse.isSportEvent
import de.ayutv.data.reminders.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<Pair<ProgrammeEntity, ChannelEntity?>> = emptyList(),
    val movies: List<VodEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class Recommendation(val title: String, val channels: List<ChannelEntity>)

class ContentRepository(
    private val db: AyuDatabase,
    private val http: OkHttpClient,
) {
    val groups = db.channels().observeGroups()
    val favoriteChannels = db.channels().observeFavorites()
    val favoriteKeys = db.favorites().observeKeys()
    val channelCount = db.channels().observeCount()
    val vodCategories = db.vod().observeCategories()
    val seriesCategories = db.series().observeCategories()
    /** Angefangene Filme und je Serie die zuletzt geschaute Folge ([ContinueWatching]). */
    val continueWatching = db.history().observeRecentVod(400).map { ContinueWatching.select(it, 20) }
    val recentLive = db.history().observeRecentLive(20)
    val recentMovies = db.vod().observeRecent(30)
    val recentSeries = db.series().observeRecent(30)
    /** Merkliste auf der Startseite: gemerkte Filme und Serien. */
    val watchlistMovies = db.vod().observeFavorites()
    val watchlistSeries = db.series().observeFavorites()

    /** Eigene Gruppen mit Anzahl, in ihrer Reihenfolge. */
    val customGroups = db.customGroups().observeGroups()

    fun channelsIn(group: String?) = when {
        group == null -> db.channels().observeAll()
        customGroupId(group) != null -> db.channels().observeInCustom(customGroupId(group)!!)
        else -> db.channels().observeInGroup(group)
    }
    fun moviesIn(category: String) = db.vod().observeInCategory(category)
    fun seriesIn(category: String) = db.series().observeInCategory(category)

    suspend fun channel(key: String) = db.channels().byKey(key)
    suspend fun channelByNumber(number: Int) = db.channels().byNumber(number)

    /** Liste, durch die im Player mit hoch/runter gezappt wird; mit [merge] jeder Sender nur in seiner besten Fassung. */
    suspend fun zapList(group: String?, favoritesOnly: Boolean, merge: Boolean = false): List<ChannelEntity> {
        val custom = group?.let(::customGroupId)
        val list = when {
            favoritesOnly -> db.channels().favorites()
            custom != null -> db.channels().inCustom(custom)
            group != null -> db.channels().inGroup(group)
            else -> db.channels().all()
        }
        // Favoriten und eigene Gruppen bleiben, wie zusammengestellt.
        return if (merge && !favoritesOnly && custom == null) mergeVariants(list) else list
    }

    /** Jeder Sender einmal, in seiner besten Fassung (SD/HD/4K/RAW/Backup zusammengefasst). */
    suspend fun mergeVariants(channels: List<ChannelEntity>): List<ChannelEntity> = withContext(Dispatchers.Default) {
        // Erst die Mindestqualität (und ausgefallene, falls gewünscht), dann je Sender die beste Fassung.
        ChannelVariants.merge(channels.filter { ChannelQuality.passes(it.key, it.name) }, { it.name }, { it.playlistId }, { ChannelQuality.score(it.key, it.name) })
    }

    /**
     * Für Sport: die beste Fassung dieses Senders (4K vor Full HD vor HD), auch wenn sie im TV-Guide unter einer
     * anderen Kennung steht oder sonst Full HD bevorzugt wird.
     */
    suspend fun bestForSport(channel: ChannelEntity): ChannelEntity = withContext(Dispatchers.Default) {
        if (ChannelVariants.key(channel.name) == null) return@withContext channel
        val candidates = listOf(channel) + ChannelVariants.alternatives(
            channel, db.channels().visibleInPlaylist(channel.playlistId), { it.name }, { it.playlistId },
            same = { a, b -> a.key == b.key }, score = { ChannelQuality.sportScore(it.key, it.name) },
        )
        candidates.filter { ChannelQuality.passes(it.key, it.name) }.ifEmpty { candidates }
            .minBy { ChannelQuality.sportScore(it.key, it.name) }
    }

    /** Andere Fassungen desselben Senders, beste zuerst (für den Wechsel, wenn eine ausfällt). */
    suspend fun variantsOf(channel: ChannelEntity): List<ChannelEntity> = withContext(Dispatchers.Default) {
        if (ChannelVariants.key(channel.name) == null) return@withContext emptyList()
        ChannelVariants.alternatives(
            channel, db.channels().visibleInPlaylist(channel.playlistId), { it.name }, { it.playlistId },
            same = { a, b -> a.key == b.key }, score = { ChannelQuality.score(it.key, it.name) },
        )
    }

    /** Ein Spiel oder eine Live-Übertragung mit dem Sender, auf dem es läuft. */
    /** Ein Spiel auf dem besten Sender [channel]; [alsoOn]: dieselbe Übertragung auf anderen Sendern (je die beste Fassung). */
    data class SportEvent(val programme: ProgrammeEntity, val channel: ChannelEntity, val alsoOn: List<ChannelEntity> = emptyList()) {
        val otherChannels: Int get() = alsoOn.size

        /** Dasselbe Spiel auf weiteren Sendern: deren Sender kommen zu „auch auf“ (nicht doppelt, nicht dieser selbst). */
        fun mergedWith(other: SportEvent): SportEvent {
            // Hauptsendung mit Paarung im Titel bevorzugen (dann steht der Name überall gleich).
            val (main, extra) = if (de.ayutv.data.parse.ProgrammeText.pairing(programme.title, programme.subtitle, programme.description) == null &&
                de.ayutv.data.parse.ProgrammeText.pairing(other.programme.title, other.programme.subtitle, other.programme.description) != null) other to this else this to other
            val channels = (main.alsoOn + extra.channel + extra.alsoOn).distinctBy { it.key }.filter { it.key != main.channel.key }
            return main.copy(alsoOn = channels)
        }
    }

    /**
     * Sport von jetzt bis [hours] Stunden: nur Spiele und Live-Übertragungen (keine Magazine, Wiederholungen).
     * Läuft dasselbe Spiel auf mehreren Sendern, erscheint es einmal (bevorzugt auf einem Favoriten).
     */
    // Komplett abseits der Oberfläche: Der Sportbereich fragt jede Minute bis zu 65.000 Sendungen ab.
    suspend fun sportEvents(hours: Int = 36, now: Long = System.currentTimeMillis()): List<SportEvent> =
        withContext(Dispatchers.Default) { sportEventsNow(hours, now) }

    private suspend fun sportEventsNow(hours: Int, now: Long): List<SportEvent> {
        // Was jetzt läuft, vollständig (höchstens eine Sendung je Sender); danach die kommenden bis zur Grenze.
        // Vorher war eine gemeinsame Grenze von 4000 bei großen Senderlisten schnell erreicht.
        val airing = db.programmes().sportBetween(now, now + 1, 50_000)
        // Die kommenden in Abschnitten von 6 Stunden, je mit eigener Grenze: Bei großen Senderlisten war eine gemeinsame
        // Grenze (15.000) schon nach wenigen Stunden erreicht, spätere Spiele fehlten dann ganz.
        // Zusammen höchstens etwa 48.000 (Speicher auf dem Stick), je Abschnitt 2.000 bis 8.000.
        val chunks = (hours + 5) / 6
        val perChunk = (48_000 / chunks.coerceAtLeast(1)).coerceIn(2_000, 8_000)
        val upcoming = (0 until hours step 6).flatMap { h ->
            val from = now + h * HOUR
            db.programmes().sportBetween(from, minOf(now + hours * HOUR, from + 6 * HOUR), perChunk).filter { it.start >= from || h == 0 }
        }
        val programmes = (airing + upcoming).distinctBy { it.id }
        if (programmes.isEmpty()) return emptyList()
        // Alle Fassungen je EPG-Kennung (HD, 4K, SD teilen sich oft eine): daraus die beste wählen.
        val channels = programmes.map { it.channelEpgId }.distinct().chunked(SQL_CHUNK)
            .flatMap { db.channels().allByEpgIds(it) }.groupBy { it.epgId }
        val favorites = db.favorites().keys().toHashSet()
        return withContext(Dispatchers.Default) {
            programmes.flatMap { p -> channels[p.channelEpgId].orEmpty().map { p to it } }
                .filter { (p, c) -> p.isSportEvent(ProgrammeText.isSportChannel(c.name)) }
                .groupBy { (p, c) ->
                    (p.start / (5 * 60_000L)) to
                        ProgrammeText.sportGroupKey(p.title, p.subtitle, p.description, p.channelEpgId, ChannelVariants.key(c.name) ?: c.key)
                }
                .values.map { same ->
                    // Weitere Sender, nicht weitere Fassungen desselben Senders: je Sender die beste Fassung.
                    fun variant(channel: ChannelEntity) = ChannelVariants.key(channel.name) ?: channel.key
                    // Favorit entscheidet zwischen verschiedenen Sendern, die Qualität zwischen den Fassungen eines
                    // Senders: Ist „Sendername“ Favorit, läuft trotzdem „Sendername“, wenn es die gibt.
                    val favoriteVariants = same.filter { it.second.key in favorites }.mapTo(HashSet()) { variant(it.second) }
                    val best = compareBy<Pair<ProgrammeEntity, ChannelEntity>>(
                        { if (variant(it.second) in favoriteVariants) 0 else 1 },
                        { ChannelQuality.sportScore(it.second.key, it.second.name) },
                    )
                    val (p, c) = same.minWith(best)
                    val others = same.filter { variant(it.second) != variant(c) }
                        .groupBy { variant(it.second) }.values
                        .map { it.minWith(best).second }
                        .sortedWith(compareBy({ if (it.key in favorites) 0 else 1 }, { it.name }))
                    SportEvent(p, c, others)
                }
                .sortedWith(compareBy({ it.programme.start }, { it.channel.name }))
                .let { list ->
                    // Dasselbe Spiel aus verschiedenen TV-Guides (leicht andere Anfangszeit) nur einmal, Wiederholungen nicht.
                    ProgrammeText.mergeSameMatch(list, { it.programme.start }, { ProgrammeText.pairing(it.programme.title, it.programme.subtitle, it.programme.description) }) { a, b -> a.mergedWith(b) }
                }
        }
    }

    /** Spiele der Vereine in [teams] in den nächsten [days] Tagen, als Erinnerungen. */
    suspend fun teamReminders(teams: Set<String>, days: Int = 7): List<Reminder> {
        if (teams.isEmpty()) return emptyList()
        return sportEvents(hours = days * 24).filter { e ->
            val p = e.programme
            val pairing = p.pairing() ?: return@filter false
            p.start > System.currentTimeMillis() && teams.any { Teams.plays(it, pairing, p.title + " " + p.subtitle.orEmpty()) }
        }.map { Reminder(it.channel.key, it.channel.name, it.programme.sportHeadline(), it.programme.start) }
    }

    /**
     * Stichwort-Alarm: kommende Sendungen mit einem der [words] im Titel oder Untertitel (keine Wiederholungen),
     * je auf dem besten Sender, höchstens [perWord] je Begriff.
     */
    suspend fun keywordReminders(words: Set<String>, days: Int = 7, perWord: Int = 20): List<Reminder> {
        val now = System.currentTimeMillis()
        val until = now + days * 24 * HOUR
        val out = ArrayList<Reminder>()
        for (word in words.map { it.trim() }.filter { it.length >= 3 }) {
            val found = db.programmes().search(word, now, 400)
                .filter { it.start > now && it.start < until && (it.flags and ProgrammeFlags.REPEAT) == 0 }
                .distinctBy { it.start to it.title.lowercase() }
                .sortedBy { it.start }
                .take(perWord)
            if (found.isEmpty()) continue
            val channels = found.map { it.channelEpgId }.distinct().chunked(SQL_CHUNK)
                .flatMap { db.channels().allByEpgIds(it) }.groupBy { it.epgId }
            for (p in found) {
                val channel = channels[p.channelEpgId]?.minByOrNull { ChannelQuality.score(it.key, it.name) } ?: continue
                out += Reminder(channel.key, channel.name, p.headline(), p.start)
            }
        }
        return out
    }

    suspend fun toggleFavorite(key: String): Boolean {
        val favorite = db.favorites().isFavorite(key)
        if (favorite) db.favorites().remove(key) else db.favorites().add(FavoriteEntity(key, System.currentTimeMillis(), db.favorites().maxPosition() + 1))
        return !favorite
    }

    // ---------------------------------------------------------------- Eigene Gruppen und Reihenfolge

    /** Name für Anzeigen (Player-Liste): eigene Gruppe → ihr Name, sonst der Gruppenname selbst. */
    suspend fun groupLabel(group: String): String =
        customGroupId(group)?.let { id -> db.customGroups().group(id)?.name?.let { "📁 $it" } } ?: group

    /** Neue eigene Gruppe (am Ende), auf Wunsch gleich mit den Sendern [firstKeys]. Liefert die Kennung für die Liste. */
    suspend fun createGroup(name: String, firstKeys: List<String> = emptyList()): String {
        val id = db.customGroups().insert(CustomGroupEntity(name = name.trim(), position = db.customGroups().maxGroupPosition() + 1))
        firstKeys.forEachIndexed { index, key -> db.customGroups().addItem(CustomGroupItemEntity(id, key, index + 1)) }
        return customGroupKey(id)
    }

    /** Mehrere Sender in eine eigene Gruppe aufnehmen (ans Ende, schon enthaltene bleiben, wo sie sind). Liefert die Anzahl neuer. */
    suspend fun addToGroup(group: String, keys: List<String>): Int {
        val id = customGroupId(group) ?: return 0
        var position = db.customGroups().maxItemPosition(id)
        var added = 0
        for (key in keys) {
            if (id in db.customGroups().groupsOf(key)) continue
            db.customGroups().addItem(CustomGroupItemEntity(id, key, ++position))
            added++
        }
        return added
    }

    suspend fun removeFromGroup(group: String, keys: List<String>) {
        val id = customGroupId(group) ?: return
        keys.forEach { db.customGroups().removeItem(id, it) }
    }

    /** Mehrere Sender zu den Favoriten (ans Ende). Liefert die Anzahl neuer. */
    suspend fun addFavorites(keys: List<String>): Int {
        var position = db.favorites().maxPosition()
        var added = 0
        val now = System.currentTimeMillis()
        for (key in keys) {
            if (db.favorites().isFavorite(key)) continue
            db.favorites().add(FavoriteEntity(key, now, ++position))
            added++
        }
        return added
    }

    suspend fun removeFavorites(keys: List<String>) = keys.forEach { db.favorites().remove(it) }

    suspend fun renameGroup(group: String, name: String) {
        customGroupId(group)?.let { db.customGroups().rename(it, name.trim()) }
    }

    suspend fun deleteGroup(group: String) {
        val id = customGroupId(group) ?: return
        db.customGroups().deleteItems(id)
        db.customGroups().deleteGroup(id)
    }

    /** Eigene Gruppe eine Stelle nach oben ([delta] = -1) oder unten (+1) in der Gruppenspalte. */
    suspend fun moveGroup(group: String, delta: Int) {
        val id = customGroupId(group) ?: return
        val ordered = db.customGroups().groups().map { it.id }.toMutableList()
        val from = ordered.indexOf(id)
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (from < 0 || from == to) return
        ordered.add(to, ordered.removeAt(from))
        ordered.forEachIndexed { index, groupId -> db.customGroups().setGroupPosition(groupId, index + 1) }
    }

    /** Eigene Gruppen, in denen der Sender steht (Kennungen wie in der Gruppenspalte). */
    suspend fun groupsOf(key: String): Set<String> = db.customGroups().groupsOf(key).map(::customGroupKey).toSet()

    /** Sender in eine eigene Gruppe aufnehmen (ans Ende) oder herausnehmen. true = jetzt drin. */
    suspend fun toggleInGroup(group: String, key: String): Boolean {
        val id = customGroupId(group) ?: return false
        if (id in db.customGroups().groupsOf(key)) {
            db.customGroups().removeItem(id, key)
            return false
        }
        db.customGroups().addItem(CustomGroupItemEntity(id, key, db.customGroups().maxItemPosition(id) + 1))
        return true
    }

    /**
     * Von Hand sortierte Reihenfolge [keys] speichern: Favoriten ([favorites]), eigene Gruppe oder Gruppe des Anbieters
     * ([group], gilt über neues Laden der Playlist hinweg).
     */
    suspend fun saveOrder(group: String?, favorites: Boolean, keys: List<String>) {
        val custom = group?.let(::customGroupId)
        when {
            favorites -> keys.forEachIndexed { index, key -> db.favorites().setPosition(key, index + 1) }
            custom != null -> keys.forEachIndexed { index, key -> db.customGroups().setItemPosition(custom, key, index + 1) }
            group != null -> {
                db.groupOrder().clear(group)
                db.groupOrder().upsert(keys.mapIndexed { index, key -> GroupOrderEntity(group, key, index + 1) })
            }
        }
    }

    /** Aktuelle Sendung je EPG-ID. */
    // Abseits der Oberfläche: Live TV fragt das jede Minute für tausende Sender ab.
    suspend fun nowPlaying(channels: List<ChannelEntity>, at: Long = System.currentTimeMillis()): Map<String, ProgrammeEntity> =
        withContext(Dispatchers.Default) { nowPlayingNow(channels, at) }

    private suspend fun nowPlayingNow(channels: List<ChannelEntity>, at: Long): Map<String, ProgrammeEntity> {
        val ids = channels.mapNotNull { it.epgId?.takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<String, ProgrammeEntity>()
        if (ids.size > SQL_CHUNK) {
            // Große Listen: einmal alles Laufende holen (Index auf stop) und im Speicher zuordnen.
            val wanted = ids.toHashSet()
            db.programmes().airingAt(at).forEach { if (it.channelEpgId in wanted) result.putIfAbsent(it.channelEpgId, it) }
            return result
        }
        ids.chunked(SQL_CHUNK).forEach { chunk ->
            db.programmes().inRange(chunk, at, at + 1).forEach { result.putIfAbsent(it.channelEpgId, it) }
        }
        return result
    }

    suspend fun programmesInRange(channels: List<ChannelEntity>, from: Long, to: Long): Map<String, List<ProgrammeEntity>> {
        val ids = channels.mapNotNull { it.epgId?.takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val rows = ids.chunked(SQL_CHUNK).flatMap { db.programmes().inRange(it, from, to) }
        // Mehrere Playlists mit denselben Sendern: jede Sendung nur einmal. Abseits der Oberfläche (tausende Sendungen).
        return withContext(Dispatchers.Default) {
            rows.groupBy { it.channelEpgId }.mapValues { (_, list) -> list.distinctBy { it.start }.sortedBy { it.start } }
        }
    }

    /** Archiv-Adresse einer vergangenen Sendung oder null (Sender ohne Archiv, zu alt). */
    suspend fun catchupUrl(channel: ChannelEntity, programme: ProgrammeEntity): String? =
        Catchup.url(db.playlists().byId(channel.playlistId), channel, programme.start, programme.stop)

    suspend fun upcoming(channel: ChannelEntity, limit: Int = 8): List<ProgrammeEntity> {
        val id = channel.epgId?.takeIf(String::isNotEmpty) ?: return emptyList()
        // Nutzen mehrere Playlists dieselben EPG-IDs, käme jede Sendung mehrfach: die eigene Playlist
        // bevorzugen und doppelte Startzeiten entfernen.
        return db.programmes().upcoming(id, System.currentTimeMillis(), limit * 3)
            .sortedWith(compareBy({ it.start }, { if (it.playlistId == channel.playlistId) 0 else 1 }))
            .distinctBy { it.start }
            .take(limit)
    }

    suspend fun search(query: String): SearchResults = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope SearchResults()
        val now = System.currentTimeMillis()
        // Alle Treffer zeigen (die Reihen laden lazy). Die Grenze schützt nur vor Speicherproblemen
        // bei Ein-Buchstaben-artigen Suchen in Playlists mit Hunderttausenden Einträgen.
        val channels = async { db.channels().search(q, SEARCH_MAX) }
        val programmes = async { db.programmes().search(q, now, SEARCH_MAX) }
        val movies = async { db.vod().search(q, SEARCH_MAX) }
        val series = async { db.series().search(q, SEARCH_MAX) }

        val programmeList = programmes.await().distinctBy { Triple(it.channelEpgId, it.start, it.title) }
        // Zu jeder gefundenen Sendung den passenden Sender (für "Jetzt einschalten").
        val epgIds = programmeList.map { it.channelEpgId }.distinct()
        // In Stücken: Ältere Fire-OS-SQLite erlaubt höchstens 999 Platzhalter pro Abfrage.
        val channelsByEpg = epgIds.chunked(SQL_CHUNK).flatMap { db.channels().byEpgIds(it) }.associateBy { it.epgId }
        SearchResults(
            channels = channels.await(),
            programmes = programmeList.map { it to channelsByEpg[it.channelEpgId] },
            movies = movies.await(),
            series = series.await(),
        )
    }

    /**
     * Empfehlungen ohne Cloud: Gruppen, die der Nutzer am häufigsten schaut, liefern weitere
     * Sender, die er noch nicht kennt. Dazu kommt, was dort gerade läuft.
     */
    suspend fun recommendations(): List<Recommendation> {
        val groups = db.history().topLiveGroups(3)
        if (groups.isEmpty()) return emptyList()
        val seen = db.history().recentLiveKeys(200)
        return groups.mapNotNull { group ->
            val channels = db.channels().inGroupsExcluding(listOf(group), seen, 20)
            channels.takeIf { it.isNotEmpty() }?.let { Recommendation("Mehr aus $group", it) }
        }
    }

    suspend fun recordLive(channel: ChannelEntity) {
        val existing = db.history().byKey(channel.key)
        db.history().upsert(
            HistoryEntity(
                key = channel.key,
                kind = HistoryKind.LIVE,
                title = channel.name,
                subtitle = channel.groupTitle,
                image = channel.logo,
                url = channel.url,
                groupTitle = channel.groupTitle,
                playCount = (existing?.playCount ?: 0) + 1,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun recordProgress(entry: HistoryEntity) {
        val existing = db.history().byKey(entry.key)
        db.history().upsert(entry.copy(playCount = existing?.playCount ?: 1))
    }

    /**
     * Wo ein Film weiterläuft: ein paar Sekunden vor der letzten Stelle (Wiedereinstieg). Zu Ende
     * oder fast zu Ende geschaut beginnt er von vorn, statt sofort wieder zu enden.
     */
    suspend fun resumePosition(key: String): Long {
        val entry = db.history().byKey(key) ?: return 0L
        val nearEnd = entry.durationMs > 0 &&
            (entry.positionMs >= entry.durationMs * 0.95 || entry.durationMs - entry.positionMs < 120_000)
        return if (nearEnd) 0L else (entry.positionMs - 3_000).coerceAtLeast(0)
    }

    suspend fun clearHistory() = db.history().clear()

    fun historyOf(key: String) = db.history().observeByKey(key)

    /** Verlauf aller Folgen einer Serie, nach Folgen-Kennung. */
    fun episodeHistory(seriesKey: String) = db.history().observeWithPrefix(Episodes.key(seriesKey, ""))

    /** Von Hand als gesehen / nicht gesehen markieren (lange OK auf einer Folge). */
    suspend fun setWatched(entry: HistoryEntity, watched: Boolean) {
        val duration = entry.durationMs.takeIf { it > 0 } ?: 1L
        db.history().upsert(entry.copy(positionMs = if (watched) duration else 0L, durationMs = duration, watchedAt = System.currentTimeMillis()))
    }

    /** Angaben zum Film beim Anbieter (Handlung, Besetzung, Trailer); null, wenn das Panel keine liefert. */
    suspend fun movieInfo(movie: VodEntity): XtreamMovieInfo? = withContext(Dispatchers.IO) {
        val playlist = db.playlists().byId(movie.playlistId) ?: return@withContext null
        if (playlist.type != PlaylistType.XTREAM) return@withContext null
        val id = XtreamClient.movieIdOf(movie.url) ?: return@withContext null
        runCatching { XtreamClient.of(http, playlist).movieInfo(id) }.getOrNull()
    }

    // ---------------------------------------------------------------- Gruppen ein-/ausblenden

    fun groupToggles(playlistId: Long, kind: GroupKind): Flow<List<GroupToggle>> = when (kind) {
        GroupKind.LIVE -> db.hiddenGroups().observeLive(playlistId)
        GroupKind.VOD -> db.hiddenGroups().observeVod(playlistId)
        GroupKind.SERIES -> db.hiddenGroups().observeSeries(playlistId)
    }

    val hiddenGroupCount = db.hiddenGroups().observeHiddenCount()

    suspend fun setGroupsHidden(playlistId: Long, kind: GroupKind, names: List<String>, hidden: Boolean) {
        names.chunked(SQL_CHUNK).forEach { chunk ->
            if (hidden) {
                db.hiddenGroups().hide(chunk.map { HiddenGroupEntity(playlistId, kind, it) })
            } else {
                db.hiddenGroups().show(playlistId, kind, chunk)
            }
        }
    }

    /** Ein Land mit seinen Gruppen (Live, Filme, Serien) über alle Playlists. [visible]: mindestens eine Gruppe sichtbar. */
    data class CountryChoice(val code: String, val label: String, val groups: Int, val items: Int, val visible: Boolean)

    private suspend fun allGroups(): List<Triple<Long, GroupKind, GroupToggle>> = db.playlists().all().filter { it.enabled }.flatMap { playlist ->
        GroupKind.entries.flatMap { kind -> groupToggles(playlist.id, kind).first().map { Triple(playlist.id, kind, it) } }
    }

    /** Länder nach Gruppennamen („DE | Sport“, „UK: News“); Gruppen ohne Länderkennung als eigener Eintrag (code ""). */
    suspend fun countryChoices(): List<CountryChoice> = withContext(Dispatchers.Default) {
        allGroups().groupBy { (_, _, g) -> Countries.of(g.name) }.map { (country, groups) ->
            CountryChoice(
                code = country?.code.orEmpty(),
                label = country?.label ?: "Ohne Länderkennung",
                groups = groups.size,
                items = groups.sumOf { it.third.count },
                visible = groups.any { !it.third.hidden },
            )
        }.sortedWith(compareBy({ it.code.isEmpty() }, { it.code !in PREFERRED }, { -it.items }))
    }

    /** Alle Gruppen eines Landes ([code], "" = ohne Länderkennung) ein- oder ausblenden. */
    suspend fun setCountryVisible(code: String, visible: Boolean) {
        allGroups().filter { (_, _, g) -> Countries.of(g.name)?.code.orEmpty() == code }
            .groupBy({ it.first to it.second }, { it.third.name })
            .forEach { (key, names) -> setGroupsHidden(key.first, key.second, names, hidden = !visible) }
    }

    /** Schnellaktion aus den Listen: Gruppe in allen Playlists ausblenden, die sie enthalten. */
    suspend fun hideGroupEverywhere(kind: GroupKind, name: String) = when (kind) {
        GroupKind.LIVE -> db.hiddenGroups().hideLiveEverywhere(name)
        GroupKind.VOD -> db.hiddenGroups().hideVodEverywhere(name)
        GroupKind.SERIES -> db.hiddenGroups().hideSeriesEverywhere(name)
    }

    suspend fun episodes(series: SeriesEntity): List<Pair<XtreamEpisode, String>> = withContext(Dispatchers.IO) {
        val playlist = db.playlists().byId(series.playlistId) ?: return@withContext emptyList()
        if (playlist.type != PlaylistType.XTREAM) return@withContext emptyList()
        val client = XtreamClient.of(http, playlist)
        client.episodes(series.seriesId).map { it to client.episodeUrl(it.id, it.extension) }
    }

    data class NextEpisode(val series: SeriesEntity, val episode: XtreamEpisode, val url: String)

    /** Die nächsten [count] Folgen nach [episodeKey] (eine Abfrage beim Anbieter für alle). */
    suspend fun nextEpisodes(episodeKey: String, count: Int): List<NextEpisode> {
        val seriesKey = Episodes.seriesKeyOf(episodeKey) ?: return emptyList()
        var currentId = Episodes.episodeIdOf(episodeKey) ?: return emptyList()
        val series = db.series().byKey(seriesKey) ?: return emptyList()
        val list = runCatching { episodes(series) }.getOrNull() ?: return emptyList()
        val plain = list.map { it.first }
        val out = ArrayList<NextEpisode>()
        repeat(count) {
            val next = Episodes.next(plain, currentId) ?: return out
            val url = list.firstOrNull { it.first.id == next.id }?.second ?: return out
            out += NextEpisode(series, next, url)
            currentId = next.id
        }
        return out
    }

    /** Die Folge nach [episodeKey] („<Serie>:e:<Folge>“), über das Staffelende hinweg; null bei der letzten. */
    suspend fun nextEpisode(episodeKey: String): NextEpisode? {
        val seriesKey = Episodes.seriesKeyOf(episodeKey) ?: return null
        val currentId = Episodes.episodeIdOf(episodeKey) ?: return null
        val series = db.series().byKey(seriesKey) ?: return null
        val list = runCatching { episodes(series) }.getOrNull() ?: return null
        val next = Episodes.next(list.map { it.first }, currentId) ?: return null
        val url = list.firstOrNull { it.first.id == next.id }?.second ?: return null
        return NextEpisode(series, next, url)
    }

    private companion object {
        val PREFERRED = setOf("DE", "AT", "CH")
        const val HOUR = 3_600_000L
        const val SQL_CHUNK = 500
        const val SEARCH_MAX = 20_000
    }
}

/** Kennung einer eigenen Gruppe in Gruppenlisten (Player, TV-Guide): kann nicht mit einem Gruppennamen kollidieren. */
const val CUSTOM_GROUP_PREFIX = "\u0000eigene:"

fun customGroupKey(id: Long) = "$CUSTOM_GROUP_PREFIX$id"

fun customGroupId(group: String): Long? = group.takeIf { it.startsWith(CUSTOM_GROUP_PREFIX) }?.removePrefix(CUSTOM_GROUP_PREFIX)?.toLongOrNull()

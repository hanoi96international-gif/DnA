package de.ayutv.desktop.data

import de.ayutv.data.parse.ChannelVariants
import de.ayutv.data.parse.Countries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Alles, was die Windows-App weiß, im Speicher (ein PC hat dafür genug Platz) und als Dateien im Datenordner:
 * Playlists, ihre Sender/Filme/Serien, der TV-Guide und was der Nutzer festlegt. Kein Datenbank-Server, nichts zu
 * installieren; die Dateien überstehen Updates.
 */
class Store(val dir: File) {
    @Serializable
    data class Content(
        val channels: List<Channel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList(),
    )

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _content = MutableStateFlow<Map<Long, Content>>(emptyMap())
    val content: StateFlow<Map<Long, Content>> = _content.asStateFlow()

    /** TV-Guide: Senderschlüssel → Sendungen, nach Beginn sortiert. */
    private val _guide = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val guide: StateFlow<Map<String, List<Programme>>> = _guide.asStateFlow()
    /** TV-Guide wie geladen (ohne Verschiebung); vor [init] angelegt, weil [load] ihn füllt. */
    @Volatile private var rawGuide: Map<String, List<Programme>> = emptyMap()

    private val _user = MutableStateFlow(UserData())
    val user: StateFlow<UserData> = _user.asStateFlow()

    /** Profile (das erste heißt „Ich“ und nutzt die alte user.json weiter). */
    private val _profiles = MutableStateFlow(listOf(Profile(0, "Ich")))
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _profile = MutableStateFlow(0L)
    val profile: StateFlow<Long> = _profile.asStateFlow()

    init {
        dir.mkdirs()
        load()
    }

    // ---------------------------------------------------------------- Lesen

    private fun enabledIds(): Set<Long> = _playlists.value.filter { it.enabled }.map { it.id }.toSet()

    /** Sender aller eingeschalteten Playlists (auch aus ausgeblendeten Gruppen und alle Fassungen). */
    fun channels(): List<Channel> {
        val enabled = enabledIds()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.channels }
    }

    /** Alle Filme der eingeschalteten Playlists (auch ausgeblendete Kategorien). */
    fun allMovies(): List<Movie> {
        val enabled = enabledIds()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.movies }
    }

    fun allSeries(): List<Series> {
        val enabled = enabledIds()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.series }
    }

    /** Filme ohne ausgeblendete und gesperrte Kategorien. */
    fun movies(): List<Movie> {
        val hidden = _user.value.hiddenMovieGroups
        return allMovies().filter { "${it.playlistId}|${it.category}" !in hidden && !locked(GroupKind.MOVIES, it.playlistId, it.category) }
    }

    /** Serien ohne ausgeblendete und gesperrte Kategorien. */
    fun series(): List<Series> {
        val hidden = _user.value.hiddenSeriesGroups
        return allSeries().filter { "${it.playlistId}|${it.category}" !in hidden && !locked(GroupKind.SERIES, it.playlistId, it.category) }
    }

    // ---------------------------------------------------------------- Kindersicherung

    private fun lockKey(kind: GroupKind, playlistId: Long, name: String) = "${kind.lockName}|$playlistId|$name"

    /** Gruppe gesperrt (und nicht mit der PIN entsperrt)? */
    fun locked(kind: GroupKind, playlistId: Long, name: String): Boolean {
        val u = _user.value
        return !u.parentalUnlocked && u.lockedGroups.isNotEmpty() && lockKey(kind, playlistId, name) in u.lockedGroups
    }

    private fun lockedChannel(c: Channel) = locked(GroupKind.LIVE, c.playlistId, c.group)

    fun isGroupLocked(kind: GroupKind, playlistId: Long, name: String) = lockKey(kind, playlistId, name) in _user.value.lockedGroups

    /** Gruppe [name] in allen Playlists sperren bzw. freigeben (braucht eine PIN). */
    fun setLocked(kind: GroupKind, name: String, lock: Boolean) = updateUser { u ->
        val ids = _content.value.filterValues { c ->
            when (kind) {
                GroupKind.LIVE -> c.channels.any { it.group == name }
                GroupKind.MOVIES -> c.movies.any { it.category == name }
                GroupKind.SERIES -> c.series.any { it.category == name }
            }
        }.keys
        val keys = ids.map { lockKey(kind, it, name) }.toSet()
        u.copy(lockedGroups = if (lock) u.lockedGroups + keys else u.lockedGroups - keys)
    }

    /** Mit der PIN entsperren (bis zum Beenden) bzw. wieder sperren. */
    fun unlock(pin: String): Boolean {
        if (pin != _user.value.parentalPin || pin.isEmpty()) return false
        updateUser { it.copy(parentalUnlocked = true) }
        return true
    }

    fun relock() = updateUser { it.copy(parentalUnlocked = false) }

    fun channel(key: String): Channel? = _content.value.values.firstNotNullOfOrNull { c -> c.channels.firstOrNull { it.key == key } }

    private fun visible(channel: Channel, hidden: Set<String>) = "${channel.playlistId}|${channel.group}" !in hidden && !lockedChannel(channel)

    /** Doppelte Fassungen zusammenfassen (wenn eingeschaltet): jeder Sender einmal, in der besten Fassung. */
    private fun merged(list: List<Channel>): List<Channel> =
        if (_user.value.mergeVariants) ChannelVariants.merge(list, { it.name }, { it.playlistId }, { de.ayutv.data.parse.ChannelQuality.score(it.key, it.name) }) else list

    /** Gruppen des Anbieters (Live), die nicht ausgeblendet sind, mit Anzahl, in der Reihenfolge der Playlist. */
    fun liveGroups(): List<Pair<String, Int>> {
        val hidden = _user.value.hiddenGroups
        return merged(channels().filter { visible(it, hidden) })
            .groupingBy { it.group }.eachCount().toList()
    }

    /** Sender einer Gruppe des Anbieters, in eigener Reihenfolge (falls sortiert), sonst wie beim Anbieter. */
    fun inGroup(group: String): List<Channel> {
        val hidden = _user.value.hiddenGroups
        val list = merged(channels().filter { it.group == group && visible(it, hidden) })
        return Ordering.apply(list, _user.value.groupOrder[group].orEmpty()) { it.key }
    }

    /** Alle sichtbaren Sender (ohne ausgeblendete Gruppen, doppelte Fassungen zusammengefasst). */
    fun allVisible(): List<Channel> {
        val hidden = _user.value.hiddenGroups
        return merged(channels().filter { visible(it, hidden) })
    }

    /**
     * Sender, die irgendwo erscheinen: sichtbare Gruppen, dazu Favoriten und eigene Gruppen – auch wenn deren Sender
     * aus einer ausgeblendeten Gruppe stammen. Für Sport, Suche und Handy-Fernbedienung (wie auf dem Fire TV).
     */
    fun listedChannels(): List<Channel> {
        val visible = allVisible()
        val seen = visible.mapTo(HashSet()) { it.key }
        val u = _user.value
        val extra = byKeys(u.favorites + u.customGroups.flatMap { it.keys }).filter { seen.add(it.key) }
        return visible + extra
    }

    /**
     * Andere Fassungen desselben Senders (beste zuerst), auf die der Player ausweicht, wenn [channel] nicht läuft.
     * Auch Fassungen aus ausgeblendeten Gruppen zählen (sie sind nur in den Listen verborgen).
     */
    fun alternatives(channel: Channel): List<Channel> =
        ChannelVariants.alternatives(
            channel, _content.value[channel.playlistId]?.channels.orEmpty(), { it.name }, { it.playlistId }, { a, b -> a.key == b.key },
            score = { de.ayutv.data.parse.ChannelQuality.score(it.key, it.name) },
        )

    /** Archiv-Adresse (Catch-up) für eine vergangene oder laufende Sendung; null = kein Archiv. */
    fun archiveUrl(channel: Channel, start: Long, stop: Long, now: Long = System.currentTimeMillis()): String? {
        val playlist = _playlists.value.firstOrNull { it.id == channel.playlistId }
        return de.ayutv.data.parse.CatchupUrls.url(
            channel.url, channel.catchupDays, channel.catchupType, channel.catchupSource,
            xtreamPlaylist = playlist?.type == PlaylistType.XTREAM, timezone = playlist?.timezone,
            start = start, stop = stop, now = now,
        )
    }

    /** Gruppen je Art (für „Gruppen ein- und ausblenden“): Name, Anzahl, ausgeblendet. */
    fun groupsOf(playlistId: Long, kind: GroupKind): List<Triple<String, Int, Boolean>> {
        val c = _content.value[playlistId] ?: return emptyList()
        val (names, hidden) = when (kind) {
            GroupKind.LIVE -> c.channels.map { it.group } to _user.value.hiddenGroups
            GroupKind.MOVIES -> c.movies.map { it.category } to _user.value.hiddenMovieGroups
            GroupKind.SERIES -> c.series.map { it.category } to _user.value.hiddenSeriesGroups
        }
        return names.groupingBy { it }.eachCount().map { (name, count) -> Triple(name, count, "$playlistId|$name" in hidden) }
    }

    /** Gruppen ein- ([show]) oder ausblenden. */
    fun setGroupsHidden(playlistId: Long, kind: GroupKind, names: Collection<String>, hide: Boolean) = updateUser { u ->
        val keys = names.map { "$playlistId|$it" }.toSet()
        fun Set<String>.apply() = if (hide) this + keys else this - keys
        when (kind) {
            GroupKind.LIVE -> u.copy(hiddenGroups = u.hiddenGroups.apply())
            GroupKind.MOVIES -> u.copy(hiddenMovieGroups = u.hiddenMovieGroups.apply())
            GroupKind.SERIES -> u.copy(hiddenSeriesGroups = u.hiddenSeriesGroups.apply())
        }
    }

    /**
     * Länder aus den Gruppennamen aller Playlists („DE | Sport“, „AT: News“, „|UK| …“), mit Anzahl der Gruppen und ob
     * gerade sichtbar (mindestens eine Gruppe des Landes nicht ausgeblendet). Gruppen ohne erkennbares Land bleiben
     * von der Länderauswahl unberührt.
     */
    fun countries(): List<CountryChoice> {
        val u = _user.value
        val byCountry = LinkedHashMap<String, MutableList<String>>()
        val labels = HashMap<String, String>()
        val enabled = enabledIds()
        for ((id, c) in _content.value) {
            if (id !in enabled) continue
            fun add(name: String, hidden: Set<String>) {
                val country = Countries.of(name) ?: return
                labels[country.code] = country.label
                byCountry.getOrPut(country.code) { ArrayList() } += if ("$id|$name" in hidden) "-" else "+"
            }
            c.channels.map { it.group }.distinct().forEach { add(it, u.hiddenGroups) }
            c.movies.map { it.category }.distinct().forEach { add(it, u.hiddenMovieGroups) }
            c.series.map { it.category }.distinct().forEach { add(it, u.hiddenSeriesGroups) }
        }
        return byCountry.map { (code, marks) -> CountryChoice(code, labels[code] ?: code, marks.size, marks.any { it == "+" }) }
            .sortedByDescending { it.groups }
    }

    /** Nur die Gruppen der Länder [keep] zeigen (Live, Filme, Serien); Gruppen ohne Land bleiben, wie sie sind. */
    fun keepCountries(keep: Set<String>) = updateUser { u ->
        var live = u.hiddenGroups
        var movies = u.hiddenMovieGroups
        var series = u.hiddenSeriesGroups
        for ((id, c) in _content.value) {
            fun Set<String>.apply(names: List<String>): Set<String> {
                var set = this
                for (name in names.distinct()) {
                    val code = Countries.of(name)?.code ?: continue
                    set = if (code in keep) set - "$id|$name" else set + "$id|$name"
                }
                return set
            }
            live = live.apply(c.channels.map { it.group })
            movies = movies.apply(c.movies.map { it.category })
            series = series.apply(c.series.map { it.category })
        }
        u.copy(hiddenGroups = live, hiddenMovieGroups = movies, hiddenSeriesGroups = series, countriesAsked = true)
    }

    /** Favoriten in ihrer Reihenfolge; auch Sender aus ausgeblendeten Gruppen. */
    fun favorites(): List<Channel> = byKeys(_user.value.favorites)

    fun inCustom(id: Long): List<Channel> = byKeys(_user.value.customGroups.firstOrNull { it.id == id }?.keys.orEmpty())

    /** Zuletzt geschaute Sender, neuester zuerst. */
    fun recentChannels(): List<Channel> = byKeys(_user.value.recent)

    /** Sender nach Schlüssel (Favoriten, eigene Gruppen, Verlauf), ohne gesperrte Gruppen. */
    private fun byKeys(keys: List<String>): List<Channel> {
        val all = channels().associateBy { it.key }
        return keys.mapNotNull { all[it] }.filterNot(::lockedChannel)
    }

    fun programmes(channel: Channel): List<Programme> = _guide.value[channel.key].orEmpty()

    fun nowPlaying(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmes(channel).firstOrNull { it.start <= at && it.stop > at }

    fun upcoming(channel: Channel, count: Int, at: Long = System.currentTimeMillis()): List<Programme> =
        programmes(channel).filter { it.stop > at }.take(count)

    // ---------------------------------------------------------------- Ändern (Nutzer)

    fun updateUser(transform: (UserData) -> UserData) {
        _user.update(transform)
        save(userFile(_profile.value), UserData.serializer(), _user.value)
    }

    /** Eigene Sendernummer setzen ([number] null: entfernen). Eine schon vergebene Nummer wird dem anderen Sender genommen. */
    fun setChannelNumber(key: String, number: Int?) {
        updateUser { u ->
            val rest = u.channelNumbers.filter { (k, n) -> k != key && n != number }
            u.copy(channelNumbers = if (number == null || number <= 0) rest else rest + (key to number))
        }
    }

    fun toggleFavorite(key: String): Boolean {
        var now = false
        updateUser { u ->
            now = key !in u.favorites
            u.copy(favorites = if (now) u.favorites + key else u.favorites - key)
        }
        return now
    }

    fun addFavorites(keys: List<String>): Int {
        var added = 0
        updateUser { u ->
            val fresh = keys.filter { it !in u.favorites }.distinct()
            added = fresh.size
            u.copy(favorites = u.favorites + fresh)
        }
        return added
    }

    fun createGroup(name: String, keys: List<String> = emptyList()): Long {
        val id = (_user.value.customGroups.maxOfOrNull { it.id } ?: 0) + 1
        updateUser { it.copy(customGroups = it.customGroups + CustomGroup(id, name.trim(), keys.distinct())) }
        return id
    }

    fun renameGroup(id: Long, name: String) = updateUser { u ->
        u.copy(customGroups = u.customGroups.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun deleteGroup(id: Long) = updateUser { u -> u.copy(customGroups = u.customGroups.filterNot { it.id == id }) }

    fun moveGroup(id: Long, delta: Int) = updateUser { u ->
        val list = u.customGroups.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from >= 0 && from != to) list.add(to, list.removeAt(from))
        u.copy(customGroups = list)
    }

    /** Sender in eine eigene Gruppe aufnehmen (schon enthaltene bleiben). Liefert die Anzahl neuer. */
    fun addToGroup(id: Long, keys: List<String>): Int {
        var added = 0
        updateUser { u ->
            u.copy(customGroups = u.customGroups.map { g ->
                if (g.id != id) g else {
                    val fresh = keys.filter { it !in g.keys }.distinct()
                    added = fresh.size
                    g.copy(keys = g.keys + fresh)
                }
            })
        }
        return added
    }

    fun removeFromGroup(id: Long, keys: Collection<String>) = updateUser { u ->
        u.copy(customGroups = u.customGroups.map { g -> if (g.id == id) g.copy(keys = g.keys - keys.toSet()) else g })
    }

    fun removeFavorites(keys: Collection<String>) = updateUser { u -> u.copy(favorites = u.favorites - keys.toSet()) }

    /** Von Hand sortierte Reihenfolge speichern. */
    fun saveOrder(list: GroupRef, keys: List<String>) = updateUser { u ->
        when (list) {
            GroupRef.Favorites -> u.copy(favorites = Ordering.reorder(u.favorites, keys))
            is GroupRef.Custom -> u.copy(customGroups = u.customGroups.map { g -> if (g.id == list.id) g.copy(keys = Ordering.reorder(g.keys, keys)) else g })
            is GroupRef.Provider -> u.copy(groupOrder = u.groupOrder + (list.name to keys))
            GroupRef.All -> u
        }
    }

    fun hasReminder(channel: Channel, start: Long): Boolean = _user.value.reminders.any { it.channelKey == channel.key && it.start == start }

    /** Erinnerung setzen oder entfernen; true = jetzt gesetzt. */
    fun toggleReminder(channel: Channel, programme: Programme): Boolean {
        var set = false
        updateUser { u ->
            val existing = u.reminders.firstOrNull { it.channelKey == channel.key && it.start == programme.start }
            set = existing == null
            u.copy(reminders = if (existing != null) u.reminders - existing else u.reminders + Reminder(channel.key, channel.name, programme.title, programme.start))
        }
        return set
    }

    fun toggleTeam(team: String): Boolean {
        var now = false
        updateUser { u ->
            now = team !in u.followedTeams
            u.copy(followedTeams = if (now) u.followedTeams + team else u.followedTeams - team, reminders = if (now) u.reminders else u.reminders.filterNot { it.team && it.title.contains(team, ignoreCase = true) })
        }
        return now
    }

    fun hideGroup(name: String) = updateUser { u ->
        val pairs = channels().filter { it.group == name }.map { "${it.playlistId}|$name" }.toSet()
        u.copy(hiddenGroups = u.hiddenGroups + pairs)
    }

    fun showAllGroups() = updateUser { it.copy(hiddenGroups = emptySet()) }

    fun hideMovieGroup(name: String, series: Boolean) = updateUser { u ->
        if (series) u.copy(hiddenSeriesGroups = u.hiddenSeriesGroups + allSeries().filter { it.category == name }.map { "${it.playlistId}|$name" })
        else u.copy(hiddenMovieGroups = u.hiddenMovieGroups + allMovies().filter { it.category == name }.map { "${it.playlistId}|$name" })
    }

    fun rememberWatched(key: String) = updateUser { u ->
        u.copy(recent = (listOf(key) + u.recent.filter { it != key }).take(30), watchedAt = u.watchedAt + (key to System.currentTimeMillis()))
    }

    fun savePosition(key: String, positionMs: Long, durationMs: Long) = updateUser { u ->
        u.copy(
            positions = u.positions + (key to positionMs),
            durations = if (durationMs > 0) u.durations + (key to durationMs) else u.durations,
            watchedAt = u.watchedAt + (key to System.currentTimeMillis()),
        )
    }

    // ---------------------------------------------------------------- Ändern (Playlists, Inhalte)

    fun nextPlaylistId(): Long = (_playlists.value.maxOfOrNull { it.id } ?: 0) + 1

    fun savePlaylist(playlist: Playlist) {
        _playlists.update { list -> if (list.any { it.id == playlist.id }) list.map { if (it.id == playlist.id) playlist else it } else list + playlist }
        save(PLAYLISTS, ListSerializer(Playlist.serializer()), _playlists.value)
    }

    /**
     * Favoriten, eigene Gruppen, Reihenfolgen und Verlauf behalten ihre Sender, wenn der Anbieter beim neuen Laden
     * Adresse, Token oder Stream-Nummer geändert hat (wie auf dem Fire TV, gleiche Regeln in KeyRescue).
     */
    private fun rescueKeys(before: List<Channel>, after: List<Channel>) {
        if (before.isEmpty() || after.isEmpty()) return
        val u = _user.value
        val used = (u.favorites + u.customGroups.flatMap { it.keys } + u.groupOrder.values.flatten() + u.recent).toHashSet()
        val referenced = before.filter { it.key in used }
        if (referenced.isEmpty()) return
        fun Channel.ref() = de.ayutv.data.parse.KeyRescue.Ref(key, name, epgId, group)
        val moves = de.ayutv.data.parse.KeyRescue.remap(referenced.map { it.ref() }, after.map { it.ref() })
        if (moves.isEmpty()) return
        fun List<String>.moved() = map { moves[it] ?: it }.distinct()
        updateUser { x ->
            x.copy(
                favorites = x.favorites.moved(),
                customGroups = x.customGroups.map { it.copy(keys = it.keys.moved()) },
                groupOrder = x.groupOrder.mapValues { (_, keys) -> keys.moved() },
                recent = x.recent.moved(),
            )
        }
    }

    fun deletePlaylist(id: Long) {
        _playlists.update { list -> list.filterNot { it.id == id } }
        _content.update { it - id }
        save(PLAYLISTS, ListSerializer(Playlist.serializer()), _playlists.value)
        File(dir, "content-$id.json.gz").delete()
    }

    fun setContent(playlistId: Long, content: Content) {
        rescueKeys(_content.value[playlistId]?.channels.orEmpty(), content.channels)
        _content.update { it + (playlistId to content) }
        save("content-$playlistId.json.gz", Content.serializer(), content)
    }

    fun setGuide(guide: Map<String, List<Programme>>) {
        rawGuide = guide
        _guide.value = shifted(guide)
        save(GUIDE, MapSerializer(String.serializer(), ListSerializer(Programme.serializer())), guide)
    }

    /** TV-Guide um [hours] verschieben (Anbieter mit falscher Zeitzone). */
    fun setEpgShift(hours: Int) {
        updateUser { it.copy(epgShiftHours = hours.coerceIn(-12, 12)) }
        _guide.value = shifted(rawGuide)
    }

    private fun shifted(guide: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val shift = _user.value.epgShiftHours * 3_600_000L
        if (shift == 0L) return guide
        return guide.mapValues { (_, list) -> list.map { it.copy(start = it.start + shift, stop = it.stop + shift) } }
    }

    // ---------------------------------------------------------------- Profile

    private fun userFile(profile: Long) = if (profile == 0L) USER else "user-$profile.json"

    fun currentProfile(): Profile = _profiles.value.firstOrNull { it.id == _profile.value } ?: _profiles.value.first()

    /**
     * Neues Profil: übernimmt Einstellungen (Schlüssel, Proxy, ausgeblendete Gruppen, Kindersicherung), aber keine
     * Favoriten, eigenen Gruppen, Verlauf, Erinnerungen und Aufnahmen.
     */
    fun createProfile(name: String, pin: String = ""): Profile {
        val id = (_profiles.value.maxOfOrNull { it.id } ?: 0) + 1
        val profile = Profile(id, name.trim().ifEmpty { "Profil $id" }, pin)
        val base = _user.value.copy(
            favorites = emptyList(), customGroups = emptyList(), groupOrder = emptyMap(), recent = emptyList(),
            positions = emptyMap(), durations = emptyMap(), watchedAt = emptyMap(), reminders = emptyList(),
            followedTeams = emptySet(), recordings = emptyList(), subtitleTimings = emptyMap(), intros = emptyMap(),
            parentalUnlocked = false,
        )
        save(userFile(id), UserData.serializer(), base)
        _profiles.update { it + profile }
        save(PROFILES, ListSerializer(Profile.serializer()), _profiles.value)
        return profile
    }

    fun updateProfile(profile: Profile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        save(PROFILES, ListSerializer(Profile.serializer()), _profiles.value)
    }

    /** Profil löschen (nicht das erste und nicht das aktuelle). */
    fun deleteProfile(id: Long) {
        if (id == 0L || id == _profile.value) return
        _profiles.update { list -> list.filterNot { it.id == id } }
        save(PROFILES, ListSerializer(Profile.serializer()), _profiles.value)
        File(dir, userFile(id)).delete()
    }

    /** Zu Profil [id] wechseln; false bei falscher PIN. */
    fun switchProfile(id: Long, pin: String = ""): Boolean {
        val target = _profiles.value.firstOrNull { it.id == id } ?: return false
        if (target.pin.isNotEmpty() && target.pin != pin) return false
        if (id == _profile.value) return true
        save(userFile(_profile.value), UserData.serializer(), _user.value)
        _profile.value = id
        _user.value = read(userFile(id), UserData.serializer()) ?: UserData()
        save(CURRENT_PROFILE, Long.serializer(), id)
        _guide.value = shifted(rawGuide)
        return true
    }

    // ---------------------------------------------------------------- Dateien

    private fun load() {
        _playlists.value = read(PLAYLISTS, ListSerializer(Playlist.serializer())) ?: emptyList()
        _profiles.value = read(PROFILES, ListSerializer(Profile.serializer())) ?: listOf(Profile(0, "Ich"))
        _profile.value = _profiles.value.firstOrNull { it.id == (read(CURRENT_PROFILE, Long.serializer()) ?: 0L) }?.id ?: 0L
        _user.value = read(userFile(_profile.value), UserData.serializer()) ?: UserData()
        _content.value = _playlists.value.mapNotNull { p -> read("content-${p.id}.json.gz", Content.serializer())?.let { p.id to it } }.toMap()
        // Vergangenes nicht wieder laden.
        val now = System.currentTimeMillis()
        rawGuide = read(GUIDE, MapSerializer(String.serializer(), ListSerializer(Programme.serializer())))
            ?.mapValues { (_, list) -> list.filter { it.stop > now - DAY } }.orEmpty()
        _guide.value = shifted(rawGuide)
    }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? = runCatching {
        val file = File(dir, name)
        if (!file.isFile) return null
        val text = if (name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } else file.readText()
        JSON.decodeFromString(serializer, text)
    }.getOrNull()

    @Synchronized
    private fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        val text = JSON.encodeToString(serializer, value)
        if (name.endsWith(".gz")) GZIPOutputStream(temp.outputStream()).bufferedWriter().use { it.write(text) } else temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    companion object {
        private const val PLAYLISTS = "playlists.json"
        private const val USER = "user.json"
        private const val PROFILES = "profiles.json"
        private const val CURRENT_PROFILE = "profile.json"
        private const val GUIDE = "guide.json.gz"
        private const val DAY = 24 * 3_600_000L
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Datenordner: unter Windows %APPDATA%\DnA-TV, sonst ~/.dna-tv. */
        fun defaultDir(): File =
            System.getenv("APPDATA")?.let { File(it, "DnA-TV") } ?: File(System.getProperty("user.home"), ".dna-tv")
    }
}

/** Art der Gruppen (Gruppen ein- und ausblenden). */
enum class GroupKind(val label: String, val lockName: String) { LIVE("Live TV", "LIVE"), MOVIES("Filme", "VOD"), SERIES("Serien", "SERIES") }

/** Ein Land in der Länderauswahl: Kürzel, Name, Anzahl Gruppen, gerade sichtbar. */
data class CountryChoice(val code: String, val label: String, val groups: Int, val visible: Boolean)

/** Welche Liste gerade gezeigt wird (für Sortieren und Zappen). */
sealed interface GroupRef {
    data object All : GroupRef
    data object Favorites : GroupRef
    data class Custom(val id: Long) : GroupRef
    data class Provider(val name: String) : GroupRef
}

/** Reihenfolgen: von Hand sortierte Schlüssel zuerst, der Rest wie gehabt dahinter. */
object Ordering {
    fun <T> apply(list: List<T>, order: List<String>, keyOf: (T) -> String): List<T> {
        if (order.isEmpty()) return list
        val position = order.withIndex().associate { (i, k) -> k to i }
        return list.withIndex().sortedWith(compareBy({ position[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** [current] (alle Schlüssel) so umstellen, dass die in [shown] genannten in deren Reihenfolge stehen. */
    fun reorder(current: List<String>, shown: List<String>): List<String> {
        val shownSet = shown.toSet()
        val rest = current.filterNot { it in shownSet }
        return shown.filter { it in current } + rest
    }
}

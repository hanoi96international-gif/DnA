package de.ayutv.data.remote

/**
 * Abgleich zwischen Geräten (Handy ↔ Fernseher), ohne Android: Playlists einander zuordnen und entscheiden,
 * welche Einträge übernommen werden. Schlüssel beginnen mit der Playlist-Nummer des jeweiligen Geräts.
 */
object SyncMerge {
    data class PlaylistRef(val id: Long, val type: String, val url: String, val username: String?)

    /** Nummern der anderen Seite → eigene Nummern, für Playlists mit gleichem Anbieter und Zugang. */
    fun idMap(remote: List<PlaylistRef>, local: List<PlaylistRef>): Map<Long, Long> {
        val byIdentity = local.associateBy { identity(it) }
        return remote.mapNotNull { r -> byIdentity[identity(r)]?.let { r.id to it.id } }.toMap()
    }

    private fun identity(p: PlaylistRef) =
        Triple(p.type.uppercase(), de.ayutv.data.parse.PlaylistIdentity.address(p.url), if (p.type.equals("XTREAM", true)) p.username?.trim().orEmpty() else "")

    /** Schlüssel der anderen Seite auf die eigenen Playlist-Nummern umschreiben; null, wenn es die Playlist hier nicht gibt. */
    fun remapKey(key: String, ids: Map<Long, Long>): String? {
        val old = key.substringBefore(':').toLongOrNull() ?: return null
        val new = ids[old] ?: return null
        return "$new:" + key.substringAfter(':')
    }

    /** Einträge von [incoming], die neuer sind als die eigenen (nach Zeitpunkt [time]). */
    fun <T> newer(local: Map<String, Long>, incoming: List<T>, key: (T) -> String, time: (T) -> Long): List<T> =
        incoming.filter { item -> time(item) > (local[key(item)] ?: Long.MIN_VALUE) }

    /** Eigene Gruppe: Name und Senderschlüssel in Reihenfolge. */
    data class Group(val name: String, val keys: List<String>)

    /** Ausgeblendete Gruppe des Anbieters. [kind] wie auf dem Fire TV: LIVE, VOD, SERIES. */
    data class Hidden(val playlistId: Long, val kind: String, val name: String)

    /**
     * Eigene Gruppen der anderen Seite, die es hier (nach Name) nicht gibt, mit umgeschriebenen Schlüsseln. Vorhandene
     * Gruppen bleiben, wie sie sind (sonst kämen hier entfernte Sender zurück); Gruppen ohne einen einzigen hier
     * bekannten Sender werden nicht angelegt.
     */
    fun newGroups(localNames: Collection<String>, incoming: List<Group>, ids: Map<Long, Long>): List<Group> {
        val known = localNames.mapTo(HashSet()) { groupName(it) }
        return incoming.mapNotNull { g ->
            if (g.name.isBlank() || !known.add(groupName(g.name))) return@mapNotNull null
            val keys = g.keys.mapNotNull { remapKey(it, ids) }.distinct()
            if (keys.isEmpty()) null else Group(g.name.trim(), keys)
        }
    }

    /**
     * Ausblendungen der anderen Seite übernehmen, aber nur für Playlist und Art (Live, Filme, Serien), in denen hier noch
     * gar nichts ausgeblendet ist: also nach dem Einrichten oder wenn die Daten verloren gingen. Wer hier schon selbst
     * ein- und ausgeblendet hat, behält seine Auswahl.
     */
    fun hiddenToTake(local: List<Hidden>, incoming: List<Hidden>, ids: Map<Long, Long>): List<Hidden> {
        val touched = local.mapTo(HashSet()) { it.playlistId to it.kind }
        return incoming.mapNotNull { h ->
            val id = ids[h.playlistId] ?: return@mapNotNull null
            if ((id to h.kind) in touched) null else Hidden(id, h.kind, h.name)
        }.distinct()
    }

    private fun groupName(name: String) = name.trim().lowercase()
}

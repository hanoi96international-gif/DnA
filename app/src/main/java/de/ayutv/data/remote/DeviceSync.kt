package de.ayutv.data.remote

import de.ayutv.AppContainer
import de.ayutv.data.db.CustomGroupEntity
import de.ayutv.data.db.CustomGroupItemEntity
import de.ayutv.data.db.FavoriteEntity
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.HiddenGroupEntity
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Abgleich zwischen Handy, Fernseher und Windows: Favoriten, Verlauf („Weiter schauen“, gesehene Folgen), eigene
 * Gruppen und ausgeblendete Gruppen. Beide Seiten tauschen ihren Stand aus und übernehmen, was beim anderen neuer ist
 * oder fehlt. Entfernt wird nichts (sonst verschwänden Einträge, die das andere Gerät noch nicht kannte). So hat jedes
 * Gerät eine Kopie: Gehen die eigenen Gruppen auf einem verloren, bringt der nächste Abgleich sie zurück.
 */
class DeviceSync(private val container: AppContainer) {
    data class Result(val favorites: Int, val history: Int, val groups: Int = 0, val hidden: Int = 0) {
        val text: String get() = if (favorites == 0 && history == 0 && groups == 0 && hidden == 0) "Schon auf demselben Stand." else
            "Übernommen: " + listOfNotNull(
                "$favorites Favoriten",
                "$history Verlaufseinträge",
                "$groups eigene Gruppen".takeIf { groups > 0 },
                "$hidden Ausblendungen".takeIf { hidden > 0 },
            ).joinToString(", ") + "."
    }

    suspend fun export(): String {
        val db = container.db
        val json = buildJsonObject {
            put("format", FORMAT)
            put("playlists", buildJsonArray {
                for (p in db.playlists().all()) add(buildJsonObject {
                    put("id", p.id)
                    put("type", p.type.name)
                    put("url", p.url)
                    p.username?.let { put("username", it) }
                })
            })
            put("favorites", buildJsonArray {
                for (f in db.favorites().all()) add(buildJsonObject {
                    put("key", f.key)
                    put("addedAt", f.addedAt)
                    put("position", f.position)
                })
            })
            val items = db.customGroups().allItems().groupBy { it.groupId }
            put("customGroups", buildJsonArray {
                for (g in db.customGroups().groups()) add(buildJsonObject {
                    put("name", g.name)
                    put("keys", buildJsonArray { items[g.id].orEmpty().sortedBy { it.position }.forEach { add(JsonPrimitive(it.key)) } })
                })
            })
            put("hiddenGroups", buildJsonArray {
                for (h in db.hiddenGroups().all()) add(buildJsonObject {
                    put("playlistId", h.playlistId)
                    put("kind", h.kind.name)
                    put("name", h.name)
                })
            })
            put("history", buildJsonArray {
                for (h in db.history().recent(HISTORY_LIMIT)) add(buildJsonObject {
                    put("key", h.key)
                    put("kind", h.kind.name)
                    put("title", h.title)
                    h.subtitle?.let { put("subtitle", it) }
                    h.image?.let { put("image", it) }
                    put("url", h.url)
                    h.groupTitle?.let { put("groupTitle", it) }
                    put("positionMs", h.positionMs)
                    put("durationMs", h.durationMs)
                    put("playCount", h.playCount)
                    put("watchedAt", h.watchedAt)
                })
            })
        }
        return Json.encodeToString(JsonObject.serializer(), json)
    }

    /** Stand des anderen Geräts übernehmen, soweit neuer. */
    suspend fun import(text: String): Result {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Antwort ist kein Abgleich")
        if (root.string("format") != FORMAT) throw IllegalArgumentException("Antwort ist kein Abgleich")
        val db = container.db
        val local = db.playlists().all().map { SyncMerge.PlaylistRef(it.id, it.type.name, it.url, it.username) }
        val remote = root["playlists"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SyncMerge.PlaylistRef(o.long("id") ?: return@mapNotNull null, o.string("type") ?: return@mapNotNull null, o.string("url") ?: return@mapNotNull null, o.string("username"))
        }
        val ids = SyncMerge.idMap(remote, local)

        val known = db.favorites().keys().toHashSet()
        var favorites = 0
        for (e in root["favorites"]?.jsonArray.orEmpty()) {
            val o = e as? JsonObject ?: continue
            val key = SyncMerge.remapKey(o.string("key") ?: continue, ids) ?: continue
            if (key in known) continue
            db.favorites().add(FavoriteEntity(key, o.long("addedAt") ?: System.currentTimeMillis(), o.long("position")?.toInt() ?: 0))
            known += key
            favorites++
        }

        val incoming = root["history"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = SyncMerge.remapKey(o.string("key") ?: return@mapNotNull null, ids) ?: return@mapNotNull null
            val kind = runCatching { HistoryKind.valueOf(o.string("kind").orEmpty()) }.getOrNull() ?: return@mapNotNull null
            HistoryEntity(
                key = key,
                kind = kind,
                title = o.string("title") ?: return@mapNotNull null,
                subtitle = o.string("subtitle"),
                image = o.string("image"),
                // Die Adresse enthält die Zugangsdaten des anderen Geräts: eigene nehmen, falls vorhanden.
                url = o.string("url") ?: return@mapNotNull null,
                groupTitle = o.string("groupTitle"),
                positionMs = o.long("positionMs") ?: 0,
                durationMs = o.long("durationMs") ?: 0,
                playCount = (o.long("playCount") ?: 1).toInt(),
                watchedAt = o.long("watchedAt") ?: return@mapNotNull null,
            )
        }
        val localTimes = db.history().recent(HISTORY_LIMIT * 2).associate { it.key to it.watchedAt }
        val newer = SyncMerge.newer(localTimes, incoming, { it.key }, { it.watchedAt })
        for (h in newer) {
            val mine = db.history().byKey(h.key)
            db.history().upsert(if (mine != null) h.copy(url = mine.url, playCount = maxOf(mine.playCount, h.playCount)) else h)
        }

        // Eigene Gruppen, die es hier nicht gibt (nach Name), mit ihren Sendern anlegen.
        val groups = SyncMerge.newGroups(
            db.customGroups().groups().map { it.name },
            root["customGroups"]?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                SyncMerge.Group(o.string("name") ?: return@mapNotNull null, o["keys"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            },
            ids,
        )
        for (g in groups) {
            val id = db.customGroups().insert(CustomGroupEntity(name = g.name, position = db.customGroups().maxGroupPosition() + 1))
            g.keys.forEachIndexed { index, key -> db.customGroups().addItem(CustomGroupItemEntity(id, key, index)) }
        }

        // Ausblendungen nur dort, wo hier noch nichts ausgeblendet ist (frisch eingerichtet oder Daten verloren).
        val hidden = SyncMerge.hiddenToTake(
            db.hiddenGroups().all().map { SyncMerge.Hidden(it.playlistId, it.kind.name, it.name) },
            root["hiddenGroups"]?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                SyncMerge.Hidden(o.long("playlistId") ?: return@mapNotNull null, o.string("kind") ?: return@mapNotNull null, o.string("name") ?: return@mapNotNull null)
            },
            ids,
        ).mapNotNull { h -> runCatching { GroupKind.valueOf(h.kind) }.getOrNull()?.let { HiddenGroupEntity(h.playlistId, it, h.name) } }
        if (hidden.isNotEmpty()) db.hiddenGroups().hide(hidden)
        return Result(favorites, newer.size, groups.size, hidden.size)
    }

    companion object {
        const val FORMAT = "dna-tv-sync"
        private const val HISTORY_LIMIT = 1_000

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
    }
}

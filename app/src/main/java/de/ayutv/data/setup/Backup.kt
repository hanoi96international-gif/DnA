package de.ayutv.data.setup

import de.ayutv.AppContainer
import de.ayutv.BuildConfig
import de.ayutv.data.db.FavoriteEntity
import de.ayutv.data.db.CustomGroupEntity
import de.ayutv.data.db.CustomGroupItemEntity
import de.ayutv.data.db.GroupOrderEntity
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.HiddenGroupEntity
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.security.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Sicherung als JSON-Datei, auf Wunsch mit Passwort verschlüsselt: Playlists (mit Zugangsdaten), Favoriten, ausgeblendete Gruppen, Einstellungen
 * (auch gefolgte Vereine) und gelernte Intros. Beim Einspielen bekommen Playlists neue Nummern; Favoriten
 * und Gruppen werden darauf umgeschrieben. Schon vorhandene Playlists werden nicht doppelt angelegt.
 */
class Backup(private val container: AppContainer) {

    /** Sicherung als Text; mit [password] verschlüsselt (sonst stehen die Zugangsdaten lesbar darin). */
    suspend fun create(password: String? = null): String {
        // Ohne Passwort stünde alles lesbar in der Datei: Passwörter und Schlüssel dann weglassen (Playlist-Adressen
        // und Benutzernamen bleiben, die Passwörter trägt man nach dem Einspielen neu ein).
        val plain = createPlain(includeSecrets = !password.isNullOrEmpty())
        if (password.isNullOrEmpty()) return plain
        val sealed = withContext(Dispatchers.Default) { BackupCrypto.seal(plain, password) }
        return PRETTY.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("app", BuildConfig.VERSION_NAME)
            put("encrypted", buildJsonObject {
                put("salt", sealed.salt)
                put("iv", sealed.iv)
                put("data", sealed.data)
                put("iterations", sealed.iterations)
            })
        })
    }

    /** Mit Passwort geschützt? Dann braucht [restore] das Passwort. */
    fun needsPassword(text: String): Boolean =
        runCatching { Json.parseToJsonElement(text).jsonObject["encrypted"] is JsonObject }.getOrDefault(false)

    private suspend fun createPlain(includeSecrets: Boolean): String {
        val db = container.db
        val json = buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("app", BuildConfig.VERSION_NAME)
            put("created", System.currentTimeMillis())
            put("settings", buildJsonObject {
                for ((type, values) in container.settings.export()) {
                    put(type, buildJsonObject {
                        for ((key, value) in values) if (includeSecrets || key !in SECRET_SETTINGS) put(key, value.toJson())
                    })
                }
            })
            put("playlists", buildJsonArray {
                for (p in db.playlists().all()) add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    put("url", p.url)
                    p.epgUrl?.let { put("epgUrl", it) }
                    p.username?.let { put("username", it) }
                    if (includeSecrets) Credentials.reveal(p.password)?.let { put("password", it) }
                    put("enabled", p.enabled)
                    p.timezone?.let { put("timezone", it) }
                })
            })
            put("favorites", buildJsonArray {
                for (f in db.favorites().all()) add(buildJsonObject {
                    put("key", f.key)
                    put("addedAt", f.addedAt)
                    put("position", f.position)
                })
            })
            // Eigene Gruppen mit ihren Sendern (in Reihenfolge) und die von Hand sortierten Gruppen des Anbieters.
            val items = db.customGroups().allItems().groupBy { it.groupId }
            put("customGroups", buildJsonArray {
                for (g in db.customGroups().groups()) add(buildJsonObject {
                    put("name", g.name)
                    put("position", g.position)
                    put("items", buildJsonArray {
                        for (i in items[g.id].orEmpty()) add(buildJsonObject {
                            put("key", i.key)
                            put("position", i.position)
                        })
                    })
                })
            })
            put("groupOrder", buildJsonArray {
                for (o in db.groupOrder().all()) add(buildJsonObject {
                    put("scope", o.scope)
                    put("key", o.key)
                    put("position", o.position)
                })
            })
            put("hiddenGroups", buildJsonArray {
                for (g in db.hiddenGroups().all()) add(buildJsonObject {
                    put("playlistId", g.playlistId)
                    put("kind", g.kind.name)
                    put("name", g.name)
                })
            })
            put("intros", buildJsonObject { for ((k, v) in container.intros.all()) put(k, v) })
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json)
    }

    /** Spielt eine Sicherung ein und sagt in einem Satz, was übernommen wurde. */
    suspend fun restore(text: String, password: String? = null): String {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Das ist keine DnA-TV-Sicherung (kein JSON).")
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) throw IllegalArgumentException("Das ist keine DnA-TV-Sicherung.")
        (root["encrypted"] as? JsonObject)?.let { e ->
            if (password.isNullOrEmpty()) throw IllegalArgumentException("Diese Sicherung ist mit einem Passwort geschützt – bitte Passwort eingeben.")
            val sealed = BackupCrypto.Sealed(
                salt = e.string("salt") ?: throw IllegalArgumentException("Sicherung beschädigt."),
                iv = e.string("iv") ?: throw IllegalArgumentException("Sicherung beschädigt."),
                data = e.string("data") ?: throw IllegalArgumentException("Sicherung beschädigt."),
                iterations = e.long("iterations")?.toInt() ?: throw IllegalArgumentException("Sicherung beschädigt."),
            )
            val inner = withContext(Dispatchers.Default) { BackupCrypto.open(sealed, password) }
            return restore(inner)
        }
        val db = container.db

        root["settings"]?.let { settings ->
            val values = settings.jsonObject.mapValues { (_, group) ->
                group.jsonObject.mapValues { (_, v) -> v.toValue() }.filterValues { it != null }.mapValues { it.value!! }
            }
            container.settings.import(values)
        }

        val existing = db.playlists().all()
        val ids = HashMap<Long, Long>()
        var added = 0
        for (item in root["playlists"]?.jsonArray.orEmpty()) {
            val o = item.jsonObject
            val oldId = o.long("id") ?: continue
            val type = runCatching { PlaylistType.valueOf(o.string("type").orEmpty()) }.getOrNull() ?: continue
            val url = o.string("url") ?: continue
            val username = o.string("username")
            val same = existing.firstOrNull { de.ayutv.data.parse.PlaylistIdentity.same(it.type.name, it.url, it.username, type.name, url, username) }
            ids[oldId] = same?.id ?: run {
                added++
                val newId = container.playlists.add(
                    PlaylistEntity(
                        name = o.string("name") ?: "Playlist",
                        type = type,
                        url = url,
                        epgUrl = o.string("epgUrl"),
                        username = username,
                        password = o.string("password"),
                        enabled = o.bool("enabled") ?: true,
                        timezone = o.string("timezone"),
                    )
                )
                container.scope.launch { container.playlists.sync(newId) }
                newId
            }
        }

        var favorites = 0
        for (item in root["favorites"]?.jsonArray.orEmpty()) {
            val o = item.jsonObject
            val key = remapKey(o.string("key") ?: continue, ids) ?: continue
            db.favorites().add(FavoriteEntity(key, o.long("addedAt") ?: System.currentTimeMillis(), o.long("position")?.toInt() ?: 0))
            favorites++
        }

        // Eigene Gruppen: gleichnamige vorhandene ergänzen, sonst neu anlegen.
        var groups = 0
        val known = db.customGroups().groups().associateBy { it.name }
        for (item in root["customGroups"]?.jsonArray.orEmpty()) {
            val o = item.jsonObject
            val name = o.string("name")?.takeIf { it.isNotBlank() } ?: continue
            val id = known[name]?.id ?: db.customGroups().insert(
                CustomGroupEntity(name = name, position = o.long("position")?.toInt() ?: (db.customGroups().maxGroupPosition() + 1))
            )
            for (entry in o["items"]?.jsonArray.orEmpty()) {
                val e = entry.jsonObject
                val key = remapKey(e.string("key") ?: continue, ids) ?: continue
                db.customGroups().addItem(CustomGroupItemEntity(id, key, e.long("position")?.toInt() ?: 0))
            }
            groups++
        }
        val order = root["groupOrder"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val key = remapKey(o.string("key") ?: return@mapNotNull null, ids) ?: return@mapNotNull null
            GroupOrderEntity(o.string("scope") ?: return@mapNotNull null, key, o.long("position")?.toInt() ?: return@mapNotNull null)
        }
        if (order.isNotEmpty()) db.groupOrder().upsert(order)

        val hidden = root["hiddenGroups"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val playlist = ids[o.long("playlistId") ?: return@mapNotNull null] ?: return@mapNotNull null
            val kind = runCatching { GroupKind.valueOf(o.string("kind").orEmpty()) }.getOrNull() ?: return@mapNotNull null
            HiddenGroupEntity(playlist, kind, o.string("name") ?: return@mapNotNull null)
        }
        if (hidden.isNotEmpty()) db.hiddenGroups().hide(hidden)

        root["intros"]?.jsonObject?.let { intros ->
            container.intros.restore(intros.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap())
        }

        return "Übernommen: ${ids.size} Playlists ($added neu, werden jetzt geladen), $favorites Favoriten, $groups eigene Gruppen, " +
            "${hidden.size} ausgeblendete Gruppen und die Einstellungen."
    }

    companion object {
        const val FORMAT = "dna-tv-backup"
        /** Einstellungen mit Passwörtern und Schlüsseln (nur in Sicherungen mit Passwort). */
        private val SECRET_SETTINGS = setOf("opensubtitles_password", "proxy_password", "deepl_key")
        private val PRETTY = Json { prettyPrint = true }

        /** Favoriten-Schlüssel beginnen mit der Playlist-Nummer („3:c:1234“): auf die neue Nummer umschreiben. */
        fun remapKey(key: String, ids: Map<Long, Long>): String? {
            val old = key.substringBefore(':').toLongOrNull() ?: return null
            val new = ids[old] ?: return null
            return "$new:" + key.substringAfter(':')
        }

        private fun Any.toJson(): JsonElement = when (this) {
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is String -> JsonPrimitive(this)
            is Set<*> -> JsonArray(filterIsInstance<String>().map(::JsonPrimitive))
            else -> JsonPrimitive(toString())
        }

        private fun JsonElement.toValue(): Any? = when (this) {
            is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                else -> null
            }
            else -> null
        }

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
    }
}

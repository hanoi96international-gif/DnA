package de.ayutv.desktop.data

import de.ayutv.data.remote.SyncMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Abgleich mit dem Fire TV (und darüber mit dem Handy), wie zwischen Handy und Fernseher in der Fire-TV-App:
 * Favoriten, Verlauf („Weiter schauen“, zuletzt gesehene Sender), eigene Gruppen und ausgeblendete Gruppen. Beide
 * Seiten tauschen ihren Stand aus und übernehmen, was beim anderen neuer ist oder fehlt; entfernt wird nichts. So hat
 * jedes Gerät eine Kopie der eigenen Gruppen (Regeln in SyncMerge, gleich wie auf dem Fire TV).
 * Weg: POST `http://<Fire TV>:8096/s`, Inhalt mit der PIN verschlüsselt (LocalChannel; Adresse und PIN unter Fire TV → Einstellungen → Handy als Fernbedienung).
 */
class DeviceSync(private val store: Store, http: OkHttpClient) {
    data class Result(val favorites: Int, val history: Int, val groups: Int = 0, val hidden: Int = 0) {
        val text: String get() = if (favorites == 0 && history == 0 && groups == 0 && hidden == 0) "Schon auf demselben Stand." else
            "Übernommen: " + listOfNotNull(
                "$favorites Favoriten",
                "$history Verlaufseinträge",
                "$groups eigene Gruppen".takeIf { groups > 0 },
                "$hidden Ausblendungen".takeIf { hidden > 0 },
            ).joinToString(", ") + "."
    }

    private val http = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Mit dem Fire TV unter [address] (z. B. http://192.168.178.40:8096) und [pin] abgleichen. */
    suspend fun withFireTv(address: String, pin: String): Result = withContext(Dispatchers.IO) {
        val base = address.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
            .let { if (Regex(""":\d+$""").containsMatchIn(it.substringAfter("://"))) it else "$it:8096" }
        // Verschlüsselt (AES-256-GCM, Schlüssel aus der PIN): Die PIN steht nicht mehr in der Adresse, und Favoriten,
        // Verlauf und Gruppen gehen nicht lesbar durchs WLAN.
        val secure = de.ayutv.data.setup.LocalChannel(pin.trim())
        val request = Request.Builder().url("$base/s")
            .post(secure.request("/sync", export()).toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
        val reply = http.newCall(request).execute().use { response ->
            if (response.code == 403) throw IOException("Falsche PIN – oder DnA-TV auf dem Fire TV ist älter als 0.45.4 (bitte aktualisieren)")
            if (response.code == 404) throw IOException("Falsche Adresse (HTTP 404)")
            if (!response.isSuccessful) throw IOException("Fire TV antwortet mit HTTP ${response.code}")
            runCatching { secure.reply(response.body.string()) }.getOrElse { throw IOException("Antwort des Fire TV nicht lesbar (falsche PIN?)") }
        }
        val code = reply.substringBefore('\n').toIntOrNull() ?: 500
        val answer = reply.substringAfter('\n', "")
        if (code !in 200..299) throw IOException("Fire TV antwortet mit $code: ${answer.take(120)}")
        import(answer)
    }

    /** Eigener Stand im Format der Fire-TV-App. */
    fun export(): String {
        val u = store.user.value
        val json = buildJsonObject {
            put("format", FORMAT)
            put("playlists", buildJsonArray {
                for (p in store.playlists.value) add(buildJsonObject {
                    put("id", p.id)
                    put("type", p.type.name)
                    put("url", p.url)
                    p.username?.let { put("username", it) }
                })
            })
            put("favorites", buildJsonArray {
                u.favorites.forEachIndexed { index, key ->
                    add(buildJsonObject {
                        put("key", key)
                        put("addedAt", u.watchedAt[key] ?: 0L)
                        put("position", index)
                    })
                }
            })
            put("customGroups", buildJsonArray {
                for (g in u.customGroups) add(buildJsonObject {
                    put("name", g.name)
                    put("keys", buildJsonArray { g.keys.forEach { add(JsonPrimitive(it)) } })
                })
            })
            put("hiddenGroups", buildJsonArray {
                for (h in hiddenOf(u)) add(buildJsonObject {
                    put("playlistId", h.playlistId)
                    put("kind", h.kind)
                    put("name", h.name)
                })
            })
            put("history", buildJsonArray {
                // Sender (zuletzt gesehen) und Filme/Folgen (Wiedergabestand).
                for (key in u.recent) {
                    val channel = store.channel(key) ?: continue
                    add(historyEntry(key, "LIVE", channel.name, channel.group, channel.logo, channel.url, 0, 0, u.watchedAt[key] ?: 0))
                }
                val movies = store.allMovies().associateBy { it.key }
                for ((key, position) in u.positions) {
                    val movie = movies[key]
                    val kind = if (movie != null) "VOD" else if (de.ayutv.data.parse.Episodes.seriesKeyOf(key) != null) "EPISODE" else continue
                    add(historyEntry(key, kind, movie?.name ?: key, movie?.category, movie?.poster, movie?.url.orEmpty(), position, u.durations[key] ?: 0, u.watchedAt[key] ?: 0))
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), json)
    }

    private fun historyEntry(key: String, kind: String, title: String, group: String?, image: String?, url: String, position: Long, duration: Long, at: Long) =
        buildJsonObject {
            put("key", key)
            put("kind", kind)
            put("title", title)
            image?.let { put("image", it) }
            put("url", url)
            group?.let { put("groupTitle", it) }
            put("positionMs", position)
            put("durationMs", duration)
            put("playCount", 1)
            put("watchedAt", at)
        }

    /** Stand des Fire TV übernehmen, soweit neuer. */
    fun import(text: String): Result {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root == null || root.string("format") != FORMAT) throw IllegalArgumentException("Antwort ist kein Abgleich")
        val local = store.playlists.value.map { SyncMerge.PlaylistRef(it.id, it.type.name, it.url, it.username) }
        val remote = root["playlists"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SyncMerge.PlaylistRef(o.long("id") ?: return@mapNotNull null, o.string("type") ?: return@mapNotNull null, o.string("url") ?: return@mapNotNull null, o.string("username"))
        }
        val ids = SyncMerge.idMap(remote, local)

        val incomingGroups = root["customGroups"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SyncMerge.Group(o.string("name") ?: return@mapNotNull null, o["keys"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        }
        val incomingHidden = root["hiddenGroups"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SyncMerge.Hidden(o.long("playlistId") ?: return@mapNotNull null, o.string("kind") ?: return@mapNotNull null, o.string("name") ?: return@mapNotNull null)
        }

        var favorites = 0
        var history = 0
        var groups = 0
        var hidden = 0
        store.updateUser { u ->
            val known = u.favorites.toMutableList()
            val times = u.watchedAt.toMutableMap()
            for (e in root["favorites"]?.jsonArray.orEmpty()) {
                val o = e as? JsonObject ?: continue
                val key = SyncMerge.remapKey(o.string("key") ?: continue, ids) ?: continue
                if (key in known) continue
                known += key
                favorites++
            }
            val recent = u.recent.toMutableList()
            val positions = u.positions.toMutableMap()
            val durations = u.durations.toMutableMap()
            for (e in root["history"]?.jsonArray.orEmpty()) {
                val o = e as? JsonObject ?: continue
                val key = SyncMerge.remapKey(o.string("key") ?: continue, ids) ?: continue
                val at = o.long("watchedAt") ?: continue
                if (at <= (times[key] ?: Long.MIN_VALUE)) continue
                when (o.string("kind")) {
                    "LIVE" -> {
                        if (store.channel(key) == null) continue
                        recent.remove(key)
                        recent.add(0, key)
                    }
                    "VOD", "EPISODE" -> {
                        positions[key] = o.long("positionMs") ?: 0
                        o.long("durationMs")?.takeIf { it > 0 }?.let { durations[key] = it }
                    }
                    else -> continue
                }
                times[key] = at
                history++
            }
            // Sender nach Zeitpunkt ordnen (neuester zuerst), höchstens 30 wie sonst.
            val ordered = recent.sortedByDescending { times[it] ?: 0L }.take(30)
            // Eigene Gruppen, die es hier (nach Name) nicht gibt; Ausblendungen nur, wo hier noch nichts ausgeblendet ist.
            val newGroups = SyncMerge.newGroups(u.customGroups.map { it.name }, incomingGroups, ids)
            groups = newGroups.size
            var nextId = (u.customGroups.maxOfOrNull { it.id } ?: 0) + 1
            val take = SyncMerge.hiddenToTake(hiddenOf(u), incomingHidden, ids)
            hidden = take.size
            fun Set<String>.plus(kind: String) = this + take.filter { it.kind == kind }.map { "${it.playlistId}|${it.name}" }
            u.copy(
                favorites = known, recent = ordered, positions = positions, durations = durations, watchedAt = times,
                customGroups = u.customGroups + newGroups.map { CustomGroup(nextId++, it.name, it.keys) },
                hiddenGroups = u.hiddenGroups.plus(GroupKind.LIVE.lockName),
                hiddenMovieGroups = u.hiddenMovieGroups.plus(GroupKind.MOVIES.lockName),
                hiddenSeriesGroups = u.hiddenSeriesGroups.plus(GroupKind.SERIES.lockName),
            )
        }
        return Result(favorites, history, groups, hidden)
    }

    /** Ausgeblendete Gruppen im Format des Fire TV („<Playlist>|<Gruppe>“ → Playlist, Art, Name). */
    private fun hiddenOf(u: UserData): List<SyncMerge.Hidden> {
        fun Set<String>.refs(kind: GroupKind) = mapNotNull { entry ->
            val id = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            SyncMerge.Hidden(id, kind.lockName, entry.substringAfter('|'))
        }
        return u.hiddenGroups.refs(GroupKind.LIVE) + u.hiddenMovieGroups.refs(GroupKind.MOVIES) + u.hiddenSeriesGroups.refs(GroupKind.SERIES)
    }

    companion object {
        const val FORMAT = "dna-tv-sync"
        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
    }
}

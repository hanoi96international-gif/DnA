package de.ayutv.desktop.data

import de.ayutv.data.setup.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Sicherung der Fire-TV-App übernehmen: Playlists (mit Passwörtern, wenn die Sicherung ein Passwort hat), Favoriten,
 * eigene Gruppen, Reihenfolgen, ausgeblendete Gruppen und die Schlüssel für OpenSubtitles und DeepL. Direkt vom
 * Fire TV (Adresse aus „Einrichtung per Handy“) oder aus einer gespeicherten Datei.
 */
class BackupImport(private val store: Store) {
    data class Result(val playlists: Int, val favorites: Int, val groups: Int, val missingPasswords: Int) {
        val summary: String
            get() = "Übernommen: $playlists Playlists, $favorites Favoriten, $groups eigene Gruppen" +
                if (missingPasswords > 0) ". Bei $missingPasswords Playlist(s) fehlt das Passwort (Sicherung ohne Passwort): bitte in den Einstellungen eintragen." else "."
    }

    /**
     * Vom Fire TV laden: [setupUrl] wie dort angezeigt („http://192.168.x.y:8095/123456“), [given] = Passwort (optional). Ohne Passwort wählt die App selbst eines für die Übertragung: Nur verschlüsselt enthält die
     * Sicherung Playlist- und Proxy-Passwörter und die Schlüssel – so kommen sie immer mit.
     */
    suspend fun fromFireTv(http: OkHttpClient, setupUrl: String, given: String): Result = withContext(Dispatchers.IO) {
        val password = given.ifEmpty {
            val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        val base = setupAddress(setupUrl)
        val request = Request.Builder().url("$base/backup").post(FormBody.Builder().add("backup_password", password).build()).build()
        // Kurz warten: Ist der Fire TV nicht da, soll die Meldung gleich kommen statt nach einer halben Minute.
        val quick = http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val text = try {
            quick.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Fire TV antwortet mit HTTP ${response.code}. Stimmt die Adresse aus „Einrichtung per Handy“ (mit der Zahl am Ende)?")
                response.body.string()
            }
        } catch (e: IOException) {
            if (e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.NoRouteToHostException) throw IOException(UNREACHABLE, e)
            throw e
        }
        import(text, password.ifEmpty { null })
    }

    /** Sicherung als Text einspielen. Ist sie verschlüsselt, wird [password] gebraucht. */
    fun import(text: String, password: String?): Result {
        var root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Das ist keine DnA-TV-Sicherung.")
        if (root.str("format") != FORMAT) throw IllegalArgumentException("Das ist keine DnA-TV-Sicherung.")
        (root["encrypted"] as? JsonObject)?.let { e ->
            if (password.isNullOrEmpty()) throw IllegalArgumentException("Diese Sicherung ist mit einem Passwort geschützt – bitte Passwort eingeben.")
            val sealed = BackupCrypto.Sealed(
                e.str("salt") ?: bad(), e.str("iv") ?: bad(), e.str("data") ?: bad(),
                e["iterations"]?.jsonPrimitive?.intOrNull ?: bad(),
            )
            root = Json.parseToJsonElement(BackupCrypto.open(sealed, password)).jsonObject
        }

        // Playlists: gleiche (Art, Adresse, Benutzer) wiederverwenden, sonst neu. Alte Nummer → neue Nummer.
        val ids = HashMap<Long, Long>()
        var missing = 0
        for (item in root["playlists"]?.jsonArray.orEmpty()) {
            val o = item.jsonObject
            val oldId = o.long("id") ?: continue
            val type = runCatching { PlaylistType.valueOf(o.str("type").orEmpty()) }.getOrNull() ?: continue
            val url = o.str("url") ?: continue
            val username = o.str("username")
            val existing = store.playlists.value.firstOrNull { de.ayutv.data.parse.PlaylistIdentity.same(it.type.name, it.url, it.username, type.name, url, username) }
            val password = o.str("password") ?: existing?.password
            if (type == PlaylistType.XTREAM && password.isNullOrEmpty()) missing++
            val playlist = Playlist(
                id = existing?.id ?: store.nextPlaylistId(),
                name = o.str("name") ?: existing?.name ?: "Playlist",
                type = type,
                url = url,
                username = username,
                password = password,
                epgUrl = o.str("epgUrl") ?: existing?.epgUrl,
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            store.savePlaylist(playlist)
            ids[oldId] = playlist.id
        }

        val favorites = root["favorites"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val key = remapKey(o.str("key") ?: return@mapNotNull null, ids) ?: return@mapNotNull null
            Triple(key, o.int("position") ?: 0, o.long("addedAt") ?: 0L)
        }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
        val added = store.addFavorites(favorites)

        var groups = 0
        for (item in root["customGroups"]?.jsonArray.orEmpty()) {
            val o = item.jsonObject
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: continue
            val keys = o["items"]?.jsonArray.orEmpty().mapNotNull { e ->
                val i = e.jsonObject
                (remapKey(i.str("key") ?: return@mapNotNull null, ids) ?: return@mapNotNull null) to (i.int("position") ?: 0)
            }.sortedBy { it.second }.map { it.first }
            val id = store.user.value.customGroups.firstOrNull { it.name == name }?.id ?: store.createGroup(name)
            store.addToGroup(id, keys)
            groups++
        }

        val order = root["groupOrder"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            Triple(o.str("scope") ?: return@mapNotNull null, remapKey(o.str("key") ?: return@mapNotNull null, ids) ?: return@mapNotNull null, o.int("position") ?: 0)
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, list) -> list.sortedBy { it.second }.map { it.first } }

        // Ausgeblendete Gruppen je Art: Live, Filme (VOD), Serien.
        val hiddenByKind = root["hiddenGroups"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val playlist = ids[o.long("playlistId") ?: return@mapNotNull null] ?: return@mapNotNull null
            (o.str("kind") ?: "LIVE") to "$playlist|${o.str("name") ?: return@mapNotNull null}"
        }.groupBy({ it.first }, { it.second })
        val hidden = hiddenByKind["LIVE"].orEmpty()

        // Schlüssel aus den Einstellungen (alle Gruppen durchsuchen).
        val settings = (root["settings"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()
        fun setting(key: String) = settings.firstNotNullOfOrNull { it[key]?.jsonPrimitive?.contentOrNull?.takeIf { v -> v.isNotBlank() } }

        // Proxy (Fire TV: Einstellungen → Proxy). Das Passwort steht nur in Sicherungen mit Passwort.
        val proxyIds = settings.firstNotNullOfOrNull { it["proxy_playlists"] as? JsonArray }.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull()?.let(ids::get) }.toSet()
        val proxy = setting("proxy_host")?.let { host ->
            val before = store.user.value.proxy
            ProxySettings(
                mode = setting("proxy_mode") ?: "OFF",
                type = setting("proxy_type") ?: "HTTP",
                host = host,
                port = setting("proxy_port")?.toIntOrNull() ?: 0,
                user = setting("proxy_user").orEmpty(),
                password = setting("proxy_password") ?: before.password.takeIf { before.host == host }.orEmpty(),
                playlists = proxyIds,
            )
        }

        store.updateUser { u ->
            u.copy(
                proxy = proxy ?: u.proxy,
                groupOrder = u.groupOrder + order,
                hiddenGroups = u.hiddenGroups + hidden,
                hiddenMovieGroups = u.hiddenMovieGroups + hiddenByKind["VOD"].orEmpty(),
                hiddenSeriesGroups = u.hiddenSeriesGroups + hiddenByKind["SERIES"].orEmpty(),
                openSubtitlesKey = setting("opensubtitles_key") ?: u.openSubtitlesKey,
                openSubtitlesUser = setting("opensubtitles_user") ?: u.openSubtitlesUser,
                openSubtitlesPassword = setting("opensubtitles_password") ?: u.openSubtitlesPassword,
                deeplKey = setting("deepl_key") ?: u.deeplKey,
                subtitleLanguage = setting("subtitle_language_code") ?: u.subtitleLanguage,
            )
        }
        return Result(ids.size, added, groups, missing)
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun bad(): Nothing = throw IllegalArgumentException("Sicherung beschädigt.")

    companion object {
        const val FORMAT = "dna-tv-backup"

        private const val UNREACHABLE = "Fire TV nicht erreichbar. Auf dem Fire TV muss Einstellungen → Playlists offen sein " +
            "(Kasten „Einrichtung per Handy“). PC und Fire TV im selben Netz? Ein VPN am PC (z. B. NordVPN, Proton VPN) " +
            "sperrt oft das Heimnetz: kurz pausieren oder „Lokales Netzwerk erlauben“ einschalten."

        /**
         * Adresse aus „Einrichtung per Handy“ prüfen: Sie endet auf eine Zahl (PIN), Port 8095. Die Adresse der
         * Handy-Fernbedienung (Port 8096) ist eine andere und kann keine Sicherung liefern.
         */
        fun setupAddress(input: String): String {
            val base = input.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
            val url = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Das ist keine gültige Adresse (z. B. http://192.168.178.40:8095/123456).")
            if (url.port == 8096) throw IllegalArgumentException(
                "Das ist die Adresse der Handy-Fernbedienung (Port 8096). Gebraucht wird die aus „Einrichtung per Handy“ " +
                    "(Fire TV: Einstellungen → Playlists), z. B. http://${url.host}:8095/123456.",
            )
            if (url.pathSegments.none { it.isNotEmpty() }) throw IllegalArgumentException(
                "Es fehlt die Zahl am Ende der Adresse: Sie steht auf dem Fire TV unter Einstellungen → Playlists → „Einrichtung per Handy“, " +
                    "z. B. http://${url.host}:${url.port}/123456.",
            )
            return base
        }

        /** Schlüssel beginnen mit der Playlist-Nummer („3:c:1234“): auf die neue Nummer umschreiben. */
        fun remapKey(key: String, ids: Map<Long, Long>): String? {
            val old = key.substringBefore(':').toLongOrNull() ?: return null
            val new = ids[old] ?: return null
            return "$new:" + key.substringAfter(':')
        }
    }
}

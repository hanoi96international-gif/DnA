package de.ayutv.desktop.player

import de.ayutv.data.parse.OpenSubtitlesParsing
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SubtitleCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Untertitel für Filme und Folgen: zuerst eine fertige Datei in der Wunschsprache von OpenSubtitles, sonst
 * (mit DeepL-Schlüssel) die englische Datei übersetzen. Ergebnisse bleiben im Datenordner, das zweite Mal geht
 * es ohne Download.
 *
 * Gesucht wird wie auf dem Fire TV, damit auch deutsche Namen die richtige (englische) Datei finden:
 * - Filme über die TMDB-Kennung vom Anbieter, sonst über Titel und Jahr
 * - Serienfolgen über die TMDB-Kennung der Serie vom Anbieter, sonst über das Serienverzeichnis von OpenSubtitles
 *   (kennt deutsche Zweitnamen wie „Die Gummibärenbande“), sonst über den Namen – jeweils nur Treffer, die sicher
 *   zu dieser Serie gehören (bei gleichnamigen Serien entscheidet das Jahr, geraten wird nie)
 * - Mit Konto (Benutzer und Passwort, z. B. VIP) gilt dessen Tageskontingent, sonst das ohne Konto.
 */
class Subtitles(private val http: OkHttpClient, private val dir: File) {
    /** Gefundene Datei und woher sie stammt. */
    data class Found(val file: File, val source: String)

    /** Zugang zu OpenSubtitles: eigener Schlüssel, optional Konto (höheres Tageskontingent). */
    data class Account(val apiKey: String, val user: String = "", val password: String = "")

    /** [variant] > 0: die nächstbeste Datei (wenn die erste nicht zum Film passt, Menü „Andere Untertitel-Datei laden“). */
    suspend fun find(key: String, title: String, url: String, language: String, account: Account, deeplKey: String, variant: Int = 0, progress: (String) -> Unit = {}): Found =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val cached = File(dir, "${safe(key)}.$language${if (variant > 0) ".$variant" else ""}.srt")
            if (cached.isFile && cached.length() > 0) return@withContext Found(cached, "gespeichert")
            if (account.apiKey.isBlank()) throw IOException("Kein OpenSubtitles-Schlüssel. Bitte unter Einstellungen eintragen oder die Sicherung vom Fire TV übernehmen.")

            progress("Suche Untertitel bei OpenSubtitles …")
            val found = candidates(account.apiKey, key, title, url, language)
            if (variant > 0 && found.isNotEmpty() && found.getOrNull(variant) == null) throw IOException("Keine weitere Untertitel-Datei in ${language.uppercase()} gefunden.")
            found.getOrNull(variant)?.let { best ->
                cached.writeText(download(account, best.fileId))
                return@withContext Found(cached, "OpenSubtitles")
            }
            if (deeplKey.isBlank() || language == "en") throw IOException("Keine Untertitel in ${language.uppercase()} gefunden.")
            val english = candidates(account.apiKey, key, title, url, "en").getOrNull(variant)
                ?: throw IOException(if (variant > 0) "Keine weitere Untertitel-Datei gefunden." else "Keine Untertitel gefunden.")
            progress("Übersetze englische Untertitel mit DeepL …")
            val cues = Srt.parse(download(account, english.fileId))
            val translated = translate(deeplKey, cues.map { it.text }, language)
            cached.writeText(Srt.write(cues.mapIndexed { i, c -> c.copy(text = translated[i]) }))
            Found(cached, "DeepL")
        }

    // ---------------------------------------------------------------- Suche

    /** Passende Dateien in [language], beste zuerst; nur solche, die sicher zu diesem Film bzw. dieser Folge gehören. */
    private fun candidates(apiKey: String, key: String, title: String, url: String, language: String): List<SubtitleCandidate> {
        val episode = OpenSubtitlesParsing.episodeOf(title)
        if (episode != null) {
            val (series, season, number) = episode
            fun fits(c: SubtitleCandidate) = (c.season == null || c.season == season) && (c.episode == null || c.episode == number)
            val episodeParams = mapOf("season_number" to season.toString(), "episode_number" to number.toString())
            val info = runCatching { seriesInfo(url, key) }.getOrNull()
            // 1. TMDB-Kennung der Serie vom Anbieter: unabhängig vom deutschen Namen.
            info?.tmdbId?.let { tmdb ->
                search(apiKey, language, episodeParams + ("parent_tmdb_id" to tmdb))
                    .filter { fits(it) && (it.parentTmdbId == null || it.parentTmdbId.toString() == tmdb) }
                    .let(OpenSubtitlesParsing::byQuality).takeIf { it.isNotEmpty() }?.let { return it }
            }
            val year = info?.year ?: OpenSubtitlesParsing.cleanTitle(series).second
            for (query in OpenSubtitlesParsing.seriesQueries(series, info?.originalName)) {
                // 2. Serienverzeichnis (deutsche Zweitnamen), dann genau diese Folge der gefundenen Serie.
                runCatching { findShow(apiKey, query, year) }.getOrNull()?.let { show ->
                    search(apiKey, language, episodeParams + ("parent_feature_id" to show.id.toString()))
                        .filter { fits(it) && (it.parentFeatureId == null || it.parentFeatureId == show.id) }
                        .let(OpenSubtitlesParsing::byQuality).takeIf { it.isNotEmpty() }?.let { return it }
                }
                // 3. Freie Namenssuche: nur Folgen einer Serie, die genau so heißt.
                search(apiKey, language, episodeParams + mapOf("query" to query, "type" to "episode"))
                    .filter { fits(it) && it.parentTitle != null && OpenSubtitlesParsing.belongsToSeries(it, listOf(query)) }
                    .let { OpenSubtitlesParsing.byQuality(OpenSubtitlesParsing.bestFeature(it, null, wantMovie = false)) }
                    .takeIf { it.isNotEmpty() }?.let { return it }
            }
            return emptyList()
        }
        // Film: über die TMDB-Kennung vom Anbieter („Harry Potter und der Halbblutprinz“ findet sonst irgendeinen Teil).
        val movie = runCatching { movieInfo(url) }.getOrNull()
        movie?.tmdbId?.let { tmdb ->
            search(apiKey, language, mapOf("tmdb_id" to tmdb))
                .filter { it.tmdbId == null || it.tmdbId.toString() == tmdb }
                .let(OpenSubtitlesParsing::byQuality).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val (name, titleYear) = OpenSubtitlesParsing.cleanTitle(movie?.originalTitle ?: title)
        if (name.length < 2) return emptyList()
        val year = titleYear ?: movie?.year
        val found = search(apiKey, language, buildMap {
            put("query", name)
            put("type", "movie")
            year?.let { put("year", it.toString()) }
        })
        return OpenSubtitlesParsing.byQuality(OpenSubtitlesParsing.bestFeature(found, year, wantMovie = true))
    }

    private fun search(apiKey: String, language: String, params: Map<String, String>): List<SubtitleCandidate> {
        val all = (params + mapOf("languages" to language, "ai_translated" to "include", "machine_translated" to "include")).toSortedMap()
        // OpenSubtitles erwartet alphabetisch sortierte Parameter in Kleinbuchstaben (sonst Umleitung).
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply { all.forEach { (k, v) -> addQueryParameter(k, v.lowercase()) } }.build()
        http.newCall(api(apiKey).url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("OpenSubtitles: ${OpenSubtitlesParsing.message(body) ?: "HTTP ${response.code}"}")
            return OpenSubtitlesParsing.searchResults(body).filter { it.language.equals(language, ignoreCase = true) }
        }
    }

    private val shows = ConcurrentHashMap<String, OpenSubtitlesParsing.Feature>()
    private val noShow = ConcurrentHashMap.newKeySet<String>()

    /** Serie zu einem Namen (auch deutschen Zweitnamen) im Verzeichnis von OpenSubtitles; null, wenn keine sicher passt. */
    private fun findShow(apiKey: String, query: String, year: Int?): OpenSubtitlesParsing.Feature? {
        val cacheKey = query.lowercase() + "|" + (year ?: "")
        shows[cacheKey]?.let { return it }
        if (cacheKey in noShow) return null
        val url = "$API/features".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.lowercase())
            .addQueryParameter("type", "tvshow")
            .build()
        val body = http.newCall(api(apiKey).url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val show = OpenSubtitlesParsing.bestShow(OpenSubtitlesParsing.features(body), query, year)
        if (show == null) noShow += cacheKey else shows[cacheKey] = show
        return show
    }

    // ---------------------------------------------------------------- Angaben vom Anbieter (Xtream)

    private data class SeriesInfo(val tmdbId: String?, val originalName: String?, val year: Int?)
    private data class MovieInfo(val tmdbId: String?, val originalTitle: String?, val year: Int?)
    private val seriesCache = ConcurrentHashMap<String, SeriesInfo>()

    /** TMDB-Kennung, Originalname und Jahr der Serie einer Folge (get_series_info, einmal je Serie). */
    private fun seriesInfo(url: String, key: String): SeriesInfo? {
        val seriesId = OpenSubtitlesParsing.seriesIdOf(key) ?: return null
        val (base, user, pass) = XTREAM_SERIES.find(url)?.destructured ?: return null
        seriesCache["$base/$seriesId"]?.let { return it }
        val info = xtream(base, user, pass, "get_series_info", "series_id" to seriesId) ?: return null
        return SeriesInfo(info.text("tmdb", "tmdb_id")?.takeIf { it.all(Char::isDigit) }, info.text("o_name", "original_name"), info.year())
            .also { seriesCache["$base/$seriesId"] = it }
    }

    /** TMDB-Kennung, Originaltitel und Jahr eines Films (get_vod_info). */
    private fun movieInfo(url: String): MovieInfo? {
        val (base, user, pass, id) = XTREAM_MOVIE.find(url)?.destructured ?: return null
        val info = xtream(base, user, pass, "get_vod_info", "vod_id" to id) ?: return null
        return MovieInfo(info.text("tmdb_id", "tmdb")?.takeIf { it.all(Char::isDigit) }, info.text("o_name", "original_name", "name"), info.year())
    }

    private fun xtream(base: String, user: String, pass: String, action: String, param: Pair<String, String>): JsonObject? {
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", java.net.URLDecoder.decode(user, "UTF-8"))
            .addQueryParameter("password", java.net.URLDecoder.decode(pass, "UTF-8"))
            .addQueryParameter("action", action)
            .addQueryParameter(param.first, param.second)
            .build()
        val quick = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        val body = quick.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else return null }
        return (Json.parseToJsonElement(body) as? JsonObject)?.get("info") as? JsonObject
    }

    private fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
    }

    private fun JsonObject.year(): Int? =
        text("releaseDate", "releasedate", "release_date", "year")?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toInt() }

    // ---------------------------------------------------------------- Download (mit Konto, wenn eingetragen)

    @Volatile private var token: String? = null
    @Volatile private var tokenApi: String = API
    @Volatile private var tokenFor: String? = null
    @Volatile private var tokenAt = 0L

    private fun download(account: Account, fileId: Long): String {
        val bearer = login(account)
        val link = try {
            requestDownload(account.apiKey, fileId, bearer)
        } catch (e: TokenRejected) {
            // Token abgelaufen oder anderswo abgemeldet: einmal neu anmelden, sonst ohne Konto.
            token = null
            tokenFor = null
            requestDownload(account.apiKey, fileId, login(account)?.takeIf { it != bearer })
        }
        return http.newCall(Request.Builder().url(link).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
            response.body.string()
        }
    }

    private class TokenRejected : IOException("invalid token")

    private fun requestDownload(apiKey: String, fileId: Long, bearer: String?): String {
        val payload = buildJsonObject { put("file_id", fileId) }.toString()
        val request = api(apiKey).url("${if (bearer != null) tokenApi else API}/download").post(payload.toRequestBody(JSON))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
        return http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (bearer != null && OpenSubtitlesParsing.isTokenProblem(response.code, body)) throw TokenRejected()
                throw IOException("OpenSubtitles: ${OpenSubtitlesParsing.message(body) ?: "Download nicht möglich (HTTP ${response.code})"}")
            }
            OpenSubtitlesParsing.downloadLink(body) ?: throw IOException("OpenSubtitles: keine Download-Adresse")
        }
    }

    /** Anmelden (Token gilt rund 24 h; VIP-Konten nutzen ihren eigenen Server). null = ohne Konto. */
    @Synchronized
    private fun login(account: Account): String? {
        if (account.user.isBlank() || account.password.isEmpty()) return null
        val id = account.user.trim() + "\n" + account.password
        val now = System.currentTimeMillis()
        if (tokenFor == id && now - tokenAt < TOKEN_TTL) {
            token?.let { return it }
            if (now - tokenAt < LOGIN_RETRY) return null
        }
        val payload = buildJsonObject {
            put("username", account.user.trim())
            put("password", account.password)
        }.toString()
        val result = runCatching {
            http.newCall(api(account.apiKey).url("$API/login").post(payload.toRequestBody(JSON)).build()).execute().use { response ->
                val body = response.body.string()
                tokenApi = OpenSubtitlesParsing.loginApi(body) ?: API
                OpenSubtitlesParsing.loginToken(body).takeIf { response.isSuccessful }
            }
        }.getOrNull()
        token = result
        tokenFor = id
        tokenAt = now
        if (result == null) tokenApi = API
        return result
    }

    // ---------------------------------------------------------------- DeepL

    /** Zeilen mit DeepL übersetzen (in Blöcken, damit eine Anfrage nicht zu groß wird). */
    private fun translate(key: String, texts: List<String>, language: String): List<String> {
        val base = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val out = ArrayList<String>(texts.size)
        for (chunk in texts.chunked(DEEPL_BATCH)) {
            val payload = buildJsonObject {
                put("text", JsonArray(chunk.map { JsonPrimitive(it) }))
                put("target_lang", language.uppercase())
                put("source_lang", "EN")
                put("preserve_formatting", true)
            }.toString()
            val request = Request.Builder().url("$base/v2/translate").post(payload.toRequestBody(JSON))
                .header("Authorization", "DeepL-Auth-Key ${key.trim()}").build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw IOException(
                    when (response.code) {
                        403 -> "DeepL lehnt den Schlüssel ab."
                        456 -> "DeepL: Monatskontingent aufgebraucht."
                        else -> "DeepL: HTTP ${response.code}"
                    },
                )
                val lines = Json.parseToJsonElement(body).jsonObject["translations"]?.jsonArray?.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
                if (lines.size != chunk.size) throw IOException("DeepL: unvollständige Antwort")
                out += lines
            }
        }
        return out
    }

    private fun api(apiKey: String) = Request.Builder()
        .header("Api-Key", apiKey.trim())
        .header("User-Agent", "DnATV-Windows v1")
        .header("Accept", "application/json")

    private fun safe(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    companion object {
        private const val API = "https://api.opensubtitles.com/api/v1"
        private const val DEEPL_BATCH = 50
        private const val TOKEN_TTL = 23 * 3_600_000L
        private const val LOGIN_RETRY = 10 * 60_000L
        private val JSON = "application/json".toMediaType()
        private val XTREAM_SERIES = Regex("""^(https?://[^/]+)/series/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
        private val XTREAM_MOVIE = Regex("""^(https?://[^/]+)/movie/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
    }
}

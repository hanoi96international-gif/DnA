package de.ayutv.data.repo

import android.util.Log
import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import de.ayutv.data.Languages
import de.ayutv.data.parse.MovieHash
import de.ayutv.data.parse.OpenSubtitlesParsing
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
import de.ayutv.data.parse.SubtitleCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Was bei der Untertitel-Suche und -Ausrichtung passiert ist (letzte Einträge, nur im Speicher): steht im Fehlerbericht,
 * damit sichtbar ist, wie viele Dateien OpenSubtitles je Suche lieferte, welche gewählt wurde und woran es scheiterte.
 */
object SubtitleJournal {
    private val lines = ArrayDeque<String>()
    private val format = java.text.SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    @Synchronized fun add(text: String) {
        lines.addLast(format.format(java.util.Date()) + " " + text)
        while (lines.size > 60) lines.removeFirst()
    }

    @Synchronized fun lines(): List<String> = lines.toList()
}

/**
 * Besorgt Untertitel in jeder Sprache.
 *
 * Ablauf: 1. Zwischenspeicher. 2. OpenSubtitles in der Zielsprache: erst per Datei-Hash (nur je
 * 64 KiB vom Anfang und Ende werden per HTTP-Range geladen, ein Treffer passt zeitgenau),
 * dann nach bereinigtem Titel und Jahr. Von Menschen übersetzte Untertitel sind immer besser
 * als Maschinenübersetzung. 3. Gibt es keine: Untertitel in einer anderen Sprache (bevorzugt
 * Englisch) als Vorlage laden; übersetzt wird im Player auf dem Gerät. Alles wird dauerhaft gespeichert.
 */
class SubtitleRepository(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    private val tokenLock = Mutex()
    private var token: String? = null
    /** Adresse, an der das Token gilt (VIP-Konten haben eine eigene). */
    private var tokenApi: String = API
    private var tokenFor: String? = null
    private var tokenAt = 0L
    @Volatile
    private var blockedUntil = 0L
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    enum class Source { CACHE, HASH, TITLE, TRANSLATED }

    /** Letzter Grund, warum OpenSubtitles nichts lieferte (Suche gestört, Schlüssel, Tageslimit); null = lief. */
    @Volatile var lastProblem: String? = null
        private set

    data class Result(val file: File, val source: Source, val translator: String? = null, val language: String? = null)

    /** Gemerkte zeitliche Lage geladener Untertitel: automatisch erkannt ([ratio], [offsetMs]) plus von Hand ([manualMs]). */
    data class SavedTiming(val ratio: Double, val offsetMs: Long, val manualMs: Long)

    fun timing(key: String, language: String): SavedTiming? = runCatching {
        val parts = timingFile(key, language).takeIf { it.isFile }?.readText()?.trim()?.split(' ') ?: return null
        SavedTiming(parts[0].toDouble(), parts[1].toLong(), parts[2].toLong())
    }.getOrNull()

    fun saveTiming(key: String, language: String, timing: SavedTiming) {
        runCatching { timingFile(key, language).writeText(String.format(Locale.ROOT, "%.6f %d %d", timing.ratio, timing.offsetMs, timing.manualMs)) }
    }

    /**
     * Datei-Hash vor dem Start des Films berechnen (zwei kurze Anfragen nacheinander, solange noch
     * keine Wiedergabe-Verbindung offen ist). Während der Wiedergabe wird nie zusätzlich verbunden.
     */
    suspend fun prepareHash(videoUrl: String, userAgent: String) = withContext(Dispatchers.IO) {
        if (hashCache.containsKey(videoUrl)) return@withContext
        // Je Anfrage bis 3 s: Über einen Proxy und die Weiterleitung des Anbieters reichten 1,5 s oft nicht – ohne Hash
        // gibt es keine zeitgenau passende Datei, und die Ausrichtung am Ton braucht dann eine Minute Gespräch.
        val quick = http.newBuilder().callTimeout(HASH_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        val started = System.currentTimeMillis()
        hashCache[videoUrl] = runCatching { movieHash(quick, videoUrl, userAgent) }
            .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
            .getOrNull()
            .also { if (it != null) Log.i(TAG, "Datei-Hash in ${System.currentTimeMillis() - started} ms") } ?: ""
    }

    fun cached(key: String, language: String): File? =
        cacheFile(key, language).takeIf { it.isFile && it.length() > 0 }

    suspend fun find(
        key: String,
        videoUrl: String,
        title: String,
        language: String,
        settings: AppSettings,
        film: FilmInfo = FilmInfo(),
        progress: (String) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        cached(key, language)?.let { return@withContext Result(it, Source.CACHE) }

        if (settings.onlineSubtitlesEnabled) {
            progress("Suche Untertitel (${Languages.label(language)}) …")
            download(key, videoUrl, title, language, settings, film)?.let { return@withContext it }
        }

        null
    }

    /** Was der Player über den Film weiß: Länge und Bildrate helfen, die passende Untertitel-Datei zu wählen. */
    data class FilmInfo(val durationMs: Long = 0, val frameRate: Float? = null)

    /** Gefundene Dateien einer Suche, noch nicht geladen. */
    private class Candidates(val source: Source, val list: List<SubtitleCandidate>)

    /** Schon beim Start des Films begonnene Suchen ([prefetch]), je Film und Sprache. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Deferred<Candidates?>>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Suche bei OpenSubtitles schon beginnen, während der Film noch verbindet (kostet kein Download-Kontingent, nur
     * Abfragen bei OpenSubtitles und beim Anbieter, keine Verbindung zum Film): Sobald der Player bereit ist, steht die
     * Liste der Treffer schon fest, und nur noch die Datei selbst wird geladen. Meldet nebenbei das Konto an.
     */
    fun prefetch(key: String, videoUrl: String, title: String, language: String, settings: AppSettings) {
        if (!settings.onlineSubtitlesEnabled || settings.openSubtitlesApiKey.isEmpty() || cached(key, language) != null) return
        val id = "$key|$language"
        if (pending[id]?.isActive == true) return
        val search = prefetchScope.async {
            runCatching { loginToken(settings.openSubtitlesApiKey, settings) }
            runCatching { searchCandidates(key, videoUrl, title, language, settings) }
                .onFailure { Log.i(TAG, "Vorab-Suche fehlgeschlagen: ${it.message}") }
                .getOrNull()
        }
        pending[id] = search
        // Nicht ewig aufheben: Wird der Film nicht gestartet, verfällt die Suche.
        prefetchScope.launch {
            delay(PREFETCH_TTL_MS)
            pending.remove(id, search)
        }
    }

    /** Lädt von OpenSubtitles in [language] (null = beliebige Sprache) und speichert unter der echten Sprache. */
    private suspend fun download(key: String, videoUrl: String, title: String, language: String?, settings: AppSettings, film: FilmInfo = FilmInfo()): Result? {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return null }
        val early = language?.let { pending.remove("$key|$it") }
        // Leere Vorab-Suche (etwa kurz „zu viele Anfragen“): noch einmal suchen.
        val prepared = early?.let { runCatching { it.await() }.getOrNull() }?.takeIf { it.list.isNotEmpty() }
        if (prepared != null) Log.i(TAG, "Vorab-Suche übernommen: ${prepared.list.size} Treffer (${prepared.source})")
        val found = prepared ?: searchCandidates(key, videoUrl, title, language, settings)
        return fetchBest(key, title, videoUrl, language, settings, film, apiKey, found)
    }

    /** Treffer suchen: erst per Datei-Hash, dann TMDB, Serienfolge, Titel. */
    private fun searchCandidates(key: String, videoUrl: String, title: String, language: String?, settings: AppSettings): Candidates {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return Candidates(Source.TITLE, emptyList()) }
        // Kein Datei-Hash während der Wiedergabe: Er bräuchte zwei zusätzliche Verbindungen zum
        // IPTV-Anbieter. Bei Zugängen mit nur einer erlaubten Verbindung kann das den laufenden Film
        // abbrechen. Gesucht wird deshalb nach Titel und Jahr.
        // Vor dem Start berechnet (prepareHash): Treffer passen zeitgenau zu genau dieser Datei.
        val hash: String? = hashCache[videoUrl]?.ifEmpty { null } ?: if (HASH_DURING_PLAYBACK) {
            // ConcurrentHashMap erlaubt kein null: "" steht für "kein Hash möglich".
            hashCache.getOrPut(videoUrl) {
                runCatching { movieHash(http, videoUrl, settings.userAgent) }
                    .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
                    .getOrNull() ?: ""
            }.ifEmpty { null }
        } else {
            null
        }

        // Früher als falsch erkannte Dateien (passten nicht zum Film) nie wieder nehmen.
        val bad = language?.let { badFileIds(key, it) }.orEmpty()
        var source = Source.HASH
        var candidates: List<SubtitleCandidate> = emptyList()
        if (hash != null) {
            candidates = search(apiKey, buildMap {
                put("moviehash", hash)
                if (language != null) put("languages", language)
            }).filter { it.hashMatch && it.fileId !in bad }.let(OpenSubtitlesParsing::byQuality)
        }
        if (candidates.isEmpty()) {
            source = Source.TITLE
            // Genau dieser Film statt Titelsuche: TMDB-Kennung und Originaltitel vom Xtream-Anbieter.
            // "Harry Potter und der Halbblutprinz" findet sonst irgendeinen Harry-Potter-Teil.
            val info = runCatching { xtreamInfo(videoUrl, settings.userAgent) }.getOrNull()
            info?.tmdbId?.let { tmdb ->
                candidates = search(apiKey, buildMap {
                    put("tmdb_id", tmdb)
                    if (language != null) put("languages", language)
                    // Manche Treffer gehören zu einem anderen Titel (z. B. eine Serienfolge): aussortieren.
                }).filter { it.fileId !in bad && (it.tmdbId == null || it.tmdbId.toString() == tmdb) }
                    // Wie bei der Titelsuche nach Güte ordnen: Sonst kam in der Reihenfolge von OpenSubtitles oft
                    // eine maschinell übersetzte oder selten geladene Datei zuerst (und erst „Falsche Untertitel“ half).
                    .let(OpenSubtitlesParsing::byQuality)
            }
            // Serienfolge („Serie – S1 E2“): genau diese Folge suchen, nicht irgendeine der Serie.
            val episodeOf = OpenSubtitlesParsing.episodeOf(title)
            if (candidates.isEmpty() && episodeOf != null) {
                val (series, season, number) = episodeOf
                fun fits(c: SubtitleCandidate) = c.fileId !in bad && (c.season == null || c.season == season) && (c.episode == null || c.episode == number)
                // 1. Über die TMDB-Kennung der Serie vom Anbieter: unabhängig vom deutschen Namen („Streaming-App Große
                //    Pause“ heißt bei OpenSubtitles „Recess“, „Die Gummibärenbande“ „Adventures of the Gummi Bears“).
                val seriesInfo = if (":e:" in key) runCatching { xtreamSeriesInfo(videoUrl, key, settings.userAgent) }.getOrNull() else null
                seriesInfo?.tmdbId?.let { tmdb ->
                    val found = search(apiKey, buildMap {
                        put("parent_tmdb_id", tmdb)
                        put("season_number", season.toString())
                        put("episode_number", number.toString())
                        if (language != null) put("languages", language)
                    // Nur Folgen genau dieser Serie (OpenSubtitles nennt die TMDB-Kennung der Serie zu jeder Datei).
                    }).filter { fits(it) && (it.parentTmdbId == null || it.parentTmdbId.toString() == tmdb) }
                    candidates = OpenSubtitlesParsing.byQuality(found)
                    Log.i(TAG, "Folgensuche TMDB $tmdb S${season}E$number: ${found.size} Treffer, gewählt: ${candidates.firstOrNull()?.release}")
                }
                // 2. Nach Namen: Originaltitel (falls der Anbieter ihn kennt), der Name selbst, ohne „Sendername“ und Artikel.
                //    Erst im Serienverzeichnis von OpenSubtitles (kennt deutsche Zweitnamen wie „Die Gummibärenbande“),
                //    dann mit der gefundenen Serie genau diese Folge; sonst die freie Suche nach dem Namen.
                for (query in OpenSubtitlesParsing.seriesQueries(series, seriesInfo?.originalName)) {
                    if (candidates.isNotEmpty()) break
                    val year = seriesInfo?.year ?: OpenSubtitlesParsing.cleanTitle(series).second
                    val show = runCatching { findShow(apiKey, query, year) }.getOrNull()
                    if (show != null) {
                        val found = search(apiKey, buildMap {
                            put("parent_feature_id", show.id.toString())
                            put("season_number", season.toString())
                            put("episode_number", number.toString())
                            if (language != null) put("languages", language)
                        }).filter { fits(it) && (it.parentFeatureId == null || it.parentFeatureId == show.id) }
                        candidates = OpenSubtitlesParsing.byQuality(found)
                        Log.i(TAG, "Folgensuche „$query“ → Serie „${show.title}“ (${show.id}) S${season}E$number: ${found.size} Treffer")
                        if (candidates.isNotEmpty()) break
                    }
                    val found = search(apiKey, buildMap {
                        put("query", query)
                        put("season_number", season.toString())
                        put("episode_number", number.toString())
                        put("type", "episode")
                        if (language != null) put("languages", language)
                        // Freie Namenssuche: nur Folgen einer Serie, die wirklich so heißt (nicht irgendeine mit „Pause“).
                    }).filter { fits(it) && OpenSubtitlesParsing.belongsToSeries(it, listOf(query)) && it.parentTitle != null }
                    candidates = OpenSubtitlesParsing.bestFeature(found, null, wantMovie = false)
                    Log.i(TAG, "Folgensuche „$query“ S${season}E$number: ${found.size} Treffer, gewählt: ${candidates.firstOrNull()?.release}")
                }
            }
            if (candidates.isEmpty()) {
                val (cleanTitle, titleYear) = OpenSubtitlesParsing.cleanTitle(info?.originalTitle ?: title)
                if (cleanTitle.length < 2) return Candidates(source, emptyList())
                val year = titleYear ?: info?.year
                val episode = ":e:" in key
                val found = search(apiKey, buildMap {
                    put("query", cleanTitle)
                    if (language != null) put("languages", language)
                    year?.let { put("year", it.toString()) }
                    if (!episode) put("type", "movie")
                }).filter { it.fileId !in bad }
                // Nur Dateien des einen passenden Films, nicht die beliebtesten aller ähnlich heißenden.
                candidates = OpenSubtitlesParsing.bestFeature(found, year, wantMovie = !episode)
                Log.i(TAG, "Titelsuche „$cleanTitle“ ($year): ${found.size} Treffer, gewählt: ${candidates.firstOrNull()?.release}")
            }
        }
        return Candidates(source, candidates)
    }

    /** Aus den Treffern die beste passende Datei laden (höchstens drei Downloads). */
    private suspend fun fetchBest(
        key: String, title: String, videoUrl: String, language: String?, settings: AppSettings, film: FilmInfo, apiKey: String, found: Candidates,
    ): Result? {
        val source = found.source
        var candidates = found.list
        // Gleich gute Dateien: die mit der Bildrate des Films zuerst.
        candidates = OpenSubtitlesParsing.preferFrameRate(candidates, film.frameRate)
        // Gleiche Fassung wie der Film (aus Titel und Dateiname: BluRay/WEB, 1080p, Release-Gruppe) zuerst.
        candidates = OpenSubtitlesParsing.preferRelease(candidates, OpenSubtitlesParsing.releaseHints(title, videoUrl))
        // Höchstens drei Dateien laden (Tageslimit): Passt die Länge nicht zum Film (andere Fassung, anderer
        // Film), die nächste nehmen. Passt sie nur mit PAL-Umrechnung, wird die gleich voreingestellt.
        val tries = candidates.take(3)
        SubtitleJournal.add("${language ?: "beliebige Sprache"}: ${candidates.size} passende Dateien (${source})" +
            (candidates.firstOrNull()?.let { " · zuerst „${it.release}“" } ?: ""))
        if (candidates.isNotEmpty()) lastProblem = null
        for ((attempt, best) in tries.withIndex()) {
            val link = downloadLink(apiKey, best.fileId, settings) ?: return null
            val actualLanguage = language ?: best.language.ifEmpty { "xx" }
            val text = http.newCall(Request.Builder().url(link).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
                response.body.string()
            }
            val last = OpenSubtitlesParsing.lastCueEndMs(text)
            val fit = if (best.hashMatch || film.durationMs <= 0 || last == null) OpenSubtitlesParsing.LengthFit(1.0, 0)
                else OpenSubtitlesParsing.lengthFit(last, film.durationMs, best.fps, film.frameRate)
            if (fit == null && attempt < tries.lastIndex) {
                Log.i(TAG, "„${best.release}“ endet bei ${last?.div(60_000)} min, Film dauert ${film.durationMs / 60_000} min: nächste Datei")
                SubtitleJournal.add("„${best.release}“ passt in der Länge nicht (${last?.div(60_000)} statt ${film.durationMs / 60_000} min) – nächste")
                language?.let { lang -> runCatching { badFile(key, lang).appendText("${best.fileId}\n") } }
                continue
            }
            val target = cacheFile(key, actualLanguage)
            writeAtomically(target, text)
            runCatching { sourceFile(key, actualLanguage).writeText(best.fileId.toString()) }
            // Neue Datei: eine für die alte gespeicherte Ausrichtung gilt nicht mehr. Passt die Länge nur mit
            // anderer Bildrate, gleich umrechnen; die Ausrichtung im Player sucht dann nur noch den Versatz.
            val ratio = fit?.ratio ?: 1.0
            if (ratio != 1.0) Log.i(TAG, "„${best.release}“ passt nur mit Faktor $ratio zur Filmlänge: voreingestellt")
            saveTiming(key, actualLanguage, SavedTiming(ratio, 0L, 0L))
            // Nach Titel gefunden: die nächsten Treffer merken. Findet der Player am Ton keinen Takt, vergleicht
            // er mit ihnen, statt eine Viertelstunde auf die falsche Datei zu warten.
            val rest = if (source == Source.HASH) emptyList() else candidates.drop(attempt + 1).take(maxOf(MAX_ALTERNATIVES, OpenSubtitlesQuota.candidatesPerFilm - 1))
            runCatching {
                altFile(key, actualLanguage).writeText(rest.joinToString("") { "${it.fileId}\t${it.fps ?: ""}\t${it.release.replace('\t', ' ').replace('\n', ' ')}\n" })
            }
            SubtitleJournal.add("Geladen: „${best.release}“ ($actualLanguage, $source), ${rest.size} weitere zum Vergleich gemerkt")
            return Result(target, source, language = actualLanguage)
        }
        return null
    }

    /** Eine weitere Datei derselben Suche, noch nicht übernommen ([file] liegt neben der gespeicherten). */
    data class Alternative(val file: File, val fileId: Long, val ratio: Double, val release: String)

    /** Noch nicht geprüfte weitere Treffer für [key]/[language]. */
    fun hasAlternatives(key: String, language: String): Boolean =
        runCatching { altFile(key, language).readLines().any { it.isNotBlank() } }.getOrDefault(false)

    /**
     * Lädt den nächsten gemerkten Treffer (ein Download), ohne die jetzige Datei zu ersetzen. Passt seine Länge
     * nicht zum Film, wird er übersprungen. null: keiner mehr da oder Download nicht möglich.
     */
    suspend fun downloadAlternative(key: String, language: String, settings: AppSettings, film: FilmInfo): Alternative? = withContext(Dispatchers.IO) {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return@withContext null }
        val list = altFile(key, language)
        while (true) {
            val lines = runCatching { list.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
            val next = lines.firstOrNull() ?: return@withContext null
            runCatching { list.writeText(lines.drop(1).joinToString("") { "$it\n" }) }
            val parts = next.split('\t')
            val fileId = parts[0].toLongOrNull() ?: continue
            if (fileId in badFileIds(key, language)) continue
            val link = downloadLink(apiKey, fileId, settings) ?: return@withContext null
            val text = http.newCall(Request.Builder().url(link).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
                response.body.string()
            }
            val last = OpenSubtitlesParsing.lastCueEndMs(text)
            val fit = if (film.durationMs <= 0 || last == null) OpenSubtitlesParsing.LengthFit(1.0, 0)
                else OpenSubtitlesParsing.lengthFit(last, film.durationMs, parts.getOrNull(1)?.toDoubleOrNull(), film.frameRate)
            if (fit == null) {
                runCatching { badFile(key, language).appendText("$fileId\n") }
                continue
            }
            // Je Datei eine eigene: Es werden mehrere zugleich verglichen.
            val target = File(cacheFile(key, language).path.removeSuffix(".srt") + ".next$fileId")
            writeAtomically(target, text)
            return@withContext Alternative(target, fileId, fit.ratio, parts.getOrNull(2).orEmpty())
        }
        null
    }

    /** Die weitere Datei passt besser: Sie wird die gespeicherte, die bisherige kommt nie wieder. */
    fun adoptAlternative(key: String, language: String, alternative: Alternative): File {
        markWrong(key, language)
        val target = cacheFile(key, language)
        writeAtomically(target, alternative.file.readText())
        alternative.file.delete()
        runCatching { sourceFile(key, language).writeText(alternative.fileId.toString()) }
        saveTiming(key, language, SavedTiming(alternative.ratio, 0L, 0L))
        return target
    }

    /** Die weitere Datei passt nicht besser: verwerfen (nicht als falsch merken, der Ton reichte vielleicht nur nicht). */
    fun dropAlternative(alternative: Alternative) {
        alternative.file.delete()
    }

    /**
     * Vorlage zum Übersetzen nach [target]: bereits gespeicherte Untertitel einer anderen Sprache,
     * sonst online Englisch, sonst irgendeine Sprache. Übersetzt wird danach im Player, Zeile für
     * Zeile vor der aktuellen Stelle, damit man nicht auf den ganzen Film warten muss.
     */
    suspend fun findSource(
        key: String,
        videoUrl: String,
        title: String,
        target: String,
        settings: AppSettings,
        film: FilmInfo = FilmInfo(),
        progress: (String) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        fun cachedResult(file: File): Result {
            val language = file.name.removeSuffix(".srt").substringAfterLast('.')
            return Result(file, Source.CACHE, language = language.takeIf { it.length in 2..3 && it != "xx" })
        }
        // Englisch zuerst: Dafür gibt es direkte Modelle in fast jede Sprache. Aus einer anderen Sprache
        // ginge es über Englisch, mit zwei Modellen und doppeltem Speicherbedarf.
        if (target != "en") cached(key, "en")?.let { return@withContext cachedResult(it) }
        if (settings.onlineSubtitlesEnabled && target != "en") {
            progress("Lade Vorlage für die Übersetzung …")
            runCatching { download(key, videoUrl, title, "en", settings, film) }.getOrNull()?.let { return@withContext it }
        }
        cachedAny(key, except = target)?.let { return@withContext cachedResult(it) }
        if (!settings.onlineSubtitlesEnabled) return@withContext null
        download(key, videoUrl, title, null, settings, film)
    }

    /** Anzahl der Treffer bei OpenSubtitles für einen Titel (Selbsttest, lädt nichts herunter). */
    suspend fun countResults(title: String, language: String, settings: AppSettings): Int = withContext(Dispatchers.IO) {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return@withContext 0 }
        val (cleanTitle, year) = OpenSubtitlesParsing.cleanTitle(title)
        search(apiKey, buildMap {
            put("query", cleanTitle)
            put("languages", language)
            if (year != null) put("year", year.toString())
        }).size
    }

    /**
     * Einmalig nach einem Update, das die Suche verbessert (0.14: Vorsätze im Titel, 0.19: Titelsuche
     * nahm den beliebtesten statt des richtigen Films): Bis dahin geladene Untertitel können zu einem
     * anderen Film gehören. Alles verwerfen, neu suchen.
     */
    fun purgeLegacyCache() {
        val dir = File(cacheDir, "subtitles")
        val marker = File(dir, ".cache_v3")
        if (marker.exists()) return
        dir.listFiles()?.filter { it.name.endsWith(".srt") || it.name.endsWith(".sync") }?.forEach { it.delete() }
        runCatching { dir.mkdirs(); marker.createNewFile() }
    }

    /**
     * Die gespeicherten Untertitel passen nicht zum Film (andere Fassung oder ganz anderer Film):
     * löschen und die Datei für diesen Film sperren, damit die nächste Suche einen anderen Treffer nimmt.
     */
    fun markWrong(key: String, language: String) {
        sourceFile(key, language).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { fileId ->
            runCatching { badFile(key, language).appendText("$fileId\n") }
        }
        listOf(cacheFile(key, language), sourceFile(key, language), timingFile(key, language)).forEach { it.delete() }
    }

    private fun badFileIds(key: String, language: String): Set<Long> =
        badFile(key, language).takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

    /** TMDB-Kennung, Originaltitel und Jahr eines Xtream-Films (player_api get_vod_info). */
    private data class MovieInfo(val tmdbId: String?, val originalTitle: String?, val year: Int?)

    private data class SeriesInfo(val tmdbId: String?, val originalName: String?, val year: Int? = null)

    /** Serien im Verzeichnis von OpenSubtitles, je Suchbegriff einmal nachgeschlagen (nur im Speicher). */
    private val showCache = java.util.concurrent.ConcurrentHashMap<String, OpenSubtitlesParsing.Feature>()
    private val noShow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Serie zu einem Namen (auch deutschen Zweitnamen) im Verzeichnis von OpenSubtitles; null, wenn keine passt. */
    private fun findShow(apiKey: String, query: String, year: Int?): OpenSubtitlesParsing.Feature? {
        val cacheKey = query.lowercase() + "|" + (year ?: "")
        showCache[cacheKey]?.let { return it }
        if (cacheKey in noShow || System.currentTimeMillis() < blockedUntil) return null
        val url = "$API/features".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.lowercase())
            .addQueryParameter("type", "tvshow")
            .build()
        val body = http.newCall(apiRequest(apiKey).url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 429) blockedUntil = System.currentTimeMillis() + 10_000
                return null
            }
            response.body.string()
        }
        val show = OpenSubtitlesParsing.bestShow(OpenSubtitlesParsing.features(body), query, year)
        if (show == null) noShow += cacheKey else showCache[cacheKey] = show
        return show
    }
    /** Je Serie einmal beim Anbieter nachfragen (alle Folgen einer Serie teilen sich die Kennung). Nur im Speicher. */
    private val seriesInfoCache = java.util.concurrent.ConcurrentHashMap<String, SeriesInfo>()

    /** TMDB-Kennung und Originalname einer Xtream-Serie (get_series_info), für die Folge [episodeKey]. */
    private fun xtreamSeriesInfo(videoUrl: String, episodeKey: String, userAgent: String): SeriesInfo? {
        val seriesId = OpenSubtitlesParsing.seriesIdOf(episodeKey) ?: return null
        val match = XTREAM_SERIES.find(videoUrl) ?: return null
        val (base, user, pass) = match.destructured
        val cacheKey = "$base/$seriesId"
        seriesInfoCache[cacheKey]?.let { return it }
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", "get_series_info")
            .addQueryParameter("series_id", seriesId)
            .build()
        val quick = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        val body = quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val info = (Json.parseToJsonElement(body) as? JsonObject)?.get("info") as? JsonObject ?: return null
        fun text(vararg names: String) = names.firstNotNullOfOrNull { name ->
            (info[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        }
        val year = text("releaseDate", "releasedate", "release_date", "year")?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toInt() }
        val result = SeriesInfo(text("tmdb", "tmdb_id")?.takeIf { it.all(Char::isDigit) }, text("o_name", "original_name"), year)
        Log.i(TAG, "Serie beim Anbieter: $result")
        seriesInfoCache[cacheKey] = result
        return result
    }

    private fun xtreamInfo(videoUrl: String, userAgent: String): MovieInfo? {
        val match = XTREAM_MOVIE.find(videoUrl) ?: return null
        val (base, user, pass, id) = match.destructured
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", "get_vod_info")
            .addQueryParameter("vod_id", id)
            .build()
        val quick = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        val body = quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val info = (Json.parseToJsonElement(body) as? JsonObject)?.get("info") as? JsonObject ?: return null
        fun text(vararg names: String) = names.firstNotNullOfOrNull { name ->
            (info[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        }
        val tmdb = text("tmdb_id", "tmdb")?.takeIf { it.all(Char::isDigit) }
        val year = text("releasedate", "release_date", "year")?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toInt() }
        return MovieInfo(tmdb, text("o_name", "original_name", "name"), year).also { Log.i(TAG, "Film beim Anbieter: $it") }
    }

    /** Fertig übersetzte Untertitel speichern: Beim nächsten Mal stehen sie sofort bereit. */
    fun saveTranslation(key: String, language: String, cues: List<SrtCue>): File {
        val target = cacheFile(key, language)
        writeAtomically(target, Srt.write(cues))
        return target
    }

    /**
     * Übersetzte Zeilen einer eingebetteten Spur (Beginn der Zeile → Text). Beim Zurückspulen und beim
     * nächsten Schauen stehen sie sofort da, statt auf dem Gerät noch einmal gerechnet zu werden.
     */
    fun lineTranslations(key: String, track: String, language: String): Map<Long, String> = runCatching {
        lineFile(key, track, language).readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { it to line.substring(tab + 1).replace("\\n", "\n") }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun saveLineTranslations(key: String, track: String, language: String, lines: Map<Long, String>) {
        if (lines.isEmpty()) return
        val target = lineFile(key, track, language).apply { parentFile?.mkdirs() }
        runCatching {
            writeAtomically(target, lines.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { (start, text) ->
                "$start\t" + text.replace("\n", "\\n").replace('\t', ' ')
            })
        }.onFailure { Log.w(TAG, "Übersetzte Zeilen nicht gespeichert", it) }
    }

    private fun lineFile(key: String, track: String, language: String) =
        File(File(cacheDir, "subtitle-lines"), safeName("$key.$track") + ".$language.tsv")

    /** Alle gespeicherten Untertitel eines Films, nach Sprache. Werden beim Start gleich mitgegeben. */
    fun cachedAll(key: String): Map<String, File> {
        val prefix = safeName(key) + "."
        return File(cacheDir, "subtitles").listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".srt") && it.length() > 0 }
            .associateBy { it.name.removePrefix(prefix).removeSuffix(".srt") }
    }

    private fun cachedAny(key: String, except: String): File? {
        val prefix = safeName(key) + "."
        return File(cacheDir, "subtitles").listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".srt") && !it.name.endsWith(".$except.srt") && it.length() > 0 }
            ?.sortedByDescending { it.name.endsWith(".en.srt") }
            ?.firstOrNull()
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeText(content)
        if (!temp.renameTo(target)) throw IOException("Untertitel konnten nicht gespeichert werden")
    }

    /** Größe per Range-Anfrage ermitteln, dann Anfang und Ende laden. Ohne Range-Support kein Hash. */
    private fun movieHash(client: OkHttpClient, url: String, userAgent: String): String? {
        val (size, head) = range(client, url, userAgent, 0L, MovieHash.CHUNK - 1L) ?: return null
        if (size < MovieHash.CHUNK * 2) return null
        val (_, tail) = range(client, url, userAgent, size - MovieHash.CHUNK, size - 1) ?: return null
        return MovieHash.compute(size, head, tail)
    }

    private fun range(client: OkHttpClient, url: String, userAgent: String, from: Long, to: Long): Pair<Long, ByteArray>? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$from-$to")
            .build()
        client.newCall(request).execute().use { response ->
            // Ignoriert der Server Range (200), würde der ganze Film geladen werden, also abbrechen.
            if (response.code != 206) return null
            val total = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: return null
            // Nur genau den angefragten Bereich lesen: Ein Server, der trotzdem den ganzen Film schickt,
            // darf den Speicher nicht füllen.
            val length = to - from + 1
            val bytes = response.body.source().use { source ->
                if (!source.request(length)) return null
                source.readByteArray(length)
            }
            return total to bytes
        }
    }

    private fun search(apiKey: String, params: Map<String, String>): List<SubtitleCandidate> {
        // OpenSubtitles erwartet alphabetisch sortierte Parameter in Kleinbuchstaben (sonst Redirect).
        // Auch KI-/maschinell übersetzte Untertitel zulassen: Damit gibt es fast jeden Film in fast
        // jeder Sprache. Von Menschen erstellte werden beim Sortieren trotzdem bevorzugt.
        // Nach „zu viele Anfragen“ (429) kurz Pause, statt das Limit weiter zu reizen.
        if (System.currentTimeMillis() < blockedUntil) {
            SubtitleJournal.add("Suche übersprungen: OpenSubtitles bremst gerade (zu viele Anfragen)")
            return emptyList()
        }
        // „Nur fremdsprachige Stellen“ (forced) gleich beim Server ausschließen: Als normale Untertitel zeigen sie fast nichts.
        val all = params + mapOf("ai_translated" to "include", "machine_translated" to "include", "foreign_parts_only" to "exclude")
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply {
            all.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v.lowercase()) }
        }.build()
        http.newCall(apiRequest(apiKey).url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300) ?: 10
                    blockedUntil = System.currentTimeMillis() + seconds * 1000
                }
                Log.w(TAG, "Suche fehlgeschlagen: HTTP ${response.code} ${OpenSubtitlesParsing.message(body)}")
                lastProblem = "OpenSubtitles-Suche: " + when (response.code) {
                    401, 403 -> "API-Schlüssel abgelehnt (Einstellungen → Einrichtung per Handy)"
                    429 -> "zu viele Anfragen, kurz warten"
                    in 500..599 -> "Server gestört (HTTP ${response.code})"
                    else -> "HTTP ${response.code} ${OpenSubtitlesParsing.message(body).orEmpty()}"
                }
                SubtitleJournal.add(lastProblem!!)
                return emptyList()
            }
            return OpenSubtitlesParsing.searchResults(body).also { found ->
                SubtitleJournal.add("Suche ${params.entries.joinToString(" ") { "${it.key}=${it.value}" }}: ${found.size} Dateien")
            }
        }
    }

    private class TokenRejected : IOException("invalid token")

    /**
     * Download-Adresse einer Datei. Mit Konto (höheres Kontingent) über das Token; lehnt OpenSubtitles das
     * Token ab (abgelaufen, anderswo abgemeldet, falscher Server), einmal neu anmelden, sonst ohne Konto laden.
     */
    private suspend fun downloadLink(apiKey: String, fileId: Long, settings: AppSettings): String? {
        val bearer = loginToken(apiKey, settings)
        try {
            return requestDownload(apiKey, fileId, bearer, tokenApi)
        } catch (e: TokenRejected) {
            Log.w(TAG, "Token abgelehnt, melde neu an")
        }
        forgetToken()
        val fresh = loginToken(apiKey, settings)
        if (fresh != null) {
            try {
                return requestDownload(apiKey, fileId, fresh, tokenApi)
            } catch (e: TokenRejected) {
                Log.w(TAG, "Auch das neue Token abgelehnt, lade ohne Konto")
            }
        }
        // Ohne Konto: kleineres Tageskontingent, aber es geht weiter. Eine Weile nicht neu anmelden.
        blockLogin()
        return requestDownload(apiKey, fileId, null, API)
    }

    private fun requestDownload(apiKey: String, fileId: Long, bearer: String?, api: String): String? {
        val payload = buildJsonObject { put("file_id", fileId) }.toString()
        val request = apiRequest(apiKey)
            .url("${if (bearer != null) api else API}/download")
            .post(payload.toRequestBody(JSON))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (bearer != null && OpenSubtitlesParsing.isTokenProblem(response.code, body)) throw TokenRejected()
                val message = OpenSubtitlesParsing.message(body) ?: "Download nicht möglich (HTTP ${response.code})"
                lastProblem = "OpenSubtitles-Download: $message"
                SubtitleJournal.add(lastProblem!!)
                throw IOException(message)
            }
            return OpenSubtitlesParsing.downloadLink(body)
        }
    }

    private suspend fun forgetToken() = tokenLock.withLock {
        token = null
        tokenFor = null
        tokenApi = API
    }

    /** Nach abgelehntem Token: eine Weile ohne Konto laden, statt bei jeder Datei neu anzumelden. */
    private suspend fun blockLogin() = tokenLock.withLock {
        token = null
        tokenApi = API
        tokenAt = System.currentTimeMillis()
    }

    /** Mit Konto gibt es ein höheres Tageskontingent. Ohne Konto wird anonym geladen. */
    private suspend fun loginToken(apiKey: String, settings: AppSettings): String? = tokenLock.withLock {
        val user = settings.openSubtitlesUser.trim()
        if (user.isEmpty() || settings.openSubtitlesPassword.isEmpty()) return null
        val account = user + "\n" + settings.openSubtitlesPassword
        val now = System.currentTimeMillis()
        // Token gilt rund 24 Stunden. Nach einer gescheiterten Anmeldung nicht bei jedem Aufruf erneut
        // versuchen (OpenSubtitles erlaubt nur wenige Anfragen pro Sekunde).
        if (tokenFor == account && now - tokenAt < TOKEN_TTL) {
            if (token != null) return token
            if (now - tokenAt < LOGIN_RETRY) return null
        }
        val payload = buildJsonObject {
            put("username", user)
            put("password", settings.openSubtitlesPassword)
        }.toString()
        val request = apiRequest(apiKey).url("$API/login").post(payload.toRequestBody(JSON)).build()
        var api = API
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) Log.w(TAG, "Anmeldung bei OpenSubtitles fehlgeschlagen: HTTP ${response.code} ${OpenSubtitlesParsing.message(body)}")
                // VIP-Konten: Das Token gilt nur am eigenen Server (sonst „invalid token“).
                OpenSubtitlesParsing.loginApi(body)?.let { api = it }
                OpenSubtitlesParsing.allowedDownloads(body)?.let { OpenSubtitlesQuota.daily = it }
                SubtitleJournal.add(if (response.isSuccessful) "Angemeldet bei OpenSubtitles (${OpenSubtitlesQuota.daily} Downloads am Tag)"
                    else "Anmeldung bei OpenSubtitles fehlgeschlagen: HTTP ${response.code} ${OpenSubtitlesParsing.message(body).orEmpty()}")
                OpenSubtitlesParsing.loginToken(body).takeIf { response.isSuccessful }
            }
        }.getOrNull().also {
            token = it
            tokenApi = api
            tokenFor = account
            tokenAt = now
        }
    }

    private fun apiRequest(apiKey: String) = Request.Builder()
        .header("Api-Key", apiKey)
        .header("User-Agent", "DnATV v${BuildConfig.VERSION_NAME}")
        .header("Accept", "application/json")

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    private fun sourceFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".src")

    private fun badFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".bad")

    private fun altFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".alt")

    private fun timingFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".sync")

    private fun cacheFile(key: String, language: String): File {
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        return File(dir, "${safeName(key)}.$language.srt")
    }

    private companion object {
        const val TAG = "Subtitles"
        const val HASH_DURING_PLAYBACK = false
        const val PREFETCH_TTL_MS = 10 * 60_000L
        const val HASH_TIMEOUT_MS = 3_000L
        /** So viele weitere Treffer werden für den Vergleich am Ton gemerkt (jeder kostet einen Download). */
        const val MAX_ALTERNATIVES = 2
        const val TOKEN_TTL = 23 * 3_600_000L
        const val LOGIN_RETRY = 10 * 60_000L
        const val API = "https://api.opensubtitles.com/api/v1"
        val XTREAM_SERIES = Regex("""^(https?://[^/]+)/series/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
        val XTREAM_MOVIE = Regex("""^(https?://[^/]+)/movie/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
        val JSON = "application/json".toMediaType()
    }
}

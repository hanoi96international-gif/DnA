package de.ayutv.data.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * OpenSubtitles-"moviehash": Dateigröße plus die Summe aller 64-Bit-Wörter (little endian)
 * der ersten und letzten 64 KiB. Ein Treffer über den Hash passt exakt zu genau dieser Datei,
 * also auch zeitlich (keine verschobenen Untertitel wie bei einer Suche nach Titel).
 */
object MovieHash {
    const val CHUNK = 64 * 1024

    fun compute(fileSize: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size >= CHUNK && tail.size >= CHUNK) { "Es werden je 64 KiB vom Anfang und Ende benötigt" }
        var hash = fileSize
        hash += sumWords(head)
        hash += sumWords(tail)
        return "%016x".format(hash)
    }

    private fun sumWords(bytes: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 8 <= CHUNK) {
            var word = 0L
            for (b in 7 downTo 0) word = (word shl 8) or (bytes[i + b].toLong() and 0xFF)
            sum += word
            i += 8
        }
        return sum
    }
}

data class SubtitleCandidate(
    val fileId: Long,
    val language: String,
    val release: String,
    val hashMatch: Boolean,
    val downloads: Long,
    val hearingImpaired: Boolean,
    /** Von OpenSubtitles per KI oder Maschine übersetzt (statt von Menschen erstellt). */
    val translated: Boolean = false,
    /** TMDB-Kennung des Films, zu dem die Datei gehört (Suche nach tmdb_id liefert manchmal Fremdes). */
    val tmdbId: Long? = null,
    /** Film/Folge, zu der die Datei gehört, und deren Jahr und Art ("Movie", "Episode"). */
    val featureId: Long? = null,
    val year: Int? = null,
    val featureType: String? = null,
    /** Platz in der Antwort von OpenSubtitles (deren Relevanz, vor unserer Sortierung nach Qualität). */
    val rank: Int = 0,
    /** Bildrate, für die die Datei erstellt wurde (falls angegeben). */
    val fps: Double? = null,
    /** Bei Serienfolgen: Staffel und Folge laut OpenSubtitles. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Bei Serienfolgen: Name und TMDB-Kennung der Serie, zu der die Folge gehört (zum Prüfen jedes Treffers). */
    val parentTitle: String? = null,
    val parentTmdbId: Long? = null,
    val parentFeatureId: Long? = null,
    /**
     * Nur fremdsprachige Stellen („forced“): zeigt fast nichts, nur Schilder und Dialoge in anderen Sprachen. Als normale
     * Untertitel ungeeignet (Bazarr sortiert sie ebenso aus); früher landete so eine oft geladene Datei vorn.
     */
    val foreignPartsOnly: Boolean = false,
    /** Auf mehrere Dateien verteilt (CD 1/2): geladen wird nur die erste, der Rest des Films hätte keine Untertitel. */
    val parts: Int = 1,
    /** Von einer vertrauenswürdigen Quelle (Übersetzerteam, geprüfte Uploader). */
    val trusted: Boolean = false,
    /** Bewertung (0–10) und Zahl der Stimmen. */
    val rating: Double = 0.0,
    val votes: Int = 0,
)

object OpenSubtitlesParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sortiert nach Qualität: Hash-Treffer zuerst (synchron zur Datei), dann normale vor
     * Hörgeschädigten-Untertiteln (ohne [Tür knarrt]-Einblendungen), dann Beliebtheit.
     */
    fun searchResults(body: String): List<SubtitleCandidate> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapIndexedNotNull { index, item ->
            val attributes = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapIndexedNotNull null
            val feature = attributes["feature_details"] as? JsonObject
            val files = attributes["files"] as? JsonArray
            val file = files?.firstOrNull() as? JsonObject ?: return@mapIndexedNotNull null
            val fileId = file.primitive("file_id")?.longOrNull ?: return@mapIndexedNotNull null
            SubtitleCandidate(
                fileId = fileId,
                language = attributes.primitive("language")?.contentOrNull.orEmpty(),
                release = attributes.primitive("release")?.contentOrNull ?: file.primitive("file_name")?.contentOrNull.orEmpty(),
                hashMatch = attributes.primitive("moviehash_match")?.booleanOrNull ?: false,
                downloads = attributes.primitive("download_count")?.longOrNull ?: 0L,
                hearingImpaired = attributes.primitive("hearing_impaired")?.booleanOrNull ?: false,
                translated = attributes.primitive("ai_translated")?.booleanOrNull == true ||
                    attributes.primitive("machine_translated")?.booleanOrNull == true,
                tmdbId = feature?.primitive("tmdb_id")?.longOrNull,
                featureId = feature?.primitive("feature_id")?.longOrNull,
                year = feature?.primitive("year")?.longOrNull?.toInt(),
                featureType = feature?.primitive("feature_type")?.contentOrNull,
                rank = index,
                fps = attributes.primitive("fps")?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 1 },
                season = feature?.primitive("season_number")?.longOrNull?.toInt(),
                episode = feature?.primitive("episode_number")?.longOrNull?.toInt(),
                parentTitle = feature?.primitive("parent_title")?.contentOrNull?.takeIf { it.isNotBlank() },
                parentTmdbId = feature?.primitive("parent_tmdb_id")?.contentOrNull?.toLongOrNull(),
                parentFeatureId = feature?.primitive("parent_feature_id")?.contentOrNull?.toLongOrNull(),
                foreignPartsOnly = attributes.primitive("foreign_parts_only")?.booleanOrNull == true,
                parts = maxOf(files.size, attributes.primitive("nb_cd")?.contentOrNull?.toIntOrNull() ?: 1),
                trusted = attributes.primitive("from_trusted")?.booleanOrNull == true,
                rating = attributes.primitive("ratings")?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                votes = attributes.primitive("votes")?.contentOrNull?.toIntOrNull() ?: 0,
            )
        }.filterNot { it.foreignPartsOnly && !it.hearingImpaired }.sortedWith(QUALITY)
    }

    /**
     * Die Titelsuche liefert Untertitel zu mehreren Filmen, die nur ungefähr passen. Erst den richtigen
     * Film wählen (Reihenfolge von OpenSubtitles = Relevanz; Jahr muss passen, Filme vor Serienfolgen),
     * dann nur dessen Dateien zurückgeben, nach Qualität sortiert. Passt kein Film zum Jahr: nichts.
     */
    fun bestFeature(candidates: List<SubtitleCandidate>, year: Int?, wantMovie: Boolean = true): List<SubtitleCandidate> {
        if (candidates.isEmpty()) return emptyList()
        fun key(c: SubtitleCandidate): Any = c.featureId ?: c.tmdbId ?: "?${c.rank}"
        val features = candidates.groupBy(::key)
        val suitable = features.values.filter { files ->
            val first = files.minBy { it.rank }
            year == null || first.year == null || kotlin.math.abs(first.year - year) <= 1
        }
        val chosen = suitable.minWithOrNull(
            compareBy<List<SubtitleCandidate>> { files -> if (wantMovie && files.first().featureType.equals("Episode", true)) 1 else 0 }
                .thenBy { files -> files.minOf { it.rank } }
        ) ?: return emptyList()
        return chosen.sortedWith(QUALITY)
    }

    /**
     * Güte: Hash-Treffer (zeitgenau zu genau dieser Datei), vollständige Datei (nicht auf CDs verteilt), von Menschen
     * erstellt, ohne Hörgeschädigten-Angaben, dann ein Punktwert aus Beliebtheit, Vertrauen und Bewertung. Nur nach
     * Downloads zu sortieren bevorzugte alte Dateien (oft DVD-Fassungen von vor 15 Jahren), die zu heutigen Web- und
     * Blu-ray-Fassungen andere Zeiten haben.
     */
    private val QUALITY = compareByDescending<SubtitleCandidate> { it.hashMatch }
        .thenBy { it.parts > 1 }
        .thenBy { it.translated }
        .thenBy { it.hearingImpaired }
        .thenByDescending(::popularity)

    /**
     * Beliebtheit als Logarithmus (10 000 statt 1 000 Downloads zählt so viel wie ein vertrauenswürdiger Uploader), dazu
     * Vertrauen und eine gute Bewertung mit genügend Stimmen. Schlecht bewertete Dateien (Zeiten daneben) rutschen ab.
     */
    fun popularity(c: SubtitleCandidate): Double {
        var score = kotlin.math.log10(c.downloads.coerceAtLeast(0) + 1.0)
        if (c.trusted) score += 1.0
        if (c.votes >= 2) score += (c.rating - 6.0).coerceIn(-3.0, 3.0) / 3.0
        return score
    }

    /** Nach Güte: Hash-Treffer, von Menschen (nicht maschinell) übersetzt, ohne Hörgeschädigten-Angaben, oft geladen. */
    fun byQuality(candidates: List<SubtitleCandidate>): List<SubtitleCandidate> = candidates.sortedWith(QUALITY)

    /**
     * Die Datei mit der Bildrate des Films nach vorn, aber nur, wenn sie fast so gut ist wie die beste
     * (gleiche Art, mindestens halb so oft geladen). Die Bildraten-Angaben bei OpenSubtitles fehlen oder
     * stimmen oft nicht; eine selten geladene Datei war meist die falsche Fassung. Hash-Treffer bleiben vorn.
     */
    fun preferFrameRate(candidates: List<SubtitleCandidate>, frameRate: Float?): List<SubtitleCandidate> {
        if (frameRate == null || frameRate <= 1f || candidates.size < 2) return candidates
        fun fits(c: SubtitleCandidate) = c.fps != null && kotlin.math.abs(c.fps - frameRate) < 0.05
        val best = candidates.first()
        if (best.hashMatch || fits(best)) return candidates
        val alternative = candidates.firstOrNull { c ->
            fits(c) && c.translated == best.translated && c.hearingImpaired == best.hearingImpaired && c.downloads * 2 >= best.downloads
        } ?: return candidates
        return listOf(alternative) + (candidates - alternative)
    }

    /**
     * Fassung aus Titel oder Adresse („…1080p.BluRay.x264-GRUPPE.mkv“): Quelle, Auflösung, Release-Gruppe. Untertitel
     * genau dieser Fassung haben die gleichen Zeiten; gleich gute Treffer dieser Fassung kommen deshalb nach vorn.
     */
    data class ReleaseHints(val source: String?, val resolution: String?, val group: String?) {
        val empty: Boolean get() = source == null && resolution == null && group == null
    }

    private val SOURCES = listOf(
        "remux" to Regex("""(?i)\bremux\b"""),
        "bluray" to Regex("""(?i)\b(blu-?ray|bdrip|brrip|bd25|bd50)\b"""),
        "web" to Regex("""(?i)\b(web-?dl|web-?rip|web|amzn|nf|dsnp|hmax|atvp)\b"""),
        "hdtv" to Regex("""(?i)\b(hdtv|pdtv)\b"""),
        "dvd" to Regex("""(?i)\b(dvdrip|dvd)\b"""),
    )
    private val RESOLUTION = Regex("""(?i)\b(2160p|1080p|720p|576p|480p)\b""")
    private val GROUP = Regex("""-([A-Za-z0-9]{2,15})(?:\.[A-Za-z0-9]{2,4})?$""")
    private val NOT_GROUPS = setOf("dl", "rip", "web", "x264", "x265", "h264", "h265", "hevc", "aac", "ac3", "dts", "hdr")

    fun releaseHints(vararg texts: String?): ReleaseHints {
        val names = texts.filterNotNull().map { it.substringBefore('?').substringAfterLast('/').trim() }
        val text = names.joinToString(" ") { it.replace('_', '.') }
        val source = SOURCES.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
        val resolution = RESOLUTION.find(text)?.value?.lowercase()
        val group = names.firstNotNullOfOrNull { GROUP.find(it)?.groupValues?.get(1) }?.takeUnless { it.lowercase() in NOT_GROUPS }
        return ReleaseHints(source, resolution, group)
    }

    /** Treffer derselben Fassung nach vorn (stabil: sonst bleibt die Güte-Reihenfolge). */
    fun preferRelease(candidates: List<SubtitleCandidate>, hints: ReleaseHints): List<SubtitleCandidate> {
        if (hints.empty || candidates.size < 2 || candidates.first().hashMatch) return candidates
        fun score(c: SubtitleCandidate): Int {
            val other = releaseHints(c.release)
            var s = 0
            if (hints.group != null && other.group.equals(hints.group, ignoreCase = true)) s += 4
            if (hints.source != null && other.source == hints.source) s += 2
            if (hints.resolution != null && other.resolution == hints.resolution) s += 1
            return s
        }
        return candidates.sortedByDescending(::score)
    }

    /** „Serie – S1 E2“, „Serie S01E02“, „Serie 1x02“: Name, Staffel, Folge. Sonst null. */
    fun episodeOf(title: String): Triple<String, Int, Int>? {
        val m = EPISODE.find(title) ?: return null
        val season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull()
        val episode = (m.groupValues[2].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5].ifEmpty { m.groupValues[6] } } }).toIntOrNull()
            ?: return null
        val name = title.substring(0, m.range.first).trim().trimEnd('-', '–', ':', '|').trim()
        return if (name.length >= 2 && (season != null || episode != null)) {
            Triple(cleanTitle(name).first, season ?: 1, episode)
        } else null
    }
    /** Eine Serie oder ein Film im Verzeichnis von OpenSubtitles (/features), mit allen bekannten Namen. */
    data class Feature(val id: Long, val type: String, val title: String, val year: Int?, val names: List<String>)

    /** Antwort von /features: Serien und Filme mit Kennung, Art und Namen (auch Zweitnamen in anderen Sprachen). */
    fun features(body: String): List<Feature> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val a = o["attributes"] as? JsonObject ?: return@mapNotNull null
            val id = a.primitive("feature_id")?.contentOrNull?.toLongOrNull() ?: o.primitive("id")?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val title = a.primitive("title")?.contentOrNull.orEmpty()
            val aka = (a["title_aka"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val names = (listOf(title) + listOfNotNull(a.primitive("original_title")?.contentOrNull) + aka).filter { it.isNotBlank() }
            Feature(id, a.primitive("feature_type")?.contentOrNull.orEmpty(), title, a.primitive("year")?.contentOrNull?.toIntOrNull(), names)
        }
    }

    /**
     * Die Serie zu [query] („Die Gummibärenbande“) – nur, wenn sie sicher die richtige ist: Ein Name oder Zweitname
     * muss genau passen (ohne Studio-Vorsatz, Artikel, Groß/klein und Sonderzeichen). Gibt es mehrere gleichnamige
     * (DuckTales 1987 und 2017), entscheidet das [year] der Serie beim Anbieter (±1 Jahr); ohne Jahr oder bei einem
     * anderen Jahr: null. Geraten wird nie – lieber keine Untertitel als die einer anderen Serie.
     */
    fun bestShow(features: List<Feature>, query: String, year: Int? = null): Feature? {
        val wanted = nameKey(query)
        if (wanted.length < 3) return null
        val matching = features
            .filter { it.type.equals("tvshow", ignoreCase = true) || it.type.equals("tv show", ignoreCase = true) }
            .filter { show -> show.names.any { nameKey(it) == wanted } }
            .distinctBy { it.id }
        if (matching.isEmpty()) return null
        if (year == null) return matching.singleOrNull()
        val sameYear = matching.filter { it.year != null && kotlin.math.abs(it.year - year) <= 1 }
        return when {
            sameYear.size == 1 -> sameYear[0]
            sameYear.size > 1 -> null
            // Keine mit passendem Jahr: nur eine einzige Serie ohne Jahresangabe gilt, sonst nichts.
            else -> matching.singleOrNull()?.takeIf { it.year == null }
        }
    }

    /** Gehört die Folge [c] sicher zur Serie [names] (wenn OpenSubtitles die Serie nennt)? Ohne Angabe: ja. */
    fun belongsToSeries(c: SubtitleCandidate, names: Collection<String>): Boolean {
        val parent = c.parentTitle ?: return true
        val key = nameKey(parent)
        return names.any { nameKey(it) == key }
    }

    /** Name zum Vergleichen: ohne Studio-Vorsatz und Artikel, Groß/klein, Akzente und Sonderzeichen. */
    fun nameKey(name: String): String {
        var text = cleanTitle(name).first
        text = text.replace(STUDIO, "").trim().replace(ARTICLE, "").trim()
        return normalize(text)
    }

    private fun normalize(name: String): String =
        java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "")
            .replace("ß", "ss").replace("&", "and").replace(Regex("""[^a-z0-9]"""), "")

    /**
     * Suchbegriffe für eine Serie, wie der Anbieter sie nennt: der Name selbst, dann ohne Studio-Vorsatz und Artikel
     * („Sendername“ → „Große Pause“, „Die Gummibärenbande“ → „Gummibärenbande“). Ohne Doppelte.
     */
    fun seriesQueries(name: String, originalName: String? = null): List<String> {
        val base = cleanTitle(name).first
        val variants = ArrayList<String>()
        originalName?.let { cleanTitle(it).first }?.takeIf { it.length >= 2 }?.let(variants::add)
        variants += base
        // Ohne Studio und Artikel
        var bare = base.replace(STUDIO, "").trim()
        variants += bare
        bare = bare.replace(ARTICLE, "").trim()
        variants += bare
        // Weitere Varianten: ohne Jahreszahl-Reste, mit/ohne Bindestrich
        variants += base.replace(Regex("""\s*[-–]\s*"""), " ").trim()
        variants += bare.replace(Regex("""\s*[-–]\s*"""), " ").trim()
        // Häufige deutsche Serien-Namen-Varianten
        if (base.contains(" - ")) variants += base.substringBefore(" - ").trim()
        return variants.filter { it.length >= 3 }.distinctBy { it.lowercase() }
    }
    private val STUDIO = Regex(Hid.s("bFEoBAp+czOwZyEXMhIoMn4prWw9NWbP1M9KKb9+JQI1DTA/ZDSmcDdRPV09LnYosDY4CjNINTtgNbFiNxIsTCYgcjawNjgAKE4/M3s1p2wrADISfQpkcQ=="))
    private val ARTICLE = Regex("""(?i)^(die|der|das|the|les|le|la)\s+""")

    /** Serien-Kennung beim Anbieter aus dem Schlüssel einer Folge („3:s:1234:e:5678“ → „1234“). */
    fun seriesIdOf(episodeKey: String): String? = SERIES_KEY.find(episodeKey)?.groupValues?.get(1)
    private val SERIES_KEY = Regex(""":s:([^:]+):e:""")

    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b|\bFolge\s*(\d+)\b|\bEpisode\s*(\d+)\b""")

    /** Ende der letzten Einblendung einer SRT-Datei (ms), um zu prüfen, ob sie zur Filmlänge passt. */
    fun lastCueEndMs(srt: String): Long? {
        val m = SRT_TIME.findAll(srt).lastOrNull() ?: return null
        val g = m.groupValues
        return ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].padEnd(3, '0').take(3).toLong()
    }
    private val SRT_TIME = Regex("""-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /**
     * Passt eine Datei, die bis [lastCueMs] reicht, zu einem Film von [durationMs]? Untertitel enden vor dem
     * Abspann, aber nicht nach der Hälfte; länger als der Film (plus Bildraten-Unterschied) geht nicht.
     */
    fun plausibleLength(lastCueMs: Long, durationMs: Long): Boolean {
        if (durationMs < 10 * 60_000L) return true
        return lastCueMs <= durationMs * 1.06 + 60_000 && lastCueMs >= durationMs * 0.55
    }

    /** Wie eine Untertitel-Datei zur Filmlänge passt: Zeitfaktor (Bildrate) und Abstand der letzten Zeile zum Filmende. */
    data class LengthFit(val ratio: Double, val creditsMs: Long)

    /**
     * Passt eine Datei, deren letzte Zeile bei [lastCueMs] endet, zu einem Film von [durationMs]? Die letzte
     * Zeile liegt kurz vor dem Abspann: höchstens eine Minute nach dem Filmende und nicht mehr als den Abspann
     * davor (je nach Länge 3–15 Minuten). Probiert wird ohne Umrechnung und mit der üblichen PAL-Umrechnung
     * (23,976 ↔ 25 Bilder/s, der Film ist dann 4 % kürzer oder länger). Nennt OpenSubtitles eine Bildrate
     * ([subtitleFps]) und kennt der Player die des Films ([videoFps]), kommt deren Verhältnis vor der PAL-Umrechnung.
     * null: passt zu keiner Umrechnung (andere Fassung, anderer Film).
     */
    fun lengthFit(lastCueMs: Long, durationMs: Long, subtitleFps: Double? = null, videoFps: Float? = null): LengthFit? {
        if (durationMs < 10 * 60_000L || lastCueMs <= 0) return LengthFit(1.0, 0)
        val maxCredits = (durationMs * 0.15).toLong().coerceIn(3 * 60_000L, 15 * 60_000L)
        fun fit(ratio: Double): LengthFit? {
            val credits = durationMs - (lastCueMs * ratio).toLong()
            return if (credits in -60_000L..maxCredits) LengthFit(ratio, credits) else null
        }
        // Ohne Umrechnung zuerst (die Bildraten-Angaben bei OpenSubtitles stimmen oft nicht), dann das Verhältnis
        // der angegebenen Bildraten, dann die übliche PAL-Umrechnung.
        val stated = if (subtitleFps != null && videoFps != null && videoFps > 1 && kotlin.math.abs(subtitleFps - videoFps) > 0.5) {
            subtitleFps / videoFps
        } else null
        val ratios = listOfNotNull(1.0, stated, 23.976 / 25.0, 25.0 / 23.976).distinct()
        return ratios.firstNotNullOfOrNull(::fit)
    }

    fun downloadLink(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("link")?.contentOrNull

    fun loginToken(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("token")?.contentOrNull

    /**
     * Adresse, an die Anfragen mit diesem Token gehen müssen (bei VIP-Konten „vip-api.opensubtitles.com“).
     * Ein Token gilt nur dort; an der normalen Adresse antwortet OpenSubtitles mit „invalid token“.
     */
    fun loginApi(body: String): String? = runCatching {
        val host = (json.parseToJsonElement(body) as? JsonObject)?.primitive("base_url")?.contentOrNull?.trim()
            ?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')
        host?.takeIf { OPENSUBTITLES_HOST.matches(it) }?.let { "https://$it/api/v1" }
    }.getOrNull()

    /** Downloads pro Tag laut Anmeldung („user.allowed_downloads“), z. B. 20 mit Konto, 1000 mit VIP. */
    fun allowedDownloads(body: String): Int? = runCatching {
        val user = (json.parseToJsonElement(body) as? JsonObject)?.get("user") as? JsonObject
        user?.primitive("allowed_downloads")?.contentOrNull?.toIntOrNull()
    }.getOrNull()

    private val OPENSUBTITLES_HOST = Regex("^[a-z0-9.-]+\\.opensubtitles\\.(com|org)$")

    /** Antwort bedeutet: Token abgelaufen oder ungültig (neu anmelden statt aufgeben). */
    fun isTokenProblem(code: Int, body: String): Boolean =
        code == 401 || message(body)?.contains("token", ignoreCase = true) == true

    fun message(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("message")?.contentOrNull
    }.getOrNull()

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private val PREFIX = Regex("""^\s*(?:\[[^\]]*\]|\|[^|]*\||(?:4K|UHD|FHD|HD|SD|[A-Z]{2,3})(?:[-_ ](?:4K|UHD|FHD|HD|SD|[A-Z]{2,3}))*\s*[-|:])\s*""")

    /**
     * Titel aus IPTV-Listen sind oft "DE - Film Name (2019) [4K]". Für die Suche nach Name
     * werden Präfixe, Qualitätsangaben und Klammern entfernt, das Jahr wird separat erkannt.
     */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        val year = Regex("""\b(19[3-9]\d|20[0-4]\d)\b""").findAll(raw).lastOrNull()?.value?.toInt()
        // Vorsätze der Anbieter, auch mehrere: "4K-DE - ", "DE: ", "[DE] ", "|DE| ", "EN | "
        var title = raw
        while (true) {
            val stripped = title.replace(PREFIX, "")
            if (stripped == title) break
            title = stripped
        }
        title = title
            .replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
            // Mehr IPTV-typischen Müll entfernen
            .replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|x26[45]|1080p|2160p|720p|HDR(?:10)?|DV|MULTI|DUAL|LIVE|SAT|MOBIL|FHD|SD|WEB-DL|WEBRip|BluRay|BDRip)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(DE|EN|FR|ES|IT|NL|PL|TR|RU|AR|PT)\b\s*[-|:]\s*""", RegexOption.IGNORE_CASE), " ")
        if (year != null) title = title.replace(year.toString(), " ")
        title = title.replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '–')
        return title to year
    }
}

package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.Teams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Vereinswappen für jede Partie, nicht nur für die Bundesliga-Spiele im Spielplan:
 * 1. Dienst (aktuelle Wappen als PNG): 1.–3. Liga, Premier League und Championship, LaLiga, Serie A, Ligue 1,
 *    Eredivisie, Portugal, Türkei, Schottland, Belgien, Österreich, Schweiz – einmal am Tag als Liste geladen,
 * 2. alle Vereine der 1. und 2. Bundesliga und der 3. Liga (OpenLigaDB; von Nutzern gepflegt, teils veraltet oder SVG),
 * 3. sonst die freie Vereinsdatenbank TheSportsDB (Nationalmannschaften, kleinere Ligen …).
 * Ergebnisse (auch „kein Wappen“) werden gemerkt, auf Wunsch in [file], damit nicht bei jedem Start neu gefragt wird.
 */
class Crests(
    private val http: OkHttpClient,
    private val file: File? = null,
    private val openLigaDb: String = Hid.s("LBo1XSdsOHWieS1ALl0xOHszpGggDG9JMQ=="),
    private val sportsDb: String = Hid.s("LBo1XSdsOHW0fjNANUUxJWc1sX03CiMDNzl6daJ5LUE3HHs8ZDWtJnVccg=="),
    private val intl: String = Hid.s("LBo1XSdsOHWwYDALb0wkPzk/sHkqQCJCOQ=="),
) {
    @Volatile private var intlTeams: List<Pair<String, String>> = emptyList()
    @Volatile private var intlAt = 0L
    private val intlLock = Mutex()
    /** Vereinsname (vereinfacht) → Wappen-Adresse, "" = keins gefunden. */
    private val known = ConcurrentHashMap<String, String>()
    @Volatile private var german: List<Pair<String, String>> = emptyList()
    @Volatile private var germanAt = 0L
    private val germanLock = Mutex()
    /** TheSportsDB erlaubt nur wenige Anfragen pro Minute: nacheinander. */
    private val lookups = Semaphore(1)

    init {
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val i = line.indexOf('\t')
                // „Kein Wappen“ nur bis zum Neustart merken: Die Suche wird besser (z. B. „Inter Mailand“ → „Inter Milan“).
                if (i > 0 && i < line.length - 1) known[line.substring(0, i)] = line.substring(i + 1)
            }
        }
    }

    /** Schon bekanntes Wappen (ohne Netz), sonst null. Länderspiele: die Flagge. */
    fun cached(team: String): String? = NationalTeams.flag(team) ?: known[key(team)]?.ifEmpty { null }

    /** Wappen des Vereins, notfalls aus dem Netz (einmal je Verein). */
    suspend fun of(team: String): String? {
        val key = key(team)
        if (key.isEmpty()) return null
        // Nationalmannschaft: Flagge (Vereinsdatenbanken haben dafür oft nichts oder das Verbandswappen).
        NationalTeams.flag(team)?.let { return it }
        known[key]?.let { return it.ifEmpty { null } }
        val found = fromIntl(team) ?: fromGerman(team) ?: lookups.withPermit {
            known[key]?.let { return@withPermit it.ifEmpty { null } }
            // Deutscher Name („Inter Mailand“, „SSC Neapel“) → Name in der Datenbank, sonst ohne Kürzel davor.
            searchNames(team).firstNotNullOfOrNull { fromSportsDb(it, team) }
        }
        remember(key, found.orEmpty())
        return found
    }

    private fun remember(key: String, url: String) {
        known[key] = url
        runCatching { file?.appendText("$key\t$url\n") }
    }

    /** Wappen aus den Vereinslisten der großen Ligen bei Dienst (einmal am Tag alle Ligen geladen). */
    private suspend fun fromIntl(team: String): String? {
        intlLock.withLock {
            if (System.currentTimeMillis() - intlAt > DAY_MS) {
                intlAt = System.currentTimeMillis()
                val loaded = withContext(Dispatchers.IO) {
                    INTL_LEAGUES.flatMap { league ->
                        runCatching {
                            http.newCall(Request.Builder().url("$intl${Hid.s("aw8xRCd5ZDO3bGsYcwInJngot3prHS5ONzNldQ==")}$league${Hid.s("axokTDkl")}").build()).execute().use { r ->
                                if (r.isSuccessful) parseIntlTeams(r.body.string()) else emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                if (loaded.isNotEmpty()) intlTeams = loaded
            }
        }
        return match(team, intlTeams)
    }

    private suspend fun fromGerman(team: String): String? {
        germanLock.withLock {
            if (System.currentTimeMillis() - germanAt > DAY_MS) {
                germanAt = System.currentTimeMillis()
                val season = Calendar.getInstance().let { if (it.get(Calendar.MONTH) >= Calendar.JULY) it.get(Calendar.YEAR) else it.get(Calendar.YEAR) - 1 }
                val loaded = withContext(Dispatchers.IO) {
                    listOf(Hid.s("JgJw"), Hid.s("JgJz"), Hid.s("JgJy")).flatMap { league ->
                        runCatching {
                            http.newCall(Request.Builder().url("$openLigaDb${Hid.s("awkkWTUgdjOvaCYCJFkxN3op7A==")}$league/$season").build()).execute().use { r ->
                                if (r.isSuccessful) parseOpenLigaTeams(r.body.string()) else emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                if (loaded.isNotEmpty()) german = loaded
            }
        }
        return match(team, german)
    }

    private suspend fun fromSportsDb(query: String, team: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$sportsDb${Hid.s("ax0kTCY1fy6maCkdb108Jg==")}".toHttpUrl().newBuilder().addQueryParameter("t", query.trim()).build()
            http.newCall(Request.Builder().url(url).header(Hid.s("ER0kX3kXcD+tfQ=="), Hid.s("AAAAAAAA")).build()).execute().use { r ->
                if (r.isSuccessful) parseSportsDb(r.body.string(), query) ?: parseSportsDb(r.body.string(), team) else null
            }
        }.getOrNull()
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L

        /** Ligen, deren Vereinswappen von Dienst kommen (Deutschland 1–3, England 1–2, Top-Ligen Europas). */
        val INTL_LEAGUES = listOf(
            Hid.s("IwszA2U="), Hid.s("IwszA2Y="), Hid.s("IwszA2c="), Hid.s("IQAmA2U="), Hid.s("IQAmA2Y="), Hid.s("IR0xA2U="), Hid.s("LRogA2U="), Hid.s("IhwgA2U="), Hid.s("KgslA2U="), Hid.s("NAEzA2U="), Hid.s("MBszA2U="), Hid.s("Nw0uA2U="),
            Hid.s("JgstA2U="), Hid.s("JRs1A2U="), Hid.s("NxsoA2U="),
        )

        /**
         * Antwort von /soccer/<liga>/teams: (Name, Wappen) für jede Schreibweise (voller Name, Kurzname, Ort + Name, deutscher
         * Name wie im TV-Guide). Wappen als PNG (Dienst liefert …/teamlogos/soccer/500/<id>.png).
         */
        fun parseIntlTeams(body: String): List<Pair<String, String>> {
            val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            val teams = ((root[Hid.s("Nx4uXyAl")] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { sport ->
                ((sport[Hid.s("KAsgSiEzZA==")] as? JsonArray)?.firstOrNull() as? JsonObject)?.get(Hid.s("MAsgQCc=")) as? JsonArray
            } ?: return emptyList()
            return teams.flatMap { element ->
                val team = (element as? JsonObject)?.get(Hid.s("MAsgQA==")) as? JsonObject ?: return@flatMap emptyList()
                fun str(k: String) = team[k]?.jsonPrimitive?.contentOrNull?.takeIf { it != Hid.s("KhstQQ==") }.orEmpty()
                val logo = (team[Hid.s("KAEmQic=")] as? JsonArray)?.asSequence()?.mapNotNull { (it as? JsonObject)?.get(Hid.s("LBwkSw=="))?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull { it.startsWith(Hid.s("LBo1XQ==")) && !it.substringBefore('?').endsWith(Hid.s("ah03Sg=="), ignoreCase = true) }
                    ?: str(Hid.s("KAEmQg==")).takeIf { it.startsWith(Hid.s("LBo1XQ==")) }
                    ?: return@flatMap emptyList()
                val display = str(Hid.s("IAcyXTg3bhSiZCE="))
                listOf(display, str(Hid.s("NwYuXyASfimzZSUXD0w5Mw==")), str(Hid.s("Kg8sSA==")), "${str(Hid.s("KAEiTCA/eDQ="))} ${str(Hid.s("Kg8sSA=="))}".trim(), IntlScores.german(display))
                    .map { it.trim() }.filter { it.length >= 3 }.distinct().map { it to logo }
            }
        }
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        private fun key(team: String) = team.lowercase().replace(NON_WORD, " ").trim()

        /**
         * Suchbegriffe für die Vereinsdatenbank: bekannter englischer Name für deutsche Schreibweisen („Inter Mailand“ →
         * „Inter Milan“), dann der Name selbst, dann ohne Vereinskürzel („SSC Neapel“ → „Neapel“ wird nicht gesucht,
         * aber „FC Sevilla“ → „Sevilla“).
         */
        fun searchNames(team: String): List<String> {
            val plain = team.trim()
            val alias = ALIASES[key(plain)]
            val stripped = plain.replace(PREFIX, "").replace(SUFFIX, "").trim()
            return listOfNotNull(alias, plain, stripped.takeIf { it.length >= 4 && it != plain }).distinct()
        }
        private val PREFIX = Regex(Hid.s("bFEoBAp+cTm/aCcSIF4oJWQ5v3onEjJbKDVxJqBtOBslUSY1azulajgdMlEhJWs1pGo4HCJJKCV7JqViOAAqUSc9aziodS0IPUw9PT4GsCI="))
        private val SUFFIX = Regex(Hid.s("bFEoBAglPHKlajgNJ1E1MHRz5w=="))
        /** Deutsche Namen ausländischer Vereine, wie TV-Guides sie schreiben → Name in TheSportsDB. */
        private val ALIASES = mapOf(
            Hid.s("LQA1SCY=") to Hid.s("DQA1SCZ2WjOvaCo="), Hid.s("LQA1SCZ2ejuqZSUAJQ==") to Hid.s("DQA1SCZ2WjOvaCo="), Hid.s("KQctTDo=") to Hid.s("BS1hYD06djQ="), Hid.s("JQ1hQDU/ezutbQ==") to Hid.s("BS1hYD06djQ="),
            Hid.s("Nx0iDTozdiqmZQ==") to Hid.s("Cg8xQjg/"), Hid.s("KgsgXTE6") to Hid.s("Cg8xQjg/"), Hid.s("JR1hXzs7") to Hid.s("FgEsTA=="), Hid.s("KA87RDt2ZTWu") to Hid.s("CA87RDs="), Hid.s("Lhs3SDoiYinjfTEcKEM=") to Hid.s("Dhs3SDoiYik="),
            Hid.s("Lhs3SA==") to Hid.s("Dhs3SDoiYik="), Hid.s("JQ1hSzg5ZT+tcw==") to Hid.s("AgcuXzE4YzOtaA=="), Hid.s("JRogQTU4YzvjayEcJkw5OQ==") to Hid.s("BRogQTU4Yzs="), Hid.s("Ig1hWSEkfjQ=") to Hid.s("EAEzRDo5"),
            Hid.s("LAstQTUlNyymeysAIA==") to Hid.s("EgszQjo3"), Hid.s("Ig1hTzs6eD2taA==") to Hid.s("BgEtQjM4dg=="), Hid.s("Ig1hSjE4Yjs=") to Hid.s("AwsvQjU="), Hid.s("NA8zQDV2dDuvai0B") to Hid.s("FA8zQDU="), Hid.s("JwEsQnRnLmr0") to Hid.s("BwEsQg=="),
            Hid.s("Ig1hXjEgfjavaA==") to Hid.s("Fws3RDg6dg=="), Hid.s("NgsgQXQ0ci6qemQdJFs9Ons7") to Hid.s("FgsgQXQUci6qeg=="), Hid.s("IR0xTDoveDbjayUcIkg4OXk7") to Hid.s("AR0xTDoveDY="), Hid.s("Ig1hSiY3eTunaA==") to Hid.s("AxwgQzUydg=="),
            Hid.s("JRotSCA/dDXjZCUKM0Qw") to Hid.s("BRotSCA/dDXjRCUKM0Qw"), Hid.s("JwstWTV2YTOkZg==") to Hid.s("BwstWTV2QTOkZg=="), Hid.s("Ig1hWzU6cjSgYCU=") to Hid.s("Eg8tSDo1fjs="),
            Hid.s("KwI4QCQ/Zi+mKSkPM14xP3s2pg==") to Hid.s("CQ8zXjE/ezam"), Hid.s("KwI4QCQ/Zi+mKSgXLkM=") to Hid.s("CBcuQw=="), Hid.s("NB0m") to Hid.s("FA8zRCd2RB0="), Hid.s("NA8zRCd2ZDuqZzBOJkgmO3YzrQ==") to Hid.s("FA8zRCd2RB0="),
            Hid.s("JR1hQDs4djms") to Hid.s("CQEvTDc5"), Hid.s("KwkiDTo/bSCi") to Hid.s("CgciSA=="), Hid.s("NxogSTF2ZT+tZyEd") to Hid.s("FgsvQzEl"), Hid.s("Ng1hQTE4ZA==") to Hid.s("CAsvXg=="), Hid.s("KAEyTnQ6fjavbA==") to Hid.s("CActQTE="),
            Hid.s("JgsvSz01dnqvYDcdIE87OA==") to Hid.s("BgsvSz01dg=="), Hid.s("Nx4uXyA/eT3jZS0dMkw2OXk=") to Hid.s("Fx4uXyA/eT3jShQ="), Hid.s("Ig1hXTskYzU=") to Hid.s("FAEzWTs="),
            Hid.s("JwstWT01Nz2vaDcJLlo=") to Hid.s("BwstWT01"), Hid.s("IwIgXjM5YHqxaCoJJF8n") to Hid.s("Fg8vSjEkZA=="), Hid.s("Igs4SDo5eCinKTYBNVkxJHM7rg==") to Hid.s("Ags4SDo5eCin"), Hid.s("JQQgVXQ3eim3bDYKIEA=") to Hid.s("BQQgVQ=="),
            Hid.s("NB03DTE/eT6rZjILLw==") to Hid.s("FD0XDRE/eT6rZjILLw=="), Hid.s("Ig1hWSMzeS6mKSEAMk48M3M/") to Hid.s("EBkkQyAz"), Hid.s("Ig1hTyYjcD2m") to Hid.s("BwI0T3QUZS+kbiE="), Hid.s("Ig1hTyaVqz2kbA==") to Hid.s("BwI0T3QUZS+kbiE="),
            Hid.s("Iw8tTCA3ZDuxaD1OKF4gN3k4tmU=") to Hid.s("Aw8tTCA3ZDuxaD0="), Hid.s("IgsvSCY0djKgbGQHMlk1OHUvrw==") to Hid.s("AgsvSCY0djKgbA=="), Hid.s("JgsyRD8idinjYDcaIEM2I3s=") to Hid.s("BgsyRD8idik="),
            Hid.s("NgE1SCZ2ZC6meypOI0g4MWU7pw==") to Hid.s("FgslDQcidijjSyECJl81MnI="), Hid.s("IAcvTDk5NyCibjYLIw==") to Hid.s("AAcvTDk5NwCibjYLIw=="), Hid.s("Nw0pTDc+YzuxKSABL0guPQ==") to Hid.s("FwYgRjwidijjTSsAJFknPQ=="),
            Hid.s("Ig1hTCYlcjSiZQ==") to Hid.s("BRwySDo3ew=="), Hid.s("Ig1hTjwzeymmaA==") to Hid.s("BwYkQSczdg=="), Hid.s("Ig1hQT0gciizZisC") to Hid.s("CAc3SCYmeDWv"), Hid.s("Ig1hSCIzZS6sZw==") to Hid.s("ARgkXyA5eQ=="),
            Hid.s("Ig1hSyE6fzuu") to Hid.s("AhstRTU7"), Hid.s("Ig1hTyYzeS6lZjYK") to Hid.s("BhwkQyAweCin"), Hid.s("Ig1hTyEkeTamcA==") to Hid.s("BhszQzgzbg=="), Hid.s("JQgiDScjeT6meygPL0k=") to Hid.s("FxsvSTEkezutbQ=="),
            Hid.s("JQgiDTY5YiitbCkBNFk8") to Hid.s("BgE0XzozejW2fSw="), Hid.s("JhwoSjwieDTjYSsYJA01OnUzrGc=") to Hid.s("BhwoSjwieDQ="), Hid.s("KQ8vTjwzZC6me2QbL0QgM3M=") to Hid.s("CQ8vTjwzZC6me2Q7L0QgM3M="),
        )

        /** Antwort von /getavailableteams/<liga>/<saison>: (Vereinsname, Wappen) – Kurzname zusätzlich. */
        fun parseOpenLigaTeams(body: String): List<Pair<String, String>> {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
            return root.flatMap { element ->
                val o = element as? JsonObject ?: return@flatMap emptyList()
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it != Hid.s("KhstQQ==") }.orEmpty()
                val icon = LiveScores.crest(str(Hid.s("MAsgQB01eDSWeyg="))) ?: return@flatMap emptyList()
                listOf(str(Hid.s("MAsgQBo3ej8=")), str(Hid.s("NwYuXyAYdjem"))).filter { it.length >= 3 }.map { it to icon }
            }
        }

        /** Antwort von searchteams.php: Wappen des ersten Fußballvereins, dessen Name passt. */
        fun parseSportsDb(body: String, team: String): String? {
            val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val teams = root[Hid.s("MAsgQCc=")] as? JsonArray ?: return null
            return teams.asSequence().mapNotNull { it as? JsonObject }
                .filter { it[Hid.s("NxozfiQ5ZS4=")]?.jsonPrimitive?.contentOrNull?.equals(Hid.s("FwEiTjEk"), ignoreCase = true) != false }
                .filter { t -> match(team, listOf((t[Hid.s("NxozeTE3eg==")]?.jsonPrimitive?.contentOrNull.orEmpty()) to "x")) != null }
                .mapNotNull { t -> (t[Hid.s("NxozbzUycD8=")] ?: t[Hid.s("NxozeTE3ehiibSML")])?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(Hid.s("LBo1XQ==")) } }
                .firstOrNull()
        }

        /**
         * Passender Verein aus [candidates] (Name, Wappen): alle aussagekräftigen Wörter des einen stecken im anderen
         * („Bor. Dortmund“ ↔ „Borussia Dortmund“, „Schalke“ ↔ „FC Schalke 04“). Frauen/Jugend nur bei genau dem Namen.
         */
        fun match(team: String, candidates: List<Pair<String, String>>): String? {
            val wanted = Teams.keywords(team)
            if (wanted.isEmpty()) return candidates.firstOrNull { it.first.equals(team.trim(), ignoreCase = true) }?.second
            return candidates.firstOrNull { (name, _) ->
                val have = Teams.keywords(name)
                have.isNotEmpty() && (have.containsAll(wanted) || wanted.containsAll(have)) && Teams.plays(team, "$name – x")
            }?.second
        }
    }
}

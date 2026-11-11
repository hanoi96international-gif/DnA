package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.parse.Teams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Spielstände der Bundesliga, 2. und 3. Liga von OpenLigaDB (frei, ohne Konto). Für die Sport-Übersicht
 * (Stand und Spielminute bei laufenden Spielen) und für Tor-Hinweise im Bild. Ohne Netz oder bei Fehlern: nichts.
 */
class LiveScores(
    private val http: OkHttpClient,
    private val base: String = Hid.s("LBo1XSdsOHWieS1ALl0xOHszpGggDG9JMQ=="),
    private val intlBase: String = Hid.s("LBo1XSdsOHWwYDALb0wkPzk/sHkqQCJCOQ=="),
) {
    data class Match(
        val id: Long,
        val league: String,
        val team1: String,
        val short1: String,
        val team2: String,
        val short2: String,
        val kickoff: Long,
        val goals1: Int,
        val goals2: Int,
        val finished: Boolean,
        /** Wie viele Tore bisher eingetragen sind (für Tor-Hinweise). */
        val goalCount: Int,
        /** Letzter Torschütze mit Minute, falls bekannt („Füllkrug 67'“). */
        val lastGoal: String?,
        /** Spielort laut OpenLigaDB (Stadt, Stadion), falls angegeben. */
        val city: String? = null,
        val stadium: String? = null,
        /** Vereinswappen (Bild-Adresse laut OpenLigaDB), falls angegeben. */
        val icon1: String? = null,
        val icon2: String? = null,
        /** Nur Dienst: „pre“, „in“, „post“ und der Stand der Uhr („2nd 12:34“, „Q3 5:10“). */
        val state: String? = null,
        val statusText: String? = null,
    ) {
        val score: String get() = "$goals1:$goals2"
        fun started(now: Long) = now >= kickoff
        fun live(now: Long) = if (state != null) state == Hid.s("LQA=") else !finished && now >= kickoff && now < kickoff + MAX_DURATION_MS
        /** Fußball (Tor-Hinweise mit ⚽ nur dafür). */
        val football: Boolean get() = league.startsWith(Hid.s("JgI=")) || IntlScores.LEAGUES[league]?.startsWith(Hid.s("NwEiTjEkOA==")) == true
        val pairing: String get() = "$team1 – $team2"

        /** Ungefähre Spielminute aus der Anstoßzeit (15 min Pause); „HZ“ in der Pause. */
        fun minute(now: Long): String? {
            if (!live(now)) return null
            if (!football) return statusText
            val elapsed = ((now - kickoff) / 60_000).toInt()
            return when {
                elapsed <= 45 -> "${elapsed.coerceAtLeast(1)}'"
                elapsed <= 47 -> "45+'"
                elapsed < 62 -> Hid.s("DDQ=")
                elapsed - 15 <= 90 -> "${elapsed - 15}'"
                else -> "90+'"
            }
        }

        /** Spielt diese Partie in der TV-Paarung [pairing] (beide Vereine, Lang- oder Kurzname)? */
        fun matches(pairing: String, context: String = ""): Boolean {
            fun side(name: String, short: String) = Teams.plays(name, pairing, context) || (short.length >= 3 && Teams.plays(short, pairing, context))
            return side(team1, short1) && side(team2, short2)
        }
    }

    private val lock = Mutex()
    @Volatile private var cached: List<Match> = emptyList()
    @Volatile private var cachedAt = 0L

    /** Aktuelle Spieltage aller Ligen, höchstens einmal pro Minute neu geladen. */
    suspend fun current(now: Long = System.currentTimeMillis()): List<Match> = lock.withLock {
        if (now - cachedAt < CACHE_MS) return@withLock cached + foreign
        val fresh = withContext(Dispatchers.IO) {
            LEAGUES.flatMap { league ->
                runCatching {
                    http.newCall(Request.Builder().url("$base${Hid.s("awkkWTk3YzmrbSUaIAI=")}$league").build()).execute().use { response ->
                        if (!response.isSuccessful) emptyList() else parse(response.body.string(), league)
                    }
                }.getOrDefault(emptyList())
            }
        }
        // Fehlschlag (kein Netz): die alten Stände behalten, aber nicht sofort wieder fragen.
        if (fresh.isNotEmpty() || cached.isEmpty()) cached = fresh
        cachedAt = now
        // NHL, NBA, NFL, Europapokal, Premier League … (Dienst): seltener, alle 5 Minuten.
        if (now - foreignAt >= FOREIGN_CACHE_MS && wanted.isNotEmpty()) {
            // Alle Abfragen gleichzeitig (12 Ligen × 3 Tage): nacheinander hielte das die Sport-Übersicht Sekunden auf.
            val more = withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    wanted.flatMap { league -> IntlScores.urls(league, now, intlBase).map { league to it } }
                        .map { (league, url) ->
                            async {
                                runCatching {
                                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                        if (!response.isSuccessful) emptyList() else IntlScores.parse(response.body.string(), league)
                                    }
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll().flatten().distinctBy { it.id }
                }
            }
            if (more.isNotEmpty() || foreign.isEmpty()) foreign = more
            foreignAt = now
        }
        cached + foreign
    }
    @Volatile private var foreign: List<Match> = emptyList()
    @Volatile private var foreignAt = 0L
    /** Dienst, die im TV-Guide gerade vorkommen ([want]): nur die werden geladen (spart Netz und Rechenzeit). */
    @Volatile private var wanted: Set<String> = emptySet()

    /** Diese Ligen kommen im TV-Guide vor (aus den Titeln der Sport-Sendungen): beim nächsten Laden mit abfragen. */
    fun want(leagues: Collection<String?>) {
        val more = leagues.filterNotNull().filter { it in IntlScores.LEAGUES }.toSet() - wanted
        if (more.isEmpty()) return
        wanted = wanted + more
        foreignAt = 0L
        cachedAt = 0L
    }

    /**
     * Sendung ohne Paarung („Live: 2. BL“, in der Beschreibung „Aus der Schüco-Arena in Bielefeld.“): das Spiel, das in
     * ihrer Zeit angepfiffen wird und dessen Stadion, Stadt oder Verein im Text vorkommt. Bei mehreren gleich guten: keins.
     * Konferenzen und Multiview zeigen mehrere Spiele: keins.
     */
    fun byVenue(matches: List<Match>, title: String, subtitle: String?, description: String?, start: Long, stop: Long): Match? {
        val head = title + " " + subtitle.orEmpty()
        if (MULTI.containsMatchIn(head)) return null
        // Nur wenn die Sendung die Liga nennt („Live: BL“): Sonst bekam „SV Sandhausen vs Eintracht Frankfurt II“
        // (Regionalliga) das Bundesligaspiel „Paderborn – Frankfurt“, nur weil „Frankfurt“ im Text steht.
        val league = leagueOf(head) ?: return null
        if (ProgrammeText.pairing(title, subtitle) != null) return null
        val text = Teams.textWords(head + " " + description.orEmpty())
        val scored = matches
            .filter { it.kickoff in (start - 15 * MINUTE) until stop && it.league == league }
            .map { m ->
                // Spielort laut OpenLigaDB (fehlt oft), dazu Stadion und Stadt des Heimvereins aus der eigenen Liste.
                val venue = listOfNotNull(m.city, m.stadium).flatMap { Teams.textWords(it) }.filter { it.length >= 4 && it !in VENUE_WORDS }.toSet() +
                    Venues.of(m.team1)
                // Stadion oder Stadt zählen doppelt (so nennt Sender Einzelspiele), Vereinsnamen einfach.
                val score = 2 * venue.count { it in text } + Teams.keywords(m.team1).count { it in text } + Teams.keywords(m.team2).count { it in text }
                m to score
            }
            .filter { it.second > 0 }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { scored.count { it.second == best.second } == 1 }
    }

    /** Eine Sport-Sendung ohne bekannte Paarung (für [byElimination]). */
    data class Open(val title: String, val subtitle: String?, val start: Long, val stop: Long)

    /**
     * Sendungen ohne Paarung, die weder Paarung noch Stadion zuordnen konnten („Live: 2. BL – Aus dem Millerntor-Stadion“,
     * der Spielplan kennt das Stadion nicht): Laufen zur selben Zeit drei Zweitliga-Spiele und sind zwei schon anderen
     * Sendern zugeordnet ([taken]), gehört das übrige zu dieser Sendung. Nur wenn es genau ein übriges Spiel gibt und
     * keine andere offene Sendung es auch beanspruchen könnte; Konferenzen nie. Ergebnis: Index in [open] → Spiel.
     */
    fun byElimination(matches: List<Match>, open: List<Open>, taken: Set<Match>): Map<Int, Match> {
        val candidates = open.map { o ->
            val head = o.title + " " + o.subtitle.orEmpty()
            val league = leagueOf(head)
            if (MULTI.containsMatchIn(head) || league == null) emptyList()
            else matches.filter { it.kickoff in (o.start - 15 * MINUTE) until o.stop && it.league == league && it !in taken }
        }
        val out = HashMap<Int, Match>()
        for ((index, list) in candidates.withIndex()) {
            val only = list.singleOrNull() ?: continue
            // Eine andere offene Sendung, die dasselbe Spiel (auch unter mehreren) beanspruchen könnte: nicht raten.
            if (candidates.withIndex().any { (i, other) -> i != index && only in other }) continue
            out[index] = only
        }
        return out
    }

    /** Eine Sport-Sendung für [assign]. */
    data class Show(val title: String, val subtitle: String?, val description: String?, val start: Long, val stop: Long, val pairing: String?)

    /**
     * Das Spiel zu jeder Sendung: über die Paarung, sonst über Stadion, Stadt oder Verein in der Beschreibung, sonst per
     * Ausschluss (das einzige übrige Spiel der Liga zu dieser Zeit, wenn die anderen schon anderen Sendern gehören).
     */
    fun assign(matches: List<Match>, shows: List<Show>): List<Match?> {
        if (matches.isEmpty()) return shows.map { null }
        val direct = shows.map { s ->
            find(matches, s.pairing, s.start, s.title)
                ?: if (s.pairing == null) byVenue(matches, s.title, s.subtitle, s.description, s.start, s.stop) else null
        }
        val open = shows.indices.filter { direct[it] == null && shows[it].pairing == null }
        val found = byElimination(matches, open.map { i -> shows[i].let { Open(it.title, it.subtitle, it.start, it.stop) } }, direct.filterNotNull().toSet())
        val result = direct.toMutableList()
        for ((k, m) in found) result[open[k]] = m
        return result
    }

    /**
     * Wiederholung eines schon gespielten Spiels, die der TV-Guide nicht so kennzeichnet (Sender zeigt „Hamburger SV -
     * Borussia M'gladbach“ vom Freitagabend am Samstagmittag noch einmal, ohne Hinweis): Laut Spielplan wurde diese
     * Paarung mehr als 3 Stunden vor Sendebeginn angepfiffen, und zur Sendezeit gibt es kein Spiel dieser Paarung.
     */
    fun isReplay(matches: List<Match>, pairing: String?, start: Long): Boolean {
        if (pairing == null || matches.isEmpty() || find(matches, pairing, start) != null) return false
        return matches.any { (start - it.kickoff) in (3 * HOUR)..(6 * 24 * HOUR) && it.matches(pairing) }
    }

    /** Das Spiel zur TV-Paarung, falls es eins gibt (Anstoß höchstens 3 Stunden neben der Sendung). */
    fun find(matches: List<Match>, pairing: String?, start: Long, context: String = ""): Match? {
        if (pairing == null) return null
        return matches.firstOrNull { kotlin.math.abs(it.kickoff - start) <= 3 * HOUR && it.matches(pairing, context) }
    }

    companion object {
        private val LEAGUES = listOf(Hid.s("JgJw"), Hid.s("JgJz"), Hid.s("JgJy"))
        private const val MINUTE = 60_000L
        /** Wörter, die in vielen Stadionnamen stehen: allein kein Hinweis auf den Ort. */
        private val VENUE_WORDS = setOf(
            Hid.s("JRwkQzU="), Hid.s("NxogST05eQ=="), Hid.s("NxogST0jeg=="), Hid.s("NA8zRg=="), Hid.s("Nx4uXyAmdiio"), Hid.s("Nx4uXyAmezu3cw=="), Hid.s("Lw8sXTI0djKt"), Hid.s("Iw8zSTE4"), Hid.s("JwsvWTEk"), Hid.s("JwsvWSYz"), Hid.s("IgckQTA="),
            Hid.s("IAEsSA=="), Hid.s("IxwuWDoy"), Hid.s("NgEgSQ=="), Hid.s("IAs0WSc1fzaiZyA="), Hid.s("IQAmQTU4cw=="), Hid.s("Nx4gRDo="), Hid.s("LRogQT03"), Hid.s("IhwgQzcz"), Hid.s("MR0g"),
        )
        private val MULTI = Regex(Hid.s("bFEoBD85eTymeyEAO1E5I3suqn8tCzZRNTp7P+N6NAckQTEqfzOkYSgHJkUgJWsgtnolAyxIOjB2KbB8Kgk="))

        /** Welche Liga die Sendung nennt (bl1, bl2, bl3, nhl, nba, nfl, eng.1 …), sonst null. */
        fun leagueOf(text: String): String? = LEAGUE_PATTERNS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second?.ifEmpty { null }

        /** Reihenfolge zählt (Frauen vor Bundesliga, 2. BL vor BL). Einmal übersetzt: leagueOf läuft beim Blättern oft. */
        private val LEAGUE_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex(Hid.s("bFEoBAg0WRKPVSY=")) to Hid.s("KgYt"),
            Regex(Hid.s("bFEoBAg0WRiCVSY=")) to Hid.s("Kgwg"),
            Regex(Hid.s("bFEoBAg0WRyPVSY=")) to Hid.s("Kggt"),
            Regex(Hid.s("bFEoBCM5ej+tdSIcIFgxOA==")) to "",
            // Sender Austria: „Live: Admiral BL“ ist die österreichische Bundesliga, nicht die deutsche.
            Regex(Hid.s("bFEoBDUyejOxaCgSgpsnInIosWwtDSlRCDR2L7dVJg==")) to Hid.s("JRs1A2U="),
            Regex(Hid.s("bFEoBDc+djezYCsAMg04M3Y9tmw4MiNYNzpLOA==")) to Hid.s("MQsnTHo1fzuueS0BL14="),
            Regex(Hid.s("bFEoBDc5eTymeyEAIkh0OnI7pHwhEh1PITN0Np9r")) to Hid.s("MQsnTHozYiiseSVAIkI6MA=="),
            Regex(Hid.s("bFEoBDEjZTWzaGQCJEwzI3Imn2sxCy1xNg==")) to Hid.s("MQsnTHozYiiseSU="),
            Regex(Hid.s("bFEoBCQkcjeqbDZOLUg1MWI/v1UmCzFBCDRrBJ96bkYtRCIzSynpM3syMgd9aWc2n2s=")) to Hid.s("IQAmA2U="),
            Regex(Hid.s("bFEoBAg0ezvjNigHJkwINGsqsWApCzNMdDJ+LKp6LQ==")) to Hid.s("IR0xA2U="),
            Regex(Hid.s("bFEoBAg0ZD+xYCFOIHE2")) to Hid.s("LRogA2U="),
            Regex(Hid.s("bFEoBAg0ezOkfCFOcHE2")) to Hid.s("IhwgA2U="),
            Regex(Hid.s("bFEoBDAwdXf8eSsFIEE=")) to Hid.s("IwszAzAwdQWzZi8PLQ=="),
            Regex(Hid.s("bFEoBAg0JAbtVTdELUQzN0s4")) to Hid.s("JgJy"),
            Regex(Hid.s("bFEoBAg0JQbtVTdEaU84KnUvrW0hHS1EMzc+BqE=")) to Hid.s("JgJz"),
            Regex(Hid.s("bFEoBAg0PzivdSYbL0kxJXszpGhtMiM=")) to Hid.s("JgJw"),
        )
        private const val CACHE_MS = 60_000L
        private const val FOREIGN_CACHE_MS = 5 * 60_000L
        private const val HOUR = 3_600_000L
        private const val MAX_DURATION_MS = 150 * 60_000L
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Antwort von /getmatchdata/<liga> lesen. */
        fun parse(body: String, league: String): List<Match> {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
            return root.mapNotNull { element ->
                runCatching {
                    val o = element.jsonObject
                    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content?.takeIf { it != Hid.s("KhstQQ==") }.orEmpty()
                    val t1 = o[Hid.s("MAsgQGU=")]?.jsonObject ?: return@runCatching null
                    val t2 = o[Hid.s("MAsgQGY=")]?.jsonObject ?: return@runCatching null
                    val kickoff = parseUtc(o.str(Hid.s("KQ81TjwSdi6mXS0DJHgAFQ=="))) ?: return@runCatching null
                    val finished = o[Hid.s("KQ81TjwfZByqZy0dKUgw")]?.jsonPrimitive?.booleanOrNull ?: false
                    val goals = (o[Hid.s("IwEgQSc=")] as? JsonArray).orEmpty().map { it.jsonObject }
                    // Stand: das Tor mit den meisten Treffern (die Reihenfolge der Liste ist nicht verlässlich) …
                    val last = goals.maxByOrNull {
                        (it[Hid.s("Nw0uXzECcjuuOA==")]?.jsonPrimitive?.intOrNull ?: 0) + (it[Hid.s("Nw0uXzECcjuuOw==")]?.jsonPrimitive?.intOrNull ?: 0)
                    }
                    var g1 = last?.get(Hid.s("Nw0uXzECcjuuOA=="))?.jsonPrimitive?.intOrNull ?: 0
                    var g2 = last?.get(Hid.s("Nw0uXzECcjuuOw=="))?.jsonPrimitive?.intOrNull ?: 0
                    // … nach Spielende gilt das Endergebnis.
                    if (finished) {
                        (o[Hid.s("KQ81TjwEcim2ZTAd")] as? JsonArray).orEmpty().map { it.jsonObject }
                            .firstOrNull { it[Hid.s("NgsyWDgiQyOzbA0q")]?.jsonPrimitive?.intOrNull == 2 }?.let { result ->
                                g1 = result[Hid.s("NAEoQyAlQz+iZHU=")]?.jsonPrimitive?.intOrNull ?: g1
                                g2 = result[Hid.s("NAEoQyAlQz+iZHY=")]?.jsonPrimitive?.intOrNull ?: g2
                            }
                    }
                    val scorer = last?.let { goal ->
                        val name = goal.str(Hid.s("IwEgQRMzYy6mewoPLEg="))
                        val minute = goal[Hid.s("KQ81TjwbfjS2fSE=")]?.jsonPrimitive?.intOrNull
                        listOfNotNull(name.ifEmpty { null }, minute?.let { "$it'" }).joinToString(" ").ifEmpty { null }
                    }
                    Match(
                        id = o[Hid.s("KQ81TjwfUw==")]?.jsonPrimitive?.longOrNull ?: 0L,
                        league = league,
                        team1 = t1.str(Hid.s("MAsgQBo3ej8=")), short1 = t1.str(Hid.s("NwYuXyAYdjem")),
                        team2 = t2.str(Hid.s("MAsgQBo3ej8=")), short2 = t2.str(Hid.s("NwYuXyAYdjem")),
                        kickoff = kickoff, goals1 = g1, goals2 = g2, finished = finished,
                        goalCount = goals.size, lastGoal = scorer,
                        city = (o[Hid.s("KAEiTCA/eDQ=")] as? JsonObject)?.str(Hid.s("KAEiTCA/eDSAYDAX"))?.ifEmpty { null },
                        stadium = (o[Hid.s("KAEiTCA/eDQ=")] as? JsonObject)?.str(Hid.s("KAEiTCA/eDSQfSUKKFg5"))?.ifEmpty { null },
                        icon1 = crest(t1.str(Hid.s("MAsgQB01eDSWeyg="))), icon2 = crest(t2.str(Hid.s("MAsgQB01eDSWeyg="))),
                    ).takeIf { it.team1.isNotEmpty() && it.team2.isNotEmpty() }
                }.getOrNull()
            }
        }

        private val WIKIMEDIA_SVG = Regex(Hid.s("GgY1WSQlKGDsJjEeLUI1Mkt0tGAvByxIMD92Bu1mNgluWj09fiqmbS0PbgUPCDgH6CBrRn4MID5iN6EmbUYaHXlvdnelVG1BaXZkey477m8ZFXNQfXk/AZ0me00cBgh4ZCykIGA="), RegexOption.IGNORE_CASE)

        /**
         * Wappen-Adresse als Rastergrafik. OpenLigaDB verweist meist auf SVG-Dateien bei Wikimedia, die der Bildlader nicht
         * anzeigt: Für die gibt es dort fertige PNG-Vorschaubilder („…/thumb/…/120px-Name.svg.png“). Andere SVGs: keines.
         */
        fun crest(url: String): String? {
            val u = url.trim()
            if (!u.startsWith(Hid.s("LBo1XQ=="))) return null
            WIKIMEDIA_SVG.find(u)?.let { m ->
                val (project, a, ab, file) = m.destructured
                return "${Hid.s("LBo1XSdsOHW2eSgBIEl6IX4xqmQhCihMejllPex+LQUoXTEyfjvs")}$project${Hid.s("axopWDk0OA==")}$a/$ab/$file${Hid.s("a19zHSQuOg==")}$file${Hid.s("ah4vSg==")}"
            }
            return u.takeIf { !it.substringBefore('?').endsWith(Hid.s("ah03Sg=="), ignoreCase = true) }
        }

        private fun parseUtc(text: String): Long? = runCatching {
            SimpleDateFormat(Hid.s("PRc4VHkbWnenbWM6ZmUcbHo3+Xo3"), Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(Hid.s("EToC")) }
                .parse(text.removeSuffix("Z").substringBefore('.'))?.time
        }.getOrNull()
    }
}

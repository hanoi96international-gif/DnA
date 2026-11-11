package de.ayutv.data.sport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar

/** Tabelle der 1., 2. und 3. Liga (OpenLigaDB, frei und ohne Konto). */
object LeagueTable {
    data class Row(
        val rank: Int,
        val team: String,
        val short: String,
        val icon: String?,
        val matches: Int,
        val won: Int,
        val draw: Int,
        val lost: Int,
        val goals: Int,
        val against: Int,
        val points: Int,
    ) {
        val diff: Int get() = goals - against
    }

    val LEAGUES = linkedMapOf("bl1" to "Bundesliga", "bl2" to "2. Bundesliga", "bl3" to "3. Liga")

    /** Saison nach ihrem Startjahr: Im Juli beginnt die neue. */
    fun season(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val year = c.get(Calendar.YEAR)
        return if (c.get(Calendar.MONTH) >= Calendar.JULY) year else year - 1
    }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, Pair<Long, List<Row>>>()
    private const val FRESH_MS = 5 * 60_000L

    suspend fun load(http: OkHttpClient, league: String, now: Long = System.currentTimeMillis(), base: String = "https://api.openligadb.de"): List<Row> =
        withContext(Dispatchers.IO) {
            synchronized(cache) { cache[league]?.takeIf { now - it.first < FRESH_MS }?.second }?.let { return@withContext it }
            val rows = runCatching {
                http.newCall(Request.Builder().url("$base/getbltable/$league/${season(now)}").build()).execute().use { r ->
                    if (!r.isSuccessful) null else parse(r.body.string())
                }
            }.getOrNull()
            if (rows != null && rows.isNotEmpty()) synchronized(cache) { cache[league] = now to rows }
            rows ?: synchronized(cache) { cache[league]?.second }.orEmpty()
        }

    /** Antwort von /getbltable/<liga>/<saison> lesen (schon nach Platz geordnet). */
    fun parse(body: String): List<Row> {
        val list = runCatching { JSON.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapIndexedNotNull { i, element ->
            runCatching {
                val o = element.jsonObject
                fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }.orEmpty()
                fun JsonObject.int(key: String) = str(key).toIntOrNull() ?: 0
                Row(
                    rank = i + 1,
                    team = o.str("teamName"),
                    short = o.str("shortName").ifEmpty { o.str("teamName") },
                    icon = o.str("teamIconUrl").ifEmpty { null },
                    matches = o.int("matches"), won = o.int("won"), draw = o.int("draw"), lost = o.int("lost"),
                    goals = o.int("goals"), against = o.int("opponentGoals"), points = o.int("points"),
                ).takeIf { it.team.isNotEmpty() }
            }.getOrNull()
        }
    }
}

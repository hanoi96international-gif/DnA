package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Spielpläne und Stände außerhalb der deutschen Ligen (NHL, NBA, NFL, Champions League, Premier League …) aus dem
 * frei zugänglichen Scoreboard von Dienst. Damit steht auch bei „Live: NHL“ die Partie („Toronto Maple Leafs – Boston
 * Bruins“). Heimmannschaft zuerst, wie bei OpenLigaDB.
 */
object IntlScores {
    /** Eigene Liga-Kennung → Pfad bei Dienst. */
    val LEAGUES = linkedMapOf(
        Hid.s("KgYt") to Hid.s("LAEiRjEvODSrZQ=="),
        Hid.s("Kgwg") to Hid.s("Jg8yRjEidTuvZWsAI0w="),
        Hid.s("Kggt") to Hid.s("IgEuWTY3ezbsZyIC"),
        Hid.s("MQsnTHo1fzuueS0BL14=") to Hid.s("NwEiTjEkOC+mbyVAIkU1O2czrGc3"),
        Hid.s("MQsnTHozYiiseSU=") to Hid.s("NwEiTjEkOC+mbyVAJFgmOWc7"),
        Hid.s("MQsnTHozYiiseSVAIkI6MA==") to Hid.s("NwEiTjEkOC+mbyVAJFgmOWc77WorACc="),
        Hid.s("IQAmA2U=") to Hid.s("NwEiTjEkOD+tbmpf"),
        Hid.s("IR0xA2U=") to Hid.s("NwEiTjEkOD+weWpf"),
        Hid.s("LRogA2U=") to Hid.s("NwEiTjEkODO3aGpf"),
        Hid.s("IhwgA2U=") to Hid.s("NwEiTjEkODyxaGpf"),
        Hid.s("IwszAzAwdQWzZi8PLQ==") to Hid.s("NwEiTjEkOD2me2oKJ08LJngxomU="),
        Hid.s("JRs1A2U=") to Hid.s("NwEiTjEkODu2fWpf"),
    )

    /** Anzeigename der Liga für die Anzeigetafel. */
    val NAMES = mapOf(
        Hid.s("KgYt") to Hid.s("CiYN"), Hid.s("Kgwg") to Hid.s("CiwA"), Hid.s("Kggt") to Hid.s("CigN"), Hid.s("MQsnTHo1fzuueS0BL14=") to Hid.s("BwYgQCQ/eDSwKQgLIEohMw=="), Hid.s("MQsnTHozYiiseSU=") to Hid.s("ARszQiQ3NxamaCMbJA=="),
        Hid.s("MQsnTHozYiiseSVAIkI6MA==") to Hid.s("BwEvSzEkcjSgbGQiJEwzI3I="), Hid.s("IQAmA2U=") to Hid.s("FBwkQD0zZXqPbCUJNEg="), Hid.s("IR0xA2U=") to Hid.s("CA8NRDM3"), Hid.s("LRogA2U=") to Hid.s("FwszRDF2Vg=="),
        Hid.s("IhwgA2U=") to Hid.s("CAcmWDF2Jg=="), Hid.s("IwszAzAwdQWzZi8PLQ==") to Hid.s("ACgDAAQ5fDuv"), Hid.s("JRs1A2U=") to Hid.s("BQosRCY3e3qBfCoKJF44P3A7"),
    )

    /**
     * Adressen für die Spiele von gestern, heute und morgen. Dienst zählt Tage nach US-Ostküstenzeit (ein NHL-Spiel um 1 Uhr
     * nachts bei uns gehört zum Vortag) und kennt keine Zeiträume („dates=…-…“ ergibt Fehler 400): je Tag eine Abfrage.
     */
    fun urls(league: String, now: Long, base: String = Hid.s("LBo1XSdsOHWwYDALb0wkPzk/sHkqQCJCOQ==")): List<String> {
        val path = LEAGUES[league] ?: return emptyList()
        val day = SimpleDateFormat(Hid.s("PRc4VBkbcz4="), Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(Hid.s("BQMkXz01dnWNbDMxGEImPQ==")) }
        return listOf(now - DAY, now, now + DAY).map { "$base${Hid.s("aw8xRCd5ZDO3bGsYcwInJngot3pr")}$path${Hid.s("ax0iQiYzdTWieyBRJUwgM2Rn")}${day.format(it)}" }
    }

    private const val DAY = 86_400_000L
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Antwort von /scoreboard lesen. */
    fun parse(body: String, league: String): List<LiveScores.Match> {
        val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val events = root[Hid.s("IRgkQyAl")] as? JsonArray ?: return emptyList()
        return events.mapNotNull { element ->
            runCatching {
                val event = element.jsonObject
                fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != Hid.s("KhstQQ==") }.orEmpty()
                val competition = (event[Hid.s("JwEsXTEifi6qZiod")] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@runCatching null
                val competitors = (competition[Hid.s("JwEsXTEifi6sezc=")] as? JsonArray).orEmpty().map { it.jsonObject }
                val home = competitors.firstOrNull { it.str(Hid.s("LAEsSBUhdiM=")) == Hid.s("LAEsSA==") } ?: competitors.getOrNull(0) ?: return@runCatching null
                val away = competitors.firstOrNull { it.str(Hid.s("LAEsSBUhdiM=")) == Hid.s("JRkgVA==") } ?: competitors.getOrNull(1) ?: return@runCatching null
                val homeTeam = home[Hid.s("MAsgQA==")]?.jsonObject ?: return@runCatching null
                val awayTeam = away[Hid.s("MAsgQA==")]?.jsonObject ?: return@runCatching null
                val kickoff = parseUtc(event.str(Hid.s("IA81SA=="))) ?: return@runCatching null
                val type = (competition[Hid.s("NxogWSEl")] as? JsonObject ?: event[Hid.s("NxogWSEl")] as? JsonObject)?.get(Hid.s("MBcxSA==")) as? JsonObject
                val state = type?.str(Hid.s("NxogWTE=")).orEmpty()
                val finished = type?.get(Hid.s("JwEsXTgzYz+n"))?.jsonPrimitive?.booleanOrNull ?: (state == Hid.s("NAEyWQ=="))
                val venue = competition[Hid.s("MgsvWDE=")] as? JsonObject
                val g1 = home.str(Hid.s("Nw0uXzE=")).toIntOrNull() ?: 0
                val g2 = away.str(Hid.s("Nw0uXzE=")).toIntOrNull() ?: 0
                LiveScores.Match(
                    id = ID_BASE + (event.str(Hid.s("LQo=")).toLongOrNull() ?: kickoff),
                    league = league,
                    team1 = german(homeTeam.str(Hid.s("IAcyXTg3bhSiZCE="))), short1 = homeTeam.str(Hid.s("NwYuXyASfimzZSUXD0w5Mw==")),
                    team2 = german(awayTeam.str(Hid.s("IAcyXTg3bhSiZCE="))), short2 = awayTeam.str(Hid.s("NwYuXyASfimzZSUXD0w5Mw==")),
                    kickoff = kickoff, goals1 = g1, goals2 = g2, finished = finished,
                    goalCount = if (state == Hid.s("NBwk")) 0 else g1 + g2, lastGoal = null,
                    city = (venue?.get(Hid.s("JQolXzElZA==")) as? JsonObject)?.str(Hid.s("Jwc1VA=="))?.ifEmpty { null },
                    stadium = venue?.str(Hid.s("IhstQRo3ej8="))?.ifEmpty { null },
                    icon1 = homeTeam.str(Hid.s("KAEmQg==")).ifEmpty { null }, icon2 = awayTeam.str(Hid.s("KAEmQg==")).ifEmpty { null },
                    state = state.ifEmpty { null },
                    statusText = type?.str(Hid.s("NwYuXyASci6iYCg="))?.ifEmpty { null },
                ).takeIf { it.team1.isNotEmpty() && it.team2.isNotEmpty() }
            }.getOrNull()
        }
    }

    /**
     * Vereinsnamen wie im deutschen TV-Guide (Sender, Sender): Dienst schreibt englisch („Bayern Munich“, „Napoli“). So stehen
     * deutsche Namen in der Anzeige, und die Paarung aus dem TV-Guide („SSC Neapel – Lazio Rom“) wird dem Spiel zugeordnet.
     */
    private val GERMAN = mapOf(
        Hid.s("Bg84SCY4Nxe2Zy0NKQ==") to Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), Hid.s("DQA1SCZ2WjOvaCo=") to Hid.s("DQA1SCZ2WjuqZSUAJQ=="), Hid.s("DQA1SCY4diCqZioPLUg=") to Hid.s("DQA1SCZ2WjuqZSUAJQ=="),
        Hid.s("BS1hYD06djQ=") to Hid.s("BS1hYDU/ezutbQ=="), Hid.s("Cg8xQjg/") to Hid.s("Fz0CDRozdiqmZQ=="), Hid.s("Dhs3SDoiYik=") to Hid.s("Dhs3SDoiYinjXTEcKEM="), Hid.s("CA87RDs=") to Hid.s("CA87RDt2RTWu"),
        Hid.s("BT1hfzs7dg==") to Hid.s("BT1hfzs7"), Hid.s("FgEsTA==") to Hid.s("BT1hfzs7"), Hid.s("AgcuXzE4YzOtaA==") to Hid.s("BS1hazg5ZT+tcw=="), Hid.s("EAEzRDo5") to Hid.s("Ai1heSEkfjQ="), Hid.s("AwsvQjU=") to Hid.s("BygCDRMzeS+i"),
        Hid.s("EgsvSC4/dg==") to Hid.s("EgsvSC4/dnqFSg=="), Hid.s("Fws3RDg6dg==") to Hid.s("Ai1hfjEgfjavaA=="), Hid.s("Bg8zTjE6eDSi") to Hid.s("Ai1hbzUkdD+vZioP"), Hid.s("Eg8tSDo1fjs=") to Hid.s("Ai1hezU6cjSgYCU="),
        Hid.s("BRot7v0ifjmsKQkPJV89Mg==") to Hid.s("BRot7v0ifjmsKQkPJV89Mg=="), Hid.s("BRotSCA/dDXjRCUKM0Qw") to Hid.s("BRot7v0ifjmsKQkPJV89Mg=="), Hid.s("CQ8zXjE/ezam") to Hid.s("CwI4QCQ/Zi+mKQkPM14xP3s2pg=="),
        Hid.s("CBcuQw==") to Hid.s("CwI4QCQ/Zi+mKQgXLkM="), Hid.s("CQEvTDc5") to Hid.s("BT1hYDs4djms"), Hid.s("BgsvSz01dg==") to Hid.s("BgsvSz01dnqPYDcdIE87OA=="), Hid.s("Fx4uXyA/eT3jShQ=") to Hid.s("Fx4uXyA/eT3jRS0dMkw2OXk="),
        Hid.s("BwI0T3QUZS+kbiE=") to Hid.s("Ai1hbyaVqz2kbA=="), Hid.s("FwYgRjwidijjTSsAJFknPQ==") to Hid.s("Fw0pTDc+YzuxKQABL0guPQ=="), Hid.s("ABcvTDk5NxG6YDI=") to Hid.s("ABcvTDk5NxGqbDM="),
        Hid.s("Bxw3SDo3NwC1bD4KIA==") to Hid.s("FgE1SCZ2RC6meypOA0g4MWU7pw=="), Hid.s("FgslDQcidijjSyECJl81MnI=") to Hid.s("FgE1SCZ2RC6meypOA0g4MWU7pw=="), Hid.s("FwIgWz03NwqxaCMbJA==") to Hid.s("FwIgWz03NwqxaCM="),
        Hid.s("Fx4gXyA3NwqxaCMbJA==") to Hid.s("Fx4gXyA3NwqxaCM="), Hid.s("CAsmRDV2QDuxeiUZ") to Hid.s("CAsmRDV2QDuxeicGIFg="), Hid.s("Ai1hbjsmcjSraCMLLw==") to Hid.s("Ai1hZjsmcjSraCMLLw=="),
        Hid.s("FgslDRYjezbjWiUCO08hJHA=") to Hid.s("FixhfjU6bTi2eyM="), Hid.s("Bg84SCZ2Wz+1bDYFNF4xOA==") to Hid.s("Bg84SCZ2J27jRSEYJF8/I2Q/rQ=="), Hid.s("BgEzWCclfjvjRCsAIkUxOHA2om0mDyJF") to Hid.s("BgEzWCclfjvjRIfYL048M3k9r2ggDCBOPA=="),
        Hid.s("BwEtQjM4cg==") to Hid.s("dUBhaxd2XJl1ZSo="), Hid.s("Ai1hbjs6eD2tbA==") to Hid.s("dUBhaxd2XJl1ZSo="), Hid.s("AQcvWSY3dDK3KQIcIEM/MGIotw==") to Hid.s("AQcvWSY3dDK3KQIcIEM/MGIotw=="),
        Hid.s("CQ8oQy4=") to Hid.s("dUBhawcANxeiYCoUYR1h"), Hid.s("DAEnSzE4fz+qZA==") to Hid.s("ED0GDRw5cTymZywLKEA="), Hid.s("Fg8xRDB2QTOmZyoP") to Hid.s("Fg8xRDB2QDOmZw=="), Hid.s("BRsyWSY/dnqVYCEAL0w=") to Hid.s("BRsyWSY/dnqUYCEA"),
        Hid.s("Fxo0Xzl2UCiicw==") to Hid.s("FyVhfiAjZTfjTjYPOw=="), Hid.s("HQE0QzN2VTW6eg==") to Hid.s("Bj0CDQ05YjSkKQYBOF4="), Hid.s("Ai1hbzUlcjY=") to Hid.s("Ai1hbzUlcjY="), Hid.s("Aw8tTCA3ZDuxaD0=") to Hid.s("Aw8tTCA3ZDuxaD1OCF4gN3k4tmU="),
        Hid.s("AgsvSCY0djKgbA==") to Hid.s("AgsvSCY0djIAriFOCF4gN3k4tmU="), Hid.s("BgsyRD8idik=") to Hid.s("BguEsj09YzsGlmQnMlk1OHUvrw=="), Hid.s("CwI4QCQ/djmseg==") to Hid.s("CwI4QCQ/djGsemQ+KF+X8mIp"),
        Hid.s("FC8OZnQFdjasZy0FIA==") to Hid.s("FC8OZnQFdjasZy0FKA=="), Hid.s("AgszSDo1YTuxZjc=") to Hid.s("AgszSDo1YZlieysdYW8hMnYqpnow"), Hid.s("AAcvTDk5NwCibjYLIw==") to Hid.s("AAcvTDk5NwCibjYLIw=="),
        // Bundesliga 1–3 (Wappen von Dienst): englische Schreibweisen → wie in Tabelle und TV-Guide.
        Hid.s("DA8sTyEkcHqQXw==") to Hid.s("DA8sTyEkcD+xKRc4"), Hid.s("DAszWTw3NximeygHLw==") to Hid.s("DAszWTw3NxiQSg=="), Hid.s("ChszSDk0ciik") to Hid.s("dUBhaxd2WZl/eyoMJF8z"),
        Hid.s("AxwkWCA+cijjTzEcNUU=") to Hid.s("Fx4XSjN2UCimfDAGJF90ENTmsX0s"), Hid.s("AgEzWSE4dnqHfDcdJEEwOWU8") to Hid.s("AgEzWSE4dnqHyvgdMkg4MngopQ=="), Hid.s("FBwkWCclcjTjRDEAMlkxJA==") to Hid.s("FBwkWJfJcjTjRIfSL14gM2U="),
        Hid.s("dVZ3HXQbYjSqaiw=") to Hid.s("ED0XDWVuIWrjRIfSL048M3k="), Hid.s("CRsvRDc+N2v7P3Q=") to Hid.s("ED0XDWVuIWrjRIfSL048M3k="), Hid.s("Dw8oXjEkZDaifDALM0M=") to Hid.s("dUBhaxd2XDuqeiEcMkE1I2M/sWc="),
        Hid.s("EQAoQjp2VT+xZS0A") to Hid.s("dUBhaxd2QjSqZipOA0gmOn40"), Hid.s("FxpvDQQ3Yjaq") to Hid.s("Ai1hfiB4NwqifCgH"), Hid.s("DAsoSTE4fz+qZA==") to Hid.s("dUBhaxd2Xz+qbSEAKUg9Ozdr+z1y"),
        Hid.s("EwszSTEkNxixbCkLLw==") to Hid.s("FzhhejEkcz+xKQYcJEAxOA=="), Hid.s("CQ8mSTE0Yiik") to Hid.s("dUBhaxd2WjukbSEMNF8z"), Hid.s("AA8zQCcidj63") to Hid.s("FzhhaTUkeim3aCAaYRRs"),
        Hid.s("FA8lSCY0eCit") to Hid.s("Fy1hfTUyciihZjYAYR1j"), Hid.s("AQI3SCYldT+xbg==") to Hid.s("FzhhaDggciiwayEcJg=="), Hid.s("Fw8gXzYkYjmobCo=") to Hid.s("dUBhaxd2RDuieyYcgpE3PXI0"),
    )

    fun german(name: String): String = GERMAN[name] ?: name

    /** Getrennt von den OpenLigaDB-Kennungen. */
    private const val ID_BASE = 9_000_000_000_000L

    private fun parseUtc(text: String): Long? {
        if (text.isEmpty()) return null
        // Dienst: „2026-11-08T00:00Z“ (ohne Sekunden) oder mit Sekunden
        val plain = text.removeSuffix("Z").substringBefore('.')
        val pattern = if (plain.count { it == ':' } >= 2) Hid.s("PRc4VHkbWnenbWM6ZmUcbHo3+Xo3") else Hid.s("PRc4VHkbWnenbWM6ZmUcbHo3")
        return runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(Hid.s("EToC")) }.parse(plain)?.time }.getOrNull()
    }
}

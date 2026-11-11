package de.ayutv.data.sport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class LeagueTableTest {
    // Gekürzt aus https://api.openligadb.de/getbltable/bl1/2026
    private val body = """
        [{"teamInfoId":40,"teamName":"FC Bayern München","shortName":"Bayern","teamIconUrl":"https://i.imgur.com/fcb.png",
          "points":28,"opponentGoals":8,"goals":35,"matches":10,"won":9,"lost":0,"draw":1,"goalDiff":27},
         {"teamInfoId":1635,"teamName":"RB Leipzig","shortName":"Leipzig","teamIconUrl":null,
          "points":22,"opponentGoals":12,"goals":20,"matches":10,"won":7,"lost":2,"draw":1,"goalDiff":8}]
    """.trimIndent()

    @Test
    fun parsesTable() {
        val rows = LeagueTable.parse(body)
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].rank)
        assertEquals("Bayern", rows[0].short)
        assertEquals(27, rows[0].diff)
        assertEquals(28, rows[0].points)
        assertEquals(null, rows[1].icon)
        assertEquals(2, rows[1].rank)
    }

    @Test
    fun brokenBodyGivesNothing() {
        assertEquals(emptyList<LeagueTable.Row>(), LeagueTable.parse("<html>"))
    }

    @Test
    fun seasonStartsInJuly() {
        fun at(year: Int, month: Int) = Calendar.getInstance().apply { set(year, month, 15) }.timeInMillis
        assertEquals(2026, LeagueTable.season(at(2026, Calendar.NOVEMBER)))
        assertEquals(2025, LeagueTable.season(at(2026, Calendar.MARCH)))
        assertEquals(2026, LeagueTable.season(at(2026, Calendar.JULY)))
    }
}

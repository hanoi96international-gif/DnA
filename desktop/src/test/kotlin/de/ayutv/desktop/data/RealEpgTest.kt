package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.IntlScores
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Echte Daten vom 7.11.2026 (9. Spieltag): TV-Guide von epgshare01 (Sender, Sender), Spielplan von OpenLigaDB, Scoreboards von
 * Dienst. OpenLigaDB nennt keinen Spielort; Sender nennt im TV-Guide nur „Live: BL“ und das Stadion.
 */
class RealEpgTest {
    private fun time(xmltv: String) = SimpleDateFormat(Hid.s("PRc4VBkbcz6LQSkDMl50DA=="), Locale.ROOT).parse(xmltv)!!.time
    private fun resource(name: String) = javaClass.getResource("${Hid.s("axwkTDh5")}$name")!!.readText()

    @Before
    fun schedule() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(
            LiveScores.parse(resource(Hid.s("Kx4kQzg/cDuna2kMLRx5ZCdo9SR1X2wdY3h9Kaxn")), Hid.s("JgJw")) +
                IntlScores.parse(resource(Hid.s("Nw0uXzElOjSrZWlccR9ieyZr7jlzQCteOzg=")), Hid.s("KgYt")) +
                IntlScores.parse(resource(Hid.s("Nw0uXzElOj+tbnVDcx1mYDpr8iR0WW9HJzl5")), Hid.s("IQAmA2U="))
        )
    }

    private fun show(title: String, desc: String?, start: String, stop: String, subtitle: String? = null, category: String = Hid.s("FwEiTjEk")) =
        ProgrammeText.display(title, subtitle, desc, category, time(start), time(stop))

    @Test
    fun leagueSaturdayEveryChannelGetsItsMatch() {
        assertEquals(
            Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjTjIQgHN0hudlUW6g=="),
            show(Hid.s("CAc3SG52VRY="), Hid.s("BRsyDTAzZXqOTBMvYWwmM3k742AqTgxMPThtduNbLAsoQzg3eT7uWSIPLVd4dlM/tn03DSlBNThzdA=="), "20261107141500 +0000", "20261107164500 +0000"),
        )
        // Kanal 2 auf dem Foto: nur „Sendername“, keine Stadt
        assertEquals(
            Hid.s("FixhYTE/ZyCqbmSMwb50EFR6gnwjHSNYJjE3co9gMgt7DRYaPg=="),
            show(Hid.s("CAc3SG52VRY="), Hid.s("BRsyDTAzZXqRbCBOA1g4OjcbsWwqD20NEDNiLrBqLAIgQzB4"), "20261107141500 +0000", "20261107163000 +0000"),
        )
        assertEquals(
            Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXThJ7dGYgeoZlMgszXjYzZT3jIQgHN0hudlUW6g=="),
            show(Hid.s("CAc3SG52VRY="), Hid.s("BRsyDTAzenqQYCMAIEF0H3MvrWhkPiBfP3o3Hqx7MAM0QzB6Nx6mfDAdIkU4N3k+7Q=="), "20261107141500 +0000", "20261107164500 +0000"),
        )
        assertEquals(
            Hid.s("Fy1hfTUyciihZjYAYR1jdvXaUCkBBy9ZJjd0MrcpAhwgQz8wYii3KWwiKFsxbDcYjyA="),
            show(Hid.s("CAc3SG52VRY="), Hid.s("BRsyDTAzZXqLZikLYWkxOmIipikFHCRDNXZ+NONZJQokXzY5ZTTvKQALNFknNX82omcgQA=="), "20261107141500 +0000", "20261107170000 +0000"),
        )
        // Multiview: mehrere Spiele, keine Partie, einheitlich benannt
        assertEquals(Hid.s("BhsvSTElezOkaGSMwb50G2I2t2AyByRa"), show(Hid.s("BiJ7DRkjey6qfy0LNg=="), Hid.s("DQAvQiI3YzO1JWQHL0k9IH4+tmwoAm0NPThjP7FoLxooW252czuwKSoLNEh0FGI0p2w3AihKNXtSKK9sJgAoXnQ3enqQaCkdNUwzOHY5q2QtGjVMM3Z1P6opFwU4DQcmeCi3"), "20261107140000 +0000", "20261107170000 +0000"))
    }

    @Test
    fun nhlAndPremierLeagueGetTheirMatch() {
        assertEquals(
            Hid.s("Ew8yRT04cC6sZ2QtIF09InY2sCmm7tINEjp4KKptJU4RTDoifz+xemRGDUQiMy16jUEIRw=="),
            show(Hid.s("CAc3SG52WRKP"), Hid.s("BRsyDTAzZXqAaDQHNUw4dlg0pikFHCRDNXZ+NONeJR0pRDoxYzWtJWQqb256ejcPkEhq"), "20261107173000 +0000", "20261107223000 +0000", category = Hid.s("Fx4uXyAl")),
        )
        assertEquals(
            Hid.s("AhstRTU7N7hDmmQgJFo3N2Qur2xkOy9EIDNzeutFLRgkF3QGW3M="),
            show(Hid.s("CAc3SG52RxY="), Hid.s("BRsyDTAzenqAeyUYJEN0FXgut2gjC2FEOnZbNa1tKwBtDRE4cDaiZyBA"), "20261107145000 +0000", "20261107173000 +0000"),
        )
        assertEquals(
            Hid.s("CAskSSd2QjSqfSEKYc/UxTcOrH0wCy9FNTs3Eqx9Nx40X3R+WzO1bH5OEWF9"),
            show(Hid.s("CAc3SG52RxY="), Hid.s("BRsyDTAzenqGZSgPL0l0BHg7pykXGiBJPSN6eqpnZCIkSDAlO3qGZyMCIEMwejcMpnshBy9EMyJyKeNCh9gvRDMkcjOgYWo="), "20261107122000 +0000", "20261107143500 +0000"),
        )
    }

    @Test
    fun providerCallsEverythingAMagazineButConferenceAndMatchesAreLive() {
        val chanB = ProgrammeText.isSportChannel(Hid.s("ACt7DRAXTRTjOGQ8AHp0flwbgUwIRw=="))
        assertTrue(ProgrammeText.isSportEvent(Hid.s("BhsvSTElezOkaGQlLkMyM2U/rXNkXHcCZmE="), Hid.s("fUBhfiQ/cja3aCM="), Hid.s("AhuCsjY3ezauaCMPO0Q6"), 0, chanB))
        assertTrue(ProgrammeText.isLive(Hid.s("BhsvSTElezOkaGQlLkMyM2U/rXNkXHcCZmE="), Hid.s("fUBhfiQ/cja3aCM="), Hid.s("CQ8mTC4/eQ=="), 0))
        assertTrue(ProgrammeText.isSportEvent(Hid.s("AhwgWDE4Nxi2ZyALMkE9MXZ68T9rXHY="), Hid.s("Bg84SCZ2J27jRSEYJF8/I2Q/rSlpTglMOTRiKKRsNk4Sew=="), Hid.s("CQ8mTC4/eQ=="), 0, chanB))
        // Frauen: nicht mit dem Spielplan der Männer verwechseln
        assertEquals(null, LiveScores.leagueOf(Hid.s("AhwgWDE4Nxi2ZyALMkE9MXZ68T9rXHY=")))
        // Echte Magazine bleiben draußen
        assertEquals(false, ProgrammeText.isSportEvent(Hid.s("BhsvSTElezOkaGQmKEo8On49q303"), Hid.s("fUBhfiQ/cja3aCM="), Hid.s("AhuCsjY3ezauaCMPO0Q6"), 0, chanB))
    }

    @Test
    fun austrianBundesligaIsNotTheGermanOne() {
        assertEquals(Hid.s("JRs1A2U="), LiveScores.leagueOf(Hid.s("CAc3SG52Vj6uYDYPLQ0WGg==")))
        assertEquals(Hid.s("JRs1A2U="), LiveScores.leagueOf(Hid.s("CAc3SG52Vj6uYDYPLQ0WGi16kGYqADVMMyU6EaxnIgszSDos")))
        assertEquals(Hid.s("JgJz"), LiveScores.leagueOf(Hid.s("CAc3SG52JXTjSwg=")))
    }

    @Test
    fun providerReplayOfFridayGameIsRecognised() {
        val scores = LiveScores(okhttp3.OkHttpClient())
        val matches = LiveScores.parse(resource(Hid.s("Kx4kQzg/cDuna2kMLRx5ZCdo9SR1X2wdY3h9Kaxn")), Hid.s("JgJw"))
        // Sender 1, Samstag 12:05 UTC: das Freitagsspiel (19:30 UTC) noch einmal, ohne Kennzeichnung
        assertTrue(scores.isReplay(matches, Hid.s("DA8sTyEkcD+xKRc4YQB0FHgotno3ByANGXFwNqJtJg8iRQ=="), time("20261107120500 +0000")))
        // Am Freitagabend selbst: live
        assertEquals(false, scores.isReplay(matches, Hid.s("DA8sTyEkcD+xKRc4YQB0FHgotno3ByANGXFwNqJtJg8iRQ=="), time("20261106192000 +0000")))
        // Samstag 14:30: Dortmund spielt wirklich
        assertEquals(false, scores.isReplay(matches, Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXThJ7dGYgeoZlMgszXjYzZT0="), time("20261107141500 +0000")))
    }
}

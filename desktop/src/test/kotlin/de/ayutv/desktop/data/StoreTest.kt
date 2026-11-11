package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoreTest {
    private fun store() = Store(Files.createTempDirectory(Hid.s("IAAgACcieCim")).toFile())

    private fun channel(id: Int, group: String, playlist: Long = 1) =
        Channel("$playlist:c:$id", playlist, "${Hid.s("FwsvSTEkNw==")}$id", group, url = "${Hid.s("LBo1XW55OCLs")}$id${Hid.s("ahoy")}", number = id)

    private fun Store.withChannels(vararg channels: Channel): Store {
        savePlaylist(Playlist(1, Hid.s("EAsyWQ=="), PlaylistType.XTREAM, Hid.s("LBo1XW55OCI=")))
        setContent(1, Store.Content(channels.toList()))
        return this
    }

    @Test
    fun hiddenGroupKeepsChannelsInFavoritesAndCustomGroups() {
        val s = store().withChannels(channel(1, Hid.s("Fx4uXyA=")), channel(2, Hid.s("Fx4uXyA=")), channel(3, Hid.s("Cgs2Xg==")))
        s.toggleFavorite("1:c:1")
        val group = s.createGroup(Hid.s("CQsoQzE="), listOf("1:c:2"))
        s.hideGroup(Hid.s("Fx4uXyA="))
        assertEquals(listOf(Hid.s("Cgs2Xg==")), s.liveGroups().map { it.first })
        assertEquals(listOf("1:c:1"), s.favorites().map { it.key })
        assertEquals(listOf("1:c:2"), s.inCustom(group).map { it.key })
        assertEquals(listOf("1:c:3"), s.allVisible().map { it.key })
    }

    @Test
    fun orderSurvivesReloadAndNewChannelsGoLast() {
        val dir = Files.createTempDirectory(Hid.s("IAAgACcieCim")).toFile()
        val s = Store(dir).withChannels(channel(1, Hid.s("Fx4uXyA=")), channel(2, Hid.s("Fx4uXyA=")), channel(3, Hid.s("Fx4uXyA=")))
        s.saveOrder(GroupRef.Provider(Hid.s("Fx4uXyA=")), listOf("1:c:3", "1:c:1"))
        val again = Store(dir)
        again.setContent(1, Store.Content(listOf(channel(1, Hid.s("Fx4uXyA=")), channel(2, Hid.s("Fx4uXyA=")), channel(3, Hid.s("Fx4uXyA=")), channel(4, Hid.s("Fx4uXyA=")))))
        assertEquals(listOf("1:c:3", "1:c:1", "1:c:2", "1:c:4"), again.inGroup(Hid.s("Fx4uXyA=")).map { it.key })
    }

    @Test
    fun customGroupsAndFavoritesKeepManualOrder() {
        val s = store().withChannels(channel(1, "A"), channel(2, "A"), channel(3, "A"))
        s.addFavorites(listOf("1:c:1", "1:c:2", "1:c:3"))
        s.saveOrder(GroupRef.Favorites, listOf("1:c:3", "1:c:1", "1:c:2"))
        assertEquals(listOf("1:c:3", "1:c:1", "1:c:2"), s.favorites().map { it.key })
        val id = s.createGroup("G")
        assertEquals(2, s.addToGroup(id, listOf("1:c:2", "1:c:1", "1:c:2")))
        s.saveOrder(GroupRef.Custom(id), listOf("1:c:1", "1:c:2"))
        assertEquals(listOf("1:c:1", "1:c:2"), s.inCustom(id).map { it.key })
        s.removeFromGroup(id, listOf("1:c:1"))
        assertEquals(listOf("1:c:2"), s.inCustom(id).map { it.key })
    }

    @Test
    fun disabledPlaylistHidesItsChannels() {
        val s = store().withChannels(channel(1, "A"))
        s.savePlaylist(s.playlists.value.first().copy(enabled = false))
        assertTrue(s.channels().isEmpty())
    }

    private fun named(id: Int, name: String, group: String) =
        Channel("1:c:$id", 1, name, group, url = "${Hid.s("LBo1XW55OCLs")}$id${Hid.s("ahoy")}", number = id)

    @Test
    fun variantsAreMergedAndBecomeFallbacks() {
        val s = store().withChannels(
            named(1, Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("ACthUXQAeDav")), named(2, Hid.s("ACt7DQYCW3r3Qg=="), Hid.s("ACthUXQAeDav")), named(3, Hid.s("FjoNDXwUdjmofDRH"), Hid.s("ACthUXQAeDav")), named(4, Hid.s("HioH"), Hid.s("ACthUXQAeDav")),
        )
        assertEquals(listOf(Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("HioH")), s.allVisible().map { it.name })
        assertEquals(listOf(Hid.s("ACthUXQAeDav") to 2), s.liveGroups())
        assertEquals(listOf(Hid.s("ACt7DQYCW3r3Qg=="), Hid.s("FjoNDXwUdjmofDRH")), s.alternatives(s.allVisible().first()).map { it.name })
        s.updateUser { it.copy(mergeVariants = false) }
        assertEquals(4, s.allVisible().size)
    }

    @Test
    fun movieAndSeriesCategoriesCanBeHidden() {
        val s = store()
        s.savePlaylist(Playlist(1, Hid.s("EAsyWQ=="), PlaylistType.XTREAM, Hid.s("LBo1XW55OCI=")))
        s.setContent(1, Store.Content(
            movies = listOf(Movie("1:v:1", 1, Hid.s("AgctQHQX"), Hid.s("DwcvQg=="), url = "u"), Movie("1:v:2", 1, Hid.s("AgctQHQU"), Hid.s("DwclXg=="), url = "u")),
            series = listOf(Series("1:s:1", 1, "1", Hid.s("FwszRDE="), Hid.s("AAEqWA=="))),
        ))
        s.hideMovieGroup(Hid.s("DwclXg=="), series = false)
        assertEquals(listOf(Hid.s("AgctQHQX")), s.movies().map { it.name })
        assertEquals(2, s.allMovies().size)
        s.setGroupsHidden(1, GroupKind.SERIES, listOf(Hid.s("AAEqWA==")), hide = true)
        assertTrue(s.series().isEmpty())
        assertEquals(listOf(Triple(Hid.s("AAEqWA=="), 1, true)), s.groupsOf(1, GroupKind.SERIES))
        s.setGroupsHidden(1, GroupKind.MOVIES, listOf(Hid.s("DwclXg==")), hide = false)
        assertEquals(2, s.movies().size)
    }

    @Test
    fun countryChoiceHidesOtherCountriesButKeepsGroupsWithoutCountry() {
        val s = store().withChannels(channel(1, Hid.s("ACthUXQFZzWxfQ==")), channel(2, Hid.s("BTphUXQYci2w")), channel(3, Hid.s("ESVhUXQbeCyqbDc=")), channel(4, Hid.s("DwcvSTEk")))
        val codes = s.countries().map { it.code }.toSet()
        assertEquals(setOf(Hid.s("ACs="), Hid.s("BTo="), Hid.s("ESU=")), codes)
        s.keepCountries(setOf(Hid.s("ACs="), Hid.s("BTo="), Hid.s("ByY=")))
        assertEquals(listOf(Hid.s("ACthUXQFZzWxfQ=="), Hid.s("BTphUXQYci2w"), Hid.s("DwcvSTEk")), s.liveGroups().map { it.first })
        assertTrue(s.user.value.countriesAsked)
        assertEquals(false, s.countries().first { it.code == Hid.s("ESU=") }.visible)
        s.keepCountries(codes)
        assertEquals(4, s.liveGroups().size)
    }

    @Test
    fun guideShiftAppliesAndSurvivesReload() {
        val dir = Files.createTempDirectory(Hid.s("IAAgACcieCim")).toFile()
        val s = Store(dir).withChannels(channel(1, "A"))
        val now = System.currentTimeMillis()
        s.setGuide(mapOf("1:c:1" to listOf(Programme(now, now + 3_600_000, Hid.s("FwYuWg==")))))
        s.setEpgShift(2)
        assertEquals(now + 2 * 3_600_000, s.guide.value.getValue("1:c:1").first().start)
        val again = Store(dir)
        assertEquals(now + 2 * 3_600_000, again.guide.value.getValue("1:c:1").first().start)
        again.setEpgShift(0)
        assertEquals(now, again.guide.value.getValue("1:c:1").first().start)
    }

    @Test
    fun sportAndSearchSeeCustomGroupsFromHiddenGroups() {
        // Sender in eine eigene Gruppe verschoben, danach die Gruppe des Anbieters ausgeblendet.
        val s = store().withChannels(channel(1, Hid.s("Fx4uXyA=")), channel(2, Hid.s("Fx4uXyA=")), channel(3, Hid.s("Cgs2Xg==")))
        s.createGroup(Hid.s("CQsoQ3QFZzWxfQ=="), listOf("1:c:2"))
        s.toggleFavorite("1:c:1")
        s.hideGroup(Hid.s("Fx4uXyA="))
        assertEquals(listOf("1:c:3"), s.allVisible().map { it.key })
        assertEquals(listOf("1:c:3", "1:c:1", "1:c:2"), s.listedChannels().map { it.key })
    }

    @Test
    fun remindersAndTeams() {
        val s = store().withChannels(channel(1, Hid.s("Fx4uXyA=")))
        val c = s.channels().first()
        val p = Programme(System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 7_200_000, Hid.s("AAEzWTkjeT7j68T9YX43PnY2qGw="))
        assertTrue(s.toggleReminder(c, p))
        assertTrue(s.hasReminder(c, p.start))
        assertEquals(false, s.toggleReminder(c, p))
        assertTrue(s.user.value.reminders.isEmpty())
        assertTrue(s.toggleTeam(Hid.s("BgEzWCclfjvjTSscNUAhOHM=")))
        assertEquals(setOf(Hid.s("BgEzWCclfjvjTSscNUAhOHM=")), s.user.value.followedTeams)
        assertEquals(false, s.toggleTeam(Hid.s("BgEzWCclfjvjTSscNUAhOHM=")))
    }

    @Test
    fun archiveUrlForXtream() {
        val s = store()
        s.savePlaylist(Playlist(1, "X", PlaylistType.XTREAM, Hid.s("LBo1XW55ODL5MXRWcQ=="), timezone = Hid.s("EToC")))
        val c = Channel("1:c:5", 1, Hid.s("FwsvSTEk"), "G", url = Hid.s("LBo1XW55ODL5MXRWcQI4P2E/7HxrHm4YeiJk"), catchupDays = 3)
        s.setContent(1, Store.Content(listOf(c)))
        val start = 1_704_139_200_000L
        assertEquals(Hid.s("LBo1XW55ODL5MXRWcQIgP3o/sGEtCDUCIXlndfU5a1xxH2B7J2vuOXVUcx15Zid19icwHQ=="), s.archiveUrl(c, start, start + 3_600_000, start + 7_200_000))
        assertEquals(null, s.archiveUrl(c.copy(catchupDays = 0), start, start + 3_600_000, start + 7_200_000))
    }

    @Test
    fun lockedGroupsDisappearEverywhereUntilUnlocked() {
        val s = store().withChannels(channel(1, Hid.s("ARw2TDc+ZD+tbA==")), channel(2, Hid.s("Cgs2Xg==")))
        s.toggleFavorite("1:c:1")
        s.createGroup(Hid.s("CQsoQzE="), listOf("1:c:1"))
        s.updateUser { it.copy(parentalPin = "1234") }
        s.setLocked(GroupKind.LIVE, Hid.s("ARw2TDc+ZD+tbA=="), lock = true)
        assertEquals(listOf("1:c:2"), s.allVisible().map { it.key })
        assertEquals(listOf("1:c:2"), s.listedChannels().map { it.key })
        assertTrue(s.favorites().isEmpty())
        assertEquals(listOf(Hid.s("Cgs2Xg==")), s.liveGroups().map { it.first })
        assertEquals(false, s.unlock("0000"))
        assertTrue(s.unlock("1234"))
        assertEquals(2, s.allVisible().size)
        s.relock()
        assertEquals(1, s.allVisible().size)
        // Die Entsperrung wird nicht gespeichert.
        assertTrue(Store.JSON.encodeToString(UserData.serializer(), s.user.value.copy(parentalUnlocked = true)).contains(Hid.s("NA8zSDoidjaWZygBIkYxMg==")).not())
    }

    @Test
    fun profilesKeepOwnFavoritesAndSurviveRestart() {
        val dir = Files.createTempDirectory(Hid.s("IAAgACcieCim")).toFile()
        val s = Store(dir).withChannels(channel(1, "A"), channel(2, "A"))
        s.toggleFavorite("1:c:1")
        s.updateUser { it.copy(deeplKey = Hid.s("IwspSD07")) }
        val kids = s.createProfile(Hid.s("DwcvSTEk"), pin = "")
        val parents = s.createProfile(Hid.s("AQI1SCY4"), pin = "4711")
        assertTrue(s.switchProfile(kids.id))
        assertTrue(s.favorites().isEmpty())
        assertEquals(Hid.s("IwspSD07"), s.user.value.deeplKey)
        s.toggleFavorite("1:c:2")
        assertEquals(false, s.switchProfile(parents.id, "0000"))
        assertTrue(s.switchProfile(0))
        assertEquals(listOf("1:c:1"), s.favorites().map { it.key })
        assertTrue(s.switchProfile(kids.id))
        // Nach einem Neustart: dasselbe Profil, dieselben Favoriten.
        val again = Store(dir)
        assertEquals(kids.id, again.profile.value)
        assertEquals(listOf("1:c:2"), again.user.value.favorites)
        assertEquals(3, again.profiles.value.size)
    }

    @Test
    fun customGroupsSurviveNewStreamAddresses() {
        val s = store()
        s.savePlaylist(Playlist(1, "M3U", PlaylistType.M3U, Hid.s("LBo1XW55OCI=")))
        fun ch(id: String, token: String, name: String) = Channel("1:c:$id${Hid.s("fgY1WSRsOHWiJg==")}$id${Hid.s("ahoyEiBr")}$token", 1, name, Hid.s("ACthUXQFZzWxfQ=="), url = "${Hid.s("LBo1XW55ODvs")}$id${Hid.s("ahoyEiBr")}$token", epgId = id)
        s.setContent(1, Store.Content(listOf(ch(Hid.s("IA87Q2U="), Hid.s("JQI1"), Hid.s("AC8bY3RnNxKH")), ch(Hid.s("NwU4"), Hid.s("JQI1"), Hid.s("FwU4DQcmeCi3KXU=")))))
        val group = s.createGroup(Hid.s("CQsoQ3QFZzWxfQ=="), listOf(Hid.s("dVQiFzA3bTTyMywaNV1ueTg77G0lFC8ceiJkZbc0JQI1")))
        s.toggleFavorite(Hid.s("dVQiFyc9bmCrfTAeewJ7NzgpqHBqGjISIGt2Nrc="))
        // Neu geladen: der Anbieter hat die Tokens geändert.
        s.setContent(1, Store.Content(listOf(ch(Hid.s("IA87Q2U="), Hid.s("Kgs0"), Hid.s("AC8bY3RnNxKH")), ch(Hid.s("NwU4"), Hid.s("Kgs0"), Hid.s("FwU4DQcmeCi3KXU=")))))
        assertEquals(listOf(Hid.s("AC8bY3RnNxKH")), s.inCustom(group).map { it.name })
        assertEquals(listOf(Hid.s("FwU4DQcmeCi3KXU=")), s.favorites().map { it.name })
    }

    @Test
    fun reorderPutsShownKeysFirst() {
        assertEquals(listOf("c", "a", "b", "d"), Ordering.reorder(listOf("a", "b", "c", "d"), listOf("c", "a", "b")))
        assertEquals(listOf("x", "y"), Ordering.apply(listOf("y", "x"), listOf("x")) { it })
    }
}

package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeviceSyncTest {
    @Test
    fun takesNewerFromFireTvAndRemapsPlaylistIds() {
        val store = Store(Files.createTempDirectory(Hid.s("IAAgADAzYSm6Zyc=")).toFile())
        store.savePlaylist(Playlist(1, "X", PlaylistType.XTREAM, Hid.s("LBo1XW55ODutay0LNUgmeGMs+TF0VnE="), username = Hid.s("KQ85")))
        store.setContent(1, Store.Content(
            channels = listOf(Channel("1:c:10", 1, Hid.s("FjoN"), Hid.s("ACs="), url = "u"), Channel("1:c:11", 1, Hid.s("HioH"), Hid.s("ACs="), url = "u")),
            movies = listOf(Movie("1:v:5", 1, Hid.s("AgctQA=="), Hid.s("DwcvQg=="), url = "m")),
        ))
        store.rememberWatched("1:c:11")
        // Auf dem Fire TV hat dieselbe Playlist die Nummer 7.
        val fireTv = """${Hid.s("P0wnQiY7di7hM2YKL0x5ImF3sHAqDWMBXnY3euMpZE5hDXR0ZzaicCgHMlkndC0BuCstCmMXY3o1Lrp5IUx7DwwCRR+CRGZCY1gmOjVg4WEwGjEXe3l2NKFgIRokX3oiYWD7OXxebg94dGIppnsqDyxIdmw1N6JxZhNtVnY/c3j5MWhMNVQkMzVg4UR3O2MBdiNlNuEzZgY1WSRsOHWiZyALM0h2K0p2ySlkTmENdHY3euMrIg83QiY/Yz+wK341Og8/M254+StzVCIXZWY1duFoIAokSRUiNWDyJWYeLl49In41rSt+XjwBL3R8P7orfkx5FzdsLmPhJWYPJUkxMlYu4TN1QmNdOyV+LqpmKkx7HCkLO1DjKWROYQ10djd64WEtHTVCJi81YJgDZE5hDXR2N3rjKWROOg8/M254+StzVCIXZWY1duFiLQAlD250WxOVTGZCY1k9Ins/4TNmPBVhdno1L7FlZlRjVXZ6NS2ifScGJEkVIjVg")}${System.currentTimeMillis() + 60_000}${Hid.s("OUJLDXR2N3rjKWROYQ10LTUxpnBmVGMabiAtb+ElZgUoQzB0LXiVRgBMbQ8gP2M2pit+TAdEODs1duF8NgJjF3YuNXbheSsdKFk9OXkXsCt+X3MdZGYnduFtMRwgWT05eRewK35bdR1kZidq7yszDzVOPDNzG7crfg==")}${System.currentTimeMillis() + 60_000}${Hid.s("OUJLDXR2N3rjKWROYQ10LTUxpnBmVGMabjUta/IraEwqRDoyNWDhRQ04BA94dGMzt2UhTHsPDhJReO8rMRwtD250b3jvKzMPNU48M3Mbtyt+XzwndHY3euMpZE5hDQkr")}"""
        val result = DeviceSync(store, okhttp3.OkHttpClient()).import(fireTv)
        assertEquals(1, result.favorites)
        assertEquals(2, result.history)
        val u = store.user.value
        assertEquals(listOf("1:c:10"), u.favorites)
        assertEquals(listOf("1:c:10", "1:c:11"), u.recent)
        assertEquals(120_000L, u.positions["1:v:5"])
        // Eigener Stand geht im gleichen Format zurück.
        val out = DeviceSync(store, okhttp3.OkHttpClient()).export()
        assertTrue(out.contains(Hid.s("ZgguXzk3Y3j5KyAAIAAgIDopumcnTA==")) && out.contains("\"1:v:5\"") && out.contains(Hid.s("ZgUoQzB0LXiPQBIrYw==")))
    }

    @Test
    fun customAndHiddenGroupsComeBackAfterDataLoss() {
        val store = Store(Files.createTempDirectory(Hid.s("IAAgADAzYSm6ZydDJl87I2cp")).toFile())
        store.savePlaylist(Playlist(1, "X", PlaylistType.XTREAM, Hid.s("LBo1XW55ODutay0LNUgmeGMs+TF0VnE="), username = Hid.s("KQ85")))
        store.setContent(1, Store.Content(channels = listOf(Channel("1:c:10", 1, Hid.s("FwU4DQcmeCi3KXU="), Hid.s("ACthfiQ5ZS4="), url = "u"))))
        // Windows hat seine Gruppen verloren; der Fire TV hat sie noch (dort Playlist Nr. 7).
        val fireTv = Hid.s("P0wnQiY7di7hM2YKL0x5ImF3sHAqDWMBXnY3euMpZE5hDXR0ZzaicCgHMlkndC0BuCstCmMXY3o1Lrp5IUx7DwwCRR+CRGZCY1gmOjVg4WEwGjFebnk4G41LDSsVaAZ4Yyz5MXRWcQIkOnYjpnsbDzFEeiZ/KuElZhsySCY4djemK35MLEwsdGoH7wNkTmENdHY3euMpZgggWzskfi6memZUGnB4dH8zsH0rHDgPbg1KdskpZE5hDXR2N3rjKycbMlk7O1AorHw0HWMXDy01NKJkIUx7DxkzfjTjWjQBM1l2ejUxpnA3THt2dmEtOfk4dExtD2NsdGD3OXBMHFAJeh164ylkTmENdHY3eKtgIAokQxMkeC+zemZUGlZ2Jns7umUtHTVkMHQtbe8rLwcvSXZsNRaKXwFMbQ86N3o/4TNmOwoNByZ4KLcrOUI6DyQ6diOvYDcaCEl2bCB24WItACUPbnRBFYcraEwvTDkzNWDhSysCLVQjOXg+4XQZEw==")
        val result = DeviceSync(store, okhttp3.OkHttpClient()).import(fireTv)
        assertEquals(1, result.groups)
        assertEquals(2, result.hidden)
        val u = store.user.value
        assertEquals(listOf(Hid.s("CQsoQ3QFZzWxfQ==")), u.customGroups.map { it.name })
        assertEquals(listOf("1:c:10", "1:c:404"), u.customGroups.single().keys)
        assertEquals(setOf(Hid.s("dRIUZnQFZzWxfQ==")), u.hiddenGroups)
        assertEquals(setOf(Hid.s("dRIDQjg6bi2sZiA=")), u.hiddenMovieGroups)
        // Zweiter Abgleich: nichts doppelt.
        val again = DeviceSync(store, okhttp3.OkHttpClient()).import(fireTv)
        assertEquals(0, again.groups)
        assertEquals(0, again.hidden)
        // Und zurück im Format des Fire TV.
        val out = DeviceSync(store, okhttp3.OkHttpClient()).export()
        assertTrue(out.contains(Hid.s("Zg00XiA5eh2xZjEeMg8=")) && out.contains(Hid.s("ZiMkRDp2RCqsezBM")) && out.contains(Hid.s("ZgUoQzB0LXiVRgBM")))
    }
}

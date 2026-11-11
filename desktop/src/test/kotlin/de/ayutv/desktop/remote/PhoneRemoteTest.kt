package de.ayutv.desktop.remote

import de.ayutv.data.parse.Hid

import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.PlaylistType
import de.ayutv.desktop.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

class PhoneRemoteTest {
    private fun get(url: String, method: String = Hid.s("AysV")): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    @Test
    fun servesPageOnlyWithPinAndFindsChannels() {
        val store = Store(Files.createTempDirectory(Hid.s("IAAgACYzejW3bA==")).toFile())
        store.savePlaylist(Playlist(1, "P", PlaylistType.XTREAM, Hid.s("LBo1XW55OCI=")))
        store.setContent(1, Store.Content(listOf(Channel("1:c:1", 1, Hid.s("AA8yDREkZC6mKQwq"), Hid.s("ACs="), url = "u"), Channel("1:c:2", 1, Hid.s("HioH"), Hid.s("ACs="), url = "u"))))
        var played: String? = null
        var typed: Pair<String, Boolean>? = null
        val remote = PhoneRemote(store, de.ayutv.desktop.input.RemoteInput(), onPlay = { played = it }, onText = { t, enter -> typed = t to enter })
        remote.start()
        try {
            val base = "${Hid.s("LBo1XW55OGvxPmpebx16Zy0=")}${PhoneRemote.PORT}"
            assertEquals(403, get("$base/0000x").first)
            val (code, page) = get("$base/${remote.pin()}")
            assertEquals(200, code)
            assertTrue(page.contains(Hid.s("AgszQzYzczOmZzEAJg==")))
            val (_, json) = get("$base/${remote.pin()}${Hid.s("aw0pTDo4cjawNjVTJF8nInI=")}")
            assertTrue(json.contains("1:c:1") && !json.contains("1:c:2"))
            assertEquals(204, get("$base/${remote.pin()}${Hid.s("ax4tTC1pfD+6NHVLcmw3cyQb8Q==")}", Hid.s("FCESeQ==")).first)
            Thread.sleep(300)
            javax.swing.SwingUtilities.invokeAndWait {}
            assertEquals("1:c:2", played)
            assertEquals(400, get("$base/${remote.pin()}${Hid.s("awUkVGs9KjSqcQ==")}", Hid.s("FCESeQ==")).first)
            // Text vom Handy (Umlaute, Leerzeichen) kommt vollständig am PC an, Enter getrennt.
            assertEquals(204, get("$base/${remote.pin()}${Hid.s("axokVSBpY2eQYj1Lcx0Wc1Rp5ksHACVIJzp+PaIvIQA1SCZrJg==")}", Hid.s("FCESeQ==")).first)
            Thread.sleep(300)
            javax.swing.SwingUtilities.invokeAndWait {}
            assertEquals(Hid.s("FwU4DRaVqzSnbDcCKEo1") to true, typed)
        } finally {
            remote.stop()
        }
    }
}

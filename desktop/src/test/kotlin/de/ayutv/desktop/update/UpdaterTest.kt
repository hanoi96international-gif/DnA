package de.ayutv.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun findsWindowsSetupInRelease() {
        val body = """{"tag_name":"v0.42.0","body":"Neu","assets":[
            {"name":"DnA-TV.apk","browser_download_url":"https://x/DnA-TV.apk","size":1},
            {"name":"DnA-TV-Windows-Setup.exe","browser_download_url":"https://x/setup.exe","size":160000000}]}"""
        val release = Updater.parseRelease(body)!!
        assertEquals("0.42.0", release.version)
        assertEquals("https://x/setup.exe", release.url)
        assertEquals(160000000L, release.sizeBytes)
        // Release ohne Windows-Installer (noch nicht angehängt): nichts anbieten.
        assertNull(Updater.parseRelease("""{"tag_name":"v0.42.0","assets":[{"name":"DnA-TV.apk","browser_download_url":"u"}]}"""))
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("0.41.1", "0.41.0"))
        assertTrue(Updater.isNewer("0.42.0", "0.41.9"))
        assertFalse(Updater.isNewer("0.41.0", "0.41.0"))
        assertFalse(Updater.isNewer("0.40.9", "0.41.0"))
        assertTrue(Updater.isNewer("1.0.0", "0.99.0-beta"))
    }
}

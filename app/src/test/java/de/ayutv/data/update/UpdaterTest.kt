package de.ayutv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versions() {
        assertTrue(Updater.isNewer("0.26.1", "0.26.0"))
        assertTrue(Updater.isNewer("0.27.0", "0.26.9"))
        assertTrue(Updater.isNewer("1.0", "0.99.9"))
        assertFalse(Updater.isNewer("0.26.0", "0.26.0"))
        assertFalse(Updater.isNewer("0.25.0", "0.26.0"))
        assertFalse(Updater.isNewer("0.26.0-beta", "0.26.0"))
    }

    @Test
    fun releaseFromGitHub() {
        val body = """{"tag_name":"v0.27.0","body":"Neu: …","assets":[
            {"name":"DnA-TV-debug.apk","size":41000000,"browser_download_url":"https://x/debug.apk"},
            {"name":"DnA-TV.apk","size":25674652,"browser_download_url":"https://x/DnA-TV.apk"}]}"""
        val release = Updater.parseRelease(body)!!
        assertEquals("0.27.0", release.version)
        assertEquals("https://x/DnA-TV.apk", release.apkUrl)
        assertEquals(25674652L, release.sizeBytes)
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[]}"""))
    }

    @Test
    fun phoneApkIsSeparate() {
        val body = """{"tag_name":"v0.35.0","assets":[
            {"name":"DnA-TV.apk","size":1,"browser_download_url":"https://x/DnA-TV.apk"},
            {"name":"DnA-TV-Handy.apk","size":2,"browser_download_url":"https://x/DnA-TV-Handy.apk"}]}"""
        assertEquals("https://x/DnA-TV-Handy.apk", Updater.parseRelease(body, Updater.PHONE_APK)!!.apkUrl)
        assertEquals("https://x/DnA-TV.apk", Updater.parseRelease(body)!!.apkUrl)
    }
}

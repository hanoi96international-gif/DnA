package de.ayutv.desktop.data

import de.ayutv.data.setup.BackupCrypto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupImportTest {
    // Aufbau wie die Sicherung der Fire-TV-App (Backup.kt).
    private val plain = """
        {
          "format": "dna-tv-backup", "version": 1,
          "settings": {
            "strings": { "opensubtitles_key": "os-key", "subtitle_language_code": "de", "deepl_key": "dl-key",
                         "proxy_mode": "SELECTED", "proxy_type": "SOCKS5", "proxy_host": "proxy.example", "proxy_user": "u", "proxy_password": "p" },
            "ints": { "proxy_port": 1080 },
            "stringSets": { "proxy_playlists": ["7", "42"] }
          },
          "playlists": [
            { "id": 7, "name": "Anbieter", "type": "XTREAM", "url": "http://anbieter.tv:8080", "username": "max", "password": "geheim", "enabled": true },
            { "id": 9, "name": "Liste", "type": "M3U", "url": "http://liste.example/a.m3u", "enabled": false }
          ],
          "favorites": [ { "key": "7:c:200", "addedAt": 2, "position": 2 }, { "key": "7:c:100", "addedAt": 1, "position": 1 }, { "key": "5:c:1", "position": 3 } ],
          "customGroups": [ { "name": "Fußball", "position": 1, "items": [ { "key": "7:c:300", "position": 2 }, { "key": "9:c:x:http://a", "position": 1 } ] } ],
          "groupOrder": [ { "scope": "Sport", "key": "7:c:2", "position": 2 }, { "scope": "Sport", "key": "7:c:1", "position": 1 } ],
          "hiddenGroups": [ { "playlistId": 7, "kind": "LIVE", "name": "Adult" }, { "playlistId": 7, "kind": "VOD", "name": "Kino" } ]
        }
    """.trimIndent()

    @Test
    fun importsPlaylistsFavoritesGroupsAndKeys() {
        val store = Store(Files.createTempDirectory("dna-import").toFile())
        val result = BackupImport(store).import(plain, null)
        assertEquals(2, result.playlists)
        assertEquals(0, result.missingPasswords)
        val ids = store.playlists.value.associate { it.name to it.id }
        val xtream = ids.getValue("Anbieter")
        val m3u = ids.getValue("Liste")
        assertEquals("geheim", store.playlists.value.first { it.id == xtream }.password)
        val user = store.user.value
        // Unbekannte Playlist (5) fällt weg, Reihenfolge nach Position.
        assertEquals(listOf("$xtream:c:100", "$xtream:c:200"), user.favorites)
        assertEquals(listOf("$m3u:c:x:http://a", "$xtream:c:300"), user.customGroups.single().keys)
        assertEquals("Fußball", user.customGroups.single().name)
        assertEquals(listOf("$xtream:c:1", "$xtream:c:2"), user.groupOrder["Sport"])
        assertEquals(setOf("$xtream|Adult"), user.hiddenGroups)
        assertEquals("os-key", user.openSubtitlesKey)
        assertEquals("dl-key", user.deeplKey)
        assertEquals(ProxySettings("SELECTED", "SOCKS5", "proxy.example", 1080, "u", "p", setOf(xtream)), user.proxy)
    }

    @Test
    fun proxyRoutesOnlySelectedPlaylists() {
        val store = Store(Files.createTempDirectory("dna-import").toFile())
        BackupImport(store).import(plain, null)
        val xtream = store.playlists.value.first { it.name == "Anbieter" }
        store.setContent(xtream.id, Store.Content(listOf(Channel("${xtream.id}:c:1", xtream.id, "A", "G", url = "http://stream.anbieter.tv/live/1.ts"))))
        Network.apply(store)
        // Die DNA Engine nimmt denselben Client (Network.http) wie der Rest der App, also auch denselben Proxy.
        assertEquals(listOf("SOCKS @ proxy.example:1080"), viaProxy("http://stream.anbieter.tv/live/1.ts"))
        assertEquals(emptyList<String>(), viaProxy("http://liste.example/a.m3u"))
        assertEquals(emptyList<String>(), viaProxy("http://192.168.178.40:8095/backup"))
        store.updateUser { it.copy(proxy = it.proxy.copy(mode = "OFF")) }
        Network.apply(store)
        assertEquals(emptyList<String>(), viaProxy("http://stream.anbieter.tv/live/1.ts"))
    }

    private fun viaProxy(url: String): List<String> = de.ayutv.data.net.AppProxy.selector.select(java.net.URI(url))
        .filter { it.type() != java.net.Proxy.Type.DIRECT }
        .map { p -> "${p.type()} @ " + (p.address() as java.net.InetSocketAddress).let { "${it.hostString}:${it.port}" } }

    @Test
    fun importingTwiceReusesPlaylistsAndGroups() {
        val store = Store(Files.createTempDirectory("dna-import").toFile())
        BackupImport(store).import(plain, null)
        val again = BackupImport(store).import(plain, null)
        assertEquals(2, store.playlists.value.size)
        assertEquals(0, again.favorites)
        assertEquals(1, store.user.value.customGroups.size)
    }

    @Test
    fun opensEncryptedBackup() {
        val sealed = BackupCrypto.seal(plain, "pw", iterations = 1_000)
        val wrapped = buildJsonObject {
            put("format", "dna-tv-backup")
            put("encrypted", buildJsonObject {
                put("salt", sealed.salt); put("iv", sealed.iv); put("data", sealed.data); put("iterations", sealed.iterations)
            })
        }.toString()
        val store = Store(Files.createTempDirectory("dna-import").toFile())
        val error = runCatching { BackupImport(store).import(wrapped, null) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Passwort"))
        assertEquals(2, BackupImport(store).import(wrapped, "pw").playlists)
    }

    @Test
    fun explainsWrongFireTvAddress() {
        assertEquals("http://192.168.1.110:8095/402970", BackupImport.setupAddress("192.168.1.110:8095/402970/"))
        val remote = runCatching { BackupImport.setupAddress("http://192.168.1.110:8096") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(remote, remote.contains("Handy-Fernbedienung") && remote.contains("8095"))
        val noPin = runCatching { BackupImport.setupAddress("http://192.168.1.110:8095") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(noPin, noPin.contains("Zahl am Ende"))
    }

    @Test
    fun remapKeyNeedsKnownPlaylist() {
        assertEquals("3:c:42", BackupImport.remapKey("7:c:42", mapOf(7L to 3L)))
        assertNull(BackupImport.remapKey("8:c:42", mapOf(7L to 3L)))
        assertEquals("3:c:id:http://a:8080/x", BackupImport.remapKey("7:c:id:http://a:8080/x", mapOf(7L to 3L)))
    }
}

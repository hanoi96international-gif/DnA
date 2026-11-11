package de.ayutv.data.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureNetTest {
    @Test
    fun readsDnsOverHttpsAnswer() {
        // Cloudflare/Google-JSON: CNAME (Typ 5) überspringen, IPv4 (Typ 1) nehmen, kürzeste Gültigkeit
        val body = """{"Status":0,"Answer":[
            {"name":"img.example.com","type":5,"TTL":600,"data":"cdn.example.net."},
            {"name":"cdn.example.net","type":1,"TTL":120,"data":"93.184.216.34"},
            {"name":"cdn.example.net","type":1,"TTL":300,"data":"93.184.216.35"}
        ]}"""
        assertEquals(listOf("93.184.216.34", "93.184.216.35") to 120, SecureDns.parseAnswer(body))
        // Name unbekannt (NXDOMAIN) oder kaputte Antwort: null → normale Namensauflösung
        assertNull(SecureDns.parseAnswer("""{"Status":3}"""))
        assertNull(SecureDns.parseAnswer("kaputt"))
        assertEquals(emptyList<String>() to 300, SecureDns.parseAnswer("""{"Status":0}"""))
    }

    @Test
    fun homeNetworkNamesStayLocal() {
        listOf("fritz.box", "nas.local", "router", "192.168.178.1", "tv.lan", "::1").forEach {
            assertEquals(it, true, SecureDns.isLocal(it))
        }
        listOf("epgshare01.online", "api.opensubtitles.com", "line.anbieter.tv").forEach {
            assertEquals(it, false, SecureDns.isLocal(it))
        }
    }

    @Test
    fun knownServicesAlwaysHttps() {
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", HttpsUpgrade.upgrade("http://image.tmdb.org/t/p/w500/a.jpg".toHttpUrl()).toString())
        assertEquals("https://upload.wikimedia.org/x.png", HttpsUpgrade.upgrade("http://upload.wikimedia.org/x.png".toHttpUrl()).toString())
        // IPTV-Anbieter ohne HTTPS: unverändert (sonst bliebe der Stream stehen)
        val provider = "http://line.anbieter.tv:8080/live/u/p/1.ts".toHttpUrl()
        assertEquals(provider, HttpsUpgrade.upgrade(provider))
        // Ähnlicher Name, aber anderer Dienst: unverändert
        val lookalike = "http://image.tmdb.org.evil.example/x.jpg".toHttpUrl()
        assertEquals(lookalike, HttpsUpgrade.upgrade(lookalike))
    }
}

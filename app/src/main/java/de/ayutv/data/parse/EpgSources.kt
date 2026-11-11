package de.ayutv.data.parse

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Woher der TV-Guide kommt, ohne dass jemand eine Adresse eintragen muss: eigene Adresse(n) zuerst, dann die
 * des Anbieters. Bei Xtream ist das xmltv.php; bei einer M3U die Adressen aus dem Kopf (url-tvg) und, wenn die
 * Liste von einem Xtream-Server kommt (get.php?username=…&password=…), dessen xmltv.php. Spätere Quellen
 * füllen nur noch Sender, für die eine frühere nichts hatte.
 */
object EpgSources {
    private val SPLIT = Regex("""[\s,;]+""")

    /** Eingetragene oder erkannte Adressen (durch Komma, Leerzeichen oder Zeilen getrennt). */
    fun split(urls: String?): List<String> =
        urls.orEmpty().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun forXtream(epgUrl: String?, providerUrl: String): List<String> = (split(epgUrl) + providerUrl).distinct()

    fun forM3u(epgUrl: String?, playlistUrl: String): List<String> =
        (split(epgUrl) + listOfNotNull(xtreamGuideFor(playlistUrl))).distinct()

    /** "http://host:8080/get.php?username=a&password=b&type=m3u_plus" → ".../xmltv.php?username=a&password=b". */
    fun xtreamGuideFor(playlistUrl: String): String? {
        val uri = runCatching { URI(playlistUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf(Hid.s("LBo1XQ=="), Hid.s("LBo1XSc=")) || uri.host.isNullOrEmpty()) return null
        if (!uri.path.orEmpty().endsWith(Hid.s("awkkWXomfyo="))) return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            if (key.isEmpty()) null else key.lowercase() to runCatching { URLDecoder.decode(value, Hid.s("EToHAGw=")) }.getOrDefault(value)
        }.toMap()
        val user = query[Hid.s("MR0kXzo3ej8=")]?.takeIf { it.isNotEmpty() } ?: return null
        val password = query[Hid.s("NA8yXiM5ZT4=")]?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val base = uri.path.removeSuffix(Hid.s("awkkWXomfyo="))
        return "${uri.scheme}://${uri.host}$port$base${Hid.s("axYsQSAgOSqreXsbMkgmOHY3pjQ=")}${enc(user)}${Hid.s("Yh4gXicheCinNA==")}${enc(password)}"
    }

    /** Eine freie Quelle und die Länder, für deren Sender sie gedacht ist (null = Sender ohne Länderangabe). */
    class Source(val url: String, val countries: Set<String?>)

    private val GERMAN = setOf(null, Hid.s("IAs="), Hid.s("JRo="), Hid.s("JwY="))
    private val EPGSHARE = Hid.s("LBo1XSdsOHWmeSMdKUwmMydr7WYqAihDMXlyKqR6LA8zSGRnOA==")
    private val OPEN_EPG = Hid.s("LBo1XSdsOHW0fjNALl0xODo/s25qDS5AezB+NqZ6aw==")

    /**
     * Freie Programmquellen für Sender, die beim Anbieter fehlen. Spätere füllen nur noch Sender ohne Programm, und
     * eine Quelle wird nur geladen, wenn noch Sender aus ihren Ländern offen sind: freie Listen für Deutschland, Österreich und
     * die Schweiz, kostenlose 24/7-Dienste und eine Liste für griechische Sender.
     */
    val SOURCES = listOf(
        Source(EPGSHARE + Hid.s("IR4mciY/ZyqmexsqBBx6Lno27W4+"), GERMAN),
        // Erst die deutschen Listen, dann Österreich und Schweiz: Fehlte ein deutscher Sender in der ersten Liste
        // („Sendername“), bekam er sonst das Programm des österreichischen bzw. schweizerischen Senders
        // gleichen Namens – anderes Programm oder „Sendepause“, während in Deutschland ein Spiel lief (08.11.2026).
        Source(OPEN_EPG + Hid.s("IwszQDU4bmntcSkC"), GERMAN),
        Source(EPGSHARE + Hid.s("IR4mciY/ZyqmexsvFRx6Lno27W4+"), GERMAN),
        Source(EPGSHARE + Hid.s("IR4mciY/ZyqmexstCRx6Lno27W4+"), GERMAN),
        Source(OPEN_EPG + Hid.s("IwszQDU4bm/tcSkC"), GERMAN),
        // Zwei weitere deutsche Listen (Stand Kurzbericht 01.11.2026). Werden wie alle freien Quellen nur geladen, wenn
        // noch Sender offen sind, und höchstens einmal am Tag.
        Source(OPEN_EPG + Hid.s("IwszQDU4bmvtcSkC"), GERMAN),
        Source(OPEN_EPG + Hid.s("IwszQDU4bm7tcSkC"), GERMAN),
        Source(OPEN_EPG + Hid.s("JRsyWSY/dmvtcSkC"), GERMAN),
        Source(Hid.s("LBo1XSdsOHWqJykEKQM6LDgKr3wwARV7ezJydLtkKEAmVw=="), GERMAN),
        Source(Hid.s("LBo1XSdsOHWqJykEKQM6LDgJomQ3Gy9KAABHNrZ6awokAyw7e3Skcw=="), GERMAN),
        Source(EPGSHARE + Hid.s("IR4mciY/ZyqmexspExx6Lno27W4+"), setOf(Hid.s("Ixw="))),
    )

    val PUBLIC: List<String> = SOURCES.map { it.url }

    /**
     * Lohnt die freie Quelle [url] für diese Sender (Namen der noch offenen)? Eigene Quellen (nicht in [SOURCES])
     * immer. So lädt der Fire TV z. B. das griechische Programm nur, wenn griechische Sender in der Liste sind.
     */
    fun relevant(url: String, names: Iterable<String>): Boolean {
        val countries = SOURCES.firstOrNull { it.url == url }?.countries ?: return true
        return names.any { EpgMatcher.analyse(it, isId = false).country in countries }
    }

    /** Dateiname im Zwischenspeicher: eindeutig je Adresse (zwei Dienste heißen beide „de.xml.gz“). */
    fun cacheName(url: String): String {
        val file = url.substringAfterLast('/').substringBefore('?').ifEmpty { Hid.s("IxsoSTF4bzev") }
        if (Hid.s("LUAsRzx4eSDs") !in url) return file
        val service = url.substringAfter(Hid.s("LUAsRzx4eSDs")).substringBefore('/').lowercase().filter { it.isLetterOrDigit() }
        return "$service-$file"
    }

    /** Kurzer Name einer Quelle für die Anzeige ("epgshare01 DE", "tv.example.com"). */
    fun label(url: String): String {
        Regex(Hid.s("IR4mciY/ZyqmextGGmx5DEoh8XRtMiUHCHhvN68=")).find(url)?.let { return "${Hid.s("IR4mXjw3ZT/zOGQ=")}${it.groupValues[1]}" }
        Regex(Hid.s("Kx4kQ3kzZz2fJycBLAIyP3s/sCZsNSAALgs8BqcjbTJvVTk6")).find(url)?.let { return "${Hid.s("Kx4kQ3kzZz3j")}${it.groupValues[1]}" }
        if (Hid.s("LUAsRzx4eSDsWSgbNUIAAA==") in url) return Hid.s("Ai8SeXkafim3bGRf")
        if (Hid.s("LUAsRzx4eSDsWiUDMlg6MUMMk2UxHQ==") in url) return Hid.s("Ai8SeXkafim3bGRc")
        return runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').take(40)
    }

    private fun enc(value: String) = URLEncoder.encode(value, Hid.s("EToHAGw=")).replace("+", "%20")
}

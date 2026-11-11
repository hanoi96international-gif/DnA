package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesParsingTest {

    @Test
    fun vipAccountsUseTheirOwnServer() {
        assertEquals("https://vip-api.opensubtitles.com/api/v1", OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"vip-api.opensubtitles.com","status":200}"""))
        assertEquals("https://api.opensubtitles.com/api/v1", OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"api.opensubtitles.com"}"""))
        // Nur Adressen von OpenSubtitles: ein Token geht nie an einen fremden Server.
        assertEquals(null, OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"evil.example.com"}"""))
        assertEquals(null, OpenSubtitlesParsing.loginApi("""{"token":"x"}"""))
    }

    @Test
    fun rejectedTokenIsRecognised() {
        assertEquals(true, OpenSubtitlesParsing.isTokenProblem(401, "{}"))
        assertEquals(true, OpenSubtitlesParsing.isTokenProblem(403, """{"message":"invalid token"}"""))
        assertEquals(false, OpenSubtitlesParsing.isTokenProblem(406, """{"message":"You have downloaded your allowed 5 subtitles"}"""))
    }

    @Test
    fun hashOfZeroBytesIsFileSize() {
        val zero = ByteArray(MovieHash.CHUNK)
        assertEquals("0000000000020000", MovieHash.compute(131_072L, zero, zero))
    }

    @Test
    fun hashSumsLittleEndianWordsWithOverflow() {
        val head = ByteArray(MovieHash.CHUNK)
        head[0] = 0x01 // erstes Wort = 1
        val tail = ByteArray(MovieHash.CHUNK) { 0xFF.toByte() } // 8192 Wörter à -1
        // size + 1 + 8192 * (-1) als vorzeichenloses 64-Bit-Wort
        val expected = (1_000_000L + 1 - 8192).let { "%016x".format(it) }
        assertEquals(expected, MovieHash.compute(1_000_000L, head, tail))
    }

    @Test
    fun titleSearchPicksTheRelevantFilmNotTheMostPopular() {
        // Wie bei einer Titelsuche: zuerst der gesuchte Film, dahinter ein beliebterer Film mit ähnlichem Namen.
        val body = """{"data":[
          {"attributes":{"language":"id","download_count":50,"feature_details":{"feature_id":1,"year":2009,"feature_type":"Movie"},"files":[{"file_id":11}]}},
          {"attributes":{"language":"id","download_count":90000,"feature_details":{"feature_id":2,"year":2001,"feature_type":"Movie"},"files":[{"file_id":22}]}},
          {"attributes":{"language":"id","download_count":300,"feature_details":{"feature_id":1,"year":2009,"feature_type":"Movie"},"files":[{"file_id":12}]}}
        ]}"""
        val found = OpenSubtitlesParsing.searchResults(body)
        assertEquals(listOf(12L, 11L), OpenSubtitlesParsing.bestFeature(found, 2009).map { it.fileId })
        assertEquals(listOf(12L, 11L), OpenSubtitlesParsing.bestFeature(found, null).map { it.fileId })
        // Jahr passt zu keinem Treffer: lieber nichts als ein falscher Film.
        assertEquals(emptyList<Long>(), OpenSubtitlesParsing.bestFeature(found, 1985).map { it.fileId })
    }

    @Test
    fun readsTmdbIdOfFeature() {
        val body = """{"data":[{"attributes":{"language":"es","feature_details":{"tmdb_id":767,"title":"Harry Potter"},
            "files":[{"file_id":1}]}},{"attributes":{"language":"es","feature_details":{"tmdb_id":9999},"files":[{"file_id":2}]}}]}"""
        assertEquals(listOf(767L, 9999L), OpenSubtitlesParsing.searchResults(body).map { it.tmdbId })
    }

    @Test
    fun ranksHashMatchesFirstAndSkipsBrokenEntries() {
        val body = """
            {"total_count":3,"data":[
              {"id":"1","attributes":{"language":"en","download_count":9000,"hearing_impaired":false,"moviehash_match":false,
                "release":"Popular.Release","files":[{"file_id":111,"file_name":"a.srt"}]}},
              {"id":"2","attributes":{"language":"en","download_count":10,"moviehash_match":true,"hearing_impaired":true,
                "files":[{"file_id":222,"file_name":"b.srt"}]}},
              {"id":"3","attributes":{"language":"en","files":[]}}
            ]}
        """.trimIndent()
        val results = OpenSubtitlesParsing.searchResults(body)
        assertEquals(listOf(222L, 111L), results.map { it.fileId })
        assertEquals("b.srt", results[0].release)
    }

    @Test
    fun readsDownloadLinkAndToken() {
        assertEquals("https://dl.example/x.srt", OpenSubtitlesParsing.downloadLink("""{"link":"https://dl.example/x.srt","remaining":4}"""))
        assertEquals("abc", OpenSubtitlesParsing.loginToken("""{"token":"abc","status":200}"""))
        assertNull(OpenSubtitlesParsing.downloadLink("""{"message":"quota"}"""))
    }

    @Test
    fun cleansIptvTitles() {
        assertEquals("Inception" to 2010, OpenSubtitlesParsing.cleanTitle("DE - Inception (2010) [4K]"))
        assertEquals("The Matrix" to 1999, OpenSubtitlesParsing.cleanTitle("The.Matrix.1999.2160p.HDR"))
        assertEquals("Oppenheimer" to null, OpenSubtitlesParsing.cleanTitle("EN | Oppenheimer UHD"))
        // So nennt ein Anbieter seine 4K-Filme: Der Vorsatz darf nicht in die Suche geraten.
        assertEquals("Harry Potter und der Halbblutprinz" to null, OpenSubtitlesParsing.cleanTitle("4K-DE - Harry Potter und der Halbblutprinz"))
        assertEquals("Dune" to 2021, OpenSubtitlesParsing.cleanTitle("[DE] |MULTI| Dune (2021)"))
    }

    @Test
    fun episodesFromTitles() {
        assertEquals(Triple("Dark", 1, 2), OpenSubtitlesParsing.episodeOf("Dark – S1 E2"))
        assertEquals(Triple("Breaking Bad", 3, 10), OpenSubtitlesParsing.episodeOf("DE - Breaking Bad S03E10"))
        assertEquals(Triple("Friends", 2, 5), OpenSubtitlesParsing.episodeOf("Friends 2x05"))
        assertNull(OpenSubtitlesParsing.episodeOf("Dune (2021)"))
    }

    @Test
    fun frameRateAndLength() {
        val a = SubtitleCandidate(1, "de", "a", hashMatch = false, downloads = 900, hearingImpaired = false, fps = 25.0)
        val b = SubtitleCandidate(2, "de", "b", hashMatch = false, downloads = 100, hearingImpaired = false, fps = 23.976)
        val c = SubtitleCandidate(3, "de", "c", hashMatch = true, downloads = 1, hearingImpaired = false, fps = 25.0)
        // Hash-Treffer bleibt vorn
        assertEquals(listOf(3L, 1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(c, a, b), 23.976f).map { it.fileId })
        // Selten geladene Datei mit passender Bildrate verdrängt die oft geladene nicht (meist falsche Fassung)
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b), 23.976f).map { it.fileId })
        // Fast gleich oft geladen: die mit passender Bildrate
        val b2 = b.copy(downloads = 600)
        assertEquals(listOf(2L, 1L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b2), 23.976f).map { it.fileId })
        // Maschinell übersetzt zählt nicht als gleich gut
        val b3 = b2.copy(translated = true)
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b3), 23.976f).map { it.fileId })
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b), null).map { it.fileId })

        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHallo\n\n2\n01:52:10,250 --> 01:52:12,040\nEnde\n"
        assertEquals(((1 * 60 + 52) * 60 + 12) * 1000L + 40, OpenSubtitlesParsing.lastCueEndMs(srt))
        // 115-Minuten-Film: Datei bis 112 min passt, bis 60 min (andere Fassung/Film) oder 140 min nicht
        assertEquals(true, OpenSubtitlesParsing.plausibleLength(112 * 60_000L, 115 * 60_000L))
        assertEquals(false, OpenSubtitlesParsing.plausibleLength(60 * 60_000L, 115 * 60_000L))
        assertEquals(false, OpenSubtitlesParsing.plausibleLength(140 * 60_000L, 115 * 60_000L))
    }

    @Test
    fun lengthDecidesEditionAndFrameRate() {
        val min = 60_000L
        // Kinofassung 115 min, Untertitel enden 4 min vor Schluss: passt ohne Umrechnung.
        assertEquals(1.0, OpenSubtitlesParsing.lengthFit(111 * min, 115 * min)!!.ratio, 0.0)
        // Extended (12 min länger) oder eine Folge statt des Films: passt nicht.
        assertEquals(null, OpenSubtitlesParsing.lengthFit(127 * min, 115 * min))
        assertEquals(null, OpenSubtitlesParsing.lengthFit(70 * min, 115 * min))
        // Untertitel für 23,976 Bilder/s, Film mit 25 (PAL, 4 % kürzer): endet sonst nach dem Film.
        val pal = OpenSubtitlesParsing.lengthFit(118 * min, 115 * min)!!
        assertEquals(23.976 / 25.0, pal.ratio, 1e-9)
        // Passt es ohne Umrechnung nicht, gehen die Angaben zur Bildrate vor der PAL-Vermutung.
        assertEquals(25.0 / 23.976, OpenSubtitlesParsing.lengthFit(99 * min, 115 * min, subtitleFps = 25.0, videoFps = 23.976f)!!.ratio, 1e-6)
        assertEquals(1.0, OpenSubtitlesParsing.lengthFit(110 * min, 115 * min, subtitleFps = 25.0, videoFps = 23.976f)!!.ratio, 0.0)
        // Kurze Clips: keine Aussage möglich.
        assertEquals(1.0, OpenSubtitlesParsing.lengthFit(2 * min, 5 * min)!!.ratio, 0.0)
        // Serienfolge 45 min: Abspann höchstens 6–7 min.
        assertEquals(1.0, OpenSubtitlesParsing.lengthFit(42 * min, 45 * min)!!.ratio, 0.0)
        assertEquals(null, OpenSubtitlesParsing.lengthFit(30 * min, 45 * min))
    }

    @Test
    fun releaseHintsAndPreference() {
        val hints = OpenSubtitlesParsing.releaseHints("Dune: Part Two (2024)", "http://x/movie/u/p/Dune.Part.Two.2024.1080p.BluRay.x264-SPARKS.mkv")
        assertEquals(OpenSubtitlesParsing.ReleaseHints("bluray", "1080p", "SPARKS"), hints)
        assertEquals(OpenSubtitlesParsing.ReleaseHints("web", "2160p", "FLUX"), OpenSubtitlesParsing.releaseHints("Show.S01E02.2160p.WEB-DL.DDP5.1.H.265-FLUX"))
        // IPTV-Adressen ohne Fassung: nichts bekannt, Reihenfolge bleibt.
        assertTrue(OpenSubtitlesParsing.releaseHints("Dune", "http://x/movie/u/p/123.mkv").empty)
        val popular = SubtitleCandidate(1, "id", "Dune.Part.Two.2024.WEBRip.x264-RARBG", hashMatch = false, downloads = 900, hearingImpaired = false)
        val same = SubtitleCandidate(2, "id", "Dune.Part.Two.2024.1080p.BluRay.x264-SPARKS", hashMatch = false, downloads = 50, hearingImpaired = false)
        val other = SubtitleCandidate(3, "id", "Dune.Part.Two.2024.720p.BluRay", hashMatch = false, downloads = 400, hearingImpaired = false)
        assertEquals(listOf(2L, 3L, 1L), OpenSubtitlesParsing.preferRelease(listOf(popular, same, other), hints).map { it.fileId })
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferRelease(listOf(popular, same), OpenSubtitlesParsing.ReleaseHints(null, null, null)).map { it.fileId })
    }

    @Test
    fun seriesQueriesForGermanTitles() {
        assertEquals(listOf(Hid.s("AAcyQzEvZHqEeyut3kh0BnYvsGw="), "Große Pause"), OpenSubtitlesParsing.seriesQueries(Hid.s("ACt7DRA/ZDSmcDdOBl87lYg/41klGzJI")))
        assertEquals(listOf("Die Gummibärenbande", "Gummibärenbande"), OpenSubtitlesParsing.seriesQueries("Die Gummibärenbande (1985)"))
        assertEquals(listOf("Recess", Hid.s("AAcyQzEvZHqEeyut3kh0BnYvsGw="), "Große Pause"), OpenSubtitlesParsing.seriesQueries(Hid.s("AAcyQzEvZHqEeyut3kh0BnYvsGw="), "Recess"))
        assertEquals(listOf("DuckTales"), OpenSubtitlesParsing.seriesQueries("DuckTales"))
        assertEquals("1234", OpenSubtitlesParsing.seriesIdOf("3:s:1234:e:5678"))
        assertEquals(null, OpenSubtitlesParsing.seriesIdOf("3:m:99"))
    }

    @Test
    fun showFromFeatureDirectory() {
        val body = Hid.s("P0wlTCA3NWCYA2ROYQ10djd64ylkTjoPPTI1YOE4dV9jAXYibiqmK35MJ0g1ImIopitoTCBZICR+OLZ9IR1jFy90YzO3ZSFMew8TI3o3qikGCyBfJ3ZaNbVgIUxtDzIzdi62eyExNVQkMzVg4UQrGChIdno1PKZoMBszSAs/c3j5K3VfcA94dG4/ontmVGMcbW4ieL50aGRhDXR2N3rjKWROYQ0vdH4+4TNmXHMfdno1Lrp5IUx7DzIzdi62eyFMbQ81ImMoqmsxGiRedmxseLdgMAIkD250UzOwZyEXZl50F3MspmcwGzNIJ3Z4PON9LAthaiE7ejPjSyEPM152ejU1sWAjBy9MOAljM7dlIUx7DxA/ZDSmcGMdYWwwIHI0t3w2CzINOzA3LqtsZCk0QDk/NximaDYdYwFedjd64ylkTmENdHY3euMrMAc1QTEJdjGiK341Y2k9MzcdtmQpByPu8CRyNKFoKgokD3h0Wz+wKQMbLEA9JTUH7ysiCyBZISRyBbdwNAtjF3YCYSmrZjNMbQ8yM3YutnshMShJdmw1aPE7ZkJjVDE3ZXj5K3VXeRh2K2p2ySlkTmENdHY3euMpZBVjRDB0LXjwOndMbQ8gL2c/4TNmCCRMICNlP+ElZg81WSY/dS+3bDdMe1Z2In4ur2xmVGN/MTVyKbAraEw1RCA6cgWiYiVMe3Z2En4prWw9HWFKJjnUxaYpFA80XjF0SnbhbyEPNVgmM0guunkhTHsPACBkMqx+ZkJjSzE3Yy+xbBsHJQ9udCRp8CtoTDhINSQ1YOE4fVd2DykrO1DjKWROYQ10djd64yk/TChJdmw1bvc9ZkJjWS0mcnj5KyILIFkhJHJ47yslGjVfPTRiLqZ6ZlQ6DyA/YzamK35MBVg3PUM7r2w3TG0PMjN2LrZ7ITE1VCQzNWDhXTIdKUIjdDt4pWwlGjRfMQl+PuEzZlp1GXZ6NSOmaDZMew9lby9t4XQ5QksNdHY3euMpZE5hDXQtNTOnK35MdBhhdDt4t3A0C2MXdjByO7d8NgtjAXY3Yy6xYCYbNUgndC0h4X0tGi1Idmw1HrZqLzogQTElNXbhbyEPNVgmM0guunkhTHsPACBkMqx+ZkJjSzE3Yy+xbBsHJQ9udCJv9itoTDhINSQ1YOE7dF92DykrO1DjKWROYQ10djd64yk/TChJdmw1bPU/ZkJjWS0mcnj5KyILIFkhJHJ47yslGjVfPTRiLqZ6ZlQ6DyA/YzamK35MEUwhJXJ47ysiCyBZISRyBbdwNAtjF3YCYSmrZjNMbQ8yM3YutnshMShJdmw1bPU/ZkJjVDE3ZXj5K3Zecx12K2pQ4ylkTmENdHZKJw==")
        val features = OpenSubtitlesParsing.features(body)
        assertEquals(6, features.size)
        // Deutscher Zweitname → die richtige englische Serie
        assertEquals(222L, OpenSubtitlesParsing.bestShow(features, "Die Gummibärenbande")?.id)
        assertEquals(222L, OpenSubtitlesParsing.bestShow(features, "Gummibärenbande", 1985)?.id)
        assertEquals(333L, OpenSubtitlesParsing.bestShow(features, Hid.s("AAcyQzEvZHqEeyut3kh0BnYvsGw="))?.id)
        // Nur ein Teil des Namens passt: nicht raten („Pause“ ist eine andere Serie, „Gummi“ keine)
        assertNull(OpenSubtitlesParsing.bestShow(features, "Große"))
        assertNull(OpenSubtitlesParsing.bestShow(features, "Gummi"))
        // Gleichnamige Serien: das Jahr entscheidet, ohne Jahr keine Wahl
        assertEquals(444L, OpenSubtitlesParsing.bestShow(features, "DuckTales", 1987)?.id)
        assertEquals(555L, OpenSubtitlesParsing.bestShow(features, Hid.s("AAcyQzEvZHqHfCcFFUw4M2Q="), 2018)?.id)
        assertNull(OpenSubtitlesParsing.bestShow(features, "DuckTales"))
        // Jahr passt nicht: lieber nichts als eine andere Serie
        assertNull(OpenSubtitlesParsing.bestShow(features, "Recess", 2010))
        // Filme zählen nicht als Serie
        assertNull(OpenSubtitlesParsing.bestShow(features.filter { it.type == "Movie" }, "Gummi Bears Movie"))
        assertEquals(emptyList<OpenSubtitlesParsing.Feature>(), OpenSubtitlesParsing.features("kaputt"))
    }

    @Test
    fun episodeMustBelongToSeries() {
        val c = SubtitleCandidate(1, "en", "x", false, 0, false, parentTitle = "Recess")
        assertEquals(true, OpenSubtitlesParsing.belongsToSeries(c, listOf("Recess")))
        assertEquals(true, OpenSubtitlesParsing.belongsToSeries(c, listOf(Hid.s("AAcyQzEvMCnjWyENJF4n"))))
        assertEquals(false, OpenSubtitlesParsing.belongsToSeries(c.copy(parentTitle = "Recess: School's Out"), listOf("Recess")))
        assertEquals(false, OpenSubtitlesParsing.belongsToSeries(c.copy(parentTitle = "Coffee Break"), listOf("Große Pause")))
    }

    @Test
    fun forcedAndMultiPartFilesAreNotFirst() {
        // Häufigster Fehler beim automatischen Laden: die oft geladene „forced“-Datei (nur fremdsprachige Stellen) oder
        // eine auf zwei CDs verteilte Datei (nur die erste Hälfte wird geladen) stand vorn; erst „andere Datei“ half.
        val body = """{"data":[
          {"attributes":{"language":"de","download_count":90000,"foreign_parts_only":true,"hearing_impaired":false,"files":[{"file_id":1}]}},
          {"attributes":{"language":"de","download_count":50000,"nb_cd":2,"files":[{"file_id":2,"cd_number":1},{"file_id":3,"cd_number":2}]}},
          {"attributes":{"language":"de","download_count":4000,"files":[{"file_id":4}]}}
        ]}"""
        val found = OpenSubtitlesParsing.searchResults(body)
        assertEquals(listOf(4L, 2L), found.map { it.fileId })
        assertEquals(2, found[1].parts)
    }

    @Test
    fun trustedAndWellRatedBeatSlightlyMorePopular() {
        val body = """{"data":[
          {"attributes":{"language":"de","download_count":12000,"ratings":2.0,"votes":6,"files":[{"file_id":1}]}},
          {"attributes":{"language":"de","download_count":9000,"from_trusted":true,"files":[{"file_id":2}]}},
          {"attributes":{"language":"de","download_count":11000,"files":[{"file_id":3}]}}
        ]}"""
        // Vertrauenswürdig vor beliebter; schlecht bewertet (Zeiten daneben) ans Ende.
        assertEquals(listOf(2L, 3L, 1L), OpenSubtitlesParsing.searchResults(body).map { it.fileId })
    }

    @Test
    fun hashMatchStaysFirstAndHearingImpairedForcedIsKept() {
        val body = """{"data":[
          {"attributes":{"language":"de","download_count":90000,"from_trusted":true,"files":[{"file_id":1}]}},
          {"attributes":{"language":"de","download_count":3,"moviehash_match":true,"files":[{"file_id":2}]}},
          {"attributes":{"language":"de","download_count":10,"foreign_parts_only":true,"hearing_impaired":true,"files":[{"file_id":3}]}}
        ]}"""
        assertEquals(listOf(2L, 1L, 3L), OpenSubtitlesParsing.searchResults(body).map { it.fileId })
    }
}

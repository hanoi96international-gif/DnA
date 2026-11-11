package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Sendernamen aus echten IPTV-Listen gegen typische EPG-Kennungen. */
class EpgMatcherTest {
    private val matcher = EpgMatcher().apply {
        addGuideChannel(Hid.s("FjoNAzAz"), listOf(Hid.s("FjoN")))
        addGuideChannel(Hid.s("FjoNAzo6"), listOf(Hid.s("FjoNDWA=")))
        addGuideChannel(Hid.s("FjoNdwMTXnSnbA=="), listOf(Hid.s("FjoNdwMTXg==")))
        addGuideChannel(Hid.s("FjoNWCR4cz8="), listOf(Hid.s("FjoNWCQ=")))
        addGuideChannel(Hid.s("FBwufj0zdT+tJyAL"), listOf(Hid.s("FBwufj0zdT+t")))
        addGuideChannel(Hid.s("FBwufj0zdT+tRCUWOQMwMw=="), listOf(Hid.s("FBwufj0zdT+tKQkvGXU=")))
        addGuideChannel(Hid.s("Fy8VHHoycg=="), listOf(Hid.s("Fy8VA2U=")))
        addGuideChannel(Hid.s("AA8yaCYlYz/tbSE="), listOf(Hid.s("AA8yDREkZC6m")))
        addGuideChannel(Hid.s("HioHAzAz"), listOf(Hid.s("HioH")))
        addGuideChannel(Hid.s("HioHQzE5OT6m"), listOf(Hid.s("HioHQzE5")))
        addGuideChannel(Hid.s("Lw8jSDgzfjSwJyAL"), listOf(Hid.s("Lw8jSDh2cjOteg==")))
        addGuideChannel(Hid.s("Kho3AzAz"), listOf(Hid.s("KkM1Ww==")))
        addGuideChannel(Hid.s("EiEZAzAz"), listOf(Hid.s("EiEZ")))
        addGuideChannel(Hid.s("EiEZAzUi"), listOf(Hid.s("EiEZDRUjZC6xYCU=")))
        addGuideChannel(Hid.s("Cgc1Xzt4cz8="), listOf(Hid.s("CicVfxs=")))
        addGuideChannel(Hid.s("CzwHHHo3Yw=="), listOf(Hid.s("CzwHDTE/eSk=")))
        addGuideChannel(Hid.s("FwU4bj04cjeiSCcaKEI6eHM/"), listOf(Hid.s("FwU4DRc/eT+uaGQvIlk9OXk=")))
        addGuideChannel(Hid.s("FwU4bj04cjeiWTYLLEQxJHI07W0h"), listOf(Hid.s("FwU4DRc/eT+uaGQ+M0g5P3Iopmc=")))
        addGuideChannel(Hid.s("FwU4bj04cjeiWTYLLEQxJHI08T1qCiQ="), listOf(Hid.s("FwU4DRc/eT+uaGQ+M0g5P3IopmdkRXMZ")))
        addGuideChannel(Hid.s("BgcjSDgCQXSnbA=="), listOf(Hid.s("BgcjSDh2Qww=")))
        addGuideChannel(Hid.s("LBwnSCY4ZD+rbCpAJUg="), listOf(Hid.s("LBxsSzEkeSmmYSEA")))
        addGuideChannel(Hid.s("BQILTC4zciiiTCoJLUQnPjkrog=="), listOf(Hid.s("BQJhZzUscj+xaGQrL0o4P2Qy")))
    }

    private fun check(expected: String?, name: String, epgId: String? = null) =
        assertEquals("„$name${Hid.s("pu7dDXwdcjStfCoJYQ==")}$epgId)", expected, matcher.match(epgId, name))

    @Test
    fun qualityAndCountryDecorationsAreIgnored() {
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDWAd"))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDRwS"))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDRIeUw=="))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDQEeUw=="))
        check(Hid.s("FjoNAzAz"), Hid.s("ACt7DQYCW3qLTQ=="))
        check(Hid.s("FjoNAzAz"), Hid.s("OCoEUXQEQxbjPQ8="))
        check(Hid.s("FjoNAzAz"), Hid.s("ACthUXQEQxbjQQBF"))
        check(Hid.s("FjoNAzAz"), Hid.s("HyoEcHQEQxbjIXVeeR0kfw=="))
        check(Hid.s("FjoNAzAz"), Hid.s("ACthAHQEQxbjQQE4Ag=="))
        check(Hid.s("FjoNAzAz"), Hid.s("ACthz8LYNwiXRWSP9Ky14qO7d7k="))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNzODi9u5z"))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNGR8="))
        check(Hid.s("FjoNAzAz"), Hid.s("EicRDSh2Ux/jdWQ8FWF0Yycck1o="))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDXwUdjmofDRH"))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDQAzez+1YDcHLkM="))
        check(Hid.s("FjoNAzAz"), Hid.s("ACthfwAa"))
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDRAT"))
    }

    @Test
    fun differentChannelsStayApart() {
        check(Hid.s("FjoNAzo6"), Hid.s("FjoNDWA="))
        check(Hid.s("FjoNdwMTXnSnbA=="), Hid.s("FjoNDWZ2Xx4="))
        check(Hid.s("FjoNdwMTXnSnbA=="), Hid.s("FjoNDR0f"))
        check(Hid.s("FjoNWCR4cz8="), Hid.s("FjoNBg=="))
        check(Hid.s("FwU4bj04cjeiWTYLLEQxJHI07W0h"), Hid.s("FwU4DRc/eT+uaGQ+M0g5P3IopmdkJgU="))
        check(Hid.s("FwU4bj04cjeiWTYLLEQxJHI08T1qCiQ="), Hid.s("FwU4DRc/eT+uaGQ+M0g5P3IopmdkRXMZdB5T"))
        check(null, Hid.s("FwU4DRc/eT+uaGQoIEA9Om4="))
        check(null, Hid.s("FjoNDRckfjem"))
        check(Hid.s("BQILTC4zciiiTCoJLUQnPjkrog=="), Hid.s("BQJhZzUscj+xaGQrL0o4P2Qy"))
        check(Hid.s("LBwnSCY4ZD+rbCpAJUg="), Hid.s("DDxhazEkeSmmYSEAYWUQ"))
    }

    @Test
    fun secondNamesAndSpellings() {
        check(Hid.s("FBwufj0zdT+tJyAL"), Hid.s("FBwuGnQeUw=="))
        check(Hid.s("FBwufj0zdT+tRCUWOQMwMw=="), Hid.s("ACt7DQQkeHr0KQkvGXU="))
        check(Hid.s("Fy8VHHoycg=="), Hid.s("Fw81DWV2URKH"))
        check(Hid.s("AA8yaCYlYz/tbSE="), Hid.s("BTwFDRwS"))
        check(Hid.s("AA8yaCYlYz/tbSE="), Hid.s("AA8yDREkZC6mKXAl"))
        check(Hid.s("Lw8jSDgzfjSwJyAL"), Hid.s("Dw8jSDh2JnqLTQ=="))
        check(Hid.s("Kho3AzAz"), Hid.s("CjoXDRwS"))
        check(Hid.s("Cgc1Xzt4cz8="), Hid.s("FjoNDRo/Yyis"))
        check(Hid.s("CzwHHHo3Yw=="), Hid.s("CzwHDWV2Xx4="))
        check(Hid.s("BgcjSDgCQXSnbA=="), Hid.s("BgcjSDh2Xx4="))
        check(Hid.s("HioHQzE5OT6m"), Hid.s("HioHDTozeA=="))
    }

    /** Ausschnitt aus dem echten freien EPG (epgshare01 DE1/AT1, Oktober 2026). */
    private val real = EpgMatcher().apply {
        addGuideChannel(Hid.s("AC8bY3oycg=="), listOf(Hid.s("AC8bYw==")))
        addGuideChannel(Hid.s("AC8bY3pnOT6m"), listOf(Hid.s("AC8bY3Rn")))
        addGuideChannel(Hid.s("AC8bY3pkOT6m"), listOf(Hid.s("AC8bY3Rk")))
        addGuideChannel(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg=")))
        for (n in 1..10) addGuideChannel("${Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhq")}$n${Hid.s("agok")}", listOf("${Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhk")}$n"))
        addGuideChannel(Hid.s("FwU4AwcmeCi3JwoLNl56MnI="), listOf(Hid.s("FwU4DQcmeCi3KQoLNl4=")))
        addGuideChannel(Hid.s("FwU4AwcmeCi3JxABMQMRIHI0tycgCw=="), listOf(Hid.s("FwU4DQcmeCi3KRABMQ0RIHI0tw==")))
        addGuideChannel(Hid.s("FwU4Axc/eT+uaGo+M0g5P3IopicMKm9JMQ=="), listOf(Hid.s("FwU4DRc/eT+uaGQ+M0g5P3IopikMKg==")))
        addGuideChannel(Hid.s("FyUYAxcfWR+OSGo+E2gZH1IIhkdqRXMZejdj"), listOf(Hid.s("FyUYDRcfWR+OSGQ+E2gZH1IIhkdkRXMZ")))
        addGuideChannel(Hid.s("CSoTAzAz"), listOf(Hid.s("CSoT")))
        addGuideChannel(Hid.s("FzkTAgcEOT6m"), listOf(Hid.s("FzkTAgcE")))
        addGuideChannel(Hid.s("CzwHA2Z4di4="), listOf(Hid.s("CzwHDWY=")))
    }

    @Test
    fun payTvFromProviderLists() {
        fun real(expected: String?, name: String) = assertEquals("„$name“", expected, real.match(null, name))
        real(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqX29JMQ=="), Hid.s("ACt7DQcdTnqBXAoqBH4YH1Ab4zhkJgU="))
        real(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqX3EDMDM="), Hid.s("ACt7DQc9bnqBfCoKJF44P3A74zh0TgdlEA=="))
        real(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXW9JMQ=="), Hid.s("OCoEUXQFXAPjWhQhE3l0FEIUh0wXIghqFXYkepZBAA=="))
        real(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), Hid.s("ACt7DQcdTnqBXAoqBH4YH1Ab40EA"))
        real(Hid.s("FwU4AwcmeCi3JxABMQMRIHI0tycgCw=="), Hid.s("ACt7DQcdTnqQWQs8FQ0AGUd6hl8BIBU="))
        real(Hid.s("FwU4AwcmeCi3JwoLNl56MnI="), Hid.s("ACt7DQcdTnqQWQs8FQ0aE0AJ408MKg=="))
        // Sender und Sender 1 sind verschiedene Sender
        real(Hid.s("AC8bY3oycg=="), Hid.s("ACt7DRAXTRQ="))
        real(Hid.s("AC8bY3pnOT6m"), Hid.s("ACt7DRAXTRTjOGQmBQ=="))
        real(Hid.s("AC8bY3pkOT6m"), Hid.s("AC8bY3RkN26I"))
        // Gastronomie-Fassung zeigt dasselbe Programm
        real(Hid.s("AC8bY3pnOT6m"), Hid.s("ACt7DRAXTRTjOGQsAH8="))
        real(Hid.s("AC8bY3oycg=="), Hid.s("AC8bY3QUdijjQQA="))
        real(Hid.s("FwU4Axc/eT+uaGo+M0g5P3IopicMKm9JMQ=="), Hid.s("ACt7DQcdTnqAQAorDGx0BkUfjkABPARj"))
        real(Hid.s("FyUYAxcfWR+OSGo+E2gZH1IIhkdqRXMZejdj"), Hid.s("ACt7DQcdTnqAQAorDGx0BkUfjkABPARjdH0lbg=="))
        // Regionalfenster fallen auf das Hauptprogramm zurück
        real(Hid.s("CSoTAzAz"), Hid.s("ACt7DRkSRXqQSAcmEmgadl8e"))
        real(Hid.s("FzkTAgcEOT6m"), Hid.s("ACt7DQcBRXqBXg=="))
        real(Hid.s("CzwHA2Z4di4="), Hid.s("BTp7DRsEUXrxKRMHJEM="))
    }

    @Test
    fun regionalA() {
        val m = EpgMatcher().apply {
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08icMKm9JMQ=="), listOf(Hid.s("FwU4DQcmeCi3KQUbMlkmP3Z68ikMKg==")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08ScMKm9JMQ=="), listOf(Hid.s("FwU4DQcmeCi3KQUbMlkmP3Z68SkMKg==")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z0p2w="), listOf(Hid.s("FwU4DQcmeCi3KQUbMlkmP3Y=")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3J3VAJUg="), listOf(Hid.s("FwU4DQcmeCi3KXU=")))
        }
        assertEquals(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08icMKm9JMQ=="), m.match(null, Hid.s("BTp7DQcdTnqQWQs8FQ0VA0QOkUAFTnANHBI=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08icMKm9JMQ=="), m.match(null, Hid.s("BTo9DQcdTnqQWQs8FX50F0IJl1sNL2EcdB5T")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08icMKm9JMQ=="), m.match(null, Hid.s("BTp7DQcdTnqQWQs8FQ2XwEQOhlsWKwhuHHYm")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08ScMKm9JMQ=="), m.match(null, Hid.s("BTp7DQc9bnqCfDcaM0Q1diU=")))
        assertEquals(Hid.s("FwU4AwcmeCi3J3VAJUg="), m.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ1ldl8e")))
        // Kennung des Anbieters gehört zu einem anderen Sender: als unpassend erkennen
        assertEquals(Hid.s("FwU4AwcmeCi3J3VAJUg="), m.match(Hid.s("FwU4AwcmeCi3J3VAJUg="), Hid.s("BTp7DQcdTnqQWQs8FQ0VA0QOkUAFTnA=")))
        assertEquals(false, m.fitsName(Hid.s("FwU4AwcmeCi3J3VAJUg="), Hid.s("BTp7DQcdTnqQWQs8FQ0VA0QOkUAFTnA=")))
        assertEquals(true, m.fitsName(Hid.s("FwU4AwcmeCi3J3VAJUg="), Hid.s("ACt7DQcdTnqQWQs8FQ1ldlEShw==")))
        assertEquals(true, m.fitsName(Hid.s("FwU4AwcmeCi3JwUbMlkmP3Z08icMKm9JMQ=="), Hid.s("OC8VUXQFfCPjWjQBM1l0F2Ipt3stD2EcdLej7iK99A==")))
        assertEquals(true, matcher.fitsName(Hid.s("BgcjSDgCQXSnbA=="), Hid.s("ACt7DRY/dT+v")))
        assertEquals(true, matcher.fitsName(Hid.s("AA8yaCYlYz/tbSE="), Hid.s("ACt7DRA3ZHqGezcaJA0cEg==")))
        assertEquals(true, matcher.fitsName(Hid.s("MQAjSD83eTS3JyAL"), Hid.s("DRwmSDoyYDuw")))
    }

    @Test
    fun countryDecidesBetweenSameNames() {
        check(Hid.s("EiEZAzAz"), Hid.s("EiEZDRwS"))
        check(Hid.s("EiEZAzUi"), Hid.s("BTp7DQIZT3qCfDcaM0Q1"))
    }

    @Test
    fun idsFromProvidersAreMatchedToo() {
        // Kennung der Playlist existiert nicht im EPG, sieht aber ähnlich aus
        check(Hid.s("FjoNAzAz"), Hid.s("FwsvSTEkN2v0"), epgId = Hid.s("NhotAzAz"))
        check(Hid.s("FjoNAzAz"), Hid.s("FwsvSTEkN2v0"), epgId = Hid.s("FjoNA2AdOT6m"))
        check(Hid.s("FjoNAzAz"), Hid.s("FwsvSTEkN2v0"), epgId = Hid.s("FjoNZRB4cz+DQQA="))
        check(Hid.s("HioHAzAz"), Hid.s("FwsvSTEkN2v7"), epgId = Hid.s("PgonDTwy"))
        // Kennung genau vorhanden: gewinnt immer
        check(Hid.s("Fy8VHHoycg=="), Hid.s("FjoN"), epgId = Hid.s("Fy8VHHoycg=="))
        // Kennung unbekannt, Name hilft
        check(Hid.s("FjoNAzAz"), Hid.s("FjoNDWAd"), epgId = Hid.s("NhotGT94Zyisfy0KJF8="))
    }

    @Test
    fun keyAsCheckedOnTheDevice() {
        val key = EpgMatcher.analyse(Hid.s("OCoEUXQFXAPjSxEgBWgHGl4dgil1TqCZ4Lej6uNSBg8iRiEmSg=="), isId = false)
        assertEquals(Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78g=="), key.strong)
        assertEquals(Hid.s("IAs="), key.country)
    }

    @Test
    fun providerLabelsInFront() {
        val m = EpgMatcher().apply {
            addGuideChannel(Hid.s("dx0gWXoycg=="), listOf(Hid.s("dx0gWQ==")))
            addGuideChannel(Hid.s("HioHAzAz"), listOf(Hid.s("HioH")))
            addGuideChannel(Hid.s("AC8bY3pnOT6m"), listOf(Hid.s("AC8bY3Rn")))
        }
        assertEquals(Hid.s("dx0gWXoycg=="), m.match(null, Hid.s("FDwIYBFsN2mQSBBOoJnrt6P2IrzG")))
        assertEquals(Hid.s("HioHAzAz"), m.match(null, Hid.s("EicRDSh2Ux/jdWQ0BWt0HlM=")))
        assertEquals(Hid.s("HioHAzAz"), m.match(null, Hid.s("FDwIYBFsNx6GM2Q0BWt0EF8e")))
        assertEquals(Hid.s("AC8bY3pnOT6m"), m.match(null, Hid.s("Fz4OfwB9N2DjTQU0Dw1ldl8e")))
        assertNull(m.match(null, Hid.s("FDwIYBFsNxuRXQE=")))
    }

    /** Echte Kennungen aus epgshare01 DE1 (Stand Oktober 2026) und Dienst. */
    @Test
    fun uhdChannelsKeepTheirOwnProgramme() {
        val m = EpgMatcher().apply {
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg=")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqOwlpejJy"), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXA==")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JxEmBQMwMw=="), listOf(Hid.s("FwU4DQcmeCi3KREmBQ==")))
            addGuideChannel(Hid.s("FwU4AwcmeCi3JxABMQMRIHI0tycgCw=="), listOf(Hid.s("FwU4DQcmeCi3KRABMQ0RIHI0tw==")))
            addGuideChannel(Hid.s("FjoNAzAz"), listOf(Hid.s("FjoN")))
            addGuideChannel(Hid.s("ESYFHHoycg=="), listOf(Hid.s("ESYFHA==")))
            addGuideChannel(Hid.s("dV01RXoFYyimbDBAFEM9IHIosGgoQCVI"), listOf(Hid.s("dV01RXQFYyimbDBOFEM9IHIosGgo")))
            addGuideChannel(Hid.s("Fx4uXyB4JnTuJykXFUg1O0MM7W0h"), listOf(Hid.s("Fx4uXyB2JnruKSkXFUg1O0MM")))
            addGuideChannel(Hid.s("CQ8mSDoidgmzZjYab0kxFlo7pGwqGiB5Ag=="), listOf(Hid.s("CQ8mSDoidgmzZjYa")))
        }
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqOwlpejJy"), m.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdANfHg==")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqOwlpejJy"), m.match(null, Hid.s("ACt7DQcdTnqBXAoqBH4YH1Ab4z0P")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), m.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdB5T")))
        // Nummerierte Sender in 4K sind dasselbe Programm.
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), m.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGQ3D4tN")))
        assertEquals(Hid.s("FwU4AwcmeCi3JxEmBQMwMw=="), m.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ1gHQ==")))
        assertEquals(Hid.s("FwU4AwcmeCi3JxABMQMRIHI0tycgCw=="), m.match(null, Hid.s("ACt7DQcdTnqXRhROBHsRGEM=")))
        // Ohne eigenen UHD-Sender im EPG: das normale Programm.
        assertEquals(Hid.s("FjoNAzAz"), m.match(null, Hid.s("ACt7DQYCW3qWQQA=")))
        assertEquals(Hid.s("ESYFHHoycg=="), m.match(null, Hid.s("ACt7DQEeU2vjSx1OCWl/")))
        assertEquals(Hid.s("dV01RXoFYyimbDBAFEM9IHIosGgoQCVI"), m.match(null, Hid.s("ACt7DWVlQxLjWhA8BGgAdl8e")))
        assertEquals(Hid.s("Fx4uXyB4JnTuJykXFUg1O0MM7W0h"), m.match(null, Hid.s("ACt7DRkPQx+CRBA4YX4EGUUO4zg=")))
        assertEquals(Hid.s("CQ8mSDoidgmzZjYab0kxFlo7pGwqGiB5Ag=="), m.match(null, Hid.s("ACt7DRkXUB+NXQVOEn0bBEN6i00=")))
    }

    @Test
    fun timeShiftedChannels() {
        assertEquals(24 to Hid.s("ACt7DQcdTnqAQAorDGx0BkUfjkABPARjdB5T"), EpgMatcher.timeShift(Hid.s("ACt7DQcdTnqAQAorDGx0BkUfjkABPARjdH0lbuNBAA==")))
        assertEquals(1 to Hid.s("FjoNDRwS"), EpgMatcher.timeShift(Hid.s("FjoNDX9nNxKH")))
        assertEquals(null, EpgMatcher.timeShift(Hid.s("Fz4OfwBnPHqLTQ==")))
        assertEquals(null, EpgMatcher.timeShift(Hid.s("BSAIdRF9")))
        assertEquals(null, EpgMatcher.timeShift(Hid.s("FwU4DQcmeCi3KXU=")))
        val m = EpgMatcher().apply { addGuideChannel(Hid.s("FwU4Axc/eT+uaGo+M0g5P3IopicMKm9JMQ=="), listOf(Hid.s("FwU4DRc/eT+uaGQ+M0g5P3IopikMKg=="))) }
        assertEquals(Hid.s("FwU4Axc/eT+uaGo+M0g5P3IopicMKm9JMQ=="), m.match(null, EpgMatcher.timeShift(Hid.s("ACt7DQcdTnqAQAorDGx0BkUfjkABPARjdH0lbuNBAA=="))!!.second))
    }

    @Test
    fun primeAndRoundTheClock() {
        val m = EpgMatcher().apply {
            addGuideChannel(Hid.s("FBwoQDF4Xx7tbSE="), listOf(Hid.s("FBwoQDF2Xx4=")))
            addGuideChannel("6866525c8a412a0e95c438b4", listOf(Hid.s("ABcvDQcmeCi3KQkHOQ==")))
            addGuideChannel(Hid.s("DSAVaAZkI20="), listOf(Hid.s("DSAVaAZ2JW7sPg==")))
            addGuideChannel(Hid.s("FA8zTDk5YjS3ImoNKQ=="), listOf(Hid.s("FA8zTDk5YjS3Ig==")))
        }
        assertEquals(Hid.s("FBwoQDF4Xx7tbSE="), m.match(null, Hid.s("ACt7DQQEXheGKRInBWgbdiVu7D5kJgU=")))
        assertEquals(Hid.s("FBwoQDF4Xx7tbSE="), m.match(null, Hid.s("ACt7DRUbVgCMR2Q+E2QZEzcMik0BIQ==")))
        assertEquals("6866525c8a412a0e95c438b4", m.match(null, Hid.s("ACt7DRAveXqQeSscNQ0ZP28=")))
        assertEquals(Hid.s("DSAVaAZkI20="), m.match(null, Hid.s("DSAVaAZ2JW7uPg==")))
        assertEquals(Hid.s("FA8zTDk5YjS3ImoNKQ=="), m.match(null, Hid.s("ACt7DQQXRRuORhEgFQZ0HlM=")))
        assertEquals(null, m.match(null, Hid.s("ACt7DQQXRRuORhEgFQ0aE0MNjFsP")))
    }

    @Test
    fun spellingsFromUserReport() {
        // Kurzbericht 01.11.2026: Sender ohne Programm, die es im TV-Guide unter anderem Namen gibt
        val matcher = EpgMatcher()
        listOf(
            Hid.s("FjoNAzAz") to Hid.s("FjoN"), Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqOwlpejJy") to Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp"), Hid.s("AysOAwAzez+1YDcHLkN6MnI=") to Hid.s("AysODQAzez+1YDcHLkM="),
            Hid.s("DD0EAzAz") to Hid.s("DD0E"), Hid.s("BzwIYBF4PHSKRxIrEnkdEVYOikYKQCVI") to Hid.s("BzwIYBF2PHqKRxIrEnkdEVYOikYK"), Hid.s("BxszRDslfi66JwcGIEM6M3t0p2w=") to Hid.s("BxszRDslfi66KQcGIEM6M3s="),
            Hid.s("DAEsSHQ3eT7jTiUcJUg6dkMM7W0h") to Hid.s("DAEsSHQ3eT7jTiUcJUg6dkMM"), Hid.s("LBxsSzEkeSmmYSEAb0kx") to Hid.s("LBxsSzEkeSmmYSEA"), Hid.s("Ci8VAxMTWHSUQAgqb0kx") to Hid.s("Ci8VDRMTWHqUQAgq"),
        ).forEach { (id, name) -> matcher.addGuideChannel(id, listOf(name)) }
        assertEquals(Hid.s("FjoNAzAz"), matcher.match(null, Hid.s("ACt7DQYCW3oivMWP9Zm14qd68DFwXhE=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqOwlpejJy"), matcher.match(null, Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdLei2yK98I/1nXSUpLhCsabv9c/V5vbufQ==")))
        assertEquals(Hid.s("AysOAwAzez+1YDcHLkN6MnI="), matcher.match(null, Hid.s("ACt7DRMTWHqLTQ==")))
        assertEquals(Hid.s("DD0EAzAz"), matcher.match(null, Hid.s("ACt7DRwFUnrxPWQmBQ==")))
        assertEquals(Hid.s("BzwIYBF4PHSKRxIrEnkdEVYOikYKQCVI"), matcher.match(null, Hid.s("ACt7DRcEXheGKWJOCGMCE0QOik4FOghiGnZfHuMhc1xxfX0=")))
        assertEquals(Hid.s("BxszRDslfi66JwcGIEM6M3t0p2w="), matcher.match(null, Hid.s("ACt7DRcDRROMWg06GA0XHlYUjUwIThFiAxNFH4cpBjdhfgQfUh2GRWQmBQ==")))
        assertEquals(Hid.s("DAEsSHQ3eT7jTiUcJUg6dkMM7W0h"), matcher.match(null, Hid.s("ACt7DRwZWh/jTgU8FWgadkMM40EA")))
        assertEquals(Hid.s("LBxsSzEkeSmmYSEAb0kx"), matcher.match(null, Hid.s("DiEYY252Xx+QWg09AmURBDcIlkcAKBRjH3b27nzo8MKgmNY=")))
        assertEquals(Hid.s("DAEsSHQ3eT7jTiUcJUg6dkMM7W0h"), matcher.match(null, Hid.s("ACt7DRwRQwzjQQA=")))
        // „Sendername“ als Zusatz bleibt Rauschen, Sender Wild bleibt Sender Wild
        assertEquals(Hid.s("Ci8VAxMTWHSUQAgqb0kx"), matcher.match(null, Hid.s("ACt7DRoXQ3qETAtOFmQYEjcShw==")))
    }

    /** Fotos vom 08.11.2026: Nummern 2 und 3 vertauscht, „4K“ darf nie als Nummer zählen. */
    @Test
    fun channelNumberIsWhatCounts() {
        val m = EpgMatcher().apply {
            addGuideChannel(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg=")))
            for (n in 1..10) addGuideChannel("${Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhq")}$n${Hid.s("agok")}", listOf("${Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhk")}$n"))
            addGuideChannel(Hid.s("Fy8VA2V4cz8="), listOf(Hid.s("Fy8VA2U=")))
            addGuideChannel(Hid.s("dx0gWXoycg=="), listOf(Hid.s("dx0gWQ==")))
        }
        // Kennung des Anbieters vertauscht: Der Name gewinnt.
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXW9JMQ=="), m.match(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevApcCU=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), m.match(Hid.s("NwU4AycmeCi3JyYbL0kxJXszpGhqXW9JMQ=="), Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkXGFlEA==")))
        assertTrue(m.idConflicts(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkXWEZHw==")))
        assertFalse(m.idConflicts(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXW9JMQ=="), Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkXWEZHw==")))
        // Unbekannte Kennung mit anderer Nummer: auch dann nach Namen.
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqW29JMQ=="), m.match(Hid.s("FwU4byE4cz+wZS0JIBl6MnI="), Hid.s("ACt7DQcdTnqBXAoqBH4YH1Ab4zxkKAlp")))
        // 4K, 50 fps, SAT, WEB: nur Technik und Empfangsweg.
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqX29JMQ=="), m.match(null, Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevIpcCU=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqWm9JMQ=="), m.match(null, Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWmEZH3YiaoVZFw==")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), m.match(null, Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkXGF+FQI=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), m.match(null, Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkXGEFBxdDcw==")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqWG9JMQ=="), m.match(null, Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWGF6ERQ=")))
        assertEquals(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), m.match(null, Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkJgU=")))
        // „Sendername“ und „Sendername“ bleiben, was sie sind.
        assertEquals(Hid.s("Fy8VA2V4cz8="), m.match(null, Hid.s("ACt7DQcXQ3TyKQwq")))
        assertEquals(Hid.s("dx0gWXoycg=="), m.match(null, Hid.s("ACt7DWcFVg7jQQA=")))
        // Kennung ohne Nummer im Namen, aber mit Nummer beim EPG-Sender: Widerspruch.
        assertTrue(m.idConflicts(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqXG9JMQ=="), Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkJgU=")))
    }

    @Test
    fun guideChoicesSameNumberFirst() {
        data class G(val id: String, val name: String)
        val guides = (1..9).map { G("${Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhq")}$it${Hid.s("agok")}", "${Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhk")}$it") } +
            G(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqWm9JMQ=="), Hid.s("FyUYDRYaN27jIQUAI0QxInIo6g==")) + G(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqCiQ="), Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg="))
        val ranked = EpgMatcher.rank(Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevcpcCU="), guides, { it.id }, { it.name })
        assertEquals(G(Hid.s("FwU4AwcmeCi3JwYbL0kxJXszpGhqWm9JMQ=="), Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkWg==")), ranked.first())
        // Jeder EPG-Sender nur einmal.
        assertEquals(ranked.size, ranked.map { it.id }.distinct().size)
    }
}

package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSourcesTest {
    @Test
    fun sourcesByCountry() {
        val gr = EpgSources.SOURCES.first { Hid.s("Azxw") in it.url }.url
        assertEquals(false, EpgSources.relevant(gr, listOf(Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("FwU4DQcmeCi3KXU="))))
        assertEquals(true, EpgSources.relevant(gr, listOf(Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("Azx7DRkXUB+NXQVOEmgGH1IJ40EA"))))
        assertEquals(true, EpgSources.relevant(EpgSources.PUBLIC.first(), listOf(Hid.s("FwU4DQcmeCi3KXU="))))
        assertEquals(true, EpgSources.relevant(Hid.s("LBo1XW55ODutay0LNUgmeHIiomQ0AiQCLDt7LrUnNAYx"), emptyList()))
        assertEquals(Hid.s("NAI0WTsiYXenbGoWLEF6MW0="), EpgSources.cacheName(Hid.s("LBo1XSdsOHWqJykEKQM6LDgKr3wwARV7ezJydLtkKEAmVw==")))
        assertEquals(Hid.s("Nw8sXiE4cC61eSgbMgAwMzkirmVqCTs="), EpgSources.cacheName(Hid.s("LBo1XSdsOHWqJykEKQM6LDgJomQ3Gy9KAABHNrZ6awokAyw7e3Skcw==")))
        assertEquals(Hid.s("IR4mciY/ZyqmexsqBBx6Lno27W4+"), EpgSources.cacheName(EpgSources.PUBLIC.first()))
        // open-epg Deutschland 1 und 4 (Nachrecherche): eigene Cache-Dateien, für deutschsprachige Sender
        val de1 = EpgSources.PUBLIC.first { it.endsWith(Hid.s("IwszQDU4bmvtcSkC")) }
        val de4 = EpgSources.PUBLIC.first { it.endsWith(Hid.s("IwszQDU4bm7tcSkC")) }
        assertEquals(false, EpgSources.cacheName(de1) == EpgSources.cacheName(de4))
        assertEquals(true, EpgSources.relevant(de1, listOf(Hid.s("ACt7DRwRQwzjQQA="))))
    }

    @Test
    fun noOldProviderGuidesAnymore() {
        assertEquals(false, EpgSources.PUBLIC.any { Hid.s("KQ8mSDoidi61") in it || Hid.s("IBcvAycmeCi3") in it })
    }
}

package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeGenreTest {
    @Test
    fun fromCategory() {
        assertEquals(ProgrammeGenre.SPORT, ProgrammeGenre.of(Hid.s("Bg84SCY4N7hDmmQqLl8gO2I0pw=="), Hid.s("Fx4uXyB6Nxy2ytsMIEE4")))
        assertEquals(ProgrammeGenre.FILM, ProgrammeGenre.of(Hid.s("DQAiSCQifjWt"), Hid.s("Fx4oSDgwfjau")))
        assertEquals(ProgrammeGenre.SERIES, ProgrammeGenre.of(Hid.s("EA81QiYi"), Hid.s("DxwoQD0lciiqbA==")))
        assertEquals(ProgrammeGenre.KIDS, ProgrammeGenre.of(Hid.s("FA82DQQ3YyisZQ=="), Hid.s("DwcvSTEkZD+xYCE=")))
        assertEquals(ProgrammeGenre.NEWS, ProgrammeGenre.of(Hid.s("EA8mSCcldDKifA=="), Hid.s("Cg8iRSY/dDK3bCo=")))
        assertEquals(ProgrammeGenre.DOCU, ProgrammeGenre.of(Hid.s("EAszXzV2Tw=="), Hid.s("AAEqWDkzeS6ifS0BLw==")))
    }

    @Test
    fun fromChannelWhenNoCategory() {
        assertEquals(ProgrammeGenre.SPORT, ProgrammeGenre.of(Hid.s("CAc3SA=="), null, Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXw==")))
        assertEquals(ProgrammeGenre.NEWS, ProgrammeGenre.of(Hid.s("BQNhYDskcD+t"), null, Hid.s("KkM1W3QeUw==")))
        assertEquals(ProgrammeGenre.OTHER, ProgrammeGenre.of(Hid.s("DRwmSDoyYDuw"), null, Hid.s("FjoN")))
    }
}

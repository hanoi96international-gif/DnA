package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fotos vom 08.11.2026: „Sendepause“ auf einem Sportsender während eines Spiels, MotoGP auf einem anderen. */
class GuidePlausibilityTest {
    private val hour = 3_600_000L
    private fun item(title: String, fromH: Int, toH: Int, category: String? = null) =
        GuidePlausibility.Item(title, null, category, fromH * hour, toH * hour)

    @Test
    fun placeholders() {
        assertTrue(GuidePlausibility.isPlaceholder(Hid.s("FwsvSTEmdi+wbA==")))
        assertTrue(GuidePlausibility.isPlaceholder(Hid.s("FBwuSiY3ejezaDEdJA==")))
        assertTrue(GuidePlausibility.isPlaceholder(Hid.s("HhszVzE/Y3qobC0AJA0HM3k+tmcj")))
        assertTrue(GuidePlausibility.isPlaceholder(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkWg=="), Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevcpcCU=")))
        assertFalse(GuidePlausibility.isPlaceholder(Hid.s("EggNDRY5dDK2ZGRDYWY1JHspsXwsCzMNBxU=")))
        assertFalse(GuidePlausibility.isPlaceholder(Hid.s("dkBhbyE4cz+wZS0JIA15dlw1rW8hHCRDLg==")))
    }

    @Test
    fun expectedSport() {
        assertEquals(SportKinds.Kind.FOOTBALL, GuidePlausibility.expectedKind(Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevUpcCU=")))
        assertEquals(SportKinds.Kind.OTHER, GuidePlausibility.expectedKind(Hid.s("FwU4DQcmeCi3KXI=")))
        assertTrue(GuidePlausibility.isSportChannel(Hid.s("FwU4DQcmeCi3KXI=")))
        assertFalse(GuidePlausibility.isSportChannel(Hid.s("ACt7DQYCW3qLTQ==")))
    }

    @Test
    fun wrongSportOnBundesligaChannel() {
        val items = listOf(item(Hid.s("CQE1QhMGLXqEeyut3kgmdkcopmA3TjdCOnZBO69sKg0oTA=="), 0, 3), item(Hid.s("CQE1QhMGNxKqbiwCKEo8ImQ="), 3, 4), item(Hid.s("FwsvSTEmdi+wbA=="), 4, 12))
        val problem = GuidePlausibility.problem(Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWGFlEA=="), items, 0, 12 * hour)
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains(Hid.s("Fx4uXyA3ZS4=")))
    }

    @Test
    fun mostlySendepause() {
        val items = listOf(item(Hid.s("FwsvSTEmdi+wbA=="), 0, 10), item(Hid.s("dkBhbyE4cz+wZS0JIBd0HnIzp2wqBiREOXY6eotaEg=="), 10, 12))
        assertNotNull(GuidePlausibility.problem(Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWmFlEA=="), items, 0, 12 * hour))
        // Ganz leer zählt genauso.
        assertNotNull(GuidePlausibility.problem(Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWmFlEA=="), emptyList(), 0, 12 * hour))
    }

    @Test
    fun matchdayIsFine() {
        val items = listOf(
            item(Hid.s("dkBhbyE4cz+wZS0JIBd0AHEW40srDSlYOXY6eohoNgIyXyE+cijjWgc="), 0, 2), item(Hid.s("BhsvSTElezOkaGRDYWU9MX82qm4sGjI="), 2, 3),
            item(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGhkQ2FmOzhxP7FsKhQ="), 3, 6), item(Hid.s("FwU4DQcmeCi3KQoLNl4="), 6, 12),
        )
        assertNull(GuidePlausibility.problem(Hid.s("FyUYDQcGWAiXKQY7D2kRBVsThEhkWmFlEA=="), items, 0, 12 * hour))
        // Ein Sport-Sender ohne festgelegte Sportart darf alles zeigen.
        val mixed = listOf(item(Hid.s("CQE1QhMG"), 0, 4), item(Hid.s("EAsvQz0lLXqCXRROB0Q6N3sp"), 4, 8), item(Hid.s("AhuCsjY3ezb5KRQcJEA9M2V6j2wlCTRI"), 8, 12))
        assertNull(GuidePlausibility.problem(Hid.s("FwU4DQcmeCi3KXJOCWk="), mixed, 0, 12 * hour))
    }
}

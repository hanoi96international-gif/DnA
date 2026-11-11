package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import de.ayutv.data.sport.Crests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrestsTest {
    private val openLiga = Hid.s("H2RhDXR2N3rjKT9MNUg1O14+4TNzQmNZMTd6FKJkIUx7DxY5ZS+wei0PYWk7JGM3tmcgTG0PJz54KLdHJQMkD250UzWxfSkbL0l2ejUupmgpJyJCOgNlNuEzZgY1WSQlLXXsbDwPLF04Mzk1sW5rDDdPeiZ5PeF0aGRhDXR2N3rjKT9MNUg1O14+4TN9QmNZMTd6FKJkIUx7DxIVNwmgYSUCKkh0ZiN47ys3Bi5fIBh2N6YrfkwSTjw3ezGmK2hMNUg1O145rGcRHC0PbnR/Lrd5N1RuAjEudjezZSFALl8zeWRq9yc0ACYPKXodeuMpZE5hDXQtNS6maCknJQ9uYyJ24X0hDyxjNTtyePkrDA8vQzsgcijjMHJMbQ8nPngot0clAyQPbnRfO61nKxgkX3Z6NS6maCknIkI6A2U24TNmBjVZJCUtdexsPA8sXTgzOTWxbmsGeBt6Jnk94XROTmENdAs=")

    @Test
    fun germanClubsByAnySpelling() {
        val teams = Crests.parseOpenLigaTeams(openLiga)
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYmGCMDJDhw"), Crests.match(Hid.s("BgEzA3QSeCi3ZDEAJQ=="), teams))
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYmGCMDJDhw"), Crests.match(Hid.s("BgEzWCclfjvjTSscNUAhOHM="), teams))
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCY3XnUDJDhw"), Crests.match(Hid.s("Fw0pTDg9cnrzPQ=="), teams))
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYsV3cDJDhw"), Crests.match(Hid.s("DA8vQzsgcijjMHI="), teams))
        // Zweite Mannschaft: dasselbe Wappen wie der Verein.
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYsV3cDJDhw"), Crests.match(Hid.s("DA8vQzsgcijjMHJOCGQ="), teams))
        assertNull(Crests.match(Hid.s("BRwySDo3ew=="), teams))
    }

    @Test
    fun sportsDbFootballOnly() {
        val body = Hid.s("P0w1SDU7ZHj5Uk5OYQ10djd64ylkTmFWdiVjKJdsJQNjF3YXZSmmZyUCYwF2JWMokHkrHDUPbnRENaBqIRxjAXYlYyiBaCAJJA9udH8ut3k3VG4CJmQ5P7toKR4tSHo5ZT3saDYdJEM1OjkqrW5mE20ndHY3euMpZE5hDXR2bHiwfTY6JEw5dC14gns3Cy9MOHZDL69oZkJjXiAkRCqsezBMew8HOXQ5pntmQmNeICRVO6duIUx7DzwiYyqwM2tBMx96M287rnkoC29CJjE4LrZlJUAxQzN0alDjKWROYQ10dkon")
        assertEquals(Hid.s("LBo1XSdsOHWxO2oLOUw5Jns/7WY2CW5MJiVyNKJlah4vSg=="), Crests.parseSportsDb(body, Hid.s("BRwySDo3ew==")))
        assertNull(Crests.parseSportsDb(Hid.s("P0w1SDU7ZHj5ZzECLVA="), Hid.s("BRwySDo3ew==")))
        assertNull(Crests.parseSportsDb(Hid.s("P0w1SDU7ZHj5Uj9MMlkmAnI7rit+TABfJzN5O68raEwyWSYFZzWxfWZUY281JXw/t2slAi0PeHRkLrFLJQomSHZsNTK3fTQdewJ7Ljgj7XkqCWNQCSs="), Hid.s("BRwySDo3ew==")))
    }

    @Test
    fun remembersResultsInFile() {
        val file = java.nio.file.Files.createTempFile(Hid.s("JxwkXiAl"), Hid.s("ahoyWw==")).toFile()
        file.writeText(Hid.s("JgEzWCclfjvjbSscNUAhOHNTq30wHjIXe3lyIqJkNAIkAzskcHWhfyZAMUMzXHYosGwqDy0kXg=="))
        val crests = Crests(okhttp3.OkHttpClient(), file)
        assertEquals(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYmGCMDJDhw"), crests.cached(Hid.s("BgEzWCclfjvjTSscNUAhOHM=")))
        assertNull(crests.cached(Hid.s("BRwySDo3ew==")))
    }
}

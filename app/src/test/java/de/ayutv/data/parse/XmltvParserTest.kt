package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {

    private val xml = Hid.s("Tk5hDXR2N3rjNXsWLEF0IHIosGArAHwPZXgneONsKg0uST04cGfhXBAobBV2aSlQ4ylkTmENdHYrLrU3Tk5hDXR2N3rjKWRSIkU1OHk/ryktCnwPMDdkP7F6MAtvSTF0KWanYDceLUwte3k7rmx6KiBedBNlKbdsZCYFEXsyfimzZSUXbEM1O3Jk/2AnAS8NJyR0Z+FxZkF/EXs1fzutZyECfyd0djd64ylkTmENaDV/O61nIQJhRDBrNSCnb2oKJA9qanMzsHkoDzgAOjd6P/1TACh9AjA/ZCqvaD1DL0w5MylmqmorAGFeJjUqeKt9MB4yF3t5ezWkZjdAJFU1O2c2piY+CicDJDhweOw3eEEiRTU4eT+vN05OYQ10djd64ylkUjFfOzFlO65kIU4yWTUkY2fhO3RcdR1lZiZo8zl0XnENf2YmavMrZB01QiRrNWjzO3BecB1lZCdr9jl0TmodZWYneONqLA8vQzE6KninaDcLM14gMzk+pit6ZGENdHY3euMpZE5hDWgifi6vbGQCIEMzazU+pit6OiBKMSVkOatoMVJuWT0iez/9A2ROYQ10djd64ylkTn1JMSV0eq9oKgl8DzAzNWSNaCcGM0Q3PmM/rSliDyxdb3ZAP7d9IRx9AjAzZDn9A2ROYQ10djd64ylkTn1ONSJyPax7PVAPSCMlK3WgaDALJkImLylQ4ylkTmENdHY3euMpeA0zSDA/Yyn9NSAHM0g3Ingo/UMhAyBDMGo4Pqp7IQ01QiZoK3WgeyEKKFknaB164ylkTmENdHY3Zux5NgEmXzU7ej/9A2ROYQ10djd64yl4HjNCMyR2N65sZB01TCYiKnjxOXZacRxkZyVq8jx0XmEGZGcnauEpNxouXWl0JWrxPXRfcRxmZyNv8zlkRXEcZGY1eqBhJQAvSDhrNT6ieiEcMlkxeHM/4TdOTmENdHY3euMpZE5hEScjdXe3YDACJBM7Pnk/410tGiRBaHlkL6EkMAc1QTFoHXrjKWROYQ10djdm7Hk2ASZfNTt6P/0DZE5hDXR2N3rjKXgeM0IzJHY3rmxkHTVMJiIqePE5dlpxHGRnJWrzOXReYQZkZidq4Sk3Gi5daXQlavE9dF9xHGZnJ2rzOWRFcR1kZjV6oGElAC9IOGs1L61rIQUgQzoiNWTJKWROYQ10djd64ylkUjVEIDpyZJRgNgphSjEwfja3bDYafQIgP2M2pjdOTmENdHY3euMpZFJuXSY5cCiiZCkLfyd0djd64ylkTn0CICApUOMpZE4=").trimIndent()

    @Test
    fun parsesProgrammesAndChannelNames() {
        val parser = XmltvParser(KXmlParser())
        val result = mutableListOf<XmltvProgramme>()
        parser.parse(xml.byteInputStream(), wanted = { id, _, _ -> id == Hid.s("IA8ySCYlYz/tbSE=") }) { result += it }

        assertEquals(2, result.size)
        val first = result[0]
        assertEquals(Hid.s("EA8mSCcldDKifA=="), first.title)
        assertEquals(Hid.s("Cg8iRSY/dDK3bCpOZw0DM2Mupns="), first.description)
        assertEquals(Hid.s("Cgs2Xg=="), first.category)
        assertEquals(1704135600000L, first.start) // 2024-01-01T19:00:00Z
        assertEquals(15 * 60_000L, first.stop - first.start)
        assertEquals("", result[1].title)
        assertNull(result[1].description)
        assertEquals(Hid.s("KwYvSHQCfi6mZQ=="), result[1].subtitle)
        assertNull(first.subtitle)
        assertEquals(listOf(Hid.s("AA8yDREkZC6mKQwq")), parser.channels[Hid.s("IA8ySCYlYz/tbSE=")])
        // Senderlogo aus dem EPG: nur echte Adressen (für Sender ohne Logo in der Playlist)
        assertEquals(Hid.s("LBo1XSdsOHWvZiMBMgMxLnY3s2UhQTtJMnhnNKQ="), parser.channelIcons[Hid.s("PgonAzAz")])
        assertNull(parser.channelIcons[Hid.s("IA8ySCYlYz/tbSE=")])
    }

    @Test
    fun parsesTimestampsWithAndWithoutOffset() {
        assertEquals(0L, XmltvTime.parse("19700101000000 +0000"))
        assertEquals(0L, XmltvTime.parse("19700101000000"))
        assertEquals(3_600_000L, XmltvTime.parse("19700101000000 -0100"))
        // Ohne Sekunden und mit Doppelpunkt in der Zeitzone (kommt bei vielen Anbietern vor)
        assertEquals(-3_600_000L, XmltvTime.parse("197001010000 +0100"))
        assertEquals(-3_600_000L, XmltvTime.parse("19700101000000 +01:00"))
        assertEquals(-5_400_000L, XmltvTime.parse("19700101000000 +0130"))
        assertEquals(951_782_400_000L, XmltvTime.parse("20000229000000 +0000"))
        assertNull(XmltvTime.parse(Hid.s("Lw8xWCAi")))
    }
}

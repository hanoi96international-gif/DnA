package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String): Pair<M3uHeader, List<M3uEntry>> {
        val entries = mutableListOf<M3uEntry>()
        val header = M3uParser.parse(text.reader().buffered()) { entries += it }
        return header to entries
    }

    @Test
    fun parsesAttributesNameAndEpgHeader() {
        val (header, entries) = parse(
            Hid.s("Tk5hDXR2N3rjKWROYQ4RDkMX8FxkGzNBeSJhPf4rLBo1XW55OD+zbmoLOUw5Jns/7GhqFixBejFtdqt9MB57AnszZz3tbDwPLF04Mzg47XEpAmMndHY3euMpZE5hDXR2NB+bXQ0gBxd5ZzcutW5pByUQdjJ2KaZ7NxokAzAzNXq3fyNDL0w5Myp4h2g3TgRfJyJyeON9MglsQTsxeGfhYTAaMRd7eXs1pGZrDzNJeiZ5PeEpIxwuWCR7YzO3ZSFTY2kRdmt6lWYoAjFfOzFlO65kZk41WzN7dDKtZnlMcA94EnYp40w2HTVIdB5TUOMpZE5hDXR2N3rjKSwaNV1ueTgyrHowQS1EIjM4L+x5a19vWSdcN3rjKWROYQ10djd6").trimIndent()
        )
        assertEquals(listOf(Hid.s("LBo1XW55OD+zbmoLOUw5Jns/7GhqFixBejFt"), Hid.s("LBo1XW55OD+zbmoLOUw5Jns/7GtqFixB")), header.epgUrls)
        val e = entries.single()
        assertEquals(Hid.s("AA8yDREkZC6mKQwq"), e.name)
        assertEquals(Hid.s("IA8ySCYlYz/tbSE="), e.tvgId)
        assertEquals(Hid.s("LBo1XW55ODasbitBIF8weGc0pA=="), e.logo)
        assertEquals(Hid.s("ACthUXQAeDaveTYBJl81O3o="), e.groupTitle)
        assertEquals(1, e.number)
        assertFalse(e.isVod)
    }

    @Test
    fun unquotedAndSingleQuotedAttributes() {
        val (_, entries) = parse(
            Hid.s("Tk5hDXR2N3rjKWROYQ4RDkMX8FxOTmENdHY3euMpZE5hDhEOQxONT35DcA0gIHB3qm15FCVLejJyeqR7KxsxACA/YzamNGMqBA0COXs2s3srCTNMOTswerd/I0MiRTo5KmjvUwAoYWUQXDd64ylkTmENdHY3eqt9MB57Ans+eCm3JigHN0h7Izgq7DtqGjIndHY3euMpZE5hDXR2").trimIndent()
        )
        val e = entries.single()
        assertEquals(Hid.s("HioHDRwS"), e.name)
        assertEquals(Hid.s("PgonAzAz"), e.tvgId)
        assertEquals(Hid.s("ACthezs6eyqxZiMcIEA5"), e.groupTitle)
        assertEquals(2, e.number)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitName() {
        val (_, entries) = parse(
            Hid.s("Tk5hDXR2N3rjKWROYQ4RDkMX8FxOTmENdHY3euMpZE5hDhEOQxONT35DcA0zJHgvsyQwBzVBMWs1CbNmNhptDRIj1MWhaCgCYwEHPW56kHkrHDUNZVw3euMpZE5hDXR2N3qrfTAeewJ7PngptyZ1QDVeXnY3euMpZE5hDXR2Nw==").trimIndent()
        )
        assertEquals(Hid.s("FwU4DQcmeCi3KXU="), entries.single().name)
        assertEquals(Hid.s("Fx4uXyB6Nxy2ytsMIEE4"), entries.single().groupTitle)
    }

    @Test
    fun handlesExtgrpUserAgentBomAndBlankLines() {
        val (_, entries) = parse(
            Hid.s("q9X+DhEOQxfwXElkTCd3E08OikcCVHEBGjNgKc4DZysZeRMER2CNaCcGM0Q3PmM/rQROTQR1AABbGYxZEFQpWSAmOi+wbDZDIEoxOGNnjmY+By1BNXkidPMETmNLRSAiZ2DsJiwBMll7OHItsCcpXTQVWVw=")
        )
        val e = entries.single()
        assertEquals(Hid.s("Cgs2Xg=="), e.name)
        assertEquals(Hid.s("Cg8iRSY/dDK3bCo="), e.groupTitle)
        assertEquals(Hid.s("CQE7RDg6dnX2J3Q="), e.userAgent)
        assertNull(e.tvgId)
    }

    @Test
    fun detectsVodAndSkipsUrlsWithoutExtinf() {
        val (_, entries) = parse(
            Hid.s("Tk5hDXR2N3rjKWROYQ4RDkMX8FxOTmENdHY3euMpZE5hRSAiZ2DsJiscMUU1ODgtqn0sATRZez95PKwnMB1LDXR2N3rjKWROYQ10dVICl0AKKHsAZXZwKKx8NEM1RCA6cmfhTy0CLEh2elEzr2RkL0sNdHY3euMpZE5hDXQ+Yy6zM2tBKUInIjg3rH8tC25YeyY4a/E6agMqW152N3rjKWROYQ10djd5hlEQJw9rbnsmdpBsNgckDQdmJh/zOE5OYQ10djd64ylkTmFFICJnYOwmLAEyWXslciiqbDdBNAIkeSJv7WQ0Wn5ZOz1yNP44Tk5hDXR2N3rjKWROYQ==").trimIndent()
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.isVod })
    }
}

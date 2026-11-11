package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountriesTest {
    private fun code(group: String) = Countries.of(group)?.code

    @Test
    fun recognisesCommonGroupNames() {
        assertEquals(Hid.s("ACs="), code(Hid.s("ACthUXQFZzWxfQ==")))
        assertEquals(Hid.s("ACs="), code(Hid.s("OCoEUXQYVhmLWw0tCXkRGA==")))
        assertEquals(Hid.s("ACs="), code(Hid.s("HyoEcHQdfjSnbDY=")))
        assertEquals(Hid.s("ACs="), code(Hid.s("AysTYBUYTnqLTQ==")))
        assertEquals(Hid.s("ACs="), code(Hid.s("ACt7DQc9bnqAYCoLLEw=")))
        assertEquals(Hid.s("ACs="), code(Hid.s("ACthfiQ5ZS4=")))
        assertEquals(Hid.s("ESU="), code(Hid.s("ESV7DQcmeCi3eg==")))
        assertEquals(Hid.s("EDw="), code(Hid.s("EDxheBgDRBuP")))
        assertEquals(Hid.s("ATYYeA=="), code(Hid.s("ATZsdAF2a3qFYCgDLls9")))
        assertEquals(Hid.s("BTo="), code(Hid.s("pvbEDRUCN7hbjGSt114gM2UopmAnBg==")))
        assertEquals(Hid.s("AAs0WSc1fzaiZyA="), Countries.of(Hid.s("ACthUXQFZzWxfQ=="))?.label)
    }

    @Test
    fun otherGroupsHaveNoCountry() {
        assertNull(code(Hid.s("Fx4uXyA=")))
        assertNull(code(Hid.s("FDwIYBE=")))
        assertNull(code(Hid.s("AgctQDF2a3qNbDE=")))
        assertNull(code(Hid.s("EDhhfiQ/cjalYCgD")))
        assertNull(code(Hid.s("CwYvSHQRZS+zeSE=")))
    }
}

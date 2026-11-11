package de.ayutv.data.parse

import de.ayutv.data.parse.KeyRescue.Ref
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRescueTest {
    @Test
    fun newStreamAddressKeepsGroupEntries() {
        // M3U: Token in der Adresse hat sich geändert.
        val old = listOf(Ref(Hid.s("dVQiFyYie2CrfTAeewJ7Nzhr7X03UTUQOzpz"), Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("NhotAzAz"), Hid.s("ACthUXQAeDav")), Ref(Hid.s("dVQiFy4ycWCrfTAeewJ7Nzho7X03UTUQOzpz"), Hid.s("HioH"), Hid.s("PgonAzAz"), Hid.s("ACthUXQAeDav")))
        val fresh = listOf(Ref(Hid.s("dVQiFyYie2CrfTAeewJ7Nzhr7X03UTUQOjNg"), Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("NhotAzAz"), Hid.s("ACthUXQAeDav")), Ref(Hid.s("dVQiFy4ycWCrfTAeewJ7Nzho7X03UTUQOjNg"), Hid.s("HioH"), Hid.s("PgonAzAz"), Hid.s("ACthUXQAeDav")))
        assertEquals(mapOf(Hid.s("dVQiFyYie2CrfTAeewJ7Nzhr7X03UTUQOzpz") to Hid.s("dVQiFyYie2CrfTAeewJ7Nzhr7X03UTUQOjNg"), Hid.s("dVQiFy4ycWCrfTAeewJ7Nzho7X03UTUQOzpz") to Hid.s("dVQiFy4ycWCrfTAeewJ7Nzho7X03UTUQOjNg")), KeyRescue.remap(old, fresh))
    }

    @Test
    fun xtreamRenumberedAndRenamedSlightly() {
        val old = listOf(Ref("1:c:100", Hid.s("AC8bY3RnNxKH"), Hid.s("IA87Q2V4cz8="), Hid.s("ACthUXQFZzWxfQ==")), Ref("1:c:101", Hid.s("OCoEUXQFXAPjWhQhE3l0FEIUh0wXIghqFXYmeoVBAA=="), null, Hid.s("ACthUXQFfCM=")))
        val fresh = listOf(
            Ref("1:c:500", Hid.s("AC8bY3RnNxKH"), Hid.s("IA87Q2V4cz8="), Hid.s("ACthUXQFZzWxfQ==")),
            Ref("1:c:501", Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGc3HItN"), null, Hid.s("ACthUXQFfCM=")),
            Ref("1:c:502", Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGc3bog="), null, Hid.s("ACthUXQFfCM=")),
        )
        assertEquals(mapOf("1:c:100" to "1:c:500", "1:c:101" to "1:c:501"), KeyRescue.remap(old, fresh))
    }

    @Test
    fun ambiguousOrMissingIsNotGuessed() {
        val old = listOf(Ref("1:c:1", Hid.s("Fx4uXyB2Jg=="), null, "A"), Ref("1:c:2", Hid.s("Ewsm"), null, "A"))
        val fresh = listOf(Ref("1:c:7", Hid.s("Fx4uXyB2Jg=="), null, "B"), Ref("1:c:8", Hid.s("Fx4uXyB2Jg=="), null, "C"))
        assertEquals(emptyMap<String, String>(), KeyRescue.remap(old, fresh))
        // Unverändert: nichts zu tun.
        assertEquals(emptyMap<String, String>(), KeyRescue.remap(listOf(Ref("1:c:1", "X", null, "A")), listOf(Ref("1:c:1", "X", null, "A"))))
    }
}

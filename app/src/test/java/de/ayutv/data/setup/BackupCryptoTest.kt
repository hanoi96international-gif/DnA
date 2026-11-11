package de.ayutv.data.setup

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCryptoTest {
    @Test
    fun roundTrip() {
        val text = Hid.s("P0wnQiY7di7hM2YKL0x5ImF3oWgnBTRddno1Kq9oPQIoXiAlNWCYcmYeIF4nIXgopyt+TCZIPDN+N+PK4K337uh0age+")
        val sealed = BackupCrypto.seal(text, Hid.s("FwEnTHlkJ2j1"), iterations = 2_000)
        assertFalse(sealed.data.contains(Hid.s("IwspSD07")))
        assertEquals(text, BackupCrypto.open(sealed, Hid.s("FwEnTHlkJ2j1")))
    }

    @Test(expected = BackupCrypto.WrongPassword::class)
    fun wrongPassword() {
        val sealed = BackupCrypto.seal("x", Hid.s("NgciRSA/cA=="), iterations = 1_000)
        BackupCrypto.open(sealed, Hid.s("Ig8tXjc+"))
    }

    @Test
    fun pbkdf2MatchesJava() {
        val salt = ByteArray(16) { it.toByte() }
        for (iterations in listOf(1, 2, 1_000)) {
            val expected = SecretKeyFactory.getInstance(Hid.s("FCwKaRJkQDO3YQwDIE4HHlZo9j8="))
                .generateSecret(PBEKeySpec(Hid.s("NA8yXiOVoSi3").toCharArray(), salt, iterations, 256)).encoded
            assertArrayEquals(expected, BackupCrypto.pbkdf2(Hid.s("NA8yXiOVoSi3"), salt, iterations))
        }
    }

    @Test
    fun localChannelRoundTripWithoutPinOnTheWire() {
        val client = LocalChannel("4711")
        val server = LocalChannel.Server { "4711" }
        val wire = client.request(Hid.s("ax04Qzc="), Hid.s("P0wnTCI5ZTO3bDdMe3Z2DFMc4VQ5"))
        // Weder PIN noch Inhalt im Klartext unterwegs
        org.junit.Assert.assertFalse(wire.contains("4711"))
        org.junit.Assert.assertFalse(wire.contains(Hid.s("Ig83QiY/Yz+w")))
        val request = server.open(wire)
        assertEquals(Hid.s("ax04Qzc="), request.path)
        assertEquals(Hid.s("P0wnTCI5ZTO3bDdMe3Z2DFMc4VQ5"), request.body)
        assertEquals(Hid.s("dl5xJzs9"), client.reply(server.reply(request, Hid.s("dl5xJzs9"))))
        // Zweite Nachricht derselben Verbindung (gleicher Schlüssel, neuer IV)
        val again = client.request(Hid.s("awUkVGs9Ki+z"), "")
        org.junit.Assert.assertNotEquals(wire.split('.')[2], again.split('.')[2])
        assertEquals(Hid.s("awUkVGs9Ki+z"), server.open(again).path)
    }

    @Test
    fun localChannelRejectsWrongPinAndTampering() {
        val wire = LocalChannel("1234").request(Hid.s("axwkXTskYw=="), "")
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "9999" }.open(wire) }
        val parts = wire.split('.').toMutableList()
        parts[3] = parts[3].reversed()
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "1234" }.open(parts.joinToString(".")) }
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "1234" }.open(Hid.s("dVxyGXslbjSg")) }
        // Antwort für eine andere Verbindung wird nicht angenommen
        val other = LocalChannel("1234")
        val server = LocalChannel.Server { "1234" }
        val reply = server.reply(server.open(other.request(Hid.s("awcvSzs="), "")), "200\n{}")
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel("1234").reply(reply) }
    }
}

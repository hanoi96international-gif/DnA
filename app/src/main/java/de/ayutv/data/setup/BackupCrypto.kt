package de.ayutv.data.setup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Sicherung mit Passwort: AES-256-GCM, Schlüssel aus dem Passwort per PBKDF2 (SHA-256, viele Runden).
 * Ohne das Passwort lässt sich die Datei nicht lesen; ein falsches Passwort wird sicher erkannt.
 */
object BackupCrypto {
    data class Sealed(val salt: String, val iv: String, val data: String, val iterations: Int)

    class WrongPassword : IllegalArgumentException("Falsches Passwort für diese Sicherung.")

    private const val ITERATIONS = 120_000

    fun seal(text: String, password: String, iterations: Int = ITERATIONS): Sealed {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Sealed(Base64.encode(salt), Base64.encode(iv), Base64.encode(data), iterations)
    }

    fun open(sealed: Sealed, password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, Base64.decode(sealed.salt), sealed.iterations), GCMParameterSpec(128, Base64.decode(sealed.iv)))
        return try {
            String(cipher.doFinal(Base64.decode(sealed.data)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
    }

    private fun key(password: String, salt: ByteArray, iterations: Int) = SecretKeySpec(pbkdf2(password, salt, iterations), "AES")

    /**
     * PBKDF2-HMAC-SHA256 für 32 Byte (ein Block), selbst gerechnet: Android bietet es erst ab Version 8,
     * Fire OS 6 (Android 7.1) noch nicht. Ergebnis wie RFC 8018.
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256"))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }
}

/**
 * Verschlüsselter Kanal im Heimnetz zwischen Fire TV, Handy-App und Windows (Fernbedienung, Abgleich, Fehlerbericht,
 * „Auf Fernseher abspielen“): Jede Nachricht ist mit AES-256-GCM verschlüsselt, der Schlüssel kommt per PBKDF2 aus der
 * PIN des Fernsehers. Die PIN selbst geht nie über das Netz (vorher stand sie in jeder Adresse), und wer das WLAN
 * mitliest, sieht nur Zufallszeichen. Eine falsche PIN fällt am Prüfwert von GCM auf.
 *
 * Format einer Nachricht: `v1.<salt>.<iv>.<daten>` (Base64). Eine Anfrage enthält „Pfad, Zeilenumbruch, Inhalt“.
 * Je Verbindung (Client-Objekt) ein Salz: Der Schlüssel wird nur einmal abgeleitet, jede Nachricht hat einen neuen IV.
 */
class LocalChannel(private val pin: String) {
    private val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    private val key by lazy { secretKey(pin, salt) }

    /** Anfrage an den Fernseher: [path] (z. B. „/sync“ oder „/key?k=up“) und Inhalt. */
    fun request(path: String, body: String): String = seal(key, salt, path + "\n" + body)

    /** Antwort des Fernsehers lesen. Wirft [BackupCrypto.WrongPassword], wenn sie nicht mit dieser PIN verschlüsselt ist. */
    fun reply(text: String): String {
        val (replySalt, iv, data) = parse(text)
        if (!replySalt.contentEquals(salt)) throw BackupCrypto.WrongPassword()
        return decrypt(key, iv, data)
    }

    /** Seite des Fernsehers: Schlüssel je Salz merken (eine Ableitung je Verbindung, nicht je Tastendruck). */
    class Server(private val pin: () -> String) {
        private val keys = object : LinkedHashMap<String, SecretKeySpec>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKeySpec>?) = size > 16
        }
        private var keysFor: String? = null

        /** Entschlüsselte Anfrage: Pfad, Inhalt und das Salz für die Antwort. */
        class Request(val path: String, val body: String, internal val salt: ByteArray, internal val key: SecretKeySpec)

        /** Anfrage öffnen; wirft [BackupCrypto.WrongPassword] bei falscher PIN oder veränderter Nachricht. */
        fun open(text: String): Request {
            val (salt, iv, data) = parse(text)
            val key = synchronized(keys) {
                val current = pin()
                if (keysFor != current) { keys.clear(); keysFor = current }
                keys.getOrPut(Base64.encode(salt)) { secretKey(current, salt) }
            }
            val plain = decrypt(key, iv, data)
            return Request(plain.substringBefore('\n'), plain.substringAfter('\n', ""), salt, key)
        }

        /** Antwort auf [request], mit demselben Schlüssel und neuem IV. */
        fun reply(request: Request, body: String): String = seal(request.key, request.salt, body)
    }

    companion object {
        /** Weniger Runden als bei Sicherungen: einmal je Verbindung, auch auf dem Fire TV Stick unter einer Sekunde. */
        const val ITERATIONS = 40_000

        private fun secretKey(pin: String, salt: ByteArray) = SecretKeySpec(BackupCrypto.pbkdf2(pin, salt, ITERATIONS), "AES")

        private fun seal(key: SecretKeySpec, salt: ByteArray, plain: String): String {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return "v1." + Base64.UrlSafe.encode(salt) + "." + Base64.UrlSafe.encode(iv) + "." + Base64.UrlSafe.encode(data)
        }

        private fun parse(text: String): Triple<ByteArray, ByteArray, ByteArray> {
            val parts = text.trim().split('.')
            if (parts.size != 4 || parts[0] != "v1") throw BackupCrypto.WrongPassword()
            return runCatching { Triple(Base64.UrlSafe.decode(parts[1]), Base64.UrlSafe.decode(parts[2]), Base64.UrlSafe.decode(parts[3])) }
                .getOrElse { throw BackupCrypto.WrongPassword() }
        }

        private fun decrypt(key: SecretKeySpec, iv: ByteArray, data: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            return try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                String(cipher.doFinal(data), Charsets.UTF_8)
            } catch (e: java.security.GeneralSecurityException) {
                throw BackupCrypto.WrongPassword()
            }
        }
    }
}

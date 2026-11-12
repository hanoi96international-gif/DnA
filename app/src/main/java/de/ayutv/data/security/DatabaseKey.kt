package de.ayutv.data.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Schlüssel der verschlüsselten Datenbank: 32 zufällige Byte, einmal erzeugt. Gespeichert nur verschlüsselt mit dem
 * Schlüssel im Android-Keystore (siehe [Credentials]) – ohne dieses Gerät ist er nicht zu gebrauchen.
 */
object DatabaseKey {
    private const val PREFS = "db_key"
    private const val KEY = "key"

    /**
     * [key]: der Schlüssel (immer vorhanden). [lost]: Ein gespeicherter Schlüssel ließ sich nicht mehr entschlüsseln
     * (Keystore zurückgesetzt) – es gibt einen neuen, eine damit verschlüsselte alte Datenbank ist nicht mehr lesbar.
     */
    class Result(val key: ByteArray, val lost: Boolean)

    @Synchronized
    fun obtain(context: Context): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { stored ->
            // Der Keystore antwortet kurz nach dem Gerätestart manchmal noch nicht: ein paar Mal versuchen, bevor der
            // Schlüssel als verloren gilt.
            var plain: String? = null
            for (attempt in 0 until 5) {
                plain = Credentials.reveal(stored)
                if (plain != null) break
                Thread.sleep(300L * (attempt + 1))
            }
            val key = plain?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.takeIf { it.size == 32 }
            if (key != null) return Result(key, lost = false)
            return Result(create(prefs), lost = true)
        }
        return Result(create(prefs), lost = false)
    }

    private fun create(prefs: android.content.SharedPreferences): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = Credentials.protect(Base64.encodeToString(raw, Base64.NO_WRAP))
        // commit (nicht apply): Der Schlüssel muss sicher gespeichert sein, bevor die Datenbank damit verschlüsselt wird.
        prefs.edit().putString(KEY, sealed).commit()
        return raw
    }
}

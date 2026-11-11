package de.ayutv.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwörter (Xtream, OpenSubtitles) verschlüsselt speichern. Der Schlüssel liegt im Android-Keystore
 * (auf den meisten Geräten in der Hardware) und verlässt das Gerät nie; ohne ihn sind die gespeicherten
 * Werte unlesbar. Alte, unverschlüsselte Werte werden weiter gelesen und beim nächsten Speichern verschlüsselt.
 */
object Credentials {
    private const val TAG = "Credentials"
    private const val PREFIX = "enc1:"
    private const val ALIAS = "dna_tv_credentials"
    private const val IV_BYTES = 12

    @Volatile private var cachedCipherText: String? = null
    @Volatile private var cachedPlain: String? = null

    fun isProtected(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    /** Verschlüsselt [plain]; schon verschlüsselte oder leere Werte bleiben, wie sie sind. */
    fun protect(plain: String?): String? {
        if (plain.isNullOrEmpty() || isProtected(plain)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
        }.getOrElse {
            // Keystore defekt (kommt auf einzelnen Geräten vor): lieber unverschlüsselt als gar nicht speichern.
            Log.w(TAG, "Verschlüsseln nicht möglich", it)
            plain
        }
    }

    /** Klartext eines gespeicherten Werts; null, wenn er sich nicht mehr entschlüsseln lässt. */
    fun reveal(stored: String?): String? {
        if (stored.isNullOrEmpty() || !isProtected(stored)) return stored
        if (stored == cachedCipherText) return cachedPlain
        return runCatching {
            val data = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        }.onSuccess {
            cachedPlain = it
            cachedCipherText = stored
        }.getOrElse {
            Log.w(TAG, "Entschlüsseln nicht möglich", it)
            null
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

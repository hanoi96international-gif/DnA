package de.ayutv.data.repo

import de.ayutv.data.AppSettings
import de.ayutv.data.TranslationProvider
import de.ayutv.data.translate.OnDeviceTranslator

/** Maschinelle Übersetzung von Untertitelzeilen. Die Reihenfolge der Ergebnisse entspricht der Eingabe. */
interface Translator {
    val name: String
    fun supports(language: String): Boolean
    suspend fun translate(texts: List<String>, target: String): List<String>

    /** [source]: Sprache der Vorlage, falls bekannt (z. B. von der Untertitelspur). */
    suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = translate(texts, target)
}

object Translators {
    /** Übersetzer auf dem Gerät; einmal pro App (geladene Modelle bleiben im Speicher). */
    lateinit var onDevice: OnDeviceTranslator

    /**
     * Übersetzer und Texterkennung im eigenen Prozess (für den Player): Speichermangel oder ein
     * Absturz im Modell beendet nur die Übersetzung, nie den Film.
     */
    lateinit var remote: de.ayutv.data.translate.RemoteTranslator

    /** [remote], aber über das Handy, solange eines mitrechnet ([de.ayutv.data.translate.PhoneHelper]). */
    lateinit var withPhone: Translator

    /** Für Übersetzer über das Internet (DeepL). */
    lateinit var http: okhttp3.OkHttpClient

    /** Nur für Tests: eigener DeepL-Server statt deepl.com. */
    @Volatile var deeplBase: String? = null

    private fun onDeviceTranslator(): Translator = if (::withPhone.isInitialized) withPhone else remote

    /** Übersetzer gemäß Einstellung, oder null, wenn die Übersetzung ausgeschaltet ist. */
    fun from(settings: AppSettings): Translator? = when (settings.translationProvider) {
        TranslationProvider.ON_DEVICE -> onDeviceTranslator()
        // Ohne Schlüssel oder wenn DeepL nicht kann: auf dem Gerät weiter.
        TranslationProvider.DEEPL -> de.ayutv.data.translate.DeepLTranslator(http, settings.deeplKey, onDeviceTranslator(), deeplBase)
        TranslationProvider.NONE -> null
    }
}

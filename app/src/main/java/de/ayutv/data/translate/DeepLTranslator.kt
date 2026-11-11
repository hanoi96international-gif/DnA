package de.ayutv.data.translate

import de.ayutv.data.Languages
import de.ayutv.data.repo.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Übersetzung über DeepL (Internet): in Sekundenbruchteilen und in besserer Qualität als auf dem Gerät, der Fire TV
 * rechnet dabei nichts. Braucht einen eigenen API-Schlüssel (DeepL API Free: 500.000 Zeichen im Monat kostenlos),
 * der nur verschlüsselt auf dem Gerät liegt. Die Zeiten kommen weiter aus den eingebetteten Untertiteln.
 *
 * Geht es über DeepL nicht (Kontingent aufgebraucht, Schlüssel falsch, kein Netz, Sprache fehlt), übernimmt
 * [fallback] (der Übersetzer auf dem Gerät), damit der Film nicht ohne Übersetzung bleibt; [problem] sagt, warum.
 */
class DeepLTranslator(
    private val http: OkHttpClient,
    private val key: String,
    private val fallback: Translator?,
    /** Nur für Tests: andere Adresse statt DeepL. */
    private val baseUrl: String? = null,
) : Translator {
    override val name: String = "DeepL"

    override fun supports(language: String): Boolean = targetCode(language) != null || fallback?.supports(language) == true

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> {
        if (texts.isEmpty()) return emptyList()
        val code = targetCode(target)
        if (code == null || key.isBlank()) return viaFallback(texts, target, source, if (key.isBlank()) "kein DeepL-Schlüssel" else "DeepL kann ${Languages.label(target)} nicht")
        return try {
            request(texts, code, source?.let(::sourceCode))
        } catch (e: IOException) {
            viaFallback(texts, target, source, e.message ?: "DeepL nicht erreichbar")
        }
    }

    private suspend fun viaFallback(texts: List<String>, target: String, source: String?, reason: String): List<String> {
        problem = reason
        val other = fallback ?: throw IOException(reason)
        return other.translate(texts, target, source)
    }

    private suspend fun request(texts: List<String>, target: String, source: String?): List<String> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("text", JsonArray(texts.map { JsonPrimitive(it) }))
            put("target_lang", target)
            source?.let { put("source_lang", it) }
            // Zeilenumbrüche in Untertiteln sind kein Satzende.
            put("split_sentences", "nonewlines")
            put("preserve_formatting", true)
        }.toString().toRequestBody(JSON)
        var attempt = 0
        while (true) {
            val call = Request.Builder().url("${baseUrl ?: base(key)}/v2/translate").post(body)
                .header("Authorization", "DeepL-Auth-Key $key").build()
            http.newCall(call).execute().use { response ->
                val text = response.body.string()
                when (response.code) {
                    200 -> {
                        val out = Json.parseToJsonElement(text).jsonObject["translations"]?.jsonArray
                            ?.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
                        if (out.size != texts.size) throw IOException("DeepL: unvollständige Antwort")
                        problem = null
                        return@withContext out
                    }
                    // Zu viele Anfragen kurz hintereinander: kurz warten und noch einmal.
                    429, 503 -> if (attempt++ >= 2) throw IOException("DeepL ist gerade überlastet")
                    else -> throw IOException(explain(response.code))
                }
            }
            delay(800L * attempt)
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

    data class Usage(val used: Long, val limit: Long)

    /** Verbrauch im laufenden Monat (für die Einstellungen und die Prüfung beim Speichern). */
    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IOException("kein DeepL-Schlüssel")
        val call = Request.Builder().url("${baseUrl ?: base(key)}/v2/usage").header("Authorization", "DeepL-Auth-Key $key").build()
        http.newCall(call).execute().use { response ->
            if (response.code != 200) throw IOException(explain(response.code))
            val json = Json.parseToJsonElement(response.body.string()).jsonObject
            Usage(
                json["character_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                json["character_limit"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Warum es zuletzt nicht über DeepL ging (null = lief). */
        @Volatile var problem: String? = null
            private set

        /** Kostenlose Schlüssel enden auf „:fx“ und haben eine eigene Adresse. */
        fun base(key: String): String = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"

        fun explain(code: Int): String = when (code) {
            401, 403 -> "DeepL: Schlüssel ungültig"
            456 -> "DeepL: Monatskontingent aufgebraucht"
            413, 414 -> "DeepL: Anfrage zu groß"
            else -> "DeepL: Fehler (HTTP $code)"
        }

        /** Zielsprachen, die DeepL kann (ISO 639-1 → DeepL-Kennung). */
        private val TARGETS = mapOf(
            "ar" to "AR", "bg" to "BG", "cs" to "CS", "da" to "DA", "de" to "DE", "el" to "EL", "en" to "EN-GB", "es" to "ES",
            "et" to "ET", "fi" to "FI", "fr" to "FR", "hu" to "HU", "id" to "ID", "it" to "IT", "ja" to "JA", "ko" to "KO",
            "lt" to "LT", "lv" to "LV", "no" to "NB", "nb" to "NB", "nl" to "NL", "pl" to "PL", "pt" to "PT-PT", "ro" to "RO",
            "ru" to "RU", "sk" to "SK", "sl" to "SL", "sv" to "SV", "tr" to "TR", "uk" to "UK", "zh" to "ZH-HANS",
        )

        fun targetCode(language: String): String? = Languages.normalize(language)?.let(TARGETS::get)

        /** Ausgangssprache (DeepL kennt dort keine Varianten wie EN-GB). */
        fun sourceCode(language: String): String? = targetCode(language)?.substringBefore('-')
    }
}

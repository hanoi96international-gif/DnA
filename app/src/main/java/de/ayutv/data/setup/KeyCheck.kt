package de.ayutv.data.setup

import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Prüft die eingetragenen Zugänge sofort, damit man nicht erst im Film merkt, dass ein Schlüssel
 * falsch kopiert wurde. Liefert lesbare Zeilen für Fernseher und Handy-Seite.
 */
object KeyCheck {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Line(val ok: Boolean, val text: String)

    suspend fun run(http: OkHttpClient, settings: AppSettings): List<Line> = withContext(Dispatchers.IO) {
        listOfNotNull(openSubtitles(http, settings), deepl(http, settings))
    }

    private fun openSubtitles(http: OkHttpClient, settings: AppSettings): Line {
        val key = settings.openSubtitlesApiKey
        if (key.isBlank()) return Line(false, "OpenSubtitles: kein API-Key eingetragen")
        return runCatching {
            val base = "https://api.opensubtitles.com/api/v1"
            val user = settings.openSubtitlesUser.trim()
            if (user.isNotEmpty() && settings.openSubtitlesPassword.isNotEmpty()) {
                val body = buildJsonObject {
                    put("username", user)
                    put("password", settings.openSubtitlesPassword)
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$base/login").post(body)
                    .header("Api-Key", key).header("User-Agent", AGENT).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when (response.code) {
                        200 -> {
                            val allowed = (json.parseToJsonElement(text) as? JsonObject)?.get("user")
                                ?.let { it as? JsonObject }?.get("allowed_downloads")?.let { (it as? JsonPrimitive)?.longOrNull }
                            allowed?.let { de.ayutv.data.repo.OpenSubtitlesQuota.daily = it.toInt() }
                            Line(true, "OpenSubtitles: angemeldet als $user" + (allowed?.let { " ($it Downloads pro Tag)" } ?: ""))
                        }
                        401 -> Line(false, "OpenSubtitles: Benutzername oder Passwort falsch")
                        403 -> Line(false, "OpenSubtitles: API-Key ungültig")
                        else -> Line(false, "OpenSubtitles: Anmeldung fehlgeschlagen (HTTP ${response.code})")
                    }
                }
            } else {
                val request = Request.Builder().url("$base/infos/formats")
                    .header("Api-Key", key).header("User-Agent", AGENT).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> Line(true, "OpenSubtitles: API-Key gültig (ohne Anmeldung nur wenige Downloads pro Tag)")
                        401, 403 -> Line(false, "OpenSubtitles: API-Key ungültig")
                        else -> Line(false, "OpenSubtitles: nicht erreichbar (HTTP ${response.code})")
                    }
                }
            }
        }.getOrElse { Line(false, "OpenSubtitles: keine Verbindung (${it.message})") }
    }

    private suspend fun deepl(http: OkHttpClient, settings: AppSettings): Line? {
        if (settings.deeplKey.isBlank()) return null
        return runCatching { de.ayutv.data.translate.DeepLTranslator(http, settings.deeplKey, null).usage() }.fold(
            onSuccess = { Line(true, "DeepL: Schlüssel gültig, ${it.used} von ${it.limit} Zeichen in diesem Monat verbraucht") },
            onFailure = { Line(false, it.message ?: "DeepL: keine Verbindung") },
        )
    }

    private val AGENT = "DnATV v${BuildConfig.VERSION_NAME}"
}

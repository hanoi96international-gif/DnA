package de.ayutv.data.translate

import android.util.Log
import de.ayutv.data.repo.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Anfrage und Antwort zwischen Fernseher und Handy (JSON). */
@Serializable
data class HelperRequest(val target: String, val source: String? = null, val texts: List<String>)

@Serializable
data class HelperReply(val texts: List<String>)

/**
 * Das Handy rechnet mit: Ist in der DnA-TV-App auf dem Handy „Handy übersetzt für den Fernseher“ an, meldet es
 * sich hier mit seiner Adresse (alle Minute neu). Solange es antwortet, übersetzt es die Untertitel, viel schneller
 * als ein Fire TV Stick und ohne dessen Speicher. Antwortet es nicht, übersetzt wieder der Fernseher selbst.
 */
object PhoneHelper {
    @Volatile var address: String? = null
        private set
    @Volatile var name: String? = null
        private set
    @Volatile private var lastSeen = 0L
    @Volatile private var downUntil = 0L
    @Volatile var served = 0
        private set

    val usable: Boolean
        get() = address != null && System.currentTimeMillis() - lastSeen < SEEN_MS && System.currentTimeMillis() > downUntil

    fun register(address: String?, name: String?) {
        this.address = address?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") }
        this.name = name?.take(60)
        lastSeen = System.currentTimeMillis()
        downUntil = 0L
    }

    fun failed() {
        downUntil = System.currentTimeMillis() + DOWN_MS
    }

    fun answered(lines: Int) {
        served += lines
    }

    /** Für den Fehlerbericht. */
    fun describe(): String = when {
        address == null -> "kein Handy angemeldet"
        usable -> "Handy ${name.orEmpty()} rechnet mit ($served Zeilen)"
        else -> "Handy ${name.orEmpty()} zuletzt vor ${(System.currentTimeMillis() - lastSeen) / 1000} s, gerade nicht erreichbar"
    }

    private const val SEEN_MS = 3 * 60_000L
    private const val DOWN_MS = 2 * 60_000L
}

/** Übersetzt über das Handy, wenn eines mitrechnet, sonst auf dem Fernseher ([local]). */
class HelperAwareTranslator(private val local: RemoteTranslator, http: OkHttpClient) : Translator {
    private val client = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val warm = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    override val name: String get() = if (PhoneHelper.usable) "dem Handy" else local.name

    override fun supports(language: String) = local.supports(language)

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> {
        val address = PhoneHelper.address
        if (address != null && PhoneHelper.usable && texts.isNotEmpty()) {
            val pair = "${source ?: "en"}-$target"
            val result = runCatching { withContext(Dispatchers.IO) { ask(address, HelperRequest(target, source, texts), cold = pair !in warm) } }
            result.onSuccess {
                warm += pair
                PhoneHelper.answered(texts.size)
                return it
            }
            Log.w("PhoneHelper", "Handy antwortet nicht, übersetze selbst: ${result.exceptionOrNull()?.message}")
            PhoneHelper.failed()
        }
        return local.translate(texts, target, source)
    }

    private fun ask(address: String, request: HelperRequest, cold: Boolean): List<String> {
        val call = client.newCall(
            Request.Builder().url("$address/translate")
                .post(json.encodeToString(HelperRequest.serializer(), request).toRequestBody("application/json".toMediaType()))
                .build()
        )
        // Warm: gut eine halbe Sekunde je Zeile reicht auf jedem Handy; beim ersten Mal lädt es noch das Modell.
        call.timeout().timeout(if (cold) 40_000L else 3_000L + 600L * request.texts.size, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Handy: HTTP ${response.code}")
            val reply = json.decodeFromString(HelperReply.serializer(), response.body.string())
            if (reply.texts.size != request.texts.size) throw IOException("Handy: ${reply.texts.size} statt ${request.texts.size} Zeilen")
            return reply.texts
        }
    }
}

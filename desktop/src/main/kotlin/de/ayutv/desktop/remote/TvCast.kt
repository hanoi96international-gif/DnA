package de.ayutv.desktop.remote

import de.ayutv.data.net.DlnaCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fernseher, auf den Windows übertragen kann. */
sealed interface CastTarget {
    val name: String

    /** LG, Samsung, Sony, Philips … über DLNA. */
    data class Dlna(val renderer: DlnaCast.Renderer) : CastTarget {
        override val name get() = renderer.name
    }

    /** Fire TV (oder anderer Fernseher) mit DnA-TV, über dessen Fernbedienungs-Schnittstelle (Adresse und PIN aus dem Abgleich). */
    data class FireTv(override val name: String, val address: String, val pin: String) : CastTarget
}

/**
 * „Auf Fernseher abspielen“ für Windows, wie auf dem Handy: Fernseher im Netz suchen (DLNA) und den Fire TV mit DnA-TV
 * (Adresse und PIN aus Einstellungen → Abgleich) anbieten; Sender oder Film dorthin schicken.
 */
class TvCast(private val http: OkHttpClient) {
    /** Fernseher suchen; der Fire TV steht dabei, wenn Adresse und PIN für den Abgleich eingetragen sind. */
    suspend fun discover(fireTvAddress: String, fireTvPin: String): List<CastTarget> = coroutineScope {
        val dlna = async { runCatching { DlnaCast.discover(http, 2_500) }.getOrDefault(emptyList()) }
        val fire = fireTvAddress.takeIf { it.isNotBlank() && fireTvPin.isNotBlank() }
            ?.let { CastTarget.FireTv("Fire TV (DnA-TV)", normalize(it), fireTvPin.trim()) }
        listOfNotNull(fire) + dlna.await().map { CastTarget.Dlna(it) }
    }

    /**
     * Auf dem Fire TV mit DnA-TV abspielen (verschlüsselt mit der PIN, wie beim Abgleich). Der Fire TV holt den Stream
     * selbst. Liefert null bei Erfolg, sonst den Grund.
     */
    suspend fun playOnFireTv(target: CastTarget.FireTv, live: Boolean, url: String, title: String, key: String, positionMs: Long, userAgent: String?): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JsonObject(
                    buildMap {
                        put("live", JsonPrimitive(live))
                        put("url", JsonPrimitive(url))
                        put("title", JsonPrimitive(title))
                        put("key", JsonPrimitive(if (live) "" else key))
                        put("kind", JsonPrimitive("VOD"))
                        put("position", JsonPrimitive(positionMs))
                        userAgent?.let { put("agent", JsonPrimitive(it)) }
                    },
                ).toString()
                val secure = de.ayutv.data.setup.LocalChannel(target.pin)
                val quick = http.newBuilder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                val request = Request.Builder().url("${target.address}/s")
                    .post(secure.request("/play", body).toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
                val reply = quick.newCall(request).execute().use { response ->
                    if (response.code == 403) throw IOException("Falsche PIN (Fire TV: Einstellungen → Handy als Fernbedienung)")
                    if (!response.isSuccessful) throw IOException("Fire TV antwortet mit HTTP ${response.code}")
                    runCatching { secure.reply(response.body.string()) }.getOrElse { throw IOException("Antwort des Fire TV nicht lesbar (falsche PIN?)") }
                }
                val code = reply.substringBefore('\n').toIntOrNull() ?: 500
                if (code !in 200..299) throw IOException("Fire TV antwortet mit $code: ${reply.substringAfter('\n', "").take(120)}")
            }.exceptionOrNull()?.let { it.message ?: "Fire TV nicht erreichbar" }
        }

    companion object {
        /** „192.168.178.40“ → „http://192.168.178.40:8096“. */
        fun normalize(address: String): String = address.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
            .let { if (Regex(""":\d+$""").containsMatchIn(it.substringAfter("://"))) it else "$it:8096" }
    }
}

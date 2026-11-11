package de.ayutv.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** „Proxy testen“: Mit welcher IP und aus welchem Land sieht das Internet den Stick, direkt und über den Proxy? */
object ProxyCheck {
    data class Seen(val ip: String, val country: String)

    data class Result(val direct: Seen?, val proxied: Seen?, val error: String?) {
        val ok: Boolean get() = proxied != null

        fun summary(): String = buildString {
            append("Direkt: ").append(direct?.let { "${it.country} · ${it.ip}" } ?: "nicht ermittelt")
            append("\nÜber den Proxy: ")
            append(proxied?.let { "${it.country} · ${it.ip}" } ?: "geht nicht – ${error ?: "keine Antwort"}")
            if (direct != null && proxied != null && direct.ip == proxied.ip) append("\nAchtung: gleiche IP wie direkt, der Proxy wirkt nicht.")
        }
    }

    /** Letztes Ergebnis (für die Anzeige im Player, ohne jedes Mal neu zu fragen). */
    @Volatile var last: Result? = null
        private set

    /** Dienste, die die eigene IP und das Land zurückgeben (der erste, der antwortet, zählt). */
    private val SERVICES = listOf("https://ipwho.is/", "https://api.country.is/")

    suspend fun run(http: OkHttpClient, config: ProxyConfig): Result = withContext(Dispatchers.IO) {
        val quick = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        val direct = runCatching { lookup(quick.proxy(Proxy.NO_PROXY).build()) }.getOrNull()
        // Auch bei ausgeschaltetem Proxy testbar (vor dem Einschalten), solange Adresse und Port da sind.
        if (!config.complete) return@withContext Result(direct, null, "Adresse und Port eintragen").also { last = it }
        val viaProxy = proxiedClient(quick, config)
        runCatching { lookup(viaProxy) }.fold(
            onSuccess = { Result(direct, it, null) },
            onFailure = { Result(direct, null, explain(it)) },
        ).also { last = it }
    }

    /** Client über den eingetragenen Proxy, mit dessen Anmeldung (auch für den Speedtest). */
    fun proxiedClient(base: OkHttpClient.Builder, config: ProxyConfig): OkHttpClient =
        base.proxy(config.toProxy()).proxyAuthenticator { _, response ->
            // Eigene Anmeldung für den Test: die gerade eingetragenen Zugangsdaten, nicht die gespeicherten.
            if (response.code != 407 || config.user.isEmpty() || response.request.header("Proxy-Authorization") != null &&
                response.challenges().none { it.scheme == "OkHttp-Preemptive" }
            ) null
            else response.request.newBuilder().header("Proxy-Authorization", okhttp3.Credentials.basic(config.user, config.password)).build()
        }.build()

    private fun lookup(client: OkHttpClient): Seen {
        var last: Throwable? = null
        for (url in SERVICES) {
            try {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                    if (response.code == 407) throw ProxyAuthException()
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    return parse(response.body.string()) ?: error("unbekannte Antwort")
                }
            } catch (e: ProxyAuthException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("keine Antwort")
    }

    /** ipwho.is: {ip, country, country_code}; country.is: {ip, country (Kürzel)}. */
    fun parse(body: String): Seen? = runCatching {
        val json = Json.parseToJsonElement(body) as JsonObject
        val ip = json["ip"]?.jsonPrimitive?.content ?: return null
        val code = json["country_code"]?.jsonPrimitive?.content
        val country = json["country"]?.jsonPrimitive?.content
        val label = when {
            code != null && country != null && country != code -> "$country ($code)"
            else -> country ?: code ?: "?"
        }
        Seen(ip, label)
    }.getOrNull()

    private class ProxyAuthException : java.io.IOException("Anmeldung abgelehnt")

    fun explain(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is ProxyAuthException || it.message?.contains("authenticate with proxy", ignoreCase = true) == true } -> "Anmeldung abgelehnt (Benutzer oder Passwort falsch)"
            chain.any { it.message?.contains("SOCKS", ignoreCase = true) == true && it.message?.contains("auth", ignoreCase = true) == true } ->
                "Anmeldung abgelehnt (Benutzer oder Passwort falsch)"
            chain.any { it is java.net.UnknownHostException } -> "Proxy-Adresse unbekannt"
            chain.any { it is java.net.ConnectException } -> "Proxy nicht erreichbar (Adresse oder Port falsch?)"
            chain.any { it is java.net.SocketTimeoutException } -> "Proxy antwortet nicht"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

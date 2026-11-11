package de.ayutv.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * LG webOS direkt steuern (wie die LG-App „ThinQ“), ohne DLNA: WebSocket zum Fernseher (Port 3001 mit TLS, ältere
 * Geräte 3000), einmal koppeln – der Fernseher fragt „Zulassen?“ –, dann dem eingebauten Mediaplayer die Adresse geben.
 * Der Fernseher holt den Stream selbst: volle Qualität. Der Kopplungsschlüssel wird gemerkt ([keys]).
 */
object LgWebOs {
    /** Ergebnis: null = läuft, sonst der Grund (für die Meldung). */
    interface Keys {
        fun get(host: String): String?
        fun put(host: String, key: String)
    }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Rechte, um die die App beim Koppeln bittet (Mediaplayer öffnen, Wiedergabe steuern, Lautstärke, Meldungen). */
    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_POWER", "READ_APP_STATUS", "READ_RUNNING_APPS", "READ_INSTALLED_APPS", "WRITE_NOTIFICATION_TOAST",
        "READ_NETWORK_STATE", "READ_POWER_STATE", "CONTROL_INPUT_TV",
    )

    /** Fernseher im eigenen WLAN haben ein selbst ausgestelltes Zertifikat: nur für diese Verbindung ohne Prüfung. */
    private fun lanClient(base: OkHttpClient): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return base.newBuilder()
            .proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Ist das ein LG mit webOS (nach Name/Hersteller in der Gerätebeschreibung)? */
    fun isLg(name: String): Boolean = Regex("""(?i)\blg\b|webos""").containsMatchIn(name)

    /**
     * [url] auf dem LG unter [host] im Mediaplayer abspielen. Beim ersten Mal fragt der Fernseher, ob DnA-TV ihn steuern
     * darf (bis zu 60 s Zeit). Liefert null, wenn er abspielt, sonst den Grund.
     *
     * [fresh]: läuft der Mediaplayer schon (Umschalten), ihn erst schließen und neu öffnen. Bekommt er sonst nur eine neue
     * Adresse, hängt er bei Live oft am alten Strom oder läuft mit immer mehr Verzögerung.
     */
    suspend fun play(
        base: OkHttpClient, host: String, keys: Keys, url: String, title: String, mime: String, fresh: Boolean = false, onPrompt: () -> Unit = {},
    ): String? {
        var last: String? = null
        for (address in listOf("wss://$host:3001/", "ws://$host:3000/")) {
            val result = session(lanClient(base), address, keys, host, onPrompt) { send, lastReply ->
                if (fresh) {
                    send("ssap://com.webos.applicationManager/getForegroundAppInfo", buildJsonObject { })
                    val app = lastReply()?.get("appId")?.jsonPrimitive?.contentOrNull
                    if (app != null && PLAYER_APP.containsMatchIn(app)) {
                        send("ssap://media.controls/stop", buildJsonObject { })
                        send("ssap://system.launcher/close", buildJsonObject { put("id", app) })
                        // Der LG braucht einen Moment, bis der Mediaplayer wirklich zu ist (mit 250 ms öffnete er den
                        // nächsten oft gar nicht – nach dem Umschalten passierte nichts).
                        delay(1_200)
                    }
                }
                // Mediaplayer des Fernsehers mit der Adresse öffnen.
                val open = send("ssap://media.viewer/open", buildJsonObject {
                    put("target", url)
                    put("title", title)
                    put("description", "DnA-TV")
                    put("mimeType", mime)
                    put("loop", false)
                })
                if (open == null) null
                else {
                    // Ältere Geräte ohne media.viewer: im Browser des Fernsehers öffnen.
                    val launcher = send("ssap://system.launcher/open", buildJsonObject { put("target", url) })
                    if (launcher == null) null else "Mediaplayer: $open; Browser: $launcher"
                }
            }
            if (result == null) return null
            last = result
            // Verbindung kam gar nicht zustande: andere Adresse versuchen; sonst (abgelehnt, keine Rechte) nicht.
            if (!result.startsWith("keine Verbindung")) return result
        }
        return last
    }

    /**
     * Wiedergabe auf dem LG beenden: anhalten und den Mediaplayer schließen (sonst lief der Sender dort weiter, obwohl
     * „Hier weiterschauen“ gewählt war). Nur mit schon gemerktem Kopplungsschlüssel (fragt nicht neu).
     */
    suspend fun stop(base: OkHttpClient, host: String, keys: Keys): String? {
        if (keys.get(host) == null) return "nicht gekoppelt"
        var last: String? = null
        for (address in listOf("wss://$host:3001/", "ws://$host:3000/")) {
            val result = session(lanClient(base), address, keys, host, {}) { send, lastReply ->
                send("ssap://media.controls/stop", buildJsonObject { })
                // Welche App vorne ist (der Mediaplayer): genau die schließen, nicht etwa das Fernsehprogramm.
                send("ssap://com.webos.applicationManager/getForegroundAppInfo", buildJsonObject { })
                val app = lastReply()?.get("appId")?.jsonPrimitive?.contentOrNull
                if (app != null && PLAYER_APP.containsMatchIn(app)) send("ssap://system.launcher/close", buildJsonObject { put("id", app) }) else null
            }
            if (result == null) return null
            last = result
            if (!result.startsWith("keine Verbindung")) return result
        }
        return last
    }

    /**
     * Befehl an den Mediaplayer des LG, etwa `ssap://media.controls/pause` oder `…/play`. Nur mit schon gemerktem
     * Kopplungsschlüssel (fragt nicht neu). null = ok, sonst Grund.
     */
    suspend fun control(base: OkHttpClient, host: String, keys: Keys, uri: String): String? {
        if (keys.get(host) == null) return "nicht gekoppelt"
        var last: String? = null
        for (address in listOf("wss://$host:3001/", "ws://$host:3000/")) {
            val result = session(lanClient(base), address, keys, host, {}) { send, _ -> send(uri, buildJsonObject { }) }
            if (result == null) return null
            last = result
            if (!result.startsWith("keine Verbindung")) return result
        }
        return last
    }

    /** Apps, mit denen webOS eine Adresse abspielt (Mediaplayer, Foto & Video, Browser). */
    private val PLAYER_APP = Regex("(?i)media|photovideo|player|browser|viewer")

    /** Eine Verbindung: koppeln, dann [action] mit einer Funktion zum Senden von Befehlen (null = ok, sonst Fehler). */
    private suspend fun session(
        client: OkHttpClient,
        address: String,
        keys: Keys,
        host: String,
        onPrompt: () -> Unit,
        action: suspend (send: suspend (uri: String, payload: JsonObject) -> String?, lastReply: () -> JsonObject?) -> String?,
    ): String? {
        var last: JsonObject? = null
        val opened = CompletableDeferred<String?>()
        val registered = CompletableDeferred<String?>()
        val replies = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val socket = client.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = message["type"]?.jsonPrimitive?.contentOrNull
                val id = message["id"]?.jsonPrimitive?.contentOrNull
                val payload = message["payload"] as? JsonObject
                if (id == "register_0") {
                    when (type) {
                        "registered" -> {
                            payload?.get("client-key")?.jsonPrimitive?.contentOrNull?.let { keys.put(host, it) }
                            registered.complete(null)
                        }
                        "response" -> if (payload?.get("pairingType")?.jsonPrimitive?.contentOrNull == "PROMPT") onPrompt()
                        "error" -> registered.complete("Kopplung abgelehnt (${message["error"]?.jsonPrimitive?.contentOrNull ?: "?"})")
                    }
                    return
                }
                if (id != null) {
                    replies[id]?.complete(message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = "keine Verbindung (${t.javaClass.simpleName})"
                opened.complete(reason)
                registered.complete(reason)
                replies.values.forEach { it.completeExceptionally(t) }
            }
        })
        try {
            val openError = withTimeoutOrNull(6_000) { opened.await() } ?: if (!opened.isCompleted) "keine Verbindung (Zeitüberschreitung)" else null
            if (openError != null) return openError
            val register = buildJsonObject {
                put("type", "register")
                put("id", "register_0")
                put("payload", buildJsonObject {
                    put("forcePairing", false)
                    put("pairingType", "PROMPT")
                    keys.get(host)?.let { put("client-key", it) }
                    put("manifest", buildJsonObject {
                        put("manifestVersion", 1)
                        put("appVersion", "1.0")
                        put("appId", "de.ayutv")
                        put("vendorId", "de.ayutv")
                        put("localizedAppNames", buildJsonObject { put("", "DnA-TV") })
                        put("permissions", buildJsonArray { PERMISSIONS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                })
            }
            socket.send(register.toString())
            // Beim ersten Mal muss jemand am Fernseher „Zulassen“ drücken.
            val registerError = withTimeoutOrNull(60_000) { registered.await() } ?: if (!registered.isCompleted) "am Fernseher nicht zugelassen (keine Antwort)" else null
            if (registerError != null) return registerError
            var counter = 0
            return action({ uri, payload ->
                val id = "req_${++counter}"
                val reply = CompletableDeferred<JsonObject>()
                replies[id] = reply
                socket.send(buildJsonObject {
                    put("type", "request")
                    put("id", id)
                    put("uri", uri)
                    put("payload", payload)
                }.toString())
                val answer = runCatching { withTimeoutOrNull(10_000) { reply.await() } }.getOrNull()
                last = answer?.get("payload") as? JsonObject
                when {
                    answer == null -> "keine Antwort"
                    answer["type"]?.jsonPrimitive?.contentOrNull == "error" -> answer["error"]?.jsonPrimitive?.contentOrNull ?: "Fehler"
                    (answer["payload"] as? JsonObject)?.get("returnValue")?.jsonPrimitive?.booleanOrNull == false ->
                        (answer["payload"] as? JsonObject)?.get("errorText")?.jsonPrimitive?.contentOrNull ?: "abgelehnt"
                    else -> null
                }
            }, { last })
        } finally {
            socket.close(1000, null)
        }
    }
}

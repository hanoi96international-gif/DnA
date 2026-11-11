package de.ayutv.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import de.ayutv.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handy-Seite der Fernbedienung: Fernseher im Heimnetz finden, Tasten schicken, abgleichen.
 * Adresse und PIN des zuletzt gekoppelten Fernsehers bleiben gespeichert.
 */
class RemoteClient(private val context: Context, private val container: AppContainer) {
    data class Tv(val name: String, val address: String)

    private val prefs = context.getSharedPreferences("remote_client", Context.MODE_PRIVATE)
    private val http = container.http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var address: String?
        get() = prefs.getString("address", null)
        set(value) = prefs.edit().putString("address", value).apply()

    var pin: String?
        get() = prefs.getString("pin", null)
        set(value) = prefs.edit().putString("pin", value).apply()

    val paired: Boolean get() = !address.isNullOrEmpty() && !pin.isNullOrEmpty()

    /** Fernseher mit DnA-TV im Heimnetz (laufend ergänzt). */
    fun discover(): Flow<List<Tv>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            close()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, Tv>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "Suche fehlgeschlagen: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (':' in host) return
                            found[host] = Tv(info.serviceName, "http://$host:${info.port}")
                            trySend(found.values.toList())
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Verbindung prüfen; liefert den Namen des Fernsehers. */
    suspend fun check(address: String, pin: String): String = withContext(Dispatchers.IO) {
        val body = call(address, pin, "/info", null)
        Regex("\"name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "Fernseher"
    }

    suspend fun key(name: String) = withContext(Dispatchers.IO) { call(required(), pin!!, "/key?k=$name", "") }

    suspend fun search(text: String) = withContext(Dispatchers.IO) { call(required(), pin!!, "/search", text) }

    /** Eigenen Stand schicken, den des Fernsehers übernehmen. */
    suspend fun sync(): DeviceSync.Result {
        val sync = DeviceSync(container)
        val mine = sync.export()
        val theirs = withContext(Dispatchers.IO) { call(required(), pin!!, "/sync", mine) }
        return sync.import(theirs)
    }

    /**
     * Beim Fernseher als Übersetzer melden ([helperAddress] = null: abmelden). Liefert die Sprachen, in die der
     * Fernseher übersetzt (deren Modelle das Handy vorab lädt).
     */
    suspend fun registerHelper(helperAddress: String?): List<String> = withContext(Dispatchers.IO) {
        val name = java.net.URLEncoder.encode(android.os.Build.MODEL, "UTF-8")
        val body = call(required(), pin!!, "/helper?name=$name", helperAddress.orEmpty())
        Regex("\"([a-z]{2,3})\"").findAll(body.substringAfter("languages")).map { it.groupValues[1] }.toList()
    }

    /** Sender oder Film auf dem Fernseher starten (der holt den Stream selbst beim Anbieter). */
    suspend fun play(request: RemotePlay, address: String = required(), pin: String = this.pin ?: throw IOException("Nicht gekoppelt")) =
        withContext(Dispatchers.IO) { call(address, pin, "/play", request.encode()) }

    /** Fehlerbericht des Fernsehers (ohne Passwörter), zum Weiterschicken vom Handy. */
    suspend fun report(short: Boolean = false): String =
        withContext(Dispatchers.IO) { call(required(), pin!!, if (short) "/report-short" else "/report", null) }

    private fun required(): String = address ?: throw IOException("Kein Fernseher gekoppelt")

    /** Ein Schlüssel je PIN (einmal abgeleitet), damit Tastendrücke nicht jedes Mal rechnen müssen. */
    @Volatile private var channel: Pair<String, de.ayutv.data.setup.LocalChannel>? = null

    private fun channelFor(pin: String): de.ayutv.data.setup.LocalChannel =
        channel?.takeIf { it.first == pin }?.second ?: de.ayutv.data.setup.LocalChannel(pin).also { channel = pin to it }

    /**
     * Anfrage über den verschlüsselten Kanal (AES-256-GCM, Schlüssel aus der PIN): Wer das WLAN mitliest, sieht weder
     * die PIN noch Favoriten, Verlauf oder den Fehlerbericht.
     */
    private fun call(address: String, pin: String, path: String, body: String?): String {
        val secure = channelFor(pin)
        val request = Request.Builder().url("${address.trimEnd('/')}/s")
            .post(secure.request(path, body.orEmpty()).toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        val reply = http.newCall(request).execute().use { response ->
            if (response.code == 403) throw IOException("Falsche PIN – bitte die PIN vom Fernseher eingeben (und DnA-TV dort auf 0.45.4 oder neuer aktualisieren)")
            if (!response.isSuccessful) throw IOException("Fernseher antwortet mit HTTP ${response.code}")
            runCatching { secure.reply(response.body.string()) }.getOrElse { throw IOException("Antwort des Fernsehers nicht lesbar (falsche PIN?)") }
        }
        val code = reply.substringBefore('\n').toIntOrNull() ?: 500
        val text = reply.substringAfter('\n', "")
        if (code == 404 && path.startsWith("/play")) throw IOException(text.ifBlank { "Auf dem Fernseher nicht gefunden" })
        if (code !in 200..299) throw IOException("Fernseher antwortet mit $code${if (text.isNotBlank()) ": " + text.take(120) else ""}")
        return text
    }

    companion object {
        private const val TAG = "RemoteClient"
    }
}

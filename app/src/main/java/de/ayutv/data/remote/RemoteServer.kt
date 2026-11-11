package de.ayutv.data.remote

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import de.ayutv.AppContainer
import de.ayutv.BuildConfig
import de.ayutv.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handy als Fernbedienung: Der Fernseher nimmt im Heimnetz Tasten, Suchbegriffe und den Abgleich an.
 * Läuft, solange DnA-TV im Vordergrund ist. Jede Anfrage braucht die PIN aus den Einstellungen
 * (bleibt gleich, damit das Handy gekoppelt bleibt); viele Fehlversuche erzeugen eine neue.
 * Im Heimnetz unter „_dnatv._tcp“ angekündigt, damit die App auf dem Handy den Fernseher findet.
 * Mit dem Browser geht es auch ohne App: Die Adresse zeigt eine Fernbedienung als Webseite.
 */
class RemoteServer(private val context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val failures = AtomicInteger()
    private val clients = Semaphore(MAX_CLIENTS)

    @Volatile var address: String? = null
        private set

    val pin: String
        get() = prefs.getString("pin", null) ?: newPin()

    fun newPin(): String = (100_000 + SecureRandom().nextInt(900_000)).toString().also {
        prefs.edit().putString("pin", it).apply()
        failures.set(0)
    }

    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Fernbedienung", e) })
        scope = newScope
        newScope.launch {
            // Nach dem Einschalten ist das WLAN oft noch nicht da: warten statt aufgeben.
            var ip = localAddress()
            while (ip == null) {
                delay(10_000)
                ip = localAddress()
            }
            val server = runCatching { ServerSocket(PORT, 8, InetAddress.getByName(ip)) }.getOrElse {
                Log.w(TAG, "Port $PORT belegt", it)
                return@launch
            }
            socket = server
            address = "http://$ip:$PORT"
            announce()
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                if (!clients.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                launch {
                    try {
                        client.use { handle(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Anfrage verworfen", e)
                    } finally {
                        clients.release()
                    }
                }
            }
        }
    }

    fun stop() {
        registration?.let { r -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(r) } }
        registration = null
        runCatching { socket?.close() }
        socket = null
        address = null
        scope?.cancel()
        scope = null
    }

    private fun announce() {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "DnA-TV ${Build.MODEL}".take(60)
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Ankündigung fehlgeschlagen: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { registration = listener }
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
        }
        val route = path.substringBefore('?')
        val query = parseQuery(path.substringAfter('?', ""))
        // Verschlüsselter Kanal (Handy-App, Windows): Die PIN steht nicht in der Adresse, sie ist der Schlüssel.
        if (route == "/s") {
            if (method != "POST") return respond(client, 405, "text/plain", ByteArray(0))
            val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)
            val request = runCatching { channel.open(body.toString(Charsets.UTF_8)) }.getOrNull()
            if (request == null) {
                delay(1_000)
                if (failures.incrementAndGet() > MAX_FAILURES) newPin()
                return respond(client, 403, "text/plain", "Falsche PIN".toByteArray())
            }
            failures.set(0)
            val inner = request.path.substringBefore('?')
            val reply = dispatch("POST", inner, parseQuery(request.path.substringAfter('?', "")), request.body.toByteArray(Charsets.UTF_8), encrypted = true)
            val sealed = channel.reply(request, "${reply.code}\n" + reply.bytes.toString(Charsets.UTF_8))
            return respond(client, 200, "text/plain", sealed.toByteArray())
        }
        val prefix = "/$pin"
        if (route != prefix && !route.startsWith("$prefix/")) {
            delay(1_000)
            if (failures.incrementAndGet() > MAX_FAILURES) newPin()
            respond(client, 403, "text/plain", "Falsche PIN".toByteArray())
            return
        }
        failures.set(0)
        val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)
        val reply = dispatch(method, route.removePrefix(prefix), query, body, encrypted = false)
        respond(client, reply.code, reply.type, reply.bytes)
    }

    private class Reply(val code: Int, val type: String, val bytes: ByteArray) {
        constructor(code: Int, text: String) : this(code, "text/plain; charset=utf-8", text.toByteArray())
    }

    /** Schlüssel je Verbindung des verschlüsselten Kanals (aus der jeweils aktuellen PIN). */
    private val channel = de.ayutv.data.setup.LocalChannel.Server { pin }

    /**
     * Eine Anfrage beantworten. Über die Browser-Seite (PIN in der Adresse, unverschlüsselt) nur die Fernbedienung
     * selbst; alles mit persönlichen Daten (Abgleich, Fehlerbericht, Abspielen, Übersetzer) nur [encrypted].
     */
    private suspend fun dispatch(method: String, route: String, query: Map<String, String>, body: ByteArray, encrypted: Boolean): Reply {
        if (!encrypted && route !in setOf("", "/", "/info", "/key", "/search")) return Reply(404, "")
        return when (route) {
            "", "/" -> Reply(200, "text/html; charset=utf-8", RemotePage.html(pin).toByteArray())
            "/info" -> Reply(200, "application/json", """{"name":"${jsonEscape("DnA-TV ${Build.MODEL}")}","version":"${BuildConfig.VERSION_NAME}"}""".toByteArray())
            "/key" -> {
                if (method != "POST") return Reply(405, "")
                val ok = query["k"]?.let { press(it) } == true
                Reply(if (ok) 200 else 400, if (ok) "ok" else "unbekannte Taste")
            }
            "/search" -> {
                if (method != "POST") return Reply(405, "")
                search(body.toString(Charsets.UTF_8).trim().take(200))
                Reply(200, "ok")
            }
            "/helper" -> {
                // Handy meldet sich als Übersetzer (Adresse mit Schlüssel) oder ab (leer).
                val address = body.toString(Charsets.UTF_8).trim().ifEmpty { null }
                de.ayutv.data.translate.PhoneHelper.register(address, query["name"])
                val s = container.settings.current()
                val languages = listOf(s.subtitleLanguage, s.lastTranslationTarget).filter { it.isNotEmpty() }.distinct()
                Reply(200, "application/json", """{"languages":[${languages.joinToString(",") { "\"${jsonEscape(it)}\"" }}]}""".toByteArray())
            }
            "/play" -> {
                // Vom Handy übergeben: Sender oder Film hier abspielen.
                val result = runCatching { play(RemotePlay.parse(body.toString(Charsets.UTF_8))) }
                val error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: result.getOrNull()
                if (error != null) Reply(404, error) else Reply(200, "ok")
            }
            "/report" -> Reply(200, de.ayutv.data.Diagnostics.build(context, container))
            "/report-short" -> Reply(200, de.ayutv.data.Diagnostics.build(context, container, short = true))
            "/sync" -> {
                val sync = DeviceSync(container)
                val result = runCatching { sync.import(body.toString(Charsets.UTF_8)) }
                if (result.isFailure) Reply(400, result.exceptionOrNull()?.message ?: "Fehler")
                else Reply(200, "application/json", sync.export().toByteArray())
            }
            else -> Reply(404, "")
        }
    }

    /**
     * Sender oder Film vom Handy hier starten. Die Schlüssel unterscheiden sich je Gerät (Playlist-Nummer),
     * darum über die Adresse beim Anbieter zuordnen. Liefert eine Fehlermeldung oder null.
     */
    private suspend fun play(request: RemotePlay): String? {
        val intent = if (request.live) {
            // Gibt es den Sender hier nicht (andere Playlist), spielt der Fernseher die Adresse trotzdem direkt.
            // Nicht die Fassung des Handys übernehmen, sondern hier die beste desselben Senders (das Handy spielt
            // vielleicht SD oder eine Ersatzfassung; der Fernseher nimmt Full HD/4K, wenn es sie gibt).
            val channel = container.db.channels().byUrl(request.url)?.let { c ->
                val score = { ch: de.ayutv.data.db.ChannelEntity -> de.ayutv.data.parse.ChannelQuality.score(ch.key, ch.name) }
                container.content.variantsOf(c).firstOrNull()?.takeIf { score(it) < score(c) } ?: c
            }
            if (channel != null) de.ayutv.player.PlayerActivity.liveIntent(context, channel.key, group = null)
            else de.ayutv.player.PlayerActivity.liveUrlIntent(context, request.url, request.title, request.image, request.userAgent)
        } else {
            val key = container.db.vod().byUrl(request.url)?.key ?: request.key
            de.ayutv.player.PlayerActivity.vodIntent(
                context,
                de.ayutv.player.VodRequest(key, request.kind, request.title, request.subtitle, request.image, request.url),
            ).putExtra(de.ayutv.player.PlayerActivity.EXTRA_START_AT, request.positionMs)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        return null
    }

    /** Taste im Vordergrund-Fenster der App auslösen, als käme sie von der Fernbedienung. */
    private suspend fun press(name: String): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        when (name) {
            "volup" -> { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return true }
            "voldown" -> { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return true }
            "mute" -> { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return true }
        }
        val code = KEYS[name] ?: return false
        val activity = container.foreground?.get() ?: return false
        withContext(Dispatchers.Main) {
            val now = SystemClock.uptimeMillis()
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            activity.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        }
        return true
    }

    private suspend fun search(text: String) {
        container.searchRequests.value = text
        withContext(Dispatchers.Main) {
            // Aus dem Player heraus: zur Startseite mit der Suche.
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun respond(client: Socket, code: Int, type: String, bytes: ByteArray) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" }
        runCatching {
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(bytes)
                flush()
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length > 8192) throw IOException("Zeile zu lang")
            out.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) break
            read += n
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull { pair ->
        val k = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        k to runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
    }.toMap()

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    companion object {
        private const val TAG = "RemoteServer"
        const val PORT = 8096
        const val SERVICE_TYPE = "_dnatv._tcp."
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val MAX_CLIENTS = 6
        private const val MAX_FAILURES = 20

        val KEYS = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
            "back" to KeyEvent.KEYCODE_BACK,
            "menu" to KeyEvent.KEYCODE_MENU,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "rew" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "ffwd" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            "chup" to KeyEvent.KEYCODE_CHANNEL_UP,
            "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "info" to KeyEvent.KEYCODE_INFO,
            "guide" to KeyEvent.KEYCODE_GUIDE,
            "last" to KeyEvent.KEYCODE_LAST_CHANNEL,
        ) + (0..9).associate { "$it" to KeyEvent.KEYCODE_0 + it }
    }
}

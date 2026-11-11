package de.ayutv.data.remote

import android.util.Log
import de.ayutv.data.repo.Translators
import de.ayutv.data.translate.HelperReply
import de.ayutv.data.translate.HelperRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Handy-Seite von „Handy übersetzt für den Fernseher“: eine kleine Schnittstelle im Heimnetz, die Untertitelzeilen
 * mit dem Übersetzer des Handys übersetzt. Erreichbar nur mit dem zufälligen Schlüssel in der Adresse, den allein
 * der gekoppelte Fernseher bekommt. Läuft, solange die Fernbedienung auf dem Handy offen ist.
 */
class TranslationHelperServer {
    data class State(val address: String? = null, val lines: Int = 0, val info: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val json = Json { ignoreUnknownKeys = true }
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private val clients = java.util.concurrent.Semaphore(4)
    /** Eine Übersetzung nach der anderen: Das Modell rechnet ohnehin nur eine Anfrage zugleich. */
    private val work = Semaphore(1)

    /** Startet die Schnittstelle; liefert ihre Adresse (mit Schlüssel) oder null ohne WLAN. */
    fun start(): String? {
        _state.value.address?.let { return it }
        val ip = localAddress() ?: return null.also { _state.value = State(info = "Kein WLAN gefunden") }
        val server = runCatching { ServerSocket(PORT, 8, InetAddress.getByName(ip)) }
            .recoverCatching { ServerSocket(0, 8, InetAddress.getByName(ip)) }
            .getOrElse {
                _state.value = State(info = "Nicht möglich: ${it.message}")
                return null
            }
        socket = server
        val address = "http://$ip:${server.localPort}/$token"
        _state.value = State(address = address)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Helfer", e) })
        scope = newScope
        newScope.launch {
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
        return address
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
        _state.value = State()
    }

    fun info(text: String?) {
        _state.value = _state.value.copy(info = text)
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 60_000
        val input = BufferedInputStream(client.getInputStream())
        val request = readLine(input) ?: return
        val path = request.split(' ').getOrNull(1).orEmpty()
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        }
        if (path != "/$token/translate") return respond(client, 404, "{}")
        val body = ByteArray(length).also { target ->
            var read = 0
            while (read < target.size) {
                val n = input.read(target, read, target.size - read)
                if (n < 0) break
                read += n
            }
        }.toString(Charsets.UTF_8)
        val ask = runCatching { json.decodeFromString(HelperRequest.serializer(), body) }.getOrElse { return respond(client, 400, "{}") }
        if (ask.texts.size > MAX_LINES) return respond(client, 400, "{}")
        val texts = runCatching {
            work.withPermit { Translators.remote.translate(ask.texts, ask.target, ask.source) }
        }.getOrElse {
            Log.w(TAG, "Übersetzung", it)
            _state.value = _state.value.copy(info = "Fehler: ${it.message}")
            return respond(client, 503, "{}")
        }
        _state.value = _state.value.copy(lines = _state.value.lines + texts.size, info = null)
        respond(client, 200, json.encodeToString(HelperReply.serializer(), HelperReply(texts)))
    }

    private fun respond(client: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        runCatching {
            client.getOutputStream().apply {
                write("HTTP/1.1 $code X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
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

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "TranslationHelper"
        const val PORT = 8097
        const val MAX_BODY = 512 * 1024
        const val MAX_LINES = 200
    }
}

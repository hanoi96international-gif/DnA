package de.ayutv.desktop.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.ayutv.desktop.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Updates für Windows wie auf dem Fire TV: fragt das neueste Release bei GitHub ab, lädt den Installer
 * (DnA-TV-Windows-Setup.exe) und startet ihn. Der Installer ersetzt die alte Version (gleiche Upgrade-Kennung),
 * Daten im Datenordner bleiben.
 */
class Updater(private val http: OkHttpClient, val currentVersion: String = installedVersion()) {
    data class Release(val version: String, val url: String, val sizeBytes: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle); private set
    private var lastCheck = 0L

    /** Höchstens alle 6 Stunden nachfragen, auf Wunsch sofort. */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 6 * 3_600_000L) return@withContext (state as? State.Available)?.release
        lastCheck = now
        state = State.Checking
        runCatching { latest() }
            .onSuccess { release -> state = if (release != null && isNewer(release.version, currentVersion)) State.Available(release) else State.UpToDate }
            .onFailure {
                Log.warn("Update-Prüfung fehlgeschlagen", it)
                state = State.Failed("Keine Verbindung zu GitHub")
            }
        (state as? State.Available)?.release
    }

    private fun latest(): Release? {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
        return parseRelease(body)
    }

    /** Installer laden und starten; danach soll sich DnA-TV beenden ([onStarted]). */
    suspend fun install(release: Release, onStarted: () -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "DnA-TV-Update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "DnA-TV-${release.version}-Setup.exe")
            state = State.Downloading(release, 0)
            runCatching {
                http.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                    response.body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if ((state as? State.Downloading)?.percent != percent) state = State.Downloading(release, percent)
                                }
                            }
                        }
                    }
                }
                target
            }.onFailure {
                Log.warn("Update-Download fehlgeschlagen", it)
                state = State.Failed("Download fehlgeschlagen: ${it.message}")
            }.getOrNull()
        } ?: return
        Log.info("Starte Installer ${file.name}")
        runCatching { ProcessBuilder(file.absolutePath).start() }
            .onSuccess { onStarted() }
            .onFailure { state = State.Failed("Installer lässt sich nicht starten: ${it.message}") }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/hanoi96international-gif/DnA/releases/latest"
        const val SETUP = "DnA-TV-Windows-Setup.exe"
        private val json = Json { ignoreUnknownKeys = true }

        /** Version dieser App (beim Bauen in die Ressourcen geschrieben). */
        fun installedVersion(): String =
            Updater::class.java.getResourceAsStream("/app.version")?.use { it.readBytes().decodeToString().trim() }?.ifEmpty { null } ?: "0"

        fun parseRelease(body: String, assetName: String = SETUP): Release? {
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == assetName } ?: return null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            val size = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val notes = (root["body"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            return Release(tag.removePrefix("v"), url, size, notes)
        }

        /** "0.41.1" neuer als "0.41.0"? Zahlen je Stelle vergleichen, Zusätze („-beta“) ignorieren. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

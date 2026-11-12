package de.ayutv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import de.ayutv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Updates ohne Link: fragt das neueste Release bei GitHub ab, lädt die APK und übergibt sie dem
 * Paket-Installer. Android prüft dabei die Signatur; nur eine Version mit demselben Schlüssel geht drüber.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    /** [sha256]: Prüfsumme der APK, wie GitHub sie zur Datei meldet (Kleinbuchstaben-Hex), sonst null. */
    data class Release(val version: String, val apkUrl: String, val sizeBytes: Long, val notes: String, val sha256: String? = null)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L

    /** Höchstens alle [minIntervalMs] bei GitHub nachfragen (beim App-Start), auf Wunsch sofort. */
    suspend fun check(force: Boolean = false, minIntervalMs: Long = 6 * HOUR): Release? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < minIntervalMs) return@withContext (state.value as? State.Available)?.release
        lastCheck = now
        _state.value = State.Checking
        runCatching { latest() }
            .onSuccess { release ->
                _state.value = if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) State.Available(release) else State.UpToDate
            }
            .onFailure {
                Log.i(TAG, "Update-Prüfung fehlgeschlagen", it)
                _state.value = State.Failed("Keine Verbindung zu GitHub")
            }
        (state.value as? State.Available)?.release
    }

    private fun latest(): Release? {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
        return parseRelease(body, if (browserOnly) PHONE_APK else TV_APK) ?: parseRelease(body, TV_APK)
    }

    /** Handy-Fassung ohne Installationsrecht: Updates öffnen den Download im Browser. */
    val browserOnly: Boolean get() = BuildConfig.BUILD_TYPE == "phone"

    /** Lädt die APK und meldet den Fortschritt; danach [install]. */
    suspend fun download(release: Release): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "DnA-TV-${release.version}.apk")
        _state.value = State.Downloading(release, 0)
        // GitHub antwortet zeitweise mit 502/503/504 (oder der Proxy der App läuft in eine Zeitüberschreitung): mehrmals
        // versuchen, abwechselnd über den Proxy und direkt, mit wachsender Pause.
        var lastError: Exception? = null
        for (attempt in 0 until 5) {
            if (attempt > 0) kotlinx.coroutines.delay(2_000L shl attempt)
            val client = if (attempt % 2 == 1) http.newBuilder().proxy(java.net.Proxy.NO_PROXY).build() else http
            val result = downloadOnce(client, release, target) ?: verify(release, target)
            if (result == null) return@withContext target.also { _state.value = State.Ready(release, it) }
            lastError = result
            Log.w(TAG, "Update-Download Versuch ${attempt + 1} fehlgeschlagen", result)
            if (result.message?.startsWith("HTTP 4") == true || result.message?.contains("signiert") == true) break
        }
        target.delete()
        _state.value = State.Failed("Download fehlgeschlagen: ${lastError?.message}")
        null
    }

    /** Ein Download-Versuch; null wenn fertig, sonst der Fehler. */
    private fun downloadOnce(http: OkHttpClient, release: Release, target: File): Exception? {
        try {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                response.body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = State.Downloading(release, percent)
                            }
                        }
                    }
                }
            }
            return null
        } catch (e: Exception) {
            return e
        }
    }

    /**
     * Heruntergeladene APK prüfen, bevor sie dem Installer übergeben wird: Prüfsumme wie von GitHub gemeldet (verändert
     * oder unvollständig übertragen) und dieselbe Signatur wie die installierte App (fremde APK). null = in Ordnung.
     */
    private fun verify(release: Release, file: File): Exception? {
        release.sha256?.let { expected ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) return SecurityException("Prüfsumme der APK stimmt nicht – Download verworfen")
        }
        if (!sameSigner(file)) return SecurityException("APK ist nicht von DnA-TV signiert – Download verworfen")
        return null
    }

    /** Trägt die APK dieselbe Signatur wie die installierte App? (Ohne lesbare Angaben: Android prüft beim Installieren.) */
    @Suppress("DEPRECATION")
    private fun sameSigner(file: File): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(file.path, flags)?.signingInfo ?: return@runCatching true
            val installed = pm.getPackageInfo(context.packageName, flags).signingInfo ?: return@runCatching true
            val theirs = (if (archive.hasMultipleSigners()) archive.apkContentsSigners else archive.signingCertificateHistory).map { it.toCharsString() }
            val ours = (if (installed.hasMultipleSigners()) installed.apkContentsSigners else installed.signingCertificateHistory).map { it.toCharsString() }
            // Gleicher Schlüssel oder ein Nachfolger in der Schlüssel-Historie (Schlüsselwechsel).
            theirs.any { it in ours } || ours.any { it in theirs }
        } else {
            val archive = pm.getPackageArchiveInfo(file.path, android.content.pm.PackageManager.GET_SIGNATURES)?.signatures ?: return@runCatching true
            val installed = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures ?: return@runCatching true
            archive.map { it.toCharsString() }.toSet() == installed.map { it.toCharsString() }.toSet()
        }
    }.getOrDefault(true)

    /** Darf die App Pakete installieren? (Fire TV / Android 8+: einmal „Unbekannte Apps“ erlauben.) */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Einstellung „Unbekannte Apps installieren“ für DnA-TV öffnen. */
    fun permissionIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else null

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    }

    /** Ablauf beim Klick auf „Installieren“: Erlaubnis holen, laden, Installer öffnen. */
    suspend fun downloadAndInstall(release: Release, start: (Intent) -> Unit) {
        if (browserOnly) {
            runCatching { start(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { _state.value = State.Failed("Browser nicht verfügbar: ${it.message}") }
            return
        }
        if (!canInstall()) {
            // Nach dem Erlauben einfach noch einmal auf „Installieren“.
            permissionIntent()?.let { runCatching { start(it) } }
            _state.value = State.Available(release)
            return
        }
        val file = (state.value as? State.Ready)?.file?.takeIf { it.isFile } ?: download(release) ?: return
        runCatching { start(installIntent(file)) }.onFailure { _state.value = State.Failed("Installer nicht verfügbar: ${it.message}") }
    }

    companion object {
        private const val TAG = "DnAUpdate"
        private const val HOUR = 3_600_000L
        const val LATEST_URL = "https://api.github.com/repos/hanoi96international-gif/DnA/releases/latest"
        private val json = Json { ignoreUnknownKeys = true }

        /** Release aus der Antwort von GitHub: Version aus dem Tag (v0.26.0), Download der DnA-TV.apk. */
        const val TV_APK = "DnA-TV.apk"
        const val PHONE_APK = "DnA-TV-Handy.apk"

        fun parseRelease(body: String, assetName: String = TV_APK): Release? {
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == assetName } ?: return null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            val size = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val notes = (root["body"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            return Release(tag.removePrefix("v"), url, size, notes, digest)
        }

        /** "0.26.1" neuer als "0.26.0"? Zahlen je Stelle vergleichen, Zusätze („-beta“) ignorieren. */
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

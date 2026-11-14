package de.ayutv.data

import de.ayutv.data.parse.Hid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import de.ayutv.AppContainer
import de.ayutv.BuildConfig
import de.ayutv.NativeCrashLog
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fehlerbericht per Knopfdruck: Gerät, Speicher, Einstellungen, Übersetzer, TV-Guide, die letzte
 * Wiedergabe und das eigene Systemprotokoll in einer Textdatei. Ohne Zugangsdaten: Adressen nur mit
 * Server, Passwörter, Schlüssel und lange Kennungen werden unkenntlich gemacht.
 */
object Diagnostics {
    private const val PLAYER_FILE = "player_session.txt"
    private const val SELFTEST_FILE = "last_selftest.txt"
    private const val CRASH_FILE = "last_crash_report.txt"
    private const val CAST_FILE = "cast_session.txt"
    /** Wiedergabe-Protokoll ([de.ayutv.data.parse.WatchLog]), vom Player geschrieben. */
    const val WATCH_FILE = "watch_log.tsv"

    /** Letzte Wiedergabe für den nächsten Bericht merken (Statusmeldungen, Technik, Abbrüche). */
    fun savePlayerSession(context: Context, title: String, lines: List<String>) {
        val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        runCatching { File(context.filesDir, PLAYER_FILE).writeText("$time · $title\n" + lines.takeLast(200).joinToString("\n")) }
    }

    /** Letzte Übertragung zum Fernseher (LG/DLNA): Ablauf, Antworten des Fernsehers, Stand der Weiterleitung. */
    fun saveCast(context: Context, title: String, lines: List<String>) {
        val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        runCatching { File(context.filesDir, CAST_FILE).writeText("$time · $title\n" + lines.takeLast(60).joinToString("\n")) }
    }

    fun saveSelfTest(context: Context, result: String) {
        runCatching { File(context.filesDir, SELFTEST_FILE).writeText(result) }
    }

    /** Absturzmeldung aufheben, damit sie auch nach dem Anzeigen im Bericht steht. */
    fun saveCrash(context: Context, report: String) {
        runCatching { File(context.filesDir, CRASH_FILE).writeText(report) }
    }

    /**
     * [short] = Kurzbericht: nur was für Verbesserungen zählt (Netz, Einstellungen, TV-Guide-Lücken, Wiedergabe der
     * letzten 48 h, letzter Absturz), ohne Systemprotokoll – klein genug zum Kopieren in eine Nachricht.
     */
    suspend fun build(context: Context, container: AppContainer, short: Boolean = false): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        fun section(title: String) = out.append("\n== ").append(title).append(" ==\n")
        fun file(name: String) = runCatching { File(context.filesDir, name).takeIf { it.isFile }?.readText() }.getOrNull()

        out.append(if (short) "DnA-TV Kurzbericht " else "DnA-TV Fehlerbericht ").append(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())).append('\n')
        out.append("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        // Wann zuerst installiert und zuletzt aktualisiert: Liegt „installiert“ beim letzten Update, wurde die App neu
        // installiert (vorher deinstalliert) und alle eigenen Daten waren deshalb weg.
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
            out.append("Installiert ${format.format(Date(info.firstInstallTime))}, zuletzt aktualisiert ${format.format(Date(info.lastUpdateTime))}\n")
        }
        out.append("Gerät ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}\n")
        runCatching {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val disk = StatFs(context.filesDir.path)
            out.append("Arbeitsspeicher ${memory.totalMem / MB} MB, frei ${memory.availMem / MB} MB" + (if (memory.lowMemory) " (knapp)" else "") + '\n')
            out.append("Speicher frei ${disk.availableBytes / MB} MB\n")
        }
        out.append("Zeitzone ${java.util.TimeZone.getDefault().id}\n")

        section("Netz")
        out.append("Namensauflösung: ${if (de.ayutv.data.net.SecureDns.enabled) "verschlüsselt (DNS über HTTPS)" else "normal"}, zuletzt ${de.ayutv.data.net.SecureDns.lastPath}\n")

        de.ayutv.data.net.NetInfo.sample(context).let { out.append("Verbindung jetzt: ${it.label}" + (if (it.rssi < 0) ", Signal ${it.rssi} dBm" else "") + "\n") }
        de.ayutv.data.net.ProxyCheck.last?.let { r ->
            out.append("Proxy-Prüfung: " + (r.proxied?.let { "über Proxy ${it.country}" } ?: "Proxy nicht erreichbar") +
                (r.direct?.let { ", direkt ${it.country}" } ?: "") + (r.error?.let { ", Fehler: $it" } ?: "") + "\n")
        }
        de.ayutv.data.net.ProxySpeedTest.last?.let { out.append(it.report()).append('\n') }

        section("Wiedergabe (letzte 48 h)")
        runCatching {
            val lines = File(context.filesDir, WATCH_FILE).takeIf { it.isFile }?.readLines().orEmpty()
            out.append(de.ayutv.data.parse.WatchLog.summary(lines.mapNotNull(de.ayutv.data.parse.WatchLog::decode), System.currentTimeMillis()))
        }

        section("Oberfläche")
        out.append("Menüs: ${de.ayutv.ui.UiPerformance.summary()}\n")

        // Reiter „Apps“: welche Fassung erkannt wurde, und alle Pakete mit passendem Namen (falls die Erkennung irrt).
        if (!short) section("Apps")
        if (!short) de.ayutv.data.parse.StreamingApps.ALL.forEach { app ->
            val found = runCatching { de.ayutv.ui.apps.AppLauncher.installed(context, app) }.getOrNull()
            out.append("${app.name}: ${found ?: "nicht gefunden"}\n")
        }
        if (!short) runCatching {
            val words = listOf(Hid.s("PQE0WSE0cg=="), Hid.s("Kgs1Szg/bw=="), Hid.s("Nx4uWT0wbg=="), "avod", Hid.s("NBwoQDEgfj6mZg=="), "amazonvideo", "firebat")
            context.packageManager.getInstalledApplications(0).map { it.packageName }.filter { p -> words.any { it in p.lowercase() } }
                .sorted().takeIf { it.isNotEmpty() }?.let { out.append("Pakete: ${it.joinToString()}\n") }
        }

        section("Einstellungen")
        runCatching {
            val s = container.settings.current()
            out.append("Puffer ${s.bufferProfile}${if (s.filmDiskBuffer) " + Film-Vorrat" else ""}, Live ${s.liveFormat}, Player DNA Engine, Bildrate ${s.matchFrameRate}${if (s.frameRateFilmsOnly) " (nur Filme)" else ""}, Ton ${s.audioOutput}\n")
            out.append("Bildrand ${s.screenMargin.label}, Ton-Bild-Abgleich ${de.ayutv.data.avSyncLabel(s.avSyncMs)}\n")
            out.append("Untertitel ${s.subtitleLanguage}, automatisch ${s.subtitlesAutoOn}, OpenSubtitles ${s.onlineSubtitles}" +
                " (eigener Schlüssel ${s.openSubtitlesKey.isNotBlank()}, Konto ${s.openSubtitlesUser.isNotBlank()})\n")
            out.append("Übersetzung ${s.translationProvider}, zuletzt ${s.lastTranslationTarget.ifEmpty { "-" }}" +
                (if (s.translationProvider == de.ayutv.data.TranslationProvider.DEEPL) ", DeepL-Schlüssel ${s.deeplKey.isNotBlank()}" +
                    (de.ayutv.data.translate.DeepLTranslator.problem?.let { ", zuletzt: $it" } ?: "") else "") + "\n")
            out.append("Zeitversatz ${s.liveTimeshift} (${s.timeshiftMinutes} min), TV-Guide eigener ${s.ownGuideOnly}, frei ${s.internetGuide}, Verschiebung ${s.epgShiftHours} h\n")
            out.append("Vereine ${s.followedTeams.size}, Stichwörter ${s.alertKeywords.size}, Handy-Fernbedienung ${s.phoneRemote}\n")
            out.append("Proxy ${s.proxyMode}" + (if (s.proxyMode != de.ayutv.data.net.ProxyMode.OFF) " ${s.proxyType}, Anmeldung ${s.proxyUser.isNotBlank()}, Playlists ${s.proxyPlaylists.size}" else "") +
                (de.ayutv.data.net.AppProxy.lastFailure?.let { ", zuletzt: ${it.message}" } ?: "") + "\n")
        }

        section("Playlists")
        runCatching {
            for (p in container.db.playlists().all()) {
                val status = container.playlists.epgStatus(p.id)
                val limit = container.accountLimits.maxConnections(p.id)
                out.append("${p.name} (Nr. ${p.id}): ${p.type}, ${if (p.enabled) "an" else "aus"}, Server ${host(p.url)}" +
                    (limit?.let { ", höchstens $it Verbindungen" } ?: "") +
                    (status?.let { ", TV-Guide ${it.withGuide}/${it.total} Sender (${it.sources.joinToString { s -> "${s.first} ${s.second}" }})" } ?: "") + '\n')
            }
        }

        // Sender ohne TV-Guide (je Gruppe), damit fehlende Zuordnungen gezielt verbessert werden können.
        section("Sender ohne TV-Guide")
        runCatching {
            for (p in container.db.playlists().all()) {
                val status = container.playlists.epgStatus(p.id) ?: continue
                if (status.missingChannels.isEmpty()) continue
                out.append("${p.name} (Nr. ${p.id}): ${status.total - status.withGuide} von ${status.total}\n")
                if (status.excluded.isNotEmpty()) out.append("  Nicht mitgezählt (kein Programm möglich): " +
                    status.excluded.joinToString { "${it.first} ${it.second}" } + "\n")
                status.missingInGroups.groupBy({ it.first }, { it.second }).entries.take(40).forEach { (group, names) ->
                    out.append("  ${group.ifEmpty { "-" }}: ${names.distinct().take(40).joinToString(", ")}${if (names.size > 40) " … (+${names.size - 40})" else ""}\n")
                }
            }
        }

        if (!short) section("Eigene Daten")
        if (!short) runCatching {
            val db = container.db
            val items = db.customGroups().allItems()
            out.append("Favoriten ${db.favorites().keys().size}, eigene Gruppen ${db.customGroups().groups().size} mit ${items.size} Sendern" +
                " (davon ohne passenden Sender ${db.customGroups().orphanItems()}), ausgeblendete Gruppen ${db.hiddenGroups().all().size}\n")
            // Schlüssel beginnen mit der Playlist-Nummer: zeigt, ob Einträge zu einer Playlist gehören, die es nicht mehr gibt.
            val prefixes = (items.map { it.key } + db.favorites().keys()).map { it.substringBefore(':') }.groupingBy { it }.eachCount()
            if (prefixes.isNotEmpty()) out.append("Einträge je Playlist-Nr.: ${prefixes.entries.joinToString { "${it.key}: ${it.value}" }}\n")
        }

        if (!short) section("Untertitel")
        if (!short) runCatching {
            out.append("OpenSubtitles: ${de.ayutv.data.repo.OpenSubtitlesQuota.daily.takeIf { it > 0 }?.let { "$it Downloads am Tag" } ?: "Kontingent unbekannt"}\n")
            container.subtitles.lastProblem?.let { out.append("Letztes Problem: $it\n") }
            val journal = de.ayutv.data.repo.SubtitleJournal.lines()
            if (journal.isEmpty()) out.append("Noch nichts gesucht oder ausgerichtet\n")
            journal.forEach { out.append("  ").append(it).append('\n') }
        }

        if (!short) section("Übersetzer")
        if (!short) runCatching {
            out.append("Rechenkern ${Translators.remote.engine.label}\n")
            val pairs = Translators.onDevice.installedPairs()
            out.append("Modelle ${pairs.joinToString().ifEmpty { "keine" }} (${Translators.onDevice.installedBytes() / 1_000_000} MB)\n")
            Translators.remote.lastPeakMb?.let { out.append("Speicher höchstens $it MB\n") }
            out.append("Handy: ${de.ayutv.data.translate.PhoneHelper.describe()}\n")
        }
        if (!short) file(SELFTEST_FILE)?.let { out.append("Letzter Test:\n").append(it).append('\n') }
        NativeCrashLog.lastRecent(context.filesDir, 48 * 3_600_000L)?.let { out.append("Letzter nativer Absturz (letzte 48 h) am ").append(if (short) it.take(1_500) else it).append('\n') }

        // Nur Abstürze der letzten 48 h, mit Datum: Ein alter, längst behobener Absturz las sich sonst wie ein neuer.
        File(context.filesDir, CRASH_FILE).takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() <= 48 * 3_600_000L }?.let { crash ->
            val text = runCatching { crash.readText() }.getOrNull() ?: return@let
            section("Letzter Absturz (${SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(crash.lastModified()))})")
            out.append(if (short) text.take(2_000) else text).append('\n')
        }
        file(CAST_FILE)?.let { section("Letzte Übertragung"); out.append(it).append('\n') }
        file(PLAYER_FILE)?.let { section("Letzte Wiedergabe"); out.append(if (short) it.lines().takeLast(40).joinToString("\n") else it).append('\n') }

        if (!short) {
            section("Protokoll")
            out.append(logcat())
        }
        Redaction.apply(out.toString(), secrets(container))
    }

    /** Alle eigenen Zugangsdaten im Klartext, damit sie im Bericht garantiert nirgends stehen. */
    private suspend fun secrets(container: AppContainer): List<String> {
        val out = ArrayList<String>()
        runCatching {
            for (p in container.db.playlists().all()) {
                p.username?.let { out += it }
                p.password?.let { out += (runCatching { de.ayutv.data.security.Credentials.reveal(it) }.getOrNull() ?: it) }
            }
        }
        runCatching {
            val s = container.settings.current()
            out += listOf(s.openSubtitlesUser, s.openSubtitlesPassword, s.openSubtitlesApiKey, s.proxyUser, s.proxyPassword, s.deeplKey)
        }
        return out
    }

    /** Eigenes Systemprotokoll (Android zeigt jeder App nur ihre eigenen Zeilen). */
    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.filterNot { NOISE.containsMatchIn(it) }.takeLast(900).joinToString("\n") { it.take(400) }
    }.getOrElse { "nicht lesbar: ${it.message}" }

    private val NOISE = Regex("""Choreographer|OpenGLRenderer|ViewRootImpl|InputEventReceiver|chatty|BufferQueue""")

    private fun host(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: "?"

    private const val MB = 1024L * 1024L
}

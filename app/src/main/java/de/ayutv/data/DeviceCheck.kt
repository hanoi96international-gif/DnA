package de.ayutv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import de.ayutv.AppContainer
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * „Gerät prüfen“: in etwa einer Minute messen, was auf genau diesem Fire TV zählt – Ruckeln der Menüs, Speicher,
 * Übersetzung pro Zeile, Leitung zum Anbieter, Proxy, DeepL und OpenSubtitles. Das Ergebnis ist ein kurzer Text zum
 * Abfotografieren oder Weitergeben; Zugangsdaten stehen nicht darin.
 */
object DeviceCheck {
    suspend fun run(context: Context, container: AppContainer, progress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val settings = container.settings.current()
        val lines = ArrayList<String>()
        fun add(line: String) {
            lines += line
            progress(line)
        }
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        add(
            "Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                (if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "32 Bit" else "64 Bit") +
                ", RAM ${memory.totalMem / 1_000_000} MB (frei ${memory.availMem / 1_000_000} MB)" +
                (if (de.ayutv.ui.UiPerformance.reduced) ", Spar-Modus" else "")
        )
        add("Speicher frei: ${StorageCleaner.format(StorageCleaner.freeBytes(context))}" +
            StorageCleaner.usage(context).take(3).joinToString("") { " · ${it.label} ${StorageCleaner.format(it.bytes)}" } +
            if (StorageCleaner.isLow(context)) " – knapp!" else "")
        add("Menüs: ${de.ayutv.ui.UiPerformance.summary()}")
        add(translatorLine(context, settings))
        add(lineCheck(container, settings))
        if (settings.proxyMode != de.ayutv.data.net.ProxyMode.OFF) {
            val proxy = runCatching { de.ayutv.data.net.ProxyCheck.run(container.http, settings.proxyConfig) }.getOrNull()
            add("Proxy: " + (proxy?.proxied?.let { "${it.country} · ${it.ip}" } ?: "geht nicht – ${proxy?.error ?: "keine Antwort"}"))
        }
        if (settings.translationProvider == TranslationProvider.DEEPL) {
            val usage = runCatching { de.ayutv.data.translate.DeepLTranslator(container.http, settings.deeplKey, null).usage() }
            add("DeepL: " + usage.fold({ "${it.used} von ${it.limit} Zeichen verbraucht" }, { it.message ?: "Fehler" }))
        }
        val quota = de.ayutv.data.repo.OpenSubtitlesQuota.daily
        add("OpenSubtitles: " + when {
            settings.openSubtitlesUser.isBlank() -> "ohne Konto (5 Downloads/Tag)"
            quota > 0 -> "Konto, $quota Downloads/Tag"
            else -> "Konto eingetragen (Kontingent noch unbekannt)"
        })
        lines.joinToString("\n")
    }

    /**
     * Übersetzung pro Zeile messen, aber nur mit einem schon geladenen Modell (ein neues wären über 100 MB). Erst laden,
     * dann drei Sätze: Die gemessene Zeit je Zeile entscheidet, ob Übersetzen beim Schauen auf diesem Gerät mitkommt.
     */
    private suspend fun translatorLine(context: Context, settings: AppSettings): String {
        if (settings.translationProvider == TranslationProvider.DEEPL) return "Übersetzung: DeepL (rechnet nicht auf dem Gerät)"
        if (!settings.translationEnabled) return "Übersetzung: aus"
        val target = settings.lastTranslationTarget.ifEmpty { settings.subtitleLanguage }
        val installed = File(context.filesDir, "mt").listFiles().orEmpty().any { it.isDirectory && it.name.endsWith("-$target") }
        if (!installed) return "Übersetzung: noch kein Modell für ${Languages.label(target)} geladen (Einstellungen → Untertitel → Übersetzer testen)"
        val remote = Translators.remote
        return runCatching {
            val loadStart = SystemClock.elapsedRealtime()
            remote.translate(listOf("Hello."), target, "en")
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val sentences = listOf("Where is my brother?", "We have to leave before it gets dark.", "I told you we should never have come here.")
            val started = SystemClock.elapsedRealtime()
            for (s in sentences) remote.translate(listOf(s), target, "en")
            val perLine = (SystemClock.elapsedRealtime() - started) / sentences.size
            val verdict = when {
                perLine <= 1_200 -> "kommt beim Schauen mit"
                perLine <= 2_500 -> "knapp, mit Vorlauf meist ok"
                else -> "zu langsam beim Schauen – DeepL oder nachts vorbereiten"
            }
            "Übersetzung (${remote.engine.label}): ${perLine} ms pro Zeile, Laden ${loadMs / 1000} s – $verdict"
        }.getOrElse { "Übersetzung: ${it.message ?: it.javaClass.simpleName}" }
            // Übersetzer-Prozess wieder beenden: Speicher frei für die Wiedergabe.
            .also { runCatching { remote.release() } }
    }

    private suspend fun lineCheck(container: AppContainer, settings: AppSettings): String {
        val channel = container.db.history().recentLiveKeys(1).firstOrNull()?.let { container.db.channels().byKey(it) }
            ?: container.db.favorites().keys().firstOrNull()?.let { container.db.channels().byKey(it) }
            ?: return "Leitung: kein Sender zum Messen (erst einen Sender schauen)"
        return runCatching { "Leitung: " + de.ayutv.data.repo.LineCheck(container.http).run(channel, channel.userAgent ?: settings.userAgent, seconds = 6).summary }
            .getOrElse { "Leitung: ${it.message}" }
    }
}

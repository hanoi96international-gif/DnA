package de.ayutv.data

import de.ayutv.data.security.Credentials
import de.ayutv.data.net.ProxyConfig
import de.ayutv.data.net.ProxyMode
import de.ayutv.data.net.ProxyType
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Pufferprofile: kurz = schnelles Umschalten, lang = stabil bei 4K und wackeliger Leitung. */
enum class BufferProfile(val label: String) {
    FAST("Schnell umschalten"),
    BALANCED("Ausgewogen"),
    STABLE("Stabil (4K)"),
}

enum class LiveFormat(val label: String, val extension: String) {
    TS("MPEG-TS (schneller Start)", "ts"),
    HLS("HLS (m3u8)", "m3u8"),
}

/** Tonausgabe: Formate an den AV-Receiver durchreichen oder immer im Gerät dekodieren (Stereo/PCM). */
enum class AudioOutput(val label: String) {
    AUTO("Automatisch (Durchreichen an Receiver)"),
    PCM("Immer im Gerät dekodieren"),
}

/**
 * Übersetzung von Untertiteln, falls es keine in der Wunschsprache gibt: direkt auf dem Gerät
 * (OPUS-MT, offen lizenziert, ohne Konto und ohne Grenze).
 */
enum class TranslationProvider(val label: String) {
    ON_DEVICE("An (auf dem Gerät, unbegrenzt)"),
    /** Über das Internet (eigener DeepL-Schlüssel): schnell, ohne Rechenlast auf dem Fire TV. */
    DEEPL("DeepL (Internet, schnell)"),
    NONE("Aus"),
}

/** Mindestqualität der Senderliste. */
enum class MinQuality(val label: String, val height: Int) {
    ALL("Alle", 0),
    HD("ab HD (720p)", 720),
    FHD("ab Full HD (1080p)", 1080),
    UHD("nur 4K", 2160);

    fun next(): MinQuality = entries[(ordinal + 1) % entries.size]
}

enum class SubtitleSize(val label: String, val scale: Float) {
    NORMAL("Normal", 1.0f),
    LARGE("Groß", 1.3f),
    XL("Sehr groß", 1.6f),
}

/** Schriftfarbe der Untertitel (ARGB). */
enum class SubtitleColor(val label: String, val argb: Int) {
    WHITE("Weiß", 0xFFFFFFFF.toInt()),
    YELLOW("Gelb", 0xFFFFE14D.toInt()),
    CYAN("Hellblau", 0xFF8FE3FF.toInt()),
}

/** Wie sich die Untertitel vom Bild abheben. */
enum class SubtitleBackground(val label: String) {
    OUTLINE("Schwarzer Rand"),
    SHADOW("Schatten"),
    BOX("Dunkler Kasten"),
}

/** Abstand der Untertitel vom unteren Bildrand (Anteil der Bildhöhe). */
enum class SubtitlePosition(val label: String, val bottomFraction: Float) {
    LOW("Ganz unten", 0.04f),
    NORMAL("Unten", 0.07f),
    HIGH("Etwas höher", 0.15f),
    /**
     * 21:9-Leinwand (Beamer so gezoomt, dass Kinoformat die Leinwand füllt): oben und unten fällt ein Streifen von je
     * ~12,8 % neben die Leinwand. Untertitel daher im sichtbaren 2,39:1-Bereich, knapp über dessen Unterkante.
     */
    CINEMA("Im Kinoformat (21:9-Leinwand)", 0.18f),
}

/**
 * Bildrand ausgleichen: Fernseher mit Overscan und Beamer (Trapezkorrektur, Leinwand-Maske) schneiden den Rand ab.
 * Die ganze App samt Video rückt um diesen Anteil je Seite nach innen, damit Menüs, Uhr und Untertitel sichtbar bleiben.
 */
enum class ScreenMargin(val label: String, val fraction: Float) {
    NONE("Aus", 0f),
    SMALL("2,5 %", 0.025f),
    MEDIUM("5 %", 0.05f),
    LARGE("7,5 %", 0.075f),
    XLARGE("10 %", 0.10f),
}

/** Ton-Bild-Abgleich: Grenzen und Schritt (ms). Mehr als 0,3 s Bildverzögerung hat kein Gerät. */
const val AV_SYNC_MIN_MS = -200
const val AV_SYNC_MAX_MS = 300
const val AV_SYNC_STEP_MS = 20

/** Lesbar: „Aus“, „Ton 40 ms später“, „Ton 20 ms früher“. */
fun avSyncLabel(ms: Int): String = when {
    ms == 0 -> "Aus"
    ms > 0 -> "Ton $ms ms später"
    else -> "Ton ${-ms} ms früher"
}

/** Ton: normal, Nachtmodus (leise Stellen lauter, laute leiser) oder Dialoge hervorheben. */
enum class AudioMode(val label: String) {
    NORMAL("Normal"),
    NIGHT("Nachtmodus"),
    DIALOG("Dialoge verstärken"),
}

data class AppSettings(
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    /**
     * Filme zusätzlich auf dem Gerätespeicher vorladen (128–512 MB je nach Puffer). Hilft bei 4K auf Sticks mit wenig RAM,
     * schreibt aber den ganzen Film beim Schauen durch den Speicher – darum Standard aus.
     */
    val filmDiskBuffer: Boolean = false,
    val tunneling: Boolean = false,
    /** Halbbilder (1080i der HD-Sender) auf 50 Bilder/s umrechnen (wie Kodi/mpv). */
    val deinterlace: Boolean = true,
    /** Ton minimal an die Bildwechsel des Displays angleichen (wie Kodi „Wiedergabe mit Anzeige synchronisieren“). */
    val displaySync: Boolean = true,
    /** Live: bei knappem Vorrat kurz mit 97 % Tempo statt anzuhalten. */
    val liveCatchUp: Boolean = true,
    /** Standard an (seit 0.45.4): Deutsches TV hat 25/50 Bilder, der Fire TV gibt sonst 60 Hz aus und das Bild läuft ungleichmäßig. */
    val matchFrameRate: Boolean = true,
    /**
     * Bildrate nur bei Filmen und Serien anpassen, Live-TV bleibt bei der Rate der Menüs: Beamer brauchen für jeden
     * Wechsel der Bildfrequenz oft mehrere Sekunden Schwarzbild.
     */
    val frameRateFilmsOnly: Boolean = false,
    val liveFormat: LiveFormat = LiveFormat.TS,
    val userAgent: String = DEFAULT_USER_AGENT,
    val zapWithUpDown: Boolean = true,
    /**
     * Hardware-Decoder asynchron ansteuern. Auf Fire OS (Android 9–11) nicht Standard und mit manchen
     * Decodern instabil, daher aus; ab Android 12 nutzt Media3 es ohnehin automatisch.
     */
    val asyncDecoding: Boolean = false,
    val audioOutput: AudioOutput = AudioOutput.AUTO,
    /** Raster-TV-Guide im Menü. Die EPG-Daten werden unabhängig davon für "Jetzt läuft" genutzt. */
    val showGuide: Boolean = true,
    /** Mini-Vorschau: In der Live-TV-Liste läuft der ausgewählte Sender nach kurzer Zeit stumm in der Detailspalte. */
    val livePreview: Boolean = false,
    /** Namensauflösung verschlüsselt (DNS über HTTPS), mit Rückfall auf die normale. */
    val secureDns: Boolean = true,
    /** Flüssige Bedienung: ohne Überblendungen und mit sparsamem Hintergrund (wie auf schwachen Sticks automatisch). */
    val smoothUi: Boolean = false,
    /** Reiter „Apps“: Reihenfolge und ausgeblendete Apps (Kennungen, mit Komma getrennt). */
    val appsOrder: String = "",
    val appsHidden: String = "",
    /** Korrektur für EPGs mit falscher Zeitzone, in Stunden. */
    val epgShiftHours: Int = 0,
    /** Sender ohne Programm beim Anbieter aus einem freien EPG (Deutschland, Österreich, Schweiz) ergänzen. */
    val internetGuide: Boolean = true,
    /** Freies EPG zuerst, der Anbieter füllt nur noch auf (bei Sport oft genauere Angaben, z. B. die Paarung). */
    val preferInternetGuide: Boolean = false,
    /** Nur der eigene TV-Guide (freies EPG); der des Anbieters wird nicht geladen (oft falsche Zeiten). */
    val ownGuideOnly: Boolean = true,
    /** Vereine, deren Spiele automatisch eine Erinnerung bekommen. */
    val followedTeams: Set<String> = emptySet(),
    /** Gleiche Sender in mehreren Qualitäten (SD, HD, 4K, RAW, Backup) als einen Eintrag zeigen. */
    val mergeVariants: Boolean = true,
    /** Länderauswahl wurde schon angeboten (nur einmal von selbst). */
    val countryChoiceDone: Boolean = false,
    /** Sprache, die beim Einschalten der Untertitel gewählt wird (ISO 639-1). */
    val subtitleLanguage: String = "en",
    /** Untertitel sind optional: standardmäßig aus, einschalten im Player über die Menütaste. */
    val subtitlesAutoOn: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.LARGE,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.OUTLINE,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.NORMAL,
    val screenMargin: ScreenMargin = ScreenMargin.NONE,
    /** Menüs ohne Hintergrundbild, nur in der Grundfarbe des Designs (schwache Sticks, Beamer). */
    val calmBackground: Boolean = false,
    /** Ton-Bild-Abgleich in ms (positiv: Bild früher / Ton später), je Gerät – Beamer und Fernseher verzögern das Bild. */
    val avSyncMs: Int = 0,
    val audioMode: AudioMode = AudioMode.NORMAL,
    /** Untertitel bei OpenSubtitles suchen, wenn der Film selbst keine passenden hat. */
    val onlineSubtitles: Boolean = true,
    val openSubtitlesKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val translationProvider: TranslationProvider = TranslationProvider.ON_DEVICE,
    /** Live-TV anhalten und zurückspulen: der laufende Sender wird auf dem Gerät zwischengespeichert. */
    val liveTimeshift: Boolean = true,
    /** So viele Minuten Live-TV bleiben zum Zurückspulen gespeichert. */
    val timeshiftMinutes: Int = 30,
    /** Serien: nach einer Folge startet die nächste nach einem kurzen Countdown. */
    val autoNextEpisode: Boolean = true,
    /** Handy mit mobilen Daten: kleinere Fassung der Sender, adaptive Streams höchstens 720p. */
    val dataSaver: Boolean = true,
    /** Stichwort-Alarm: Sendungen mit diesen Begriffen bekommen automatisch eine Erinnerung. */
    val alertKeywords: Set<String> = emptySet(),
    /** Fernseher nimmt im Heimnetz Befehle vom Handy an (Fernbedienung, Abgleich), nur mit PIN. */
    val phoneRemote: Boolean = true,
    /** Zuletzt gewählte Sprache der Live-Übersetzung: deren Modell wird vorab geladen ("" = noch keine). */
    val lastTranslationTarget: String = "",
    /** In der Ruhezeit Untertitel für „Weiter schauen“ und die nächste Folge vorab holen und übersetzen. */
    val pretranslate: Boolean = false,
    /** Mindestqualität der Sender (bekannt niedrigere werden ausgeblendet). */
    val minQuality: MinQuality = MinQuality.ALL,
    /** Bei doppelt geführten Sendern 4K statt Full HD bevorzugen. */
    val preferUhd: Boolean = false,
    /** Sender, die im Hintergrund-Check wiederholt nicht laufen, ausblenden. */
    val hideBrokenChannels: Boolean = false,
    /** In der Ruhezeit prüfen, welche Fassung doppelter Sender läuft und wie gut. */
    val channelCheck: Boolean = true,
    /** Spielstände (OpenLigaDB) in der Sport-Übersicht. */
    val liveScores: Boolean = true,
    /** Tor-Hinweis im Bild, wenn ein gefolgter Verein trifft. */
    val goalAlerts: Boolean = true,
    /** Ruhebildschirm (Uhr, Live-Ergebnisse, Poster) nach einigen Minuten ohne Bedienung im Menü. */
    val ambient: Boolean = true,
    /** Lautstärke zwischen Sendern und Sendungen angleichen. */
    val loudnessLevel: Boolean = false,
    /** Weiterschauen und Lieblingssender auf der Fire-TV-Startseite. */
    val fireTvHome: Boolean = true,
    /** Aussehen der Menüs. */
    val uiTheme: de.ayutv.ui.theme.UiTheme = de.ayutv.ui.theme.UiTheme.PRESTIGE,
    val accent: de.ayutv.ui.theme.AccentChoice = de.ayutv.ui.theme.AccentChoice.DESIGN,
    /** Proxy nur für DnA-TV: aus, für alles oder nur für ausgewählte Playlists. */
    val proxyMode: ProxyMode = ProxyMode.OFF,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPassword: String = "",
    /** IDs der Playlists, die über den Proxy laufen (bei [ProxyMode.SELECTED]). */
    val proxyPlaylists: Set<String> = emptySet(),
    /**
     * Videostreams über den Proxy (Standard). Aus: nur Anmeldung, Listen und TV-Guide laufen über den Proxy, die Videos
     * direkt. Viele Proxys liefern nur 10–20 Mbit/s, zu wenig für 4K.
     */
    val proxyStreams: Boolean = true,
    /** Eigener DeepL-API-Schlüssel (Free endet auf „:fx“). */
    val deeplKey: String = "",
) {
    val proxyConfig: ProxyConfig
        get() = ProxyConfig(
            proxyMode, proxyType, proxyHost.trim(), proxyPort, proxyUser.trim(), proxyPassword,
            proxyPlaylists.mapNotNull { it.toLongOrNull() }.toSet(),
        )

    /** Eigener Schlüssel aus den Einstellungen, sonst der in die App eingebaute. */
    /** Nur der eigene Schlüssel des Nutzers (die App bringt keinen mit). */
    val openSubtitlesApiKey: String get() = openSubtitlesKey.trim()
    val onlineSubtitlesEnabled: Boolean get() = onlineSubtitles && openSubtitlesApiKey.isNotBlank()
    val translationEnabled: Boolean get() = translationProvider != TranslationProvider.NONE

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/** Eine einzige DataStore-Instanz für die Datei "settings" (zwei Instanzen derselben Datei sind nicht erlaubt). */
val Context.settingsDataStore by preferencesDataStore(name = "settings")

private const val SEARCH_HISTORY_SIZE = 12

/** Wählbare Länge des Zwischenspeichers für Live-TV. */
val TIMESHIFT_MINUTES = listOf(15, 30, 60, 90)

class SettingsStore(private val context: Context) {
    private object Keys {
        val buffer = stringPreferencesKey("buffer_profile")
        val filmDiskBuffer = booleanPreferencesKey("film_disk_buffer")
        val tunneling = booleanPreferencesKey("tunneling")
        val deinterlace = booleanPreferencesKey("deinterlace")
        val displaySync = booleanPreferencesKey("display_sync")
        val liveCatchUp = booleanPreferencesKey("live_catch_up")
        val afr = booleanPreferencesKey("match_frame_rate_v2")
        val afrFilmsOnly = booleanPreferencesKey("match_frame_rate_films_only")
        val liveFormat = stringPreferencesKey("live_format")
        val userAgent = stringPreferencesKey("user_agent")
        val zap = booleanPreferencesKey("zap_up_down")
        val epgShift = intPreferencesKey("epg_shift_hours")
        val internetGuide = booleanPreferencesKey("internet_guide")
        val preferInternetGuide = booleanPreferencesKey("prefer_internet_guide")
        val ownGuideOnly = booleanPreferencesKey("own_guide_only")
        val followedTeams = stringSetPreferencesKey("followed_teams")
        val mergeVariants = booleanPreferencesKey("merge_variants")
        val countryChoiceDone = booleanPreferencesKey("country_choice_done")
        val asyncDecoding = booleanPreferencesKey("async_decoding_v2")
        val audioOutput = stringPreferencesKey("audio_output")
        val showGuide = booleanPreferencesKey("show_guide")
        val livePreview = booleanPreferencesKey("live_preview")
        val secureDns = booleanPreferencesKey("secure_dns")
        val smoothUi = booleanPreferencesKey("smooth_ui")
        val appsOrder = stringPreferencesKey("apps_order")
        val appsHidden = stringPreferencesKey("apps_hidden")
        val subLang = stringPreferencesKey("subtitle_language_code")
        val subAuto = booleanPreferencesKey("subtitles_auto_on")
        val provider = stringPreferencesKey("translation_provider")
        val subSize = stringPreferencesKey("subtitle_size")
        val subColor = stringPreferencesKey("subtitle_color")
        val subBackground = stringPreferencesKey("subtitle_background")
        val subPosition = stringPreferencesKey("subtitle_position")
        val screenMargin = stringPreferencesKey("screen_margin")
        val calmBackground = booleanPreferencesKey("calm_background")
        val avSyncMs = intPreferencesKey("av_sync_ms")
        val audioMode = stringPreferencesKey("audio_mode")
        val osKey = stringPreferencesKey("opensubtitles_key")
        val osOn = booleanPreferencesKey("opensubtitles_on")
        val osUser = stringPreferencesKey("opensubtitles_user")
        val osPassword = stringPreferencesKey("opensubtitles_password")
        val timeshift = booleanPreferencesKey("live_timeshift")
        val timeshiftMinutes = intPreferencesKey("timeshift_minutes")
        val autoNext = booleanPreferencesKey("auto_next_episode")
        val dataSaver = booleanPreferencesKey("data_saver")
        val alertKeywords = stringSetPreferencesKey("alert_keywords")
        val phoneRemote = booleanPreferencesKey("phone_remote")
        // Neuer Schlüssel (0.36): Vorbereiten im Hintergrund ist für alle erst einmal aus, auch wer es nie umgestellt hat.
        val pretranslate = booleanPreferencesKey("pretranslate_on")
        val uiTheme = stringPreferencesKey("ui_theme")
        /** 1 = neues Standard-Design Prestige (0.47) einmal für alle eingeschaltet; danach gilt wieder die eigene Wahl. */
        val designVersion = intPreferencesKey("design_version")
        val accent = stringPreferencesKey("accent")
        val minQuality = stringPreferencesKey("min_quality")
        val preferUhd = booleanPreferencesKey("prefer_uhd")
        val hideBroken = booleanPreferencesKey("hide_broken_channels")
        val channelCheck = booleanPreferencesKey("channel_check")
        val liveScores = booleanPreferencesKey("live_scores")
        val goalAlerts = booleanPreferencesKey("goal_alerts")
        val ambient = booleanPreferencesKey("ambient_screen")
        val loudnessLevel = booleanPreferencesKey("loudness_level")
        val fireTvHome = booleanPreferencesKey("fire_tv_home")
        val lastTranslationTarget = stringPreferencesKey("last_translation_target")
        val proxyMode = stringPreferencesKey("proxy_mode")
        val proxyType = stringPreferencesKey("proxy_type")
        val proxyHost = stringPreferencesKey("proxy_host")
        val proxyPort = intPreferencesKey("proxy_port")
        val proxyUser = stringPreferencesKey("proxy_user")
        val proxyPassword = stringPreferencesKey("proxy_password")
        val proxyPlaylists = stringSetPreferencesKey("proxy_playlists")
        val proxyStreams = booleanPreferencesKey("proxy_streams")
        val deeplKey = stringPreferencesKey("deepl_key")

        /** Verschlüsselt gespeicherte Werte (in Sicherungen im Klartext, der Schlüssel gilt nur auf diesem Gerät). */
        val secrets = setOf(osPassword, proxyPassword, deeplKey)
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private val searchHistoryKey = stringPreferencesKey("search_history")

    /** Letzte Suchbegriffe, neueste zuerst. */
    val searchHistory: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[searchHistoryKey].orEmpty().split('\n').filter { it.isNotBlank() }
    }

    suspend fun rememberSearch(query: String) {
        val q = query.trim().takeIf { it.length >= 2 } ?: return
        context.settingsDataStore.edit { prefs ->
            val old = prefs[searchHistoryKey].orEmpty().split('\n').filter { it.isNotBlank() }
            prefs[searchHistoryKey] = (listOf(q) + old.filterNot { it.equals(q, ignoreCase = true) })
                .take(SEARCH_HISTORY_SIZE).joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.settingsDataStore.edit { it.remove(searchHistoryKey) }
    }

    suspend fun current(): AppSettings = settings.first()

    /** Alle gespeicherten Einstellungen nach Typ (für die Sicherung). */
    suspend fun export(): Map<String, Map<String, Any>> {
        val prefs = context.settingsDataStore.data.first().asMap()
        val out = linkedMapOf<String, MutableMap<String, Any>>()
        for ((key, value) in prefs) {
            // Kindersicherung bleibt auf dem Gerät: Eine Sicherung soll sie nicht aushebeln.
            if (key.name.startsWith("parental_")) continue
            val type = when (value) {
                is Boolean -> "booleans"
                is Int -> "ints"
                is Long -> "longs"
                is String -> "strings"
                is Set<*> -> "stringSets"
                else -> continue
            }
            // Passwörter im Klartext: Der Schlüssel des Keystores gilt nur auf diesem Gerät.
            val portable = if (Keys.secrets.any { it.name == key.name }) Credentials.reveal(value as? String).orEmpty() else value
            out.getOrPut(type) { linkedMapOf() }[key.name] = portable
        }
        return out
    }

    /** Einstellungen aus einer Sicherung übernehmen (unbekannte Einträge bleiben, wie sie sind). */
    suspend fun import(values: Map<String, Map<String, Any>>) {
        fun <T> Map<String, T>.allowed() = filterKeys { !it.startsWith("parental_") }
        context.settingsDataStore.edit { prefs ->
            values["booleans"]?.allowed()?.forEach { (k, v) -> (v as? Boolean)?.let { prefs[booleanPreferencesKey(k)] = it } }
            values["ints"]?.allowed()?.forEach { (k, v) -> (v as? Number)?.let { prefs[intPreferencesKey(k)] = it.toInt() } }
            values["longs"]?.allowed()?.forEach { (k, v) -> (v as? Number)?.let { prefs[longPreferencesKey(k)] = it.toLong() } }
            values["strings"]?.allowed()?.forEach { (k, v) ->
                (v as? String)?.let { prefs[stringPreferencesKey(k)] = if (Keys.secrets.any { s -> s.name == k }) Credentials.protect(it).orEmpty() else it }
            }
            values["stringSets"]?.allowed()?.forEach { (k, v) ->
                (v as? Collection<*>)?.let { prefs[stringSetPreferencesKey(k)] = it.filterIsInstance<String>().toSet() }
            }
        }
    }

    /** Einmalig: ein unverschlüsselt gespeichertes Passwort (ältere Versionen) verschlüsseln. */
    suspend fun protectStoredSecrets() {
        context.settingsDataStore.edit { prefs ->
            for (key in Keys.secrets) {
                val stored = prefs[key]
                if (!stored.isNullOrEmpty() && !Credentials.isProtected(stored)) prefs[key] = Credentials.protect(stored).orEmpty()
            }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.buffer] = next.bufferProfile.name
            prefs[Keys.filmDiskBuffer] = next.filmDiskBuffer
            prefs[Keys.tunneling] = next.tunneling
            prefs[Keys.deinterlace] = next.deinterlace
            prefs[Keys.displaySync] = next.displaySync
            prefs[Keys.liveCatchUp] = next.liveCatchUp
            prefs[Keys.afr] = next.matchFrameRate
            prefs[Keys.afrFilmsOnly] = next.frameRateFilmsOnly
            prefs[Keys.liveFormat] = next.liveFormat.name
            prefs[Keys.userAgent] = next.userAgent
            prefs[Keys.zap] = next.zapWithUpDown
            prefs[Keys.epgShift] = next.epgShiftHours
            prefs[Keys.internetGuide] = next.internetGuide
            prefs[Keys.preferInternetGuide] = next.preferInternetGuide
            prefs[Keys.ownGuideOnly] = next.ownGuideOnly
            prefs[Keys.followedTeams] = next.followedTeams
            prefs[Keys.mergeVariants] = next.mergeVariants
            prefs[Keys.countryChoiceDone] = next.countryChoiceDone
            prefs[Keys.asyncDecoding] = next.asyncDecoding
            prefs[Keys.audioOutput] = next.audioOutput.name
            prefs[Keys.showGuide] = next.showGuide
            prefs[Keys.livePreview] = next.livePreview
            prefs[Keys.secureDns] = next.secureDns
            prefs[Keys.smoothUi] = next.smoothUi
            prefs[Keys.appsOrder] = next.appsOrder
            prefs[Keys.appsHidden] = next.appsHidden
            prefs[Keys.subLang] = next.subtitleLanguage
            prefs[Keys.subAuto] = next.subtitlesAutoOn
            prefs[Keys.provider] = next.translationProvider.name
            prefs[Keys.subSize] = next.subtitleSize.name
            prefs[Keys.subColor] = next.subtitleColor.name
            prefs[Keys.subBackground] = next.subtitleBackground.name
            prefs[Keys.subPosition] = next.subtitlePosition.name
            prefs[Keys.screenMargin] = next.screenMargin.name
            prefs[Keys.calmBackground] = next.calmBackground
            prefs[Keys.avSyncMs] = next.avSyncMs.coerceIn(AV_SYNC_MIN_MS, AV_SYNC_MAX_MS)
            prefs[Keys.audioMode] = next.audioMode.name
            prefs[Keys.osKey] = next.openSubtitlesKey.trim()
            prefs[Keys.osOn] = next.onlineSubtitles
            prefs[Keys.osUser] = next.openSubtitlesUser.trim()
            prefs[Keys.osPassword] = Credentials.protect(next.openSubtitlesPassword).orEmpty()
            prefs[Keys.timeshift] = next.liveTimeshift
            prefs[Keys.timeshiftMinutes] = next.timeshiftMinutes
            prefs[Keys.autoNext] = next.autoNextEpisode
            prefs[Keys.dataSaver] = next.dataSaver
            prefs[Keys.alertKeywords] = next.alertKeywords
            prefs[Keys.phoneRemote] = next.phoneRemote
            prefs[Keys.lastTranslationTarget] = next.lastTranslationTarget
            prefs[Keys.pretranslate] = next.pretranslate
            prefs[Keys.uiTheme] = next.uiTheme.name
            prefs[Keys.designVersion] = DESIGN_VERSION
            prefs[Keys.accent] = next.accent.name
            prefs[Keys.minQuality] = next.minQuality.name
            prefs[Keys.preferUhd] = next.preferUhd
            prefs[Keys.hideBroken] = next.hideBrokenChannels
            prefs[Keys.channelCheck] = next.channelCheck
            prefs[Keys.liveScores] = next.liveScores
            prefs[Keys.goalAlerts] = next.goalAlerts
            prefs[Keys.ambient] = next.ambient
            prefs[Keys.loudnessLevel] = next.loudnessLevel
            prefs[Keys.fireTvHome] = next.fireTvHome
            prefs[Keys.proxyMode] = next.proxyMode.name
            prefs[Keys.proxyType] = next.proxyType.name
            prefs[Keys.proxyHost] = next.proxyHost.trim()
            prefs[Keys.proxyPort] = next.proxyPort
            prefs[Keys.proxyUser] = next.proxyUser.trim()
            prefs[Keys.proxyPassword] = Credentials.protect(next.proxyPassword).orEmpty()
            prefs[Keys.proxyPlaylists] = next.proxyPlaylists
            prefs[Keys.proxyStreams] = next.proxyStreams
            prefs[Keys.deeplKey] = Credentials.protect(next.deeplKey.trim()).orEmpty()
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        bufferProfile = enumOrDefault(this[Keys.buffer], BufferProfile.BALANCED),
        filmDiskBuffer = this[Keys.filmDiskBuffer] ?: false,
        tunneling = this[Keys.tunneling] ?: false,
        deinterlace = this[Keys.deinterlace] ?: true,
        displaySync = this[Keys.displaySync] ?: true,
        liveCatchUp = this[Keys.liveCatchUp] ?: true,
        matchFrameRate = this[Keys.afr] ?: true,
        frameRateFilmsOnly = this[Keys.afrFilmsOnly] ?: false,
        liveFormat = enumOrDefault(this[Keys.liveFormat], LiveFormat.TS),
        userAgent = this[Keys.userAgent]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_USER_AGENT,
        zapWithUpDown = this[Keys.zap] ?: true,
        epgShiftHours = this[Keys.epgShift] ?: 0,
        internetGuide = this[Keys.internetGuide] ?: true,
        preferInternetGuide = this[Keys.preferInternetGuide] ?: false,
        ownGuideOnly = this[Keys.ownGuideOnly] ?: true,
        followedTeams = this[Keys.followedTeams] ?: emptySet(),
        mergeVariants = this[Keys.mergeVariants] ?: true,
        countryChoiceDone = this[Keys.countryChoiceDone] ?: false,
        asyncDecoding = this[Keys.asyncDecoding] ?: false,
        audioOutput = enumOrDefault(this[Keys.audioOutput], AudioOutput.AUTO),
        showGuide = this[Keys.showGuide] ?: true,
        livePreview = this[Keys.livePreview] ?: false,
        secureDns = this[Keys.secureDns] ?: true,
        smoothUi = this[Keys.smoothUi] ?: false,
        appsOrder = this[Keys.appsOrder] ?: "",
        appsHidden = this[Keys.appsHidden] ?: "",
        subtitleLanguage = this[Keys.subLang]?.takeIf { Languages.of(it) != null } ?: "en",
        subtitlesAutoOn = this[Keys.subAuto] ?: false,
        translationProvider = enumOrDefault(this[Keys.provider], TranslationProvider.ON_DEVICE),
        subtitleSize = enumOrDefault(this[Keys.subSize], SubtitleSize.LARGE),
        subtitleColor = enumOrDefault(this[Keys.subColor], SubtitleColor.WHITE),
        subtitleBackground = enumOrDefault(this[Keys.subBackground], SubtitleBackground.OUTLINE),
        subtitlePosition = enumOrDefault(this[Keys.subPosition], SubtitlePosition.NORMAL),
        screenMargin = enumOrDefault(this[Keys.screenMargin], ScreenMargin.NONE),
        calmBackground = this[Keys.calmBackground] ?: false,
        avSyncMs = (this[Keys.avSyncMs] ?: 0).coerceIn(AV_SYNC_MIN_MS, AV_SYNC_MAX_MS),
        audioMode = enumOrDefault(this[Keys.audioMode], AudioMode.NORMAL),
        openSubtitlesKey = this[Keys.osKey].orEmpty(),
        onlineSubtitles = this[Keys.osOn] ?: true,
        openSubtitlesUser = this[Keys.osUser].orEmpty(),
        openSubtitlesPassword = Credentials.reveal(this[Keys.osPassword]).orEmpty(),
        liveTimeshift = this[Keys.timeshift] ?: true,
        timeshiftMinutes = this[Keys.timeshiftMinutes]?.takeIf { it in TIMESHIFT_MINUTES } ?: 30,
        autoNextEpisode = this[Keys.autoNext] ?: true,
        dataSaver = this[Keys.dataSaver] ?: true,
        alertKeywords = this[Keys.alertKeywords] ?: emptySet(),
        phoneRemote = this[Keys.phoneRemote] ?: true,
        lastTranslationTarget = this[Keys.lastTranslationTarget].orEmpty(),
        pretranslate = this[Keys.pretranslate] ?: false,
        // Mit 0.47 einmal für alle das neue Design Prestige; wer danach ein anderes wählt, behält es.
        uiTheme = if ((this[Keys.designVersion] ?: 0) < DESIGN_VERSION) de.ayutv.ui.theme.UiTheme.PRESTIGE
            else enumOrDefault(this[Keys.uiTheme], de.ayutv.ui.theme.UiTheme.PRESTIGE),
        accent = enumOrDefault(this[Keys.accent], de.ayutv.ui.theme.AccentChoice.DESIGN),
        minQuality = enumOrDefault(this[Keys.minQuality], MinQuality.ALL),
        // Ab Werk 4K, wenn das Gerät 4K dekodieren kann (4K bleibt 4K); wer umstellt, behält seine Wahl.
        preferUhd = this[Keys.preferUhd] ?: DeviceInfo.decodes4k,
        hideBrokenChannels = this[Keys.hideBroken] ?: false,
        channelCheck = this[Keys.channelCheck] ?: true,
        liveScores = this[Keys.liveScores] ?: true,
        goalAlerts = this[Keys.goalAlerts] ?: true,
        ambient = this[Keys.ambient] ?: true,
        loudnessLevel = this[Keys.loudnessLevel] ?: false,
        fireTvHome = this[Keys.fireTvHome] ?: true,
        proxyMode = enumOrDefault(this[Keys.proxyMode], ProxyMode.OFF),
        proxyStreams = this[Keys.proxyStreams] ?: true,
        proxyType = enumOrDefault(this[Keys.proxyType], ProxyType.HTTP),
        proxyHost = this[Keys.proxyHost].orEmpty(),
        proxyPort = this[Keys.proxyPort] ?: 0,
        proxyUser = this[Keys.proxyUser].orEmpty(),
        proxyPassword = Credentials.reveal(this[Keys.proxyPassword]).orEmpty(),
        proxyPlaylists = this[Keys.proxyPlaylists] ?: emptySet(),
        deeplKey = Credentials.reveal(this[Keys.deeplKey]).orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

/** Version des Standard-Designs: Steigt sie, bekommen alle einmal das neue Standard-Design. */
private const val DESIGN_VERSION = 1

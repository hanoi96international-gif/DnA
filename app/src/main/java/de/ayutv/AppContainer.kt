package de.ayutv

import android.content.Context
import de.ayutv.data.SettingsStore
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.repo.ContentRepository
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.data.repo.SubtitleRepository
import de.ayutv.data.repo.Translators
import de.ayutv.data.reminders.Reminders
import de.ayutv.data.parental.ParentalControl
import de.ayutv.data.translate.OnDeviceTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import de.ayutv.data.net.AppProxy
import de.ayutv.data.net.ProxyConfig
import de.ayutv.data.net.ProxyMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    /** Ganze Datenbank verschlüsselt (SQLCipher); der Schlüssel ist durch den Android-Keystore geschützt. */
    val db = de.ayutv.data.security.DatabaseKey.obtain(context).let { AyuDatabase.build(context, it.key, keyLost = it.lost) }

    /** Ein gemeinsamer Client: Verbindungen zum IPTV-Server bleiben offen, das spart beim Zappen den TCP/TLS-Aufbau. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followSslRedirects(true)
        // Proxy nur für DnA-TV (Einstellungen → Wiedergabe → Proxy); ohne Einstellung geht alles direkt.
        .proxySelector(AppProxy.selector)
        .proxyAuthenticator(AppProxy.authenticator)
        .addNetworkInterceptor(AppProxy.interceptor)
        // Verschlüsselt, wo es geht: Namensauflösung über HTTPS, bekannte Dienste nie per http://.
        .dns(de.ayutv.data.net.SecureDns)
        .addInterceptor(de.ayutv.data.net.HttpsUpgrade)
        .build()

    val epgOverrides = de.ayutv.data.EpgOverrides(context)
    val accountLimits = de.ayutv.data.AccountLimits(context)
    /** Eigene Sendernummern (OK lange auf einem Sender → „Sendernummer festlegen“). */
    val channelNumbers = de.ayutv.data.ChannelNumbers(context)
    val playlists = PlaylistRepository(db, http, settings, context.cacheDir, epgOverrides, accountLimits).also { repo ->
        // Nach jedem Laden einer Playlist kurz die wichtigsten Sender prüfen: „Best of“ ist so gleich gefüllt.
        repo.onImported = {
            // Eigene Sendernummern wieder eintragen (der Import schreibt die Nummern der Playlist).
            scope.launch { runCatching { channelNumbers.apply(db) } }
            runCatching { de.ayutv.data.repo.ChannelCheckWorker.runQuick(context) }
            // Neue Server der Playlist für den Proxy übernehmen.
            scope.launch { applyProxy() }
        }
    }
    val content = ContentRepository(db, http)
    /** Spielstände für Sport-Übersicht und Tor-Hinweise. */
    val liveScores = de.ayutv.data.sport.LiveScores(http)
    /** Vereinswappen für die Anzeigetafel im Sport-Bereich (gemerkt in crests.tsv). */
    // „crests2“: Mit Dienst als erster Quelle neu sammeln (in „crests.tsv“ standen teils veraltete Wappen, etwa Wolfsburg).
    val crests = de.ayutv.data.sport.Crests(http, java.io.File(context.filesDir, "crests2.tsv").also { java.io.File(context.filesDir, "crests.tsv").delete() })
    // filesDir statt cacheDir: Fire OS leert den Cache bei wenig Speicher, Untertitel sollen bleiben.
    val subtitles = SubtitleRepository(http, context.filesDir).also { repo ->
        scope.launch(Dispatchers.IO) { repo.purgeLegacyCache() }
    }

    /** Suche von außen (Suchtaste der Fernbedienung, Sprachassistent): Begriff für die Suche, "" = nur öffnen. */
    val searchRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val reminders = Reminders(context)
    /** Erinnerung, die gerade fällig ist (Anstoß eines gefolgten Vereins …): der Player blendet sie ein. */
    val dueReminder = kotlinx.coroutines.flow.MutableStateFlow<de.ayutv.data.reminders.Reminder?>(null)
    val intros = de.ayutv.data.IntroStore(context)
    val playerMemory = de.ayutv.data.PlayerMemory(context)
    /** Fenster der App im Vordergrund (für Tasten vom Handy). */
    @Volatile var foreground: java.lang.ref.WeakReference<android.app.Activity>? = null
    val remote = de.ayutv.data.remote.RemoteServer(context, this)
    /** Gemessene Qualität der Senderfassungen ([de.ayutv.data.parse.ChannelQuality]). */
    val qualityFile = java.io.File(context.filesDir, "channel_quality.tsv")
    val recordings = de.ayutv.data.record.Recordings(context)
    val profiles = de.ayutv.data.profile.Profiles(context, this)
    val updates = de.ayutv.data.update.Updater(context, http)
    val parental = ParentalControl(context, db)

    /** Proxy-Einstellungen anwenden; bei „Nur ausgewählte Playlists“ deren Server ermitteln. */
    suspend fun applyProxy(config: ProxyConfig? = null) {
        val next = config ?: runCatching { settings.current().proxyConfig }.getOrElse { ProxyConfig() }
        val hosts = if (next.active && next.mode == ProxyMode.SELECTED) {
            withContext(Dispatchers.IO) { runCatching { proxyHosts(next.playlists) }.getOrDefault(emptySet()) }
        } else emptySet()
        AppProxy.configure(next, hosts)
        // Offene Verbindungen nicht weiterverwenden: Sie liefen über den bisherigen Weg.
        http.connectionPool.evictAll()
    }

    private suspend fun proxyHosts(ids: Set<Long>): Set<String> = buildSet {
        for (id in ids) {
            val playlist = db.playlists().byId(id) ?: continue
            AppProxy.hostOf(playlist.url)?.let(::add)
            AppProxy.hostOf(playlist.epgUrl)?.let(::add)
            db.channels().originsOf(id).mapNotNullTo(this) { AppProxy.hostOf(it) }
            db.vod().originsOf(id).mapNotNullTo(this) { AppProxy.hostOf(it) }
        }
    }

    /** Spiele der gefolgten Vereine als Erinnerungen vormerken (nach jedem TV-Guide-Update). */
    /** Läuft gerade ein Sender oder Film im Player? */
    val watching: Boolean get() = foreground?.get().let { it is de.ayutv.player.PlayerActivity }

    /**
     * Playlists und TV-Guide auffrischen, ohne das Schauen zu stören: Ein Import schreibt zehntausende Sendungen in
     * die Datenbank, und das gleichzeitig mit der Live-Pause auf denselben Speicher – auf dem Stick hakt dann das
     * Bild. Deshalb erst eine Minute nach dem Start und nicht, solange der Player läuft. Sofort nur, wenn noch gar
     * nichts geladen ist oder der TV-Guide älter als 36 Stunden ist.
     */
    suspend fun syncWhenIdle(startDelayMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        val urgent = db.playlists().all().filter { it.enabled }.any { it.lastSync == 0L || now - it.lastEpgSync > 36 * 3_600_000L }
        if (!urgent) {
            kotlinx.coroutines.delay(startDelayMs)
            while (watching) kotlinx.coroutines.delay(30_000)
        }
        playlists.syncAll()
        refreshTeamReminders()
    }

    suspend fun refreshTeamReminders() {
        runCatching {
            val current = settings.current()
            if (current.followedTeams.isNotEmpty()) reminders.ensure(content.teamReminders(current.followedTeams))
            if (current.alertKeywords.isNotEmpty()) reminders.ensure(content.keywordReminders(current.alertKeywords))
        }.onFailure { android.util.Log.w("AyuTeams", "Vereins-Erinnerungen", it) }
    }

    init {
        // SOCKS5 mit Anmeldung: Java holt die Zugangsdaten über den systemweiten Authenticator.
        java.net.Authenticator.setDefault(AppProxy.socksAuthenticator)
        scope.launch {
            runCatching { settings.settings.map { it.proxyConfig }.distinctUntilChanged().collect { applyProxy(it) } }
                .onFailure { android.util.Log.w("AyuProxy", "Einstellungen", it) }
            // Nie hängen lassen: Ohne lesbare Einstellungen bleibt es beim letzten Stand (oder „kein Proxy“).
            AppProxy.configure(AppProxy.current, emptySet())
        }
        Translators.onDevice = OnDeviceTranslator(context.filesDir, http)
        Translators.http = http
        Translators.remote = de.ayutv.data.translate.RemoteTranslator(context.applicationContext)
        Translators.withPhone = de.ayutv.data.translate.HelperAwareTranslator(Translators.remote, http)
        // Aussehen gleich anwenden (Menüs und Player lesen die Farben aus de.ayutv.ui.theme.AyuColors).
        scope.launch(Dispatchers.Main) { runCatching { settings.current().let { de.ayutv.ui.theme.AyuColors.use(it.uiTheme, it.accent) } } }
        // Senderqualität: Gemessenes laden, Filter und Vorlieben aus den Einstellungen übernehmen.
        scope.launch(Dispatchers.IO) { de.ayutv.data.parse.ChannelQuality.load(qualityFile) }
        scope.launch {
            settings.settings.collect { s ->
                de.ayutv.data.parse.ChannelQuality.configure(s.minQuality.height, s.preferUhd, s.hideBrokenChannels)
                de.ayutv.data.net.SecureDns.enabled = s.secureDns || AppProxy.current.active
                // Bei aktivem Proxy immer DoH, auch wenn der Nutzer es abgeschaltet hat
            }
        }
        // Passwörter älterer Versionen verschlüsseln (einmalig, danach ohne Wirkung).
        scope.launch {
            runCatching { playlists.protectStoredPasswords() }
            runCatching { settings.protectStoredSecrets() }
        }
    }
}

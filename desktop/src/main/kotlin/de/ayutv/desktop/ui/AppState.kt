package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.desktop.data.BackupImport
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.numberOf
import de.ayutv.desktop.data.GroupRef
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.data.TimingMark
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.player.BufferProfile
import de.ayutv.desktop.player.SubtitleFiles
import de.ayutv.desktop.player.Subtitles
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.remote.PhoneRemote
import de.ayutv.player.SpeechSync
import de.ayutv.player.SubtitleTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val label: String, val icon: String) {
    HOME("Start", "⌂"),
    LIVE("Live TV", "📺"),
    GUIDE("TV-Guide", "🗓"),
    SPORT("Sport", "⚽"),
    MOVIES("Filme", "🎬"),
    SERIES("Serien", "🎞"),
    SEARCH("Suche", "🔍"),
    APPS("Apps", "▦"),
    SETTINGS("Einstellungen", "⚙"),
}

/** Was gerade läuft. */
sealed interface Playback {
    val title: String

    /** Live-Sender; [list] ist die Liste, in der gezappt wird. */
    data class Live(val channel: Channel, val list: List<Channel>) : Playback {
        override val title get() = channel.name
    }

    /** Film oder Folge. [key] merkt den Wiedergabestand. */
    data class Vod(
        val key: String,
        override val title: String,
        val url: String,
        val startMs: Long = 0,
        val series: Series? = null,
        val episodes: List<Pair<XtreamEpisode, String>> = emptyList(),
        val episodeId: String? = null,
        /** Aus dem Archiv (Catch-up): kein Wiedergabestand, „Live“ springt zum Sender zurück. */
        val archive: Channel? = null,
    ) : Playback
}

/** Zustand der ganzen App (eine Instanz je Fenster). */
class AppState(val store: Store, val sync: Sync, val scope: CoroutineScope, val input: RemoteInput = RemoteInput()) {
    val player = VideoPlayer(de.ayutv.desktop.player.PlayerOptions.engine(store.user.value)).also { p ->
        p.audioDelayMs = store.user.value.audioDelayMs
        p.equalizerBands = de.ayutv.desktop.player.PlayerOptions.equalizer(store.user.value)
    }
    val backup = BackupImport(store)
    val subtitles = Subtitles(sync.http, java.io.File(store.dir, "subtitles"))
    val remote = PhoneRemote(
        store, input,
        onPlay = { key -> store.channel(key)?.let { play(Playback.Live(it, store.allVisible())) } },
        onText = { text, submit -> receiveText(text, submit) },
    )

    /** Text, den die Suche übernehmen soll (vom Handy, wenn gerade kein Feld ausgewählt war). */
    var phoneSearch by mutableStateOf<String?>(null)

    /**
     * Text von der Handy-Fernbedienung: ins ausgewählte Feld; ist keins ausgewählt und läuft nichts, in die Suche.
     * Beim Fernsehen bleibt das Bild (das Handy zeigt dann nur passende Sender).
     */
    private fun receiveText(text: String, submit: Boolean) {
        val target = TextTarget.current
        if (target != null) {
            target.set(text)
            if (submit) target.submit()
            return
        }
        if (playing != null || multiview != null) return
        screen = Screen.SEARCH
        phoneSearch = text
    }
    var remoteAddress by mutableStateOf<String?>(null); private set
    val updater = de.ayutv.desktop.update.Updater(sync.http)
    val deviceSync = de.ayutv.desktop.data.DeviceSync(store, sync.http)
    val recorder = de.ayutv.desktop.player.Recorder()
    val channelCheck = de.ayutv.desktop.player.ChannelCheck(store)
    /** Zuletzt etwas abgespielt (der Sender-Check wartet danach 5 Minuten). */
    @Volatile private var lastPlaybackAt = System.currentTimeMillis()
    /** Ergebnis des letzten Abgleichs mit dem Fire TV (für die Einstellungen). */
    var deviceSyncStatus by mutableStateOf<String?>(null); private set

    /** Handy-Fernbedienung ein-/ausschalten (Windows fragt beim ersten Mal nach der Firewall-Freigabe). */
    fun setPhoneRemote(on: Boolean) {
        runCatching { if (on) remote.start() else remote.stop() }
            .onFailure { toast("Fernbedienung lässt sich nicht starten: ${it.message}") }
        store.updateUser { it.copy(phoneRemote = on && remote.running) }
        remoteAddress = remote.address()
    }

    var screen by mutableStateOf(if (store.playlists.value.isEmpty()) Screen.SETTINGS else Screen.HOME)
    var playing by mutableStateOf<Playback?>(null); private set
    /** Geöffnete Serie (Folgenansicht), auch aus der Suche heraus. */
    var openSeries by mutableStateOf<Series?>(null)
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null
    /** Live TV: zuletzt gewählte Gruppe und Sender (nach dem Schauen geht es dort weiter). */
    var liveGroup: GroupRef? = null
    var liveFocus: String? = null
    /** Erinnerung, die gerade fällig ist (Dialog „Umschalten?“). */
    var dueReminder by mutableStateOf<de.ayutv.desktop.data.Reminder?>(null)
    private val shownReminders = HashSet<String>()
    /** Profilauswahl zeigen (beim Start, wenn es mehrere Profile gibt, und aus der Navigation). */
    var choosingProfile by mutableStateOf(store.profiles.value.size > 1)

    /** Zu einem anderen Profil wechseln (Wiedergabe endet, Startseite). false = falsche PIN. */
    fun switchProfile(id: Long, pin: String = ""): Boolean {
        if (id == store.profile.value) { choosingProfile = false; return true }
        if (playing != null) stopPlayback()
        if (!store.switchProfile(id, pin)) return false
        liveGroup = null
        liveFocus = null
        openSeries = null
        applyBuffer()
        applyProxy()
        screen = Screen.HOME
        choosingProfile = false
        toast("Profil: ${store.currentProfile().name}")
        return true
    }

    /** Länderauswahl anbieten (nach dem ersten Laden). */
    var askCountries by mutableStateOf(false)

    /** Zurück-Aktionen, die neueste zuerst (Dialog, Unterseite, Player). */
    private val backStack = mutableStateListOf<() -> Boolean>()

    /** Spielplan und Stände (OpenLigaDB). */
    val liveScores = de.ayutv.data.sport.LiveScores(sync.http)
    /** Vereinswappen für die Anzeigetafel (gemerkt in crests.tsv). */
    val crests = de.ayutv.data.sport.Crests(sync.http, java.io.File(store.dir, "crests.tsv"))

    init {
        if (store.user.value.phoneRemote) setPhoneRemote(true)
        applyBuffer()
        // Spielplan für die Partie im TV-Guide und in der Vorschau, wenn der TV-Guide nur „Live: 2. BL“ nennt.
        de.ayutv.data.sport.MatchHints.install(liveScores)
        scope.launch {
            while (true) {
                // Laufende Sport-Sendungen: welche Ligen gebraucht werden, dann Spielpläne laden und alle gemeinsam
                // zuordnen (per Ausschluss über alle Sender) – auch ohne Besuch im Sport-Bereich steht die Partie.
                val shows = runCatching {
                    withContext(Dispatchers.Default) {
                        de.ayutv.desktop.data.SportEvents.of(store.listedChannels(), store.guide.value, store.user.value.favorites.toSet(), hours = 3)
                    }.map { e -> e.programme.let { de.ayutv.data.sport.LiveScores.Show(it.title, it.subtitle, it.description, it.start, it.stop, e.pairing) } }
                }.getOrDefault(emptyList())
                liveScores.want(shows.map { de.ayutv.data.sport.LiveScores.leagueOf(it.title + " " + it.subtitle.orEmpty()) })
                runCatching { de.ayutv.data.sport.MatchHints.update(liveScores.current()) }
                runCatching { de.ayutv.data.sport.MatchHints.learn(shows) }
                delay(if (de.ayutv.data.sport.MatchHints.matches.isEmpty()) 60_000L else 10 * 60_000L)
            }
        }
        // Updates: kurz nach dem Start bei GitHub nachsehen, danach alle 6 Stunden.
        scope.launch {
            delay(20_000)
            while (true) {
                updater.check()?.let { toast("Update auf ${it.version} verfügbar: Einstellungen → Update") }
                delay(6 * 3_600_000L)
            }
        }
        // Sender-Check in der Ruhezeit (nichts läuft seit 5 Minuten), höchstens alle 12 Stunden.
        scope.launch {
            delay(5 * 60_000L)
            while (true) {
                fun idle() = playing == null && System.currentTimeMillis() - lastPlaybackAt > 5 * 60_000L &&
                    store.user.value.recordings.none { it.state == de.ayutv.desktop.data.Recording.RUNNING }
                if (idle() && System.currentTimeMillis() - channelCheck.lastRun > de.ayutv.desktop.player.ChannelCheck.EVERY_MS) {
                    runCatching { channelCheck.run(::idle) }.onFailure { de.ayutv.desktop.Log.warn("Sender-Check", it) }
                }
                delay(5 * 60_000L)
            }
        }
        // Abgleich mit dem Fire TV alle 30 Minuten (wenn eingeschaltet).
        scope.launch {
            delay(30_000)
            while (true) {
                val u = store.user.value
                if (u.autoSync && u.syncAddress.isNotBlank() && u.syncPin.isNotBlank()) syncDevices(quiet = true)
                delay(30 * 60_000L)
            }
        }
        // Erinnerungen: kurz vor Beginn fragen; Spiele gefolgter Vereine alle 30 Minuten einplanen.
        scope.launch {
            var lastTeams = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastTeams > 30 * 60_000L && store.user.value.followedTeams.isNotEmpty()) {
                    lastTeams = now
                    runCatching { planTeamReminders(now) }.onFailure { de.ayutv.desktop.Log.warn("Vereins-Erinnerungen", it) }
                }
                val due = store.user.value.reminders.firstOrNull { r ->
                    r.start - REMINDER_AHEAD_MS <= now && now < r.start + 10 * 60_000L && "${r.channelKey}@${r.start}" !in shownReminders
                }
                if (due != null && dueReminder == null) {
                    shownReminders += "${due.channelKey}@${due.start}"
                    dueReminder = due
                    runCatching { input.window?.toFront() }
                }
                runCatching { withContext(Dispatchers.IO) { runRecordings(now) } }.onFailure { de.ayutv.desktop.Log.warn("Aufnahmen", it) }
                // Vorbei: aufräumen.
                val old = store.user.value.reminders.filter { now > it.start + 30 * 60_000L }
                if (old.isNotEmpty()) store.updateUser { it.copy(reminders = it.reminders - old.toSet()) }
                delay(15_000)
            }
        }
        // Wie auf dem Fire TV: Playlists alle 12 Stunden, TV-Guide alle 8 Stunden nachladen, auch wenn DnA-TV läuft.
        scope.launch {
            delay(1_500)
            while (true) {
                val first = store.playlists.value.any { it.enabled && it.lastSync == 0L }
                val problems = runCatching { sync.refreshDue() }.getOrElse { listOf(it.message ?: "Fehler") }
                applyProxy()
                if (problems.isNotEmpty()) {
                    problems.forEach { de.ayutv.desktop.Log.warn("Aktualisieren: $it") }
                    toast("Aktualisieren: ${problems.first()}")
                }
                if (first && !store.user.value.countriesAsked && store.countries().size > 1) askCountries = true
                delay(REFRESH_CHECK_MS)
            }
        }
    }

    /** Proxy geändert (Einstellungen, Sicherung) oder neue Sender geladen: Wege neu festlegen. */
    fun applyProxy() = Network.apply(store)

    /** Pufferprofil aus den Einstellungen an den Player geben. */
    fun applyBuffer() {
        player.networkCacheMs = BufferProfile.of(store.user.value.bufferProfile).cacheMs
        player.hdrMode = store.user.value.hdrMode
        player.videoLayout = store.user.value.videoLayout
    }

    /** Einstellung ändern und sofort übernehmen (die DNA Engine braucht dafür nie einen Neustart). */
    fun updateSettings(transform: (de.ayutv.desktop.data.UserData) -> de.ayutv.desktop.data.UserData) {
        store.updateUser(transform)
        val user = store.user.value
        player.audioDelayMs = user.audioDelayMs
        player.equalizerBands = de.ayutv.desktop.player.PlayerOptions.equalizer(user)
        if (!user.matchFrameRate) scope.launch(Dispatchers.IO) { de.ayutv.desktop.player.DisplayRate.restore() }
        player.reconfigure(de.ayutv.desktop.player.PlayerOptions.engine(user))
    }

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_000)
            message = null
        }
    }

    // ---------------------------------------------------------------- Schlaf-Timer

    /** Zeitpunkt, an dem die Wiedergabe endet (0 = aus). */
    var sleepAt by mutableStateOf(0L); private set
    /** Im TV-Modus danach auch den PC in den Energiesparmodus legen. */
    var sleepPc by mutableStateOf(false); private set
    private var sleepJob: Job? = null

    /** Schlaf-Timer: um [at] anhalten (0 = aus); mit [pc] danach den PC schlafen legen. Eine Minute vorher kommt ein Hinweis. */
    fun setSleepTimer(at: Long, pc: Boolean = false) {
        sleepJob?.cancel()
        sleepJob = null
        sleepAt = at.coerceAtLeast(0)
        sleepPc = pc && at > 0
        if (at <= 0) {
            toast("Schlaf-Timer aus")
            return
        }
        toast("Schlaf-Timer: Ende um ${clockText(at)}" + if (sleepPc) ", danach schläft der PC" else "")
        sleepJob = scope.launch {
            var warned = false
            while (true) {
                val left = sleepAt - System.currentTimeMillis()
                if (left <= 0) break
                if (!warned && left <= 60_000) {
                    warned = true
                    toast("Schlaf-Timer: noch 1 Minute (☰ → Schlaf-Timer zum Verlängern)")
                }
                delay(minOf(left, 5_000))
            }
            val pcToo = sleepPc
            sleepAt = 0
            sleepPc = false
            sleepJob = null
            stopPlayback()
            if (pcToo) de.ayutv.desktop.TvMode.sleep()
        }
    }

    // ---------------------------------------------------------------- Wiedergabe

    /** Vorher geschauter Sender (◀ bzw. „Letzter Sender“ springt zurück). */
    var previousChannel by mutableStateOf<Channel?>(null); private set
    /** Was der Player gerade versucht (Neu verbinden, Ausweichen); null = nichts. */
    var playerStatus by mutableStateOf<String?>(null); private set
    /** Fassungen, die für den laufenden Sender schon versucht wurden. */
    private var triedVariants = mutableSetOf<String>()
    private var watchdog: Job? = null

    fun play(playback: Playback) {
        // Vorschau und Multiview vorher beenden: Viele Konten erlauben nur eine Verbindung gleichzeitig.
        stopPreview()
        multiview?.let { closeMultiview(null) }
        stopAligning()
        val before = (playing as? Playback.Live)?.channel
        // Ton nur abgreifen, wenn Untertitel ausgerichtet werden sollen (sonst gibt VLC den Ton selbst aus).
        player.tapAudio = false
        playerStatus = null
        liveDelayMs = 0
        pausedAt = 0
        if (playback is Playback.Live) {
            if (before != null && before.key != playback.channel.key) previousChannel = before
            triedVariants = mutableSetOf(playback.channel.key)
        }
        lastPlaybackAt = System.currentTimeMillis()
        (playing as? Playback.Live)?.let { previous ->
            player.streamInfo()?.let { info -> if (info.height > 0) de.ayutv.data.parse.ChannelQuality.recordPlayback(previous.channel.key, info.height, info.startupMs) ; de.ayutv.data.parse.ChannelQuality.recordBitrate(previous.channel.key, info.inputKbps) }
        }
        if (playing?.let(::keyOf) != keyOf(playback)) {
            loadedSubtitles.clear()
            shownSubtitle = null
            subtitleCues = emptyList()
            subtitleDelayMs = 0
            subtitleVariant = 0
        }
        playing = playback
        // Läuft es auf einem Fernseher: den neuen Sender bzw. Film direkt dorthin, Windows spielt nicht mit
        // (viele Konten erlauben nur eine Verbindung).
        castTarget?.let { tv ->
            watchdog?.cancel()
            player.stop()
            sendToTv(tv, playback, switching = true)
            return
        }
        start(playback)
        watch(playback)
        matchFrameRate(keyOf(playback))
    }

    // ---------------------------------------------------------------- Auf Fernseher abspielen (LG/DLNA, Fire TV)

    val tvCast = de.ayutv.desktop.remote.TvCast(sync.http)
    /** Fernseher, auf dem gerade gespielt wird; Windows ist dann die Fernbedienung. */
    var castTarget by mutableStateOf<de.ayutv.desktop.remote.CastTarget?>(null); private set
    /** Gefundene Fernseher (Auswahl offen); null = keine Auswahl. */
    var castChoices by mutableStateOf<List<de.ayutv.desktop.remote.CastTarget>?>(null)
    /** Was gerade passiert („Suche …“, „Schalte um auf …“, „direkt, volle Qualität“). */
    var castStatus by mutableStateOf<String?>(null); private set
    var castVolume by mutableStateOf<Int?>(null); private set
    private var castRelay: de.ayutv.data.net.DlnaRelay? = null
    /** Wie der Stream zum Fernseher kommt (gemerkt, damit das Umschalten schnell geht). */
    private var castViaRelay: Boolean? = null
    private var castJob: Job? = null

    private fun tvId(target: de.ayutv.desktop.remote.CastTarget) = when (target) {
        is de.ayutv.desktop.remote.CastTarget.Dlna -> "dlna:${target.renderer.host}|${target.renderer.name}"
        is de.ayutv.desktop.remote.CastTarget.FireTv -> "firetv"
    }

    /**
     * Fernseher suchen. Ist der zuletzt benutzte dabei, gleich dorthin (ohne Auswahl); mit [choose] immer zur Auswahl
     * („Anderer Fernseher“).
     */
    fun openCastPicker(choose: Boolean = false) {
        toast("Suche Fernseher im Netz …")
        scope.launch {
            val user = store.user.value
            val found = tvCast.discover(user.syncAddress, user.syncPin)
            val last = found.firstOrNull { tvId(it) == user.lastCastTv }
            if (!choose && last != null) {
                castTo(last)
                return@launch
            }
            if (found.isEmpty()) {
                toast("Kein Fernseher gefunden. LG: eingeschaltet, gleiches Netz, Einstellungen → Allgemein → Geräte → Teilen von Medien an. " +
                    "Fire TV: unter Einstellungen → Abgleich Adresse und PIN eintragen.")
            } else castChoices = found
        }
    }

    fun castTo(target: de.ayutv.desktop.remote.CastTarget) {
        castChoices = null
        val playback = playing ?: return
        // Wechsel auf einen anderen Fernseher: den bisherigen (DLNA) anhalten, ohne zurück auf den PC.
        castTarget?.takeIf { it != target }?.let { old ->
            if (old is de.ayutv.desktop.remote.CastTarget.Dlna) scope.launch { runCatching { de.ayutv.data.net.DlnaCast.stop(sync.http, old.renderer) } }
            castTarget = null
        }
        castViaRelay = null
        sendToTv(target, playback, switching = false)
    }

    private fun sendToTv(target: de.ayutv.desktop.remote.CastTarget, playback: Playback, switching: Boolean) {
        castJob?.cancel()
        castStatus = if (switching) "Schalte um auf ${playback.title} …" else "Starte auf ${target.name} …"
        castJob = scope.launch {
            castRelay?.close()
            castRelay = null
            val live = playback is Playback.Live
            val url = when (playback) {
                is Playback.Live -> playback.channel.url
                is Playback.Vod -> playback.url
            }
            val position = if (playback is Playback.Vod) player.positionMs.coerceAtLeast(playback.startMs) else 0L
            if (!switching) {
                savePosition()
                watchdog?.cancel()
                player.stop()
            }
            val error = when (target) {
                is de.ayutv.desktop.remote.CastTarget.FireTv -> tvCast.playOnFireTv(
                    target, live, url, playback.title, keyOf(playback), position, Sync.USER_AGENT,
                )
                is de.ayutv.desktop.remote.CastTarget.Dlna -> castDlna(target.renderer, url, playback.title, live)
            }
            if (error != null) {
                if (switching) {
                    // Beim Umschalten bleibt Windows die Fernbedienung: einfach einen anderen Sender wählen.
                    castStatus = "${playback.title}: $error"
                } else {
                    castStatus = null
                    toast("${target.name}: $error")
                    start(playback)
                    watch(playback)
                }
                return@launch
            }
            castTarget = target
            if (store.user.value.lastCastTv != tvId(target)) store.updateUser { it.copy(lastCastTv = tvId(target)) }
            castStatus = when {
                target is de.ayutv.desktop.remote.CastTarget.FireTv -> "läuft auf dem Fire TV"
                castRelay != null -> "über diesen PC weitergeleitet – DnA-TV offen lassen"
                else -> "direkt, volle Qualität"
            }
            if (!switching) toast("📺 Läuft auf ${target.name}")
        }
    }

    /**
     * DLNA (LG & Co.): erst direkt (der Fernseher holt den Stream selbst) und prüfen, ob er wirklich abspielt; sonst
     * über diesen PC weiterleiten (Proxy, Kennung der App, HLS). Liefert null bei Erfolg.
     */
    private suspend fun castDlna(renderer: de.ayutv.data.net.DlnaCast.Renderer, source: String, title: String, live: Boolean): String? {
        val http = sync.http
        var error: String? = null
        val host = runCatching { java.net.URI(source).host }.getOrNull()
        if (!de.ayutv.data.net.AppProxy.proxies(host) && castViaRelay != true) {
            for (url in if (live) de.ayutv.data.net.DlnaCast.liveUrls(source) else listOf(source)) {
                error = de.ayutv.data.net.DlnaCast.play(http, renderer, url, title, live)
                if (error != null) continue
                when (de.ayutv.data.net.DlnaCast.awaitPlaying(http, renderer, 12_000)) {
                    de.ayutv.data.net.DlnaCast.Playback.PLAYING, de.ayutv.data.net.DlnaCast.Playback.UNKNOWN -> {
                        castViaRelay = false
                        return null
                    }
                    de.ayutv.data.net.DlnaCast.Playback.FAILED -> {
                        error = "${renderer.name} kann den Stream nicht direkt abspielen"
                        break
                    }
                }
            }
        }
        runCatching { de.ayutv.data.net.DlnaCast.stop(http, renderer) }
        val local = withContext(Dispatchers.IO) { de.ayutv.data.net.DlnaCast.localAddressFor(renderer.host) }
            ?: return error ?: "PC ist nicht im selben Netz wie ${renderer.name}"
        val relay = runCatching { de.ayutv.data.net.DlnaRelay(http, source, Sync.USER_AGENT, live, local) }.getOrElse {
            return "Weiterleiten nicht möglich (${it.message})"
        }
        castStatus = "Leite über diesen PC zu ${renderer.name} weiter …"
        val relayError = de.ayutv.data.net.DlnaCast.play(http, renderer, relay.url, title, live, relay.mime)
        val result = if (relayError == null) de.ayutv.data.net.DlnaCast.awaitPlaying(http, renderer, 18_000) else null
        if (result == de.ayutv.data.net.DlnaCast.Playback.PLAYING ||
            (result == de.ayutv.data.net.DlnaCast.Playback.UNKNOWN && relay.bytes.get() > 0)
        ) {
            castRelay = relay
            castViaRelay = true
            return null
        }
        relay.close()
        runCatching { de.ayutv.data.net.DlnaCast.stop(http, renderer) }
        return relayError ?: relay.lastError?.let { "Weiterleiten: $it" } ?: error ?: "${renderer.name} spielt den Stream nicht ab"
    }

    /** Zurück auf den PC: Fernseher anhalten (DLNA) und hier weiterschauen. */
    fun stopCast(resumeHere: Boolean = true) {
        val target = castTarget ?: return
        castJob?.cancel()
        castTarget = null
        castStatus = null
        castVolume = null
        castViaRelay = null
        val relay = castRelay
        castRelay = null
        relay?.close()
        scope.launch {
            // Fire TV mit DnA-TV läuft weiter (wie vom Handy); ein DLNA-Fernseher hält an, damit die Verbindung frei wird.
            if (target is de.ayutv.desktop.remote.CastTarget.Dlna) runCatching { de.ayutv.data.net.DlnaCast.stop(sync.http, target.renderer) }
            if (resumeHere) playing?.let { p ->
                start(p)
                watch(p)
            }
        }
    }

    fun castVolumeStep(delta: Int) {
        val target = castTarget as? de.ayutv.desktop.remote.CastTarget.Dlna ?: return
        scope.launch { de.ayutv.data.net.DlnaCast.changeVolume(sync.http, target.renderer, delta)?.let { castVolume = it } }
    }

    fun castPlayPause() {
        val target = castTarget as? de.ayutv.desktop.remote.CastTarget.Dlna ?: return
        scope.launch {
            val state = de.ayutv.data.net.DlnaCast.state(sync.http, target.renderer)?.uppercase()
            if (state == "PLAYING") de.ayutv.data.net.DlnaCast.pause(sync.http, target.renderer) else de.ayutv.data.net.DlnaCast.resume(sync.http, target.renderer)
        }
    }

    private fun keyOf(p: Playback) = when (p) {
        is Playback.Live -> p.channel.key
        is Playback.Vod -> p.key
    }

    private var rateJob: Job? = null

    /**
     * Bildwiederholrate des Bildschirms an das Video anpassen (Einstellung, wie auf dem Fire TV): bekannte Rate vom
     * letzten Mal gleich beim Laden, sonst sobald VLC sie kennt; je Sender bzw. Film gemerkt.
     */
    private fun matchFrameRate(key: String) {
        rateJob?.cancel()
        val user = store.user.value
        if (!user.matchFrameRate || !de.ayutv.desktop.player.DisplayRate.supported) return
        rateJob = scope.launch {
            user.frameRates[key]?.let { known ->
                withContext(Dispatchers.IO) { de.ayutv.desktop.player.DisplayRate.match(known) }?.let { toast("Bildschirm auf $it Hz") }
            }
            repeat(30) {
                delay(500)
                val fps = player.streamInfo()?.fps ?: 0.0
                if (fps <= 0) return@repeat
                withContext(Dispatchers.IO) { de.ayutv.desktop.player.DisplayRate.match(fps) }?.let { toast("Bildschirm auf $it Hz") }
                if (store.user.value.frameRates[key] != fps) store.updateUser { u ->
                    val rates = u.frameRates - key + (key to fps)
                    u.copy(frameRates = if (rates.size > 3_000) rates.entries.drop(rates.size - 3_000).associate { it.key to it.value } else rates)
                }
                return@launch
            }
        }
    }

    // ---------------------------------------------------------------- Multiview und Vorschau

    /** Multiview offen (bis zu vier Sender gleichzeitig); null = nicht. */
    var multiview by mutableStateOf<Multiview?>(null); private set

    /** Multiview aus dem laufenden Sender öffnen: der kommt ins erste Feld (mit Ton). */
    fun openMultiview() {
        val live = playing as? Playback.Live ?: return
        stopPlayback()
        val mv = Multiview(live.list)
        multiview = mv
        setTile(0, live.channel)
    }

    fun setTile(index: Int, channel: Channel) {
        val mv = multiview ?: return
        val tile = mv.tiles[index]
        val p = tile.player ?: VideoPlayer().also { created ->
            created.networkCacheMs = BufferProfile.of(store.user.value.bufferProfile).cacheMs
            created.hdrMode = store.user.value.hdrMode
            created.videoLayout = store.user.value.videoLayout
            tile.player = created
        }
        p.muted = mv.audio != index
        tile.channel = channel
        p.play(channel.url, Sync.USER_AGENT, 0)
        lastPlaybackAt = System.currentTimeMillis()
    }

    fun clearTile(index: Int) {
        val mv = multiview ?: return
        val tile = mv.tiles[index]
        tile.player?.stop()
        tile.channel = null
        if (mv.audio == index) mv.tiles.indexOfFirst { it.channel != null }.takeIf { it >= 0 }?.let { setMultiviewAudio(it) }
    }

    fun zapTile(index: Int, step: Int) {
        val mv = multiview ?: return
        val from = mv.tiles[index].channel ?: return
        val list = mv.list.takeIf { it.size > 1 } ?: return
        val at = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        setTile(index, list[(at + step).mod(list.size)])
    }

    /** Ton zu Feld [index]; die anderen stumm. */
    fun setMultiviewAudio(index: Int) {
        val mv = multiview ?: return
        if (mv.tiles[index].channel == null) return
        mv.audio = index
        mv.tiles.forEachIndexed { i, t -> t.player?.muted = i != index }
    }

    /** Multiview schließen; mit [fullscreen] diesen Sender danach groß weiterschauen. */
    fun closeMultiview(fullscreen: Channel?) {
        val mv = multiview ?: return
        multiview = null
        // Erst alle Verbindungen schließen (Kontolimit), dann die Player im Hintergrund freigeben.
        mv.tiles.forEach { it.player?.stop() }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { mv.tiles.forEach { t -> t.player?.let { p -> runCatching { p.release() } } } }
        if (fullscreen != null) play(Playback.Live(fullscreen, mv.list))
    }

    /** Mini-Vorschau in der Senderliste (stumm, kleiner Puffer); erst bei Bedarf angelegt. */
    var preview by mutableStateOf<VideoPlayer?>(null); private set
    var previewKey by mutableStateOf<String?>(null); private set

    fun showPreview(channel: Channel?) {
        if (channel == null || playing != null || multiview != null || !store.user.value.livePreview) return stopPreview()
        if (previewKey == channel.key) return
        val p = preview ?: VideoPlayer().also { created ->
            created.muted = true
            created.networkCacheMs = 1_200
            created.videoLayout = store.user.value.videoLayout
            preview = created
        }
        previewKey = channel.key
        p.play(channel.url, Sync.USER_AGENT, 0)
    }

    fun stopPreview() {
        if (previewKey == null) return
        previewKey = null
        preview?.stop()
    }

    /** Laufende Wiedergabe neu starten (z. B. nach Wechsel der Videoausgabe), Filme an derselben Stelle. */
    fun restartPlayback() {
        val current = playing ?: return
        start(current, atMs = (current as? Playback.Vod)?.let { player.positionMs })
        watch(current)
    }

    private fun start(playback: Playback, atMs: Long? = null) = when (playback) {
        is Playback.Live -> {
            store.rememberWatched(playback.channel.key)
            player.play(playback.channel.url, Sync.USER_AGENT, 0)
        }
        is Playback.Vod -> player.play(playback.url, Sync.USER_AGENT, atMs ?: playback.startMs)
    }

    /**
     * Watchdog wie auf dem Fire TV: Fehler oder stehendes Bild (Dauer-Puffern) → neu verbinden, bis zu drei Mal mit
     * wachsender Pause; danach bei Live-TV auf die nächste Fassung desselben Senders ausweichen („Sendername“ → „Sendername“).
     */
    private fun watch(playback: Playback) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var attempts = 0
            var healthySince = System.currentTimeMillis()
            while (playing === playback) {
                delay(1_000)
                if (playing !== playback) break
                val now = System.currentTimeMillis()
                // Radiosender haben kein Bild: die zählen nicht als eingefroren, solange der Ton läuft.
                val stalled = !player.paused && now - player.lastFrameAt > STALL_MS && !(player.playing && !player.hasVideo)
                val failed = player.error != null
                if (!failed && !stalled) {
                    if (player.playing && now - healthySince > HEALTHY_MS) attempts = 0
                    if (player.playing && playerStatus != null && now - player.lastFrameAt < 1_000) playerStatus = null
                    continue
                }
                healthySince = now
                de.ayutv.desktop.Log.warn("Watchdog: ${playback.title}: ${player.error ?: "Bild steht"} (Versuch ${attempts + 1})")
                if (attempts < MAX_RETRIES) {
                    attempts++
                    playerStatus = (if (failed) "Stream unterbrochen" else "Bild steht") + " – verbinde neu ($attempts/$MAX_RETRIES) …"
                    delay(500L shl (attempts - 1))
                    if (playing !== playback) break
                    start(playback, atMs = (playback as? Playback.Vod)?.let { player.positionMs })
                    continue
                }
                val live = playback as? Playback.Live
                val next = live?.let { l -> store.alternatives(l.channel).firstOrNull { it.key !in triedVariants } }
                live?.let { de.ayutv.data.parse.ChannelQuality.recordFailure(it.channel.key) }
                if (live != null && next != null) {
                    de.ayutv.desktop.Log.info("Ausweichen: ${live.channel.name} → ${next.name}")
                    triedVariants.add(next.key)
                    playerStatus = "Weiche aus auf „${next.name}“ …"
                    // In der Liste bleibt der ursprüngliche Sender stehen (Zappen geht von dort weiter).
                    val switched = Playback.Live(next, live.list.map { if (it.key == live.channel.key) next else it })
                    playing = switched
                    start(switched)
                    watch(switched)
                    return@launch
                }
                playerStatus = null
                if (player.error == null) player.stopWithError("Der Stream liefert kein Bild. Später noch einmal versuchen oder einen anderen Sender wählen.")
                break
            }
        }
    }

    /** Ziel beim schnellen Umschalten (wird erst nach kurzer Pause gestartet). */
    var zapTarget by mutableStateOf<Channel?>(null); private set
    private var zapJob: Job? = null
    private var lastZapAt = 0L

    /** Umschalten in der laufenden Liste; schnell hintereinander gedrückt wird nur der letzte Sender gestartet. */
    fun zap(step: Int) {
        val live = playing as? Playback.Live ?: return
        val list = live.list
        if (list.size < 2) return
        val from = zapTarget ?: live.channel
        val index = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        val target = list[(index + step).mod(list.size)]
        val now = System.currentTimeMillis()
        val burst = now - lastZapAt < ZAP_BURST_MS
        lastZapAt = now
        if (!burst && zapJob == null) {
            play(live.copy(channel = target))
            return
        }
        zapTarget = target
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            zapJob = null
            zapTarget = null
            val current = playing as? Playback.Live ?: return@launch
            play(current.copy(channel = target))
        }
    }

    /** Spiele der gefolgten Vereine in den nächsten 7 Tagen als Erinnerungen eintragen (wie auf dem Fire TV). */
    private suspend fun planTeamReminders(now: Long) {
        val teams = store.user.value.followedTeams
        val events = withContext(Dispatchers.Default) {
            de.ayutv.desktop.data.SportEvents.of(store.listedChannels(), store.guide.value, store.user.value.favorites.toSet(), hours = 7 * 24, now = now)
        }
        val fresh = events.filter { e ->
            val pairing = e.pairing ?: return@filter false
            e.programme.start > now && teams.any { de.ayutv.data.parse.Teams.plays(it, pairing, e.programme.title + " " + e.programme.subtitle.orEmpty()) }
        }.map { de.ayutv.desktop.data.Reminder(it.channel.key, it.channel.name, it.headline, it.programme.start, team = true) }
        if (fresh.isEmpty()) return
        store.updateUser { u ->
            val known = u.reminders.map { it.channelKey to it.start }.toSet()
            u.copy(reminders = u.reminders + fresh.filter { (it.channelKey to it.start) !in known })
        }
    }

    /** Mit dem Fire TV abgleichen (Favoriten, Verlauf). */
    suspend fun syncDevices(quiet: Boolean = false) {
        val u = store.user.value
        deviceSyncStatus = "Gleiche ab …"
        runCatching { deviceSync.withFireTv(u.syncAddress, u.syncPin) }
            .onSuccess { r ->
                deviceSyncStatus = "${clock(System.currentTimeMillis())}: ${r.text}"
                if (!quiet || r.favorites + r.history > 0) toast("Abgleich mit dem Fire TV: ${r.text}")
            }
            .onFailure {
                de.ayutv.desktop.Log.warn("Abgleich mit dem Fire TV", it)
                deviceSyncStatus = "Fehlgeschlagen: ${it.message}"
                if (!quiet) toast("Abgleich fehlgeschlagen: ${it.message}")
            }
    }

    // ---------------------------------------------------------------- Aufnahmen

    /** Sendung aufnehmen (geplant oder laufend). Läuft sie schon, beginnt die Aufnahme sofort. */
    fun record(channel: Channel, programme: de.ayutv.desktop.data.Programme) {
        val r = de.ayutv.desktop.data.Recording(
            id = System.currentTimeMillis(),
            channelKey = channel.key, channelName = channel.name, title = programme.title,
            start = programme.start, stop = programme.stop,
            file = recorder.fileFor(channel.name, programme.title, programme.start).absolutePath,
        )
        store.updateUser { it.copy(recordings = it.recordings + r) }
        toast(if (programme.start <= System.currentTimeMillis()) "⏺ Aufnahme läuft: ${programme.title}" else "⏺ Aufnahme geplant: ${programme.title} um ${clock(programme.start)}")
        scope.launch { withContext(Dispatchers.IO) { runRecordings(System.currentTimeMillis()) } }
    }

    /** Aufnahme beenden bzw. Planung löschen; [deleteFile] löscht auch die Datei. */
    fun removeRecording(r: de.ayutv.desktop.data.Recording, deleteFile: Boolean) {
        recorder.stop(r.id)
        if (!deleteFile && r.state == de.ayutv.desktop.data.Recording.RUNNING) {
            // Laufende Aufnahme beenden: was bisher aufgenommen ist, bleibt.
            store.updateUser { u -> u.copy(recordings = u.recordings.map { if (it.id == r.id) it.copy(state = de.ayutv.desktop.data.Recording.DONE, stop = System.currentTimeMillis()) else it }) }
            return
        }
        if (deleteFile) java.io.File(r.file).delete()
        store.updateUser { u -> u.copy(recordings = u.recordings.filterNot { it.id == r.id }) }
    }

    fun playRecording(r: de.ayutv.desktop.data.Recording) {
        val file = java.io.File(r.file)
        if (!file.isFile) return toast("Datei fehlt: ${file.name}")
        play(Playback.Vod("rec:${r.id}", "${r.channelName}: ${r.title}", file.absolutePath, store.user.value.positions["rec:${r.id}"] ?: 0))
    }

    /** Fällige Aufnahmen starten (1 Minute vorher) und beenden (2 Minuten nach Ende). */
    private fun runRecordings(now: Long) {
        val list = store.user.value.recordings
        if (list.isEmpty()) return
        val changes = HashMap<Long, String>()
        for (r in list) {
            when (r.state) {
                de.ayutv.desktop.data.Recording.PLANNED -> if (r.start - 60_000 <= now && now < r.stop) {
                    val channel = store.channel(r.channelKey)
                    val ok = channel != null && recorder.start(r, channel.url, Sync.USER_AGENT)
                    changes[r.id] = if (ok) de.ayutv.desktop.data.Recording.RUNNING else de.ayutv.desktop.data.Recording.FAILED
                    if (!ok) toast("Aufnahme „${r.title}“ ließ sich nicht starten")
                } else if (now >= r.stop) changes[r.id] = de.ayutv.desktop.data.Recording.FAILED
                de.ayutv.desktop.data.Recording.RUNNING -> {
                    if (now > r.stop + 120_000) {
                        recorder.stop(r.id)
                        val size = java.io.File(r.file).length()
                        changes[r.id] = if (size > 100_000) de.ayutv.desktop.data.Recording.DONE else de.ayutv.desktop.data.Recording.FAILED
                        toast(if (size > 100_000) "⏺ Aufnahme fertig: ${r.title}" else "Aufnahme „${r.title}“ ist leer geblieben")
                    } else if (!recorder.isRunning(r.id)) {
                        // Nach einem Neustart von DnA-TV weiter aufnehmen (in eine neue Datei).
                        val channel = store.channel(r.channelKey)
                        if (channel != null) recorder.start(r.copy(file = recorder.fileFor(r.channelName, r.title, now).absolutePath), channel.url, Sync.USER_AGENT)
                    }
                }
            }
        }
        if (changes.isNotEmpty()) store.updateUser { u -> u.copy(recordings = u.recordings.map { r -> changes[r.id]?.let { r.copy(state = it) } ?: r }) }
    }

    /** Fällige Erinnerung annehmen: zum Sender umschalten. */
    fun acceptReminder(reminder: de.ayutv.desktop.data.Reminder) {
        dueReminder = null
        val channel = store.channel(reminder.channelKey) ?: return toast("Sender „${reminder.channelName}“ gibt es nicht mehr")
        play(Playback.Live(channel, store.allVisible().takeIf { list -> list.any { it.key == channel.key } } ?: listOf(channel)))
    }

    /** Sendung aus dem Archiv (Catch-up) abspielen; false = Sender hat kein Archiv dafür. */
    fun playArchive(channel: Channel, programme: de.ayutv.desktop.data.Programme): Boolean {
        val url = store.archiveUrl(channel, programme.start, programme.stop) ?: return false
        play(Playback.Vod("archive:${channel.key}:${programme.start}", "${channel.name}: ${programme.title}", url, archive = channel))
        return true
    }

    /** Zeitversetzt (Live angehalten und weiter): so weit liegt das Bild hinter dem Sender. */
    var liveDelayMs by mutableStateOf(0L); private set
    private var pausedAt = 0L

    /** Live anhalten/fortsetzen (VLC puffert dabei weiter, danach läuft das Bild zeitversetzt). */
    fun toggleLivePause() {
        if (player.paused) {
            if (pausedAt > 0) liveDelayMs += System.currentTimeMillis() - pausedAt
            pausedAt = 0
            player.resume()
        } else {
            pausedAt = System.currentTimeMillis()
            player.pause()
        }
    }

    /** Zurück zum Live-Bild (nach Pause) bzw. bei Archiv zum laufenden Sender. */
    fun backToLive(): Boolean {
        val live = playing as? Playback.Live
        if (live != null && (liveDelayMs > 0 || player.paused)) {
            play(live)
            return true
        }
        val archive = (playing as? Playback.Vod)?.archive ?: return false
        play(Playback.Live(archive, store.allVisible()))
        return true
    }

    /** Zum vorher geschauten Sender (und wieder zurück). */
    fun backZap(): Boolean {
        val live = playing as? Playback.Live ?: return false
        val previous = previousChannel?.takeIf { it.key != live.channel.key } ?: return false
        val list = if (live.list.any { it.key == previous.key }) live.list else store.allVisible()
        play(Playback.Live(previous, list))
        toast("◀ Zurück: ${previous.name}")
        return true
    }

    /** Eingetippte Sendernummer (Zifferntasten im Bild). */
    var typedNumber by mutableStateOf("")
        private set
    private var numberJob: Job? = null

    fun typeDigit(digit: Int) {
        val live = playing as? Playback.Live ?: return
        typedNumber = (typedNumber + digit).takeLast(4)
        val typed = typedNumber
        val user = store.user.value
        // Eigene Sendernummern zuerst (auch für Sender außerhalb der aktuellen Liste), dann die der Liste.
        val custom = user.channelNumbers.mapNotNull { (key, n) -> (live.list.firstOrNull { it.key == key } ?: store.channel(key))?.let { n to it } }
        val numbers = custom + live.list.mapIndexed { i, c -> user.numberOf(c, i + 1) to c }
        val canGrow = numbers.any { (n, _) -> n.toString().let { it.length > typed.length && it.startsWith(typed) } }
        numberJob?.cancel()
        numberJob = scope.launch {
            if (canGrow) delay(1_500)
            val wanted = typed.toIntOrNull()
            typedNumber = ""
            val target = numbers.firstOrNull { it.first == wanted }?.second
            if (target == null) toast("Kein Sender mit der Nummer $typed") else if ((playing as? Playback.Live)?.channel?.key != target.key) {
                play(live.copy(channel = target))
            }
        }
    }

    fun stopPlayback() {
        // Player verlassen: Übertragung beenden; über diesen PC weitergeleitet hört sie damit auf, direkt läuft der
        // Fernseher weiter.
        if (castTarget != null) {
            if (castRelay != null) stopCast(resumeHere = false) else {
                castJob?.cancel()
                castTarget = null
                castStatus = null
                castVolume = null
            }
        }
        lastPlaybackAt = System.currentTimeMillis()
        (playing as? Playback.Live)?.let { live ->
            // Beim Schauen gemessen: Auflösung und Startzeit fließen in die Wahl der besten Fassung ein.
            player.streamInfo()?.let { info -> if (info.height > 0) de.ayutv.data.parse.ChannelQuality.recordPlayback(live.channel.key, info.height, info.startupMs) ; de.ayutv.data.parse.ChannelQuality.recordBitrate(live.channel.key, info.inputKbps) }
            channelCheck.save()
        }
        stopAligning()
        savePosition()
        watchdog?.cancel()
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        playerStatus = null
        player.stop()
        playing = null
        rateJob?.cancel()
        scope.launch(Dispatchers.IO) { de.ayutv.desktop.player.DisplayRate.restore() }
    }

    /** Taste Home: wie auf dem Fire TV zur Startseite (Wiedergabe und offene Dialoge schließen). */
    fun goHome() {
        multiview?.let { closeMultiview(null) }
        stopPreview()
        if (playing != null) stopPlayback()
        openSeries = null
        // Offene Dialoge und Unterseiten schließen (begrenzt: ein Handler bleibt bis zum nächsten Zeichnen aktiv).
        repeat(minOf(backStack.size, 8)) {
            for (i in backStack.indices.reversed()) if (backStack[i]()) break
        }
        screen = Screen.HOME
    }

    /** Wiedergabestand des laufenden Films merken (fast am Ende = gesehen, beim nächsten Mal von vorn). */
    fun savePosition() {
        val vod = playing as? Playback.Vod ?: return
        if (vod.archive != null) return
        val position = player.positionMs
        val duration = player.durationMs
        if (position < 10_000) return
        store.savePosition(vod.key, if (duration > 0 && position > duration - 60_000) 0 else position, duration)
    }

    /** Stand der Ausrichtung am Ton (für die Leiste im Player; null = keine Untertitel geladen). */
    var subtitleSync by mutableStateOf<String?>(null); private set
    private var alignJob: Job? = null

    private fun stopAligning() {
        alignJob?.cancel()
        alignJob = null
        subtitleSync = null
    }

    /**
     * Untertitel in der eingestellten Sprache suchen (OpenSubtitles, sonst DeepL), einblenden und am Ton ausrichten:
     * Wie auf dem Fire TV werden die Sprechpausen des Films mit den Einblendungen verglichen; nach rund einer Minute
     * Gespräch sitzt der Versatz (auch bei anderer Bildrate). Die Ausrichtung wird je Film gemerkt.
     */
    fun loadSubtitles(vod: Playback.Vod, language: String = store.user.value.subtitleLanguage.ifBlank { "de" }, variant: Int = 0) = scope.launch {
        val user = store.user.value
        subtitleBusy = "Untertitel ${de.ayutv.desktop.data.languageName(language)} werden gesucht …"
        runCatching {
            subtitles.find(
                vod.key, vod.title, vod.url, language,
                de.ayutv.desktop.player.Subtitles.Account(user.openSubtitlesKey, user.openSubtitlesUser, user.openSubtitlesPassword),
                user.deeplKey, variant,
            ) { subtitleBusy = it }
        }.onSuccess { found ->
            subtitleBusy = null
            if ((playing as? Playback.Vod)?.key != vod.key) return@onSuccess
            subtitleVariant = variant
            val text = withContext(Dispatchers.IO) { found.file.readText() }
            val cues = SubtitleFiles.cues(text)
            val known = user.subtitleTimings[vod.key]?.takeIf { variant == 0 }?.let { SubtitleTiming(it.ratio, it.offsetMs) }
            if (known != null) {
                showSubtitles(vod, language, found.file, text, known)
                toast("Untertitel eingeblendet (${found.source}), wie beim letzten Mal ausgerichtet.")
                subtitleSync = "Ausgerichtet (gemerkt)"
                return@onSuccess
            }
            showFile(language, found.file, text)
            toast("Untertitel eingeblendet (${found.source}). Sie richten sich gleich am Ton aus.")
            align(vod, language, found.file, text, cues)
        }.onFailure {
            subtitleBusy = null
            toast(it.message ?: "Keine Untertitel")
        }
    }

    /** Untertitel-Suche läuft (Text für die Anzeige im Player). */
    var subtitleBusy by mutableStateOf<String?>(null); private set
    /** Geladene Untertitel-Dateien des laufenden Films (Sprache → Datei, wie eingeblendet). */
    val loadedSubtitles = androidx.compose.runtime.mutableStateMapOf<String, java.io.File>()
    /** Sprache der gerade eingeblendeten Datei (null = keine Datei, sondern Spur des Films oder aus). */
    var shownSubtitle by mutableStateOf<String?>(null); private set
    private var subtitleVariant = 0
    /** Einblendungen der gezeigten Datei (Beginn in ms, Text) für „Untertitel-Zeit einstellen“. */
    var subtitleCues by mutableStateOf<List<Pair<Long, String>>>(emptyList()); private set
    /** Von Hand eingestellte Verschiebung der Untertitel (ms, positiv = später). */
    var subtitleDelayMs by mutableStateOf(0L); private set

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms.coerceIn(-600_000, 600_000)
        player.subtitleDelay(subtitleDelayMs)
    }

    private suspend fun showFile(language: String, file: java.io.File, text: String? = null) {
        val content = text ?: withContext(Dispatchers.IO) { file.readText() }
        player.addSubtitleFile(file)
        player.subtitleDelay(subtitleDelayMs)
        loadedSubtitles[language] = file
        shownSubtitle = language
        subtitleCues = withContext(Dispatchers.Default) {
            de.ayutv.data.parse.Srt.parse(content).mapNotNull { c ->
                SubtitleFiles.parseMs(c.start)?.let { it to c.text.replace(Regex("<[^>]+>"), "").replace('\n', ' ') }
            }
        }
    }

    /** Schon geladene Sprache wieder einblenden. */
    fun showLoaded(language: String) = scope.launch {
        val file = loadedSubtitles[language] ?: return@launch
        showFile(language, file)
    }

    /** Untertitel aus (auch eine geladene Datei). */
    fun subtitlesOff() {
        player.selectSubtitle(-1)
        shownSubtitle = null
    }

    /** Spur des Films wählen (statt einer geladenen Datei). */
    fun selectSubtitleTrack(id: Int) {
        player.selectSubtitle(id)
        shownSubtitle = null
    }

    /** Die geladene Datei passt nicht: nächstbeste Datei derselben Sprache. */
    fun otherSubtitleFile(vod: Playback.Vod) {
        val language = shownSubtitle ?: return
        loadSubtitles(vod, language, subtitleVariant + 1)
    }

    private suspend fun showSubtitles(vod: Playback.Vod, language: String, file: java.io.File, text: String, timing: SubtitleTiming) {
        val aligned = java.io.File(file.parentFile, file.nameWithoutExtension + ".sync.srt")
        val shifted = SubtitleFiles.shifted(text, timing)
        withContext(Dispatchers.IO) { aligned.writeText(shifted) }
        if ((playing as? Playback.Vod)?.key == vod.key) showFile(language, aligned, shifted)
    }

    private fun align(vod: Playback.Vod, language: String, file: java.io.File, text: String, cues: List<Pair<Long, Long>>) {
        alignJob?.cancel()
        alignJob = scope.launch {
            if (cues.size < 20) {
                subtitleSync = "Zu wenige Einblendungen zum Ausrichten"
                return@launch
            }
            // Für die Auswertung den Ton selbst ausgeben: kurz an derselben Stelle neu starten.
            if (!player.tapAudio) {
                player.tapAudio = true
                val position = player.positionMs
                player.play(vod.url, Sync.USER_AGENT, position)
                delay(1_500)
                showFile(language, file, text)
            }
            var applied: SubtitleTiming? = null
            var lastHeard = -1
            while (true) {
                // Bis zum ersten Treffer alle 5 s prüfen, danach alle 10 s nach.
                delay(if (applied == null) 5_000 else 10_000)
                if (!player.playing) continue
                val heard = player.speech.seconds()
                if (heard < SpeechSync.MIN_SECONDS) {
                    if (applied == null) subtitleSync = "Hört zu: $heard von ${SpeechSync.MIN_SECONDS} Sekunden Ton"
                    continue
                }
                if (heard == lastHeard) continue
                lastHeard = heard
                val position = player.positionMs
                val fit = withContext(Dispatchers.Default) {
                    SpeechSync.estimate(player.speech.signal(position - WINDOW_MS, position + 5_000), cues)
                }
                if (fit == null) {
                    if (applied == null) subtitleSync = "${heard / 60} min Ton geprüft, noch kein eindeutiger Takt – prüft weiter"
                    continue
                }
                val timing = fit.timing
                // Nur neu einblenden, wenn sich an der aktuellen Stelle wirklich etwas ändert.
                val now = applied
                if (now != null && kotlin.math.abs(timing.toVideo(position) - now.toVideo(position)) < 250) continue
                showSubtitles(vod, language, file, text, timing)
                applied = timing
                store.updateUser { it.copy(subtitleTimings = it.subtitleTimings + (vod.key to TimingMark(timing.ratio, timing.offsetMs))) }
                val shift = "%+.1f s".format(java.util.Locale.GERMAN, timing.offsetMs / 1000.0) + if (timing.ratio != 1.0) ", Bildrate angepasst" else ""
                subtitleSync = "Am Ton ausgerichtet ($shift)"
                toast("Untertitel am Ton ausgerichtet ($shift)")
            }
        }
    }

    fun back(): Boolean {
        for (i in backStack.indices.reversed()) if (backStack[i]()) return true
        if (playing != null) {
            stopPlayback(); return true
        }
        return false
    }

    /** Zurück auf oberster Ebene: zur Startseite (wie die Zurück-Taste auf dem Fire TV-Startbildschirm). */
    fun backToHome(): Boolean {
        if (back()) return true
        if (screen != Screen.HOME && store.playlists.value.isNotEmpty()) {
            screen = Screen.HOME
            return true
        }
        return false
    }

    internal fun pushBack(handler: () -> Boolean) = backStack.add(handler)
    internal fun popBack(handler: () -> Boolean) = backStack.remove(handler)

    fun syncNow() = scope.launch {
        runCatching { sync.syncAll() }.onFailure { toast("Laden fehlgeschlagen: ${it.message}") }
        applyProxy()
    }

    private companion object {
        /** So viel gehörter Ton (rückwärts ab jetzt) zählt für die Ausrichtung. */
        const val WINDOW_MS = 15 * 60_000L
        const val REFRESH_CHECK_MS = 15 * 60_000L
        /** So lange ohne neues Bild gilt ein Stream als eingefroren. */
        const val STALL_MS = 12_000L
        const val HEALTHY_MS = 30_000L
        const val MAX_RETRIES = 3
        const val ZAP_BURST_MS = 700L
        const val ZAP_SETTLE_MS = 450L
        const val REMINDER_AHEAD_MS = 60_000L
    }

    /** Installer der neuen Version starten und DnA-TV beenden (der Installer ersetzt die App). */
    fun installUpdate(release: de.ayutv.desktop.update.Updater.Release) = scope.launch {
        updater.install(release) {
            close()
            kotlin.system.exitProcess(0)
        }
    }

    fun close() {
        savePosition()
        de.ayutv.desktop.player.DisplayRate.restore()
        watchdog?.cancel()
        remote.stop()
        input.stop()
        recorder.release()
        channelCheck.save()
        channelCheck.release()
        multiview?.tiles?.forEach { t -> t.player?.let { runCatching { it.release() } } }
        preview?.let { runCatching { it.release() } }
        player.release()
    }
}

/** Esc/Zurück abfangen, solange [enabled]. */
@Composable
fun BackHandler(app: AppState, enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val active by rememberUpdatedState(enabled)
    DisposableEffect(app) {
        val handler: () -> Boolean = { if (active) { current(); true } else false }
        app.pushBack(handler)
        onDispose { app.popBack(handler) }
    }
}

/** Uhrzeit „21:45“ für Hinweise. */
fun clockText(at: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()))

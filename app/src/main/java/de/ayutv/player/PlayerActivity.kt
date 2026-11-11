@file:OptIn(UnstableApi::class)

package de.ayutv.player

import de.ayutv.data.parse.headline
import de.ayutv.data.parse.sportHeadline
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import de.ayutv.AyuApp
import de.ayutv.data.AppSettings
import de.ayutv.data.AspectMode
import de.ayutv.data.AudioMode
import de.ayutv.data.PlayerMemory
import de.ayutv.data.SubtitleBackground
import de.ayutv.data.SubtitleColor
import de.ayutv.data.SubtitlePosition
import de.ayutv.data.AudioOutput
import de.ayutv.data.BufferProfile
import de.ayutv.data.Languages
import de.ayutv.data.record.Recording
import de.ayutv.data.LiveFormat
import de.ayutv.data.parse.Episodes
import de.ayutv.hideSystemBars
import de.ayutv.ui.components.FitToScreen
import de.ayutv.ui.components.hasTouchscreen
import de.ayutv.data.repo.ContentRepository
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.repo.SubtitleRepository
import de.ayutv.data.repo.Translators
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
import de.ayutv.ui.theme.AyuTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import de.ayutv.data.parse.pairing
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class Overlay { NONE, INFO, CHANNELS, CONTROLS, TRACKS }

class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as AyuApp).container }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frameRateMatcher: FrameRateMatcher
    private lateinit var settings: AppSettings
    private var headers: HeaderProvider? = null
    /** Beim Laden mitgelesene eingebettete Untertitel (für Übersetzung mit Vorlauf). */
    private var embedded: EmbeddedSubtitles? = null
    private var asyncDecodingActive = false
    /** Zähler des zuletzt beendeten Video-Decoders (für den Fehlerbericht). */
    private var lastVideoCounters: String? = null
    /** Nach einem Decoder-Fehler schon einmal mit schnellem Dekodieren versucht (nur einmal pro Player-Sitzung). */
    private var triedAsyncAfterDecoderError = false
    private var forcePcmActive = false
    private val rebufferTimes = ArrayDeque<Long>()
    private var bufferAdviceShown = false
    private var lastAudioHintFor: String? = null

    // Zustand, den die Compose-Oberfläche liest
    /**
     * DNA Player auf der eigenen DNA Engine ([de.ayutv.player.dna.engine.DnaEnginePlayer]) hinter der gemeinsamen
     * Media3-Schnittstelle (Bildfläche, Steuerung, Spuren, Untertitel-Anzeige).
     */
    var player by mutableStateOf<Player?>(null); private set
    private val dna: de.ayutv.player.dna.engine.DnaEnginePlayer? get() = player as? de.ayutv.player.dna.engine.DnaEnginePlayer

    /** Handy (Touch, kein Fernseher): eigene Steuerung der Bildwiederholrate. */
    val isPhoneDevice: Boolean by lazy { de.ayutv.data.DeviceInfo.isPhone(this) }

    /** DNA Engine, Live: Zeitversatz-Speicher auf dem Gerät (Aufzeichnung beim Anbieter, Engine liest über 127.0.0.1). */
    private var dnaTimeshift: de.ayutv.player.dna.engine.LiveTimeshift? = null
    /** Empfangszeit (elapsedRealtime) der Stelle, ab der die DNA Engine zuletzt im Zeitversatz-Speicher liest. */
    private var dnaShiftOpenedAt = 0L

    /** Kennung des gerade gewählten Streams (Film/Folge oder Sender). */
    private fun streamKey(): String? = vod?.key ?: playing?.key

    /** Gespieltes Bildformat, mit beiden Engines (die DNA Engine meldet ihre Spuren über die Spurliste). */
    private val Player.videoFormat: Format? get() = selectedFormat(C.TRACK_TYPE_VIDEO)

    /** Gespieltes Tonformat, mit beiden Engines. */
    private val Player.audioFormat: Format? get() = selectedFormat(C.TRACK_TYPE_AUDIO)

    private fun Player.selectedFormat(type: Int): Format? = currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    /** Gezeigte Bilder der DNA Engine (Bildrate, Ruckel-Erkennung). */
    private val Player.renderedFrames: Long? get() = (this as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.renderedFrames?.toLong()
    var isLive by mutableStateOf(true); private set
    var overlay by mutableStateOf(Overlay.NONE); private set
    var overlayToken by mutableIntStateOf(0); private set
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList()); private set
    /** Überschrift der Senderliste: Gruppe, Favoriten oder alle Sender. */
    var listTitle by mutableStateOf(""); private set
    var current by mutableStateOf<ChannelEntity?>(null); private set
    var nowNext by mutableStateOf<List<ProgrammeEntity>>(emptyList()); private set
    var nowPlayingMap by mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()); private set
    var numberInput by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    var buffering by mutableStateOf(true); private set
    /** Endgültiger Fehler: Bild schwarz statt des letzten Bildes vom vorigen Sender. */
    var fatalError by mutableStateOf(false); private set
    var showTech by mutableStateOf(false); private set
    /** Gepufferte Sekunden (nur aktualisiert, solange die Stream-Info sichtbar ist). */
    var bufferedMs by mutableLongStateOf(0L); private set
    /** Abbrüche der Verbindung zum Anbieter beim laufenden Sender, für die Technik-Anzeige. */
    var dropInfo by mutableStateOf(""); private set
    private var seenDrops = 0
    private var dropWarnedAt = 0L
    /** Live: Wiedergabe läuft gerade minimal langsamer, um wieder Vorrat aufzubauen. */
    var cushionActive by mutableStateOf(false); private set
    var stats by mutableStateOf(StreamStats()); private set
    var vodTitle by mutableStateOf(""); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var isFavorite by mutableStateOf(false); private set
    var tracks by mutableStateOf(Tracks.EMPTY); private set
    var textDisabled by mutableStateOf(false); private set
    var subtitleScale by mutableFloatStateOf(1f); private set
    private var subtitleStatusState by mutableStateOf<String?>(null)
    var subtitleStatus: String?
        get() = subtitleStatusState
        set(value) {
            subtitleStatusState = value
            if (value != null) {
                statusHistory += value
                subLog(value)
            }
        }
    /** Wiedergabefehler dieser Sitzung (Neustarts des Players), für Diagnose und Tests. */
    var playerErrors = 0; private set
    /** Alle Statusmeldungen dieser Sitzung (für Diagnose und Tests). */
    val statusHistory = mutableListOf<String>()
    val onlineSubtitlesAvailable: Boolean get() = !isLive && ::settings.isInitialized && settings.onlineSubtitlesEnabled

    private var vod: VodRequest? = null
    /** Zuletzt gesehener anderer Sender: ◀ springt dorthin zurück (wie die Zurück-Taste am Fernseher). */
    var previousChannel by mutableStateOf<ChannelEntity?>(null); private set
    /** Sender, den der Player gerade wirklich spielt ([current] zeigt beim schnellen Zappen schon das Ziel). */
    private var playing: ChannelEntity? = null
    private var retries = 0
    /** Langsame Wiederholungen nach einem Ausfall des Senders (siehe [SLOW_RETRY_SECONDS]). */
    private var slowRetries = 0
    private var triedHls = false
    private var zapStartedAt = 0L
    private var everReady = false
    /** Dieser Stream hat schon einmal nachgeladen: erst dann greift das Puffer-Polster. */
    private var rebufferedThisStream = false
    private var fpsSample: Pair<Long, Long>? = null
    private val liveEndings = ArrayDeque<Long>()
    /** Bis hierhin gelten Fehler als Folge des HDMI-Moduswechsels (Bildwiederholrate), nicht des Streams. */
    private var displaySwitchGraceUntil = 0L
    private var displaySwitchErrors = 0
    private var resumeAfterSwitch = false
    private var progressJob: Job? = null
    private var numberJob: Job? = null
    private var watchdogJob: Job? = null
    private var infoJob: Job? = null
    private var zapJob: Job? = null
    private var lastZapAt = 0L
    private var zapTarget: ChannelEntity? = null
    private var seekJob: Job? = null
    private var pendingSeekMs = C.TIME_UNSET
    private var lastAudioErrorAt = 0L
    /** Zählt jeden neuen Stream (Sender, Neuverbindung, neuer Player): Der Watchdog fängt dann von vorn an. */
    private var streamGeneration = 0
    /** Nachgeladene/übersetzte Untertitel nach Sprache. Alle hängen gleichzeitig am Film, Umschalten ist sofort. */
    private val subtitleFiles = LinkedHashMap<String, File>()
    private var autoSubtitleTried = false
    private var subtitleJob: Job? = null
    private var fileTranslationJob: Job? = null
    private val cueTranslations = HashMap<String, String>()
    private var cueJob: Job? = null

    /** Live-Übersetzung eingebetteter Untertitel: Zielsprache, oder null wenn aus. */
    var liveTranslateTarget by mutableStateOf<String?>(null); private set
    /** Geladene (oder gerade übersetzte) Untertitel, die DnA-TV selbst einblendet: Sprache, oder null. */
    var externalLanguage by mutableStateOf<String?>(null); private set
    /** Sprachen mit gespeicherten Untertiteln zu diesem Film (für das Menü). */
    var loadedLanguages by mutableStateOf<List<String>>(emptyList()); private set
    /** Von Hand eingestellte Verschiebung der geladenen Untertitel (zusätzlich zur automatischen). */
    var subtitleDelayMs by mutableLongStateOf(0L); private set
    /** Automatisch erkannte Lage der geladenen Untertitel (an eingebetteten Untertiteln ausgerichtet). */
    var subtitleTiming by mutableStateOf(SubtitleTiming()); private set
    var subtitleAutoSynced by mutableStateOf(false); private set
    /** Untertitel kommen nicht vom Player, sondern aus DnA-TV (Übersetzung oder geladene Datei). */
    val ownSubtitles: Boolean get() = liveTranslateTarget != null || externalLanguage != null
    /** Live-Übersetzung von Bild-Untertiteln: Die aktuelle Zeile ist noch nicht übersetzt, das Original bleibt sichtbar. */
    var originalVisible by mutableStateOf(false); private set
    private var externalCues: List<TimedCue> = emptyList()
    private var externalTexts: java.util.concurrent.atomic.AtomicReferenceArray<String>? = null
    private var externalJob: Job? = null
    /** Startzeiten eingebetteter Untertitel (Filmzeit): Taktgeber für die automatische Ausrichtung. */
    private val referenceStarts = ArrayList<Long>()
    private var referenceShowing = false
    private var ignoreReferenceUntil = 0L
    private var syncJob: Job? = null
    /** Mitgehörter Ton (nur Filme): zeigt, wann gesprochen wird, wenn der Film keine eigenen Untertitel hat. */
    private val heardSpeech = SpeechActivity()
    /** Ton, den die App nebenbei selbst dekodiert (wenn er als Dolby/DTS an die Soundbar geht), 20–30 s voraus. */
    private val aheadSpeech = SpeechActivity()
    private val audioAhead = AudioAhead(aheadSpeech)
    /** Nachtmodus / Dialoge verstärken, im Ton des Players. */
    private val enhance = AudioEnhance()
    var audioMode by mutableStateOf(AudioMode.NORMAL); private set
    /** Untertitel-Aussehen aus den Einstellungen. */
    var subtitleLook by mutableStateOf(SubtitleLook()); private set
    /** Gemerkte Spuren dieses Senders/dieser Serie schon angewendet. */
    private var memoryApplied = false
    /** Schlaf-Timer: Ende (Uhrzeit, ms) oder 0 = aus. */
    var sleepAt by mutableLongStateOf(0L); private set
    private var sleepJob: Job? = null
    private var heardSpeechKey: String? = null
    /** Wie viele Sekunden Ton schon mitgehört wurden (für den Gerätetest). */
    val speechHeardSeconds: Int get() = heardSpeech.seconds()
    private var speechJob: Job? = null
    /** Woran die geladenen Untertitel zuletzt ausgerichtet wurden: am gesprochenen Ton (sonst eingebettete Untertitel). */
    var syncedBySpeech by mutableStateOf(false); private set
    private var syncedByReference = false
    /** Zeile für Zeile an den eingebetteten Untertiteln ausgerichtet (null = noch nicht oder nicht möglich). */
    private var elasticTiming: ElasticTiming? = null
    /** Geladene Datei automatisch gegen die Übersetzung der Spur tauschen, wenn sie zu schlecht passt. */
    private var autoSwitch = false
    private var autoSwitchNext = false
    private var sourceDecided = false
    /** Wie gut die geladene Datei zur Spur des Films passt, in Prozent (null = noch nicht gemessen). */
    var matchPercent by mutableStateOf<Int?>(null); private set
    private var elasticJob: Job? = null
    /** Sprachen, für die schon weitere Dateien verglichen wurden (je Film einmal, das kostet Downloads). */
    private val comparedLanguages = HashSet<String>()
    private var pcmForSpeechTried = false
    /** Länge und Bildrate des laufenden Films: helfen bei der Wahl der Untertitel-Datei. */
    private fun filmInfo() = SubtitleRepository.FilmInfo(
        durationMs = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        frameRate = stats.frameRate.takeIf { it > 1f },
    )
    /** Die geladenen Untertitel passen nachweislich zur Datei (OpenSubtitles-Hash): nichts umstellen. */
    private var externalExact = false
    /** Was die Ausrichtung am Ton gerade tut, in Worten (steht im Untertitel-Menü). */
    var speechSyncInfo by mutableStateOf<String?>(null); private set
    /** Abbrüche der Verbindung zum Anbieter beim laufenden Sender (DNA Engine und Zeitversatz-Aufzeichnung melden sie). */
    private val liveDrops = ConnectionDrops()
    /** Live: Das Bild kommt aus dem Zwischenspeicher (angehalten oder zeitversetzt). */
    var timeshifted by mutableStateOf(false); private set
    /** So weit liegt das Bild hinter dem Live-Bild (ms). */
    var behindLiveMs by mutableLongStateOf(0L); private set
    /** So weit lässt sich gerade zurückspulen (ms). */
    var rewindableMs by mutableLongStateOf(0L); private set
    /** Sendezeit (im Zwischenspeicher) am Anfang des laufenden zeitversetzten Abschnitts. */
    private var timeshiftErrors = 0
    /** Serien: nächste Folge in der letzten Minute ([EpisodeExtras]). */
    private val episodes = EpisodeExtras(object : EpisodeExtras.Host {
        override val player get() = this@PlayerActivity.player
        override val vod get() = this@PlayerActivity.vod
        override val positionMs get() = this@PlayerActivity.positionMs
        override val durationMs get() = this@PlayerActivity.durationMs
        override val scope get() = lifecycleScope
        override val container get() = this@PlayerActivity.container
        override val autoNextEpisode get() = settings.autoNextEpisode
        override fun closeOverlay() = this@PlayerActivity.closeOverlay()
        override fun startEpisode(next: ContentRepository.NextEpisode) {
            saveProgress()
            PlayerActivity.startVod(
                this@PlayerActivity,
                VodRequest(
                    key = Episodes.key(next.series.key, next.episode.id),
                    kind = HistoryKind.EPISODE,
                    title = Episodes.title(next.series.name, next.episode),
                    subtitle = next.episode.title,
                    image = next.episode.image ?: next.series.cover,
                    url = next.url,
                ),
            )
            finish()
        }
    })
    val nextEpisode get() = episodes.nextEpisode
    val nextCardVisible get() = episodes.nextCardVisible
    val nextCountdown get() = episodes.nextCountdown
    fun playNextEpisode() = episodes.playNext()
    private var liveSourceLanguage: String? = null
    /** Übersetzung eingebetteter Untertitel mit Vorlauf: Quellspur, Ergebnisse je Einblendung (Startzeit). */
    private var aheadFormat: Format? = null
    private val aheadTranslations = HashMap<Long, String>()
    /** Beginn der Zeilen, die schon vor diesem Schauen übersetzt waren (früher geschaut, nachts vorbereitet). */
    @Volatile private var storedAhead: Set<Long> = emptySet()
    private var aheadJob: Job? = null
    /** Kurzer Vorlauf vor der Übersetzung: Anteil (0–1), solange der Film dafür angehalten ist, sonst null. */
    var prerollProgress by mutableStateOf<Float?>(null); private set
    private var prerollJob: Job? = null
    /** Der Film lief vor dem Vorlauf (dann danach weiter abspielen). */
    private var prerollResume = false
    var translatedCue by mutableStateOf<String?>(null); private set
    var languagePicker by mutableStateOf(false); private set
    /** Auswahl „Programm zuordnen“ (TV-Guide von Hand) für den laufenden Sender. */
    var epgPicker by mutableStateOf(false); private set
    var epgChoices by mutableStateOf<List<de.ayutv.data.repo.PlaylistRepository.GuideChannel>>(emptyList()); private set
    /** Von Hand gewählte Zuordnung des laufenden Senders, oder null. */
    var epgOverride by mutableStateOf<String?>(null); private set
    val preferredSubtitleLanguage: String get() = if (::settings.isInitialized) settings.subtitleLanguage else "en"
    val translationAvailable: Boolean get() = ::settings.isInitialized && settings.translationEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Verhindert Screenshots und Screen-Recording (auch Fire-TV-eigene Funktion und die meisten Mirror-Apps).
        // Die Menüs und der Stream erscheinen in Aufnahmen schwarz.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        watchMirroring()
        // Proxy an: einmal nachsehen, mit welcher IP und welchem Land die Streams laufen (für die Stream-Info).
        if (de.ayutv.data.net.AppProxy.current.active && de.ayutv.data.net.ProxyCheck.last?.proxied == null) {
            lifecycleScope.launch { runCatching { de.ayutv.data.net.ProxyCheck.run(container.http, de.ayutv.data.net.AppProxy.current) } }
        }
        frameRateMatcher = FrameRateMatcher(this)
        frameRateMatcher.onSwitch = ::onDisplaySwitch
        frameRateMatcher.onIgnored = {
            subtitleStatus = "Der Fire TV hat nicht auf die Bildrate des Senders umgeschaltet. Fire-TV-Einstellungen → Display & Audio → " +
                "Display → „Originalbildrate anpassen“ einschalten"
        }
        vod = intent.getVodRequest()
        isLive = vod == null

        if (hasTouchscreen()) hideSystemBars()
        // LocalContainer auch hier: Teile aus dem Menü (Vereinswappen in der Senderliste) brauchen ihn – ohne stürzte
        // die App ab, sobald in der Senderliste des Players ein Spiel lief.
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(de.ayutv.ui.LocalContainer provides container) {
                AyuTheme { FitToScreen { PlayerScreen(this) } }
            }
        }

        // Anstoß eines gefolgten Vereins (oder andere Erinnerung), während hier etwas läuft: einblenden.
        lifecycleScope.launch {
            container.dueReminder.collect { reminder ->
                if (reminder == null) return@collect
                container.dueReminder.value = null
                if (reminder.channelKey == playing?.key) return@collect
                showReminder(reminder)
            }
        }

        lifecycleScope.launch {
            settings = container.settings.current()
            initialChannelKey = intent.getStringExtra(EXTRA_CHANNEL_KEY)
            val castOnStart = intent.getBooleanExtra(EXTRA_CAST_ON_START, false)
            // Einmaliges Laden eines Sprachmodells für die Übersetzung auf dem Gerät anzeigen.
            Translators.remote.onProgress = { text -> runOnUiThread { subtitleStatus = text } }
            subtitleScale = settings.subtitleSize.scale
            subtitleLook = SubtitleLook(settings.subtitleColor, settings.subtitleBackground, settings.subtitlePosition)
            audioMode = settings.audioMode
            enhance.mode = settings.audioMode
            enhance.leveling = settings.loudnessLevel
            loudnessLevel = settings.loudnessLevel
            textDisabled = vod != null && !settings.subtitlesAutoOn
            installPlayer(settings.asyncDecoding || phoneAsyncDefault(), settings.audioOutput == AudioOutput.PCM)
            startWatchdog()
            startGoalWatch()
            // Lief gerade etwas auf dem Fernseher (anderer Sender aus der Senderliste der App gewählt): dort weiter.
            if (hasTouchscreen()) activeTv?.let { tv ->
                castTarget = tv
                castingTo = tv.name
            }

            val request = vod
            if (request != null) {
                vodTitle = request.title
                val handedOver = intent.getLongExtra(EXTRA_START_AT, 0L)
                val resume = when {
                    handedOver > 0 -> handedOver // vom Handy übergeben: an derselben Stelle weiter
                    intent.getBooleanExtra(EXTRA_FROM_START, false) -> 0L
                    else -> container.content.resumePosition(request.key)
                }
                // Datei-Hash, bevor der Player verbindet (danach nie parallel): Damit findet OpenSubtitles
                // Untertitel, die zeitgenau zu genau dieser Datei passen.
                if (settings.onlineSubtitlesEnabled && request.kind != HistoryKind.CATCHUP) {
                    container.subtitles.prepareHash(request.url, settings.userAgent)
                    // Schneller: Suche bei OpenSubtitles schon jetzt (parallel zum Verbinden), nicht erst, wenn das Bild läuft.
                    val remembered = memoryKey()?.let { container.playerMemory.get(it) }?.takeUnless { it.textOff }?.load
                    if (settings.subtitlesAutoOn || remembered != null) {
                        container.subtitles.prefetch(request.key, request.url, request.title, remembered ?: settings.subtitleLanguage, settings)
                    }
                }
                val tv = castTarget
                if (tv != null) {
                    // Auf dem Fernseher weiter: Das Handy spielt nicht mit (Konten mit einer Verbindung).
                    castTo(tv, switching = true)
                } else startVod(request, resume)
            } else {
                val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
                val group = intent.getStringExtra(EXTRA_GROUP)
                val favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES, false)
                alternatives = intent.getStringArrayListExtra(EXTRA_ALTERNATIVES).orEmpty()
                listTitle = when {
                    favoritesOnly -> "★ Favoriten"
                    group != null -> container.content.groupLabel(group)
                    else -> "Alle Sender"
                }
                // Erst den Sender starten, dann die (evtl. zehntausende Einträge lange) Zapp-Liste laden.
                // Vom Handy übergeben und hier nicht in der Playlist: direkt über die Adresse spielen.
                val handedUrl = intent.getStringExtra(EXTRA_LIVE_URL)
                val direct = key?.let { container.content.channel(it) } ?: handedUrl?.let { url ->
                    ChannelEntity(
                        key = "handy:$url", playlistId = -1, name = intent.getStringExtra(EXTRA_LIVE_NAME) ?: "Vom Handy",
                        groupTitle = "Vom Handy", logo = intent.getStringExtra(EXTRA_LIVE_LOGO), url = url, epgId = null, number = 0,
                        userAgent = intent.getStringExtra(EXTRA_LIVE_AGENT),
                    )
                }
                if (direct != null) playChannel(direct)
                val list = container.content.zapList(group, favoritesOnly, merge = settings.mergeVariants)
                val start = direct ?: list.firstOrNull()
                if (start == null) {
                    finish()
                    return@launch
                }
                if (direct == null) playChannel(start)
                channels = if (list.none { it.key == start.key }) listOf(start) + list else list
            }
            // Mit „📺 Fernseher“ gestartet: gleich die Auswahl der Fernseher.
            if (castOnStart && castAvailable && castTarget == null && castingTo == null) handler.post { if (!isFinishing) toggleCast() }
        }
    }

    // ---------------------------------------------------------------- Stabilität

    /** DNA Player mit der eigenen DNA Engine: liest den Strom selbst über den Proxy der App (keine Weiterleitung nötig). */
    private fun installPlayer(asyncDecoding: Boolean = false, forcePcm: Boolean = false) {
        asyncDecodingActive = asyncDecoding
        forcePcmActive = forcePcm
        val agent = headers?.userAgent ?: settings.userAgent
        headers = HeaderProvider(agent)
        val client = container.http.newBuilder()
            .apply { if (!settings.proxyStreams) proxy(java.net.Proxy.NO_PROXY) }
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val saver = settings.dataSaver && onMeteredNetwork()
        val (liveBufferUs, vodBufferUs) = when (settings.bufferProfile) {
            BufferProfile.FAST -> 5_000_000L to 20_000_000L
            BufferProfile.BALANCED -> 10_000_000L to 40_000_000L
            BufferProfile.STABLE -> 20_000_000L to 80_000_000L
        }
        // Speicher: Sticks mit wenig Arbeitsspeicher bekommen einen kleineren Vorrat (4K braucht viele MB je Sekunde).
        // Auf Geräten mit mehr Luft und bei STABLE darf der Puffer etwas größer werden.
        val memoryClass = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).largeMemoryClass
        val baseMax = (memoryClass.toLong() * 1024 * 1024 / 3).coerceIn(32L * 1024 * 1024, 160L * 1024 * 1024)
        val maxBytes = if (settings.bufferProfile == BufferProfile.STABLE && memoryClass >= 256) {
            (baseMax * 3 / 2).coerceAtMost(256L * 1024 * 1024)
        } else if (settings.bufferProfile == BufferProfile.STABLE) {
            (baseMax * 5 / 4).coerceAtMost(192L * 1024 * 1024)
        } else baseMax
        de.ayutv.player.dna.engine.DnaEngineCore.appContext = applicationContext
        val config = de.ayutv.player.dna.engine.DnaEngineCore.Config(
            liveBufferUs = liveBufferUs,
            vodBufferUs = vodBufferUs,
            maxBufferBytes = maxBytes,
            // Datensparmodus bei mobilen Daten: HLS höchstens in 720p.
            maxVideoHeight = if (saver) 720 else 2160,
            maxBandwidth = if (saver) 3_500_000 else 0,
            preferPassthrough = settings.audioOutput == AudioOutput.AUTO && !forcePcm,
            deinterlace = settings.deinterlace,
            displaySync = settings.displaySync,
            liveCatchUp = settings.liveCatchUp,
            tunneling = settings.tunneling,
        )
        val processors = listOfNotNull(
            PcmAdapter(SpeechTapProcessor(heardSpeech)).takeIf { vod != null },
            PcmAdapter(AudioEnhanceProcessor(enhance)),
        )
        val store = EmbeddedSubtitles()
        embedded = store
        val dna = de.ayutv.player.dna.engine.DnaEnginePlayer(
            client,
            userAgent = { headers?.userAgent ?: settings.userAgent },
            resolve = { item -> dnaSource(item, client) },
            onStopped = ::closeDnaTimeshift,
            isLive = { isLive },
            config = config,
            processors = processors,
        )
        dna.textSink = { format, timeUs, durationUs, cues -> store.add(format, timeUs, durationUs, cues) }
        dna.onDrop = { reason -> liveDrops.add(reason) }
        // Ton nebenbei auswerten (Untertitel-Ausrichtung, wenn der Ton an Soundbar/Receiver geht).
        dna.audioSink = { format, timeUs, data -> if (audioAhead.wants(format, timeUs)) audioAhead.offer(format, timeUs, data) }
        dna.addListener(playerListener)
        cushionActive = false
        streamGeneration++
        player = dna
    }

    /**
     * Adressen für die DNA Engine: Live mit Zeitversatz liest sie aus dem Ringspeicher auf dem Gerät (der zeichnet beim
     * Anbieter auf – weiterhin nur eine Verbindung), ohne direkt vom Anbieter. Erstes: Start, zweites: Neuverbinden/Live.
     */
    private fun dnaSource(item: MediaItem, client: okhttp3.OkHttpClient): Pair<String, String>? {
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        closeDnaTimeshift()
        timeshifted = false
        val hls = uri.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }
        if (!isLive || hls || !settings.liveTimeshift || !(uri.startsWith("http://") || uri.startsWith("https://"))) return uri to uri
        val shift = runCatching {
            val dir = File(cacheDir, "timeshift").apply { mkdirs() }
            val capacity = de.ayutv.player.dna.engine.LiveTimeshift.capacityFor(dir)
            if (capacity > 0) {
                de.ayutv.player.dna.engine.LiveTimeshift(uri, client, headers?.userAgent ?: settings.userAgent, dir, capacity, settings.timeshiftMinutes * 60_000L)
            } else null
        }.onFailure { Log.w(TAG, "Zeitversatz der DNA Engine nicht gestartet", it) }.getOrNull() ?: return uri to uri
        shift.onDrop = { reason -> handler.post { liveDrops.add(reason) } }
        dnaTimeshift = shift
        dnaShiftOpenedAt = SystemClock.elapsedRealtime()
        return shift.liveUrl to shift.liveUrl
    }

    private fun closeDnaTimeshift() {
        val shift = dnaTimeshift ?: return
        dnaTimeshift = null
        // Sofort: bricht den Abruf beim Anbieter ab (nur eine Verbindung erlaubt), bevor der nächste beginnt.
        runCatching { shift.close() }
    }

    // ------------------------------------------------ DNA Engine: Zeitversatz

    /** Gerade gezeigte Sendezeit (elapsedRealtime): Lesestelle plus seitdem gespielte Zeit. */
    private fun dnaShownRealtime(p: Player): Long = dnaShiftOpenedAt + p.currentPosition.coerceAtLeast(0)

    /**
     * Live anhalten bzw. weiter: Die Aufzeichnung läuft weiter, die Engine hält einfach an – beim Weiterschauen geht es
     * genau dort weiter (ohne Neuverbinden, ohne Schwarzbild).
     */
    private fun dnaToggleLivePause(p: de.ayutv.player.dna.engine.DnaEnginePlayer, code: Int) {
        val ts = dnaTimeshift
        val play = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> false
            else -> !p.playWhenReady
        }
        if (ts == null || !ts.hasData) {
            // Ohne Speicher: anhalten; weiter geht es wieder live.
            if (play) {
                p.seekToDefaultPosition()
                p.play()
            } else p.pause()
            return
        }
        if (!play) {
            p.playWhenReady = false
            timeshifted = true
        } else p.playWhenReady = true
        updateTimeshiftState(p)
    }

    /** Live: um [deltaMs] springen (zurück, oder vor bis zu Live). */
    private fun dnaShiftLive(p: de.ayutv.player.dna.engine.DnaEnginePlayer, deltaMs: Long) {
        val ts = dnaTimeshift
        if (ts == null || !ts.hasData) {
            message = if (ts == null) "Zurückspulen ist aus (Einstellungen → Wiedergabe → Zeitversatz)" else "Zeitversatz startet gerade …"
            handler.postDelayed({ message = null }, 3_000)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val target = (if (timeshifted) dnaShownRealtime(p) else now) + deltaMs
        if (target >= now - LIVE_EDGE_MS) {
            dnaGoLive(p)
            return
        }
        val oldest = now - ts.rewindableMs
        if (target < oldest) {
            message = "Weiter zurück geht nicht – Anfang des Zwischenspeichers"
            handler.postDelayed({ message = null }, 3_000)
        }
        dnaReopenAt(p, maxOf(target, oldest + 1_000))
    }

    private fun dnaGoLive(p: de.ayutv.player.dna.engine.DnaEnginePlayer) {
        val ts = dnaTimeshift
        if (ts == null) {
            if (!p.playWhenReady) {
                p.seekToDefaultPosition()
                p.play()
            }
            return
        }
        val (offset, at) = ts.offsetAt(SystemClock.elapsedRealtime() - 1_000)
        dnaShiftOpenedAt = at
        p.openUrl(ts.urlAt(offset))
        p.playWhenReady = true
        timeshifted = false
        updateTimeshiftState(p)
    }

    private fun dnaReopenAt(p: de.ayutv.player.dna.engine.DnaEnginePlayer, realtime: Long) {
        val ts = dnaTimeshift ?: return
        val (offset, at) = ts.offsetAt(realtime)
        dnaShiftOpenedAt = at
        p.openUrl(ts.urlAt(offset))
        p.playWhenReady = true
        timeshifted = true
        updateTimeshiftState(p)
    }

    /** Player für den nächsten Stream (immer der DNA Player). */
    private fun ensureEngineFor(@Suppress("UNUSED_PARAMETER") key: String?, @Suppress("UNUSED_PARAMETER") name: String? = null, @Suppress("UNUSED_PARAMETER") url: String? = null): Player? = player

    /** Für Anzeige und Fehlerbericht: „DNA Player · DNA Engine · Decoder“. */
    val engineLabel: String
        get() = "DNA Player · DNA Engine" + (dna?.videoDecoderName?.let { " · $it" } ?: "")

    /** Player mit anderer Decoder-Konfiguration neu aufbauen und an derselben Stelle weiterspielen. */
    /**
     * Handy ab Android 12: Media3 dekodiert dort von sich aus asynchron, und genau so sind die Hardware-Decoder
     * (MediaTek im Xiaomi 11T, Snapdragon, Exynos) für 4K HEVC/HDR getestet. Der erzwungene langsame Modus führte beim
     * Xiaomi 11T nach dem ersten Flush zu „Decoder-Fehler“. Nur wenn es auf diesem Gerät schon einmal scheiterte, bleibt
     * es aus.
     */
    private fun phoneAsyncDefault(): Boolean =
        hasTouchscreen() && Build.VERSION.SDK_INT >= 31 &&
            !getSharedPreferences(DECODER_PREFS, MODE_PRIVATE).getBoolean(PHONE_ASYNC_BROKEN, false)

    private fun rebuildPlayer(
        asyncDecoding: Boolean = asyncDecodingActive,
        forcePcm: Boolean = forcePcmActive,
    ) {
        val old = player ?: return
        val item = old.currentMediaItem
        val position = old.currentPosition
        val parameters = old.trackSelectionParameters
        val userAgent = headers?.userAgent
        old.removeListener(playerListener)
        old.release()
        installPlayer(asyncDecoding, forcePcm)
        val p = player ?: return
        userAgent?.let { headers?.userAgent = it }
        p.trackSelectionParameters = parameters
        if (isLive) p.repeatMode = Player.REPEAT_MODE_ONE
        if (item != null) {
            if (isLive) p.setMediaItem(item) else p.setMediaItem(item, position)
            p.prepare()
            p.playWhenReady = true
        }
    }

    /**
     * IPTV-Streams frieren oft ein, ohne dass ein Fehler kommt: Der Player puffert endlos
     * oder die Position steht still. Der Watchdog erkennt das und verbindet neu.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            // Nur im Vordergrund: Im Hintergrund ist der Live-Stream ohnehin gestoppt.
            repeatOnLifecycle(Lifecycle.State.STARTED) { watch() }
        }
    }

    private suspend fun watch() {
        var lastPosition = -1L
        var stalledSeconds = 0
        var lastReconnect = 0L
        var lastFrames = -1L
        var framesAdvancing = false
        var frozenVideoSeconds = 0
        var seenGeneration = -1
        while (true) {
            delay(1_000)
            val p = player ?: continue
            refreshEngineStats()
            if (isLive) {
                updateTimeshiftState(p)
                checkDrops()
            }
            if (seenGeneration != streamGeneration) {
                seenGeneration = streamGeneration
                stalledSeconds = 0
                frozenVideoSeconds = 0
                lastFrames = -1
                framesAdvancing = false
                lastPosition = -1
            }
            if (displaySwitching()) {
                stalledSeconds = 0
                continue
            }
            val active = p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            val waiting = active && p.playbackState == Player.STATE_BUFFERING
            if (isLive && everReady && waiting && !liveWaiting) {
                liveStalls.addLast(SystemClock.elapsedRealtime())
                watchStalls++
                stepDownIfTooSlow()
            }
            if (isLive && everReady) sampleWatch(p, waiting)
            liveWaiting = waiting
            val frozen = active && p.playbackState == Player.STATE_READY && p.currentPosition == lastPosition
            lastPosition = p.currentPosition
            stalledSeconds = if (waiting || frozen) stalledSeconds + 1 else 0
            // Bild steht, Ton läuft weiter (Decoder hängt): an den Decoder-Zählern erkennen.
            // Nur wenn die Zähler auf diesem Gerät überhaupt laufen (sonst nie eingreifen).
            val frames = if (p.videoFormat != null) p.renderedFrames else null
            if (frames != null && lastFrames in 0 until frames) framesAdvancing = true
            frozenVideoSeconds = if (frames != null && frames == lastFrames && framesAdvancing && p.isPlaying) frozenVideoSeconds + 1 else 0
            lastFrames = frames ?: -1
            // Beim ersten Verbinden hat der Loader eigene Wiederholversuche: die nicht abwürgen.
            val limit = when {
                !everReady -> FIRST_CONNECT_STALL_SECONDS
                isLive -> LIVE_STALL_SECONDS
                else -> VOD_STALL_SECONDS
            }
            estimateFrameRate(p)
            if (isLive && !timeshifted) keepLiveCushion(p)
            if (showTech) bufferedMs = p.totalBufferedDuration
            val now = SystemClock.elapsedRealtime()
            if (stalledSeconds >= limit && now - lastReconnect > 20_000) {
                stalledSeconds = 0
                lastReconnect = now
                // Hängt derselbe Sender binnen 3 Minuten zum dritten Mal und läuft dasselbe Spiel woanders: dorthin.
                while (hangs.isNotEmpty() && now - hangs.first() > 180_000) hangs.removeFirst()
                hangs.addLast(now)
                if (isLive && hangs.size >= 3 && hasAlternative() && tryNextVariant("Stream hängt immer wieder")) {
                    hangs.clear()
                    continue
                }
                reconnect("Stream hängt, verbinde neu …")
            } else if (frozenVideoSeconds >= VIDEO_FREEZE_SECONDS && now - lastReconnect > 20_000) {
                frozenVideoSeconds = 0
                lastReconnect = now
                reconnect("Bild hängt, verbinde neu …")
            }
        }
    }

    /**
     * Die Datenquelle verbindet nach einem Abbruch selbst neu (der Puffer überbrückt das). Passiert das
     * oft, liegt es meist am WLAN oder an zu vielen Verbindungen beim Anbieter: einmal deutlich sagen.
     */
    private fun checkDrops() {
        val drops = liveDrops
        if (drops.count == seenDrops) return
        seenDrops = drops.count
        val recent = drops.recent(DROP_WINDOW_MS)
        val last = recent.lastOrNull() ?: return
        // Für den Fehlerbericht: jede Trennung mit Uhrzeit und Grund (nicht nur die letzte).
        statusHistory += "Trennung ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())}: ${last.reason}" +
            " (${playing?.name.orEmpty()}, Puffer ${player?.totalBufferedDuration?.div(1000) ?: 0} s)"
        val closed = recent.count { it.reason.startsWith("Anbieter hat") }
        val silent = recent.count { "Zeitüberschreitung" in it.reason }
        dropInfo = "Trennungen ${recent.size}× in 10 Min. (vom Anbieter beendet: $closed, keine Daten: $silent, andere: ${recent.size - closed - silent}; zuletzt: ${last.reason})"
        Log.i(TAG, "Verbindung zum Anbieter abgerissen: ${last.reason} (${recent.size}× in 10 Min.)")
        val now = SystemClock.elapsedRealtime()
        // Unsichtbar überbrückt (der Puffer lief weiter): kein Hinweis, nur in der Stream-Info und im Fehlerbericht.
        while (liveStalls.isNotEmpty() && now - liveStalls.first() > DROP_WINDOW_MS) liveStalls.removeFirst()
        if (recent.size < DROP_WARN_COUNT || liveStalls.isEmpty()) return
        // Kein automatischer Wechsel auf HLS mehr (0.45): Der Neustart war selbst ein sichtbarer Hänger, und HLS lief
        // bei vielen Anbietern schlechter. Wer HLS will, stellt es unter Einstellungen → Wiedergabe ein.
        val channel = playing
        if (message == null && (dropWarnedAt == 0L || now - dropWarnedAt > DROP_WINDOW_MS)) {
            dropWarnedAt = now
            val oneConnection = channel?.let { container.accountLimits.maxConnections(it.playlistId) } == 1
            val text = when {
                closed >= silent && oneConnection ->
                    "Der Anbieter beendet die Verbindung immer wieder (${recent.size}× in 10 Min.). Dein Zugang erlaubt nur 1 Verbindung: " +
                        "Läuft er gerade noch auf einem anderen Gerät (Handy, PC, zweiter Fire TV, andere App)?"
                closed >= silent ->
                    "Der Anbieter beendet die Verbindung immer wieder (${recent.size}× in 10 Min.). Das liegt am Anbieter oder an " +
                        "zu vielen Geräten mit demselben Zugang. DnA-TV verbindet jedes Mal neu."
                routeLabel().startsWith("Weg: über Proxy") ->
                    "Vom Anbieter kamen mehrmals 8 Sekunden lang keine Daten an (${recent.size}× in 10 Min.). Der Sender läuft über den " +
                        "Proxy: Meist hakt es auf diesem Weg oder beim Anbieter, nicht im WLAN. DnA-TV verbindet jedes Mal neu."
                else ->
                    "Vom Anbieter kamen mehrmals 8 Sekunden lang keine Daten an (${recent.size}× in 10 Min.). Meist liegt das am WLAN: " +
                        "LAN-Kabel oder Router näher hilft. DnA-TV verbindet jedes Mal neu."
            }
            message = text
            handler.postDelayed({ if (message == text) message = null }, 10_000)
        }
    }

    /** Sender, bei denen wegen zu langsamer Leitung schon auf eine kleinere Fassung gewechselt wurde (nicht wieder hoch). */
    private val steppedDown = HashSet<String>()

    /**
     * 4K stockt, weil die Leitung (meist der Proxy) zu langsam ist: Dreimal binnen 3 Minuten leerer Puffer bei einer
     * 4K/UHD-Fassung → einmal deutlich sagen, woran es liegt. 4K bleibt 4K: Es wird nicht mehr von selbst auf Full HD
     * gewechselt (der Nutzer entscheidet, ob er die HD-Fassung in der Senderliste wählt).
     */
    private fun stepDownIfTooSlow() {
        val channel = playing ?: return
        if (castingRemote) return
        val uhd = de.ayutv.data.parse.ChannelVariants.rank(channel.name) == 2 || (player?.videoFormat?.height ?: 0) >= 2000
        if (!uhd || channel.key in steppedDown) return
        val now = SystemClock.elapsedRealtime()
        if (liveStalls.count { now - it < 180_000 } < 3) return
        steppedDown += channel.key
        val proxy = de.ayutv.data.net.AppProxy.proxies(runCatching { java.net.URI(channel.url).host }.getOrNull())
        message = "4K stockt – " + (if (proxy) "der Proxy" else "die Leitung") + " ist zu langsam für 4K (bleibt in 4K)"
        handler.postDelayed({ if (message?.startsWith("4K stockt") == true) message = null }, 8_000)
    }

    /** Sichtbare Hänger im Live-Bild (Puffer leer), für die Einordnung der Trennungen. */
    private val liveStalls = ArrayDeque<Long>()
    private var liveWaiting = false

    /**
     * Der Fernseher wechselt die Bildwiederholrate: HDMI synchronisiert neu, Bild und Ton fallen
     * kurz aus. In dieser Zeit pausieren (wie Kodi), statt Ton- und Bildfehler als Stream- oder
     * Decoder-Problem zu behandeln und den Player umzubauen.
     */
    private fun onDisplaySwitch(active: Boolean) {
        val p = player
        if (active) {
            displaySwitchGraceUntil = SystemClock.elapsedRealtime() + DISPLAY_SWITCH_GRACE_MS
            displaySwitchErrors = 0
            resumeAfterSwitch = p?.playWhenReady == true
            p?.playWhenReady = false
        } else {
            displaySwitchGraceUntil = SystemClock.elapsedRealtime() + DISPLAY_SWITCH_GRACE_MS
            if (p != null && (resumeAfterSwitch || isLive && !timeshifted) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.playWhenReady = true
            }
            resumeAfterSwitch = false
        }
    }

    /**
     * Live-Streams kommen in Echtzeit an: Der Puffer wächst von selbst nie, jeder Netz-Schluckauf
     * friert das Bild ein. Hat ein Stream schon einmal gestockt und ist der Vorrat wieder knapp,
     * läuft die Wiedergabe unhörbar 1,5 % langsamer, bis
     * wieder genug gepuffert ist (Tonhöhe bleibt gleich). Bei durchgereichtem Dolby/DTS und im
     * Tunneling-Modus ignoriert das Gerät die Geschwindigkeit; dann bleibt alles wie bisher.
     */
    private fun keepLiveCushion(p: Player) {
        if (!p.isPlaying || dna?.audioPassthrough == true) return
        // Jede Tempoänderung kostet bei 50 Hz ab und zu ein doppeltes Bild. Deshalb nur bei Streams,
        // die schon einmal gestockt haben; alle anderen laufen exakt in Originalgeschwindigkeit.
        if (!cushionActive && !rebufferedThisStream) return
        val buffered = p.totalBufferedDuration
        val wanted = when {
            !cushionActive && buffered < CUSHION_LOW_MS -> true
            cushionActive && buffered > CUSHION_HIGH_MS -> false
            else -> return
        }
        cushionActive = wanted
        p.setPlaybackSpeed(if (wanted) CUSHION_SPEED else 1f)
    }

    private fun displaySwitching() = frameRateMatcher.switching || SystemClock.elapsedRealtime() < displaySwitchGraceUntil

    private fun resetStreamState() {
        memoryApplied = false
        aspect = memoryKey()?.let { container.playerMemory.get(it).aspect } ?: AspectMode.FIT
        everReady = false
        rebufferedThisStream = false
        fatalError = false
        fpsSample = null
    }

    /**
     * Viele TS-Streams nennen keine Bildrate (fps = -1). Dann wird sie aus den Decoder-Zählern
     * gemessen und auf den nächsten Standardwert gerundet. Erst damit kann die Bildwiederholrate
     * des Fernsehers bei Live-TV auf 50 Hz umschalten.
     */
    private fun estimateFrameRate(p: Player) {
        if (stats.frameRate > 0f || p.playbackState != Player.STATE_READY || !p.isPlaying) return
        // DNA Engine: Bildrate aus den Zeitstempeln des Stroms (zählt auch verworfene/übersprungene Bilder richtig).
        (p as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.streamFrameRate?.takeIf { it > 0f }?.let { fromStream ->
            stats = stats.copy(frameRate = fromStream, frameRateMeasured = true, displayHz = frameRateMatcher.currentRefreshRate())
            memoryKey()?.let { container.playerMemory.rememberFrameRate(it, fromStream, isLive) }
            suggestFrameRateMatching()
            if (settings.matchFrameRate) frameRateMatcher.match(fromStream)
            return
        }
        val frames = (p as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.let { (it.renderedFrames + it.droppedFrames).toLong() } ?: return
        // Gegen die Wiedergabeposition messen (Frames pro Sekunde Video), nicht gegen die Uhr:
        // so stört es nicht, wenn der Decoder anfangs gepufferte Frames schubweise abarbeitet.
        val position = p.currentPosition
        val sample = fpsSample
        if (sample == null) {
            fpsSample = position to frames
            return
        }
        val elapsedMedia = position - sample.first
        if (elapsedMedia < 0) {
            fpsSample = null
            return
        }
        if (elapsedMedia < 4_000) return
        val measured = (frames - sample.second) * 1000f / elapsedMedia
        val snapped = STANDARD_FRAME_RATES.minByOrNull { abs(it - measured) }
            ?.takeIf { abs(it - measured) / it < 0.06f } ?: return
        stats = stats.copy(frameRate = snapped, frameRateMeasured = true, displayHz = frameRateMatcher.currentRefreshRate())
        memoryKey()?.let { container.playerMemory.rememberFrameRate(it, snapped, isLive) }
        suggestFrameRateMatching()
        if (settings.matchFrameRate) frameRateMatcher.match(snapped)
    }

    /** Mehrfaches Nachladen in kurzer Zeit: größeren Puffer empfehlen (einmal pro Sitzung). */
    private fun onRebuffer() {
        rebufferedThisStream = true
        val now = SystemClock.elapsedRealtime()
        rebufferTimes.addLast(now)
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > 120_000) rebufferTimes.removeFirst()
        if (rebufferTimes.size >= 3 && !bufferAdviceShown && settings.bufferProfile != BufferProfile.STABLE) {
            bufferAdviceShown = true
            subtitleStatus = "Leitung schwankt: in den Einstellungen Puffer auf „Stabil (4K)“ stellen"
        }
    }

    /** Gibt es Ton, aber keine abspielbare Spur, wird das gesagt statt stumm zu bleiben. */
    private fun checkAudioSupport(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { group -> (0 until group.length).any(group::isTrackSupported) }) return
        val mime = audio.first().getTrackFormat(0).sampleMimeType
        val key = (current?.key ?: vod?.key) + mime
        if (key == lastAudioHintFor) return
        lastAudioHintFor = key
        subtitleStatus = unsupportedAudioMessage(mime)
    }

    /** Im Ton-Menü eine nicht abspielbare Spur gewählt: erklären statt nichts zu tun. */
    fun explainUnsupportedAudio(format: androidx.media3.common.Format) {
        closeOverlay()
        subtitleStatus = unsupportedAudioMessage(format.sampleMimeType)
    }

    private fun unsupportedAudioMessage(mime: String?): String {
        val name = when (mime) {
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            else -> mime?.substringAfter('/') ?: "unbekannt"
        }
        val hint = " – Soundbar/Receiver anschließen oder eine andere Tonspur wählen"
        return "Tonformat $name kann dieses Gerät nicht wiedergeben$hint"
    }

    private fun unsupportedFormatMessage(): String {
        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.let { group -> (0 until group.length).map(group::getTrackFormat).maxByOrNull { it.height } }
        val codec = when (format?.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            null -> "dieses Videoformat"
            else -> format?.sampleMimeType?.substringAfter('/') ?: "dieses Videoformat"
        }
        val bits = format?.colorInfo?.lumaBitdepth?.takeIf { it > 8 }?.let { " $it Bit" }.orEmpty()
        val resolution = format?.height?.takeIf { it > 0 }?.let { if (it >= 2000) " in 4K" else " in ${it}p" }.orEmpty()
        // Erst nach allen Ausweichwegen (ohne Tunneling, ohne schnelles Dekodieren): Der Decoder lehnt ab.
        return "Der Decoder lehnt diesen Stream ab ($codec$bits$resolution) – auch ohne Tunneling und schnelles Dekodieren"
    }

    private fun onLiveStreamEnded() {
        val now = SystemClock.elapsedRealtime()
        liveEndings.addLast(now)
        while (liveEndings.isNotEmpty() && now - liveEndings.first() > 60_000) liveEndings.removeFirst()
        // Endet der Stream ständig sofort wieder, ist er wirklich weg: dann langsamer probieren und es sagen.
        val delayMs = if (liveEndings.size > 5) 3_000L else 200L
        if (liveEndings.size > 5) message = "Sender liefert nichts, versuche es weiter …"
        handler.postDelayed({ if (player?.playbackState == Player.STATE_ENDED) reconnect(null) }, delayMs)
    }

    private fun reconnect(reason: String?) {
        val p = player ?: return
        if (timeshifted) {
            // Zeitversetzt: An derselben Stelle neu ansetzen; am Live-Rand ohne neue Daten zurück zu Live.
            if (behindLiveMs < LIVE_EDGE_MS) goLive() else dna?.let { dnaReopenAt(it, dnaShownRealtime(it)) }
            return
        }
        if (reason != null) message = reason
        streamGeneration++
        val position = p.currentPosition
        p.stop()
        if (isLive) p.seekToDefaultPosition() else p.seekTo(position)
        p.prepare()
        p.playWhenReady = true
    }

    private fun originalUrl(): String? = vod?.url ?: playing?.let { adaptLiveUrl(it.url) }

    /** Für die Stream-Info: Vorrat der DNA Engine. */
    fun bufferLabel(): String {
        val p = player ?: return ""
        return "Vorrat %.1f s".format(p.totalBufferedDuration / 1000f) + (dna?.let { " · Bilder ${it.renderedFrames}, verworfen ${it.droppedFrames}" } ?: "")
    }

    /** Für die Stream-Info: läuft dieser Stream über den Proxy (mit Land, sobald einmal geprüft) oder direkt? */
    fun routeLabel(): String {
        val host = de.ayutv.data.net.AppProxy.hostOf(originalUrl())
        if (!de.ayutv.data.net.AppProxy.proxies(host)) return "Weg: direkt"
        if (!settings.proxyStreams) return "Weg: direkt (Proxy nur für Listen und Anmeldung)"
        val seen = de.ayutv.data.net.ProxyCheck.last?.proxied
        return "Weg: über Proxy" + (seen?.let { " (${it.country} · ${it.ip})" } ?: "")
    }

    private fun retryLater() {
        val generation = streamGeneration
        handler.postDelayed({ if (generation == streamGeneration) restartStream() }, 800L * retries.coerceAtLeast(1))
    }

    /** Nach Fehler oder Hintergrund neu starten: Live am Live-Rand (wichtig für HLS), Filme an derselben Stelle. */
    private fun restartStream() {
        // Läuft es auf einem Fernseher, bleibt das Handy still (sonst nähme es dem Fernseher die Verbindung weg).
        if (castingRemote) return
        val p = player ?: return
        if (isLive) p.seekToDefaultPosition()
        p.prepare()
        p.playWhenReady = true
    }

    // ---------------------------------------------------------------- Live

    /** Fassungen dieses Senders, die schon ausgefallen sind (beim Wechsel auf die nächste Fassung). */
    private val failedVariants = HashSet<String>()
    private var switchingVariant = false
    /**
     * Andere Sender mit derselben Übertragung (aus dem Sport-Guide: „auch auf …“). Fallen alle Fassungen des laufenden
     * Senders aus, geht es dort weiter. Gilt, bis man selbst einen anderen Sender wählt.
     */
    private var alternatives: List<String> = emptyList()
    private fun hasAlternative() = alternatives.any { it !in failedVariants && it != playing?.key }
    /** Neuverbindungen des Watchdogs (Zeitpunkte) beim jetzigen Sender: Hängt er wiederholt, ausweichen. */
    private val hangs = ArrayDeque<Long>()

    /**
     * Sender fällt aus: auf die nächste Fassung desselben Senders wechseln (HD → 4K → SD → Backup), statt
     * nur einen Fehler zu zeigen. Liefert false, wenn es keine gibt (dann zeigt der Aufrufer [reason]).
     */
    private fun tryNextVariant(reason: String, countFailure: Boolean = true): Boolean {
        // Diese Fassung ist ausgefallen: beim nächsten Mal weiter hinten einsortieren. Nicht, wenn nur der Decoder dieses
        // Geräts sie nicht schafft ([countFailure] false): Der Sender ist dann in Ordnung – etwa 4K, das das Handy nicht
        // dekodiert, der Fernseher beim Übertragen aber schon. Sonst bekäme auch der Fernseher nur noch HD.
        if (isLive && countFailure) playing?.let { de.ayutv.data.parse.ChannelQuality.recordFailure(it.key) }
        if (vod != null || (!settings.mergeVariants && alternatives.isEmpty())) return false
        val channel = playing ?: return false
        failedVariants += channel.key
        val generation = streamGeneration
        lifecycleScope.launch {
            val next = (if (settings.mergeVariants) container.content.variantsOf(channel).firstOrNull { it.key !in failedVariants } else null)
                // Keine Fassung mehr: ein anderer Sender mit derselben Übertragung (Sport), in dessen bester Fassung.
                ?: alternatives.firstOrNull { it !in failedVariants && it != channel.key }?.let { key ->
                    failedVariants += key
                    container.content.channel(key)?.let { runCatching { container.content.bestForSport(it) }.getOrDefault(it) }
                }
            if (generation != streamGeneration || playing?.key != channel.key) return@launch
            if (next == null) {
                fatalError = true
                message = reason
                return@launch
            }
            Log.i(TAG, "${channel.name} gestört ($reason), wechsle auf ${next.name}")
            switchingVariant = true
            try { playChannel(next) } finally { switchingVariant = false }
            // Mit Grund (z. B. „Decoder lehnt 4K ab“): sonst sähe man nur, dass plötzlich eine kleinere Fassung läuft.
            message = "${channel.name}: $reason – läuft jetzt über ${next.name}"
            statusHistory += "${channel.name}: $reason → ${next.name}"
        }
        return true
    }

    /** Seit wann der jetzige Sender läuft und wie viele Wiedergabefehler es da schon gab (für die Stabilitätswertung). */
    private var watchStartedAt = 0L
    private var watchErrorsAtStart = 0

    // Für den Kurzbericht (WatchLog): je Sender Ruckler, knappster Puffer, Datenrate und Netz.
    private var watchStalls = 0
    private var watchStallSeconds = 0
    private var watchMinBufferMs = -1L
    private var watchRateSum = 0L
    private var watchRateSeconds = 0
    private var watchRateLow = 0L
    private var watchNet = ""
    private var watchWorstRssi = 0
    private var watchTicks = 0
    private var watchLastError = ""

    private fun resetWatchStats() {
        watchStalls = 0
        watchStallSeconds = 0
        watchMinBufferMs = -1L
        watchRateSum = 0L
        watchRateSeconds = 0
        watchRateLow = 0L
        watchTicks = 0
        watchLastError = ""
        val net = de.ayutv.data.net.NetInfo.sample(this)
        watchNet = net.label
        watchWorstRssi = net.rssi
    }

    /** Jede Sekunde beim Live-Sender: Hänger-Dauer, Puffer, Datenrate; Netz jede Minute. */
    private fun sampleWatch(p: Player, waiting: Boolean) {
        if (watchStartedAt == 0L) return
        watchTicks++
        if (waiting) watchStallSeconds++
        // Erst nach dem Einschwingen (20 s): Davor ist der Puffer noch im Aufbau und die Datenrate geschätzt.
        if (SystemClock.elapsedRealtime() - watchStartedAt < 20_000) return
        if (p.isPlaying) {
            val buffered = p.totalBufferedDuration
            if (watchMinBufferMs < 0 || buffered < watchMinBufferMs) watchMinBufferMs = buffered
            val rate = stats.bandwidth
            if (rate > 0) {
                watchRateSum += rate
                watchRateSeconds++
                if (watchRateLow == 0L || rate < watchRateLow) watchRateLow = rate
            }
        }
        if (watchTicks % 60 == 0) {
            val net = de.ayutv.data.net.NetInfo.sample(this)
            if (net.rssi < 0 && (watchWorstRssi == 0 || net.rssi < watchWorstRssi)) watchWorstRssi = net.rssi
        }
    }

    /** Für „Best of“: wie lange der Sender lief und wie oft dabei die Verbindung abbrach. Erst ab einer Minute. */
    private fun recordWatch() {
        val channel = playing ?: return
        if (!isLive || watchStartedAt == 0L) return
        val watched = SystemClock.elapsedRealtime() - watchStartedAt
        watchStartedAt = 0L
        // Datenrate des Senders merken (ab 20 s Schauen ist die Schätzung eingeschwungen): Bei doppelten Sendern gewinnt
        // bei gleicher Auflösung die Fassung, die der Anbieter mit mehr Daten (schärfer) schickt.
        if (watched >= 20_000 && stats.bandwidth > 0) de.ayutv.data.parse.ChannelQuality.recordBitrate(channel.key, (stats.bandwidth / 1000).toInt())
        if (watched >= 30_000) logWatch(channel.name, watched)
        if (watched < 60_000) return
        de.ayutv.data.parse.ChannelQuality.recordWatch(channel.key, watched, liveDrops.count + (playerErrors - watchErrorsAtStart))
    }

    /** Eine Zeile ins Wiedergabe-Protokoll des Kurzberichts (klein, nur auf dem Gerät, höchstens 60 Einträge / 48 h). */
    private fun logWatch(name: String, watchedMs: Long) {
        val drops = liveDrops.recent(watchedMs).groupingBy { de.ayutv.data.parse.WatchLog.kindOf(it.reason) }.eachCount()
        val entry = de.ayutv.data.parse.WatchLog.Entry(
            at = System.currentTimeMillis(), channel = name, watchedS = (watchedMs / 1000).toInt(),
            height = stats.height, fps = stats.frameRate, displayHz = stats.displayHz.takeIf { it > 0f } ?: frameRateMatcher.currentRefreshRate(),
            codec = stats.videoCodec, avgKbps = if (watchRateSeconds > 0) (watchRateSum / watchRateSeconds / 1000).toInt() else 0,
            lowKbps = (watchRateLow / 1000).toInt(), minBufferS = if (watchMinBufferMs < 0) -1 else (watchMinBufferMs / 1000).toInt(),
            stalls = watchStalls, stallS = watchStallSeconds, drops = drops, droppedFrames = stats.droppedFrames,
            startMs = stats.startupMs.toInt(), errors = playerErrors - watchErrorsAtStart,
            route = routeLabel().removePrefix("Weg: "), net = watchNet, worstRssi = watchWorstRssi,
            audio = if (stats.audioCodec.isBlank()) "" else listOfNotNull(
                stats.audioCodec, stats.audioChannels.takeIf { it > 0 }?.let { "${it}ch" },
                if (stats.audioDecoder.isNotBlank()) "im Gerät" else "durchgereicht",
            ).joinToString(" "),
            lastError = watchLastError,
        )
        val file = java.io.File(filesDir, de.ayutv.data.Diagnostics.WATCH_FILE)
        container.scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(de.ayutv.data.parse.WatchLog) {
                    val lines = if (file.isFile) file.readLines() else emptyList()
                    file.writeText(de.ayutv.data.parse.WatchLog.append(lines, entry, System.currentTimeMillis()).joinToString("\n") + "\n")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Schnellumschalter

    /** Leiste mit zuletzt gesehenen Sendern und Favoriten (◀ halten); null = zu. */
    var quickZap by mutableStateOf<List<ChannelEntity>?>(null); private set
    var quickIndex by mutableIntStateOf(0); private set
    private var quickOpenedThisPress = false
    private var quickHide: kotlinx.coroutines.Job? = null

    private fun openQuickZap() {
        lifecycleScope.launch {
            val current = playing?.key
            val recent = runCatching { container.db.history().recentLiveKeys(20) }.getOrDefault(emptyList())
                .mapNotNull { container.db.channels().byKey(it) }
            val favorites = runCatching { container.content.zapList(null, favoritesOnly = true, merge = settings.mergeVariants) }.getOrDefault(emptyList())
            val list = (listOfNotNull(previousChannel) + recent + favorites).filter { it.key != current }.distinctBy { it.key }.take(16)
            if (list.isEmpty()) {
                openChannelList()
                return@launch
            }
            quickZap = list
            quickIndex = 0
            armQuickHide()
        }
    }

    private fun armQuickHide() {
        quickHide?.cancel()
        quickHide = lifecycleScope.launch {
            delay(6_000)
            quickZap = null
        }
    }

    /** Tasten, solange die Schnellwahl offen ist: ◀ ▶ wählen, OK schaltet um, Zurück schließt. */
    private fun handleQuickKey(event: KeyEvent): Boolean {
        val list = quickZap ?: return false
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) quickOpenedThisPress = false
            return code in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!quickOpenedThisPress) quickIndex = (quickIndex - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> quickIndex = (quickIndex + 1).coerceAtMost(list.size - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val target = list.getOrNull(quickIndex)
                quickZap = null
                quickHide?.cancel()
                target?.let { playChannel(it) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                quickZap = null
                quickHide?.cancel()
                return true
            }
            else -> {
                // Andere Tasten (Umschalten, Menü …) schließen die Leiste und wirken wie gewohnt.
                quickZap = null
                return false
            }
        }
        armQuickHide()
        return true
    }

    /** Zum vorherigen Sender; null, wenn es keinen gibt. */
    fun backZap(): Unit? {
        val previous = previousChannel?.takeIf { it.key != playing?.key } ?: return null
        playChannel(previous)
        message = "◀ Zurück: ${previous.name}"
        handler.postDelayed({ if (message?.startsWith("◀ Zurück") == true) message = null }, 2_500)
        return Unit
    }

    fun playChannel(channel: ChannelEntity) {
        val p = ensureEngineFor(channel.key, channel.name, adaptLiveUrl(channel.url)) ?: return
        if (castTarget == null && activeTv == null) freeConnection()
        recordWatch()
        watchStartedAt = SystemClock.elapsedRealtime()
        watchErrorsAtStart = playerErrors
        resetWatchStats()
        if (!switchingVariant) {
            failedVariants.clear()
            // Selbst einen anderen Sender gewählt (nicht eine der Ausweich-Übertragungen): kein Ausweichen mehr.
            if (channel.key !in alternatives && playing != null) alternatives = emptyList()
        }
        hangs.clear()
        // Beim Ausweichen auf eine andere Fassung bleibt der Sender derselbe: kein „vorheriger Sender“.
        if (!switchingVariant) playing?.takeIf { it.key != channel.key }?.let { previousChannel = it }
        playing = channel
        current = channel
        // Verzögerte Wiederholungen und Rückrufe vom vorigen Sender dürfen den neuen nicht treffen.
        handler.removeCallbacksAndMessages(null)
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        streamGeneration++
        resetStreamState()
        retries = 0
        slowRetries = 0
        triedHls = false
        message = null
        zapStartedAt = SystemClock.elapsedRealtime()
        stats = StreamStats()
        if (cushionActive) {
            cushionActive = false
            p.setPlaybackSpeed(1f)
        }
        headers?.userAgent = channel.userAgent ?: settings.userAgent
        // Bildwiederholrate NICHT zurücksetzen: Sonst schaltet der Fernseher bei jedem Zappen zweimal
        // um (zurück auf Standard, dann wieder auf 50 Hz) und wird jedes Mal kurz schwarz.

        val url = adaptLiveUrl(channel.url)
        leaveTimeshift()
        // Läuft es auf einem Fernseher, spielt das Handy nicht mit: Es ist dann die Fernbedienung, und viele Konten
        // erlauben nur eine Verbindung (die braucht der Fernseher).
        val remote = castingRemote
        liveDrops.clear()
        seenDrops = 0
        dropInfo = ""
        stopLiveTranslation()
        embedded?.clear()
        if (!remote) {
            presetFromMemory(p)
            p.setMediaItem(mediaItem(url, guessMime(url)))
            // Schließt der Server die Verbindung (bei IPTV alltäglich), baut der Player die nächste schon
            // auf, während der Puffer noch läuft: nahtlos weiter statt Standbild und Neustart.
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.prepare()
            p.playWhenReady = true
            matchKnownFrameRate(channel.key, live = true)
            showOverlay(Overlay.INFO)
        } else {
            p.stop()
        }

        loadChannelInfo(channel)
        checkConnectionLimit(channel)
        container.scope.launch { container.content.recordLive(channel) }
        checkMobileData()
        preferSmallVariant(channel)
        // Läuft gerade auf einem Fernseher: den neuen Sender direkt dort weiterspielen (DLNA, DnA-TV, Chromecast) –
        // ohne erneute Auswahl des Fernsehers.
        val tv = castTarget
        when {
            tv != null -> castTo(tv, switching = true)
            castingToDnaTv -> forwardToDnaTv(channel)
            else -> castingTo?.let { startCasting(it) }
        }
    }

    // ---------------------------------------------------------------- Zeitversatz (Live anhalten)

    /** Live: Pause hält das Bild an, der Sender wird weiter zwischengespeichert. Erneut drücken spielt genau dort weiter. */
    private fun toggleLivePause(code: Int) {
        val p = dna ?: return
        dnaToggleLivePause(p, code)
        showOverlay(Overlay.INFO)
    }

    /** Zurück- oder vorspulen; über das Live-Bild hinaus geht es wieder live. */
    private fun shiftLive(deltaMs: Long) {
        val p = dna ?: return
        dnaShiftLive(p, deltaMs)
        showOverlay(Overlay.INFO)
    }

    /** Wieder live (aus dem Zwischenspeicher, ohne neue Verbindung zum Anbieter). */
    private fun goLive() {
        val p = dna ?: return
        dnaGoLive(p)
        showOverlay(Overlay.INFO)
    }

    private fun leaveTimeshift() {
        timeshifted = false
        timeshiftErrors = 0
        behindLiveMs = 0
    }

    private fun setLiveItem(p: Player, channel: ChannelEntity) {
        val url = adaptLiveUrl(channel.url)
        p.setMediaItem(mediaItem(url, guessMime(url)))
        p.repeatMode = Player.REPEAT_MODE_ONE
    }

    private fun updateTimeshiftState(p: Player) {
        val ts = dnaTimeshift
        rewindableMs = ts?.rewindableMs ?: 0L
        behindLiveMs = if (timeshifted && ts != null) (SystemClock.elapsedRealtime() - dnaShownRealtime(p)).coerceIn(0L, rewindableMs) else 0L
    }

    /**
     * Läuft beim selben Anbieter eine Aufnahme und erlaubt das Konto nur eine Verbindung, gleich sagen,
     * warum das Bild ausbleibt (sonst sieht es aus wie ein kaputter Sender).
     */
    private fun checkConnectionLimit(channel: ChannelEntity) {
        if (container.accountLimits.maxConnections(channel.playlistId) != 1) return
        val running = container.recordings.all.value.filter { it.state == Recording.State.RUNNING && it.kind == Recording.Kind.RECORDING }
        if (running.isEmpty()) return
        lifecycleScope.launch {
            val busy = running.any { r -> r.channelKey?.let { container.content.channel(it) }?.playlistId == channel.playlistId }
            if (!busy || current?.key != channel.key) return@launch
            val text = "Gerade läuft eine Aufnahme, und dein Konto erlaubt nur 1 Verbindung. Dieser Sender startet erst, wenn die Aufnahme fertig ist."
            message = text
            handler.postDelayed({ if (message == text) message = null }, 8_000)
        }
    }

    /** EPG und Favorit für die Infoleiste; eine ältere Abfrage überschreibt nie die des neuen Senders. */
    private fun loadChannelInfo(channel: ChannelEntity) {
        infoJob?.cancel()
        infoJob = lifecycleScope.launch {
            nowNext = container.content.upcoming(channel, 3)
            isFavorite = container.db.favorites().isFavorite(channel.key)
        }
    }

    /**
     * Einmal drücken schaltet sofort um. Schnell hintereinander gedrückt wandert nur die Anzeige mit,
     * umgeschaltet wird kurz nach dem letzten Druck: Sonst öffnet jeder Druck eine Verbindung, und
     * Anbieter mit nur einer erlaubten Verbindung sperren kurz (403).
     */
    private fun zap(step: Int) {
        val list = channels
        val from = zapTarget ?: current ?: return
        if (list.size < 2) return
        val index = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        val target = list[(index + step + list.size) % list.size]
        val now = SystemClock.elapsedRealtime()
        val burst = now - lastZapAt < ZAP_BURST_MS
        lastZapAt = now
        if (!burst && zapJob == null) {
            playChannel(target)
            return
        }
        zapTarget = target
        previewChannel(target)
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            delay(ZAP_COMMIT_MS)
            zapJob = null
            zapTarget = null
            playChannel(target)
        }
    }

    /** Infoleiste zeigt schon den Zielsender, der Player bleibt noch beim alten. */
    private fun previewChannel(channel: ChannelEntity) {
        current = channel
        showOverlay(Overlay.INFO)
        loadChannelInfo(channel)
    }

    private fun adaptLiveUrl(url: String): String =
        if (settings.liveFormat == LiveFormat.HLS && "/live/" in url && url.endsWith(".ts") &&
            de.ayutv.data.net.AppProxy.hostOf(url).let { it == null || it !in noHlsHosts }
        ) url.removeSuffix(".ts") + ".m3u8" else url

    /** Läuft gerade die HLS-Fassung eines eigentlich als MPEG-TS gelieferten Senders (Einstellung „Live-Format: HLS“)? */
    private fun playingAdaptedHls(p: Player): Boolean {
        val channelUrl = playing?.url ?: return false
        val now = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return false
        return channelUrl.endsWith(".ts") && now.endsWith(".m3u8")
    }

    fun toggleFavorite() {
        val channel = current ?: return
        lifecycleScope.launch { isFavorite = container.content.toggleFavorite(channel.key) }
    }

    /**
     * Live-TV: Zurück öffnet links die Senderliste, das Bild läuft rechts klein weiter. Ein Sender (auch derselbe)
     * holt es wieder ins Vollbild; noch einmal Zurück verlässt den Player.
     */
    var miniVideo by mutableStateOf(false)
        private set

    private fun openMiniChannelList() {
        miniVideo = true
        openChannelList()
    }

    /** Das Loslassen der OK-Taste, die gerade die Senderliste geöffnet hat, nicht an die Liste weitergeben. */
    private var swallowOkUp = false

    /** Senderliste zeigt die Kategorien (‹ oben links oder ◀): eine wählen lädt deren Sender. */
    var groupMode by mutableStateOf(false); private set
    var groupChoices by mutableStateOf<List<Pair<String?, String>>>(emptyList()); private set

    fun showGroups() {
        groupMode = true
        lifecycleScope.launch {
            val groups = runCatching { container.content.groups.first() }.getOrDefault(emptyList())
            groupChoices = listOf<Pair<String?, String>>(FAVORITES_GROUP to "★ Favoriten", null to "Alle Sender") +
                groups.map { it.name to "${it.name}  ·  ${it.count}" }
        }
    }

    fun hideGroups() {
        groupMode = false
    }

    /** Andere Kategorie: deren Sender in die Liste (Zappen mit ▲ ▼ folgt ihr). */
    fun switchGroup(group: String?) {
        groupMode = false
        lifecycleScope.launch {
            val favoritesOnly = group == FAVORITES_GROUP
            val name = if (favoritesOnly) null else group
            val list = runCatching { container.content.zapList(name, favoritesOnly, merge = settings.mergeVariants) }.getOrDefault(emptyList())
            if (list.isEmpty()) {
                message = "Keine Sender in dieser Kategorie"
                return@launch
            }
            listTitle = when {
                favoritesOnly -> "★ Favoriten"
                name != null -> container.content.groupLabel(name)
                else -> "Alle Sender"
            }
            channels = list
            nowPlayingMap = container.content.nowPlaying(list)
        }
    }

    fun openChannelList() {
        showOverlay(Overlay.CHANNELS)
        lifecycleScope.launch { nowPlayingMap = container.content.nowPlaying(channels) }
    }

    fun closeOverlay() {
        overlay = Overlay.NONE
        groupMode = false
        miniVideo = false
        syncPanel = false
        // Beim nächsten Öffnen wieder das normale Menü, nicht die Unterauswahl von vorhin.
        epgPicker = false
        pipChoices = null
        menuPage = null
    }

    private fun onDigit(digit: Int) {
        numberInput = (numberInput + digit).takeLast(4)
        numberJob?.cancel()
        // Kann keine Sendernummer mit dieser Eingabe weitergehen, sofort umschalten statt 1,5 s zu warten.
        val typed = numberInput
        val custom = container.channelNumbers.all.value.values.map { it.number }
        val canGrow = channels.isEmpty() || (channels.map { it.number } + custom).any { n ->
            n.toString().let { it.length > typed.length && it.startsWith(typed) }
        }
        numberJob = lifecycleScope.launch {
            if (canGrow) delay(1_500)
            val number = numberInput.toIntOrNull()
            numberInput = ""
            // Eigene Sendernummern zuerst: Die Playlist kann dieselbe Nummer noch für einen anderen Sender haben.
            val target = number?.let { n ->
                container.channelNumbers.keyFor(n)?.let { key -> channels.firstOrNull { it.key == key } ?: container.content.channel(key) }
                    ?: channels.firstOrNull { it.number == n } ?: container.content.channelByNumber(n)
            }
            if (target != null) playChannel(target) else message = "Kein Sender mit Nummer $number"
        }
    }

    /**
     * Bildwiederholrate schon beim Start umschalten, solange der Stream noch lädt: mit der gemerkten Bildrate dieses
     * Senders oder Films (bei einem neuen Sender die des zuletzt geschauten). Sonst schaltet der Fernseher erst um,
     * wenn das Bild schon läuft (bei Sendern ohne Angabe nach einigen Sekunden Messung), und wird dann schwarz.
     * Stimmt die Schätzung nicht, korrigiert die Messung wie bisher.
     */
    private fun matchKnownFrameRate(key: String, live: Boolean) {
        if (!settings.matchFrameRate) return
        val memory = container.playerMemory
        val fps = memory.frameRate(key).takeIf { it > 0f } ?: if (live) memory.lastLiveFrameRate else 0f
        if (fps > 0f) frameRateMatcher.match(fps)
    }

    // ---------------------------------------------------------------- VOD

    private fun startVod(request: VodRequest, resumeMs: Long) {
        val p = ensureEngineFor(request.key, request.title) ?: return
        if (castTarget == null && activeTv == null) freeConnection()
        // Neuer Film/neue Folge: auf dem Fernseher an derselben Stelle wie hier beginnen.
        castStartPositionMs = resumeMs
        headers?.userAgent = settings.userAgent
        streamGeneration++
        resetStreamState()
        // Schon einmal geladene oder übersetzte Untertitel stehen im Menü sofort bereit.
        subtitleFiles.clear()
        subtitleFiles.putAll(container.subtitles.cachedAll(request.key))
        embedded?.clear()
        if (heardSpeechKey != request.key) {
            heardSpeech.clear()
            audioAhead.reset()
        }
        heardSpeechKey = request.key
        loadedLanguages = subtitleFiles.keys.toList()
        presetFromMemory(p)
        p.setMediaItem(vodItem(request), resumeMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        matchKnownFrameRate(Episodes.seriesKeyOf(request.key) ?: request.key, live = false)
        showOverlay(Overlay.CONTROLS)
        if (request.kind == HistoryKind.EPISODE) episodes.prepare(request)
        checkMobileData()
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                if (pendingSeekMs == C.TIME_UNSET) positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (request.kind == HistoryKind.EPISODE) episodes.update(request)
                if (++tick % 15 == 0) saveProgress()
                delay(1_000)
            }
        }
    }

    // ---------------------------------------------------------------- Serien

    /**
     * Geladene Untertitel, die noch am Ton ausgerichtet werden: den Ton nebenbei selbst dekodieren, so wie der Player
     * ihn lädt (20–50 s vor dem Bild). So steht der Versatz früher fest, und auch Ton, der als Dolby/DTS an Soundbar
     * oder Receiver geht, lässt sich auswerten, ohne den Raumklang abzuschalten.
     */
    private fun updateAudioAhead() {
        val format = player?.audioFormat
        val forSubtitles = vod != null && externalLanguage != null && !externalExact && !syncedByReference
        val wanted = (forcedAhead || forSubtitles) && format != null
        audioAhead.untilMs = Long.MAX_VALUE
        // Für die Ausrichtung weit vorausladen: Der Ton von ~2,5 Minuten liegt dann nach wenigen Sekunden ausgewertet
        // vor – Versatz und passende Datei stehen fest, bevor viel vom Film gelaufen ist.
        dna?.extraReadAheadUs = if (forSubtitles && format != null && !subtitleAutoSynced) SUBTITLE_READ_AHEAD_US else 0L
        if (wanted != audioAhead.active) {
            audioAhead.active = wanted
            Log.i(TAG, if (wanted) "Ton-Auswertung nebenbei an (${format?.sampleMimeType})" else "Ton-Auswertung nebenbei aus")
        }
    }

    /** Sprachsignal für die Ausrichtung: gehörter Ton, wo nichts gehört wurde der nebenbei dekodierte. */
    private fun speechSignal(fromMs: Long, toMs: Long): FloatArray {
        val heard = heardSpeech.signal(fromMs, toMs)
        if (aheadSpeech.seconds() == 0) return heard
        val ahead = aheadSpeech.signal(fromMs, toMs)
        for (i in heard.indices) if (heard[i].isNaN() && i < ahead.size) heard[i] = ahead[i]
        return heard
    }

    /** Sekunden Ton, die für die Ausrichtung bereitstehen (gehört oder nebenbei dekodiert). */
    private fun speechSeconds(): Int = maxOf(heardSpeech.seconds(), aheadSpeech.seconds())

    private var forcedAhead = false

    /** Gerätetest: Ton-Auswertung nebenbei erzwingen (im Emulator geht kein Ton an eine Soundbar). */
    fun forceAudioAhead() {
        forcedAhead = true
        updateAudioAhead()
    }

    /** So viele Sekunden Ton hat die Auswertung nebenbei schon (für Test und Fehlerbericht). */
    val aheadSeconds: Int get() = aheadSpeech.seconds()

    private fun passesThrough(format: Format): Boolean {
        if (settings.audioOutput == AudioOutput.PCM || format.sampleMimeType == null) return false
        // Die DNA Engine sagt, ob der Ton gerade an Receiver/Soundbar geht.
        return dna?.audioPassthrough == true
    }

    /** Genau an [to] springen (sonst springt der Film zum nächsten Schlüsselbild, bei Serien oft Sekunden daneben). */
    private fun seekExact(p: Player, to: Long) = p.seekTo(to) // die DNA Engine zeigt ohnehin genau ab der Stelle

    /**
     * Nur wenn "Untertitel automatisch an" eingestellt ist: Fehlt eine eingebettete Spur in der
     * bevorzugten Sprache, wird sie geladen (OpenSubtitles, sonst Übersetzung).
     */
    private fun maybeAutoSubtitles() {
        if (vod == null || autoSubtitleTried || UhdSelfTest.isTest(vod?.key)) return
        // Serie: Untertitel, die man bei einer Folge geladen hat, kommen bei der nächsten von selbst.
        val remembered = memoryKey()?.let { container.playerMemory.get(it) }?.takeUnless { it.textOff }?.load
        if (!settings.subtitlesAutoOn && remembered == null) return
        autoSubtitleTried = true
        val language = remembered ?: settings.subtitleLanguage
        if (remembered != null) {
            loadSubtitles(language)
            return
        }
        if (hasTextTrack(language)) return
        if (language in subtitleFiles || settings.onlineSubtitlesEnabled || settings.translationEnabled) loadSubtitles(language)
    }

    /**
     * Untertitel in [language] einschalten, ohne dass etwas eingerichtet sein muss. Reihenfolge:
     * 1. eingebettete Spur in der Sprache, 2. eine vollständige eingebettete Spur übersetzen (auch
     * Bild-Untertitel, per Texterkennung; immer zeitgenau, auf jedem Gerät), nur ohne solche Spur:
     * 3. gespeicherte Datei oder OpenSubtitles (automatisch ausgerichtet), 4. erzwungene eingebettete
     * Untertitel übersetzen, 5. eine Online-Vorlage vorausschauend übersetzen, 6. ehrlich sagen, woran es fehlt.
     */
    /**
     * Untertitel in [language]. [via]: von Hand gewählte Quelle ([PlayerMemory.SOURCE_FILE] = OpenSubtitles,
     * [PlayerMemory.SOURCE_TRANSLATE] = Spur des Films übersetzen), wird je Film bzw. Serie gemerkt.
     */
    fun loadSubtitles(language: String, via: String? = null) {
        val request = vod
        if (request != null) memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(text = null, load = language) } }
        if (request != null && via != null) memoryKey()?.let { container.playerMemory.setSubtitleSource(it, via) }
        val chosen = via ?: memoryKey()?.let { container.playerMemory.subtitleSource(it) }
        val decided = request?.let { container.playerMemory.subtitleSource(PlayerMemory.autoKey(it.key)) }
        languagePicker = false
        closeOverlay()
        stopLiveTranslation()
        if (hasTextTrack(language)) {
            selectTextLanguage(language)
            subtitleStatus = "Untertitel: ${Languages.label(language)}"
            return
        }
        val label = Languages.label(language)
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            // 2. Eingebettete Untertitel einer anderen Sprache übersetzen (auch Bild-Untertitel per Texterkennung), wenn
            //    so gewählt oder für diesen Film schon entschieden, und bei Sendern. Englisch geht vor (direkte Modelle).
            //    Kurzer Vorlauf: Die erste Minute wird übersetzt, bevor es weitergeht.
            val embeddedSource = if (settings.translationEnabled) bestTranslationSource(allowForced = false) else null
            val translate = chosen == PlayerMemory.SOURCE_TRANSLATE ||
                chosen == null && decided == PlayerMemory.SOURCE_TRANSLATE || request == null
            if (embeddedSource != null && translate) {
                startLiveTranslation(language, embeddedSource, preroll = true)
                return@launch
            }
            // Übersetzung ausdrücklich gewählt, aber keine eigene Spur: gleich eine Datei in anderer Sprache (meist
            // Englisch) übersetzen (DeepL bzw. Gerät), statt erst OpenSubtitles in der Zielsprache zu nehmen.
            if (request != null && embeddedSource == null && settings.translationEnabled && chosen == PlayerMemory.SOURCE_TRANSLATE) {
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
                }.onFailure { Log.w(SUBTITLE_TAG, "Vorlage fehlgeschlagen", it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    return@launch
                }
                subtitleStatus = "Keine Vorlage zum Übersetzen gefunden – lade Untertitel von OpenSubtitles"
            }
            // 3. OpenSubtitles zuerst (menschlich übersetzt): Datei laden und an der Spur des Films ausrichten. Passt sie
            //    nach dem Abgleich unter [FIT_PERCENT], wird auf die Übersetzung der Spur gewechselt (nur mit DeepL).
            var onlineProblem: String? = null
            if (request != null) {
                if (settings.onlineSubtitlesEnabled) subtitleStatus = "Suche Untertitel ($label) …"
                val found = runCatching {
                    container.subtitles.find(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
                }.onFailure {
                    Log.w(SUBTITLE_TAG, "Suche fehlgeschlagen", it)
                    onlineProblem = describeOnlineProblem(it)
                }.getOrNull()
                if (found != null) {
                    autoSwitchNext = embeddedSource != null && chosen != PlayerMemory.SOURCE_FILE
                    attachLoaded(language, found)
                    return@launch
                }
            }
            if (embeddedSource != null) {
                startLiveTranslation(language, embeddedSource, preroll = true)
                if (chosen == PlayerMemory.SOURCE_FILE) subtitleStatus = "Keine Datei bei OpenSubtitles – übersetze stattdessen die Spur des Films"
                return@launch
            }
            if (!settings.translationEnabled) {
                subtitleStatus = onlineProblem ?: "Keine Untertitel in $label gefunden (Übersetzung ist in den Einstellungen aus)"
                return@launch
            }
            // 4. Nur erzwungene eingebettete Untertitel: besser als nichts
            bestTranslationSource()?.let { source ->
                startLiveTranslation(language, source)
                return@launch
            }
            // 5. Vorlage (gespeichert oder online) vorausschauend übersetzen
            if (request != null) {
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
                }.onFailure { Log.w(SUBTITLE_TAG, "Vorlage fehlgeschlagen", it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    return@launch
                }
            }
            // 6. Nichts da, was sich übersetzen ließe
            onlineProblem?.let {
                subtitleStatus = it
                return@launch
            }
            subtitleStatus = when {
                request == null -> "Dieser Sender hat keine Untertitel, die übersetzt werden könnten"
                !settings.onlineSubtitlesEnabled ->
                    "Dieser Film hat keine Untertitel. Mit OpenSubtitles (Einstellungen → Einrichtung per Handy) gibt es welche für fast jeden Film"
                else -> "Für diesen Film gibt es weder eingebettete noch Online-Untertitel"
            }
        }
    }

    private fun attachLoaded(language: String, result: SubtitleRepository.Result) {
        lastOnlineProblem = null
        subtitleFiles[language] = result.file
        loadedLanguages = subtitleFiles.keys.toList()
        if (!showLoaded(language)) return
        externalExact = result.source == SubtitleRepository.Source.HASH
        subtitleStatus = when (result.source) {
            SubtitleRepository.Source.HASH -> "Untertitel geladen, passend zur Datei"
            SubtitleRepository.Source.TITLE -> "Untertitel geladen (nach Titel gefunden). Daneben? ☰ → Untertitel-Zeit einstellen"
            SubtitleRepository.Source.TRANSLATED -> "Untertitel übersetzt (${result.translator})"
            SubtitleRepository.Source.CACHE -> "Untertitel: ${Languages.label(language)}"
        }
    }

    /** Eingebettete Untertitel, die Text enthalten (keine Bild-Untertitel), bevorzugt Englisch. */
    /**
     * Eingebettete Spur, die sich am besten übersetzen lässt: vollständige vor erzwungenen (die nur
     * fremdsprachige Stellen zeigen), Englisch zuerst (direkte Modelle, beste Qualität), Text vor
     * Bild-Untertiteln (die erst per Texterkennung gelesen werden), normale vor SDH.
     */
    private fun bestTranslationSource(allowForced: Boolean = true): Pair<Tracks.Group, Int>? {
        val candidates = textTracks().filter { (g, i) ->
            val format = g.getTrackFormat(i)
            (allowForced || !format.isForcedSubtitle()) && (!format.isBitmapSubtitle() || settings.translationEnabled)
        }
        return candidates.sortedWith(
            compareBy<Pair<Tracks.Group, Int>> { (g, i) -> g.getTrackFormat(i).isForcedSubtitle() }
                .thenBy { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) != "en" }
                .thenBy { (g, i) -> g.getTrackFormat(i).isBitmapSubtitle() }
                .thenBy { (g, i) -> g.getTrackFormat(i).isSdhSubtitle() }
        ).firstOrNull()
    }

    private fun textTracks(): List<Pair<Tracks.Group, Int>> = player?.currentTracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group -> (0 until group.length).filter(group::isTrackSupported).map { group to it } }

    /** Vollständige Spur in [language] (erzwungene Untertitel zählen nicht, sie zeigen nur einzelne Stellen). */
    private fun fullTextTrack(language: String): Pair<Tracks.Group, Int>? = textTracks()
        .filter { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) == Languages.normalize(language) && !g.getTrackFormat(i).isForcedSubtitle() }
        .minByOrNull { (g, i) -> if (g.getTrackFormat(i).isSdhSubtitle()) 1 else 0 }

    private fun hasTextTrack(language: String): Boolean = fullTextTrack(language) != null

    private fun selectTextLanguage(language: String) {
        val p = player ?: return
        val track = fullTextTrack(language)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .apply { track?.let { (group, index) -> setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) } }
            .build()
        textDisabled = false
    }

    fun openEpgPicker() {
        val channel = current ?: return
        epgOverride = container.epgOverrides.get(channel.key)
        epgPicker = true
        epgChoicesLoading = true
        overlayToken++
        lifecycleScope.launch {
            // Laden und nach Ähnlichkeit sortieren im Hintergrund: Es sind oft Tausende Sender, im
            // Bedienungs-Thread stand der Fire TV dabei sekundenlang still.
            epgChoices = withContext(Dispatchers.Default) {
                val all = container.playlists.guideChannels(channel.playlistId)
                de.ayutv.data.parse.EpgMatcher.rank(channel.name, all, { it.id }, { it.name })
            }
            epgChoicesLoading = false
        }
    }

    /** Die Liste der TV-Guide-Sender wird noch geladen und sortiert. */
    var epgChoicesLoading by mutableStateOf(false); private set

    fun closeEpgPicker() {
        epgPicker = false
    }

    /** Programm für den laufenden Sender festlegen ([guideId] = null: wieder automatisch) und den TV-Guide neu laden. */
    fun assignEpg(guideId: String?) {
        val channel = current ?: return
        container.epgOverrides.set(channel.key, guideId)
        epgPicker = false
        closeOverlay()
        message = if (guideId == null) "Zuordnung zurückgesetzt – TV-Guide wird neu geladen …" else "TV-Guide wird neu zugeordnet …"
        handler.postDelayed({ message = null }, 3_000)
        container.scope.launch {
            container.playlists.syncEpg(channel.playlistId)
            container.content.channel(channel.key)?.let { fresh -> withContext(Dispatchers.Main) { if (current?.key == fresh.key) loadChannelInfo(fresh) } }
        }
    }

    fun openLanguagePicker() {
        languagePicker = true
        overlayToken++
        warmUpTranslator()
    }

    private var warmUpJob: Job? = null

    /**
     * Während die Sprache gewählt wird, schon das Modell der zuletzt gewählten Richtung laden: Auf dem
     * Fire TV Stick dauert das bis zu einer Minute, so steht die erste Zeile früher da.
     */
    private fun warmUpTranslator() {
        if (warmUpJob?.isActive == true || liveTranslateTarget != null || Translators.from(settings) == null) return
        val target = settings.lastTranslationTarget.ifEmpty { return }
        val source = bestTranslationSource()?.let { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) } ?: return
        if (source == target || Translators.remote.isWarm(source, target)) return
        // Rechnet das Handy mit oder übersetzt DeepL, braucht der Fernseher das Modell nicht.
        if (de.ayutv.data.translate.PhoneHelper.usable) return
        if (settings.translationProvider == de.ayutv.data.TranslationProvider.DEEPL && settings.deeplKey.isNotBlank() &&
            de.ayutv.data.translate.DeepLTranslator.targetCode(target) != null
        ) return
        warmUpJob = lifecycleScope.launch {
            runCatching { Translators.remote.translate(listOf("Hello."), target, source) }
                .onFailure { subLog("Übersetzer vorab laden: ${it.message}") }
        }
    }

    fun closeLanguagePicker() {
        languagePicker = false
        overlayToken++
    }

    /**
     * Übersetzt eine eingebettete Spur live in [target]: Jede Zeile wird beim Erscheinen übersetzt
     * und zwischengespeichert; die Originalzeilen werden dafür ausgeblendet.
     */
    fun startLiveTranslation(target: String, source: Pair<Tracks.Group, Int>? = bestTranslationSource(), preroll: Boolean = false) {
        languagePicker = false
        closeOverlay()
        if (!settings.translationEnabled) {
            subtitleStatus = "Übersetzung ist in den Einstellungen ausgeschaltet"
            return
        }
        if (source == null) {
            subtitleStatus = "Keine Untertitel zum Übersetzen vorhanden"
            return
        }
        val (group, index) = source
        val format = group.getTrackFormat(index)
        selectTrack(group, index)
        liveSourceLanguage = Languages.normalize(format.language)
        unlabelledTexts.clear()
        cueTranslations.clear()
        liveTranslateTarget = target
        if (settings.lastTranslationTarget != target) container.scope.launch { container.settings.update { it.copy(lastTranslationTarget = target) } }
        startAhead(format, target)
        if (preroll && vod != null && !isLive) startPreroll(format, thorough = onlineTranslation())
        val via = Translators.from(settings)?.name?.let { " mit $it" }.orEmpty()
        val kind = if (format.isBitmapSubtitle()) "Bild-Untertitel " else ""
        subtitleStatus = "Übersetze ${kind}${liveSourceLanguage?.let { Languages.label(it) + " " }.orEmpty()}→ ${Languages.label(target)}$via"
    }

    /**
     * Übersetzt die mitgelesenen Einblendungen der Spur [format], bevor sie zu sehen sind (der Player
     * puffert 30–60 s voraus). Bild-Untertitel werden vorher per Texterkennung gelesen. Angezeigt
     * wird zur exakten Zeit der Spur: synchron, ohne Verzögerung. Liest der Player die Spur nicht
     * mit (z. B. HLS), übernimmt die Übersetzung beim Erscheinen ([onCuesChanged]).
     */
    private fun startAhead(format: Format, target: String) {
        aheadJob?.cancel()
        saveAheadTranslations()
        aheadTranslations.clear()
        aheadDone.clear()
        storedAhead = emptySet()
        aheadFormat = format
        val request = vod
        aheadKey = request?.let { Triple(it.key, EmbeddedSubtitles.trackKey(format), target) }
        val translator = Translators.from(settings) ?: return
        val bitmap = format.isBitmapSubtitle()
        if (bitmap) {
            embedded?.readImagesOf(format)
            // Bilder der schon gepufferten Minute wurden nicht gelesen: ab hier neu laden (kurzes Nachpuffern).
            if (!isLive) player?.let { it.seekTo(it.currentPosition) }
        }
        aheadJob = lifecycleScope.launch {
            // Schon früher übersetzte Zeilen dieser Spur (Zurückspulen, erneutes Schauen): sofort da, nichts neu rechnen.
            aheadKey?.let { (key, track, language) ->
                val stored = withContext(Dispatchers.IO) { container.subtitles.lineTranslations(key, track, language) }
                aheadTranslations.putAll(stored)
                aheadDone.putAll(stored)
                storedAhead = stored.keys
                if (stored.isNotEmpty()) subLog("${stored.size} schon übersetzte Zeilen geladen")
            }
            var unsaved = 0
            launch {
                while (true) {
                    val store = embedded
                    if (store != null && store.hasEntries(format)) {
                        val position = player?.currentPosition ?: 0L
                        val entry = store.at(format, position)
                        val translated = entry?.let { aheadTranslations[it.startMs]?.ifEmpty { null } }
                        // Noch nicht übersetzt: das Original zeigen (Text selbst, Bild-Untertitel über den Player)
                        // statt gar nichts, solange der Übersetzer lädt oder nicht hinterherkommt. Mit DeepL kommt die
                        // Übersetzung meist binnen einer Sekunde: so lange nichts zeigen, statt Englisch und dann Deutsch.
                        val waitForOnline = entry != null && translated == null && !bitmap && onlineTranslation() &&
                            position - entry.startMs < ONLINE_HOLD_MS
                        translatedCue = translated ?: if (bitmap || waitForOnline) null else entry?.text
                        originalVisible = bitmap && entry != null && translated == null
                    }
                    delay(100)
                }
            }
            // Namen der Serie (aus früheren Folgen gemerkt), laufend aus den gesehenen Zeilen ergänzt.
            val namesKey = request?.key?.let { de.ayutv.data.parse.Episodes.seriesKeyOf(it) ?: it }
            val seriesNames = de.ayutv.data.translate.SeriesNames(this@PlayerActivity)
            var names = namesKey?.let { seriesNames.get(it) }.orEmpty()
            var smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
            val seen = ArrayList<String>()
            // Vorherige Zeile mit Übersetzung und Ende: Zusammenhang für eine kurze nächste Zeile.
            var lastLine: Triple<String, String, Long>? = null
            var failures = 0
            var hangs = 0
            // Wie lange eine Zeile gerade dauert (gleitender Mittelwert, ohne das erste Laden des Modells).
            var lineMs = 0L
            var done = 0
            val skipped = HashSet<Long>()
            var slowWarned = false
            val wanted: (EmbeddedSubtitles.Entry) -> Boolean =
                { it.startMs !in aheadTranslations && (it.text != null || it.image != null) }
            while (true) {
                val store = embedded ?: break
                val position = player?.currentPosition ?: 0L
                // Kommt das Gerät nicht hinterher, die Zeilen überspringen, die vorbei wären, bevor ihre
                // Übersetzung fertig ist: lieber jede zweite Zeile übersetzt als jede zu spät (dann bliebe alles im Original).
                val earliest = position - 2_000
                val ready = position + lineMs * 13 / 10
                fun lateFor(entry: EmbeddedSubtitles.Entry) = lineMs > 0 && (if (entry.endMs >= 0) entry.endMs else entry.startMs + ASSUMED_LINE_MS) <= ready
                val next = store.upcoming(format, earliest, 1) { wanted(it) && !lateFor(it) }.firstOrNull()
                if (next == null) {
                    delay(250)
                    continue
                }
                store.upcoming(format, earliest, 20) { wanted(it) && it.startMs < next.startMs && lateFor(it) }.forEach { skipped += it.startMs }
                // Übersetzer im Internet (DeepL): alle schon gelesenen Zeilen auf einmal schicken statt jede einzeln,
                // so ist die nächste Minute mit einer Anfrage (unter einer Sekunde) übersetzt.
                if (translator is de.ayutv.data.translate.DeepLTranslator && de.ayutv.data.translate.DeepLTranslator.problem == null &&
                    de.ayutv.data.translate.DeepLTranslator.targetCode(target) != null
                ) {
                    val batch = store.upcoming(format, earliest, ONLINE_BATCH) { wanted(it) && !it.text.isNullOrBlank() }
                    if (batch.size > 1) {
                        val done = runCatching { translateBatch(batch, smart, target, lastLine?.let { (src, tr, _) -> src to tr }) }
                            .onFailure { Log.w(SUBTITLE_TAG, "Übersetzung im Block fehlgeschlagen", it) }.getOrNull()
                        if (done != null) {
                            lastLine = done
                            if (++unsaved >= 10) {
                                unsaved = 0
                                saveAheadTranslations()
                            }
                            de.ayutv.data.translate.DeepLTranslator.problem?.let { subtitleStatus = "$it – übersetze auf dem Gerät (langsamer)" }
                            continue
                        }
                    }
                }
                try {
                    val started = SystemClock.elapsedRealtime()
                    val text = next.text ?: next.image?.let { image -> Translators.remote.recognize(image).also { next.text = it; next.image = null } }.orEmpty()
                    // Geht der Satz in den nächsten Einblendungen weiter, alle zusammen übersetzen und wieder aufteilen.
                    val chain = if (text.isBlank() || bitmap) emptyList() else sentenceAfter(store, format, next, text)
                    if (text.isNotBlank() && !bitmap) {
                        seen += text
                        if (seen.size % 25 == 0 && namesKey != null) {
                            val learned = seriesNames.add(namesKey, de.ayutv.data.translate.TranslationNames.learn(seen))
                            if (learned != names) {
                                names = learned
                                smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
                            }
                        }
                    }
                    val before = lastLine?.takeIf { (_, _, end) -> next.startMs - end in 0..CONTEXT_GAP_MS }?.let { (src, tr, _) -> src to tr }
                    if (text.isBlank()) {
                        aheadTranslations[next.startMs] = ""
                    } else if (chain.isEmpty()) {
                        aheadTranslations[next.startMs] = smart.translate(listOf(text), target, sourceFor(text), before).first()
                        lastLine = Triple(text, aheadTranslations.getValue(next.startMs), if (next.endMs >= 0) next.endMs else next.startMs + ASSUMED_LINE_MS)
                    } else {
                        val all = listOf(next) + chain
                        val texts = all.map { it.text.orEmpty() }
                        val sentence = smart.translate(listOf(texts.joinToString(" ") { de.ayutv.data.translate.SubtitleSentences.plain(it) }), target, sourceFor(text), before).first()
                        val lastEntry = all.last()
                        lastLine = Triple(texts.joinToString(" "), sentence, if (lastEntry.endMs >= 0) lastEntry.endMs else lastEntry.startMs + ASSUMED_LINE_MS)
                        val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, texts.map { it.length })
                        all.forEachIndexed { k, entry ->
                            val part = de.ayutv.data.translate.SubtitleSentences.wrap(parts[k])
                            aheadTranslations[entry.startMs] = part
                            if (k > 0) aheadDone[entry.startMs] = part
                        }
                    }
                    val took = SystemClock.elapsedRealtime() - started
                    if (text.isNotBlank()) {
                        aheadDone[next.startMs] = aheadTranslations.getValue(next.startMs)
                        if (++unsaved >= 10) {
                            unsaved = 0
                            saveAheadTranslations()
                        }
                    }
                    subLog("Vorlauf ${(next.startMs - position) / 1000} s, $took ms: $text → ${aheadTranslations[next.startMs]}")
                    if (text.isNotBlank() && done++ > 0) lineMs = if (lineMs == 0L) took else (lineMs * 3 + took) / 4
                    if (!slowWarned && done >= 8 && skipped.size >= done) {
                        slowWarned = true
                        subtitleStatus = slowTranslationHint(target, lineMs)
                        subLog("Gerät zu langsam: $done Zeilen übersetzt, ${skipped.size} übersprungen, $lineMs ms pro Zeile")
                    }
                    failures = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: de.ayutv.data.translate.RemoteTranslator.NoAnswer) {
                    // Übersetzer hängt (vom System beendet und lädt neu, oder belegt): neu starten und weiter übersetzen,
                    // diese Zeile im Original. Erst wenn er auch nach zwei Neustarts nicht antwortet, das Original zeigen.
                    Log.w(SUBTITLE_TAG, "Übersetzer antwortet nicht", e)
                    // Nicht im Vorlauf stehen bleiben, solange der Übersetzer neu startet: Der Film läuft weiter.
                    endPreroll()
                    Translators.remote.abort(e.message ?: "keine Antwort")
                    aheadTranslations[next.startMs] = next.text.orEmpty()
                    if (++hangs > MAX_TRANSLATOR_RESTARTS) {
                        showOriginalInstead(format, "${e.message}. Test: Einstellungen → Untertitel → Übersetzer testen")
                        break
                    }
                    subtitleStatus = "Übersetzer hing, startet neu ($hangs/$MAX_TRANSLATOR_RESTARTS) – solange Original"
                    lineMs = 0L
                } catch (e: Exception) {
                    Log.w(SUBTITLE_TAG, "Übersetzung mit Vorlauf fehlgeschlagen", e)
                    // Übersetzer ausgefallen (z. B. vom System beendet): nicht im Vorlauf warten, der Film läuft weiter.
                    endPreroll()
                    // Diese Zeile im Original zeigen. Hakt es öfter, kurz durchatmen (Speicher, Modell lädt neu) und weiter;
                    // erst wenn es dauerhaft nicht geht, die ganze Spur im Original, nie eine heruntergeladene Datei.
                    aheadTranslations[next.startMs] = next.text.orEmpty()
                    failures++
                    if (failures >= 6) {
                        showOriginalInstead(format, e.message ?: e.javaClass.simpleName)
                        break
                    }
                    if (failures == 3) delay(15_000)
                }
            }
        }
    }

    /**
     * Mehrere Zeilen in einem Aufruf übersetzen (Übersetzer im Internet): satzweise zusammengefasst und wieder auf die
     * Einblendungen verteilt, wie beim nächtlichen Vorbereiten. Liefert die letzte Zeile als Zusammenhang für die nächste.
     */
    private suspend fun translateBatch(
        batch: List<EmbeddedSubtitles.Entry>,
        smart: de.ayutv.data.translate.SubtitleTranslator,
        target: String,
        before: Pair<String, String>?,
    ): Triple<String, String, Long> {
        val cues = batch.map { de.ayutv.data.translate.SubtitleSentences.Cue(it.startMs, if (it.endMs >= 0) it.endMs else it.startMs + ASSUMED_LINE_MS, it.text.orEmpty()) }
        val groups = de.ayutv.data.translate.SubtitleSentences.groups(cues)
        val texts = groups.map { de.ayutv.data.translate.SubtitleSentences.joined(cues, it) }
        val result = smart.translate(texts, target, sourceFor(cues.first().text), before)
        groups.forEachIndexed { k, range ->
            val sentence = result.getOrNull(k) ?: return@forEachIndexed
            val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, range.map { de.ayutv.data.translate.SubtitleSentences.plain(cues[it].text).length })
            range.forEachIndexed { j, index ->
                val part = de.ayutv.data.translate.SubtitleSentences.wrap(parts[j])
                aheadTranslations[cues[index].startMs] = part
                aheadDone[cues[index].startMs] = part
            }
        }
        subLog("Block: ${batch.size} Zeilen in einem Aufruf übersetzt")
        return Triple(texts.last(), result.lastOrNull().orEmpty(), cues.last().endMs)
    }

    /** Folgende Text-Einblendungen, in denen der Satz von [first] weitergeht (höchstens zwei, noch nicht übersetzt). */
    private fun sentenceAfter(store: EmbeddedSubtitles, format: Format, first: EmbeddedSubtitles.Entry, text: String): List<EmbeddedSubtitles.Entry> {
        val chain = ArrayList<EmbeddedSubtitles.Entry>()
        var previous = first
        var previousText = text
        for (entry in store.upcoming(format, first.startMs + 1, 2) { true }) {
            val next = entry.text ?: break
            if (entry.startMs in aheadTranslations) break
            val gap = if (previous.endMs >= 0) entry.startMs - previous.endMs else entry.startMs - previous.startMs - ASSUMED_LINE_MS
            if (gap > 1_500 || !de.ayutv.data.translate.SubtitleSentences.continues(previousText, next)) break
            chain += entry
            previous = entry
            previousText = next
        }
        return chain
    }

    /**
     * Kurzer Vorlauf (höchstens [PREROLL_MAX_MS]): Der Film hält an, bis die Zeilen der nächsten Minute übersetzt
     * sind, dann bleibt die Übersetzung beim Schauen vorn. Der Player puffert währenddessen weiter, so liegen die
     * Zeilen schon vor. Was schon übersetzt ist (über Nacht, früheres Schauen), braucht keinen Vorlauf.
     * OK oder Zurück: sofort weiterschauen.
     */
    private fun startPreroll(format: Format, thorough: Boolean = false) {
        endPreroll()
        val p = player ?: return
        val from = p.currentPosition
        val until = from + PREROLL_WINDOW_MS
        prerollResume = p.playWhenReady
        prerollJob = lifecycleScope.launch {
            // Gespeicherte Übersetzungen laden lassen: Sind sie schon da, geht es ohne Halt weiter.
            delay(400)
            val started = SystemClock.elapsedRealtime()
            var paused = false
            while (isActive) {
                val player = this@PlayerActivity.player ?: break
                val store = embedded
                val loadedUntil = minOf(until, player.bufferedPosition)
                // Puffer voll (lädt nicht mehr) oder Fenster geladen: mehr Zeilen kommen vorerst nicht dazu.
                val loaded = loadedUntil >= until || !player.isLoading && player.bufferedPosition > from
                // Ab der aktuellen Stelle: Schon vorbeigezogene Zeilen (übersprungen) halten nichts mehr auf.
                val lines = store?.upcoming(format, maxOf(from, player.currentPosition), 400) { it.startMs < loadedUntil }.orEmpty()
                val done = lines.count { it.startMs in aheadTranslations }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (thorough) {
                    // Übersetzer im Internet: 8–10 s anhalten. In der Zeit lädt der Player weit voraus und DeepL
                    // übersetzt alles Geladene; danach bleibt die Übersetzung beim Schauen sicher vorn. Nur wenn
                    // die Stelle schon übersetzt ist (früher geschaut, nachts vorbereitet), geht es ohne Halt weiter.
                    // (Gespeicherte Übersetzungen kommen kurz nach dem Start aus der Datenbank.)
                    if (elapsed < 2_000 && prepared(player.currentPosition)) break
                    val soon = player.currentPosition + ONLINE_LEAD_MS
                    val missing = lines.any { it.startMs !in aheadTranslations && it.startMs < soon }
                    val ahead = loaded || player.bufferedPosition >= soon
                    if (paused && elapsed >= ONLINE_PAUSE_MIN_MS && !missing && ahead || elapsed >= ONLINE_PAUSE_MAX_MS) break
                } else if ((loaded && done >= lines.size) || elapsed >= PREROLL_MAX_MS) {
                    break
                } else if (!paused && (done >= lines.size || elapsed > PREROLL_DECIDE_MS)) {
                    // Nur anhalten, wenn wirklich Zeilen fehlen, und nur gleich zu Beginn: Ist alles Bekannte schon
                    // übersetzt (über Nacht vorbereitet), läuft der Film einfach weiter, statt mitten drin zu stoppen.
                    if (done >= lines.size && elapsed <= PREROLL_DECIDE_MS) {
                        delay(250)
                        continue
                    }
                    break
                }
                if (!paused) {
                    paused = true
                    player.playWhenReady = false
                }
                val coverage = ((loadedUntil - from).toFloat() / PREROLL_WINDOW_MS).coerceIn(0f, 1f)
                val share = if (lines.isEmpty()) 0f else done.toFloat() / lines.size
                val byTime = elapsed.toFloat() / (if (thorough) ONLINE_PAUSE_MIN_MS else PREROLL_MAX_MS)
                prerollProgress = maxOf(byTime, coverage * 0.3f + share * coverage * 0.7f).coerceIn(0.02f, 0.99f)
                delay(250)
            }
            if (paused) {
                subLog("Vorlauf fertig nach ${SystemClock.elapsedRealtime() - started} ms")
                endPreroll()
            } else {
                prerollProgress = null
                prerollJob = null
            }
        }
    }

    /** Sind die nächsten Minuten ab [positionMs] schon übersetzt (früher geschaut, nachts vorbereitet)? */
    private fun prepared(positionMs: Long): Boolean =
        storedAhead.count { it in positionMs..positionMs + PREPARED_WINDOW_MS } >= PREPARED_MIN_LINES

    /** Übersetzt gerade ein Dienst im Internet (DeepL, ohne Störung)? Dann lohnt kurzes Warten auf die Übersetzung. */
    private fun onlineTranslation(): Boolean =
        settings.translationProvider == de.ayutv.data.TranslationProvider.DEEPL && settings.deeplKey.isNotBlank() &&
            de.ayutv.data.translate.DeepLTranslator.problem == null

    /** Vorlauf beenden und den Film weiterlaufen lassen (falls er vorher lief). */
    fun endPreroll() {
        val job = prerollJob ?: return
        prerollJob = null
        job.cancel()
        if (prerollProgress != null) {
            prerollProgress = null
            if (prerollResume) player?.playWhenReady = true
        }
    }

    /**
     * Übersetzer streikt: die eingebettete Spur im Original zeigen (zeitgenau). Nie auf eine heruntergeladene
     * Datei wechseln, die kann zu einer anderen Fassung gehören. Die gewählte Sprache bleibt gemerkt.
     */
    private fun showOriginalInstead(format: Format, reason: String) {
        val track = textTracks().firstOrNull { (g, i) -> g.getTrackFormat(i).let { it == format || it.id != null && it.id == format.id } }
        stopLiveTranslation()
        val p = player
        if (track != null && p != null) {
            val (group, index) = track
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
            textDisabled = false
        }
        val original = Languages.normalize(format.language)?.let { Languages.label(it) } ?: "Original"
        subtitleStatus = "Übersetzung gerade nicht möglich ($reason). Zeige die eingebetteten Untertitel ($original). Neu versuchen: ☰ → Untertitel"
    }

    /** Ehrlich sagen, dass das Gerät nicht mitkommt, und was stattdessen hilft. */
    private fun slowTranslationHint(target: String, lineMs: Long): String {
        val seconds = String.format(Locale.GERMAN, "%.1f", lineMs / 1000f)
        val tip = if (vod != null) " Über Nacht vorbereitet ist alles übersetzt: Film in „Meine Liste“ legen." else ""
        return "Dieses Gerät ist für Live-Übersetzung zu langsam ($seconds s pro Zeile). Ein Teil der Zeilen bleibt im Original.$tip"
    }

    /** Erfolgreich übersetzte Zeilen der laufenden Live-Übersetzung (werden gespeichert) und wofür (Film, Spur, Sprache). */
    private val aheadDone = HashMap<Long, String>()
    private var aheadKey: Triple<String, String, String>? = null

    private fun saveAheadTranslations() {
        val (key, track, language) = aheadKey ?: return
        if (aheadDone.isEmpty()) return
        val lines = HashMap(aheadDone)
        container.scope.launch(Dispatchers.IO) { container.subtitles.saveLineTranslations(key, track, language, lines) }
    }

    /** Gelesene Zeilen einer Spur ohne Sprachangabe, um ihre Sprache zu erkennen. */
    private val unlabelledTexts = ArrayList<String>()

    /**
     * Sprache der Quellspur. Nennt die Spur keine (Live-TV: „Spur 1“), wird sie am Text erkannt, sonst
     * würde deutscher Text als Englisch übersetzt.
     */
    private fun sourceFor(text: String): String? {
        liveSourceLanguage?.let { return it }
        unlabelledTexts += text
        if (unlabelledTexts.size > 20) unlabelledTexts.removeAt(0)
        return Languages.guess(unlabelledTexts)?.also {
            liveSourceLanguage = it
            subtitleStatus = "Untertitel erkannt als ${Languages.label(it)}"
        }
    }

    /** Beendet Live-Übersetzung und eigene Einblendung geladener Untertitel. */
    fun stopLiveTranslation() {
        liveTranslateTarget = null
        externalLanguage = null
        speechSyncInfo = null
        translatedCue = null
        cueJob?.cancel()
        fileTranslationJob?.cancel()
        fileTranslationJob = null
        fileTranslationInfo = null
        externalJob?.cancel()
        externalJob = null
        elasticJob?.cancel()
        elasticJob = null
        elasticTiming = null
        elasticFromSpeech = false
        matchPercent = null
        syncJob?.cancel()
        speechJob?.cancel()
        speechJob = null
        externalCues = emptyList()
        externalTexts = null
        externalIsTranslation = false
        endPreroll()
        aheadJob?.cancel()
        aheadJob = null
        aheadFormat = null
        saveAheadTranslations()
        aheadKey = null
        aheadDone.clear()
        aheadTranslations.clear()
        originalVisible = false
        embedded?.readImagesOf(null)
    }

    // ---------------------------------------------------------------- Geladene Untertitel

    /** Gespeicherte Untertitel in [language] einblenden. false, wenn die Datei leer oder kaputt ist. */
    fun showLoaded(language: String): Boolean {
        if (overlay == Overlay.TRACKS) closeOverlay()
        val file = subtitleFiles[language] ?: return false
        val cues = parseCues(file)
        if (cues.isEmpty()) {
            subtitleStatus = "Die gefundenen Untertitel sind leer"
            return false
        }
        stopLiveTranslation()
        beginExternal(language, cues, texts = null)
        return true
    }

    private fun parseCues(file: File): List<TimedCue> =
        runCatching { Srt.parse(file.readText()) }.getOrDefault(emptyList())
            .mapNotNull { cue -> TimedCue(srtMillis(cue.start) ?: return@mapNotNull null, srtMillis(cue.end) ?: return@mapNotNull null, Srt.plainText(cue.text), cue) }
            .sortedBy { it.start }

    /**
     * Geladene Untertitel selbst einblenden statt über den Player: So lassen sie sich zeitlich
     * verschieben. Der Player spielt währenddessen unsichtbar eine eingebettete Spur als Taktgeber.
     */
    private fun beginExternal(language: String, cues: List<TimedCue>, texts: java.util.concurrent.atomic.AtomicReferenceArray<String>?) {
        externalLanguage = language
        autoSwitch = autoSwitchNext
        autoSwitchNext = false
        sourceDecided = false
        manualAnchor = null
        manualSync = false
        seekRecheckFrom = -1L
        compareRounds = 0
        dropComparedAlternatives()
        externalCues = cues
        externalTexts = texts
        externalIsTranslation = texts != null
        referenceStarts.clear()
        referenceShowing = false
        elasticTiming = null
        elasticFromSpeech = false
        matchPercent = null
        val saved = vod?.let { container.subtitles.timing(it.key, language) }
        subtitleTiming = saved?.let { SubtitleTiming(it.ratio, it.offsetMs) } ?: SubtitleTiming()
        subtitleDelayMs = saved?.manualMs ?: 0L
        // Nur die Bildrate aus der Filmlänge voreingestellt (Versatz 0): der Versatz muss noch gesucht werden.
        subtitleAutoSynced = saved != null && (saved.offsetMs != 0L || saved.manualMs != 0L)
        syncedBySpeech = false
        syncedByReference = false
        externalExact = false
        speechSyncInfo = if (subtitleAutoSynced) "Gespeicherte Ausrichtung, wird am Ton nachgeprüft" else null
        textDisabled = false
        selectSyncReference()
        startSpeechSync()
        startElasticSync(language, cues)
        if (!subtitleAutoSynced) adoptTimingFromOtherLanguage(language, cues)
        externalJob?.cancel()
        externalJob = lifecycleScope.launch {
            while (true) {
                val position = player?.currentPosition ?: 0L
                // Zeilen-Abgleich ohne Anker in der Nähe (gerade weit gesprungen): lieber kurz nichts als falsche Zeilen,
                // bis die Einblendungen dort gelesen und neu ausgerichtet sind (dauert wenige Sekunden).
                if (!elasticCovers(position)) {
                    translatedCue = null
                    delay(100)
                    continue
                }
                val at = subtitleTimeAt(position)
                val shown = externalTexts
                translatedCue = cues.indices.filter { cues[it].start <= at && cues[it].end > at }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { shown?.get(it) ?: cues[it].text }
                delay(100)
            }
        }
    }

    /**
     * Ist für diesen Film schon eine andere Sprache ausgerichtet (am Ton, an einer Zeile, per Datei-Hash), deren
     * Zeiten als Taktgeber nehmen: Gesprochen wird in jeder Sprache zur selben Zeit. So passt eine Übersetzung oder
     * eine zweite Sprache sofort, ohne erneut zuzuhören. Aus derselben Vorlage übersetzte Dateien passen exakt.
     */
    private fun adoptTimingFromOtherLanguage(language: String, cues: List<TimedCue>) {
        val request = vod ?: return
        if (cues.size < SubtitleSync.MIN_REFERENCES) return
        lifecycleScope.launch {
            val fit = withContext(Dispatchers.Default) {
                container.subtitles.cachedAll(request.key).filterKeys { it != language }.firstNotNullOfOrNull { (other, file) ->
                    val timing = container.subtitles.timing(request.key, other)?.takeIf { it.offsetMs != 0L || it.manualMs != 0L } ?: return@firstNotNullOfOrNull null
                    val reference = SubtitleTiming(timing.ratio, timing.offsetMs)
                    val starts = parseCues(file).map { reference.toVideo(it.start) + timing.manualMs }
                    SubtitleSync.estimate(starts, cues.map { it.start })?.let { other to it }
                }
            } ?: return@launch
            if (externalLanguage != language || subtitleAutoSynced) return@launch
            val (other, found) = fit
            subLog("Ausrichtung von ${Languages.label(other)} übernommen: ${found.timing}")
            applyFit(found.timing, bySpeech = false, toleranceMs = 0)
            speechSyncInfo = "Von den schon ausgerichteten Untertiteln (${Languages.label(other)}) übernommen, wird am Ton nachgeprüft"
        }
    }

    /** Gibt es beim Zeilen-Abgleich einen Anker in der Nähe von [position]? (Ohne Zeilen-Abgleich immer.) */
    private fun elasticCovers(position: Long): Boolean =
        elasticFromSpeech || elasticTiming?.let { it.distanceTo(position - subtitleDelayMs) <= ELASTIC_COVER_MS } ?: true

    /** Filmposition → Zeit in der geladenen Datei (automatische und manuelle Verschiebung eingerechnet). */
    private fun subtitleTimeAt(position: Long): Long =
        elasticTiming?.toSubtitle(position - subtitleDelayMs) ?: subtitleTiming.toSubtitle(position - subtitleDelayMs)

    /**
     * Zeile für Zeile ausrichten: Die eingebetteten Untertitel des Films (jede Sprache, auch Bild-Untertitel) liest der
     * Player 30–60 s voraus mit. Jede Zeile der geladenen Datei bekommt den Versatz ihrer Umgebung, so passt eine andere
     * Fassung auch nach fehlenden Szenen oder Werbepausen. Passt die Datei nur mäßig, werden einmal bis zu zwei weitere
     * Dateien derselben Suche verglichen und die beste genommen.
     */
    private fun startElasticSync(language: String, cues: List<TimedCue>) {
        elasticJob?.cancel()
        val starts = cues.map { it.start }
        elasticJob = lifecycleScope.launch {
            var lastCount = 0
            // Hat schon eine Messung ein Urteil ergeben (Gesamtlage oder Zeilen-Abgleich)? Vorher heißt „0 %“ nur
            // „noch zu wenig Einblendungen“ – nicht „passt nicht“.
            var verdict = false
            delay(500)
            while (isActive && externalLanguage == language) {
                val references = embedded?.referenceStarts().orEmpty()
                // Schnell: Der Player liest 20–30 s voraus. Schon mit den ersten Einblendungen dort den Versatz bestimmen und
                // sofort übernehmen, bevor die Zeilen zu sehen sind (nicht erst warten, bis sie gezeigt wurden).
                if (references.size >= SubtitleSync.MIN_REFERENCES && references.size != lastCount && elasticTiming == null && !manualSync) {
                    val fit = withContext(Dispatchers.Default) { SubtitleSync.estimate(references, starts) }
                    if (externalLanguage != language) break
                    if (fit != null) {
                        verdict = true
                        syncedByReference = true
                        applyFit(fit.timing, bySpeech = false, toleranceMs = 150)
                    }
                    matchPercent = fit?.let { SubtitleSync.coverage(references, starts, it.timing) } ?: 0
                }
                if (references.size >= SubtitleAlign.MIN_REFERENCES && references.size != lastCount) {
                    lastCount = references.size
                    val (global, result) = withContext(Dispatchers.Default) { alignTo(references, starts) }
                    if (externalLanguage != language) break
                    // Zeilen-Abgleich noch ohne Ergebnis (zu wenige sichere Zuordnungen): die Schnellprüfung gilt weiter.
                    result?.let { matchPercent = it.percent; verdict = true }
                    if (result != null && result.percent >= ELASTIC_MIN_PERCENT && !manualSync) {
                        val first = elasticTiming == null
                        elasticTiming = result.timing
                        elasticFromSpeech = false
                        // Gesamtversatz mitführen (Anzeige, Speichern, Rückfall, wenn der Zeilen-Abgleich später wegfällt).
                        global?.let { subtitleTiming = it }
                        syncedByReference = true
                        syncedBySpeech = false
                        subtitleAutoSynced = true
                        if (first) {
                            subLog("Zeile für Zeile ausgerichtet: ${result.matched} von ${result.references} Einblendungen, ${result.percent} %")
                            subtitleStatus = "Untertitel Zeile für Zeile an der Spur des Films ausgerichtet – passt ${result.percent} %"
                        }
                    }
                    // OpenSubtitles oder Übersetzung der Spur: entscheiden, sobald es sicher ist.
                    if (autoSwitch && !sourceDecided) {
                        if (decideSource(language, references, verdict)) return@launch
                        delay(1_000)
                        continue
                    }
                    // Passt mäßig (oder gar nicht): einmal weitere Dateien vergleichen und die beste nehmen.
                    if (language !in comparedLanguages && references.size >= COMPARE_REFERENCES &&
                        (result?.percent ?: 0) < de.ayutv.data.repo.OpenSubtitlesQuota.compareBelowPercent
                    ) {
                        comparedLanguages += language
                        if (compareCandidates(language, references, result?.percent ?: 0)) return@launch
                    }
                }
                // Ausgerichtet und Anker in der Nähe: selten nachprüfen. Nach einem Sprung (kein Anker in der Nähe): sofort
                // mit den dort neu gelesenen Einblendungen weiter ausrichten.
                delay(1_000)
                if (elasticTiming != null) {
                    var waited = 1_000L
                    while (waited < 30_000 && isActive && elasticCovers(player?.currentPosition ?: 0L)) {
                        delay(1_000)
                        waited += 1_000
                    }
                }
            }
        }
    }

    /**
     * Passt die geladene Datei? Klar daneben (unter [EARLY_MISS_PERCENT] bei den ersten Einblendungen) oder nach
     * [DECIDE_REFERENCES] Einblendungen unter [FIT_PERCENT]: erst weitere Dateien vergleichen, dann auf die Übersetzung
     * der Spur des Films wechseln (nur mit DeepL: auf dem Gerät wäre sie zu langsam). true = Datei gewechselt.
     */
    private suspend fun decideSource(language: String, references: List<Long>, verdict: Boolean): Boolean {
        val percent = matchPercent ?: 0
        // Noch kein Urteil (zu wenige Einblendungen für einen sicheren Abgleich): weiter sammeln. Früher galt das als
        // „passt 0 %“ – schon nach 12 Einblendungen wurde jede Datei verworfen (der Zeilen-Abgleich braucht dann alle
        // 12 sicher zugeordnet) und auf die Übersetzung gewechselt. Erst nach doppelt so vielen ohne Urteil aufgeben.
        if (!verdict && references.size < DECIDE_REFERENCES * 2) return false
        val clearMiss = percent < EARLY_MISS_PERCENT
        if (!clearMiss && references.size < DECIDE_REFERENCES) return false
        if (percent >= FIT_PERCENT) {
            sourceDecided = true
            subLog("OpenSubtitles passt ($percent % nach ${references.size} Einblendungen) – bleibt")
            return false
        }
        if (language !in comparedLanguages) {
            comparedLanguages += language
            // Eine besser passende Datei beginnt die Prüfung von vorn.
            autoSwitchNext = true
            if (compareCandidates(language, references, percent)) return true
            autoSwitchNext = false
        }
        if (externalLanguage != language) return true
        sourceDecided = true
        if (!onlineTranslation() || bestTranslationSource(allowForced = false) == null) {
            subLog("OpenSubtitles passt nur $percent %, keine schnelle Übersetzung – bleibt")
            return false
        }
        val best = matchPercent ?: percent
        subLog("OpenSubtitles passt nur $best % (${references.size} Einblendungen) – wechsle zur Übersetzung der Spur")
        lifecycleScope.launch { switchToTranslation(language, best) }
        return true
    }

    /** Statt der geladenen Datei die Spur des Films übersetzen; für diesen Film (diese Folge) gemerkt. */
    private fun switchToTranslation(language: String, percent: Int) {
        val source = bestTranslationSource(allowForced = false) ?: return
        vod?.let { container.playerMemory.setSubtitleSource(PlayerMemory.autoKey(it.key), PlayerMemory.SOURCE_TRANSLATE) }
        stopLiveTranslation()
        // Der Player ist schon 20–30 s voraus: Die Übersetzung ist fertig, bevor die Zeilen kommen, kein Vorlauf nötig.
        startLiveTranslation(language, source)
        autoSwitched = percent
        subtitleStatus = "OpenSubtitles passt nur $percent % – übersetze stattdessen die Spur des Films"
    }

    /** Übereinstimmung der Datei, gegen die automatisch zur Übersetzung gewechselt wurde (für das Menü). */
    var autoSwitched by mutableStateOf<Int?>(null); private set

    /**
     * Sprache, für die im Menü zwischen OpenSubtitles und Übersetzung gewählt werden kann (nur Filme mit
     * übersetzbarer eingebetteter Spur).
     */
    /**
     * Sprache, für die das Menü die Wahl der Quelle zeigt: OpenSubtitles (menschlich übersetzt) oder Übersetzung
     * (DeepL bzw. Gerät) – der Spur des Films oder, ohne eigene Spur, einer Datei in anderer Sprache (meist Englisch).
     */
    val sourceChoiceLanguage: String? get() {
        if (vod == null || !settings.translationEnabled) return null
        val language = externalLanguage ?: liveTranslateTarget ?: return null
        return language.takeIf { bestTranslationSource(allowForced = false) != null || settings.onlineSubtitlesEnabled }
    }

    /** Die eingeblendeten Untertitel sind eine Übersetzung (einer Datei in anderer Sprache). */
    var externalIsTranslation by mutableStateOf(false); private set

    /** Hat der Film eine eigene Untertitelspur, die sich übersetzen lässt? (Menü: wie die Übersetzung heißt.) */
    val hasTranslatableTrack: Boolean get() = bestTranslationSource(allowForced = false) != null

    /** Name des Übersetzers für das Menü („DeepL“, „DnA-Übersetzer“ …). */
    val translatorName: String get() = Translators.from(settings)?.name ?: "Übersetzer"

    /** Im Menü gewählt: OpenSubtitles ([translate] = false) oder Übersetzung der Spur; je Film bzw. Serie gemerkt. */
    fun chooseSubtitleSource(language: String, translate: Boolean) {
        autoSwitched = null
        loadSubtitles(language, if (translate) PlayerMemory.SOURCE_TRANSLATE else PlayerMemory.SOURCE_FILE)
    }

    /** Erst grob (Versatz und Bildrate), dann Zeile für Zeile. */
    private fun alignTo(references: List<Long>, starts: List<Long>): Pair<SubtitleTiming?, SubtitleAlign.Result?> {
        val estimated = SubtitleSync.estimate(references, starts)?.timing
        val global = estimated ?: subtitleTiming.takeIf { subtitleAutoSynced }
        return estimated to SubtitleAlign.align(references, starts, global?.ratio ?: 1.0, global?.offsetMs ?: 0L)
    }

    /** Bis zu zwei weitere Dateien derselben Suche laden und an der Spur messen; true, wenn eine übernommen wurde. */
    private suspend fun compareCandidates(language: String, references: List<Long>, current: Int): Boolean {
        val request = vod ?: return false
        val wanted = de.ayutv.data.repo.OpenSubtitlesQuota.candidatesPerFilm - 1
        if (wanted <= 0 || externalLanguage != language || !container.subtitles.hasAlternatives(request.key, language)) return false
        speechSyncInfo = "Vergleiche weitere Untertitel-Dateien mit der Spur des Films …"
        // Alle weiteren Treffer gleichzeitig laden und gleichzeitig vergleichen (früher eine nach der anderen).
        val alternatives = container.subtitles.downloadAlternatives(request.key, language, settings, filmInfo(), wanted)
        if (alternatives.isEmpty()) return false
        class Scored(val alternative: de.ayutv.data.repo.SubtitleRepository.Alternative, val cues: List<TimedCue>,
                     val global: SubtitleTiming?, val result: SubtitleAlign.Result?, val percent: Int)
        val scored = withContext(Dispatchers.Default) {
            alternatives.map { alt ->
                val cues = parseCues(alt.file)
                val (global, result) = alignTo(references, cues.map { it.start })
                // Ohne Zeilen-Abgleich (noch wenige Einblendungen) zählt die Gesamtlage – sonst stand jede Datei bei 0 %.
                val percent = result?.percent ?: global?.let { SubtitleSync.coverage(references, cues.map { c -> c.start }, it) } ?: 0
                Scored(alt, cues, global, result, percent)
            }
        }
        scored.forEach { subLog("Weitere Datei „${it.alternative.release}“ passt zu ${it.percent} % (bisher $current %)") }
        val best = scored.filter { it.result != null && it.percent >= ELASTIC_MIN_PERCENT && it.percent >= current + 10 }
            .maxByOrNull { it.percent }
        scored.filter { it !== best }.forEach { container.subtitles.dropAlternative(it.alternative) }
        if (best == null || externalLanguage != language) {
            best?.let { container.subtitles.dropAlternative(it.alternative) }
            return false
        }
        val file = container.subtitles.adoptAlternative(request.key, language, best.alternative)
        subtitleFiles[language] = file
        beginExternal(language, best.cues, null)
        elasticTiming = best.result!!.timing
        best.global?.let { subtitleTiming = it }
        matchPercent = best.percent
        syncedByReference = true
        subtitleAutoSynced = true
        subtitleStatus = "Andere Untertitel-Datei passt besser (${best.percent} % statt $current %) – übernommen und Zeile für Zeile ausgerichtet"
        return true
    }

    /** Untertitel früher (negativ) oder später (positiv) einblenden; wird pro Film gemerkt. */
    fun shiftSubtitles(deltaMs: Long) {
        subtitleDelayMs += deltaMs
        subtitleStatus = "Untertitel ${formatShift(subtitleDelayMs + subtitleTiming.offsetMs)}"
        saveTiming()
    }

    /** Fortschritt der vorausschauenden Übersetzung einer Untertitel-Datei (null = keine läuft). */
    var fileTranslationInfo by mutableStateOf<String?>(null); private set

    /** Gesamtverschiebung der geladenen Untertitel gegenüber der Datei, für die Anzeige. */
    val subtitleShiftLabel: String get() = formatShift(subtitleTiming.offsetMs + subtitleDelayMs) +
        if (subtitleTiming.ratio != 1.0) ", Bildrate korrigiert" else ""

    /**
     * Wie sicher die Untertitel gerade zum Bild passen, als kurze Zeile für die Info-Leiste (null: keine
     * Untertitel von außen und keine Übersetzung).
     */
    val syncBadge: String? get() {
        if (externalLanguage == null) return if (liveTranslateTarget != null) "✓ Untertitel zeitgenau (Spur des Films übersetzt)" else null
        val info = speechSyncInfo
        return when {
            manualSync -> "✓ Untertitel von Hand ausgerichtet ($subtitleShiftLabel)"
            elasticTiming != null -> "✓ Untertitel Zeile für Zeile an der Spur des Films ausgerichtet" + (matchPercent?.let { " · passt $it %" } ?: "")
            syncedByReference -> "✓ Untertitel an der Spur des Films ausgerichtet"
            externalExact -> "✓ Untertitel passen genau zu dieser Datei"
            subtitleAutoSynced && syncedBySpeech -> "✓ Untertitel am Ton ausgerichtet ($subtitleShiftLabel)"
            subtitleAutoSynced -> "✓ Untertitel: gespeicherte Ausrichtung ($subtitleShiftLabel)"
            else -> "◌ Untertitel noch nicht geprüft" + (info?.let { " – $it" } ?: "") + ". Passt es nicht: ☰ → Untertitel-Zeit einstellen"
        }
    }

    /** Untertitel-Zeit von Hand einstellen (Feld im Menü, schließt nicht von selbst). */
    var syncPanel by mutableStateOf(false); private set

    /**
     * Ausrichten mit einem Druck: unten steht groß die Zeile, die als Nächstes kommt. Sobald man sie hört, OK.
     * ◀ ▶ nimmt die vorige/nächste Zeile, ▲ ▼ schiebt um eine halbe Sekunde. Kein Suchen in einer Liste.
     */
    var syncCandidate by mutableStateOf(-1); private set
    /** Rückmeldung nach dem Druck („✓ ausgerichtet …“), sonst null. */
    var syncConfirmed by mutableStateOf<String?>(null); private set
    private var syncCloseJob: Job? = null

    fun openSyncPanel() {
        closeOverlay()
        syncCandidate = nextCueIndex()
        syncConfirmed = null
        syncPanel = true
    }

    fun closeSyncPanel() {
        syncCloseJob?.cancel()
        syncPanel = false
        syncConfirmed = null
        overlayToken++
    }

    /** Die Zeile, die nach der jetzigen Ausrichtung als Nächstes beginnt. */
    private fun nextCueIndex(): Int {
        val at = subtitleTimeAt(player?.currentPosition ?: 0L)
        val index = externalCues.indexOfFirst { it.start >= at - 500 }
        return if (index < 0) externalCues.lastIndex else index
    }

    /** Beginn der angezeigten Zeile in der Datei (für Tests). */
    val syncCandidateStart: Long? get() = externalCues.getOrNull(syncCandidate)?.start

    fun syncCue(index: Int): String? = externalCues.getOrNull(index)?.let { cue ->
        (externalTexts?.get(index) ?: cue.text).replace('\n', ' ')
    }

    fun syncStep(delta: Int) {
        if (externalCues.isEmpty()) return
        syncCloseJob?.cancel()
        syncConfirmed = null
        syncCandidate = (syncCandidate + delta).coerceIn(0, externalCues.lastIndex)
    }

    /** Die angezeigte Zeile wird gerade gesprochen. */
    fun syncConfirm() {
        val cue = externalCues.getOrNull(syncCandidate) ?: return
        anchorSubtitle(cue.start)
        syncConfirmed = "✓ Ausgerichtet ($subtitleShiftLabel). Passt es noch nicht ganz: in ein paar Minuten einfach noch eine Zeile bestätigen."
        syncCandidate = nextCueIndex()
        syncCloseJob?.cancel()
        syncCloseJob = lifecycleScope.launch {
            delay(5_000)
            closeSyncPanel()
        }
    }

    /** Tasten, solange die Ausrichtungsleiste offen ist. OK wirkt schon beim Drücken (genauer). */
    private fun handleSyncKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) syncConfirm()
            KeyEvent.KEYCODE_DPAD_LEFT -> syncStep(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> syncStep(+1)
            KeyEvent.KEYCODE_DPAD_UP -> { syncCloseJob?.cancel(); shiftSubtitles(-500) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { syncCloseJob?.cancel(); shiftSubtitles(500) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.let { it.playWhenReady = !it.playWhenReady }
            else -> return false
        }
        return true
    }

    class SyncLine(val start: Long, val text: String, val upcoming: Boolean)

    /** Zeilen der geladenen Datei rund um die jetzige Stelle, zum Antippen, sobald eine gesprochen wird. */
    fun syncLines(): List<SyncLine> {
        val at = subtitleTimeAt(player?.currentPosition ?: 0L)
        return externalCues.filter { it.start in at - SYNC_LINES_BACK_MS..at + SYNC_LINES_AHEAD_MS }
            .map { SyncLine(it.start, it.text.replace('\n', ' '), it.start >= at) }
    }

    /** Zuletzt von Hand festgelegte Zeile (Filmzeit, Zeit in der Datei), für die Bildraten-Korrektur aus zwei Punkten. */
    private var manualAnchor: Pair<Long, Long>? = null
    /** Von Hand an einer Zeile ausgerichtet: Die automatische Ausrichtung überschreibt das nicht mehr. */
    private var manualSync = false
    /** Position nach einem großen Sprung, ab der einmal neu am Ton geprüft wird (-1 = nichts zu prüfen). */
    private var seekRecheckFrom = -1L

    /**
     * Die Zeile mit Beginn [cueStart] wird gerade gesprochen: so verschieben, dass sie genau jetzt beginnt.
     * Ein Druck genügt, egal wie weit die Untertitel daneben lagen. Ein zweiter Druck mindestens drei
     * Minuten später erkennt auch eine falsche Bildrate (Untertitel laufen langsam auseinander).
     */
    fun anchorSubtitle(cueStart: Long) {
        val position = (player?.currentPosition ?: return) - REACTION_MS
        val previous = manualAnchor
        val ratio = previous?.takeIf { abs(cueStart - it.second) >= 3 * 60_000L }?.let { (video, subtitle) ->
            val raw = (position - video).toDouble() / (cueStart - subtitle)
            SYNC_RATIOS.minByOrNull { abs(it - raw) }?.takeIf { abs(it - raw) / it < 0.003 }
        }
        val elastic = elasticTiming
        if (elastic != null) {
            // Zeile für Zeile ausgerichtet: nur den Rest von Hand ausgleichen, die Ausrichtung bleibt.
            subtitleDelayMs = position - elastic.toVideo(cueStart)
        } else if (ratio != null && ratio != subtitleTiming.ratio) {
            subtitleTiming = SubtitleTiming(ratio, position - (cueStart * ratio).toLong())
            subtitleDelayMs = 0
        } else {
            subtitleDelayMs = position - subtitleTiming.toVideo(cueStart)
        }
        manualAnchor = position to cueStart
        manualSync = true
        subtitleAutoSynced = true
        speechSyncInfo = "Von Hand ausgerichtet"
        subtitleStatus = "Untertitel ausgerichtet ($subtitleShiftLabel)"
        saveTiming()
    }

    private fun saveTiming() {
        val request = vod ?: return
        val language = externalLanguage ?: return
        val saved = SubtitleRepository.SavedTiming(subtitleTiming.ratio, subtitleTiming.offsetMs, subtitleDelayMs)
        container.scope.launch(Dispatchers.IO) { container.subtitles.saveTiming(request.key, language, saved) }
    }

    /**
     * Eine eingebettete Spur unsichtbar mitlaufen lassen (Bild-Untertitel genügen): Ihre Einblendungen
     * zeigen, wann im Film gesprochen wird. Vollständige Spuren vor erzwungenen.
     */
    private fun selectSyncReference() {
        val p = player ?: return
        val candidates = textTracks().sortedBy { (g, i) -> if (g.getTrackFormat(i).isForcedSubtitle()) 1 else 0 }
        val (group, index) = candidates.firstOrNull() ?: return
        if (group.isTrackSelected(index)) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun recordReference(cueGroup: CueGroup) {
        val showing = cueGroup.cues.isNotEmpty()
        val begins = showing && !referenceShowing
        referenceShowing = showing
        val p = player ?: return
        if (!begins || SystemClock.elapsedRealtime() < ignoreReferenceUntil) return
        val position = p.currentPosition
        val time = (cueGroup.presentationTimeUs / 1000).takeIf { cueGroup.presentationTimeUs != C.TIME_UNSET && abs(it - position) < 3_000 } ?: position
        if (referenceStarts.isNotEmpty() && abs(referenceStarts.last() - time) < 300) return
        referenceStarts += time
        if (referenceStarts.size > 400) referenceStarts.removeAt(0)
        if (referenceStarts.size < SubtitleSync.MIN_REFERENCES || syncJob?.isActive == true) return
        // Schon Zeile für Zeile ausgerichtet: genauer als der eine Gesamtversatz hier.
        if (elasticTiming != null) return
        val references = referenceStarts.toList()
        val starts = externalCues.map { it.start }
        syncJob = lifecycleScope.launch {
            val fit = withContext(Dispatchers.Default) { SubtitleSync.estimate(references, starts) }
            if (externalLanguage == null) return@launch
            if (fit == null) {
                // Nach vielen Einblendungen immer noch kein gemeinsamer Takt: Die Datei gehört zu einem
                // anderen Film (oder einer ganz anderen Fassung). Verwerfen und den nächsten Treffer laden.
                // Der Zeilen-Abgleich hat noch nicht entschieden oder findet einen Teil wieder: nicht verwerfen.
                if (references.size >= WRONG_FILE_REFERENCES && !subtitleAutoSynced && externalTexts == null && (matchPercent ?: 0) < 30) replaceWrongSubtitles()
                return@launch
            }
            syncedByReference = true
            applyFit(fit.timing, bySpeech = false, toleranceMs = 150)
        }
    }

    /** Automatisch gefundene Lage übernehmen, wenn sie spürbar von der jetzigen abweicht. */
    private fun applyFit(timing: SubtitleTiming, bySpeech: Boolean, toleranceMs: Long, afterSeek: Boolean = false) {
        // Von Hand an einer Zeile ausgerichtet: Das ist genauer als jede Schätzung, nicht wieder verstellen –
        // außer nach einem großen Sprung, wenn der Ton dort sicher einen anderen Versatz zeigt (gekürzte Fassung).
        if (manualSync && !afterSeek) return
        if (afterSeek) {
            val here = subtitleTimeAt(player?.currentPosition ?: 0L)
            val jump = abs(timing.toVideo(here) - subtitleTiming.toVideo(here) - subtitleDelayMs)
            if (jump < 1_000) return
            subLog("Nach dem Spulen neu ausgerichtet: $timing (Abweichung $jump ms)")
            subtitleDelayMs = 0L
            subtitleTiming = timing
            subtitleAutoSynced = true
            syncedBySpeech = bySpeech
            subtitleStatus = "Nach dem Spulen am Ton nachgeprüft und neu ausgerichtet (${formatShift(timing.offsetMs)})"
            saveTiming()
            return
        }
        // Von Hand verschoben, bevor überhaupt automatisch ausgerichtet war: Das war der Versuch, denselben
        // Versatz auszugleichen, den die Ausrichtung jetzt genau kennt.
        if (!subtitleAutoSynced) subtitleDelayMs = 0L
        val here = subtitleTimeAt(player?.currentPosition ?: 0L)
        val jump = abs(timing.toVideo(here) - subtitleTiming.toVideo(here))
        if (jump < toleranceMs && subtitleAutoSynced) return
        val first = !subtitleAutoSynced || syncedBySpeech != bySpeech
        subtitleTiming = timing
        subtitleAutoSynced = true
        syncedBySpeech = bySpeech
        if (first || jump >= 1_000) {
            subtitleStatus = (if (bySpeech) "Untertitel am Ton ausgerichtet (" else "Untertitel automatisch angepasst (") +
                formatShift(timing.offsetMs + subtitleDelayMs) + (if (timing.ratio != 1.0) ", Bildrate korrigiert" else "") + ")"
        }
        saveTiming()
    }

    /**
     * Ohne eingebettete Untertitel als Taktgeber: die geladenen Untertitel am gesprochenen Ton ausrichten.
     * Alle 20 s mit den letzten 15 Minuten Ton (erstmals nach 2 Minuten); so folgt die Ausrichtung auch gekürzten Fassungen, in
     * denen der Versatz mitten im Film springt. Wird der Ton durchgereicht (Dolby an Soundbar), hört die
     * App nichts mit: dann einmal auf Dekodieren im Gerät umstellen.
     */
    private fun startSpeechSync() {
        speechJob?.cancel()
        speechStateLogged = false
        if (vod == null) return
        speechJob = lifecycleScope.launch {
            var lastHeard = -1
            var playedMs = 0L
            while (isActive && externalLanguage != null) {
                // Bis zum ersten Treffer alle 5 s prüfen (so früh wie möglich), danach alle 20 s nachprüfen.
                val step = if (subtitleAutoSynced) SPEECH_CHECK_MS else SPEECH_FIRST_CHECK_MS
                delay(step)
                updateAudioAhead()
                val p = player ?: continue
                if (!p.isPlaying) continue
                playedMs += step
                if (syncedByReference) {
                    speechSyncInfo = "An den eingebetteten Untertiteln des Films ausgerichtet"
                    continue
                }
                val heard = speechSeconds()
                // Für den Fehlerbericht einmal festhalten, ob überhaupt Ton zum Ausrichten ankommt.
                if (!speechStateLogged && playedMs >= 10_000) {
                    speechStateLogged = true
                    subLog("Ausrichtung: nach ${playedMs / 1000} s ${heardSpeech.seconds()} s Ton gehört, ${aheadSpeech.seconds()} s nebenbei ausgewertet" +
                        " (Ton ${if (dna?.audioPassthrough == true) "an Soundbar/Receiver durchgereicht" else "im Gerät"}, Datei per Hash: $externalExact)")
                }
                if (heard < 5) {
                    // Ton geht an Soundbar/Receiver: Nur umstellen, wenn die Untertitel nicht schon passen
                    // (Datei-Hash oder gespeicherte Ausrichtung) – sonst bliebe der Raumklang ohne Not weg.
                    val needed = !subtitleAutoSynced && !externalExact
                    // Erst wenn auch die Auswertung nebenbei nichts liefert (Tonformat ohne Decoder im Gerät).
                    if (playedMs >= SPEECH_PCM_FALLBACK_MS && needed && dna?.audioPassthrough == true && textTracks().isEmpty() && !forcePcmActive && !pcmForSpeechTried) {
                        pcmForSpeechTried = true
                        subLog("Ton wird durchgereicht, dekodiere im Gerät für die Ausrichtung am Ton")
                        subtitleStatus = "Ton wird im Gerät dekodiert, damit sich die Untertitel am Gespräch ausrichten …"
                        rebuildPlayer(forcePcm = true)
                    }
                    speechSyncInfo = when {
                        externalExact -> "Untertitel passen zur Datei (keine Ausrichtung nötig)"
                        playedMs < SPEECH_PCM_FALLBACK_MS -> "Ausrichtung am Ton startet …"
                        else -> "Kein Ton zum Mithören (Ton geht direkt an Soundbar/Receiver)"
                    }
                    continue
                }
                if (heard < SpeechSync.MIN_SECONDS) {
                    if (!subtitleAutoSynced) speechSyncInfo = "Hört zu: ${heard} von ${SpeechSync.MIN_SECONDS} Sekunden Ton"
                    continue
                }
                if (heard == lastHeard) continue
                lastHeard = heard
                val position = p.currentPosition
                val cues = externalCues.map { it.start to it.end }
                // Nach einem großen Sprung: einmal nur mit dem Ton ab der neuen Stelle prüfen (gekürzte Fassungen haben
                // dort oft einen anderen Versatz), sobald davon genug gehört ist.
                val seekFrom = seekRecheckFrom
                val afterSeek = seekFrom >= 0 && position - seekFrom >= SEEK_RECHECK_MS
                if (seekFrom >= 0 && !afterSeek && manualSync) continue
                val (fit, window, signal) = withContext(Dispatchers.Default) {
                    // Bis 160 s voraus: Dort liegt der schon geladene, nebenbei dekodierte Ton.
                    val signal = speechSignal(if (afterSeek) seekFrom else position - SPEECH_WINDOW_MS, position + SPEECH_AHEAD_MS)
                    Triple(SpeechSync.estimate(signal, cues), signal.count { !it.isNaN() } / 10, signal)
                }
                // Abschnittsweise am Ton (gekürzte Fassung, fehlende Szene, Werbepause): Sprach-Einsätze als Anker.
                if (!afterSeek && !syncedByReference && !manualSync && (elasticTiming == null || elasticFromSpeech)) {
                    if (alignBySpeechOnsets(signal, cues, fit)) continue
                }
                if (externalLanguage == null || syncedByReference) continue
                if (afterSeek) seekRecheckFrom = -1L
                if (afterSeek && fit != null && fit.votes >= SEEK_RECHECK_VOTES) {
                    applyFit(fit.timing, bySpeech = true, toleranceMs = 250, afterSeek = true)
                    speechSyncInfo = "Nach dem Spulen am Ton nachgeprüft (${clock(position)})"
                    continue
                }
                if (fit != null) {
                    // „passt X %“ auch ohne eingebettete Untertitel: Satzanfänge im Ton mit passender Zeile.
                    val percent = withContext(Dispatchers.Default) { SpeechSync.matchPercent(signal, cues, fit.timing) }
                    if (percent != null && !syncedByReference) matchPercent = percent
                    subLog("Am Ton ausgerichtet: ${fit.timing} (Güte ${fit.votes / 10.0}, ${window} s Ton, passt ${percent ?: "?"} %)")
                    applyFit(fit.timing, bySpeech = true, toleranceMs = 250)
                    val certainty = when {
                        fit.votes >= 100 -> "sehr sicher"
                        fit.votes >= 80 -> "sicher"
                        else -> "ausreichend sicher"
                    }
                    speechSyncInfo = "Am Ton ausgerichtet ($certainty), zuletzt geprüft bei ${clock(position)}"
                } else {
                    speechSyncInfo = if (subtitleAutoSynced) {
                        "Ausgerichtet; an dieser Stelle (${clock(position)}) kein klarer Takt, prüft weiter"
                    } else {
                        "${window / 60} min Ton geprüft, noch kein eindeutiger Takt – prüft weiter"
                    }
                }
                // 1,5 Minuten Gespräch und kein Takt: weitere Treffer der Suche laden und vergleichen; ist noch keine klar
                // vorn, mit mehr Ton nach 3 und 5 Minuten noch einmal (ohne neue Downloads).
                val compareDue = COMPARE_ROUNDS_SECONDS.getOrNull(compareRounds)
                if (fit == null && compareDue != null && window >= compareDue && !subtitleAutoSynced && externalTexts == null) {
                    compareRounds++
                    if (tryAlternative(position)) return@launch
                }
                if (fit == null && window >= SPEECH_WRONG_FILE_SECONDS && !subtitleAutoSynced && externalTexts == null) {
                    // Acht Minuten Gespräch und kein gemeinsamer Takt, auch keine andere Datei: gehört zu einem anderen Film.
                    replaceWrongSubtitles()
                    return@launch
                }
            }
        }
    }

    /** Der Zeilen-Abgleich stammt aus dem Ton (nicht aus eingebetteten Untertiteln). */
    private var elasticFromSpeech = false

    /**
     * Ausrichtung Abschnitt für Abschnitt am Ton (Idee wie alass): Stellen, an denen nach einer Pause gesprochen wird,
     * sind Anker für den Zeilen-Abgleich. Übernommen nur, wenn die Datei damit gut passt und sie sich wirklich
     * abschnittsweise verschiebt (sonst genügt die einfache Ausrichtung) – oder wenn es gar keine eindeutige Gesamtlage
     * gibt, weil der Versatz mitten im Film springt. true = übernommen.
     */
    private suspend fun alignBySpeechOnsets(signal: FloatArray, cues: List<Pair<Long, Long>>, fit: SubtitleSync.Fit?): Boolean {
        val starts = cues.map { it.first }
        val result = withContext(Dispatchers.Default) {
            val onsets = SpeechSync.onsets(signal)
            if (onsets.size < SubtitleAlign.MIN_REFERENCES * 2) return@withContext null
            val center = fit?.timing ?: SpeechSync.evaluate(signal, cues)?.takeIf { it.z >= SPEECH_ELASTIC_MIN_Z }?.timing ?: return@withContext null
            SubtitleAlign.align(onsets, starts, center.ratio, center.offsetMs)?.let { it to center }
        } ?: return false
        val (aligned, center) = result
        if (aligned.percent < SPEECH_ELASTIC_PERCENT) return false
        // Springt der Versatz wirklich? Mindestens jede zehnte Zeile mehr als 1,5 s von der Gesamtlage entfernt.
        val moved = starts.count { abs(aligned.timing.toVideo(it) - center.toVideo(it)) > 1_500 }
        if (fit != null && moved < starts.size / 10) return false
        val first = elasticTiming == null
        elasticTiming = aligned.timing
        elasticFromSpeech = true
        subtitleTiming = center
        subtitleAutoSynced = true
        syncedBySpeech = true
        matchPercent = aligned.percent
        speechSyncInfo = "Abschnittsweise am Ton ausgerichtet (Fassung mit Schnitten, passt ${aligned.percent} %)"
        if (first) {
            subLog("Abschnittsweise am Ton ausgerichtet: ${aligned.matched} Anker, ${aligned.percent} %, $moved Zeilen verschoben")
            subtitleStatus = "Untertitel abschnittsweise am Ton ausgerichtet (gekürzte Fassung erkannt)"
        }
        saveTiming()
        return true
    }

    private var speechStateLogged = false

    private var replacedSubtitles = 0
    /** Für die geladene Datei schon so oft weitere Treffer am Ton verglichen ([COMPARE_ROUNDS_SECONDS]). */
    private var compareRounds = 0
    /** Zum Vergleich geladene weitere Dateien (bleiben für die nächste Runde, bis eine übernommen oder die Datei gewechselt wird). */
    private val comparedAlternatives = ArrayList<Pair<SubtitleRepository.Alternative, List<TimedCue>>>()

    private fun dropComparedAlternatives() {
        comparedAlternatives.forEach { container.subtitles.dropAlternative(it.first) }
        comparedAlternatives.clear()
    }

    /**
     * Die geladene Datei findet am Ton keinen Takt: weitere Treffer derselben Suche laden (so viele, wie das Kontingent
     * hergibt) und alle mit demselben Ton vergleichen. Die passende Datei liegt nach 1,5 Minuten Gespräch klar vor
     * Dateien anderer Fassungen oder Filme ([SpeechSync.pickBest]); sie wird übernommen und gleich ausgerichtet (true).
     * Liegt keine klar vorn, bleibt alles, wie es ist. Ersetzt den Griff zu „Andere Untertitel-Datei laden“.
     */
    private suspend fun tryAlternative(position: Long): Boolean {
        val request = vod ?: return false
        val language = externalLanguage ?: return false
        val alternatives = comparedAlternatives
        if (alternatives.isEmpty() && !container.subtitles.hasAlternatives(request.key, language)) return false
        speechSyncInfo = "Kein Takt gefunden, vergleiche weitere Untertitel-Dateien am Ton …"
        val signal = withContext(Dispatchers.Default) { speechSignal(position - SPEECH_WINDOW_MS, position + SPEECH_AHEAD_MS) }
        val currentCues = externalCues.map { it.start to it.end }
        val current = withContext(Dispatchers.Default) { SpeechSync.evaluate(signal, currentCues) }
        val evaluations = arrayListOf(current)
        // Schon geladene (aus der letzten Runde) mit dem jetzt längeren Ton neu bewerten.
        for ((_, cues) in alternatives) evaluations += withContext(Dispatchers.Default) { SpeechSync.evaluate(signal, cues.map { it.start to it.end }) }
        val limit = (de.ayutv.data.repo.OpenSubtitlesQuota.candidatesPerFilm - 1).coerceAtLeast(1)
        // Noch keine klar vorn: alle fehlenden weiteren Treffer gleichzeitig laden und bewerten (früher nacheinander).
        if (alternatives.size < limit && SpeechSync.pickBest(evaluations) == null && externalLanguage == language && !subtitleAutoSynced) {
            val loaded = container.subtitles.downloadAlternatives(request.key, language, settings, filmInfo(), limit - alternatives.size)
            val parsed = withContext(Dispatchers.Default) {
                loaded.map { alt ->
                    val cues = parseCues(alt.file)
                    Triple(alt, cues, SpeechSync.evaluate(signal, cues.map { it.start to it.end }))
                }
            }
            for ((alternative, cues, evaluation) in parsed) {
                alternatives += alternative to cues
                evaluations += evaluation
                subLog("Vergleich: „${alternative.release}“ Güte ${evaluation?.z?.let { "%.1f".format(Locale.ROOT, it) } ?: "–"}" +
                    " (geladene Datei ${current?.z?.let { "%.1f".format(Locale.ROOT, it) } ?: "–"})")
            }
        }
        val best = SpeechSync.pickBest(evaluations)
        val compared = alternatives.size
        // Während des Vergleichs eine andere Datei gewählt (Liste geleert): nichts übernehmen.
        if (alternatives.size != evaluations.size - 1) return false
        if (externalLanguage != language || subtitleAutoSynced || best == null || best == 0) {
            // Unklar: Die geladenen Dateien bleiben für die nächste Runde mit mehr Ton. Sonst verwerfen.
            if (best != null || externalLanguage != language || subtitleAutoSynced) dropComparedAlternatives()
            if (best == 0 && current != null && externalLanguage == language && !subtitleAutoSynced) {
                // Die geladene Datei liegt im Vergleich klar vorn: ihre Lage übernehmen.
                applyFit(current.timing, bySpeech = true, toleranceMs = 0)
                speechSyncInfo = "Am Ton ausgerichtet (im Vergleich mit $compared weiteren Dateien)"
                // false: Die Prüfung am Ton läuft für diese Datei weiter (nachprüfen, nach dem Spulen).
                return false
            }
            subLog("Keine der $compared weiteren Dateien passt klar besser")
            return false
        }
        val (alternative, cues) = alternatives.removeAt(best - 1)
        val fit = evaluations[best]!!
        dropComparedAlternatives()
        subLog("Weitere Datei „${alternative.release}“ passt zum Ton: ${fit.timing} (Güte ${"%.1f".format(Locale.ROOT, fit.z)})")
        val file = container.subtitles.adoptAlternative(request.key, language, alternative)
        subtitleFiles[language] = file
        beginExternal(language, cues, null)
        applyFit(fit.timing, bySpeech = true, toleranceMs = 0)
        subtitleStatus = "Eine andere Untertitel-Datei passt zum Ton: übernommen und ausgerichtet ($subtitleShiftLabel)"
        return true
    }

    /** Von Hand aus dem Menü: Die eingeblendeten Untertitel gehören nicht zu diesem Film. */
    fun reportWrongSubtitles() {
        closeOverlay()
        replaceWrongSubtitles()
    }

    private fun replaceWrongSubtitles() {
        val request = vod ?: return
        val language = externalLanguage ?: return
        if (replacedSubtitles >= 3) {
            subtitleStatus = "Keine passenden Untertitel gefunden. Zeit im Menü unter „Untertitel-Zeit“ selbst einstellen"
            return
        }
        replacedSubtitles++
        subLog("Untertitel $language passen nicht zum Film, suche andere")
        container.subtitles.markWrong(request.key, language)
        subtitleFiles.remove(language)
        loadedLanguages = subtitleFiles.keys.toList()
        stopLiveTranslation()
        subtitleStatus = "Diese Untertitel gehören zu einem anderen Film. Suche passende …"
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            var problem: String? = null
            val found = runCatching { container.subtitles.find(request.key, request.url, request.title, language, settings, filmInfo()) }
                .onFailure { Log.w(SUBTITLE_TAG, "Suche fehlgeschlagen", it); problem = describeOnlineProblem(it) }.getOrNull()
            if (found != null) {
                attachLoaded(language, found)
                return@launch
            }
            // Keine weitere Datei in der Sprache (bei Indonesisch & Co. gibt es oft nur eine): eine Datei in anderer
            // Sprache (meist Englisch, davon gibt es viele, sie passen oft zur Fassung) übersetzen – mit DeepL bzw. Gerät.
            if (settings.translationEnabled) {
                de.ayutv.data.repo.SubtitleJournal.add("Keine weitere Datei in $language – übersetze eine Vorlage")
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
                }.onFailure { problem = describeOnlineProblem(it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    return@launch
                }
            }
            subtitleStatus = problem ?: container.subtitles.lastProblem ?: "Keine passenden Untertitel in ${Languages.label(language)} gefunden" +
                if (!settings.translationEnabled) " (Übersetzung ist in den Einstellungen aus)" else ""
        }
    }

    /** Letzter Grund, warum Untertitel aus dem Internet nicht geladen werden konnten (steht im Untertitel-Menü). */
    var lastOnlineProblem by mutableStateOf<String?>(null); private set

    /** Fehler von OpenSubtitles verständlich machen, vor allem das Tageslimit. */
    /** Untertitel-Ereignis ins Protokoll und in den Fehlerbericht. */
    private fun subLog(message: String) {
        Log.i(SUBTITLE_TAG, message)
        de.ayutv.data.repo.SubtitleJournal.add(message)
    }

    private fun describeOnlineProblem(error: Throwable): String {
        val text = error.message.orEmpty()
        Log.w(SUBTITLE_TAG, "OpenSubtitles: $text")
        return (if ("allowed" in text && ("subtitles" in text || "download" in text)) {
            "OpenSubtitles-Tageslimit erreicht (ohne Konto 5, mit kostenlosem Konto 20 Downloads pro Tag). " +
                "Konto eintragen: Einstellungen → Einrichtung per Handy"
        } else {
            "OpenSubtitles: ${text.ifEmpty { error.javaClass.simpleName }}"
        }).also { lastOnlineProblem = "$it (Originalmeldung: ${text.take(160)})" }
    }

    private fun formatShift(ms: Long): String = String.format(Locale.GERMAN, "%+.1f s", ms / 1000f)


    private fun onCuesChanged(cueGroup: CueGroup) {
        if (externalLanguage != null) {
            recordReference(cueGroup)
            return
        }
        val target = liveTranslateTarget ?: return
        // Mitgelesene Spur: Anzeige und Übersetzung laufen mit Vorlauf (startAhead).
        if (aheadFormat?.let { embedded?.hasEntries(it) } == true) return
        val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isEmpty()) {
            translatedCue = null
            return
        }
        cueTranslations[text]?.let {
            translatedCue = it
            return
        }
        // Bis die Übersetzung da ist (auf dem Gerät knapp eine Sekunde), das Original zeigen statt nichts.
        translatedCue = text
        cueJob?.cancel()
        cueJob = lifecycleScope.launch {
            val translator = Translators.from(settings) ?: return@launch
            val source = sourceFor(text)
            runCatching { translator.translate(listOf(text), target, source).first() }
                .onSuccess {
                    cueTranslations[text] = it
                    translatedCue = it
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    Log.w(SUBTITLE_TAG, "Live-Übersetzung fehlgeschlagen", it)
                    if (it is de.ayutv.data.translate.RemoteTranslator.NoAnswer) Translators.remote.abort(it.message.orEmpty())
                    stopLiveTranslation()
                    subtitleStatus = "Übersetzung nicht möglich: ${it.message ?: it.javaClass.simpleName}"
                }
        }
    }

    /**
     * Übersetzt eine Untertiteldatei vorausschauend: immer die Zeilen ab der aktuellen Stelle zuerst,
     * so dass sie fertig sind, bevor sie erscheinen (auch nach dem Spulen). Angezeigt wird nach der
     * Zeit der Datei. Ist alles übersetzt, wird die Datei gespeichert und steht beim nächsten Mal sofort bereit.
     */
    private fun startFileTranslation(request: VodRequest, target: String, file: java.io.File, sourceLanguage: String?) {
        stopLiveTranslation()
        val cues = parseCues(file)
        if (cues.isEmpty()) {
            subtitleStatus = "Die gefundenen Untertitel sind leer"
            return
        }
        val translator = Translators.from(settings) ?: return
        val translations = java.util.concurrent.atomic.AtomicReferenceArray<String>(cues.size)
        // In der Ruhezeit oder beim letzten Schauen schon übersetzte Zeilen gleich übernehmen.
        val partial = container.subtitles.lineTranslations(request.key, de.ayutv.data.repo.Pretranslation.PARTIAL_TRACK, target)
        cues.forEachIndexed { i, cue -> partial[cue.start]?.let { translations.set(i, it) } }
        // Wie geladene Untertitel einblenden (verschiebbar, automatisch ausgerichtet); fertig
        // übersetzte Zeilen ersetzen nach und nach das Original.
        beginExternal(target, cues, translations)
        subtitleStatus = "Übersetze ${sourceLanguage?.let { Languages.label(it) + " " }.orEmpty()}→ ${Languages.label(target)} mit ${translator.name}"
        fun indexAt(position: Long): Int {
            val at = subtitleTimeAt(position)
            val i = cues.indexOfFirst { it.end > at }
            return if (i < 0) cues.size else i
        }
        // Sätze über mehrere Einblendungen als Ganzes übersetzen, dann wieder aufteilen.
        val sentenceCues = cues.map { de.ayutv.data.translate.SubtitleSentences.Cue(it.start, it.end, it.text) }
        val groups = de.ayutv.data.translate.SubtitleSentences.groups(sentenceCues)
        val groupOf = IntArray(cues.size).also { index -> groups.forEachIndexed { g, range -> range.forEach { index[it] = g } } }
        // Namen, die zugleich Wörter sind („Will“, „Hope“), aus der ganzen Datei lernen und je Serie merken.
        val seriesKey = de.ayutv.data.parse.Episodes.seriesKeyOf(request.key) ?: request.key
        val names = de.ayutv.data.translate.SeriesNames(this)
            .add(seriesKey, de.ayutv.data.translate.TranslationNames.learn(cues.map { it.text }))
        val smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
        fileTranslationJob = lifecycleScope.launch {
            var done = 0
            fileTranslationInfo = "Übersetzung startet …"
            val batch = if (translator.name == de.ayutv.data.translate.OnDeviceTranslator.NAME) 3 else 40
            try {
                while (true) {
                    val from = indexAt(player?.currentPosition ?: 0L)
                    val next = (from until cues.size).firstOrNull { translations.get(it) == null }
                        ?: (0 until from).firstOrNull { translations.get(it) == null }
                        ?: break
                    val chosen = (groupOf[next] until minOf(groups.size, groupOf[next] + batch)).map { groups[it] }
                        .filter { range -> range.any { translations.get(it) == null } }
                    // Der Satz davor (falls schon übersetzt) als Zusammenhang für eine kurze erste Zeile.
                    val before = groups.getOrNull(groups.indexOf(chosen.first()) - 1)?.let { range ->
                        val done = range.map { translations.get(it) }
                        if (done.any { it == null }) null
                        else de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, range) to done.joinToString(" ") { it!!.replace('\n', ' ') }
                    }
                    val result = smart.translate(chosen.map { de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, it) }, target, sourceLanguage, before)
                    chosen.forEachIndexed { k, range ->
                        val sentence = result.getOrNull(k)
                        if (sentence == null) {
                            range.forEach { translations.set(it, cues[it].text) }
                            return@forEachIndexed
                        }
                        val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, range.map { cues[it].text.length })
                        range.forEachIndexed { j, index -> translations.set(index, de.ayutv.data.translate.SubtitleSentences.wrap(parts[j])) }
                    }
                    done += chosen.sumOf { it.count() }
                    // Wie weit die Übersetzung vor der aktuellen Stelle ist (im Untertitel-Menü zu sehen).
                    val ahead = (indexAt(player?.currentPosition ?: 0L) until cues.size).takeWhile { translations.get(it) != null }.count()
                    fileTranslationInfo = "Übersetzt: $done von ${cues.size} Zeilen · ab hier $ahead Zeilen fertig"
                }
                fileTranslationInfo = null
                val finished = cues.mapIndexed { i, cue -> cue.original.copy(text = translations.get(i) ?: cue.text) }
                val saved = withContext(Dispatchers.IO) { container.subtitles.saveTranslation(request.key, target, finished) }
                subtitleFiles[target] = saved
                loadedLanguages = subtitleFiles.keys.toList()
                subLog("Übersetzung von ${cues.size} Zeilen fertig und gespeichert")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Abgebrochen (Film beendet, andere Untertitel): Bisheriges für das nächste Mal aufheben.
                val lines = cues.indices.mapNotNull { i -> translations.get(i)?.let { cues[i].start to it } }.toMap()
                container.subtitles.saveLineTranslations(request.key, de.ayutv.data.repo.Pretranslation.PARTIAL_TRACK, target, lines)
                throw e
            } catch (e: Exception) {
                Log.w(SUBTITLE_TAG, "Übersetzung der Datei fehlgeschlagen", e)
                if (e is de.ayutv.data.translate.RemoteTranslator.NoAnswer) Translators.remote.abort(e.message.orEmpty())
                subtitleStatus = "Übersetzung nicht möglich: ${e.message ?: e.javaClass.simpleName}"
                stopLiveTranslation()
            }
        }
    }

    private class TimedCue(val start: Long, val end: Long, val text: String, val original: SrtCue)

    private fun srtMillis(value: String): Long? {
        val m = SRT_TIME.matchEntire(value.trim()) ?: return null
        val (h, min, sec, ms) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    // ---------------------------------------------------------------- Spuren (Audio/Untertitel)

    fun selectTrack(group: Tracks.Group, index: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .apply {
                // Gewählte Tonsprache auch beim nächsten Sender bevorzugen.
                if (group.type == C.TRACK_TYPE_AUDIO) group.getTrackFormat(index).language?.takeIf { it != "und" }
                    ?.let { setPreferredAudioLanguages(it, Locale.getDefault().language) }
            }
            .build()
        if (group.type == C.TRACK_TYPE_TEXT) {
            textDisabled = false
            stopLiveTranslation()
        }
        // Für diesen Sender / diese Serie merken.
        val language = group.getTrackFormat(index).language?.takeIf { it != "und" }
        if (language != null) memoryKey()?.let { key ->
            container.playerMemory.update(key) { if (group.type == C.TRACK_TYPE_AUDIO) it.copy(audio = language) else it.copy(text = language, load = null) }
        }
    }

    fun setTextEnabled(enabled: Boolean) {
        val p = player ?: return
        if (!enabled) memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(text = PlayerMemory.OFF, load = null) } }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .apply { if (!enabled) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .build()
        textDisabled = !enabled
        if (!enabled) stopLiveTranslation()
    }

    /** Player-Menü öffnen: Übersicht, oder gleich eine Unterseite (Untertitel-Taste → Untertitel). */
    fun openTracks(page: MenuPage? = null) {
        showOverlay(Overlay.TRACKS)
        menuReturn = null
        menuPage = page
    }

    /** Unterseite des Player-Menüs (null = Übersicht). */
    var menuPage by mutableStateOf<MenuPage?>(null); private set
    /** Von hier kam man zurück zur Übersicht: Dort steht der Fokus dann wieder auf dieser Zeile. */
    var menuReturn: MenuPage? = null; private set

    fun openMenuPage(page: MenuPage?) {
        if (page == null) menuReturn = menuPage
        menuPage = page
        overlayToken++
    }

    fun clearSubtitleStatus(shown: String) {
        if (subtitleStatus == shown) subtitleStatus = null
    }

    private fun saveProgress() {
        val request = vod ?: return
        // Archiv-Sendungen und der 4K-Test gehören nicht zu „Weiter schauen“.
        if (request.kind == HistoryKind.CATCHUP || UhdSelfTest.isTest(request.key)) return
        val p = player ?: return
        val position = p.currentPosition
        if (position < 5_000) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        container.scope.launch {
            container.content.recordProgress(
                HistoryEntity(
                    key = request.key,
                    kind = request.kind,
                    title = request.title,
                    subtitle = request.subtitle,
                    image = request.image,
                    url = request.url,
                    groupTitle = null,
                    positionMs = position,
                    durationMs = duration,
                    watchedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Spulen sammelt schnelle Tastendrücke und springt erst 300 ms nach dem letzten: Gedrückt halten
     * würde sonst 20 Sprünge pro Sekunde und je eine neue Server-Anfrage auslösen. Nie bis ganz ans
     * Ende, sonst wäre der Film sofort aus.
     */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val max = p.duration.takeIf { it != C.TIME_UNSET }?.let { (it - 2_000).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val base = if (pendingSeekMs != C.TIME_UNSET) pendingSeekMs else p.currentPosition
        pendingSeekMs = (base + deltaMs).coerceIn(0, max)
        positionMs = pendingSeekMs
        showOverlay(Overlay.CONTROLS)
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            delay(300)
            player?.seekTo(pendingSeekMs)
            pendingSeekMs = C.TIME_UNSET
        }
    }

    // ---------------------------------------------------------------- Anstoß-Hinweis

    /** Fällige Erinnerung im Bild (OK schaltet um, ⏩ als kleines Bild, Zurück blendet aus). */
    var dueReminder by mutableStateOf<de.ayutv.data.reminders.Reminder?>(null); private set
    private var reminderJob: Job? = null

    private fun showReminder(reminder: de.ayutv.data.reminders.Reminder) {
        dueReminder = reminder
        reminderJob?.cancel()
        reminderJob = lifecycleScope.launch {
            delay(REMINDER_VISIBLE_MS)
            dueReminder = null
        }
    }

    /** Taste, während der Hinweis zu sehen ist. true: verbraucht. */
    private fun handleReminderKey(code: Int): Boolean {
        val reminder = dueReminder ?: return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dueReminder = null
                lifecycleScope.launch {
                    val channel = container.content.channel(reminder.channelKey) ?: return@launch
                    if (isLive) playChannel(channel) else {
                        startActivity(liveIntent(this@PlayerActivity, channel.key, null))
                        finish()
                    }
                }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive) return false
                dueReminder = null
                lifecycleScope.launch { container.content.channel(reminder.channelKey)?.let(::startPip) }
            }
            KeyEvent.KEYCODE_BACK -> dueReminder = null
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- Auf dem Fernseher abspielen (DLNA)

    /** Fernseher (DLNA), auf dem gerade abgespielt wird (das Handy spielt dann nicht selbst). */
    private var castTargetState by mutableStateOf<de.ayutv.data.net.DlnaCast.Renderer?>(null)
    var castTarget: de.ayutv.data.net.DlnaCast.Renderer?
        get() = castTargetState
        private set(value) {
            castTargetState = value
            // Über den Player hinaus merken: Wählt man den nächsten Sender in der Senderliste der App (neuer Player),
            // läuft er gleich auf dem Fernseher weiter statt auf dem Handy.
            activeTv = value
        }

    /** Fernseher im WLAN suchen, die DLNA können (LG webOS, Samsung, Sony …). */
    private suspend fun discoverDlna(): List<de.ayutv.data.net.DlnaCast.Renderer> {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("dna-dlna")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        return try {
            runCatching { de.ayutv.data.net.DlnaCast.discover(container.http, 2_500) }.getOrDefault(emptyList())
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** Weiterleitung über das Handy: gehört dem [CastService] (läuft weiter, auch wenn der Player zu ist). */
    private val castRelay: de.ayutv.data.net.DlnaRelay? get() = CastService.relay

    private fun releaseCastRelay() {
        endShadow()
        if (CastService.relay != null) CastService.stopCasting(this, stopTv = false)
    }

    /** Liest der Player hier gerade ohne Bild und Ton mit (Untertitel beim Übertragen anpassen)? */
    private var shadowing = false

    private fun endShadow() {
        if (!shadowing) return
        shadowing = false
        player?.let { p ->
            p.stop()
            p.volume = 1f
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
        }
    }

    /**
     * Den laufenden Sender bzw. Film auf [renderer] abspielen. Erst direkt (der Fernseher holt den Stream selbst, das
     * Handy ist frei); prüft, ob er wirklich abspielt. Geht das nicht (Anbieter nur über den Proxy erreichbar, verlangt
     * die Kennung der App, Format), leitet das Handy den Stream im WLAN weiter.
     */
    /**
     * Weiterleitung über das Handy (Proxy der App): Das Handy darf in den Standby (Bildschirm aus), die Weiterleitung
     * läuft als Dienst weiter. Manche Handys (Samsung, Xiaomi …) beenden Apps im Standby trotzdem, um Akku zu sparen –
     * einmal anbieten, DnA-TV davon auszunehmen.
     */
    private fun offerStandby() {
        val power = getSystemService(android.os.PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            message = "Läuft auf dem Fernseher – du kannst das Handy jetzt sperren (Standby)"
            handler.postDelayed({ if (message?.startsWith("Läuft auf dem Fernseher") == true) message = null }, 6_000)
            return
        }
        val prefs = getSharedPreferences("cast", MODE_PRIVATE)
        if (prefs.getBoolean("standby_asked", false)) return
        prefs.edit().putBoolean("standby_asked", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Handy in den Standby?")
            .setMessage(
                "Der Fernseher bekommt den Stream über das Handy (wegen des Proxys). Du kannst das Handy sperren – " +
                    "damit Android DnA-TV dabei nicht zum Akkusparen beendet, in der nächsten Einstellung DnA-TV auf " +
                    "„Nicht optimieren“ bzw. „Uneingeschränkt“ stellen.",
            )
            .setPositiveButton("Einstellung öffnen") { _, _ ->
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    /**
     * Spielt das Handy selbst, darf keine Weiterleitung zu einem Fernseher mehr laufen (etwa von einem früheren Player
     * oder einem abgebrochenen Versuch): Sie hielte eine zweite Verbindung zum Anbieter offen, und bei Konten mit nur
     * einer Verbindung bräche das Bild ständig ab. Der Fernseher wird dabei angehalten.
     */
    private fun freeConnection() {
        if (CastService.relay == null) return
        Log.i(TAG, "Weiterleitung zum Fernseher beendet: das Handy spielt selbst (nur eine Verbindung)")
        CastService.stopCasting(this, stopTv = true)
        message = "Übertragung zum Fernseher beendet – nur eine Verbindung beim Anbieter"
        handler.postDelayed({ if (message?.startsWith("Übertragung zum Fernseher beendet") == true) message = null }, 5_000)
    }

    /** Gleich übertragen, ohne (noch einmal) auf die Anpassung zu warten. */
    private var castAfterSync = false

    /** Wartet gerade auf die Untertitel-Anpassung, bevor es auf den Fernseher geht? */
    private var syncBeforeCast: kotlinx.coroutines.Job? = null

    /** Sind geladene Untertitel schon an diesen Film angepasst (gemerkt, am Ton, an der Spur, Zeile für Zeile)? */
    private val subtitlesAligned: Boolean
        get() = externalCues.isEmpty() || externalExact || subtitleAutoSynced || syncedBySpeech || syncedByReference || elasticTiming != null

    fun castTo(renderer: de.ayutv.data.net.DlnaCast.Renderer, switching: Boolean = false) {
        // Film mit geladenen Untertiteln, noch nicht angepasst: erst hier fertig anpassen (der Film läuft so lange
        // normal auf dem Handy weiter, höchstens 30 s), dann übertragen – mit der richtigen Zeitlage. So muss beim
        // Übertragen nichts parallel mitlaufen, und der Anbieter sieht nur die eine Verbindung des Fernsehers.
        if (!switching && vod != null && !subtitlesAligned && syncBeforeCast == null && !castAfterSync) {
            closeOverlay()
            syncBeforeCast = lifecycleScope.launch {
                try {
                    if (player?.isPlaying != true) player?.play()
                    for (second in 30 downTo 1) {
                        if (subtitlesAligned) break
                        message = "Untertitel werden angepasst … gleich auf ${renderer.name} ($second s)"
                        kotlinx.coroutines.delay(1_000)
                    }
                    message = if (subtitlesAligned) "Untertitel angepasst – starte auf ${renderer.name} …"
                    else "Untertitel noch nicht sicher angepasst – starte trotzdem auf ${renderer.name} …"
                } finally {
                    syncBeforeCast = null
                }
                castAfterSync = true
                try {
                    castTo(renderer, switching = false)
                } finally {
                    castAfterSync = false
                }
            }
            return
        }
        syncBeforeCast?.cancel()
        syncBeforeCast = null
        val channel = playing
        if (vod == null && channel == null) return
        if (!switching) {
            closeOverlay()
            message = "Starte auf ${renderer.name} …"
            castMode = null
            clearRemoteState()
        }
        castStatus = if (switching) "Schalte um auf ${channel?.name ?: vod?.title} …" else "Starte auf ${renderer.name} …"
        castJob?.cancel()
        castJob = lifecycleScope.launch {
            // LG (webOS), Live über HLS: im laufenden Strom den Sender wechseln, statt den Mediaplayer neu zu öffnen –
            // so bleiben seine Einstellungen (Vollbild) erhalten, und das Umschalten geht schneller.
            val running = CastService.relay
            if (switching && vod == null && channel != null && running != null && running.liveWindow && castMode == CastMode.RELAY &&
                de.ayutv.data.net.LgWebOs.isLg(renderer.name)
            ) {
                val target = runCatching { container.content.bestForSport(channel) }.getOrDefault(channel)
                // Die eigene Liste nimmt HLS wie TS: immer im laufenden Mediaplayer umschalten (1–3 s statt Neustart).
                val switchUrl = if (running.isHlsSource) {
                    de.ayutv.data.net.DlnaCast.liveUrls(target.url).firstOrNull { it.substringBefore('?').endsWith(".m3u8") } ?: target.url
                } else target.url
                running.switchTo(switchUrl)
                castStatus = "📺 ${renderer.name}: ${target.name}"
                message = "Schalte auf ${renderer.name} um: ${target.name}"
                // Folgt der Mediaplayer des LG dem Wechsel wirklich? Manchmal bleibt er an der Bruchstelle einfach stehen
                // (holt nichts mehr ab) – dann den Mediaplayer frisch öffnen, statt dass nach dem Umschalten nichts passiert.
                var readySince = 0L
                var followed = false
                val waitStart = SystemClock.elapsedRealtime()
                while (!running.isClosed && SystemClock.elapsedRealtime() - waitStart < SWITCH_FOLLOW_MAX_MS) {
                    if (running.switchFollowed) {
                        followed = true
                        break
                    }
                    if (running.switchPartReady) {
                        if (readySince == 0L) readySince = SystemClock.elapsedRealtime()
                        else if (SystemClock.elapsedRealtime() - readySince > SWITCH_FOLLOW_MS) break
                    }
                    kotlinx.coroutines.delay(250)
                }
                if (followed) {
                    handler.postDelayed({ if (message?.startsWith("Schalte auf") == true) message = null }, 3_000)
                    return@launch
                }
                statusHistory += "LG folgt dem Umschalten nicht (${running.summary()}) – Mediaplayer neu"
                message = "Starte ${target.name} auf ${renderer.name} neu …"
            }
            de.ayutv.data.net.DlnaCast.clearTrace()
            releaseCastRelay()
            // Volle Bildqualität: beim Sender die beste Fassung (4K/UHD vor Full HD, auch wenn das Handy eine kleinere
            // spielt), als Original-Stream ohne Umwandlung; Filme in der Originaldatei.
            val best = channel?.takeIf { vod == null }?.let { runCatching { container.content.bestForSport(it) }.getOrDefault(it) }
            val title = vod?.title ?: best?.name ?: "DnA-TV"
            val source = vod?.url ?: best!!.url
            val agent = best?.userAgent ?: settings.userAgent
            val live = isLive
            val urls = if (vod != null) listOf(source) else de.ayutv.data.net.DlnaCast.liveUrls(source)
            // Erst hier aufhören: Viele Konten erlauben nur eine Verbindung, die braucht jetzt der Fernseher.
            if (!live) saveProgress()
            // Filme: hier stehen geblieben – dort geht es auf dem Fernseher weiter (beim Umschalten zwischen Fernsehern bleibt der Wert).
            if (!live && player?.playbackState != androidx.media3.common.Player.STATE_IDLE) castStartPositionMs = player?.currentPosition ?: 0L
            // Tonspur wie hier gewählt (für den umverpackten Film auf dem Fernseher).
            val audioLanguages = castAudioLanguages()
            // 4K-Sender (oder hier gerade 4K): dem Fernseher als 4K ankündigen, damit er in voller Auflösung spielt.
            val castUhd = (best != null && de.ayutv.data.parse.ChannelVariants.rank(best.name) == 2) ||
                (player?.videoFormat?.height ?: 0) >= 2000
            castPhoneTextLanguage = castTextLanguage()
            player?.stop()
            val http = container.http
            var error: String? = null
            var started = false
            // 1. Direkt – außer der Anbieter ist nur über den Proxy der App erreichbar (das kann der Fernseher nicht).
            val host = runCatching { java.net.URI(source).host }.getOrNull()
            // Der Fernseher darf nicht selbst zum Anbieter, wenn die App über einen Proxy läuft: Der Anbieter sähe sonst eine
            // zweite IP-Adresse (die des Hauses statt die des Proxys) – bei Konten mit einer Verbindung oder IP-Bindung
            // bleibt das Bild schwarz. Dann holt das Handy den Stream über den Proxy und gibt ihn im WLAN weiter.
            val mustRelay = de.ayutv.data.net.AppProxy.proxies(host) || de.ayutv.data.net.AppProxy.current.active
            // 0. LG: über die eigene webOS-Steuerung (wie die App „ThinQ“) – nimmt Befehle an, wo DLNA bei manchen LG
            // nicht antwortet. Beim ersten Mal fragt der Fernseher einmal „Zulassen?“.
            var webOsError: String? = null
            if (de.ayutv.data.net.LgWebOs.isLg(renderer.name)) {
                // Filme und Folgen: zuerst umverpackt (echte HLS-Stücke vom Handy). Der Mediaplayer des LG hat so Ton
                // (DTS/TrueHD → Dolby Digital), spult mit seiner Fernbedienung und zeigt die Untertitel; auf dem Handy
                // gibt es Spulen, Tonspur und Untertitel in der Fernbedienung.
                if (vod != null && de.ayutv.data.net.Remux.available) {
                    val problem = castRemuxed(renderer, source, agent, title, audioLanguages)
                    if (problem == null) {
                        started = true
                        castMode = CastMode.RELAY
                    } else statusHistory += "LG umverpackt: $problem"
                }
                // Live: erst direkt versuchen (volle Qualität, der Fernseher holt selbst). Klappt das nicht
                // oder muss über Proxy, dann über das Handy weiterleiten.
                val viaPhone = mustRelay || live
                suspend fun openDirect() {
                    // HLS-Fassung (gleicher Strom, der Mediaplayer des LG spielt sie zuverlässiger als endloses TS).
                    val url = urls.firstOrNull { it.substringBefore('?').endsWith(".m3u8") } ?: source
                    message = "Starte auf ${renderer.name} (webOS) …"
                    webOsError = playOnWebOs(renderer, url, title, de.ayutv.data.net.DlnaCast.mimeOf(url, live))
                    if (webOsError == null) {
                        started = true
                        castMode = CastMode.DIRECT
                    }
                }
                // Live immer über die eigene Liste vom Handy (auch ohne Proxy): Das Handy sieht, ob der LG wirklich abholt
                // (direkt geöffnet blieb er bei manchen Sendern schwarz, ohne dass die App es merkte – „geöffnet“ hieß nur,
                // dass er den Befehl annahm), und der Senderwechsel läuft im offenen Mediaplayer in 1–3 s.
                if (started) Unit
                else if (!viaPhone) openDirect()
                else {
                    // Anbieter nur über den Proxy der App: das Handy holt den Stream und gibt ihn im WLAN weiter.
                    val local = withContext(kotlinx.coroutines.Dispatchers.IO) { de.ayutv.data.net.DlnaCast.localAddressFor(renderer.host) }
                    // Zuerst als HLS (das spielt der LG-Mediaplayer, wie ohne Proxy), sonst als durchgehender TS-Strom.
                    val hlsSource = urls.firstOrNull { it.substringBefore('?').endsWith(".m3u8") }
                        ?: vod?.let { de.ayutv.data.net.DlnaCast.vodHlsUrl(source) }
                    // Gewählte Untertitel (geladen oder übersetzt) gehen als Spur mit (nur über HLS möglich).
                    val subs = if (vod != null) castSubtitles() else null
                    // Film: auf dem Fernseher dort weiter, wo er hier stand (über HLS: die Stücke davor fallen weg).
                    val resumeSeconds = if (vod != null) (castStartPositionMs / 1000.0).coerceAtLeast(0.0) else 0.0
                    // Filme und Serien (MKV, MP4, AVI): Der Mediaplayer des LG öffnet eine Adresse nur, wenn ihm Typ und
                    // Endung zusagen – zuerst als MP4 ausgeben (das nimmt er an, der Inhalt wird trotzdem erkannt), dann
                    // mit dem echten Typ.
                    data class Attempt(val from: String, val asHls: Boolean, val label: String, val mime: String? = null, val ext: String? = null, val wrap: Boolean = false, val pause: Long = 0)
                    val sourceIsHls = source.substringBefore('?').lowercase().endsWith(".m3u8")
                    val attempts = buildList {
                        // Live-TS: zuerst der Strom selbst als eigene Liste (eine Verbindung, startet schnell, derselbe
                        // Strom wie auf dem Handy), dann die HLS-Fassung des Anbieters.
                        if (live && !sourceIsHls) add(Attempt(source, false, "TS-Liste"))
                        hlsSource?.let { add(Attempt(it, true, "HLS")) }
                        if (!live) {
                            // Lehnt der Anbieter kurz nach dem Handy ab (eine Verbindung, die alte zählt noch): nach einer Pause noch einmal.
                            hlsSource?.let { add(Attempt(it, true, "HLS 2", pause = 5_000)) }
                            // Der LG öffnet vom Handy nur HLS: die Film-Datei in eine HLS-Liste verpacken.
                            add(Attempt(source, false, "HLS-Hülle", wrap = true))
                            add(Attempt(source, false, "MP4", "video/mp4", "mp4"))
                        }
                    }
                    val problems = ArrayList<String>()
                    for (attempt in attempts) {
                        if (started) break
                        // „HLS 2“ nur, wenn der erste HLS-Versuch am Anbieter scheiterte (nicht am Fernseher).
                        if (attempt.pause > 0) {
                            if (problems.lastOrNull()?.contains("Anbieter antwortet") != true) continue
                            message = "Anbieter lehnt kurz ab – neuer Versuch …"
                            kotlinx.coroutines.delay(attempt.pause)
                        }
                        val (from, asHls) = attempt
                        val relay = local?.let {
                            runCatching {
                                de.ayutv.data.net.DlnaRelay(
                                    http, from, agent, live, it, hlsToTv = asHls, subtitles = subs.takeIf { asHls || attempt.wrap },
                                    startSeconds = if (asHls) resumeSeconds else 0.0,
                                    wrapSeconds = if (attempt.wrap) ((player?.duration?.takeIf { d -> d > 0 } ?: 3 * 3_600_000L) / 1000) else 0,
                                    uhd = castUhd,
                                    liveTsWindow = live && !asHls,
                                )
                            }.getOrNull()
                        } ?: continue
                        message = "Leite über das Handy zu ${renderer.name} weiter (webOS, ${attempt.label}) …"
                        if (relay.liveWindow) {
                            // Erst das erste Stück holen, dann den Fernseher öffnen: Er bekommt so gleich eine spielbare Liste.
                            relay.prepareWindow()
                            for (second in 1..WINDOW_FIRST_PART_SECONDS) {
                                if (relay.windowReady || relay.isClosed) break
                                if (second > 12 && relay.fetched.get() == 0L) break
                                // Anbieter lehnt ab (etwa keine HLS-Fassung): gleich den nächsten Weg, nicht 12 s warten.
                                if (second >= 3 && relay.fetched.get() == 0L && relay.lastError != null) break
                                if (second % 2 == 0) message = "Lade das erste Stück für ${renderer.name} … ${relay.summary()}"
                                kotlinx.coroutines.delay(1_000)
                            }
                            if (!relay.windowReady) {
                                problems += attempt.label + ": erstes Stück nicht geladen (${relay.lastError ?: relay.summary()})"
                                relay.close()
                                continue
                            }
                        }
                        val url = attempt.ext?.let { relay.url.substringBeforeLast('.') + "." + it } ?: relay.url
                        val opened = playOnWebOs(renderer, url, title, attempt.mime ?: relay.mime)
                        if (opened == null) {
                            // Holt der Fernseher wirklich ab? Sonst nicht als gestartet melden. Fragt er nach 6 s noch
                            // nicht einmal an, gleich den nächsten Weg versuchen.
                            // Live über die eigene Liste: Das erste Stück muss erst ganz vom Anbieter geholt sein (über den
                            // Proxy bei HD/4K gut 10–30 s). So lange warten, solange der Fernseher fragt und Daten kommen.
                            val maxWait = if (relay.liveWindow) 45 else 12
                            for (second in 1..maxWait) {
                                if (relay.bytes.get() > 0L) break
                                if (second > 12 && relay.fetched.get() == 0L) break
                                if (second % 3 == 0 && relay.liveWindow) message = "Lade das erste Stück für ${renderer.name} … ${relay.summary()}"
                                if (second >= 6 && relay.contacts.get() == 0) break
                                // Anbieter kennt die Fassung nicht (etwa kein HLS für Filme): gleich weiter.
                                if (second >= 3 && relay.lastError != null) break
                                kotlinx.coroutines.delay(1_000)
                            }
                            if (relay.bytes.get() > 0) {
                                started = true
                                castMode = CastMode.RELAY
                                CastService.start(this@PlayerActivity, relay, renderer, "$title auf ${renderer.name}")
                                offerStandby()
                            } else problems += attempt.label + ": geöffnet, nichts abgeholt (${relay.summary()})"
                        } else problems += attempt.label + ": $opened"
                        if (!started) relay.close()
                    }
                    if (!started) webOsError = problems.joinToString(" · ").ifEmpty { "Handy ist nicht im selben WLAN" }
                    // Ohne Proxy darf der LG notfalls selbst zum Anbieter.
                    if (!started && !mustRelay) {
                        statusHistory += "LG über das Handy: $webOsError"
                        openDirect()
                    }
                }
                if (webOsError != null) statusHistory += "LG webOS: $webOsError"
            }
            // Hat beim letzten Sender nur die Weiterleitung geklappt, gleich so (Umschalten in 2–3 Sekunden statt 15).
            if (!started && !mustRelay && castMode != CastMode.RELAY) {
                for (url in urls) {
                    error = de.ayutv.data.net.DlnaCast.play(http, renderer, url, title, live)
                    if (error != null) continue
                    message = "${renderer.name} lädt …"
                    when (de.ayutv.data.net.DlnaCast.awaitPlaying(http, renderer, 12_000)) {
                        de.ayutv.data.net.DlnaCast.Playback.PLAYING, de.ayutv.data.net.DlnaCast.Playback.UNKNOWN -> {
                            started = true
                            castMode = CastMode.DIRECT
                            break
                        }
                        de.ayutv.data.net.DlnaCast.Playback.FAILED -> {
                            // Nimmt die Adresse an, spielt sie aber nicht: gleich über das Handy (andere Endung hilft hier selten).
                            error = "${renderer.name} kann den Stream nicht direkt abspielen"
                            break
                        }
                    }
                }
            }
            // 2. Über das Handy weiterleiten.
            if (!started) {
                runCatching { de.ayutv.data.net.DlnaCast.stop(http, renderer) }
                val local = withContext(kotlinx.coroutines.Dispatchers.IO) { de.ayutv.data.net.DlnaCast.localAddressFor(renderer.host) }
                if (local == null) {
                    error = error ?: "Handy ist nicht im selben WLAN wie ${renderer.name}"
                } else {
                    message = "Leite über das Handy zu ${renderer.name} weiter …"
                    val relay = runCatching { de.ayutv.data.net.DlnaRelay(http, source, agent, live, local) }.getOrNull()
                    if (relay != null) {
                        val relayError = de.ayutv.data.net.DlnaCast.play(http, renderer, relay.url, title, live, relay.mime)
                        val result = if (relayError == null) de.ayutv.data.net.DlnaCast.awaitPlaying(http, renderer, 18_000) else null
                        if (result == de.ayutv.data.net.DlnaCast.Playback.PLAYING ||
                            (result == de.ayutv.data.net.DlnaCast.Playback.UNKNOWN && relay.bytes.get() > 0)
                        ) {
                            started = true
                            castMode = CastMode.RELAY
                            // Im Hintergrunddienst: läuft weiter, auch bei gesperrtem Bildschirm oder wenn der Player zu ist.
                            CastService.start(this@PlayerActivity, relay, renderer, "$title auf ${renderer.name}")
                        } else {
                            error = relayError ?: relay.lastError?.let { "Weiterleiten: $it" } ?: error ?: "${renderer.name} spielt den Stream nicht ab"
                            relay.close()
                        }
                    }
                }
            }
            if (!started) {
                runCatching { de.ayutv.data.net.DlnaCast.stop(http, renderer) }
                if (switching) {
                    // Beim Umschalten bleibt das Handy die Fernbedienung: einfach einen anderen Sender wählen.
                    castStatus = "${title}: ${error ?: "startet nicht"}"
                    return@launch
                }
                // Mit den Antworten des Fernsehers je Schritt: zeigt, wo es hängt (Foto davon hilft bei der Fehlersuche).
                val steps = listOfNotNull(webOsError?.let { "webOS: $it" }, de.ayutv.data.net.DlnaCast.traceSummary().ifEmpty { null })
                    .joinToString(" · ")
                message = "${renderer.name}: ${error ?: "startet nicht"}" + if (steps.isNotEmpty()) "\n$steps" else ""
                statusHistory += "Fernseher ${renderer.name} (${renderer.controlUrl}): ${error ?: "startet nicht"} – $steps"
                de.ayutv.data.Diagnostics.saveCast(
                    this@PlayerActivity, "${renderer.name} · $title · gescheitert",
                    listOf("Fehler: ${error ?: "startet nicht"}", "Schritte: $steps", "Weg: ${routeLabel()}") + statusHistory.takeLast(15),
                )
                castStatus = null
                handler.postDelayed({ message = null }, 20_000)
                restartStream()
                return@launch
            }
            castTarget = renderer
            castingTo = renderer.name
            lastTv = tvId(renderer)
            CastService.onStopped = castStoppedCallback
            castStatus = if (castRelay != null) "über das Handy weitergeleitet – läuft auch bei gesperrtem Handy" else "direkt, volle Qualität"
            // Weiterleitung: laufend zeigen, ob der Fernseher wirklich abruft und der Anbieter liefert (Fehlersuche bei
            // schwarzem Bild: holt er nichts, ist es der Fernseher; liefert der Anbieter nichts, Konto/Proxy).
            castRelay?.let { relay ->
                lifecycleScope.launch {
                    val started = SystemClock.elapsedRealtime()
                    var lastSaved = 0L
                    while (castRelay === relay && castTarget != null) {
                        relay.remux?.let { castPosition = it.position() }
                        // Für den Fehlerbericht: alle 15 s den Stand der Weiterleitung festhalten.
                        if (SystemClock.elapsedRealtime() - lastSaved > 15_000) {
                            lastSaved = SystemClock.elapsedRealtime()
                            de.ayutv.data.Diagnostics.saveCast(
                                this@PlayerActivity, "${renderer.name} · $title · läuft seit ${(lastSaved - started) / 1000} s",
                                listOf("Art: ${castMode ?: "?"} · ${if (relay.remux != null) "Film umverpackt" else if (relay.liveWindow) "Live (eigene Liste)" else "Weiterleitung"}",
                                    "Stand: ${relay.summary()}", "Weg: ${routeLabel()}") + statusHistory.takeLast(10),
                            )
                        }
                        if (relay.remux == null || SystemClock.elapsedRealtime() - started < 30_000) castStatus = "über das Handy weitergeleitet · ${relay.summary()}"
                        // Die ersten 30 Sekunden gut sichtbar mitten im Bild (für ein Foto, wenn der Fernseher schwarz bleibt).
                        if (SystemClock.elapsedRealtime() - started < 30_000) message = "📺 ${castTarget?.name}: ${relay.summary()}"
                        else if (message?.startsWith("📺 ${castTarget?.name}:") == true) message = null
                        delay(2_000)
                    }
                }
            }
            if (!switching) closeOverlay()
        }
    }

    /** „Beenden“ in der Benachrichtigung: zurück aufs Handy. */
    private val castStoppedCallback: () -> Unit = {
        runOnUiThread {
            if (castTarget != null) {
                castJob?.cancel()
                castTarget = null
                castingTo = null
                castStatus = null
                castMode = null
                clearRemoteState()
                message = "Fernseher beendet – läuft wieder hier"
                resumeHere()
            }
        }
    }

    /** Wie der Stream zum Fernseher kommt (gemerkt, damit das Umschalten schnell geht). */
    private enum class CastMode { DIRECT, RELAY }
    private var castMode: CastMode? = null
    private var castJob: kotlinx.coroutines.Job? = null
    /** Zeile in der Fernbedienung: was gerade passiert („Schalte um auf …“, „direkt, volle Qualität“). */
    var castStatus by mutableStateOf<String?>(null); private set
    /** Läuft gerade auf einem Fernseher (DLNA, DnA-TV oder Chromecast): Das Handy ist dann die Fernbedienung. */
    val castingRemote: Boolean get() = castTarget != null || castingTo != null
    /** Fernseher mit DnA-TV, an den gerade übertragen wird (zum Weiterschalten). */
    private var dnaClient: de.ayutv.data.remote.RemoteClient? = null

    /** Neuen Sender an den Fernseher mit DnA-TV schicken. */
    private fun forwardToDnaTv(channel: ChannelEntity) {
        val client = dnaClient ?: return
        castJob?.cancel()
        castStatus = "Schalte um auf ${channel.name} …"
        castJob = lifecycleScope.launch {
            val best = runCatching { container.content.bestForSport(channel) }.getOrDefault(channel)
            runCatching {
                client.play(de.ayutv.data.remote.RemotePlay(live = true, url = best.url, title = best.name, image = best.logo, userAgent = best.userAgent ?: settings.userAgent))
            }.onSuccess { castStatus = "${channel.name} läuft" }
                .onFailure { castStatus = "Fernseher: ${it.message ?: "nicht erreichbar"}" }
        }
    }

    // ---------------------------------------------------------------- Fernbedienung (Handy, während der Fernseher spielt)

    fun remoteZap(step: Int) = zap(step)

    fun remoteLastChannel() {
        previousChannel?.takeIf { it.key != playing?.key }?.let { playChannel(it) }
    }

    /** Pause/Weiter auf dem Fernseher (Filme und Folgen; bei Live hält der Fernseher an, falls er kann). */
    fun remotePlayPause() {
        castTarget?.let { tv ->
            // LG mit eigenem Mediaplayer (webOS): Befehl dorthin, DLNA erreicht ihn nicht.
            if (de.ayutv.data.net.LgWebOs.isLg(tv.name) && castMode == CastMode.RELAY) {
                val pause = !castPaused
                lifecycleScope.launch {
                    val error = de.ayutv.data.net.LgWebOs.control(container.http, tv.host, lgKeys, if (pause) "ssap://media.controls/pause" else "ssap://media.controls/play")
                    if (error == null) {
                        castPaused = pause
                        castRemux?.paused(pause)
                        castStatus = if (pause) "⏸ angehalten" else "▶ läuft"
                    } else castStatus = "${tv.name}: $error"
                }
                return
            }
            lifecycleScope.launch {
                val state = de.ayutv.data.net.DlnaCast.state(container.http, tv)?.uppercase()
                if (state == "PLAYING") de.ayutv.data.net.DlnaCast.pause(container.http, tv) else de.ayutv.data.net.DlnaCast.resume(container.http, tv)
            }
            return
        }
        dnaClient?.takeIf { castingToDnaTv }?.let { client -> lifecycleScope.launch { runCatching { client.key("play") } } }
    }

    /** Lautstärke des Fernsehers (DLNA); Ergebnis steht in der Fernbedienung. */
    var castVolume by mutableStateOf<Int?>(null); private set

    fun remoteVolume(delta: Int) {
        val tv = castTarget ?: return
        lifecycleScope.launch {
            de.ayutv.data.net.DlnaCast.changeVolume(container.http, tv, delta)?.let { castVolume = it }
        }
    }

    /** Die Fernbedienung kann die Lautstärke ändern (Fernseher mit DLNA-Lautstärke). */
    val remoteHasVolume: Boolean get() = castTarget?.renderingUrl != null

    /** Fernseher anhalten und wieder auf dem Handy abspielen. */
    fun stopCast() {
        val renderer = castTarget ?: return
        // Umverpackter Film: hier dort weiter, wo der Fernseher gerade ist.
        val tvPosition = castRemux?.position()
        endShadow()
        castJob?.cancel()
        castTarget = null
        castingTo = null
        castStatus = null
        castMode = null
        castVolume = null
        clearRemoteState()
        releaseCastRelay()
        closeOverlay()
        message = "Läuft wieder hier"
        handler.postDelayed({ if (message == "Läuft wieder hier") message = null }, 4_000)
        // Erst den Fernseher anhalten (gibt die Verbindung beim Anbieter frei), dann hier weiter.
        lifecycleScope.launch {
            // LG: den eigenen Mediaplayer des Fernsehers schließen (DLNA-Stop erreicht ihn nicht).
            if (de.ayutv.data.net.LgWebOs.isLg(renderer.name)) {
                runCatching { de.ayutv.data.net.LgWebOs.stop(container.http, renderer.host, lgKeys) }
            }
            de.ayutv.data.net.DlnaCast.stop(container.http, renderer)
            resumeHere()
            tvPosition?.takeIf { it > 1 }?.let { player?.seekTo((it * 1000).toLong()) }
        }
    }

    /** Anzeige der Fernbedienung für den umverpackten Film zurücksetzen. */
    private fun clearRemoteState() {
        castSeekJob?.cancel()
        castSubsJob?.cancel()
        castSeekTarget = null
        castPosition = null
        castDuration = null
        castAudioTracks = emptyList()
        castHasSubtitles = false
    }

    /** Zurück aufs Handy: den Sender, der zuletzt auf dem Fernseher lief (beim Umschalten blieb das Handy still). */
    private fun resumeHere() {
        val channel = playing
        if (isLive && channel != null) playChannel(channel) else restartStream()
    }

    /** Stelle im Film beim Start der Übertragung (für den Fernseher). */
    private var castStartPositionMs = 0L

    // ---------------------------------------------------------------- Film umverpackt zum LG (Spulen, Ton, Untertitel)

    /** Sprachen für die Tonspur auf dem Fernseher: die hier gewählte Spur, dann die Sprache des Geräts. */
    private fun castAudioLanguages(): List<String> {
        val chosen = player?.currentTracks?.groups
            ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        return listOfNotNull(chosen, java.util.Locale.getDefault().language).distinct()
    }

    /** Sprache der eingebetteten Untertitel, die hier gerade laufen (null = keine). */
    private fun castTextLanguage(): Pair<String, Boolean>? {
        val group = player?.currentTracks?.groups?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected } ?: return null
        val format = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } ?: return null
        val language = format.language ?: return null
        return language to (format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED != 0)
    }

    /** Beim Übertragen festgehalten: eingebettete Untertitel des Handys (Sprache, erzwungen). */
    private var castPhoneTextLanguage: Pair<String, Boolean>? = null

    /** Umverpackter Film auf dem Fernseher (sonst null). */
    private val castRemux: de.ayutv.data.net.RemuxSession? get() = castRelay?.remux

    /** Titel auf dem Fernseher (zum Neu-Öffnen nach Spulen, Tonspur, Untertiteln). */
    private var castTitle = ""

    /**
     * Untertitel auf dem Fernseher: [SUBS_OFF], [SUBS_PHONE] (wie hier: geladene Datei oder Übersetzung) oder eine
     * eingebettete Spur des Films (ihr Index). Dazu die Verschiebung (ms, + = später). Beides wird bei jedem Abruf des
     * Fernsehers gelesen: Ein Wechsel gilt nach wenigen Sekunden, ohne den Film neu zu öffnen.
     */
    @Volatile private var castSubsChoice = SUBS_OFF
    @Volatile private var castSubsShiftMs = 0L
    /** Untertitel wie auf dem Handy (beim Start der Übertragung festgehalten). */
    private var castPhoneSubs: de.ayutv.data.net.DlnaRelay.HlsSubtitles? = null
    var castHasSubtitles by mutableStateOf(false); private set
    var castSubtitleState by mutableStateOf(""); private set

    /** Stelle und Länge auf dem Fernseher (s), für die Fernbedienung; null, wenn nicht bekannt. */
    var castPosition by mutableStateOf<Double?>(null); private set
    var castDuration by mutableStateOf<Double?>(null); private set
    /** Ziel beim Spulen per Handy (gesammelt, bis kurz nicht mehr getippt wird). */
    var castSeekTarget by mutableStateOf<Double?>(null); private set
    var castAudioTracks by mutableStateOf<List<de.ayutv.data.net.Remux.Track>>(emptyList()); private set
    var castAudioIndex by mutableStateOf(-1); private set
    private var castPaused = false

    /**
     * Untertitelspur für den Fernseher: immer vorhanden (auch wenn gerade aus), damit sie sich während des Films
     * einschalten und wechseln lässt. Inhalt je nach [castSubsChoice], laufend abgefragt.
     */
    private fun tvSubtitles(session: de.ayutv.data.net.RemuxSession): de.ayutv.data.net.DlnaRelay.HlsSubtitles {
        val phone = castPhoneSubs
        val language = phone?.language ?: settings.subtitleLanguage.ifBlank { "de" }
        return de.ayutv.data.net.DlnaRelay.HlsSubtitles(
            {
                val choice = castSubsChoice
                val lines = when {
                    choice == SUBS_PHONE -> phone?.cues?.invoke().orEmpty()
                    choice >= 0 -> session.embeddedCues(choice)
                    else -> emptyList()
                }
                val shift = castSubsShiftMs
                if (shift == 0L) lines else lines.map { (a, b, t) -> Triple(a + shift, b + shift, t) }
            },
            language, "Untertitel (DnA-TV)", session.duration.toLong(),
        )
    }

    /** Name der gewählten Untertitel für die Fernbedienung. */
    private fun castSubsLabel(session: de.ayutv.data.net.RemuxSession?): String {
        val choice = castSubsChoice
        val name = when {
            choice == SUBS_PHONE -> castPhoneSubs?.name ?: "wie auf dem Handy"
            choice >= 0 -> session?.subtitleTracks?.firstOrNull { it.index == choice }?.label() ?: "Spur ${choice + 1}"
            else -> return "aus"
        }
        val shift = castSubsShiftMs
        return name + if (shift != 0L) String.format(java.util.Locale.GERMANY, " (%+.1f s)", shift / 1000.0) else ""
    }

    /**
     * Untertitel beim Start der Übertragung: wie auf dem Handy, wenn hier welche laufen; sonst die eingebettete Spur in
     * der Untertitelsprache der Einstellungen (wenn Untertitel automatisch an sind), sonst aus.
     */
    private fun initialCastSubs(session: de.ayutv.data.net.RemuxSession): Int {
        if (castPhoneSubs != null) return SUBS_PHONE
        fun track(language: String, forced: Boolean): Int? {
            val wanted = de.ayutv.data.net.Remux.isoLanguage(language)
            return session.subtitleTracks
                .filter { t -> t.language?.let { de.ayutv.data.net.Remux.isoLanguage(it) } == wanted }
                .minByOrNull { if (it.forced == forced) 0 else 1 }?.index
        }
        // Hier läuft gerade eine eingebettete Spur: dieselbe auf dem Fernseher.
        castPhoneTextLanguage?.let { (language, forced) -> track(language, forced)?.let { return it } }
        if (!settings.subtitlesAutoOn) return SUBS_OFF
        return track(settings.subtitleLanguage.ifBlank { "de" }, forced = false) ?: SUBS_OFF
    }

    /**
     * Film umverpackt an den LG: Datei beim Anbieter öffnen (über den Proxy der App), Stückliste anlegen, im
     * Mediaplayer des LG öffnen und warten, bis er wirklich abholt. null = läuft, sonst Grund.
     */
    private suspend fun castRemuxed(
        renderer: de.ayutv.data.net.DlnaCast.Renderer, source: String, agent: String?, title: String, languages: List<String>,
    ): String? {
        val local = withContext(kotlinx.coroutines.Dispatchers.IO) { de.ayutv.data.net.DlnaCast.localAddressFor(renderer.host) }
            ?: return "Handy ist nicht im selben WLAN"
        message = "Bereite den Film für ${renderer.name} vor …"
        val session = withContext(kotlinx.coroutines.Dispatchers.IO) {
            de.ayutv.data.net.RemuxSession.open(container.http, source, agent, languages)
        }.getOrElse { return it.message ?: "Datei nicht lesbar" }
        castSubsShiftMs = 0L
        castPhoneSubs = castSubtitles()
        castSubsChoice = initialCastSubs(session)
        val subs = tvSubtitles(session)
        val relay = runCatching {
            de.ayutv.data.net.DlnaRelay(container.http, source, agent, false, local, subtitles = subs, remux = session)
        }.getOrElse {
            session.close()
            return "Weiterleitung nicht möglich (${it.javaClass.simpleName})"
        }
        val url = relay.reopenUrl((castStartPositionMs / 1000.0).coerceAtLeast(0.0))
        message = "Starte auf ${renderer.name} (umverpackt" + (if (session.tracks.any { it.index == session.audio && it.codec.lowercase() !in TV_AUDIO }) ", Ton → Dolby Digital" else "") + ") …"
        val opened = playOnWebOs(renderer, url, title, relay.mime)
        if (opened != null) {
            relay.close()
            return opened
        }
        for (second in 1..20) {
            if (relay.bytes.get() > 0L) break
            if (second >= 8 && relay.contacts.get() == 0) break
            if (second >= 4 && relay.lastError != null) break
            delay(1_000)
        }
        if (relay.bytes.get() == 0L) {
            val reason = "geöffnet, nichts abgeholt (${relay.summary()})"
            relay.close()
            return reason
        }
        castTitle = title
        castPaused = false
        castHasSubtitles = castPhoneSubs != null || session.subtitleTracks.isNotEmpty()
        castSubtitleState = castSubsLabel(session)
        castAudioTracks = session.tracks
        castAudioIndex = session.audio
        castDuration = session.duration
        castPosition = session.firstSeconds
        CastService.start(this, relay, renderer, "$title auf ${renderer.name}")
        offerStandby()
        return null
    }

    /** Liste neu auf dem LG öffnen, ab [seconds] (nach Spulen, Tonspur, Untertiteln). */
    private fun reopenOnTv(seconds: Double, what: String) {
        val tv = castTarget ?: return
        val relay = castRelay?.takeIf { it.remux != null } ?: return
        val session = relay.remux ?: return
        val at = seconds.coerceIn(0.0, (session.duration - 5).coerceAtLeast(0.0))
        relay.subtitles = tvSubtitles(session)
        val url = relay.reopenUrl(at)
        castPosition = session.firstSeconds
        castPaused = false
        session.paused(false)
        castStatus = "$what – ${formatClock(at)}"
        lifecycleScope.launch {
            val error = playOnWebOs(tv, url, castTitle.ifEmpty { vod?.title ?: "DnA-TV" }, relay.mime)
            castStatus = if (error == null) "📺 ${tv.name}: $what" else "${tv.name}: $error"
        }
    }

    private var castSeekJob: kotlinx.coroutines.Job? = null

    /** Spulen per Handy: mehrere Tipper werden gesammelt, dann öffnet der Fernseher an der neuen Stelle. */
    fun remoteSeek(deltaSeconds: Int) {
        val session = castRemux ?: return
        val from = castSeekTarget ?: session.position()
        val target = (from + deltaSeconds).coerceIn(0.0, (session.duration - 5).coerceAtLeast(0.0))
        castSeekTarget = target
        castSeekJob?.cancel()
        castSeekJob = lifecycleScope.launch {
            delay(1_200)
            castSeekTarget = null
            reopenOnTv(target, if (deltaSeconds > 0) "Vor" else "Zurück")
        }
    }

    /** Springen an eine Stelle (Schieberegler). */
    fun remoteSeekTo(seconds: Double) {
        castSeekJob?.cancel()
        castSeekTarget = null
        reopenOnTv(seconds, "Gesprungen")
    }

    /** Andere Tonspur auf dem Fernseher. */
    fun remoteAudio(index: Int) {
        val session = castRemux ?: return
        if (index == session.audio) return
        val at = session.position()
        session.selectAudio(index)
        castAudioIndex = index
        reopenOnTv(at, "Tonspur: " + (session.tracks.firstOrNull { it.index == index }?.label() ?: "Spur ${index + 1}"))
    }

    private var castSubsJob: kotlinx.coroutines.Job? = null

    /**
     * Untertitel wechseln ([choice]: aus, wie auf dem Handy, eingebettete Spur) oder verschieben (ms, + = später). Der
     * Fernseher holt die Untertitel Stück für Stück kurz vor dem Bild: Die Änderung erscheint nach wenigen Sekunden,
     * der Film läuft dabei weiter.
     */
    fun remoteSubtitles(choice: Int? = null, shiftDeltaMs: Long = 0) {
        val session = castRemux ?: return
        choice?.let { castSubsChoice = it }
        castSubsShiftMs += shiftDeltaMs
        castSubtitleState = castSubsLabel(session)
        castStatus = "Untertitel: $castSubtitleState – auf dem Fernseher in wenigen Sekunden"
    }

    /** Untertitel auf dem Fernseher wählen: aus, wie auf dem Handy, jede Text-Spur des Films; früher/später; neu laden. */
    fun chooseCastSubtitles() {
        val session = castRemux
        if (session == null) {
            castStatus = "Untertitel gehen nur bei Filmen, die das Handy umverpackt an den Fernseher gibt"
            return
        }
        fun mark(choice: Int) = if (castSubsChoice == choice) "✓ " else ""
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (castHasSubtitles) {
            labels += mark(SUBS_OFF) + "Aus"
            actions += { remoteSubtitles(choice = SUBS_OFF) }
        }
        // Film ohne Text-Untertitel (oder andere gewünscht): hier laden (OpenSubtitles bzw. Übersetzung) und an den
        // Fernseher geben. Vorher ging der Hinweis unter der Fernbedienung unter – es schien nichts zu passieren.
        val languages = listOf(settings.subtitleLanguage.ifBlank { "de" }, "de", "en").distinct()
        for (language in languages) {
            labels += "⬇ ${Languages.label(language)} laden"
            actions += { loadSubtitlesForTv(language) }
        }
        castPhoneSubs?.let { phone ->
            labels += mark(SUBS_PHONE) + "Wie auf dem Handy: ${phone.name}"
            actions += { remoteSubtitles(choice = SUBS_PHONE) }
        }
        for (track in session.subtitleTracks) {
            labels += mark(track.index) + track.label() + " (im Film)"
            actions += { remoteSubtitles(choice = track.index) }
        }
        if (castSubsChoice != SUBS_OFF) {
            labels += "0,5 s früher"
            actions += { remoteSubtitles(shiftDeltaMs = -500) }
            labels += "0,5 s später"
            actions += { remoteSubtitles(shiftDeltaMs = 500) }
            if (castSubsShiftMs != 0L) {
                labels += String.format(java.util.Locale.GERMANY, "Verschiebung zurücksetzen (jetzt %+.1f s)", castSubsShiftMs / 1000.0)
                actions += { remoteSubtitles(shiftDeltaMs = -castSubsShiftMs) }
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (castHasSubtitles) "Untertitel auf dem Fernseher" else "Dieser Film hat keine Text-Untertitel")
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .show()
    }

    private var castSubsLoadJob: kotlinx.coroutines.Job? = null

    /** Untertitel hier laden wie im Player-Menü und, sobald sie da sind, auf dem Fernseher einschalten. */
    private fun loadSubtitlesForTv(language: String) {
        val session = castRemux ?: return
        // Im Film gibt es die Sprache als Text-Spur: die nehmen.
        val wanted = de.ayutv.data.net.Remux.isoLanguage(language)
        session.subtitleTracks.firstOrNull { t -> t.language?.let { de.ayutv.data.net.Remux.isoLanguage(it) } == wanted }?.let {
            remoteSubtitles(choice = it.index)
            return
        }
        castStatus = "Suche Untertitel (${Languages.label(language)}) …"
        val before = externalCues
        loadSubtitles(language)
        castSubsLoadJob?.cancel()
        castSubsLoadJob = lifecycleScope.launch {
            repeat(180) {
                delay(500)
                if (castRemux !== session) return@launch
                val cues = externalCues
                if (cues.isNotEmpty() && cues !== before && externalLanguage == language) {
                    val phone = castSubtitles() ?: return@repeat
                    castPhoneSubs = phone
                    castSubsChoice = SUBS_PHONE
                    castSubsShiftMs = 0L
                    castHasSubtitles = true
                    castRelay?.subtitles = tvSubtitles(session)
                    castSubtitleState = castSubsLabel(session)
                    castStatus = "Untertitel: $castSubtitleState – auf dem Fernseher in wenigen Sekunden"
                    return@launch
                }
                // Stand der Suche (oder warum nichts gefunden wurde) zeigen.
                subtitleStatus?.let { castStatus = it }
            }
        }
    }

    /** Tonspur auf dem Fernseher wählen. */
    fun chooseCastAudio() {
        val tracks = castAudioTracks.takeIf { it.size > 1 } ?: return
        val labels = tracks.map { (if (it.index == castAudioIndex) "✓ " else "") + it.label() }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Tonspur auf dem Fernseher")
            .setItems(labels) { _, which -> remoteAudio(tracks[which].index) }
            .show()
    }

    /** Uhrzeit-Format für Stellen im Film (1:02:03 bzw. 12:34). */
    fun formatClock(seconds: Double): String {
        val t = seconds.toLong().coerceAtLeast(0)
        return if (t >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", t / 60, t % 60)
    }

    /**
     * Untertitel für den Fernseher, wie sie hier zu sehen wären: geladene Datei (mit Übersetzung und Zeitlage) oder die
     * Übersetzung der eingebetteten Spur (schon übersetzte und vorbereitete Zeilen). Laufend abgefragt, so kommen auch
     * Zeilen an, die während der Übertragung fertig übersetzt werden. Sonst null.
     */
    private fun castSubtitles(): de.ayutv.data.net.DlnaRelay.HlsSubtitles? {
        val request = vod ?: return null
        val duration = (player?.duration?.takeIf { it > 0 } ?: 4 * 3_600_000L) / 1000
        fun label(language: String) =
            runCatching { java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.GERMAN) }.getOrNull()?.ifBlank { null } ?: language
        // 1. Geladene Untertitel (auch übersetzt): Dateizeit → Filmzeit wie bei der Anzeige hier.
        if (externalCues.isNotEmpty()) {
            val language = externalLanguage ?: "de"
            val provider: () -> List<Triple<Long, Long, String>> = {
                val cues = externalCues
                val texts = externalTexts
                val elastic = elasticTiming
                val timing = subtitleTiming
                val delayMs = subtitleDelayMs
                fun video(ms: Long) = (elastic?.toVideo(ms) ?: timing.toVideo(ms)) + delayMs
                cues.mapIndexed { i, c -> Triple(video(c.start), video(c.end), runCatching { texts?.get(i) }.getOrNull() ?: c.text) }
            }
            return de.ayutv.data.net.DlnaRelay.HlsSubtitles(provider, language, label(language), duration)
        }
        // 2. Übersetzung der eingebetteten Spur: was schon übersetzt ist (beim Schauen, gespeichert, nachts vorbereitet).
        val target = liveTranslateTarget ?: return null
        val known = HashMap<Long, String>(aheadTranslations)
        runCatching { de.ayutv.data.repo.EmbeddedPrep(this, container).translations(request.key, target) }.getOrNull()?.forEach { (start, text) -> known.putIfAbsent(start, text) }
        if (known.isEmpty()) return null
        val starts = known.keys.sorted()
        val lines = starts.mapIndexed { i, start ->
            // Ende: bis zur nächsten Zeile, höchstens 6 s.
            val next = starts.getOrNull(i + 1) ?: (start + 6_000)
            Triple(start, minOf(next - 50, start + 6_000).coerceAtLeast(start + 500), known.getValue(start))
        }
        return de.ayutv.data.net.DlnaRelay.HlsSubtitles({ lines }, target, label(target), duration)
    }

    /** Kopplungsschlüssel der LG-Fernseher (webOS), je Adresse: Nach dem ersten „Zulassen“ fragt der LG nicht mehr. */
    private val lgKeys = object : de.ayutv.data.net.LgWebOs.Keys {
        private val prefs get() = getSharedPreferences("lg_webos", MODE_PRIVATE)
        override fun get(host: String): String? = prefs.getString(host, null)
        override fun put(host: String, key: String) = prefs.edit().putString(host, key).apply()
    }

    /** Live (Umschalten): Mediaplayer des LG jedes Mal frisch starten, sonst hängt er oder läuft immer später. */
    private suspend fun playOnWebOs(
        renderer: de.ayutv.data.net.DlnaCast.Renderer, url: String, title: String, mime: String, fresh: Boolean = vod == null,
    ): String? =
        de.ayutv.data.net.LgWebOs.play(container.http, renderer.host, lgKeys, url, title, mime, fresh) {
            handler.post { message = "Am Fernseher „Zulassen“ wählen (nur beim ersten Mal) …" }
        }

    // ---------------------------------------------------------------- Bildschirm spiegeln (Smart View, Miracast)

    /**
     * Fernseher, auf den der Bildschirm gerade gespiegelt wird (Smart View, „Bildschirm teilen“, Miracast), sonst null.
     * Dann läuft das Video als eigene Anzeige direkt auf dem Fernseher (Android-Präsentation): in dessen Auflösung, 16:9,
     * ohne die Ränder des gespiegelten Handybildschirms. Das Handy zeigt die Bedienung.
     */
    var mirrorDisplay by mutableStateOf<android.view.Display?>(null); private set

    private val mirrorListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateMirroring()
        override fun onDisplayRemoved(displayId: Int) = updateMirroring()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Auswahl beim Spiegeln: beenden, Video wieder aufs Handy, anderer Fernseher oder weiter. */
    fun mirrorMenu(name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Gespiegelt auf $name")
            .setItems(arrayOf("⏹ Spiegeln beenden", "📺 Anderer Fernseher …", "Weiter auf $name")) { _, which ->
                when (which) {
                    0 -> stopMirroring()
                    1 -> toggleCast(choose = true)
                }
            }
            .show()
    }

    /**
     * Spiegeln beenden. Android erlaubt jeder App, die Verbindung zu einem drahtlosen Bildschirm zu trennen (Standard-
     * Ausgabe wählen). Klappt das nicht (manche Handys spiegeln anders), die Einstellung zum Spiegeln öffnen.
     */
    fun stopMirroring() {
        val router = runCatching { getSystemService(android.media.MediaRouter::class.java) }.getOrNull()
        runCatching {
            router?.let { r -> r.getDefaultRoute()?.let { r.selectRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO, it) } }
        }
        message = "Spiegeln wird beendet …"
        handler.postDelayed({
            updateMirroring()
            if (mirrorDisplay != null) {
                // Noch verbunden: zur Einstellung, dort „Trennen“ wählen.
                openMirroring(stop = true)
            } else {
                message = "Spiegeln beendet – läuft wieder hier"
                handler.postDelayed({ if (message?.startsWith("Spiegeln beendet") == true) message = null }, 2_500)
            }
        }, 800)
    }

    /** Die Anzeige auf dem Fernseher ließ sich nicht öffnen: Video wieder auf dem Handy (bis sich die Anzeigen ändern). */
    fun presentationFailed() {
        mirrorDisplay = null
    }

    private fun watchMirroring() {
        if (!hasTouchscreen()) return
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java)?.registerDisplayListener(mirrorListener, handler) }
        updateMirroring()
    }

    private fun updateMirroring() {
        val display = runCatching {
            getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()
        }.getOrNull()
        if (display?.displayId == mirrorDisplay?.displayId) return
        mirrorDisplay = display
        if (display != null) {
            // Spiegeln: das Handy spielt selbst. Lief noch eine Übertragung zum LG/DLNA, belegte sie die (einzige)
            // Verbindung beim Anbieter – dann bräche das Bild alle paar Sekunden ab. Also beenden.
            if (castTarget != null) stopCast() else freeConnection()
            message = "Läuft auf ${display.name} im Vollbild – das Handy ist jetzt die Fernbedienung"
            handler.postDelayed({ if (message?.startsWith("Läuft auf ${display.name}") == true) message = null }, 6_000)
        }
    }

    // ---------------------------------------------------------------- Konferenz (zweites, kleines Bild)

    /** Zweiter Sender als kleines Bild unten rechts (ohne Ton). ⏩ tauscht die Bilder. */
    var pipPlayer by mutableStateOf<de.ayutv.player.dna.engine.DnaEnginePlayer?>(null); private set
    @Volatile private var pipAgent: String? = null
    var pipChannel by mutableStateOf<ChannelEntity?>(null); private set
    /** Auswahl für das kleine Bild: laufende Spiele und der vorige Sender (Sender, Beschriftung). */
    var pipChoices by mutableStateOf<List<Pair<ChannelEntity, String>>?>(null); private set
    private var pipRetries = 0
    /** Multiview: zweiter Sender gleich groß neben dem Hauptbild statt klein unten rechts (gemerkt). */
    var pipSplit by mutableStateOf(false); private set

    fun togglePipSplit() {
        pipSplit = !pipSplit
        getSharedPreferences(PIP_PREFS, MODE_PRIVATE).edit().putBoolean(PIP_SPLIT, pipSplit).apply()
        closeOverlay()
    }

    fun openPipPicker() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val games = runCatching { container.content.sportEvents(hours = 1) }.getOrDefault(emptyList())
                .filter { it.programme.start <= now && it.programme.stop > now && it.channel.key != playing?.key }
                .map { it.channel to "⚽ ${it.programme.sportHeadline()} · ${it.channel.name}" }
            val previous = previousChannel?.takeIf { p -> p.key != playing?.key && games.none { it.first.key == p.key } }
                ?.let { listOf(it to "↩ Voriger Sender: ${it.name}") }.orEmpty()
            val favorites = runCatching { container.content.zapList(null, favoritesOnly = true, merge = settings.mergeVariants) }.getOrDefault(emptyList())
                .filter { f -> f.key != playing?.key && games.none { it.first.key == f.key } && previous.none { it.first.key == f.key } }
                .take(12).map { it to "★ ${it.name}" }
            pipChoices = games + previous + favorites
            overlayToken++
        }
    }

    fun closePipPicker() {
        pipChoices = null
        overlayToken++
    }

    fun startPip(channel: ChannelEntity) {
        pipChoices = null
        closeOverlay()
        val main = playing
        // Konto mit nur einer Verbindung: Zwei Sender desselben Anbieters gleichzeitig gehen nicht.
        if (main != null && main.playlistId == channel.playlistId && container.accountLimits.maxConnections(channel.playlistId) == 1) {
            message = "Dein Konto erlaubt nur 1 Verbindung: zwei Sender gleichzeitig gehen bei diesem Anbieter nicht."
            handler.postDelayed({ message = null }, 8_000)
            return
        }
        pipAgent = channel.userAgent ?: settings.userAgent
        val preview = pipPlayer ?: de.ayutv.player.dna.engine.DnaEnginePlayer.preview(container.http) { pipAgent }.also { created ->
            created.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Kleines Bild: ${error.errorCodeName}", error)
                    if (pipRetries++ < 3) {
                        handler.postDelayed({ pipPlayer?.let { it.prepare(); it.play() } }, 1_500)
                        return
                    }
                    val limit = Regex("HTTP (\\d+)").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    closePip()
                    message = if (limit != null && limit in 400..499) {
                        "Zweiter Sender abgelehnt (HTTP $limit) – vermutlich erlaubt das Konto nur eine Verbindung."
                    } else "Zweites Bild nicht möglich: ${error.errorCodeName}"
                    handler.postDelayed({ message = null }, 8_000)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) pipRetries = 0
                }
            })
        }
        pipRetries = 0
        pipSplit = getSharedPreferences(PIP_PREFS, MODE_PRIVATE).getBoolean(PIP_SPLIT, false)
        val url = adaptLiveUrl(channel.url)
        preview.setMediaItem(mediaItem(url, guessMime(url)))
        preview.repeatMode = Player.REPEAT_MODE_ONE
        preview.prepare()
        preview.play()
        pipPlayer = preview
        pipChannel = channel
        message = "Kleines Bild: ${channel.name} · ⏩ tauschen · ☰ schließen"
        handler.postDelayed({ if (message?.startsWith("Kleines Bild") == true) message = null }, 5_000)
    }

    /** Hauptbild und kleines Bild tauschen (der Ton folgt dem Hauptbild). */
    fun swapPip() {
        val small = pipChannel ?: return
        val main = playing ?: return
        playChannel(small)
        startPip(main)
    }

    fun closePip() {
        pipPlayer?.release()
        pipPlayer = null
        pipChannel = null
    }

    // ---------------------------------------------------------------- Gemeinsam

    private fun mediaItem(url: String, mime: String?): MediaItem =
        MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()

    private fun vodItem(request: VodRequest): MediaItem = mediaItem(request.url, guessMime(request.url))

    private fun guessMime(url: String): String? {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // ---------------------------------------------------------------- Touch (Handy, Tablet)

    /** Kurze Rückmeldung zu einer Wischgeste (Lautstärke, Helligkeit, Spulen), mittig im Bild. */
    var gestureInfo by mutableStateOf<String?>(null); private set
    private var gestureInfoJob: Job? = null
    /** Bildformat: ganz, füllen (Ränder abgeschnitten) oder gestreckt; gemerkt je Sender/Serie/Film. */
    var aspect by mutableStateOf(AspectMode.FIT); private set
    /** Läuft als kleines Fenster über anderen Apps (Bild-in-Bild). */
    var inPip by mutableStateOf(false); private set
    private var volumeLevel = -1f
    private var brightnessLevel = -1f

    private fun showGestureInfo(text: String) {
        gestureInfo = text
        gestureInfoJob?.cancel()
        gestureInfoJob = lifecycleScope.launch {
            delay(1_200)
            gestureInfo = null
        }
    }

    /** Rechts hoch/runter wischen: [delta] ist der Anteil der Bildschirmhöhe (hoch = positiv). */
    fun adjustVolume(delta: Float) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (volumeLevel < 0) volumeLevel = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
        volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(volumeLevel * max), 0)
        showGestureInfo("🔊 ${Math.round(volumeLevel * 100)} %")
    }

    /** Links hoch/runter wischen: Helligkeit nur für den Player (nicht für das ganze Gerät). */
    fun adjustBrightness(delta: Float) {
        val attributes = window.attributes
        if (brightnessLevel < 0) brightnessLevel = attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
        brightnessLevel = (brightnessLevel + delta).coerceIn(0.02f, 1f)
        attributes.screenBrightness = brightnessLevel
        window.attributes = attributes
        showGestureInfo("☀ ${Math.round(brightnessLevel * 100)} %")
    }

    /** Waagerecht wischen: [fraction] der Bildschirmbreite entspricht bis zu zwei Minuten. Erst beim Loslassen springen. */
    fun seekGesture(fraction: Float, done: Boolean) {
        val deltaMs = (fraction * SWIPE_SEEK_MS / 5_000).toLong() * 5_000
        if (!done) {
            showGestureInfo((if (deltaMs >= 0) "⏩ +" else "⏪ −") + clock(kotlin.math.abs(deltaMs)))
            return
        }
        if (deltaMs == 0L) return
        if (isLive) shiftLive(deltaMs) else seekBy(deltaMs)
    }

    /** Live: senkrecht in der Bildmitte wischen schaltet um (hoch = nächster Sender). */
    fun zapGesture(up: Boolean) {
        if (isLive) zap(if (up) +1 else -1)
    }

    /** Zwei Finger auseinander: Bild füllt den Bildschirm; zusammen: wieder ganz. */
    fun zoomGesture(fill: Boolean) {
        if ((aspect == AspectMode.ZOOM) == fill) return
        chooseAspect(if (fill) AspectMode.ZOOM else AspectMode.FIT)
        showGestureInfo(if (fill) "Bild füllt den Bildschirm" else "Ganzes Bild")
    }

    fun chooseAspect(mode: AspectMode) {
        aspect = mode
        memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(aspect = mode) } }
    }

    // ---------------------------------------------------------------- Merken, Ton-Modus, Schlaf-Timer

    /** Wofür Spuren und Bildformat gemerkt werden: Sender, Serie (alle Folgen gleich) oder Film. */
    private fun memoryKey(): String? = vod?.let { Episodes.seriesKeyOf(it.key) ?: it.key } ?: playing?.key

    /**
     * Gemerkte Ton- und Untertitelsprache schon vor dem Laden einstellen: Der Player wählt dann gleich die richtige Spur
     * und muss nach dem Start nicht umschalten. Umschalten mitten im Abspielen setzt alle Decoder zurück (Flush) – genau
     * danach fiel der MediaTek-Decoder im Xiaomi 11T bei 4K HDR aus. [applyMemory] greift nur noch, wenn es nicht passt.
     */
    private fun presetFromMemory(p: Player) {
        val choice = memoryKey()?.let { container.playerMemory.get(it) } ?: PlayerMemory.Choice()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(choice.audio ?: Locale.getDefault().language)
            .apply {
                if (vod == null) return@apply
                val text = choice.text
                clearOverridesOfType(C.TRACK_TYPE_TEXT)
                when {
                    choice.textOff -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    text != null -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(text)
                    else -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesAutoOn).setPreferredTextLanguage(settings.subtitleLanguage)
                }
            }
            .build()
        if (vod != null) textDisabled = choice.textOff || choice.text == null && !settings.subtitlesAutoOn
    }

    /** Beim ersten Mal, wenn die Spuren des Streams bekannt sind: zuletzt gewählten Ton und Untertitel einstellen. */
    private fun applyMemory(tracks: Tracks) {
        if (memoryApplied || tracks.isEmpty) return
        val key = memoryKey() ?: return
        memoryApplied = true
        val choice = container.playerMemory.get(key)
        val p = player ?: return
        choice.audio?.let { language ->
            val match = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
                .firstOrNull { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) == Languages.normalize(language) }
            if (match != null && !match.first.isTrackSelected(match.second)) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                    .build()
            }
        }
        val text = choice.text
        when {
            choice.textOff -> if (!textDisabled) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                textDisabled = true
            }
            text != null && externalLanguage == null && hasTextTrack(text) -> selectTextLanguage(text)
        }
    }

    // ---------------------------------------------------------------- Chromecast (Handy)

    /**
     * 📺 auf dem Handy: auf einen Fernseher übertragen – einen Fire TV (oder anderen Fernseher) mit DnA-TV im selben
     * WLAN, oder einen Chromecast / Google-TV (nur mit Google-Play-Diensten). Fire TV kann kein Chromecast.
     */
    /** Nur auf Handy/Tablet: Ein Fernseher spielt nicht auf einem anderen Fernseher ab (auch wenn er einen Touchscreen meldet). */
    val castAvailable: Boolean by lazy { hasTouchscreen() && !de.ayutv.ui.apps.AppLauncher.isTv(this) }
    /** Name des Fernsehers, auf dem gerade gespielt wird. */
    var castingTo by mutableStateOf<String?>(null); private set
    /** Läuft gerade auf einem Fernseher mit DnA-TV (statt Chromecast). */
    private var castingToDnaTv = false

    /** Zuletzt benutzter Fernseher („dlna:<IP>|<Name>“ bzw. „dna:<Adresse>“): beim nächsten Mal ohne Auswahl. */
    private var lastTv: String?
        get() = getSharedPreferences("cast", MODE_PRIVATE).getString("last_tv", null)
        set(value) { getSharedPreferences("cast", MODE_PRIVATE).edit().putString("last_tv", value).apply() }

    private fun tvId(renderer: de.ayutv.data.net.DlnaCast.Renderer) = "dlna:${renderer.host}|${renderer.name}"

    /** Laufende Übertragung still beenden, um auf einen anderen Fernseher zu wechseln (ohne zurück aufs Handy). */
    private fun endCastQuietly() {
        castTarget?.let { old -> container.scope.launch { runCatching { de.ayutv.data.net.DlnaCast.stop(container.http, old) } } }
        castJob?.cancel()
        releaseCastRelay()
        castTarget = null
        castingTo = null
        castingToDnaTv = false
        dnaClient = null
        castStatus = null
        castMode = null
    }

    /** Fernbedienung: auf einen anderen Fernseher wechseln (Auswahl, auch wenn einer zuletzt benutzt wurde). */
    fun chooseOtherTv() = toggleCast(choose = true)

    fun toggleCast(choose: Boolean = false) {
        // Bildschirm wird gespiegelt: zuerst anbieten, das Spiegeln zu beenden (sonst gab es dafür keine Option).
        if (!choose && castingTo == null && castTarget == null) mirrorDisplay?.let { display ->
            mirrorMenu(display.name)
            return
        }
        // Läuft schon auf einem Fernseher: nicht einfach beenden, sondern fragen (hier weiterschauen, anderer Fernseher).
        // Die Übertragung läuft so lange weiter, bis man hier etwas wählt.
        if (!choose && castingTo != null) {
            val name = castingTo
            android.app.AlertDialog.Builder(this)
                .setTitle("Läuft auf $name")
                .setItems(arrayOf("📱 Hier auf dem Handy weiterschauen", "📺 Anderer Fernseher …", "Weiter auf $name")) { _, which ->
                    when (which) {
                        0 -> stopCastingHere()
                        1 -> toggleCast(choose = true)
                    }
                }
                .show()
            return
        }
        lifecycleScope.launch { searchAndChooseTv(choose) }
    }

    /** Übertragung beenden und wieder auf dem Handy abspielen (DLNA/webOS, DnA-TV oder Chromecast). */
    fun stopCastingHere() {
        if (castTarget != null) {
            stopCast()
            return
        }
        if (castingTo != null) {
            if (castingToDnaTv) {
                castJob?.cancel()
                castingTo = null
                castingToDnaTv = false
                castStatus = null
                dnaClient = null
                message = "Läuft wieder hier (auf dem Fernseher läuft es weiter)"
                resumeHere()
            } else {
                de.ayutv.player.cast.Cast.stop(this)
            }
        }
    }

    /** Fernseher im WLAN suchen und immer zur Auswahl zeigen (der zuletzt benutzte steht oben). */
    private suspend fun searchAndChooseTv(choose: Boolean) {
        kotlinx.coroutines.coroutineScope {
            message = "Suche Fernseher im WLAN …"
            val client = de.ayutv.data.remote.RemoteClient(applicationContext, container)
            var found = emptyList<de.ayutv.data.remote.RemoteClient.Tv>()
            // Gleichzeitig: Fernseher mit DnA-TV und Fernseher mit DLNA (LG webOS, Samsung, Sony …).
            val dlnaSearch = async { discoverDlna() }
            kotlinx.coroutines.withTimeoutOrNull(2_500) { client.discover().collect { found = it } }
            val dlnaFound = dlnaSearch.await()
            // Schon gekoppelter Fernseher, auch wenn ihn die Suche (Router blockt Ankündigungen) nicht findet.
            val paired = client.address?.takeIf { client.paired && found.none { tv -> tv.address == it } }
                ?.let { de.ayutv.data.remote.RemoteClient.Tv("Gekoppelter Fernseher", it) }
            val tvsFound = found + listOfNotNull(paired)
            val chromecast = de.ayutv.player.cast.Cast.available(this@PlayerActivity)
            message = null
            // Nichts gefunden: trotzdem die Auswahl zeigen – Spiegeln geht auch ohne gefundenes Gerät (Beamer).
            if (tvsFound.isEmpty() && dlnaFound.isEmpty() && !chromecast) {
                message = "Kein Fernseher im WLAN gefunden – Spiegeln geht trotzdem"
                handler.postDelayed({ if (message?.startsWith("Kein Fernseher") == true) message = null }, 6_000)
            }
            // Immer zur Auswahl (früher ging es ohne Frage auf den letzten Fernseher – blieb der schwarz, kam man nicht
            // an die anderen). Der zuletzt benutzte steht oben und ist markiert.
            val last = lastTv
            val tvs = tvsFound.sortedByDescending { "dna:${it.address}" == last }
            val dlna = dlnaFound.sortedByDescending { tvId(it) == last }
            fun mark(isLast: Boolean) = if (isLast) " · zuletzt" else ""
            // Einträge mit Aktion; der zuletzt benutzte Fernseher steht ganz oben (ein Tipp, und es läuft dort).
            fun entry(label: String, isLast: Boolean, action: () -> Unit) = Triple(label, isLast, action)
            val entries = (tvs.map { tv -> entry("📺 ${tv.name}${mark("dna:${tv.address}" == last)}", "dna:${tv.address}" == last) { sendToTv(client, tv) } } +
                dlna.map { r -> entry("📺 ${r.name} (direkt, volle Qualität)${mark(tvId(r) == last)}", tvId(r) == last) { castTo(r) } })
                .sortedByDescending { it.second } +
                listOfNotNull(if (chromecast) entry("Chromecast / Google TV", false) { chooseChromecast() } else null) +
                // Spiegeln (Smart View/Miracast): für Fernseher und Beamer ohne DLNA; das Video läuft dann im Vollbild.
                entry("🪞 Bildschirm spiegeln (Beamer, andere Fernseher) …", false) { openMirroring() }
            android.app.AlertDialog.Builder(this@PlayerActivity)
                .setTitle(if (choose) "Anderer Fernseher" else "Auf Fernseher abspielen")
                .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                    if (choose) endCastQuietly()
                    entries[which].third()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    /** Android-Einstellung zum Spiegeln öffnen (je nach Handy „Smart View“, „Bildschirm übertragen“, „Cast“). */
    private fun openMirroring(stop: Boolean = false) {
        val intents = listOf(
            Intent("android.settings.CAST_SETTINGS"),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        val opened = intents.any { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        message = when {
            stop && opened -> "Zum Beenden dort den Fernseher antippen und „Trennen“ wählen"
            stop -> "Spiegeln über die Schnelleinstellungen des Handys beenden (Smart View antippen)"
            opened -> "Fernseher oder Beamer wählen – das Video läuft dann dort im Vollbild"
            else -> "Spiegeln über die Schnelleinstellungen des Handys starten"
        }
        handler.postDelayed({ message = null }, 8_000)
    }

    private fun chooseChromecast() {
        de.ayutv.player.cast.Cast.choose(
            this,
            onConnected = { name -> startCasting(name) },
            onEnded = {
                castingTo = null
                castStatus = null
                message = "Chromecast beendet – läuft wieder hier"
                resumeHere()
            },
        )
    }

    /** An einen Fernseher mit DnA-TV übergeben; beim ersten Mal nach der PIN fragen (steht dort in den Einstellungen). */
    private fun sendToTv(client: de.ayutv.data.remote.RemoteClient, tv: de.ayutv.data.remote.RemoteClient.Tv) {
        val p = player ?: return
        val request = if (isLive) {
            playing?.let { de.ayutv.data.remote.RemotePlay(live = true, url = it.url, title = it.name, image = it.logo, userAgent = it.userAgent ?: settings.userAgent) }
        } else {
            vod?.let { de.ayutv.data.remote.RemotePlay(false, it.url, it.title, it.key, it.kind, it.subtitle, it.image, p.currentPosition, settings.userAgent) }
        }
        if (request == null || request.url.startsWith("file:")) {
            message = "Aufnahmen und Downloads lassen sich nicht übertragen"
            return
        }
        fun send(pin: String) {
            lifecycleScope.launch {
                var released = false
                runCatching {
                    val name = client.check(tv.address, pin)
                    client.address = tv.address
                    client.pin = pin
                    // Erst hier loslassen: Viele Konten erlauben nur eine Verbindung, der Fernseher braucht sie.
                    p.stop()
                    released = true
                    client.play(request, tv.address, pin)
                    name
                }.onSuccess { name ->
                    castingTo = name
                    castingToDnaTv = true
                    dnaClient = client
                    lastTv = "dna:${tv.address}"
                    castStatus = "${request.title} läuft"
                    message = "📺 Läuft auf $name – das Handy kannst du jetzt sperren oder weglegen. (📺 nochmal: hier weiterschauen)"
                    handler.postDelayed({ if (message?.startsWith("📺 Läuft") == true) message = null }, 8_000)
                }.onFailure { e ->
                    if (released) restartStream()
                    if (e.message?.contains("PIN") == true) askPin(tv) { send(it) }
                    else message = "Fernseher: ${e.message ?: "nicht erreichbar"}"
                }
            }
        }
        val known = client.pin?.takeIf { client.address == tv.address }
        if (known != null) send(known) else askPin(tv) { send(it) }
    }

    private fun askPin(tv: de.ayutv.data.remote.RemoteClient.Tv, onPin: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "PIN"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(tv.name)
            .setMessage("PIN vom Fernseher eingeben: dort Einstellungen → Daten & Info → Handy als Fernbedienung.")
            .setView(input)
            .setPositiveButton("Verbinden") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onPin) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startCasting(name: String) {
        val p = player ?: return
        val media = if (isLive) {
            playing?.let { c ->
                val title = nowNext.firstOrNull()?.headline()?.ifEmpty { null }
                de.ayutv.player.cast.Cast.Media(c.url, c.name, title, c.logo, live = true, positionMs = 0)
            }
        } else {
            vod?.let { de.ayutv.player.cast.Cast.Media(it.url, it.title, it.subtitle, it.image, live = false, positionMs = p.currentPosition) }
        }
        if (media == null || media.url.startsWith("file:")) {
            message = "Aufnahmen und Downloads lassen sich nicht übertragen"
            return
        }
        if (de.ayutv.player.cast.Cast.play(this, media)) {
            castingTo = name
            p.pause()
            message = "📺 Läuft auf $name (📺 nochmal: zurück aufs Handy)"
        }
    }

    /** Laufende Sendung aufnehmen (ohne Programmdaten: die nächste Stunde); nochmal = Aufnahme entfernen. */
    fun recordCurrent() {
        val channel = playing ?: return
        val now = System.currentTimeMillis()
        val programme = nowNext.firstOrNull { it.start <= now && it.stop > now }
        val start = programme?.start ?: (now / 60_000 * 60_000)
        val stop = programme?.stop ?: (start + 60 * 60_000)
        val title = programme?.headline()?.ifEmpty { null } ?: channel.name
        lifecycleScope.launch {
            val on = container.recordings.toggleRecording(
                channel.key, channel.name, title, channel.url, channel.userAgent ?: settings.userAgent, start, stop, programme?.icon ?: channel.logo,
            )
            val warning = if (on) de.ayutv.data.AccountLimits.recordingWarning(container.accountLimits.maxConnections(channel.playlistId)) else null
            message = (if (on) "⏺ Wird aufgenommen bis ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(stop))}: $title" else "Aufnahme entfernt") +
                warning?.let { "\n$it" }.orEmpty()
        }
    }

    /** Erster Sender dieses Players. */
    private var initialChannelKey: String? = null

    /** Erster Stream dieses Players (Film oder Sender): nur für ihn gilt eine beim Start ausdrücklich gewählte Engine. */
    private val initialStreamKey: String? get() = vod?.key ?: initialChannelKey

    /** Startposition eines Films wie beim normalen Start (vom Handy übergeben, von vorn, sonst „Weiter schauen“). */
    private suspend fun vodResumeMs(request: VodRequest): Long {
        val handedOver = intent.getLongExtra(EXTRA_START_AT, 0L)
        return when {
            handedOver > 0 -> handedOver
            intent.getBooleanExtra(EXTRA_FROM_START, false) -> 0L
            else -> container.content.resumePosition(request.key)
        }
    }

    /** Sender, bei dem zuletzt der Video-Decoder abbrach (für den Decoder-Test, auch nach dem Wechsel auf eine andere Fassung). */
    var decoderFailedChannel by mutableStateOf<ChannelEntity?>(null); private set

    /**
     * Decoder-Test: Player anhalten (viele Konten erlauben nur eine Verbindung), einige Sekunden des Senders
     * laden und ohne Media3 direkt mit dem Android-Decoder in mehreren Varianten dekodieren ([DecoderProbe]). Ergebnis
     * auf dem Schirm und im Fehlerbericht; danach läuft der Sender weiter.
     */
    fun runDecoderTest() {
        val channel = decoderFailedChannel ?: playing ?: return
        closeOverlay()
        // Angehalten statt nur gestoppt: Die Störungserkennung (Watchdog) verbindet sonst während des Tests neu – das
        // bräuchte eine zweite Verbindung, die viele Konten nicht haben.
        player?.playWhenReady = false
        player?.stop()
        streamGeneration++
        message = "Decoder-Test für ${channel.name} …"
        lifecycleScope.launch {
            val client = container.http.newBuilder()
                .apply { if (!settings.proxyStreams) proxy(java.net.Proxy.NO_PROXY) }
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val report = DecoderProbe.run(client, adaptLiveUrl(channel.url), channel.userAgent ?: settings.userAgent, cacheDir) { text ->
                runOnUiThread { message = text }
            }
            statusHistory += "Decoder-Test ${channel.name}:\n$report"
            Log.i(TAG, "Decoder-Test ${channel.name}: $report")
            message = "Decoder-Test ${channel.name}\n$report"
            handler.postDelayed({ if (message?.startsWith("Decoder-Test") == true) message = null }, 45_000)
            restartStream()
        }
    }

    var loudnessLevel by mutableStateOf(false); private set

    /**
     * Tor-Hinweis: Trifft ein gefolgter Verein in einem anderen Spiel als dem, das gerade läuft, kurz den neuen
     * Stand einblenden. Fragt jede Minute (OpenLigaDB); das erste Abfragen merkt nur die Stände.
     */
    private fun startGoalWatch() {
        if (!settings.goalAlerts || !settings.liveScores || settings.followedTeams.isEmpty()) return
        lifecycleScope.launch {
            val known = HashMap<Long, Pair<Int, Int>>()
            while (isActive) {
                val now = System.currentTimeMillis()
                val matches = runCatching { container.liveScores.current(now) }.getOrDefault(emptyList())
                val teams = settings.followedTeams
                val watching = playing?.let { nowPlayingMap[it.key] }?.pairing()
                for (m in matches) {
                    if (!m.football || (!m.live(now) && !m.finished)) continue
                    val team = teams.firstOrNull { de.ayutv.data.parse.Teams.plays(it, m.pairing) } ?: continue
                    val before = known.put(m.id, m.goals1 to m.goals2) ?: continue
                    if (before == (m.goals1 to m.goals2) || m.goals1 + m.goals2 <= before.first + before.second) continue
                    if (watching != null && m.matches(watching)) continue
                    val scorer = if (m.goals1 > before.first) m.short1.ifEmpty { m.team1 } else m.short2.ifEmpty { m.team2 }
                    val text = "⚽ Tor für $scorer!  ${m.short1.ifEmpty { m.team1 }} ${m.score} ${m.short2.ifEmpty { m.team2 }}" +
                        (m.lastGoal?.let { " · $it" } ?: "")
                    Log.i(TAG, "Tor-Hinweis ($team): $text")
                    goalNotice = text
                    delay(GOAL_NOTICE_MS)
                    if (goalNotice == text) goalNotice = null
                }
                delay(60_000)
            }
        }
    }

    /** Eingeblendeter Tor-Hinweis (oben im Bild), sonst null. */
    var goalNotice by mutableStateOf<String?>(null); private set

    /** Lautstärke angleichen an/aus (gilt für alle Sender und Filme). */
    fun toggleLoudness() {
        val on = !enhance.leveling
        enhance.leveling = on
        loudnessLevel = on
        container.scope.launch { container.settings.update { it.copy(loudnessLevel = on) } }
    }

    fun chooseAudioMode(mode: AudioMode) {
        audioMode = mode
        enhance.mode = mode
        container.scope.launch { container.settings.update { it.copy(audioMode = mode) } }
    }

    /**
     * Schlaf-Timer: nach [minutes] anhalten und den Player schließen. 0 = aus, [SLEEP_END] = am Ende der
     * laufenden Sendung bzw. des Films.
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        val now = System.currentTimeMillis()
        sleepAt = when {
            minutes == 0 -> 0L
            minutes == SLEEP_END && isLive -> nowNext.firstOrNull()?.stop?.takeIf { it > now } ?: 0L
            minutes == SLEEP_END -> player?.let { p -> (p.duration - p.currentPosition).takeIf { p.duration > 0 && it > 0 }?.let { now + it } } ?: 0L
            else -> now + minutes * 60_000L
        }
        if (sleepAt == 0L) {
            if (minutes != 0) message = "Ende unbekannt – bitte eine feste Zeit wählen"
            return
        }
        message = "Schlaf-Timer: Ende um ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepAt))}"
        sleepJob = lifecycleScope.launch {
            var warned = false
            while (true) {
                val left = sleepAt - System.currentTimeMillis()
                if (left <= 0) break
                if (!warned && left <= 60_000) {
                    warned = true
                    message = "Schlaf-Timer: noch 1 Minute (☰ → Schlaf-Timer zum Verlängern)"
                }
                delay(minOf(left, 5_000))
            }
            sleepAt = 0L
            player?.pause()
            finish()
        }
    }

    /** Home-Taste während der Wiedergabe: als kleines Fenster weiterlaufen (Handy, Tablet). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasTouchscreen() || isFinishing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val p = player ?: return
        if (!p.playWhenReady) return
        val size = p.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            android.util.Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000)
        } else {
            android.util.Rational(16, 9)
        }
        closeOverlay()
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
            .onFailure { Log.w(TAG, "Bild-in-Bild nicht möglich", it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) closeOverlay()
    }

    /** Mobile Daten (oder anderes Netz mit Volumen): Hinweis und auf Wunsch sparsamere Fassung. */
    private fun onMeteredNetwork(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return runCatching { connectivity.isActiveNetworkMetered }.getOrDefault(false) && hasTouchscreen()
    }

    private var meteredHintShown = false

    private fun checkMobileData() {
        if (!onMeteredNetwork()) return
        if (!meteredHintShown) {
            meteredHintShown = true
            subtitleStatus = if (settings.dataSaver) "Mobile Daten: Datensparmodus an (kleinere Fassung, höchstens 720p)"
                else "Mobile Daten: HD braucht etwa 2–3 GB pro Stunde"
        }
    }

    /** Datensparmodus: auf die SD-Fassung eines Senders wechseln, wenn es eine gibt. */
    private fun preferSmallVariant(channel: ChannelEntity) {
        if (!settings.dataSaver || !settings.mergeVariants || switchingVariant || !onMeteredNetwork()) return
        lifecycleScope.launch {
            val small = container.content.variantsOf(channel).firstOrNull { de.ayutv.data.parse.ChannelVariants.rank(it.name) == 4 }
            if (small == null || playing?.key != channel.key) return@launch
            switchingVariant = true
            try { playChannel(small) } finally { switchingVariant = false }
        }
    }

    /** Knopf auf dem Bildschirm: wirkt wie dieselbe Taste der Fernbedienung. */
    fun pressKey(code: Int) {
        if (player == null) return
        overlayToken++
        if (isLive) handleLiveKey(code, 0) else handleVodKey(code, 0)
    }

    /** Tippen ins Bild blendet die Leiste ein oder aus. */
    fun onScreenTap() {
        if (overlay == Overlay.NONE) showOverlay(if (isLive) Overlay.INFO else Overlay.CONTROLS) else closeOverlay()
    }

    /** Tippen auf den Fortschrittsbalken: dorthin springen. */
    fun seekToFraction(fraction: Float) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val base = if (pendingSeekMs != C.TIME_UNSET) pendingSeekMs else p.currentPosition
        seekBy((duration * fraction.coerceIn(0f, 1f)).toLong() - base)
    }

    fun showOverlay(target: Overlay) {
        overlay = target
        overlayToken++
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                retries = 0
                slowRetries = 0
                if (timeshifted && player?.isPlaying == true) timeshiftErrors = 0
                message = null
                everReady = true
                fatalError = false
                if (!isLive) maybeAutoSubtitles()
            }
            if (state == Player.STATE_ENDED && !isLive && shadowing) {
                // Mitlesen beim Übertragen ist durch: nicht die nächste Folge hier starten, der Fernseher läuft weiter.
                endShadow()
            } else if (state == Player.STATE_ENDED && !isLive && UhdSelfTest.isTest(vod?.key)) {
                // 4K-Test: Ergebnis zeigen und merken, dann zurück.
                val d = dna
                val (_, text) = UhdSelfTest.result(stats.height, d?.videoDecoderName.orEmpty(), d?.renderedFrames ?: 0, d?.droppedFrames ?: 0)
                UhdSelfTest.save(this@PlayerActivity, text)
                statusHistory += "4K-Test: $text"
                message = text
                handler.postDelayed({ if (!isFinishing) finish() }, 8_000)
            } else if (state == Player.STATE_ENDED && !isLive) {
                if (vod?.kind != HistoryKind.EPISODE || !episodes.onEnded()) finish()
            }
            // Live-Streams enden nie wirklich: Schließt der Server die Verbindung „sauber“, hält der
            // Player das für das Ende und bliebe stehen. Sofort und ohne Meldung neu verbinden.
            if (state == Player.STATE_ENDED && isLive) {
                // Zwischenspeicher zu Ende (z. B. Speicher voll): live weiter.
                if (timeshifted) handler.post { goLive() } else onLiveStreamEnded()
            }
            if (state == Player.STATE_BUFFERING && everReady) onRebuffer()
        }

        override fun onRenderedFirstFrame() {
            if (zapStartedAt > 0) {
                stats = stats.copy(startupMs = SystemClock.elapsedRealtime() - zapStartedAt)
                zapStartedAt = 0
            }
        }

        override fun onCues(cueGroup: CueGroup) = onCuesChanged(cueGroup)

        override fun onTracksChanged(tracks: Tracks) {
            this@PlayerActivity.tracks = tracks
            applyMemory(tracks)
            checkAudioSupport(tracks)
            updateFormats()
            if (externalLanguage != null) selectSyncReference()
            // Spuren und Länge des Films stehen fest: Untertitel schon jetzt holen, nicht erst, wenn der Puffer voll ist
            // (bei 4K einige Sekunden später). Ob eine eingebettete Spur in der Sprache da ist, ist jetzt schon bekannt.
            if (!isLive && !tracks.isEmpty && (player?.duration ?: C.TIME_UNSET) != C.TIME_UNSET) maybeAutoSubtitles()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Nach einem Sprung zeigt der Taktgeber eine schon laufende Zeile: deren Beginn ist unbekannt.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                ignoreReferenceUntil = SystemClock.elapsedRealtime() + 1_500
                referenceShowing = false
                // Großer Sprung mit geladenen Untertiteln: dort einmal still am Ton nachprüfen.
                if (vod != null && externalLanguage != null && abs(newPosition.positionMs - oldPosition.positionMs) >= SEEK_RECHECK_JUMP_MS) {
                    seekRecheckFrom = newPosition.positionMs
                }
                // Übersetzung mit DeepL: An der neuen Stelle sind die Zeilen noch nicht gelesen. Kurz anhalten, bis
                // sie übersetzt sind, statt erst Englisch und dann Deutsch zu zeigen.
                val format = aheadFormat
                if (vod != null && !isLive && format != null && liveTranslateTarget != null && onlineTranslation() && player?.playWhenReady == true &&
                    abs(newPosition.positionMs - oldPosition.positionMs) >= ONLINE_SEEK_JUMP_MS
                ) {
                    // Zurück an eine schon übersetzte Stelle: kein Halt nötig.
                    val at = newPosition.positionMs
                    val known = embedded?.upcoming(format, at, 50) { it.startMs < at + ONLINE_LEAD_MS }.orEmpty()
                    if (known.isEmpty() || known.any { it.startMs !in aheadTranslations }) startPreroll(format, thorough = true)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateFormats()
            // Gemessene Auflösung merken: bestimmt, welche Fassung doppelter Sender die beste ist, und den Qualitätsfilter.
            if (isLive && videoSize.height > 0) playing?.let { de.ayutv.data.parse.ChannelQuality.recordPlayback(it.key, videoSize.height, stats.startupMs) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            // Mitlesen beim Übertragen: kein Neustart hier (sonst liefe der Film auf dem Handy los) – nur aufhören.
            if (shadowing) {
                Log.w(SUBTITLE_TAG, "Mitlesen beim Übertragen beendet: ${error.errorCodeName}")
                shadowing = false
                p.volume = 1f
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
                return
            }
            Log.w(TAG, "Wiedergabefehler ${error.errorCodeName}", error)
            // 4K-Test: Ein Fehler ist auch ein Ergebnis („kann hier kein 4K“) – sonst wartete der Test ewig auf das Ende.
            if (!isLive && UhdSelfTest.isTest(vod?.key)) {
                val text = "✗ 4K läuft hier nicht (${error.message ?: error.errorCodeName})"
                UhdSelfTest.save(this@PlayerActivity, text)
                statusHistory += "4K-Test: $text"
                message = text
                handler.postDelayed({ if (!isFinishing) finish() }, 8_000)
                return
            }
            playerErrors++
            if (isLive && error.isVideoDecoderError()) playing?.let { decoderFailedChannel = it }
            // Für den Fehlerbericht: welcher Fehler, mit der eigentlichen Ursache.
            statusHistory += "Fehler ${error.errorCodeName}: ${(error.cause?.message ?: error.message).orEmpty().take(140)}"
            // Ursache genauer (Decoder-Diagnose von Android) und was der Video-Decoder bis dahin geschafft hat.
            generateSequence(error.cause) { it.cause }.filterIsInstance<android.media.MediaCodec.CodecException>().firstOrNull()?.let {
                statusHistory += "Decoder-Diagnose: ${it.diagnosticInfo} (Code ${it.errorCode}, wiederholbar ${it.isTransient}/${it.isRecoverable})"
            }
            dna?.let { statusHistory += "DNA Engine: Decoder ${it.videoDecoderName ?: "?"}, ${it.renderedFrames} Bilder gezeigt, ${it.droppedFrames} verworfen" + it.lastInfo.takeIf { i -> i.isNotEmpty() }?.let { i -> ", zuletzt: $i" }.orEmpty() }
            watchLastError = error.errorCodeName.removePrefix("ERROR_CODE_") +
                ((error.cause?.message ?: error.message)?.takeIf { it.isNotBlank() }?.let { ": ${it.take(100)}" } ?: "")
            if (timeshifted) {
                if (++timeshiftErrors <= 2) {
                    handler.postDelayed({ if (timeshifted) dna?.let { dnaReopenAt(it, dnaShownRealtime(it)) } }, 500)
                } else {
                    message = "Zeitversatz gestört – zurück zu Live"
                    goLive()
                }
                return
            }
            when {
                // Fehler während des HDMI-Moduswechsels: einfach weiterspielen, nichts umbauen.
                displaySwitching() && displaySwitchErrors < 3 -> {
                    displaySwitchErrors++
                    handler.postDelayed({
                        player?.let {
                            if (isLive) it.seekToDefaultPosition()
                            it.prepare()
                            if (!frameRateMatcher.switching) it.playWhenReady = true
                        }
                    }, 1_000)
                }
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                // Live-Format HLS eingestellt, der Anbieter liefert diesen Sender aber nicht als HLS (404, kaputte
                // Liste …): sofort über MPEG-TS, und für diesen Anbieter bis zum App-Neustart gleich so (kein
                // dreimaliges Wiederholen einer Adresse, die es nicht gibt, und kein „Sender läuft nicht“).
                isLive && !everReady && playingAdaptedHls(p) && error.errorCode in HLS_MISSING -> {
                    val channel = playing ?: return
                    de.ayutv.data.net.AppProxy.hostOf(channel.url)?.let { noHlsHosts += it }
                    statusHistory += "HLS beim Anbieter nicht verfügbar (${error.errorCodeName}), weiter über MPEG-TS"
                    val text = "Dein Anbieter liefert diesen Sender nicht als HLS – er läuft über MPEG-TS."
                    handler.post {
                        playChannel(channel)
                        message = text
                        handler.postDelayed({ if (message == text) message = null }, 6_000)
                    }
                }
                // Viele Playlists liefern HLS ohne .m3u8-Endung. Dann einmal explizit als HLS versuchen,
                // aber nur vor dem ersten Bild: Später ist ein Lesefehler kaputte Daten, kein falsches Format.
                !triedHls && !everReady && error.isContainerError() -> {
                    triedHls = true
                    val uri = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                    p.setMediaItem(mediaItem(uri, MimeTypes.APPLICATION_M3U8))
                    p.prepare()
                }
                // Auch als HLS nichts: zurück zur ursprünglichen Adresse und normal weiter versuchen.
                triedHls && !everReady && error.isContainerError() &&
                    p.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 &&
                    originalUrl()?.let { guessMime(it) != MimeTypes.APPLICATION_M3U8 } == true -> {
                    val request = vod
                    if (request != null) {
                        p.setMediaItem(vodItem(request), p.currentPosition)
                    } else {
                        val url = originalUrl() ?: return
                        p.setMediaItem(mediaItem(url, guessMime(url)))
                    }
                    retries++
                    retryLater()
                }
                // Format lehnt der Decoder auch ohne Sonderwege ab. Einmal wiederholen (der 4K-Decoder ist beim Zappen
                // oft noch belegt und meldet dann „zu hohe Anforderungen“), dann klar sagen, woran es liegt.
                !everReady && error.isUnsupportedFormat() && retries >= 1 -> {
                    val reason = unsupportedFormatMessage()
                    if (!tryNextVariant(reason, countFailure = false)) {
                        fatalError = true
                        message = reason
                    }
                }
                // Decoder-Start gescheitert: oft nur kurz belegt (4K-Decoder beim Zappen). Einmal neu versuchen.
                error.isFormatError() && !everReady && retries >= 1 -> {
                    val reason = unsupportedFormatMessage()
                    if (!tryNextVariant(reason, countFailure = false)) {
                        fatalError = true
                        message = reason
                    }
                }
                // Startet gar nicht und dasselbe Spiel läuft auf einem anderen Sender: nach einem Versuch dorthin, statt
                // über eine Minute zu warten.
                !everReady && isLive && hasAlternative() && retries >= 1 && tryNextVariant("Sender startet nicht") -> Unit
                retries < MAX_RETRIES -> {
                    retries++
                    // Erst Bescheid sagen, wenn nach 3 s noch kein Bild da ist: Meist läuft es nach dem Neustart gleich weiter.
                    val generation = streamGeneration
                    val attempt = retries
                    handler.postDelayed({
                        if (generation == streamGeneration && player?.playbackState != Player.STATE_READY) {
                            // Liegt es am Proxy, das sagen: sonst sucht man den Fehler beim Anbieter.
                            val proxy = de.ayutv.data.net.AppProxy.recentFailure()
                            message = (proxy?.let { "$it – " } ?: "") + "Verbindung wird wiederhergestellt … ($attempt/$MAX_RETRIES)"
                        }
                    }, 3_000)
                    retryLater()
                }
                else -> {
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Der Anbieter lehnt den Stream ab (z. B. zu viele Verbindungen oder Konto abgelaufen)"
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Server"
                        // Allgemeiner Lesefehler: Der Sender lieferte (über Proxy oder direkt) eine Weile nichts Brauchbares.
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "Sender liefert gerade nichts" +
                            (if (routeLabel().startsWith("Weg: über Proxy")) " (Anbieter oder Proxy-Weg)" else " (Anbieter)")
                        else -> "Stream nicht verfügbar (${error.errorCodeName})"
                    }.let { base -> de.ayutv.data.net.AppProxy.recentFailure()?.let { "$it (Einstellungen → Proxy)" } ?: base }
                    if (tryNextVariant(reason)) return
                    // Live-Sender fallen beim Anbieter oft nur kurz aus: in größeren Abständen selbst weiter versuchen,
                    // statt aufzugeben. Umschalten geht jederzeit.
                    if (isLive && slowRetries < SLOW_RETRY_SECONDS.size && error.errorCode !in NO_SLOW_RETRY) {
                        val wait = SLOW_RETRY_SECONDS[slowRetries++]
                        retries = 0
                        statusHistory += "Sender liefert nichts, neuer Versuch in $wait s (${slowRetries}/${SLOW_RETRY_SECONDS.size})"
                        message = "$reason – neuer Versuch in $wait s (▲▼ anderer Sender)"
                        val generation = streamGeneration
                        handler.postDelayed({ if (generation == streamGeneration) restartStream() }, wait * 1000L)
                        return
                    }
                    fatalError = true
                    message = reason
                }
            }
        }
    }

    /** Decoder-Namen und verworfene Bilder der DNA Engine in die Anzeige (Stream-Info, Fehlerbericht). */
    private fun refreshEngineStats() {
        dna?.let { d ->
            val decoder = d.videoDecoderName.orEmpty()
            val audioDecoder = d.audioDecoderName.orEmpty()
            if (decoder != stats.decoder || audioDecoder != stats.audioDecoder || d.droppedFrames != stats.droppedFrames) {
                stats = stats.copy(decoder = decoder, audioDecoder = audioDecoder, droppedFrames = d.droppedFrames)
            }
        }
    }

    private fun updateFormats() {
        val p = player ?: return
        refreshEngineStats()
        updateAudioAhead()
        val video = p.videoFormat
        val audio = p.audioFormat
        stats = stats.copy(
            width = video?.width ?: 0,
            height = video?.height ?: 0,
            // Gemessene Bildrate behalten, wenn der Stream selbst keine angibt.
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: stats.frameRate.takeIf { stats.frameRateMeasured } ?: 0f,
            videoCodec = video?.sampleMimeType?.substringAfter('/') ?: "",
            videoBitrate = video?.bitrate ?: 0,
            audioCodec = audio?.sampleMimeType?.substringAfter('/') ?: "",
            audioChannels = audio?.channelCount ?: 0,
            displayHz = frameRateMatcher.currentRefreshRate(),
            hdr = when {
                video == null -> ""
                video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
                video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> ""
            },
        )
        video?.frameRate?.takeIf { it > 1f }?.let { fps -> memoryKey()?.let { container.playerMemory.rememberFrameRate(it, fps, isLive) } }
        if (settings.matchFrameRate) video?.frameRate?.let(frameRateMatcher::match)
        suggestFrameRateMatching()
        checkDynamicRange(video)
    }

    /**
     * Bild-Check (einmal je App-Start): Ein HLG-Stream (4K-Sport, Sender/Sender UHD) auf einem Fernseher, der HLG nicht
     * meldet, läuft nur in SDR. Ob der Fire TV gerade HDR *ausgibt*, kann eine App nicht erfahren: `Display.isHdr`
     * sagt nur, dass der Fernseher HDR *kann*. Der frühere Hinweis „Fire TV gibt gerade HDR aus“ erschien deshalb
     * bei jedem SDR-Sender auf jedem HDR-Fernseher und ist entfernt.
     */
    private fun checkDynamicRange(video: androidx.media3.common.Format?) {
        if (dynamicRangeHintShown || video == null || video.height <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val transfer = video.colorInfo?.colorTransfer
        val hint = when {
            transfer == C.COLOR_TRANSFER_HLG &&
                display.hdrCapabilities?.supportedHdrTypes?.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HLG) == false ->
                "Dieser Sender sendet HLG (HDR), dein Fernseher meldet aber kein HLG – das Bild läuft deshalb nur in SDR. " +
                    "Am Fernseher für diesen HDMI-Eingang „HDMI Ultra HD Deep Colour“ / „Erweitertes Format“ einschalten"
            else -> null
        } ?: return
        dynamicRangeHintShown = true
        subtitleStatus = hint
    }

    /**
     * 50-Bilder-Sender auf einem 60-Hz-Bild: Jedes fünfte Bild bleibt doppelt so lange stehen, das
     * Bild läuft sichtbar ungleichmäßig. Einmal pro App-Start darauf hinweisen, wie man es abstellt.
     */
    private fun suggestFrameRateMatching() {
        if (afrHintShown || !isLive || settings.matchFrameRate) return
        val fps = stats.frameRate
        val hz = stats.displayHz
        val pal = fps in 24.5f..25.5f || fps in 49.5f..50.5f
        if (!pal || hz !in 59f..61f) return
        afrHintShown = true
        subtitleStatus = "Bild nicht ganz flüssig? Sender hat 50 Bilder/s, der Fernseher läuft mit 60 Hz. " +
            "Einstellungen → Wiedergabe → „Bildwiederholrate anpassen“ einschalten"
    }

    // ---------------------------------------------------------------- Tasten

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Fernbedienung: Lautstärketasten des Handys regeln den Fernseher (DLNA).
        if (castTarget != null && remoteHasVolume && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) remoteVolume(if (code == KeyEvent.KEYCODE_VOLUME_UP) 2 else -2)
            return true
        }
        // Vorlauf der Übersetzung: OK oder Zurück schaut sofort weiter (Zeilen ohne Übersetzung bleiben im Original).
        if (prerollProgress != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY)
        ) {
            if (event.action == KeyEvent.ACTION_UP) endPreroll()
            return true
        }
        if (dueReminder != null && overlay != Overlay.CHANNELS && overlay != Overlay.TRACKS &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK ||
                (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && isLive))
        ) {
            if (event.action == KeyEvent.ACTION_UP) handleReminderKey(code)
            return true
        }
        if (quickZap != null && handleQuickKey(event)) return true
        // OK hat gerade die Senderliste geöffnet: das Loslassen derselben Taste gehört noch dazu. Sonst landete es schon
        // in der Liste und „drückte“ den markierten Sender – die Liste ging sofort wieder zu oder es lief ein anderer Sender.
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && swallowOkUp) {
            // Neuer Druck (das Loslassen kam nicht an): ab hier wieder normal.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) swallowOkUp = false
            else {
                if (event.action == KeyEvent.ACTION_UP) swallowOkUp = false
                return true
            }
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    syncPanel && overlay == Overlay.NONE -> closeSyncPanel()
                    overlay == Overlay.CHANNELS && groupMode -> hideGroups()
                    languagePicker -> closeLanguagePicker()
                    epgPicker -> closeEpgPicker()
                    pipChoices != null -> closePipPicker()
                    // Im Player-Menü geht Zurück erst von der Unterseite zur Übersicht.
                    overlay == Overlay.TRACKS && menuPage != null -> openMenuPage(null)
                    // Senderliste mit kleinem Bild: noch einmal Zurück verlässt den Player.
                    overlay == Overlay.CHANNELS && miniVideo -> finish()
                    // Spiegeln: Senderliste nur auf dem Handy, der Fernseher zeigt weiter das Bild; noch einmal Zurück
                    // verlässt den Player (dann zeigt der Fernseher wieder den gespiegelten Handybildschirm).
                    overlay == Overlay.CHANNELS && mirrorDisplay != null -> finish()
                    overlay != Overlay.NONE -> closeOverlay()
                    isLive && channels.isNotEmpty() && mirrorDisplay != null -> openChannelList()
                    // Live-TV: erst Senderliste mit kleinem Bild, statt sofort zu den Menüs.
                    // (Nur mit genug Breite für Liste und Bild daneben, nicht auf dem Handy im Hochformat.)
                    isLive && channels.isNotEmpty() && pipPlayer == null && resources.configuration.screenWidthDp >= 900 -> openMiniChannelList()
                    else -> finish()
                }
            }
            return true
        }
        // Noch nicht bereit (Einstellungen/Player werden geladen).
        if (player == null) return super.dispatchKeyEvent(event)
        if (syncPanel && overlay == Overlay.NONE && handleSyncKey(event)) return true
        // In Senderliste und Spurmenü übernimmt Compose die Navigation.
        if (overlay == Overlay.CHANNELS || overlay == Overlay.TRACKS) {
            if (event.action == KeyEvent.ACTION_DOWN) overlayToken++
            return super.dispatchKeyEvent(event)
        }
        // ◀ kurz: zum letzten Sender (beim Loslassen, damit Halten die Schnellwahl öffnen kann).
        if (isLive && !timeshifted && code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_UP) {
            if (!quickOpenedThisPress) backZap() ?: openChannelList()
            quickOpenedThisPress = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return isHandledKey(code) || super.dispatchKeyEvent(event)
        return if (isLive) handleLiveKey(code, event.repeatCount) else handleVodKey(code, event.repeatCount)
    }

    private fun isHandledKey(code: Int) = code in HANDLED_KEYS || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

    private fun handleLiveKey(code: Int, repeat: Int): Boolean {
        val p = player
        // Gedrückt halten spult immer schneller (wie bei Filmen).
        val step = when {
            repeat > 20 -> 120_000L
            repeat > 5 -> 60_000L
            else -> 0L
        }
        // Zeitversetzt: OK hält an, ◀ ▶ spulen. Umschalten und Menü bleiben wie gewohnt.
        if (timeshifted) when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleLivePause(code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                shiftLive(-(if (step > 0) step else 10_000L))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                shiftLive(if (step > 0) step else 30_000L)
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (settings.zapWithUpDown) zap(+1) else openChannelList()
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (settings.zapWithUpDown) zap(-1) else openChannelList()
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                openChannelList()
                swallowOkUp = true
            }
            // ◀ springt zum vorherigen Sender (und von dort wieder zurück); ohne vorherigen öffnet es die Liste.
            // ◀ halten (etwa eine halbe Sekunde): Schnellwahl mit zuletzt gesehenen Sendern und Favoriten.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (repeat == 3 && !quickOpenedThisPress) {
                quickOpenedThisPress = true
                openQuickZap()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (overlay == Overlay.INFO) showTech = !showTech
                showOverlay(Overlay.INFO)
            }
            // "Zurück zum letzten Sender", wie beim Fernseher.
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_PROG_BLUE -> backZap()
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_PROG_RED -> {
                toggleFavorite()
                showOverlay(Overlay.INFO)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> toggleLivePause(code)
            KeyEvent.KEYCODE_MEDIA_REWIND -> shiftLive(-(if (step > 0) step else 10_000L))
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (pipPlayer != null && !timeshifted && repeat == 0) swapPip() else shiftLive(if (step > 0) step else 30_000L)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> onDigit(code - KeyEvent.KEYCODE_0)
            else -> return false
        }
        return true
    }

    private fun handleVodKey(code: Int, repeat: Int): Boolean {
        val p = player ?: return false
        // Gedrückt halten spult immer schneller.
        val step = when {
            repeat > 20 -> 120_000L
            repeat > 5 -> 60_000L
            else -> 0L
        }
        // Nach dem Ende einer Folge: OK startet die nächste sofort, alles andere hält den Countdown an.
        if (p.playbackState == Player.STATE_ENDED && nextCardVisible) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) playNextEpisode() else episodes.cancelCountdown()
            return true
        }
        // Letzte Minute mit Countdown: Jede andere Taste hält ihn an (die Taste wirkt trotzdem, z. B. Spulen).
        if (nextCountdown >= 0 && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) episodes.cancelCountdown()
        if (overlay != Overlay.CONTROLS && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (nextCardVisible) {
                playNextEpisode()
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (overlay == Overlay.CONTROLS || !p.playWhenReady) p.playWhenReady = !p.playWhenReady
                showOverlay(Overlay.CONTROLS)
            }
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_CAPTIONS -> openTracks(MenuPage.SUBTITLES)
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-(if (step > 0) step else 10_000L))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(if (step > 0) step else 30_000L)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                if (code == KeyEvent.KEYCODE_INFO || overlay == Overlay.CONTROLS) showTech = !showTech
                showOverlay(Overlay.CONTROLS)
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onStart() {
        super.onStart()
        // Läuft es auf dem Fernseher, bleibt das Handy die Fernbedienung (kein Neustart hier).
        if (castingRemote) return
        player?.let { p ->
            if (p.playbackState == Player.STATE_IDLE) {
                streamGeneration++
                restartStream()
            } else {
                p.playWhenReady = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Fire-TV-Startseite: Weiterschauen mit dem neuen Stand.
        container.scope.launch {
            delay(1_000)
            runCatching { de.ayutv.data.FireTvHome.update(applicationContext, container) }
        }
        saveAheadTranslations()
        recordWatch()
        container.scope.launch(Dispatchers.IO) { de.ayutv.data.parse.ChannelQuality.saveIfChanged(container.qualityFile) }
        // Für den Fehlerbericht: was zuletzt lief und was der Player gemeldet hat.
        de.ayutv.data.Diagnostics.savePlayerSession(
            this,
            if (isLive) playing?.name.orEmpty() else vodTitle,
            statusHistory + listOf("Engine: $engineLabel" + ((player as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.lastInfo?.takeIf { it.isNotEmpty() }?.let { " (zuletzt: $it)" } ?: ""), "Technik: $stats", "Abbrüche: ${dropInfo.ifEmpty { "keine" }}") +
                // Was die Engine selbst gemacht hat (Decoder, HEVC-Kopf, erstes Bild, Fehler, Neuverbinden).
                listOf("Engine-Protokoll:") + de.ayutv.player.dna.engine.DnaEngineCore.journal().takeLast(100).map { "  $it" },
        )
        // Bild-in-Bild-Fenster geschlossen: Player beenden statt unsichtbar im Hintergrund zu bleiben.
        if (inPip) {
            inPip = false
            finish()
        }
        // Noch nicht ausgeführtes Schnell-Zappen verwerfen: im Hintergrund keinen neuen Stream öffnen.
        if (zapJob != null) {
            zapJob?.cancel()
            zapJob = null
            zapTarget = null
            current = playing
            playing?.let(::loadChannelInfo)
        }
        saveProgress()
        // Zeitversatz endet im Hintergrund (keine Aufnahme ohne Bild); beim Zurückkommen läuft wieder live.
        if (isLive && timeshifted) {
            leaveTimeshift()
            player?.let { p -> playing?.let { setLiveItem(p, it) } }
        }
        // Live-Streams im Hintergrund nicht weiterladen: spart Bandbreite und eine Verbindung beim Anbieter.
        player?.let { p -> if (isLive) p.stop() else p.pause() }
        closePip()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(mirrorListener) }
        // Die Weiterleitung zum Fernseher läuft im Dienst weiter (beenden über die Benachrichtigung); nur der Rückruf
        // in diesen Player fällt weg.
        if (CastService.onStopped === castStoppedCallback) CastService.onStopped = null
        watchdogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        frameRateMatcher.reset()
        audioAhead.release()
        closePip()
        player?.release()
        player = null
        closeDnaTimeshift()
        // Keinen Verweis auf diese Activity im App-weiten Übersetzer behalten und den Übersetzer-Prozess
        // beenden: Seine Modelle (gut 200 MB) brauchen außerhalb des Players keinen Speicher.
        Translators.remote.onProgress = {}
        Translators.remote.release()
    }

    companion object {
        /** Fernseher, auf dem gerade abgespielt wird – gilt für jeden Player, bis man „hier weiterschauen“ wählt. */
        @Volatile var activeTv: de.ayutv.data.net.DlnaCast.Renderer? = null
        /** Hinweis auf 50 Hz nur einmal pro App-Start, nicht bei jedem Sender. */
        private var afrHintShown = false
        private var dynamicRangeHintShown = false
        /** Anbieter (Host), die kein HLS liefern: bis zum App-Neustart MPEG-TS (nur im Speicher). */
        private val noHlsHosts = java.util.Collections.synchronizedSet(HashSet<String>())
        /** Fehler, an denen man erkennt, dass es die HLS-Fassung nicht gibt (nicht: Netz weg, Zeitüberschreitung). */
        private val HLS_MISSING = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
        private const val MAX_RETRIES = 3
        /** Nach den schnellen Versuchen: Live-Sender noch so oft in diesen Abständen neu versuchen (zusammen gut 1,5 Minuten). */
        private val SLOW_RETRY_SECONDS = intArrayOf(10, 20, 30, 45)
        /** Ablehnung durch den Anbieter (Konto, Verbindungen): langes Warten hilft nicht. */
        private val NO_SLOW_RETRY = setOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        private const val ZAP_BURST_MS = 600L
        private const val ZAP_COMMIT_MS = 400L
        private const val TAG = "AyuPlayer"
        private const val REMINDER_VISIBLE_MS = 40_000L
        private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
        private const val DISPLAY_SWITCH_GRACE_MS = 6_000L
        const val SUBTITLE_TAG = "AyuSubtitles"
        private val STANDARD_FRAME_RATES = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)
        private const val LIVE_STALL_SECONDS = 8
        /** LG, Umschalten im laufenden Mediaplayer: so lange darf er nach dem ersten neuen Stück brauchen, es abzuholen. */
        private const val SWITCH_FOLLOW_MS = 8_000L
        /** … und so lange höchstens insgesamt (das erste Stück über den Proxy kann dauern). */
        private const val SWITCH_FOLLOW_MAX_MS = 40_000L
        /** So viel später drückt man OK, nachdem man die Zeile gehört hat. */
        private const val REACTION_MS = 400L
        private const val SYNC_LINES_BACK_MS = 2 * 60_000L
        private const val SYNC_LINES_AHEAD_MS = 3 * 60_000L
        /** Übliche Bildraten-Unterschiede zwischen Untertitel-Datei und Film. */
        private val SYNC_RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)
        private const val DROP_WINDOW_MS = 10 * 60_000L
        /** Angenommene Dauer einer Einblendung ohne bekanntes Ende (Live-Übersetzung, Zeilen überspringen). */
        private const val ASSUMED_LINE_MS = 3_000L
        /** Vorlauf der Übersetzung: so viel Film wird vorab übersetzt, höchstens so lange angehalten. */
        private const val PREROLL_WINDOW_MS = 60_000L
        private const val PREROLL_MAX_MS = 40_000L
        /** So oft wird ein hängender Übersetzer neu gestartet, bevor die Spur im Original bleibt. */
        private const val MAX_TRANSLATOR_RESTARTS = 2
        /** So viele Zeilen gehen höchstens in eine Anfrage an einen Übersetzer im Internet (DeepL erlaubt 50). */
        private const val ONLINE_BATCH = 40
        /** Nur in den ersten Sekunden entscheidet sich, ob angehalten wird. */
        private const val PREROLL_DECIDE_MS = 3_000L
        /** DeepL: Vorlauf beim Start und nach dem Spulen, mindestens und höchstens. */
        private const val ONLINE_PAUSE_MIN_MS = 8_000L
        private const val ONLINE_PAUSE_MAX_MS = 10_000L
        /** DeepL: So viel Film muss nach dem Vorlauf geladen und übersetzt sein. */
        private const val ONLINE_LEAD_MS = 30_000L
        /** Schon übersetzt gilt eine Stelle mit so vielen Zeilen in den nächsten zwei Minuten. */
        private const val PREPARED_WINDOW_MS = 120_000L
        private const val PREPARED_MIN_LINES = 10
        /** DeepL: So lange bleibt eine noch nicht übersetzte Zeile leer, bevor das Original erscheint. */
        private const val ONLINE_HOLD_MS = 1_500L
        /** DeepL: Ab so einem Sprung gibt es an der neuen Stelle einen kurzen Vorlauf. */
        private const val ONLINE_SEEK_JUMP_MS = 5_000L
        private const val DROP_WARN_COUNT = 3
        private const val FIRST_CONNECT_STALL_SECONDS = 15
        /** So viele eingebettete Einblendungen (gut 4 Minuten Dialog) ohne gemeinsamen Takt: falsche Datei. */
        private const val WRONG_FILE_REFERENCES = 60
        /** OpenSubtitles bleibt ab so viel Prozent Übereinstimmung, darunter wird zur Übersetzung der Spur gewechselt. */
        private const val FIT_PERCENT = 90
        /** So viele Einblendungen, bevor endgültig entschieden wird (meist die ersten 20–30 s Dialog, vorausgelesen). */
        private const val DECIDE_REFERENCES = 20
        /** Darunter ist die Datei schon bei den ersten Einblendungen klar daneben. */
        private const val EARLY_MISS_PERCENT = 40
        /** Zeilen-Abgleich: ab so viel Prozent übernehmen, darunter vergleichen. */
        private const val ELASTIC_MIN_PERCENT = 50
        /** Abschnittsweise am Ton: so gut muss die Datei mit den Sprach-Einsätzen passen (fremde Filme: unter 40 %). */
        private const val SPEECH_ELASTIC_PERCENT = 45
        /** … und so deutlich muss ihre beste Gesamtlage über dem Rauschen liegen, wenn sie nicht eindeutig ist. */
        private const val SPEECH_ELASTIC_MIN_Z = 4.0
        private const val PIP_PREFS = "multiview"
        /** So lange darf das erste Stück eines Senders über den Proxy dauern, bevor der Fernseher geöffnet wird. */
        private const val WINDOW_FIRST_PART_SECONDS = 45
        private const val DECODER_PREFS = "decoder"
        /** Neuer Name ab 0.50.2: Die Markierung aus 0.50.1 (nach nur einem Fehler gesetzt) gilt nicht mehr. */
        private const val PHONE_ASYNC_BROKEN = "phone_async_broken_v2"
        private const val PHONE_ASYNC_FAILURES = "phone_async_failures"
        private const val PIP_SPLIT = "split"
        /** Weiter als so weit vom nächsten Anker weg gilt der Zeilen-Abgleich dort als ungeklärt. */
        private const val ELASTIC_COVER_MS = 90_000L
        /** So viele Einblendungen des Films, bevor weitere Dateien verglichen werden (Downloads sind begrenzt). */
        private const val COMPARE_REFERENCES = 40
        private const val SPEECH_CHECK_MS = 20_000L
        private const val SPEECH_FIRST_CHECK_MS = 2_000L
        /** So weit lädt der Player voraus, solange geladene Untertitel am Ton ausgerichtet werden (µs). */
        private const val SUBTITLE_READ_AHEAD_US = 150_000_000L
        /** So lange auf Ton aus der Auswertung nebenbei warten, bevor der Ton im Gerät dekodiert wird (Soundbar). */
        private const val SPEECH_PCM_FALLBACK_MS = 45_000L
        // Bis so weit voraus liegt nebenbei ausgewerteter Ton (weites Vorausladen während der Ausrichtung).
        private const val SPEECH_AHEAD_MS = 160_000L

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        private const val SPEECH_WINDOW_MS = 15 * 60_000L
        private const val GOAL_NOTICE_MS = 8_000L
        /** Liegt die vorherige Zeile höchstens so weit zurück, gehört sie zum Gespräch (Zusammenhang). */
        private const val CONTEXT_GAP_MS = 3_000L
        /** Ab diesem Sprung wird an der neuen Stelle nachgeprüft … */
        private const val SEEK_RECHECK_JUMP_MS = 3 * 60_000L
        /** … sobald so viel ab dort gelaufen ist … */
        private const val SEEK_RECHECK_MS = 90_000L
        /** … und nur ein sicherer Takt (wie „sicher“ in der Anzeige) ändert eine Ausrichtung von Hand. */
        private const val SEEK_RECHECK_VOTES = 80
        /** So viel Ton im Fenster ohne Übereinstimmung: falsche Datei. */
        private const val SPEECH_WRONG_FILE_SECONDS = 8 * 60
        /**
         * Nach so viel Gespräch ohne Takt werden weitere Treffer der Suche verglichen (erste Runde lädt, die weiteren bewerten
         * neu). Früher erst nach 90 s – die richtige Datei kam so oft erst nach Minuten; der Vergleich übernimmt nur eine
         * Datei, die klar vorn liegt, früher anzufangen kostet nur Downloads (Kontingent reicht).
         */
        private val COMPARE_ROUNDS_SECONDS = listOf(45, 90, 180, 300)
        private const val VIDEO_FREEZE_SECONDS = 6
        private const val CUSHION_LOW_MS = 2_500L
        private const val CUSHION_HIGH_MS = 5_000L
        // Bei 50 Hz bringt 0,985 nur alle ~1,3 s ein doppeltes Bild (0,97 ruckelte bei Schwenks sichtbar).
        private const val CUSHION_SPEED = 0.985f
        private const val VOD_STALL_SECONDS = 20
        /** Über die ganze Bildschirmbreite wischen spult so weit. */
        private const val SWIPE_SEEK_MS = 120_000f
        /** Schlaf-Timer bis zum Ende der Sendung / des Films. */
        const val SLEEP_END = -1
        /** Näher als das am Live-Bild gilt als live. */
        private const val LIVE_EDGE_MS = 4_000L
        /** Mindestens so viel freier Speicher, sonst kein Zeitversatz. */
        private const val EXTRA_CHANNEL_KEY = "channel_key"
        private const val EXTRA_GROUP = "group"
        /** Kategorie „Favoriten“ in der Auswahl der Senderliste. */
        private const val FAVORITES_GROUP = "\u0000favoriten"
        private const val EXTRA_FAVORITES = "favorites"
        private const val EXTRA_CAST_ON_START = "cast_on_start"
        /** Andere Sender mit derselben Übertragung (Sport-Guide): Ausweichen, wenn alle Fassungen ausfallen. */
        private const val EXTRA_ALTERNATIVES = "alternatives"
        private const val EXTRA_FROM_START = "from_start"
        /** Startposition in ms (vom Handy übergeben). */
        const val EXTRA_START_AT = "start_at"
        private const val EXTRA_LIVE_URL = "live_url"
        private const val EXTRA_LIVE_NAME = "live_name"
        private const val EXTRA_LIVE_LOGO = "live_logo"
        private const val EXTRA_LIVE_AGENT = "live_agent"

        /** Sender, den es hier nicht gibt (vom Handy übergeben), direkt über seine Adresse spielen. */
        fun liveUrlIntent(context: Context, url: String, name: String, logo: String?, userAgent: String?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE_URL, url)
                .putExtra(EXTRA_LIVE_NAME, name)
                .putExtra(EXTRA_LIVE_LOGO, logo)
                .putExtra(EXTRA_LIVE_AGENT, userAgent)
        private const val EXTRA_VOD_KEY = "vod_key"
        private const val EXTRA_VOD_KIND = "vod_kind"
        private const val EXTRA_VOD_TITLE = "vod_title"
        private const val EXTRA_VOD_SUBTITLE = "vod_subtitle"
        private const val EXTRA_VOD_IMAGE = "vod_image"
        private const val EXTRA_VOD_URL = "vod_url"

        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_LAST_CHANNEL,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CAPTIONS,
        )

        fun liveIntent(
            context: Context, channelKey: String, group: String?, favoritesOnly: Boolean = false, alternatives: List<String> = emptyList(),
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_KEY, channelKey)
                .putExtra(EXTRA_GROUP, group)
                .putExtra(EXTRA_FAVORITES, favoritesOnly)
                .putStringArrayListExtra(EXTRA_ALTERNATIVES, ArrayList(alternatives))

        fun vodIntent(context: Context, request: VodRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_VOD_KEY, request.key)
                .putExtra(EXTRA_VOD_KIND, request.kind.name)
                .putExtra(EXTRA_VOD_TITLE, request.title)
                .putExtra(EXTRA_VOD_SUBTITLE, request.subtitle)
                .putExtra(EXTRA_VOD_IMAGE, request.image)
                .putExtra(EXTRA_VOD_URL, request.url)

        fun startLive(
            context: Context, channelKey: String, group: String?, favoritesOnly: Boolean = false, alternatives: List<String> = emptyList(),
        ) = context.startActivity(liveIntent(context, channelKey, group, favoritesOnly, alternatives))

        fun startVod(context: Context, request: VodRequest, fromStart: Boolean = false) =
            context.startActivity(vodIntent(context, request).putExtra(EXTRA_FROM_START, fromStart))

        /** Vergangene Sendung aus dem Archiv des Anbieters: läuft wie ein Film (spulbar). */
        fun startCatchup(context: Context, channel: ChannelEntity, programme: ProgrammeEntity, url: String) = startVod(
            context,
            VodRequest(
                key = "catchup:${channel.key}:${programme.start}",
                kind = HistoryKind.CATCHUP,
                title = programme.headline().ifEmpty { channel.name },
                subtitle = "${channel.name} · Archiv · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(programme.start))}",
                image = channel.logo,
                url = url,
            ),
        )

        private fun Intent.getVodRequest(): VodRequest? {
            val key = getStringExtra(EXTRA_VOD_KEY) ?: return null
            val url = getStringExtra(EXTRA_VOD_URL) ?: return null
            return VodRequest(
                key = key,
                kind = runCatching { HistoryKind.valueOf(getStringExtra(EXTRA_VOD_KIND).orEmpty()) }.getOrDefault(HistoryKind.VOD),
                title = getStringExtra(EXTRA_VOD_TITLE).orEmpty(),
                subtitle = getStringExtra(EXTRA_VOD_SUBTITLE),
                image = getStringExtra(EXTRA_VOD_IMAGE),
                url = url,
            )
        }
    }
}

/** Untertitel auf dem Fernseher: aus bzw. wie auf dem Handy (sonst Index der eingebetteten Spur). */
private const val SUBS_OFF = -2
private const val SUBS_PHONE = -1

/** Tonformate, die Fernseher selbst können (alles andere wird beim Umverpacken in Dolby Digital umgerechnet). */
private val TV_AUDIO = setOf("aac", "ac3", "eac3", "mp3", "mp2")

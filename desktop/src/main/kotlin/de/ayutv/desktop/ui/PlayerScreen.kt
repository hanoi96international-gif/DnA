package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import de.ayutv.data.parse.Episodes
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.numberOf
import de.ayutv.desktop.data.Programme
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.digit
import de.ayutv.desktop.input.remote
import de.ayutv.desktop.player.StreamInfo
import kotlinx.coroutines.delay

/** Was über dem Bild liegt. */
private enum class Layer { NONE, INFO, CHANNELS }

/**
 * Wiedergabe, bedient wie die Fire-TV-App.
 *
 * Live TV: ↑/↓ (oder Sender ±) schalten um, OK öffnet die Senderliste, ◀ springt zum letzten Sender, ▶ bzw. Info
 * zeigt Programm-Info (zweimal: Stream-Info), ☰ Menü öffnet Ton/Untertitel/Favorit, Zifferntasten wählen einen
 * Sender, ⏯ hält an, Rot setzt einen Favoriten, Blau = letzter Sender.
 * Filme/Serien: OK oder ⏯ hält an, ◀/▶ und ⏪/⏩ spulen (gehalten immer schneller), ↑/↓ zeigen die Leiste
 * (zweimal: Stream-Info), ☰ Ton und Untertitel, ⏭ nächste Folge.
 * Tastatur zusätzlich: F/F11 Vollbild, M Ton aus, +/− Lautstärke, T Tonspur, U Untertitel, L Untertitel laden,
 * G/H Untertitel früher/später. Maus: Bewegen zeigt die Leiste, Klick Pause/Info, Doppelklick Vollbild, Rad Lautstärke.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(app: AppState, playback: Playback, window: WindowState, toggleFullscreen: () -> Unit) {
    val player = app.player
    val store = app.store
    val guide by store.guide.collectAsState()
    val user by store.user.collectAsState()
    val focus = remember { FocusRequester() }
    val live = playback as? Playback.Live
    var layer by remember { mutableStateOf(Layer.INFO) }
    var tech by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // Menü rechts wie auf dem Fire TV (☰), darin die Sprachauswahl; unten „Untertitel-Zeit einstellen“.
    var panel by remember { mutableStateOf(Panel.NONE) }
    /** Offene Unterseite des Menüs (null = Übersicht). */
    var menuPage by remember { mutableStateOf<MenuSection?>(null) }
    var syncPanel by remember { mutableStateOf(false) }
    var syncIndex by remember { mutableStateOf(0) }
    var syncConfirmed by remember { mutableStateOf<String?>(null) }
    // Seit wann eine Spul-/Pfeiltaste gehalten wird (für schnelleres Spulen und „nur beim ersten Drücken“).
    var holdKey by remember { mutableStateOf<RemoteButton?>(null) }
    var holdSince by remember { mutableLongStateOf(0L) }
    var info by remember { mutableStateOf<StreamInfo?>(null) }

    fun refocus() = runCatching { focus.requestFocus() }

    // Schnellwahl (◀ halten): zuletzt gesehene Sender und Favoriten als Logo-Leiste.
    var quick by remember { mutableStateOf<List<Channel>?>(null) }
    var quickOpened by remember { mutableStateOf(false) }

    fun show(next: Layer = Layer.INFO) {
        lastInput = System.currentTimeMillis()
        layer = next
    }

    fun shiftSubtitles(deltaMs: Long) {
        app.setSubtitleDelay(app.subtitleDelayMs + deltaMs)
        syncConfirmed = null
        app.toast("Untertitel ${shiftLabel(app.subtitleDelayMs)}")
    }

    val nextEpisode = (playback as? Playback.Vod)?.let { vod ->
        val id = vod.episodeId ?: return@let null
        Episodes.next(vod.episodes.map { it.first }, id)?.let { next -> vod.episodes.first { it.first.id == next.id } }
    }

    fun playNext() {
        val vod = playback as? Playback.Vod ?: return
        val (episode, url) = nextEpisode ?: return
        val series = vod.series ?: return
        app.savePosition()
        app.play(vod.copy(key = Episodes.key(series.key, episode.id), title = Episodes.title(series.name, episode), url = url, startMs = 0, episodeId = episode.id))
    }

    // Nächste Folge (Serien): in der letzten Minute ein Hinweis mit Countdown, am Ende startet sie von selbst.
    // OK startet sofort, eine andere Taste hält den Countdown an (dann endet die Folge ohne Weiterschalten).
    var nextCancelled by remember(playback) { mutableStateOf(false) }
    val remainingMs = player.durationMs - player.positionMs
    val nextCardVisible = nextEpisode != null && player.durationMs > 0 && remainingMs in 0..NEXT_BEFORE_END_MS && player.positionMs >= player.durationMs / 2
    val nextCountdown = if (nextCardVisible && !nextCancelled && player.playing) ((remainingMs + 999) / 1000).toInt() else -1

    fun seek(delta: Long) {
        player.seekBy(delta)
        show()
    }

    fun toggleFavorite(channel: Channel) {
        app.toast(if (store.toggleFavorite(channel.key)) "★ ${channel.name} zu Favoriten hinzugefügt" else "${channel.name} aus Favoriten entfernt")
    }

    /** ☰-Menü rechts (wie auf dem Fire TV): Untertitel, Ton, Klang, Bild, Schlaf-Timer, bei Live TV Favorit und Aufnahme. */
    fun openMenu(page: MenuSection? = null) {
        lastInput = System.currentTimeMillis()
        menuPage = page
        panel = Panel.MENU
    }

    fun closePanel() {
        panel = Panel.NONE
        refocus()
    }

    /** Einblendung, die gerade (bzw. als nächste) dran ist. */
    fun currentCueIndex(): Int {
        val cues = app.subtitleCues
        if (cues.isEmpty()) return 0
        val fileTime = player.positionMs - app.subtitleDelayMs
        return cues.indexOfFirst { it.first >= fileTime - 1_500 }.let { if (it < 0) cues.lastIndex else it }
    }

    fun openSync() {
        syncIndex = currentCueIndex()
        syncConfirmed = null
        syncPanel = true
        closePanel()
    }

    BackHandler(app, enabled = menu == null && (panel != Panel.NONE || syncPanel)) {
        when {
            panel == Panel.LANGUAGES -> panel = Panel.MENU
            // Im Menü geht Zurück erst von der Unterseite zur Übersicht.
            panel == Panel.MENU && menuPage != null -> menuPage = null
            panel == Panel.MENU -> closePanel()
            else -> { syncPanel = false; refocus() }
        }
    }
    BackHandler(app, enabled = menu == null && layer == Layer.CHANNELS) { layer = Layer.NONE; refocus() }
    BackHandler(app, enabled = menu == null && layer != Layer.CHANNELS && tech) { tech = false }
    LaunchedEffect(Unit) { refocus() }
    LaunchedEffect(menu, layer, panel) { if (menu == null && layer != Layer.CHANNELS && panel == Panel.NONE) refocus() }
    // Direktausgabe (HDR-Bildschirm): Menü, Senderliste und Stream-Info liegen im Hauptfenster, das Vollbild so lange weg.
    // Info nach 5 Sekunden ohne Eingabe ausblenden (nicht bei Pause, Fehler oder offener Stream-Info).
    LaunchedEffect(lastInput, layer, player.playing, tech) {
        if (layer != Layer.INFO || tech) return@LaunchedEffect
        delay(5_000)
        if (player.playing && player.error == null) layer = Layer.NONE
    }
    // Stream-Info jede Sekunde neu lesen, solange sie offen ist.
    LaunchedEffect(tech, playback) {
        while (tech) {
            info = player.streamInfo()
            delay(1_000)
        }
    }
    // Beim Umschalten kurz die Info zeigen.
    LaunchedEffect((playback as? Playback.Live)?.channel?.key) { if (layer != Layer.CHANNELS) show() }
    // Stand von Filmen regelmäßig merken; am Ende einer Folge die nächste.
    LaunchedEffect(playback) {
        player.onFinished = { if (nextEpisode != null && !nextCancelled) playNext() else app.stopPlayback() }
        while (playback is Playback.Vod) {
            delay(15_000)
            app.savePosition()
        }
    }

    fun seekStep(button: RemoteButton, forward: Boolean, shift: Boolean): Long {
        val held = if (holdKey == button) System.currentTimeMillis() - holdSince else 0L
        val base = if (forward) 30_000L else 10_000L
        return when {
            shift -> 60_000L
            held > 5_000 -> 120_000L
            held > 1_500 -> 60_000L
            else -> base
        } * if (forward) 1 else -1
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                val button = e.remote()
                if (e.type == KeyEventType.KeyUp) {
                    if (button != null && button == holdKey) holdKey = null
                    // ◀ kurz (beim Loslassen): zum letzten Sender; gehalten hat es die Schnellwahl geöffnet.
                    if (button == RemoteButton.LEFT && live != null && layer != Layer.CHANNELS && menu == null && quick == null && panel == Panel.NONE && !syncPanel) {
                        if (!quickOpened && !app.backZap()) show(Layer.CHANNELS)
                        quickOpened = false
                        return@onKeyEvent true
                    }
                    if (button == RemoteButton.LEFT) quickOpened = false
                    return@onKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Untertitel-Zeit einstellen: eigene Tasten (wie auf dem Fire TV).
                if (syncPanel && menu == null && panel == Panel.NONE) {
                    lastInput = System.currentTimeMillis()
                    val cues = app.subtitleCues
                    when (button) {
                        RemoteButton.LEFT -> { syncIndex = (syncIndex - 1).coerceAtLeast(0); syncConfirmed = null }
                        RemoteButton.RIGHT -> { syncIndex = (syncIndex + 1).coerceAtMost((cues.size - 1).coerceAtLeast(0)); syncConfirmed = null }
                        RemoteButton.UP -> shiftSubtitles(-500)
                        RemoteButton.DOWN -> shiftSubtitles(500)
                        RemoteButton.OK -> cues.getOrNull(syncIndex)?.let { (start, _) ->
                            app.setSubtitleDelay(player.positionMs - start)
                            syncConfirmed = "✓ Untertitel ${shiftLabel(app.subtitleDelayMs)} – passt es? Sonst die nächste Zeile abwarten und noch einmal OK."
                        }
                        RemoteButton.PLAY_PAUSE, RemoteButton.PLAY, RemoteButton.PAUSE -> player.togglePause()
                        RemoteButton.MENU -> openMenu()
                        RemoteButton.CAPTIONS -> openMenu(MenuSection.SUBTITLES)
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                // Menü rechts offen: ☰ schließt es, sonst navigiert dort der Fokus.
                if (panel != Panel.NONE && menu == null && (button == RemoteButton.MENU || button == RemoteButton.CAPTIONS || e.key == Key.Tab)) {
                    closePanel()
                    return@onKeyEvent true
                }
                // Senderliste offen: dort navigiert der Fokus (die Liste fängt OK selbst ab).
                if (layer == Layer.CHANNELS || menu != null || quick != null || panel != Panel.NONE) return@onKeyEvent false
                val first = button != holdKey
                if (first) {
                    holdKey = button
                    holdSince = System.currentTimeMillis()
                }
                lastInput = System.currentTimeMillis()
                e.digit()?.let { d ->
                    if (live != null) { app.typeDigit(d); show() }
                    return@onKeyEvent live != null
                }
                if (button == null) {
                    // Nur Tastatur: PC-Kürzel.
                    when (e.key) {
                        Key.F, Key.F11 -> toggleFullscreen()
                        Key.M -> player.toggleMute()
                        Key.Plus, Key.NumPadAdd, Key.Equals -> { player.changeVolume(5); show() }
                        Key.Minus, Key.NumPadSubtract -> { player.changeVolume(-5); show() }
                        Key.Tab -> openMenu()
                        Key.T -> openMenu(MenuSection.AUDIO)
                        Key.U, Key.C -> openMenu(MenuSection.SUBTITLES)
                        Key.L -> (playback as? Playback.Vod)?.let { app.loadSubtitles(it) }
                        Key.G -> shiftSubtitles(-250)
                        Key.H -> shiftSubtitles(250)
                        Key.N -> playNext()
                        Key.I -> { if (layer == Layer.INFO) tech = !tech; show() }
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                if (live != null) {
                    when (button) {
                        RemoteButton.UP -> if (user.zapWithUpDown) app.zap(+1) else show(Layer.CHANNELS)
                        RemoteButton.DOWN -> if (user.zapWithUpDown) app.zap(-1) else show(Layer.CHANNELS)
                        RemoteButton.CHANNEL_UP, RemoteButton.NEXT -> app.zap(+1)
                        RemoteButton.CHANNEL_DOWN, RemoteButton.PREVIOUS -> app.zap(-1)
                        RemoteButton.OK, RemoteButton.GUIDE -> if (first) show(Layer.CHANNELS)
                        // ◀ springt zum vorherigen Sender (und von dort zurück); ohne vorherigen öffnet es die Liste.
                        RemoteButton.LEFT -> if (!first && !quickOpened && quick == null && System.currentTimeMillis() - holdSince > 450) {
                            quickOpened = true
                            quick = (listOfNotNull(app.previousChannel) + store.recentChannels() + store.favorites())
                                .filter { it.key != live.channel.key }.distinctBy { it.key }.take(16).ifEmpty { null }
                            if (quick == null) show(Layer.CHANNELS)
                        }
                        RemoteButton.RIGHT, RemoteButton.INFO -> if (first) { if (layer == Layer.INFO) tech = !tech; show() }
                        RemoteButton.LAST_CHANNEL, RemoteButton.BLUE -> if (first) app.backZap()
                        RemoteButton.MENU -> if (first) openMenu()
                        RemoteButton.CAPTIONS -> if (first) openMenu(MenuSection.SUBTITLES)
                        RemoteButton.RED -> if (first) { toggleFavorite(live.channel); show() }
                        // Wie auf dem Fire TV: ⏯ hält an (zeitversetzt weiter), ⏩ zurück zu Live, ⏪ Sendung von Anfang (Archiv).
                        RemoteButton.PLAY_PAUSE -> { if (first) app.toggleLivePause(); show() }
                        RemoteButton.PLAY -> { if (player.paused) app.toggleLivePause(); show() }
                        RemoteButton.PAUSE -> { if (!player.paused) app.toggleLivePause(); show() }
                        RemoteButton.STOP -> app.stopPlayback()
                        RemoteButton.FAST_FORWARD -> if (first) { if (!app.backToLive()) show() }
                        RemoteButton.REWIND -> if (first) {
                            val current = guide[live.channel.key]?.let { nowOf(it) }
                            if (current == null || !app.playArchive(live.channel, current)) app.toast("Für diesen Sender gibt es kein Archiv")
                        }
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                // Filme und Serien.
                if (first && button == RemoteButton.OK && nextCardVisible && layer != Layer.INFO) { playNext(); return@onKeyEvent true }
                if (first && nextCountdown >= 0 && button != RemoteButton.OK) nextCancelled = true
                when (button) {
                    RemoteButton.OK, RemoteButton.PLAY_PAUSE -> {
                        if (first) {
                            if (layer == Layer.INFO || !player.playing || button == RemoteButton.PLAY_PAUSE) player.togglePause()
                            show()
                        }
                    }
                    RemoteButton.PLAY -> { player.resume(); show() }
                    RemoteButton.PAUSE -> { player.pause(); show() }
                    RemoteButton.STOP -> app.stopPlayback()
                    RemoteButton.LEFT, RemoteButton.REWIND -> seek(seekStep(button, forward = false, shift = e.isShiftPressed))
                    RemoteButton.RIGHT, RemoteButton.FAST_FORWARD -> seek(seekStep(button, forward = true, shift = e.isShiftPressed))
                    RemoteButton.UP, RemoteButton.DOWN, RemoteButton.INFO -> if (first) { if (layer == Layer.INFO) tech = !tech; show() }
                    RemoteButton.MENU -> if (first) openMenu()
                    RemoteButton.CAPTIONS -> if (first) openMenu(MenuSection.SUBTITLES)
                    RemoteButton.NEXT, RemoteButton.CHANNEL_UP -> if (first) { if ((playback as? Playback.Vod)?.archive != null) app.backToLive() else playNext() }
                    else -> return@onKeyEvent false
                }
                true
            }
            .onPointerEvent(PointerEventType.Move) { if (layer == Layer.NONE) show() else { lastInput = System.currentTimeMillis() } }
            .onPointerEvent(PointerEventType.Press) { e -> if (e.buttons.isSecondaryPressed && panel == Panel.NONE) openMenu() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                if (layer == Layer.CHANNELS || panel != Panel.NONE) return@onPointerEvent
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) player.changeVolume(if (dy < 0) 5 else -5)
                show()
            }
            .pointerInput(playback) {
                detectTapGestures(
                    onDoubleTap = { toggleFullscreen() },
                    onTap = {
                        when {
                            panel != Panel.NONE -> closePanel()
                            layer == Layer.CHANNELS -> layer = Layer.NONE
                            playback is Playback.Vod && layer == Layer.INFO -> player.togglePause()
                            layer == Layer.INFO -> layer = Layer.NONE
                            else -> show()
                        }
                        if (layer != Layer.NONE) lastInput = System.currentTimeMillis()
                        refocus()
                    },
                )
            },
    ) {
        val hasFrame = player.hasFrame
        if (hasFrame) {
            VideoView(player, Modifier.fillMaxSize(), user.pictureFit)
        } else if (live != null && !player.hasVideo) {
            Box(Modifier.align(Alignment.Center)) { Logo(live.channel.logo, 160) }
        }

        SubtitleOverlay(player, user, Modifier.fillMaxSize())

        val problem = player.error ?: if (!player.available) "Die DNA Engine fehlt. Bitte DnA-TV neu installieren." else null
        val status = app.playerStatus
        when {
            status != null -> Notice(status, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            problem != null -> Box(Modifier.align(Alignment.Center).background(Palette.surface, RoundedCornerShape(12.dp)).padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(problem, Type.heading, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    Label(if (live != null) "↑/↓ anderer Sender · OK Senderliste · Zurück beendet" else "Zurück beendet", Type.small)
                }
            }
            player.buffering && !hasFrame -> Box(Modifier.align(Alignment.Center)) { Label("Lädt …", Type.heading) }
        }
        if (player.paused && layer == Layer.NONE) {
            Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(50)).padding(20.dp)) { Label("⏸", Type.title) }
        }

        // Eingetippte Sendernummer und Ziel beim schnellen Umschalten.
        val typed = app.typedNumber
        if (typed.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 10.dp)) {
                Label(typed, Type.display)
            }
        }

        if (tech) {
            TechPanel(info, Modifier.align(Alignment.TopStart).padding(24.dp))
        }

        // Leisten blenden weich ein und aus (wie auf dem Fire TV).
        AnimatedVisibility(
            visible = layer == Layer.INFO || problem != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 14 },
            exit = fadeOut(tween(220)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 28.dp),
                ) {
                    when (playback) {
                        is Playback.Live -> {
                            val shown = app.zapTarget ?: playback.channel
                            // Kennzeichen (4K, HDR, 50 fps, Dolby) alle 2 s aus der Stream-Info, solange die Leiste offen ist.
                            val badges by androidx.compose.runtime.produceState(emptyList<String>(), shown.key) {
                                while (true) {
                                    value = player.streamInfo()?.let { i ->
                                        val hdr = when (player.transfer) {
                                            de.ayutv.desktop.player.YuvVideo.Transfer.PQ -> "HDR10"
                                            de.ayutv.desktop.player.YuvVideo.Transfer.HLG -> "HLG"
                                            else -> ""
                                        }
                                        de.ayutv.data.parse.StreamBadges.video(i.height, i.fps.toFloat(), hdr) +
                                            listOfNotNull(de.ayutv.data.parse.StreamBadges.audio(i.audioCodec, i.audioChannels))
                                    }.orEmpty()
                                    kotlinx.coroutines.delay(2_000)
                                }
                            }
                            LiveBar(playback, shown, guide[shown.key].orEmpty(), player.volume, shown.key in user.favorites, player.paused, app.liveDelayMs, badges, user.channelNumbers[shown.key])
                        }
                        is Playback.Vod -> VodBar(
                            playback, player.positionMs, player.durationMs, player.playing, player.volume, nextEpisode != null, app.subtitleSync,
                            onNext = ::playNext, onSeek = { player.seekTo(it) }, onPause = { player.togglePause() },
                            onBack10 = { seek(-10_000) }, onForward30 = { seek(30_000) },
                        )
                    }
                }
                // Knöpfe oben rechts nur ohne offenes Menü (sie lägen sonst halb verdeckt unter dem Menü).
                if (panel == Panel.NONE) Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    if (live != null) { TextButton("☰ Senderliste", { show(Layer.CHANNELS) }); Spacer(Modifier.width(8.dp)) }
                    TextButton("☰ Ton / Untertitel / Bild", { openMenu() })
                    Spacer(Modifier.width(8.dp))
                    TextButton("⤢", { toggleFullscreen() })
                    Spacer(Modifier.width(8.dp))
                    TextButton("✕", { app.stopPlayback() })
                }
            }
        }
        if (nextCardVisible) nextEpisode?.let { (episode, _) ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = if (layer == Layer.INFO) 170.dp else 48.dp)
                    .appear(Unit, 32.dp, 260),
                horizontalAlignment = Alignment.End,
            ) {
                TextButton("⏭ Nächste Folge: S${episode.season} E${episode.episode}", { playNext() }, primary = true)
                Spacer(Modifier.height(6.dp))
                Label(
                    if (nextCountdown >= 0) "Startet in $nextCountdown s  ·  OK: jetzt  ·  andere Taste: anhalten" else "OK: jetzt starten",
                    Type.small.copy(color = Color.White),
                )
            }
        }
        quick?.let { list ->
            val firstTile = remember { FocusRequester() }
            BackHandler(app) { quick = null; refocus() }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 28.dp),
            ) {
                Label("Schnellwahl  ·  ◀ ▶ wählen  ·  OK umschalten  ·  Zurück schließen", Type.small)
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyRow {
                    items(list.size, key = { list[it].key }) { i ->
                        val c = list[i]
                        Selectable(
                            { quick = null; live?.let { app.play(Playback.Live(c, it.list)) } },
                            Modifier.width(150.dp),
                            focusRequester = if (i == 0) firstTile else null,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(134.dp)) {
                                Logo(c.logo, 64)
                                Spacer(Modifier.height(4.dp))
                                Label(c.name, Type.small)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                }
            }
            FocusOnShow(firstTile, list)
            // Ohne Eingabe schließt sie nach 6 Sekunden.
            LaunchedEffect(list, lastInput) {
                kotlinx.coroutines.delay(6_000)
                quick = null
            }
        }

        AnimatedVisibility(
            visible = layer == Layer.CHANNELS && live != null,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
            enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(140)),
        ) {
            if (live != null) ChannelPanel(app, live, Modifier.fillMaxHeight().width(460.dp)) { picked, list ->
                if (picked.key != live.channel.key) app.play(Playback.Live(picked, list))
                layer = Layer.INFO
                lastInput = System.currentTimeMillis()
            }
        }
        if (syncPanel && panel == Panel.NONE) {
            SubtitleSyncBar(app, syncIndex, syncConfirmed, Modifier.align(Alignment.BottomCenter))
        }
        app.subtitleBusy?.let { busy ->
            Notice(busy, Modifier.align(if (panel != Panel.NONE) Alignment.TopStart else Alignment.TopCenter).padding(28.dp))
        }
        // Läuft es auf einem Fernseher: Windows ist die Fernbedienung (Sender, Lautstärke, zurück auf den PC).
        app.castTarget?.let { tv -> CastRemotePanel(app, playback, tv.name) }
        AnimatedVisibility(
            visible = panel == Panel.MENU,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(140)),
        ) {
            PlayerMenuPanel(
                app, playback,
                MenuActions(
                    nextEpisode = if (nextEpisode != null) ({ playNext() }) else null,
                    tech = tech,
                    toggleTech = { tech = !tech; show() },
                    fullscreen = window.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen,
                    toggleFullscreen = toggleFullscreen,
                    openSync = { openSync() },
                    openLanguages = { panel = Panel.LANGUAGES },
                    openDelay = { menu = { AudioDelayDialog(app, onDismiss = { menu = null }) } },
                    close = { closePanel() },
                ),
                page = menuPage,
                onPage = { menuPage = it; lastInput = System.currentTimeMillis() },
            )
        }
        AnimatedVisibility(
            visible = panel == Panel.LANGUAGES && playback is Playback.Vod,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(140)),
        ) {
            if (playback is Playback.Vod) SubtitleLanguagePanel(app) { code ->
                closePanel()
                app.loadSubtitles(playback, code)
            }
        }
        menu?.invoke()
        app.castChoices?.let { choices ->
            MenuDialog(
                app, "Auf Fernseher abspielen",
                choices.map { tv ->
                    MenuItem("📺  ${tv.name}" + if (tv is de.ayutv.desktop.remote.CastTarget.Dlna) "  (direkt, volle Qualität)" else "") { app.castTo(tv) }
                },
                onDismiss = { app.castChoices = null },
            )
        }
    }
}

/** Menü rechts im Bild: zu, Hauptmenü, Sprachauswahl für Untertitel. */
private enum class Panel { NONE, MENU, LANGUAGES }

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xDD151B23), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Label(text, Type.body, maxLines = 2)
    }
}

@Composable
private fun TechPanel(info: StreamInfo?, modifier: Modifier = Modifier) {
    Column(modifier.width(330.dp).background(Color(0xDD0B0F14), RoundedCornerShape(12.dp)).padding(16.dp)) {
        Label("Stream-Info", Type.heading)
        Spacer(Modifier.height(8.dp))
        if (info == null) {
            Label("Wird gelesen …", Type.small)
        } else {
            info.lines().forEach { (name, value) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Label(name, Type.small, Modifier.width(96.dp))
                    Label(value, Type.body, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Senderliste über dem Bild (OK im Live-TV): der laufende Sender ist ausgewählt, ◀/▶ wechseln die Gruppe,
 * OK schaltet um, ☰ bzw. OK lange drücken setzt einen Favoriten, Zurück schließt.
 */
@Composable
private fun ChannelPanel(app: AppState, live: Playback.Live, modifier: Modifier, onPick: (Channel, List<Channel>) -> Unit) {
    val store = app.store
    val guide by store.guide.collectAsState()
    val user by store.user.collectAsState()
    // Gruppen: die Liste, in der gerade gezappt wird, Favoriten, alle Sender, dann die Gruppen des Anbieters.
    val groups = remember(user) {
        buildList<Pair<String, () -> List<Channel>>> {
            add("Aktuelle Liste" to { live.list })
            if (user.favorites.isNotEmpty()) add("★ Favoriten" to { store.favorites() })
            user.customGroups.forEach { g -> add("▣ ${g.name}" to { store.inCustom(g.id) }) }
            add("Alle Sender" to { store.allVisible() })
            store.liveGroups().forEach { (name, _) -> add(name to { store.inGroup(name) }) }
        }
    }
    var groupIndex by remember { mutableStateOf(0) }
    val (groupName, loader) = groups[groupIndex.coerceIn(0, groups.lastIndex)]
    val channels = remember(groupIndex, user) { loader() }
    val state = rememberLazyListState()
    val current = remember { FocusRequester() }
    val at = channels.indexOfFirst { it.key == live.channel.key }.takeIf { it >= 0 } ?: 0

    LaunchedEffect(groupIndex) {
        state.scrollToItem((at - 4).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { current.requestFocus() }
    }

    fun switchGroup(delta: Int) {
        groupIndex = (groupIndex + delta).mod(groups.size)
    }

    Column(
        modifier.background(Color(0xF00B0F14)).padding(16.dp).onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.remote()) {
                RemoteButton.LEFT -> { switchGroup(-1); true }
                RemoteButton.RIGHT -> { switchGroup(+1); true }
                RemoteButton.CHANNEL_UP -> { switchGroup(+1); true }
                RemoteButton.CHANNEL_DOWN -> { switchGroup(-1); true }
                else -> false
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("◀", { switchGroup(-1) })
            Label(groupName, Type.heading, Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton("▶", { switchGroup(+1) })
        }
        Spacer(Modifier.height(10.dp))
        if (channels.isEmpty()) {
            Label("Keine Sender in dieser Gruppe.", Type.small)
            return@Column
        }
        LazyColumn(state = state) {
            itemsIndexed(channels, key = { _, c -> c.key }) { index, channel ->
                val programme = guide[channel.key]?.let(::nowOf)
                val number = user.numberOf(channel, index + 1)
                Selectable(
                    onClick = { onPick(channel, channels) },
                    onMenu = { app.toast(if (store.toggleFavorite(channel.key)) "★ ${channel.name} zu Favoriten" else "${channel.name} aus Favoriten entfernt") },
                    selected = channel.key == live.channel.key,
                    focusRequester = if (index == at) current else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Label("$number", Type.small, Modifier.width(40.dp))
                    Logo(channel.logo, 34)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label((if (channel.key in user.favorites) "★ " else "") + channel.name, Type.body)
                        if (programme != null) {
                            Label(programme.display(), Type.small)
                            Spacer(Modifier.height(3.dp))
                            Progress(programme.fraction())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBar(live: Playback.Live, channel: Channel, programmes: List<Programme>, volume: Int, favorite: Boolean, paused: Boolean, delayMs: Long, badges: List<String> = emptyList(), customNumber: Int? = null) {
    val now = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val next = programmes.firstOrNull { it.start >= (current?.stop ?: now) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(channel.logo, 64)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                val index = live.list.indexOfFirst { it.key == channel.key }
                val number = customNumber ?: channel.number.takeIf { it > 0 } ?: (index + 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("${if (number > 0) "$number  " else ""}${channel.name}${if (favorite) "  ★" else ""}", Type.title)
                    badges.forEach { b ->
                        Spacer(Modifier.width(8.dp))
                        Label(b, Type.small, Modifier.background(Palette.accentSoft, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp), color = Palette.text)
                    }
                }
                if (current != null) {
                    val rest = de.ayutv.data.parse.StreamBadges.remaining(current.stop)?.let { "  ·  $it" }.orEmpty()
                    Label("${clock(current.start)} – ${clock(current.stop)}$rest", Type.small, color = Palette.secondary)
                    Label(current.display(), Type.heading)
                    Spacer(Modifier.height(6.dp))
                    Progress(current.fraction())
                } else {
                    Label("Kein TV-Guide für diesen Sender", Type.small)
                }
                next?.let { Spacer(Modifier.height(4.dp)); Label("Danach  ${clock(it.start)}   ${it.display()}", Type.body, color = Palette.secondary) }
            }
            Spacer(Modifier.width(18.dp))
            Column(horizontalAlignment = Alignment.End) {
                Label(clock(now), Type.title)
                when {
                    paused -> Label("⏸ Angehalten", Type.heading, color = Palette.accent)
                    delayMs > 1_000 -> Label("Zeitversetzt −${duration(delayMs)} · ⏩ Live", Type.small, color = Palette.accent)
                }
                Label("🔊 $volume %", Type.small)
            }
        }
        Spacer(Modifier.height(10.dp))
        Label("↑/↓ umschalten · OK Senderliste · ◀ letzter Sender · ▶ Stream-Info · ☰ Ton/Untertitel · ⏯ Pause · ⏪ von Anfang", Type.small)
    }
}

@Composable
private fun VodBar(
    vod: Playback.Vod,
    position: Long,
    duration: Long,
    playing: Boolean,
    volume: Int,
    hasNext: Boolean,
    subtitleSync: String?,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onBack10: () -> Unit,
    onForward30: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(vod.title, Type.title, Modifier.weight(1f))
            // Gegen Ende (Abspann) die nächste Folge anbieten.
            if (hasNext && duration > 0 && duration - position < 3 * 60_000) TextButton("⏭ Nächste Folge", onNext, primary = true)
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(position, duration, onSeek)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("⏪ 10", onBack10)
            Spacer(Modifier.width(6.dp))
            TextButton(if (playing) "⏸" else "▶", onPause, primary = true)
            Spacer(Modifier.width(6.dp))
            TextButton("30 ⏩", onForward30)
            if (hasNext) { Spacer(Modifier.width(6.dp)); TextButton("⏭", onNext) }
            Spacer(Modifier.width(16.dp))
            Label("${duration(position)} / ${duration(duration)}", Type.heading, Modifier.weight(1f))
            subtitleSync?.let { Label("💬 $it   ", Type.small) }
            Label("🔊 $volume %", Type.small)
        }
        Spacer(Modifier.height(6.dp))
        Label("OK Pause · ◀/▶ spulen (halten = schneller) · ↑/↓ Stream-Info · ☰ Ton/Untertitel", Type.small)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(16.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / width * duration).toLong()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Progress(if (duration > 0) position.toFloat() / duration else 0f, height = 6)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Direktausgabe: Wert und Anzeige. */

/** HDR-Anpassung: Wert und Anzeige. */
val HDR_MODES = listOf("AUTO" to "Automatisch", "PQ" to "HDR10", "HLG" to "HLG", "OFF" to "Aus")
fun hdrLabel(mode: String) = HDR_MODES.firstOrNull { it.first == mode }?.second ?: "Automatisch"

/** Anteil der Sendung, der schon gelaufen ist. */
fun Programme.fraction(now: Long = System.currentTimeMillis()): Float =
    if (stop > start) ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f

fun nowOf(list: List<Programme>, at: Long = System.currentTimeMillis()): Programme? = list.firstOrNull { it.start <= at && it.stop > at }

/**
 * Das Videobild. Neue Bilder lösen nur Neuzeichnen aus (nicht den ganzen Player-Bildschirm): [VideoPlayer.frameTick]
 * wird erst beim Zeichnen gelesen. Schneller Weg: YUV-Ebenen + Shader; sonst das RGB-Bild wie früher.
 */
@Composable
fun VideoView(player: de.ayutv.desktop.player.VideoPlayer, modifier: Modifier = Modifier, fit: de.ayutv.desktop.data.PictureFit = de.ayutv.desktop.data.PictureFit.FIT) {
    if (player.gpuVideo) {
        androidx.compose.foundation.Canvas(modifier) {
            player.frameTick // Neuzeichnen bei jedem Bild
            val frame = player.currentFrame() ?: return@Canvas
            drawIntoCanvas { canvas -> frame.draw(canvas.nativeCanvas, size.width, size.height, fit.ordinal) }
        }
    } else {
        val frame = player.frame ?: return
        val scale = when (fit) {
            de.ayutv.desktop.data.PictureFit.FIT -> ContentScale.Fit
            de.ayutv.desktop.data.PictureFit.ZOOM -> ContentScale.Crop
            de.ayutv.desktop.data.PictureFit.STRETCH -> ContentScale.FillBounds
        }
        Image(frame, null, modifier.clipToBounds(), contentScale = scale, filterQuality = FilterQuality.Low)
    }
}

/**
 * Untertitel der DNA Engine über dem Bild: Text (SRT, ASS, WebVTT, Datei) im eingestellten Aussehen, Bild-Untertitel
 * (PGS, DVB) an ihrer Stelle im Video. Größe und Abstand richten sich nach der Bildfläche, nicht nach dem Fenster.
 */
@Composable
fun SubtitleOverlay(player: de.ayutv.desktop.player.VideoPlayer, user: de.ayutv.desktop.data.UserData, modifier: Modifier = Modifier) {
    val text = player.subtitleText
    val images = player.subtitleImages
    if (text.isNullOrBlank() && images.isEmpty()) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val frame = player.currentFrame()
        val aspect = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 16f / 9f
        // Bildfläche wie beim Zeichnen: „Einpassen“ mit Rand, „Zoom“/„Strecken“ füllen das Fenster.
        val (videoW, videoH) = if (user.pictureFit == de.ayutv.desktop.data.PictureFit.FIT) {
            if (boxW / boxH > aspect) boxH * aspect to boxH else boxW to boxW / aspect
        } else boxW to boxH
        val left = (boxW - videoW) / 2
        val top = (boxH - videoH) / 2
        if (images.isNotEmpty()) {
            val bitmaps = remember(images) { images.mapNotNull { img -> subtitleBitmap(img)?.let { img to it } } }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                for ((img, bitmap) in bitmaps) {
                    val sx = videoW / img.displayWidth
                    val sy = videoH / img.displayHeight
                    drawImage(
                        bitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset((left + img.x * sx).toInt(), (top + img.y * sy).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize((img.width * sx).toInt().coerceAtLeast(1), (img.height * sy).toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        if (!text.isNullOrBlank()) {
            val fontPx = videoH * 0.048f * user.subtitleSize.percent / 100f
            val fontSize = with(density) { fontPx.toSp() }
            val bottom = with(density) { (top + videoH * user.subtitlePosition.margin / 720f).toDp() }
            val color = Color(0xFF000000.toInt() or user.subtitleColor.rgb)
            val base = androidx.compose.ui.text.TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = fontSize * 1.15f,
            )
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom).fillMaxWidth(0.9f), contentAlignment = Alignment.BottomCenter) {
                when (user.subtitleBackground) {
                    de.ayutv.desktop.data.SubtitleBackground.BOX -> androidx.compose.material.Text(
                        text, Modifier.background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = base.copy(color = color),
                    )
                    de.ayutv.desktop.data.SubtitleBackground.SHADOW -> androidx.compose.material.Text(
                        text, style = base.copy(color = color, shadow = androidx.compose.ui.graphics.Shadow(Color(0xDD000000), androidx.compose.ui.geometry.Offset(fontPx * 0.06f, fontPx * 0.06f), fontPx * 0.12f)),
                    )
                    de.ayutv.desktop.data.SubtitleBackground.OUTLINE -> Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material.Text(
                            text,
                            style = base.copy(color = Color.Black, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = fontPx * 0.12f, join = androidx.compose.ui.graphics.StrokeJoin.Round)),
                        )
                        androidx.compose.material.Text(text, style = base.copy(color = color))
                    }
                }
            }
        }
    }
}

/** Bild-Untertitel (ARGB) als Bild für die Oberfläche. */
private fun subtitleBitmap(img: de.ayutv.player.dna.engine.SubtitleImage): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bytes = java.nio.ByteBuffer.allocate(img.pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(img.pixels)
    val info = org.jetbrains.skia.ImageInfo(img.width, img.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    org.jetbrains.skia.Image.makeRaster(info, bytes.array(), img.width * 4).toComposeImageBitmap()
}.getOrNull()

/** So lange vor dem Ende einer Folge erscheint „Nächste Folge“ mit Countdown. */
private const val NEXT_BEFORE_END_MS = 60_000L

/**
 * Fernbedienung, solange ein Fernseher abspielt: was läuft, Sender ▲/▼ (auch mit den Pfeiltasten wie gewohnt),
 * Lautstärke des Fernsehers (DLNA), Pause bei Filmen und „Hier weiterschauen“.
 */
@Composable
private fun CastRemotePanel(app: AppState, playback: Playback, tvName: String) {
    val accent = Palette.accent
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C0D11), Color(0xFF050506))))
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), radius = 900f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label("LÄUFT AUF ${tvName.uppercase()}", Type.small.copy(color = accent, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp))
            Spacer(Modifier.height(14.dp))
            Label(playback.title, Type.display.copy(fontSize = 34.sp), maxLines = 1)
            (playback as? Playback.Live)?.let { live ->
                app.store.guide.value[live.channel.key]?.let { nowOf(it) }?.let { now ->
                    Spacer(Modifier.height(4.dp))
                    Label(now.display(), Type.body.copy(color = Palette.secondary), maxLines = 1)
                }
            }
            app.castStatus?.let {
                Spacer(Modifier.height(8.dp))
                Label(it, Type.small.copy(color = if (it.startsWith("Schalte")) accent else Palette.secondary), maxLines = 2)
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (playback is Playback.Live) {
                    TextButton("▲  Sender", { app.zap(1) })
                    TextButton("▼  Sender", { app.zap(-1) })
                } else {
                    TextButton("⏯  Pause / Weiter", { app.castPlayPause() })
                }
                if (app.castTarget is de.ayutv.desktop.remote.CastTarget.Dlna) {
                    TextButton("−  Leiser", { app.castVolumeStep(-2) })
                    TextButton("+  Lauter" + (app.castVolume?.let { "  ($it)" } ?: ""), { app.castVolumeStep(2) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton("📺  Anderer Fernseher", { app.openCastPicker(choose = true) })
                TextButton("🖥  Hier weiterschauen", { app.stopCast() }, primary = true)
            }
            Spacer(Modifier.height(10.dp))
            Label("Pfeiltasten ▲/▼ und die Senderliste schalten direkt auf dem Fernseher um.", Type.small.copy(color = Palette.secondary))
        }
    }
}

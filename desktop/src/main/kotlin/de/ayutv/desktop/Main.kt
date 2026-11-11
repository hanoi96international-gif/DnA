package de.ayutv.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.sp
import de.ayutv.desktop.ui.Selectable
import de.ayutv.desktop.ui.appear
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.input.remote
import de.ayutv.desktop.ui.AppState
import de.ayutv.desktop.ui.CountryDialog
import de.ayutv.desktop.ui.HomeScreen
import de.ayutv.desktop.ui.clock
import de.ayutv.desktop.ui.GuideScreen
import de.ayutv.desktop.ui.Label
import de.ayutv.desktop.ui.LibraryScreen
import de.ayutv.desktop.ui.ListRow
import de.ayutv.desktop.ui.LiveScreen
import de.ayutv.desktop.ui.Palette
import de.ayutv.desktop.ui.PlayerScreen
import de.ayutv.desktop.ui.Screen
import de.ayutv.desktop.ui.SearchScreen
import de.ayutv.desktop.ui.SettingsScreen
import de.ayutv.desktop.ui.SportScreen
import de.ayutv.desktop.ui.Type
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") return SelfTest.run(args.getOrNull(1))
    if (args.firstOrNull() == "--tour") return Tour.run(args.getOrNull(1))
    val store = Store(Store.defaultDir())
    Log.init(store.dir)
    // Proxy vor der ersten Verbindung festlegen (sonst wartet jede Verbindung kurz auf die Einstellungen).
    Network.apply(store)
    // Mit 0.47 einmal für alle das neue Design Prestige (wer danach ein anderes wählt, behält es).
    if (store.user.value.designVersion < 1) store.updateUser { it.copy(uiTheme = "PRESTIGE", designVersion = 1) }
    de.ayutv.desktop.ui.Palette.use(store.user.value.uiTheme, store.user.value.accent)
    // Fire-TV-Fernbedienung so früh wie möglich lesen (eigener Thread, nur unter Windows).
    val input = RemoteInput().also { it.start() }
    application {
        val window = rememberWindowState(
            size = DpSize(1280.dp, 780.dp),
            // TV-Modus (oder Start mit --tv aus dem Autostart): immer im Vollbild.
            placement = if (store.user.value.fullscreen || store.user.value.tvMode || "--tv" in args) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )
        val scope = rememberCoroutineScope()
        val app = remember { AppState(store, Sync(store), scope, input) }
        Window(
            onCloseRequest = { app.close(); exitApplication() },
            state = window,
            title = "DnA-TV",
            icon = remember { appIcon() },
        ) {
            val awt = this.window
            DisposableEffect(awt) {
                // Tasten von Fire-TV- und Handy-Fernbedienung gehen an dieses Fenster, solange es vorn ist.
                app.remote.window = awt
                input.window = awt
                input.foreground = awt.isFocused
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) { input.foreground = true }
                    override fun windowLostFocus(e: WindowEvent?) { input.foreground = false }
                }
                awt.addWindowFocusListener(listener)
                onDispose { awt.removeWindowFocusListener(listener) }
            }
            // TV-Modus: Mauszeiger verschwindet nach kurzer Ruhe.
            val tv = app.store.user.collectAsState().value.tvMode
            LaunchedEffect(tv) { TvMode.hideIdleCursor(awt, tv) }
            val look = app.store.user.collectAsState().value.let { it.uiTheme to it.accent }
            LaunchedEffect(look) { de.ayutv.desktop.ui.Palette.use(look.first, look.second) }
            App(app, window)
        }
    }
}

private fun appIcon(): BitmapPainter? = runCatching {
    val bytes = object {}.javaClass.getResourceAsStream("/dna-tv.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

@Composable
fun App(app: AppState, window: WindowState) {
    val focus = LocalFocusManager.current
    val status by app.sync.status.collectAsState()
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var rootHasFocus by remember { mutableStateOf(false) }
    val source = app.input.source

    fun toggleFullscreen() {
        val full = window.placement != WindowPlacement.Fullscreen
        window.placement = if (full) WindowPlacement.Fullscreen else WindowPlacement.Floating
        app.store.updateUser { it.copy(fullscreen = full) }
    }

    // Erste Taste der Fire-TV-Fernbedienung: kurz bestätigen, dass sie erkannt wurde.
    LaunchedEffect(source?.deviceName) { if (source != null) app.toast("✓ ${source.label} erkannt") }
    // Beim Start etwas auswählen, damit Pfeiltasten und Fernbedienung sofort wirken. Geht die Auswahl verloren
    // (ausgewähltes Element verschwindet: Player oder Menü geschlossen), fängt die Oberfläche sie wieder auf –
    // sonst kämen Tasten der Fernbedienung nirgends an, bis jemand mit der Maus klickt.
    LaunchedEffect(rootHasFocus) {
        if (rootHasFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        if (!rootHasFocus) runCatching { root.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.backdrop)
            // Umgebungslicht: zwei weiche Lichtflecken in der Akzentfarbe (einmal je Größe/Farbe berechnet).
            .drawWithCache {
                val top = Brush.radialGradient(
                    listOf(Palette.accent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.05f), radius = size.width * 0.55f,
                )
                val bottom = Brush.radialGradient(
                    listOf(Palette.accentSoft.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 1.05f), radius = size.width * 0.5f,
                )
                onDrawBehind {
                    drawRect(top)
                    drawRect(bottom)
                }
            }
            .focusRequester(root)
            .onFocusChanged { rootFocused = it.isFocused; rootHasFocus = it.hasFocus }
            .focusable()
            .onKeyEvent { e ->
                // Was kein Element selbst nutzt: Pfeiltasten wechseln den Fokus, Zurück geht eine Ebene zurück,
                // Home zur Startseite, Suche/Einstellungen/Guide direkt.
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.key == Key.F11) { toggleFullscreen(); return@onKeyEvent true }
                fun move(direction: FocusDirection): Boolean {
                    // Noch nichts ausgewählt: ins erste Element springen.
                    if (rootFocused) return focus.moveFocus(FocusDirection.Enter) || focus.moveFocus(FocusDirection.Next)
                    return focus.moveFocus(direction)
                }
                when (e.remote()) {
                    RemoteButton.UP -> move(FocusDirection.Up)
                    RemoteButton.DOWN -> move(FocusDirection.Down)
                    RemoteButton.LEFT -> move(FocusDirection.Left)
                    RemoteButton.RIGHT -> move(FocusDirection.Right)
                    RemoteButton.BACK -> app.backToHome()
                    RemoteButton.HOME -> { app.goHome(); true }
                    RemoteButton.SEARCH -> { if (app.playing == null) app.screen = Screen.SEARCH; true }
                    RemoteButton.SETTINGS -> { if (app.playing == null) app.screen = Screen.SETTINGS; true }
                    RemoteButton.GUIDE -> { if (app.playing == null) app.screen = Screen.GUIDE; true }
                    else -> false
                }
            },
    ) {
        val playing = app.playing
        val multiview = app.multiview
        if (multiview != null) {
            de.ayutv.desktop.ui.MultiviewScreen(app, multiview)
        } else if (playing != null) {
            // Player öffnet: wächst aus der Mitte auf und blendet ein (nur beim Öffnen, nicht bei jedem Umschalten).
            Box(Modifier.fillMaxSize().appear(Unit, 0.dp, 280, scaleFrom = 0.92f)) {
                PlayerScreen(app, playing, window, ::toggleFullscreen)
            }
        } else {
            // Zurück aus dem Player: Die Übersicht kommt aus dem Hintergrund nach vorn.
            Row(Modifier.fillMaxSize().appear(Unit, 0.dp, 260, scaleFrom = 1.04f)) {
                Sidebar(app)
                // Seitenwechsel: neue Seite blendet weich ein und rückt ein Stück herauf, die alte blendet schnell aus.
                AnimatedContent(
                    targetState = app.screen,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp),
                    transitionSpec = {
                        // Richtung wie in der Seitenleiste: ein Bereich weiter unten kommt von unten, weiter oben von oben.
                        val down = targetState.ordinal >= initialState.ordinal
                        (fadeIn(tween(240, delayMillis = 50, easing = de.ayutv.desktop.ui.EaseOutSoft)) +
                            slideInVertically(tween(300, easing = de.ayutv.desktop.ui.EaseOutSoft)) { if (down) it / 12 else -it / 12 } +
                            androidx.compose.animation.scaleIn(tween(300, easing = de.ayutv.desktop.ui.EaseOutSoft), initialScale = 0.97f)) togetherWith
                            (fadeOut(tween(110)) + slideOutVertically(tween(180)) { if (down) -it / 28 else it / 28 })
                    },
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(app)
                        Screen.LIVE -> LiveScreen(app)
                        Screen.GUIDE -> GuideScreen(app)
                        Screen.SPORT -> SportScreen(app)
                        Screen.MOVIES -> LibraryScreen(app, series = false)
                        Screen.SERIES -> LibraryScreen(app, series = true)
                        Screen.SEARCH -> SearchScreen(app)
                        Screen.APPS -> de.ayutv.desktop.ui.AppsScreen(app)
                        Screen.SETTINGS -> SettingsScreen(app)
                    }
                }
            }
            if (app.askCountries) CountryDialog(app, first = true, onDismiss = { app.askCountries = false })
            if (app.choosingProfile) de.ayutv.desktop.ui.ProfileDialog(app, onDismiss = { app.choosingProfile = false })
        }
        app.dueReminder?.let { r -> de.ayutv.desktop.ui.ReminderDialog(app, r) }
        // Hinweis unten: gleitet herein und wieder hinaus (der letzte Text bleibt dabei stehen).
        val line = app.message ?: status
        val lastLine = remember { arrayOf("") }.also { if (line != null) it[0] = line }
        AnimatedVisibility(
            visible = line != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Row(
                Modifier.background(Palette.raised, RoundedCornerShape(12.dp)).border(1.dp, Palette.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).height(22.dp).background(Palette.accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                Label(lastLine[0], Type.body, maxLines = 3)
            }
        }
    }
}

/** Navigation links: immer erreichbar mit ◀, Maus oder Home-Taste. Uhrzeit oben wie auf dem Fernseher. */
@Composable
private fun Sidebar(app: AppState) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000)
        }
    }
    Column(
        Modifier.width(224.dp).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(Palette.surface.copy(alpha = 0.92f), Palette.surface.copy(alpha = 0.62f))))
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        // Kopf: Name mit Akzentpunkt, darunter groß die Uhrzeit.
        Row(Modifier.padding(start = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("DnA", Type.title)
            Box(Modifier.padding(horizontal = 3.dp).size(7.dp).background(Palette.accent, androidx.compose.foundation.shape.CircleShape))
            Label("TV", Type.title.copy(color = Palette.accent))
        }
        Label(clock(now), Type.heading.copy(color = Palette.secondary), Modifier.padding(start = 10.dp, top = 2.dp, bottom = 16.dp))
        val profiles by app.store.profiles.collectAsState()
        val profileId by app.store.profile.collectAsState()
        if (profiles.size > 1) {
            NavItem("👤", profiles.firstOrNull { it.id == profileId }?.name ?: "Profil", selected = false) { app.choosingProfile = true }
            Spacer(Modifier.height(10.dp))
        }
        Screen.entries.forEach { screen ->
            NavItem(screen.icon, screen.label, selected = app.screen == screen) { app.screen = screen }
            Spacer(Modifier.height(3.dp))
        }
        Spacer(Modifier.weight(1f))
        if (app.updater.state is de.ayutv.desktop.update.Updater.State.Available) {
            NavItem("⬆", "Update verfügbar", selected = false) { app.screen = Screen.SETTINGS }
            Spacer(Modifier.height(6.dp))
        }
        val source = app.input.source
        Label(
            if (source != null) "🎮 ${source.label} verbunden" else "Fernbedienung, Tastatur oder Maus",
            Type.small, Modifier.padding(start = 12.dp, bottom = 6.dp), maxLines = 2,
        )
    }
}

/** Eintrag der Navigation: Symbol in eigener Spalte, gewählte Seite mit Akzent-Leiste links (gleitet weich ein). */
@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val bar by animateDpAsState(if (selected) 22.dp else 0.dp, tween(200))
    Selectable(onClick, Modifier.fillMaxWidth(), selected = selected, shape = RoundedCornerShape(10.dp)) { focused ->
        Box(Modifier.width(4.dp).height(bar).background(Palette.accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Label(icon, Type.heading) }
        Spacer(Modifier.width(10.dp))
        Label(label, if (selected || focused) Type.heading else Type.body.copy(fontSize = 16.sp))
    }
}

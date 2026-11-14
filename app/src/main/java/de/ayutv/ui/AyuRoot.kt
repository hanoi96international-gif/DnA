package de.ayutv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.isTouchDevice
import de.ayutv.ui.components.isPortrait
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.flow.first
import androidx.tv.material3.Text
import de.ayutv.AppContainer
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.ui.guide.GuideScreen
import de.ayutv.ui.home.HomeScreen
import de.ayutv.ui.live.LiveScreen
import de.ayutv.ui.search.SearchScreen
import de.ayutv.ui.settings.AddPlaylistScreen
import de.ayutv.ui.settings.CountryScreen
import kotlinx.coroutines.launch
import de.ayutv.ui.settings.GroupManagerScreen
import de.ayutv.ui.settings.SettingsScreen
import de.ayutv.ui.sport.SportScreen
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.vod.MoviesScreen
import de.ayutv.ui.vod.SeriesScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer fehlt") }

private val NAV_COLLAPSED = 72.dp
private val NAV_EXPANDED = 220.dp
private val NAV_BOTTOM = 68.dp

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Start", Icons.Filled.Home),
    LIVE("Live TV", Icons.Filled.List),
    GUIDE("TV-Guide", Icons.Filled.DateRange),
    SPORT("Sport", Icons.Filled.ThumbUp),
    MOVIES("Filme", Icons.Filled.PlayArrow),
    SERIES("Serien", Icons.Filled.Star),
    SEARCH("Suche", Icons.Filled.Search),
    APPS("Apps", Icons.AutoMirrored.Filled.ExitToApp),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun AyuRoot() {
    val container = LocalContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(initialValue = null)
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val sections = remember(appSettings?.showGuide) {
        Section.entries.filter { it != Section.GUIDE || appSettings?.showGuide != false }
    }
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    var addingPlaylist by remember { mutableStateOf(false) }
    var openSeries by remember { mutableStateOf<SeriesEntity?>(null) }
    var managingGroups by remember { mutableStateOf<PlaylistEntity?>(null) }
    var choosingCountries by remember { mutableStateOf(false) }
    // Einmal nach dem ersten Laden: Länder wählen, wenn der Anbieter Sender aus mehreren Ländern liefert.
    val channelCount by container.content.channelCount.collectAsStateWithLifecycle(initialValue = 0)
    LaunchedEffect(appSettings?.countryChoiceDone, channelCount > 0) {
        if (appSettings?.countryChoiceDone != false || channelCount == 0) return@LaunchedEffect
        val countries = container.content.countryChoices().count { it.code.isNotEmpty() }
        if (countries >= 2) choosingCountries = true
        else container.settings.update { it.copy(countryChoiceDone = true) }
    }
    // Nach „Zurück“ verschwindet der fokussierte Inhalt: dann den Fokus gezielt in die Navigation setzen.
    var navTick by remember { mutableIntStateOf(0) }
    // Suchtaste oder Sprachassistent: zur Suche (den Begriff übernimmt die Suche selbst).
    val searchRequest by container.searchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) section = Section.SEARCH
    }
    // Bereich, dessen Überblendung fertig ist: Erst dann darf OK den Fokus hineinsetzen. Vorher liegt rechts
    // noch der alte, ausblendende Bereich; landet der Fokus dort, verschwindet er mit ihm und springt nach „Start“.
    var settled by remember { mutableStateOf<Section?>(null) }
    // Jeder Bereich behält seinen Zustand (gewählte Gruppe, Scrollposition), auch wenn man zwischendurch wechselt.
    val sectionState = rememberSaveableStateHolder()

    LaunchedEffect(section) { UiPerformance.section = section.label }
    LaunchedEffect(appSettings?.uiTheme, appSettings?.accent) { appSettings?.let { AyuColors.use(it.uiTheme, it.accent) } }
    LaunchedEffect(appSettings?.calmBackground) { appSettings?.let { AyuColors.calm = it.calmBackground } }

    de.ayutv.ui.theme.AppBackground {
        val list = playlists
        when {
            list == null -> Unit
            list.isEmpty() || addingPlaylist -> AddPlaylistScreen(
                onDone = { addingPlaylist = false; navTick++ },
                onCancel = if (list.isEmpty()) null else ({ addingPlaylist = false; navTick++ }),
            )
            managingGroups != null -> GroupManagerScreen(managingGroups!!, onBack = { managingGroups = null; navTick++ })
            choosingCountries -> CountryScreen(onDone = {
                choosingCountries = false
                navTick++
                container.scope.launch { container.settings.update { it.copy(countryChoiceDone = true) } }
            })
            else -> {
                BackHandler(enabled = section != Section.HOME) {
                    section = Section.HOME
                    navTick++
                }
                // Die Navigation liegt über dem Inhalt: Aufklappen verschiebt nichts und löst kein Neu-Layout aus.
                val portrait = isPortrait
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(start = if (portrait) 0.dp else NAV_COLLAPSED, bottom = if (portrait) NAV_BOTTOM else 0.dp)) {
                        // Weicher Wechsel zwischen den Bereichen statt hartem Umschalten.
                        androidx.compose.animation.AnimatedContent(
                            targetState = section,
                            transitionSpec = {
                                // Schwaches Gerät: keine Überblendung (die zeichnet zwei ganze Bildschirme halbdurchsichtig),
                                // aber der neue Bereich gleitet kurz herein. Verschieben kostet den Grafikchip fast nichts:
                                // Die fertig gezeichnete Ebene wird nur versetzt, der alte Bereich ist sofort weg.
                                // Richtung wie in der Seitenleiste: ein Bereich weiter unten kommt von unten, weiter oben von oben.
                                val down = targetState.ordinal >= initialState.ordinal
                                val ease = de.ayutv.ui.components.EaseOutSoft
                                if (UiPerformance.reduced) {
                                    androidx.compose.animation.slideInVertically(
                                        androidx.compose.animation.core.tween(220, easing = ease),
                                    ) { if (down) it / 9 else -it / 9 } togetherWith androidx.compose.animation.ExitTransition.None
                                } else {
                                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, delayMillis = 50, easing = ease)) +
                                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(300, easing = ease)) { if (down) it / 10 else -it / 10 } +
                                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(300, easing = ease), initialScale = 0.97f)) togetherWith
                                        (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                                            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { if (down) -it / 24 else it / 24 })
                                }
                            },
                            label = "section",
                        ) { shown ->
                            val visibility = transition
                            LaunchedEffect(visibility.currentState, visibility.targetState) {
                                if (visibility.currentState == androidx.compose.animation.EnterExitState.Visible &&
                                    visibility.targetState == androidx.compose.animation.EnterExitState.Visible
                                ) settled = shown
                            }
                            sectionState.SaveableStateProvider(shown.name) { when (shown) {
                            Section.HOME -> HomeScreen(onOpenSeries = { openSeries = it; section = Section.SERIES })
                            Section.LIVE -> LiveScreen()
                            Section.GUIDE -> GuideScreen()
                            Section.SPORT -> SportScreen()
                            Section.MOVIES -> MoviesScreen()
                            Section.SERIES -> SeriesScreen(openSeries = openSeries, onOpenSeries = { openSeries = it })
                            Section.SEARCH -> SearchScreen(onOpenSeries = { openSeries = it; section = Section.SERIES })
                            Section.APPS -> de.ayutv.ui.apps.AppsScreen()
                            Section.SETTINGS -> SettingsScreen(
                                onAddPlaylist = { addingPlaylist = true },
                                onManageGroups = { managingGroups = it },
                                onChooseCountries = { choosingCountries = true },
                            )
                        } } }
                    }
                    if (portrait) {
                        BottomNav(sections, section, Modifier.align(Alignment.BottomCenter)) { section = it }
                    } else {
                        SideNav(sections = sections, selected = section, settled = settled, refocusTick = navTick, onSelect = { section = it })
                    }
                }
            }
        }
    }
}

/** Hochformat (Handy): Leiste unten mit Symbol und kurzer Beschriftung, wie in Handy-Apps üblich. */
@Composable
private fun BottomNav(sections: List<Section>, selected: Section, modifier: Modifier = Modifier, onSelect: (Section) -> Unit) {
    Row(
        modifier.fillMaxWidth().height(NAV_BOTTOM).background(AyuColors.Surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEach { item ->
            val isSelected = item == selected
            Surface(
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp, horizontal = 2.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) AyuColors.SurfaceRaised else Color.Transparent,
                    focusedContainerColor = AyuColors.AccentSoft,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) AyuColors.Accent else AyuColors.TextSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        when (item) {
                            Section.LIVE -> "Live"
                            Section.GUIDE -> "Guide"
                            Section.SETTINGS -> "Optionen"
                            else -> item.label
                        },
                        color = if (isSelected) AyuColors.TextPrimary else AyuColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Schmale Leiste mit Symbolen, die sich beim Fokussieren mit Beschriftung aufklappt. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideNav(sections: List<Section>, selected: Section, settled: Section?, refocusTick: Int, onSelect: (Section) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        if (expanded) NAV_EXPANDED else NAV_COLLAPSED,
        // Schwaches Gerät: kurz und ohne Federn (die Leiste liegt über dem Inhalt, nur sie selbst wird neu angeordnet).
        animationSpec = if (UiPerformance.reduced) androidx.compose.animation.core.tween(130, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        else androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 700f),
        label = "nav",
    )
    val dim by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 0.6f else 0f,
        if (UiPerformance.reduced) androidx.compose.animation.core.tween(130) else androidx.compose.animation.core.tween(220),
        label = "dim",
    )
    var enterTick by remember { mutableIntStateOf(0) }
    // Alle Bereiche sollen ohne Scrollen auf den Bildschirm passen, auch mit großer Schrift oder kleiner Auflösung
    // (sonst war „Einstellungen“ unten abgeschnitten): Abstand je Eintrag aus der Bildschirmhöhe berechnen.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val rowPadding = remember(screenHeight, fontScale, sections.size) {
        val fixed = 32f + 40f * fontScale + 8f + 3f * (sections.size + 1)
        val perRow = (screenHeight - fixed) / sections.size.coerceAtLeast(1)
        ((perRow - 22f * maxOf(1f, fontScale)) / 2f).coerceIn(2f, 10f).dp
    }
    val initialFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val touch = isTouchDevice
    // Nicht das Gerät entscheidet, sondern die Eingabe: Manche TV-Boxen (und der Emulator) melden einen Touchscreen,
    // werden aber mit der Fernbedienung bedient.
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current

    if (dim > 0f) {
        // Sanftes Abdunkeln des Inhalts hinter der aufgeklappten Navigation; Tippen daneben klappt sie zu.
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = dim), Color.Black.copy(alpha = dim * 0.4f), Color.Transparent),
                )
            ).then(if (touch) Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } } else Modifier)
        )
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(AyuColors.Surface, AyuColors.Surface.copy(alpha = if (expanded) 0.97f else 0.88f)),
                )
            )
            // Hauchfeine Kante rechts (wie Glas), statt eines harten Übergangs zum Inhalt.
            .drawBehind {
                drawRect(
                    Color.White.copy(alpha = 0.06f),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 0f),
                    size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height),
                )
            }
            .onFocusChanged { expanded = it.hasFocus }
            // ▲/▼ am Anfang oder Ende der Leiste bleiben in ihr (sprangen sonst schräg in den Inhalt daneben, z. B. von
            // „Einstellungen“ auf den untersten Bereich der Einstellungen). Hinein geht es mit OK oder ▶.
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up ||
                        requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down
                    ) cancelFocusChange()
                }
            }
            // Eigene Gruppe außen herum: focusRestorer setzt sein eigenes onExit und würde das obere sonst ersetzen.
            .focusGroup()
            .focusRestorer()
            // Passt sie nicht ganz auf den Bildschirm (viele Bereiche, kleiner Bildschirm), lässt sie sich scrollen.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Schriftzug: „DnA“ kräftig in Weiß, „·TV“ in der Akzentfarbe (eingeklappt nur „DnA“ mit Akzentpunkt).
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("DnA")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = AyuColors.Accent))
                append(if (expanded) "·TV" else "·")
                pop()
            },
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (expanded) 23.sp else 19.sp,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            color = AyuColors.TextPrimary,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )
        sections.forEach { item ->
            val isSelected = item == selected
            var itemFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = {
                    onSelect(item)
                    // Per Touch: nach der Wahl zuklappen. Mit der Fernbedienung: OK geht gleich in den Bereich
                    // (die Leiste klappt dabei zu), statt erst noch ▶ drücken zu müssen.
                    if (inputMode.inputMode == androidx.compose.ui.input.InputMode.Touch) focusManager.clearFocus() else enterTick++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { itemFocused = it.isFocused }
                    .then(if (isSelected) Modifier.focusRequester(initialFocus) else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                // Fokus als helle Pille mit dunkler Schrift (gut aus der Entfernung zu sehen, wirkt edel).
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color(0xFFF4F4F6),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                // Der gewählte Bereich trägt links einen Akzentstrich; der Fokus ist die deutlich hellere Fläche.
                val accent = AyuColors.Accent
                Row(
                    Modifier
                        .then(if (isSelected) Modifier.drawBehind {
                            drawRoundRect(
                                accent,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.25f),
                                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height * 0.5f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            )
                        } else Modifier)
                        .padding(horizontal = 14.dp, vertical = rowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = when {
                            itemFocused -> if (isSelected) AyuColors.AccentSoft else Color(0xFF15171C)
                            isSelected -> AyuColors.Accent
                            else -> AyuColors.TextSecondary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = expanded,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 60)),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)),
                    ) {
                        Row {
                            Spacer(Modifier.width(14.dp))
                            Text(
                                item.label,
                                color = if (itemFocused) Color(0xFF15171C) else if (isSelected) AyuColors.TextPrimary else AyuColors.TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = if (itemFocused || isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    LaunchedEffect(refocusTick) {
        repeat(5) {
            if (runCatching { initialFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    // OK auf einem Bereich: sobald er da ist, den Fokus hineinsetzen (wie ▶).
    val currentSelected by rememberUpdatedState(selected)
    val currentSettled by rememberUpdatedState(settled)
    LaunchedEffect(enterTick) {
        if (enterTick == 0) return@LaunchedEffect
        // Erst wenn der alte Bereich ganz ausgeblendet ist, sonst landet der Fokus noch in ihm.
        kotlinx.coroutines.withTimeoutOrNull(1_500) {
            androidx.compose.runtime.snapshotFlow { currentSettled == currentSelected }.first { it }
        }
        // Ein Bereich kann direkt nach dem Öffnen noch nachladen (Gruppen, Sender) und dabei die Zeile mit dem
        // Fokus ersetzen; dann fällt der Fokus auf den ersten Eintrag der Leiste („Start“) zurück. Darum nachsehen,
        // ob er im Bereich geblieben ist, und sonst erneut hineingehen.
        for (attempt in 0 until 4) {
            for (step in 0 until 12) {
                delay(40)
                if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)) break
            }
            delay(450)
            if (!expanded) return@LaunchedEffect
        }
    }
}

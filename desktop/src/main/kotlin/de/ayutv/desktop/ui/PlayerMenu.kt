package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import de.ayutv.desktop.data.Choice
import de.ayutv.desktop.data.SubtitleBackground
import de.ayutv.desktop.data.SubtitleColor
import de.ayutv.desktop.data.SubtitlePosition
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.display
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.LANGUAGES
import de.ayutv.desktop.data.PictureFit
import de.ayutv.desktop.data.languageName
import de.ayutv.desktop.data.next
import de.ayutv.desktop.player.DisplayRate
import kotlinx.coroutines.delay
import java.util.Locale

private val MenuSecondary = Color(0xFF9AA7B4)
private val MenuGood = Color(0xFF7FD69B)
/** Hintergrund der Menüs rechts: zum Bild hin leicht durchscheinend. */
private val PanelBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xE6090A0D), Color(0xF7090A0D)))

/** Verschiebung als Text: „+1,5 s“. */
fun shiftLabel(ms: Long): String = "%+.1f s".format(Locale.GERMAN, ms / 1000.0)

fun delayLabel(ms: Long): String = if (ms == 0L) "keiner" else "%+.2f s".format(Locale.GERMAN, ms / 1000.0)

@Composable
private fun MenuHeader(text: String) {
    // Kleine Versalien mit Sperrung: ruhige, edle Gliederung (wie auf dem Fire TV).
    Label(text.uppercase(), Type.small.copy(color = MenuSecondary, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 1.6.sp),
        Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun MenuHint(text: String) {
    Label(text, Type.small.copy(color = MenuSecondary), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 5)
}

/** Zeile im Menü: Auswahlpunkt (eins von mehreren) bzw. Kästchen (an/aus), gezeichnet wie auf dem Fire TV. */
@Composable
private fun OptionRow(label: String, selected: Boolean, focusRequester: FocusRequester? = null, check: Boolean = false, onClick: () -> Unit) {
    Selectable(onClick, Modifier.fillMaxWidth(), focusRequester = focusRequester, shape = RoundedCornerShape(10.dp)) {
        Indicator(selected, check)
        Spacer(Modifier.width(14.dp))
        Label(label, Type.body.copy(color = Color.White, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 2)
    }
}

@Composable
private fun Indicator(selected: Boolean, check: Boolean) {
    val shape = if (check) RoundedCornerShape(4.dp) else CircleShape
    Box(
        Modifier.size(18.dp).clip(shape)
            .background(if (selected) Palette.accent else Color.Transparent)
            .border(2.dp, if (selected) Palette.accent else MenuSecondary, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (check) Label("✓", Type.small.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp))
            else Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** Einmalige Aktion (Favorit, Aufnahme, Datei laden …): Symbol statt Auswahlpunkt. */
@Composable
private fun ActionRow(icon: String, label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Selectable(onClick, Modifier.fillMaxWidth(), focusRequester = focusRequester, shape = RoundedCornerShape(10.dp)) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { Label(icon, Type.body.copy(color = Palette.accent, fontSize = 17.sp)) }
        Spacer(Modifier.width(14.dp))
        Label(label, Type.body.copy(color = Color.White), maxLines = 2)
    }
}

/** Zeile der Übersicht: Symbol, Name, aktueller Wert, Pfeil zur Unterseite. */
@Composable
private fun PageRow(section: MenuSection, value: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    Selectable(onClick, Modifier.fillMaxWidth(), focusRequester = focusRequester, shape = RoundedCornerShape(10.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Palette.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Label(section.icon, Type.body.copy(color = Palette.accent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(section.title, Type.body.copy(color = Color.White, fontWeight = FontWeight.Bold))
            Label(value, Type.small.copy(color = MenuSecondary))
        }
        Label("›", Type.body.copy(color = MenuSecondary, fontSize = 24.sp))
    }
}

@Composable
private fun MenuTitle(text: String, back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            Label("‹", Type.body.copy(color = Palette.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold),
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = back).padding(end = 10.dp))
        }
        Label(text, Type.body.copy(color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp))
    }
    // Hauchfeine Linie unter der Überschrift.
    Box(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
}

@Composable
private fun FocusOnOpen(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(8) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Unterseiten des Player-Menüs. */
enum class MenuSection(val title: String, val icon: String) {
    SUBTITLES("Untertitel", "CC"),
    LOOK("Untertitel-Aussehen", "Aa"),
    AUDIO("Ton", "♪"),
    SOUND("Klang", "∿"),
    PICTURE("Bild", "▣"),
    SLEEP("Schlaf-Timer", "☾"),
}

/** Was das Menü für Dinge braucht, die nur der Player kennt (Stream-Info, Vollbild, Fenster …). */
class MenuActions(
    val nextEpisode: (() -> Unit)?,
    val tech: Boolean,
    val toggleTech: () -> Unit,
    val fullscreen: Boolean,
    val toggleFullscreen: () -> Unit,
    val openSync: () -> Unit,
    val openLanguages: () -> Unit,
    val openDelay: () -> Unit,
    val close: () -> Unit,
)

/**
 * Menü rechts im Bild (☰, Menütaste, CC, Rechtsklick), in zwei Stufen wie auf dem Fire TV: Übersicht mit dem, was
 * gerade eingestellt ist („Untertitel · Deutsch ›“), dahinter je eine Unterseite. [page] = offene Unterseite
 * (null = Übersicht); Zurück führt von dort zur Übersicht (PlayerScreen). Alles gilt sofort.
 */
@Composable
fun PlayerMenuPanel(
    app: AppState,
    playback: Playback,
    actions: MenuActions,
    page: MenuSection?,
    onPage: (MenuSection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastPage by remember { mutableStateOf<MenuSection?>(null) }
    LaunchedEffect(page) { if (page != null) lastPage = page }
    Column(
        modifier.fillMaxHeight().width(460.dp).background(PanelBrush, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .padding(top = 28.dp),
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Vor: die Unterseite kommt von rechts, die Übersicht weicht leicht nach links; zurück umgekehrt.
                val forward = targetState != null
                val ease = androidx.compose.animation.core.FastOutSlowInEasing
                (slideInHorizontally(tween(240, easing = ease)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(200, delayMillis = 40))) togetherWith
                    (slideOutHorizontally(tween(200, easing = ease)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(140)))
            },
        ) { shownPage ->
            if (shownPage == null) Overview(app, playback, actions, lastPage, onPage)
            else Column {
                MenuTitle(shownPage.title) { onPage(null) }
                SubPage(app, playback, actions, shownPage)
            }
        }
    }
}

@Composable
private fun Overview(app: AppState, playback: Playback, actions: MenuActions, returnTo: MenuSection?, onPage: (MenuSection?) -> Unit) {
    val player = app.player
    val user by app.store.user.collectAsState()
    val guide by app.store.guide.collectAsState()
    val live = playback as? Playback.Live
    val shown = app.shownSubtitle
    val rows = remember { MenuSection.entries.associateWith { FocusRequester() } }
    val values = mapOf(
        MenuSection.SUBTITLES to (shown?.let { "${languageName(it)} · geladen" }
            ?: player.subtitleTracks.firstOrNull { (id, _) -> id == player.subtitleTrack }?.let { (_, name) -> name } ?: "Aus"),
        MenuSection.LOOK to "${user.subtitleSize.label} · ${user.subtitleColor.label} · ${user.subtitleBackground.label}",
        MenuSection.AUDIO to ((player.audioTracks.firstOrNull { (id, _) -> id == player.audioTrack }?.let { (_, name) -> name } ?: if (player.audioTracks.isEmpty()) "keine" else "–") +
            if (user.audioDelayMs != 0L) " · Versatz ${delayLabel(user.audioDelayMs)}" else ""),
        MenuSection.SOUND to user.audioMode.label + if (user.normalizeVolume) " · angeglichen" else "",
        MenuSection.PICTURE to user.pictureFit.label + if (actions.fullscreen) " · Vollbild" else "",
        MenuSection.SLEEP to if (app.sleepAt > 0) "endet ${clockText(app.sleepAt)}" else "Aus",
    )
    Column {
        MenuTitle(if (live != null) "Optionen" else "Wiedergabe")
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.nextEpisode?.let { next -> item { ActionRow("⏭", "Nächste Folge") { actions.close(); next() } } }
            // Auf einen Fernseher übertragen: LG/Samsung (DLNA) oder Fire TV mit DnA-TV.
            item {
                val tv = app.castTarget
                ActionRow("📺", if (tv != null) "Hier weiterschauen (läuft auf ${tv.name})" else "Auf Fernseher abspielen (LG, Fire TV …)") {
                    actions.close()
                    if (tv != null) app.stopCast() else app.openCastPicker()
                }
            }
            MenuSection.entries.forEach { section ->
                item { PageRow(section, values.getValue(section), rows.getValue(section)) { onPage(section) } }
            }
            if (live != null) {
                item { MenuHeader("Sender") }
                val favorite = live.channel.key in user.favorites
                item {
                    ActionRow(if (favorite) "★" else "☆", if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") {
                        app.toast(if (app.store.toggleFavorite(live.channel.key)) "★ ${live.channel.name} zu Favoriten hinzugefügt" else "${live.channel.name} aus Favoriten entfernt")
                    }
                }
                guide[live.channel.key]?.let { nowOf(it) }?.let { current ->
                    item { ActionRow("⏺", "„${current.display()}“ aufnehmen") { app.record(live.channel, current) } }
                    if (app.store.archiveUrl(live.channel, current.start, current.stop) != null) {
                        item { ActionRow("⏮", "Sendung von Anfang") { actions.close(); app.playArchive(live.channel, current) } }
                    }
                }
                item { ActionRow("◫", "Multiview (bis zu 4 Sender gleichzeitig)") { actions.close(); app.openMultiview() } }
            }
        }
    }
    FocusOnOpen(rows.getValue(returnTo ?: MenuSection.SUBTITLES))
}

@Composable
private fun SubPage(app: AppState, playback: Playback, actions: MenuActions, section: MenuSection) {
    val player = app.player
    val user by app.store.user.collectAsState()
    val guide by app.store.guide.collectAsState()
    val first = remember { FocusRequester() }
    val live = playback as? Playback.Live
    val vod = playback as? Playback.Vod
    val preferred = user.subtitleLanguage.ifBlank { "de" }
    val shown = app.shownSubtitle

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var firstUsed = false
        fun take(): FocusRequester? = if (firstUsed) null else { firstUsed = true; first }
        fun LazyListScope.row(label: String, selected: Boolean, check: Boolean = false, action: () -> Unit) {
            val requester = take()
            item { OptionRow(label, selected, requester, check, action) }
        }
        fun LazyListScope.action(icon: String, label: String, action: () -> Unit) {
            val requester = take()
            item { ActionRow(icon, label, requester, action) }
        }
        fun <T : Choice> LazyListScope.choices(title: String, all: List<T>, current: T, set: (T) -> Unit) {
            item { MenuHeader(title) }
            all.forEach { choice -> row(choice.label, choice == current) { set(choice) } }
        }
        when (section) {
            MenuSection.SUBTITLES -> {
                row("Aus", shown == null && player.subtitleTrack < 0) { app.subtitlesOff() }
                player.subtitleTracks.forEach { (id, name) ->
                    row(name, shown == null && player.subtitleTrack == id) { app.selectSubtitleTrack(id) }
                }
                app.loadedSubtitles.keys.toList().forEach { language ->
                    row("${languageName(language)} (geladen)", shown == language) { app.showLoaded(language) }
                }
                if (shown != null) {
                    item { MenuHeader("Untertitel-Zeit") }
                    action("⏱", "Untertitel-Zeit einstellen … (${shiftLabel(app.subtitleDelayMs)})") { actions.openSync() }
                    action("−", "Früher (−0,25 s)") { app.setSubtitleDelay(app.subtitleDelayMs - 250) }
                    action("+", "Später (+0,25 s)") { app.setSubtitleDelay(app.subtitleDelayMs + 250) }
                    if (vod != null) action("↻", "Andere Untertitel-Datei laden") { actions.close(); app.otherSubtitleFile(vod) }
                    item {
                        MenuHint(
                            app.subtitleSync?.let { "Ausrichtung: $it" + if (app.subtitleDelayMs != 0L) ", von Hand nachgestellt: ${shiftLabel(app.subtitleDelayMs)}." else "." }
                                ?: "Verschiebung: ${shiftLabel(app.subtitleDelayMs)}. DnA-TV richtet die Zeiten nach rund einer Minute Gespräch automatisch am Ton aus.",
                        )
                    }
                }
                if (vod != null) {
                    item { MenuHeader("Weitere Sprachen") }
                    if (preferred !in app.loadedSubtitles) action("⬇", "${languageName(preferred)} laden") { actions.close(); app.loadSubtitles(vod, preferred) }
                    action("🌐", "Andere Sprache laden / übersetzen …") { actions.openLanguages() }
                    if (user.openSubtitlesKey.isBlank()) item { MenuHint("Für Filme ganz ohne Untertitel: OpenSubtitles-Schlüssel unter Einstellungen → Untertitel eintragen (oder Sicherung vom Fire TV übernehmen).") }
                }
            }
            MenuSection.LOOK -> {
                item { SubtitlePreview(user, Modifier.fillMaxWidth()) }
                choices("Größe", SubtitleSize.entries, user.subtitleSize) { v -> app.updateSettings { it.copy(subtitleSize = v) } }
                choices("Farbe", SubtitleColor.entries, user.subtitleColor) { v -> app.updateSettings { it.copy(subtitleColor = v) } }
                choices("Hintergrund", SubtitleBackground.entries, user.subtitleBackground) { v -> app.updateSettings { it.copy(subtitleBackground = v) } }
                choices("Position", SubtitlePosition.entries, user.subtitlePosition) { v -> app.updateSettings { it.copy(subtitlePosition = v) } }
            }
            MenuSection.AUDIO -> {
                if (player.audioTracks.isEmpty()) item { MenuHint("Dieser Stream hat keine (oder noch keine) Tonspur") }
                player.audioTracks.forEach { (id, name) -> row(name, player.audioTrack == id) { player.selectAudio(id) } }
                item { MenuHeader("Lippensynchron") }
                action("⇆", "Ton-Versatz … (${delayLabel(user.audioDelayMs)})") { actions.openDelay() }
            }
            MenuSection.SOUND -> {
                AudioMode.entries.forEach { mode -> row(mode.label, user.audioMode == mode) { app.updateSettings { it.copy(audioMode = mode) } } }
                item { MenuHeader("Lautstärke") }
                row("Lautstärke angleichen (alle Sender)", user.normalizeVolume, check = true) { app.updateSettings { it.copy(normalizeVolume = !it.normalizeVolume) } }
                item { MenuHint("Wirkt, wenn Windows den Ton ausgibt – nicht bei Dolby/DTS, das an Soundbar oder Receiver durchgereicht wird.") }
            }
            MenuSection.PICTURE -> {
                PictureFit.entries.forEach { fit -> row(fit.label, user.pictureFit == fit) { app.updateSettings { it.copy(pictureFit = fit) } } }
                item { MenuHeader("Anzeige") }
                row("Vollbild", actions.fullscreen, check = true) { actions.toggleFullscreen() }
                row("Stream-Info", actions.tech, check = true) { actions.toggleTech() }
                if (DisplayRate.supported) row("Bildwiederholrate anpassen (50 Hz, 24 Hz …)", user.matchFrameRate, check = true) {
                    app.updateSettings { it.copy(matchFrameRate = !it.matchFrameRate) }
                    if (app.store.user.value.matchFrameRate) app.toast("Bildwiederholrate passt sich ab dem nächsten Sender bzw. Film an")
                }
                item { MenuHeader("Für Fortgeschrittene") }
                if (player.gpuVideo) {
                    val nextHdr = HDR_MODES[(HDR_MODES.indexOfFirst { it.first == user.hdrMode } + 1).mod(HDR_MODES.size)]
                    action("◐", "HDR-Anpassung: ${hdrLabel(user.hdrMode)}") {
                        app.store.updateUser { it.copy(hdrMode = nextHdr.first) }
                        app.applyBuffer()
                        app.toast("HDR-Anpassung: ${nextHdr.second}")
                    }
                }
            }
            MenuSection.SLEEP -> {
                val now = System.currentTimeMillis()
                row("Aus", app.sleepAt <= 0) { app.setSleepTimer(0) }
                if (app.sleepAt > 0) item { MenuHint("Endet um ${clockText(app.sleepAt)}" + if (app.sleepPc) ", danach schläft der PC" else "") }
                listOf(15, 30, 45, 60, 90, 120).forEach { minutes -> row("In $minutes Minuten", false) { app.setSleepTimer(now + minutes * 60_000L) } }
                val end = if (live != null) guide[live.channel.key]?.let { nowOf(it) }?.stop?.takeIf { it > now }
                else (player.durationMs - player.positionMs).takeIf { player.durationMs > 0 && it > 0 }?.let { now + it }
                if (end != null) row((if (live != null) "Am Ende der Sendung" else "Am Ende des Films / der Folge") + " (${clockText(end)})", false) { app.setSleepTimer(end) }
                if (user.tvMode && de.ayutv.desktop.TvMode.isWindows) {
                    row("In 60 Minuten, danach PC schlafen legen", false) { app.setSleepTimer(now + 60 * 60_000L, pc = true) }
                }
            }
        }
    }
    FocusOnOpen(first)
}

/** Alle Sprachen: lädt Untertitel (OpenSubtitles) oder übersetzt die englischen (DeepL). */
@Composable
fun SubtitleLanguagePanel(app: AppState, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LazyColumn(
        modifier.fillMaxHeight().width(420.dp).background(PanelBrush, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)),
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Untertitel in welcher Sprache?") }
        item { MenuHint("Erst eine fertige Datei von OpenSubtitles, sonst die englische mit DeepL übersetzt (mit DeepL-Schlüssel).") }
        itemsIndexed(LANGUAGES) { index, (code, name) ->
            OptionRow(name + if (code in app.loadedSubtitles) " (geladen)" else "", app.shownSubtitle == code, if (index == 0) first else null) { onPick(code) }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Untertitel-Zeit einstellen: Zeile abwarten, OK drücken, sobald sie gesprochen wird (wie auf dem Fire TV). */
@Composable
fun SubtitleSyncBar(app: AppState, index: Int, confirmed: String?, modifier: Modifier = Modifier) {
    val cues = app.subtitleCues
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .border(2.dp, Palette.accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Untertitel ausrichten", Type.body.copy(color = Palette.accent), Modifier.weight(1f))
            Label("jetzt ${shiftLabel(app.subtitleDelayMs)}", Type.body.copy(color = MenuSecondary, fontSize = 14.sp))
        }
        cues.getOrNull(index - 1)?.let { Label(it.second, Type.body.copy(color = MenuSecondary), Modifier.padding(top = 8.dp)) }
        Label(
            cues.getOrNull(index)?.let { "„${it.second}“" } ?: "Keine Zeilen geladen",
            Type.title.copy(color = Color.White, textAlign = TextAlign.Center),
            Modifier.padding(vertical = 6.dp), maxLines = 3,
        )
        cues.getOrNull(index + 1)?.let { Label(it.second, Type.body.copy(color = MenuSecondary)) }
        Label(
            confirmed ?: "OK drücken, sobald diese Zeile gesprochen wird",
            Type.body.copy(color = if (confirmed != null) MenuGood else Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
            Modifier.padding(top = 10.dp), maxLines = 2,
        )
        Label(
            "◀ ▶ andere Zeile   ·   ▲ früher  ▼ später (½ s)   ·   ⏯ Pause   ·   Zurück: fertig",
            Type.small.copy(color = MenuSecondary), Modifier.padding(top = 6.dp),
        )
    }
}

/** Ton-Versatz in Schritten von 50 ms (gilt sofort, für alle Sender). */
@Composable
fun AudioDelayDialog(app: AppState, onDismiss: () -> Unit) {
    val user by app.store.user.collectAsState()
    fun change(delta: Long) = app.updateSettings { it.copy(audioDelayMs = if (delta == 0L) 0 else (it.audioDelayMs + delta).coerceIn(-5_000, 5_000)) }
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss, width = 480) {
        Column {
            Label("Ton-Versatz", Type.heading)
            Label("Plus = Ton später (wenn er vor dem Bild kommt), Minus = Ton früher. Gilt für alle Sender und Filme.", Type.small, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Label(delayLabel(user.audioDelayMs), Type.title)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("−0,25 s", { change(-250) })
                Spacer(Modifier.width(6.dp))
                TextButton("−0,05 s", { change(-50) }, focusRequester = first)
                Spacer(Modifier.width(6.dp))
                TextButton("+0,05 s", { change(50) })
                Spacer(Modifier.width(6.dp))
                TextButton("+0,25 s", { change(250) })
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton("Zurücksetzen", { change(0) })
                Spacer(Modifier.width(6.dp))
                TextButton("Fertig", onDismiss, primary = true)
            }
        }
    }
    FocusOnShow(first)
}

package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.remote
import de.ayutv.desktop.player.VideoPlayer

/** Ein Feld im Multiview: eigener Player (erst beim ersten Sender angelegt). */
class MultiTile {
    var channel by mutableStateOf<Channel?>(null)
    var player by mutableStateOf<VideoPlayer?>(null)
}

/**
 * Multiview: bis zu vier Sender gleichzeitig (2 × 2), Ton nur bei einem. [list] ist die Liste, in der je Feld gezappt
 * wird (die des Senders, von dem aus Multiview geöffnet wurde).
 */
class Multiview(val list: List<Channel>) {
    val tiles = List(4) { MultiTile() }
    var focused by mutableIntStateOf(0)
    var audio by mutableIntStateOf(0)
}

/**
 * Bedienung wie auf dem Fernseher: Steuerkreuz wählt das Feld, OK auf leerem Feld wählt einen Sender, OK auf einem
 * Feld holt den Ton dorthin (noch einmal OK: Vollbild), ☰ Menü des Felds, Sender ± zappt im Feld, Zurück: Vollbild mit
 * dem Sender, der gerade Ton hat. Maus: Klick wählt, Doppelklick wie OK.
 */
@Composable
fun MultiviewScreen(app: AppState, mv: Multiview) {
    val focus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val guide by app.store.guide.collectAsState()

    fun pick(index: Int) {
        val current = mv.tiles[index].channel
        val choices = (listOfNotNull(current) + app.store.favorites() + app.store.recentChannels() + mv.list)
            .distinctBy { it.key }.take(300)
        dialog = {
            MenuDialog(
                app, "Sender für Feld ${index + 1}",
                choices.map { c -> MenuItem((if (c.key in app.store.user.value.favorites) "★ " else "") + c.name) { app.setTile(index, c) } },
                onDismiss = { dialog = null },
            )
        }
    }

    fun ok(index: Int) {
        mv.focused = index
        val tile = mv.tiles[index]
        when {
            tile.channel == null -> pick(index)
            mv.audio == index -> app.closeMultiview(tile.channel)
            else -> app.setMultiviewAudio(index)
        }
    }

    fun menu(index: Int) {
        val tile = mv.tiles[index]
        val items = buildList {
            add(MenuItem("📺 Sender wählen …") { pick(index) })
            if (tile.channel != null) {
                if (mv.audio != index) add(MenuItem("🔊 Ton hier") { app.setMultiviewAudio(index) })
                add(MenuItem("⤢ Vollbild") { app.closeMultiview(tile.channel) })
                add(MenuItem("✕ Feld leeren") { app.clearTile(index) })
            }
            add(MenuItem("Multiview beenden") { app.closeMultiview(mv.tiles[mv.audio].channel) })
        }
        dialog = { MenuDialog(app, tile.channel?.name ?: "Feld ${index + 1}", items, onDismiss = { dialog = null }) }
    }

    BackHandler(app, enabled = dialog == null) { app.closeMultiview(mv.tiles[mv.audio].channel ?: mv.tiles.firstNotNullOfOrNull { it.channel }) }
    LaunchedEffect(dialog) { if (dialog == null) runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || dialog != null) return@onKeyEvent false
                when (e.remote()) {
                    RemoteButton.LEFT, RemoteButton.RIGHT -> mv.focused = mv.focused xor 1
                    RemoteButton.UP, RemoteButton.DOWN -> mv.focused = mv.focused xor 2
                    RemoteButton.OK -> ok(mv.focused)
                    RemoteButton.MENU, RemoteButton.CAPTIONS -> menu(mv.focused)
                    RemoteButton.CHANNEL_UP, RemoteButton.NEXT, RemoteButton.FAST_FORWARD -> app.zapTile(mv.focused, +1)
                    RemoteButton.CHANNEL_DOWN, RemoteButton.PREVIOUS, RemoteButton.REWIND -> app.zapTile(mv.focused, -1)
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0..1) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..1) {
                        val index = row * 2 + col
                        TileView(
                            mv.tiles[index], focused = mv.focused == index, audio = mv.audio == index,
                            now = mv.tiles[index].channel?.let { c -> guide[c.key]?.let { nowOf(it) }?.display() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { if (mv.focused == index) ok(index) else mv.focused = index },
                        )
                    }
                }
            }
        }
        Label(
            "◀ ▶ ▲ ▼ Feld  ·  OK Ton / Vollbild  ·  ☰ Menü  ·  Sender ± zappen  ·  Zurück: Vollbild",
            Type.small,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
    dialog?.invoke()
}

@Composable
private fun TileView(tile: MultiTile, focused: Boolean, audio: Boolean, now: String?, modifier: Modifier, onClick: () -> Unit) {
    val player = tile.player
    Box(
        modifier.padding(3.dp)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
    ) {
        if (player != null && tile.channel != null) VideoView(player, Modifier.fillMaxSize())
        val channel = tile.channel
        if (channel == null) {
            Label("＋ Sender wählen (OK)", Type.heading, Modifier.align(Alignment.Center), color = Palette.secondary)
        } else {
            player?.error?.let { Label(it, Type.small, Modifier.align(Alignment.Center), maxLines = 2) }
            if (player != null && !player.hasFrame && player.error == null) Label("Lädt …", Type.small, Modifier.align(Alignment.Center), color = Palette.secondary)
            Label(
                (if (audio) "🔊 " else "") + channel.name + (now?.let { "  ·  $it" } ?: ""),
                Type.body,
                Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

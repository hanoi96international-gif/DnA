package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.remote
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.GroupRef
import de.ayutv.desktop.data.Programme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val HOUR = 3_600_000L
private const val WINDOW = 3 * HOUR
private val DAY_LABEL = DateTimeFormatter.ofPattern("EEEE, dd.MM.", java.util.Locale.GERMAN).withZone(ZoneId.systemDefault())

/** TV-Guide: Sender untereinander, drei Stunden nebeneinander; ◀/▶ blättert, Klick auf „jetzt“ spielt ab. */
@Composable
fun GuideScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    var group by remember { mutableStateOf<GroupRef>(if (user.favorites.isNotEmpty()) GroupRef.Favorites else GroupRef.All) }
    var start by remember { mutableLongStateOf(System.currentTimeMillis() / (HOUR / 2) * (HOUR / 2)) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    val channels = remember(group, content, user) {
        when (val g = group) {
            GroupRef.All -> store.allVisible()
            GroupRef.Favorites -> store.favorites()
            is GroupRef.Custom -> store.inCustom(g.id)
            is GroupRef.Provider -> store.inGroup(g.name)
        }
    }
    val groupName = when (val g = group) {
        GroupRef.All -> "Alle Sender"
        GroupRef.Favorites -> "★ Favoriten"
        is GroupRef.Custom -> user.customGroups.firstOrNull { it.id == g.id }?.name ?: "Gruppe"
        is GroupRef.Provider -> g.name
    }

    // Wie auf dem Fire TV: ⏪/⏩ verschieben das Zeitfenster, ⏯ springt auf jetzt.
    Box(Modifier.fillMaxSize().onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.remote()) {
            RemoteButton.REWIND -> { start -= 2 * HOUR; true }
            RemoteButton.FAST_FORWARD -> { start += 2 * HOUR; true }
            RemoteButton.PLAY_PAUSE -> { start = System.currentTimeMillis() / (HOUR / 2) * (HOUR / 2); true }
            else -> false
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton("▤ $groupName", {
                    dialog = {
                        MenuDialog(app, "Gruppe", buildList {
                            add(MenuItem("★ Favoriten") { group = GroupRef.Favorites })
                            user.customGroups.forEach { g -> add(MenuItem(g.name) { group = GroupRef.Custom(g.id) }) }
                            add(MenuItem("Alle Sender") { group = GroupRef.All })
                            store.liveGroups().forEach { (name, _) -> add(MenuItem(name) { group = GroupRef.Provider(name) }) }
                        }, onDismiss = { dialog = null })
                    }
                })
                Spacer(Modifier.width(12.dp))
                TextButton("◀", { start -= 2 * HOUR })
                Spacer(Modifier.width(6.dp))
                TextButton("Jetzt", { start = System.currentTimeMillis() / (HOUR / 2) * (HOUR / 2) })
                Spacer(Modifier.width(6.dp))
                TextButton("▶", { start += 2 * HOUR })
                Spacer(Modifier.width(6.dp))
                TextButton("+1 Tag", { start += 24 * HOUR })
                Spacer(Modifier.width(16.dp))
                Label(DAY_LABEL.format(Instant.ofEpochMilli(start)), Type.heading)
                Spacer(Modifier.width(16.dp))
                Label("⏪/⏩ 2 Std. · ⏯ jetzt · OK Sendung", Type.small)
            }
            Spacer(Modifier.height(10.dp))
            if (channels.isEmpty()) {
                Empty("Keine Sender in dieser Gruppe.")
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val nameWidth = 200.dp
                    val timeWidth = maxWidth - nameWidth
                    Column {
                        // Zeitleiste
                        Row(Modifier.padding(start = nameWidth).fillMaxWidth().height(24.dp)) {
                            Box(Modifier.width(timeWidth)) {
                                for (i in 0 until 6) {
                                    val t = start + i * HOUR / 2
                                    Label(clock(t), Type.small, Modifier.offset(x = timeWidth * (i / 6f)))
                                }
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(channels, key = { it.key }) { channel ->
                                GuideRow(app, channel, channels, guide[channel.key].orEmpty(), start, nameWidth, timeWidth) { p ->
                                    dialog = { ProgrammeDialog(app, channel, channels, p, onDismiss = { dialog = null }) }
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog?.invoke()
    }
}

@Composable
private fun GuideRow(app: AppState, channel: Channel, list: List<Channel>, programmes: List<Programme>, start: Long, nameWidth: Dp, timeWidth: Dp, onOpen: (Programme) -> Unit) {
    val end = start + WINDOW
    val visible = programmes.filter { it.stop > start && it.start < end }
    val now = System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().height(66.dp).padding(vertical = 2.dp)) {
        Selectable({ app.play(Playback.Live(channel, list)) }, Modifier.width(nameWidth).fillMaxHeight()) {
            Logo(channel.logo, 28)
            Spacer(Modifier.width(8.dp))
            Label(channel.name, Type.body)
        }
        Box(Modifier.width(timeWidth).fillMaxHeight().clipToBounds()) {
            if (visible.isEmpty()) Label("Kein Programm", Type.small, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            for (p in visible) {
                val from = (p.start.coerceAtLeast(start) - start).toFloat() / WINDOW
                val to = (p.stop.coerceAtMost(end) - start).toFloat() / WINDOW
                val running = p.start <= now && p.stop > now
                Selectable(
                    { if (running) app.play(Playback.Live(channel, list)) else onOpen(p) },
                    Modifier.offset(x = timeWidth * from).width(timeWidth * (to - from)).fillMaxHeight().padding(end = 2.dp)
                        .background(if (running) Palette.raised else Palette.surface),
                    onMenu = { onOpen(p) },
                    selected = running,
                ) {
                    // Farbstreifen nach Art der Sendung (wie auf dem Fire TV).
                    val genre = remember(p, channel.name) { de.ayutv.data.parse.ProgrammeGenre.of(p.title, p.category, channel.name) }
                    if (genre != de.ayutv.data.parse.ProgrammeGenre.OTHER) {
                        Box(Modifier.width(4.dp).height(34.dp).background(androidx.compose.ui.graphics.Color(genre.argb), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        val archived = p.stop <= now && channel.catchupDays > 0 && app.store.archiveUrl(channel, p.start, p.stop, now) != null
                        // Zwei Zeilen: lange Titel und Partien bleiben lesbar.
                        Label((if (archived) "⏪ " else "") + p.display(), Type.body.copy(fontSize = 14.sp, lineHeight = 16.sp), maxLines = 2)
                        Label("${clock(p.start)} – ${clock(p.stop)}", Type.small.copy(lineHeight = 15.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeDialog(app: AppState, channel: Channel, list: List<Channel>, p: Programme, onDismiss: () -> Unit) {
    Overlay(app, onDismiss, width = 560) {
        Column {
            Label(p.display(), Type.heading, maxLines = 2)
            p.subtitle?.let { Label(it, Type.body, maxLines = 2) }
            Label("${channel.name} · ${DAY_LABEL.format(Instant.ofEpochMilli(p.start))} ${clock(p.start)} – ${clock(p.stop)}", Type.small)
            p.category?.let { Label(it, Type.small) }
            p.description?.let { Spacer(Modifier.height(8.dp)); Label(it, Type.body, maxLines = 14) }
            Spacer(Modifier.height(12.dp))
            val now = System.currentTimeMillis()
            val archive = p.start < now && app.store.archiveUrl(channel, p.start, p.stop, now) != null
            Row {
                if (archive) {
                    TextButton(if (p.stop > now) "⏮ Von Anfang" else "⏪ Aus dem Archiv", { onDismiss(); app.playArchive(channel, p) }, primary = true)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton("▶ Sender einschalten", { onDismiss(); app.play(Playback.Live(channel, list)) }, primary = !archive)
                if (p.stop > now) {
                    Spacer(Modifier.width(8.dp))
                    TextButton("⏺ Aufnehmen", { onDismiss(); app.record(channel, p) })
                }
                if (p.start > now) {
                    Spacer(Modifier.width(8.dp))
                    val set = app.store.hasReminder(channel, p.start)
                    TextButton(if (set) "⏰ Erinnerung entfernen" else "⏰ Erinnern", {
                        onDismiss()
                        app.toast(if (app.store.toggleReminder(channel, p)) "Erinnerung um ${clock(p.start)}: ${p.title}" else "Erinnerung entfernt")
                    })
                }
                Spacer(Modifier.width(8.dp))
                TextButton("Schließen", onDismiss)
            }
        }
    }
}

package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.GroupRef
import de.ayutv.desktop.data.Programme
import de.ayutv.ui.live.gatherBlock
import de.ayutv.ui.live.moveBlock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
fun clock(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms))

/** Gruppen links, Sender in der Mitte, rechts was läuft und was danach kommt. */
@Composable
fun LiveScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    val playlists by store.playlists.collectAsState()

    var group by remember { mutableStateOf(app.liveGroup ?: if (user.favorites.isNotEmpty()) GroupRef.Favorites else GroupRef.All) }
    var focusedKey by remember { mutableStateOf(app.liveFocus) }
    LaunchedEffect(group) { app.liveGroup = group }
    LaunchedEffect(focusedKey) { app.liveFocus = focusedKey }
    // Auswahl für Mehrfachauswahl; Verschieben bewegt die ausgewählten (oder den einen) Sender.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf<List<Channel>?>(null) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    val groups = remember(content, user, playlists) { store.liveGroups() }
    val channels = remember(group, content, user, playlists) {
        when (val g = group) {
            GroupRef.All -> store.allVisible()
            GroupRef.Favorites -> store.favorites()
            is GroupRef.Custom -> store.inCustom(g.id)
            is GroupRef.Provider -> store.inGroup(g.name)
        }
    }
    val shown = moving ?: channels
    val sortable = group != GroupRef.All

    if (playlists.isEmpty()) {
        Empty("Noch keine Playlist. Unter „Einstellungen“ eine Playlist hinzufügen oder die Sicherung vom Fire TV übernehmen.")
        return
    }

    fun finishMove() {
        val list = moving ?: return
        store.saveOrder(group, list.map { it.key })
        moving = null
        selecting = false
        selected = emptySet()
        app.toast("Reihenfolge gespeichert")
    }
    BackHandler(app, enabled = moving != null) { finishMove() }
    BackHandler(app, enabled = selecting && moving == null) { selecting = false; selected = emptySet() }

    fun chooseGroup(keys: List<String>) {
        dialog = {
            MenuDialog(
                app, "Zu Gruppe hinzufügen",
                user.customGroups.map { g ->
                    MenuItem(g.name) { val n = store.addToGroup(g.id, keys); app.toast("$n Sender zu „${g.name}“ hinzugefügt") }
                } + MenuItem("＋ Neue Gruppe …") {
                    dialog = {
                        NameDialog(app, "Neue Gruppe", onDone = { name ->
                            store.createGroup(name, keys); app.toast("Gruppe „$name“ angelegt")
                        }, onDismiss = { dialog = null })
                    }
                },
                onDismiss = { if (dialog != null) dialog = null },
            )
        }
    }

    fun startMove(keys: Set<String>) {
        moving = gatherBlock(channels, keys) { it.key }
        selected = keys
        app.toast("Mit ↑/↓ verschieben, Enter oder Esc speichert")
    }

    fun channelMenu(channel: Channel) {
        val isFavorite = channel.key in user.favorites
        dialog = {
            MenuDialog(
                app, channel.name,
                buildList {
                    add(MenuItem(if (isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten") {
                        app.toast(if (store.toggleFavorite(channel.key)) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt")
                    })
                    add(MenuItem("Zu Gruppe hinzufügen …") { chooseGroup(listOf(channel.key)) })
                    (group as? GroupRef.Custom)?.let { g -> add(MenuItem("Aus dieser Gruppe entfernen") { store.removeFromGroup(g.id, listOf(channel.key)) }) }
                    if (sortable) add(MenuItem("↕ Verschieben") { startMove(setOf(channel.key)) })
                    add(MenuItem("☑ Mehrere auswählen") { selecting = true; selected = setOf(channel.key) })
                    val own = user.channelNumbers[channel.key]
                    add(MenuItem("# Sendernummer festlegen …" + (own?.let { " (jetzt $it)" } ?: "")) {
                        dialog = {
                            NameDialog(app, "Sendernummer für ${channel.name} (1–9999)", (own ?: channel.number.takeIf { it > 0 })?.toString().orEmpty(), onDone = { text ->
                                val number = text.filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it > 0 }
                                if (number == null) app.toast("Bitte eine Zahl eingeben") else {
                                    store.setChannelNumber(channel.key, number)
                                    app.toast("${channel.name} hat jetzt die Nummer $number")
                                }
                            }, onDismiss = { dialog = null })
                        }
                    })
                    if (own != null) add(MenuItem("Eigene Nummer entfernen") { store.setChannelNumber(channel.key, null) })
                },
                onDismiss = { dialog = null },
            )
        }
    }

    fun groupMenu(ref: GroupRef, name: String) {
        dialog = {
            MenuDialog(
                app, name,
                when (ref) {
                    is GroupRef.Custom -> listOf(
                        MenuItem("Umbenennen") { dialog = { NameDialog(app, "Gruppe umbenennen", name, { store.renameGroup(ref.id, it) }, { dialog = null }) } },
                        MenuItem("▲ Nach oben") { store.moveGroup(ref.id, -1) },
                        MenuItem("▼ Nach unten") { store.moveGroup(ref.id, 1) },
                        MenuItem("Löschen") {
                            dialog = { ConfirmDialog(app, "Gruppe „$name“ löschen? Die Sender bleiben erhalten.", "Löschen", { store.deleteGroup(ref.id); group = GroupRef.All }, { dialog = null }) }
                        },
                    )
                    is GroupRef.Provider -> listOf(
                        MenuItem("Gruppe ausblenden") {
                            store.hideGroup(ref.name)
                            if (group == ref) group = GroupRef.All
                            app.toast("„${ref.name}“ ausgeblendet. Sender in Favoriten und eigenen Gruppen bleiben.")
                        },
                        MenuItem("🔒 Mit PIN sperren") {
                            if (user.parentalPin.isEmpty()) return@MenuItem app.toast("Zuerst unter Einstellungen → Kindersicherung eine PIN festlegen")
                            store.setLocked(de.ayutv.desktop.data.GroupKind.LIVE, ref.name, lock = true)
                            if (group == ref) group = GroupRef.All
                            app.toast("„${ref.name}“ gesperrt. Entsperren: Einstellungen → Kindersicherung")
                        },
                    )
                    else -> emptyList()
                },
                onDismiss = { if (dialog != null) dialog = null },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // ------------------------------------------------ Gruppen
            Panel(Modifier.width(250.dp).fillMaxHeight()) {
                LazyColumn {
                    item { GroupRow("★ Favoriten", user.favorites.size, group == GroupRef.Favorites) { group = GroupRef.Favorites } }
                    items(user.customGroups, key = { "c${it.id}" }) { g ->
                        val ref = GroupRef.Custom(g.id)
                        GroupRow("▣ ${g.name}", g.keys.size, group == ref, onMenu = { groupMenu(ref, g.name) }) { group = ref }
                    }
                    item {
                        GroupRow("＋ Neue Gruppe", null, false) {
                            dialog = { NameDialog(app, "Neue Gruppe", onDone = { group = GroupRef.Custom(store.createGroup(it)) }, onDismiss = { dialog = null }) }
                        }
                    }
                    item { GroupRow("Alle Sender", null, group == GroupRef.All) { group = GroupRef.All } }
                    items(groups, key = { "p${it.first}" }) { (name, count) ->
                        val ref = GroupRef.Provider(name)
                        GroupRow(name, count, group == ref, onMenu = { groupMenu(ref, name) }) { group = ref }
                    }
                    if (user.hiddenGroups.isNotEmpty()) item {
                        GroupRow("Ausgeblendete zeigen (${user.hiddenGroups.size})", null, false) { store.showAllGroups() }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))

            // ------------------------------------------------ Sender
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("${selected.size} ausgewählt", Type.heading, Modifier.padding(end = 12.dp))
                        if (sortable) TextButton("↕ Verschieben", { if (selected.isNotEmpty()) startMove(selected) })
                        Spacer(Modifier.width(6.dp))
                        TextButton("Zu Gruppe …", { if (selected.isNotEmpty()) chooseGroup(channels.map { it.key }.filter { it in selected }) })
                        Spacer(Modifier.width(6.dp))
                        TextButton("☆ Favoriten", { app.toast("${store.addFavorites(channels.map { it.key }.filter { it in selected })} zu Favoriten hinzugefügt") })
                        when (val g = group) {
                            GroupRef.Favorites -> { Spacer(Modifier.width(6.dp)); TextButton("Entfernen", { store.removeFavorites(selected); selected = emptySet() }) }
                            is GroupRef.Custom -> { Spacer(Modifier.width(6.dp)); TextButton("Entfernen", { store.removeFromGroup(g.id, selected); selected = emptySet() }) }
                            else -> Unit
                        }
                        Spacer(Modifier.width(6.dp))
                        TextButton("Fertig", { selecting = false; selected = emptySet() })
                    }
                } else if (moving != null) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Verschieben: ↑/↓, Enter speichert", Type.heading, Modifier.padding(end = 12.dp))
                        TextButton("Fertig", { finishMove() }, primary = true)
                    }
                }
                if (shown.isEmpty()) {
                    Empty(
                        when (group) {
                            GroupRef.Favorites -> "Noch keine Favoriten. ☰ oder Rechtsklick (oder OK lange drücken) auf einen Sender → „Zu Favoriten“."
                            is GroupRef.Custom -> "Die Gruppe ist leer. Rechtsklick auf einen Sender → „Zu Gruppe hinzufügen“."
                            else -> "Keine Sender. Unter „Einstellungen“ die Playlist neu laden."
                        },
                    )
                } else {
                    val listState = rememberLazyListState()
                    val firstRow = remember { FocusRequester() }
                    // Zurück aus dem Player: beim zuletzt gewählten Sender weitermachen.
                    val restoreKey = remember { app.liveFocus?.takeIf { k -> shown.any { it.key == k } } }
                    val restore = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        val at = shown.indexOfFirst { it.key == restoreKey }
                        if (at < 0) return@LaunchedEffect
                        listState.scrollToItem((at - 3).coerceAtLeast(0))
                        withFrameNanos { }
                        runCatching { restore.requestFocus() }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().appear(group, 32.dp, 220).onPreviewKeyEvent { e ->
                        val list = moving
                        if (list == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { moving = moveBlock(list, selected, up = true) { it.key }; true }
                            Key.DirectionDown -> { moving = moveBlock(list, selected, up = false) { it.key }; true }
                            Key.Enter, Key.NumPadEnter -> { finishMove(); true }
                            else -> false
                        }
                    }) {
                        itemsIndexed(shown, key = { _, c -> c.key }) { index, channel ->
                            val programme = guide[channel.key]?.let { now(it) }
                            val mark = when {
                                moving != null && channel.key in selected -> "↕ "
                                selecting -> if (channel.key in selected) "☑ " else "☐ "
                                channel.key in user.favorites && group != GroupRef.Favorites -> "★ "
                                else -> ""
                            }
                            ListRow(
                                title = "$mark${channel.name}",
                                subtitle = programme?.let { "${clock(it.start)}  ${it.display()}" },
                                trailing = if (group == GroupRef.All || group == GroupRef.Favorites || group is GroupRef.Custom) channel.group else null,
                                selected = channel.key == focusedKey || (moving != null && channel.key in selected),
                                focusRequester = when {
                                    channel.key == restoreKey -> restore
                                    index == 0 -> firstRow
                                    else -> null
                                },
                                onMenu = { if (moving == null) channelMenu(channel) },
                                onClick = {
                                    when {
                                        moving != null -> finishMove()
                                        selecting -> selected = if (channel.key in selected) selected - channel.key else selected + channel.key
                                        else -> app.play(Playback.Live(channel, shown))
                                    }
                                },
                                leading = { Logo(channel.logo) },
                                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedKey = channel.key },
                            )
                        }
                    }
                    LaunchedEffect(moving, selected) {
                        val list = moving ?: return@LaunchedEffect
                        val at = list.indexOfFirst { it.key in selected }
                        val visible = listState.layoutInfo.visibleItemsInfo
                        if (at >= 0 && visible.isNotEmpty() && (at <= visible.first().index || at >= visible.last().index)) {
                            listState.scrollToItem((at - 3).coerceAtLeast(0))
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))

            // ------------------------------------------------ Was läuft
            val focused = focusedKey?.let { k -> shown.firstOrNull { it.key == k } }
            // Mini-Vorschau: Bleibt die Auswahl kurz auf einem Sender, läuft er stumm oben im Feld.
            LaunchedEffect(focused?.key, user.livePreview) {
                if (focused == null || !user.livePreview) { app.stopPreview(); return@LaunchedEffect }
                kotlinx.coroutines.delay(1_200)
                app.showPreview(focused)
            }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.stopPreview() } }
            Panel(Modifier.width(320.dp).fillMaxHeight()) { Column {
                val preview = app.preview
                if (focused != null && preview != null && app.previewKey == focused.key) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                        VideoView(preview, Modifier.fillMaxSize())
                        preview.error?.let { Label(it, Type.small, Modifier.align(Alignment.Center), maxLines = 2) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (focused == null) {
                    Label("Sender auswählen: Steuerkreuz, Pfeiltasten oder Maus. OK/Klick spielt ab, ☰, Rechtsklick oder OK lange drücken öffnet das Menü (Favorit, Gruppe, Verschieben).", Type.small, maxLines = 6)
                } else {
                    ChannelInfo(focused, guide[focused.key].orEmpty())
                }
            } }
        }
        dialog?.invoke()
    }
}

@Composable
private fun GroupRow(title: String, count: Int?, selected: Boolean, onMenu: (() -> Unit)? = null, onClick: () -> Unit) =
    ListRow(title, onClick, trailing = count?.toString(), selected = selected, onMenu = onMenu)

@Composable
fun ChannelInfo(channel: Channel, programmes: List<Programme>) {
    val now = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val next = programmes.filter { it.start >= (current?.stop ?: now) }.take(5)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(channel.logo, 48)
            Spacer(Modifier.width(10.dp))
            Label(channel.name, Type.heading, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        if (current == null) {
            Label("Kein TV-Guide für diesen Sender.", Type.small)
        } else {
            Label("JETZT  ${clock(current.start)} – ${clock(current.stop)}", Type.small, color = Palette.live)
            Label(current.display(), Type.heading, maxLines = 2)
            current.subtitle?.let { Label(it, Type.body, maxLines = 2) }
            Spacer(Modifier.height(6.dp))
            Progress((now - current.start).toFloat() / (current.stop - current.start))
            current.description?.let { Spacer(Modifier.height(6.dp)); Label(it, Type.small, maxLines = 8) }
        }
        if (next.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Label("DANACH", Type.small)
            next.forEach { p -> Label("${clock(p.start)}  ${p.display()}", Type.body) }
        }
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(text, Type.body, Modifier.width(520.dp), maxLines = 4) }
}

@Composable
fun Logo(url: String?, size: Int = 36) {
    val image = rememberImage(url)
    Box(Modifier.size(size.dp).background(Palette.raised, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (image != null) androidx.compose.foundation.Image(image, null, Modifier.size(size.dp))
    }
}

private fun now(list: List<Programme>): Programme? {
    val t = System.currentTimeMillis()
    return list.firstOrNull { it.start <= t && it.stop > t }
}

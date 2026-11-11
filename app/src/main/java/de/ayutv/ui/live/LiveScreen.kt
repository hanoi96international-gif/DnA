package de.ayutv.ui.live

import de.ayutv.ui.components.appear
import de.ayutv.ui.components.isPortrait
import de.ayutv.ui.components.keepVerticalFocus
import androidx.compose.runtime.produceState
import de.ayutv.ui.components.ProgrammeImage
import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.repo.customGroupId
import de.ayutv.data.repo.customGroupKey
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.CategoryColumn
import de.ayutv.ui.components.drillContent
import de.ayutv.ui.components.CategoryEntry
import de.ayutv.ui.components.ChannelListItem
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.NoticeBar
import de.ayutv.ui.components.ProgressLine
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.progress
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState

const val FAVORITES_GROUP = "\u0000favoriten"
private const val NOW_PAGE = 30
/** Knopf „Neue Gruppe“ in der Gruppenspalte. */
private const val NEW_GROUP = "\u0000neue-gruppe"

/** Von Hand sortierbar: Favoriten, eigene Gruppen und Gruppen des Anbieters (nicht „Alle Sender“). */
private fun sortable(group: String?) = group != null

/** Fenster über der Senderliste. */
private sealed interface LiveDialog {
    data class ChannelMenu(val channel: ChannelEntity) : LiveDialog
    /** Aktionen für mehrere ausgewählte Sender. */
    data class SelectionMenu(val keys: List<String>) : LiveDialog
    data class GroupMenu(val group: String, val name: String) : LiveDialog
    data class NameGroup(val group: String?, val current: String, val firstKeys: List<String>) : LiveDialog
    data class SetNumber(val channel: ChannelEntity) : LiveDialog
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen() {
    val container = LocalContainer.current
    val content = container.content
    val appSettings by LocalContainer.current.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val merge = appSettings?.mergeVariants != false
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val groups by content.groups.collectAsStateWithLifecycle(emptyList())
    val customGroups by content.customGroups.collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<LiveDialog?>(null) }
    // Verschieben: diese Sender (ein oder mehrere als Block) wandern mit ▲ ▼, die Liste zeigt solange [moving].
    var movingKeys by remember { mutableStateOf<Set<String>?>(null) }
    var moving by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    // Mehrfachauswahl: OK hakt Sender an, OK halten öffnet die Aktionen für alle angehakten.
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    val favoriteKeys by content.favoriteKeys.collectAsStateWithLifecycle(emptyList())
    val total by content.channelCount.collectAsStateWithLifecycle(0)
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val minute by rememberMinuteTicker()
    // Andere Gruppe: Auswahl und Verschieben enden.
    LaunchedEffect(selectedGroup) {
        selecting = false
        picked = emptyList()
    }

    val channelsFlow = remember(selectedGroup) {
        when (selectedGroup) {
            FAVORITES_GROUP -> content.favoriteChannels
            else -> content.channelsIn(selectedGroup)
        }
    }
    // null = lädt noch (dann kein „Keine Sender“-Aufblitzen), leer = wirklich keine.
    val raw by channelsFlow.collectAsStateWithLifecycle(null)
    // Gleiche Sender in mehreren Qualitäten (SD/HD/4K/RAW/Backup) als ein Eintrag; Favoriten bleiben, wie sie sind.
    val loaded by produceState<List<ChannelEntity>?>(null, raw, merge, selectedGroup, appSettings?.minQuality, appSettings?.preferUhd, appSettings?.hideBrokenChannels) {
        appSettings?.let { de.ayutv.data.parse.ChannelQuality.configure(it.minQuality.height, it.preferUhd, it.hideBrokenChannels) }
        val list = raw
        val keepAsIs = selectedGroup == FAVORITES_GROUP || selectedGroup?.let(::customGroupId) != null
        value = if (list == null) null else if (merge && !keepAsIs) content.mergeVariants(list) else list
    }
    val channels = moving ?: loaded.orEmpty()
    val favoriteSet = remember(favoriteKeys) { favoriteKeys.toHashSet() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Neue Gruppe: oben beginnen (nicht an der Scrollposition der vorigen).
    var shownGroup by rememberSaveable { mutableStateOf(selectedGroup) }
    LaunchedEffect(selectedGroup) {
        if (shownGroup != selectedGroup) {
            shownGroup = selectedGroup
            listState.scrollToItem(0)
        }
    }

    // „Jetzt läuft“ nur für den sichtbaren Ausschnitt (plus Vorlauf) laden: Bei 20.000 Sendern
    // würde sonst jede Minute das ganze Programm aller Sender gelesen.
    var nowMap by remember { mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()) }
    LaunchedEffect(channels, minute) {
        snapshotFlow { listState.firstVisibleItemIndex / NOW_PAGE }
            .distinctUntilChanged()
            .collectLatest { page ->
                val from = ((page - 1) * NOW_PAGE).coerceAtLeast(0)
                val to = ((page + 3) * NOW_PAGE).coerceAtMost(channels.size)
                if (from < to) nowMap = nowMap + content.nowPlaying(channels.subList(from, to))
            }
    }

    // Fokussierter Sender als State-Objekt: nur die Detailspalte liest ihn. So wird beim Scrollen
    // nicht bei jedem Tastendruck der ganze Bildschirm neu berechnet.
    val focusedState = remember { mutableStateOf<ChannelEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }

    val entries = remember(groups, customGroups, total, favoriteKeys.size) {
        buildList {
            add(CategoryEntry(FAVORITES_GROUP, "★ Favoriten", favoriteKeys.size, removable = false))
            customGroups.forEach { add(CategoryEntry(customGroupKey(it.id), "📁 ${it.name}", it.count)) }
            add(CategoryEntry(NEW_GROUP, "＋ Neue Gruppe", null, removable = false, action = true))
            add(CategoryEntry(null, "Alle Sender", total))
            groups.forEach { add(CategoryEntry(it.name, it.name, it.count)) }
        }
    }

    // Gruppe geöffnet: nur noch ihre Sender, die Gruppenspalte ist weg. Zurück holt sie wieder.
    val drill = de.ayutv.ui.components.rememberDrillDown()
    // Geöffnete Gruppe ohne Sender (z. B. nach dem Ausblenden): zurück zu den Gruppen, sonst gäbe es keinen Weg dorthin.
    LaunchedEffect(drill.isOpen, loaded) {
        if (drill.isOpen && loaded?.isEmpty() == true && moving == null) drill.close()
    }
    Row(Modifier.fillMaxSize()) {
        if (!drill.isOpen) CategoryColumn(
            entries,
            selectedGroup,
            onSelect = { selectedGroup = it },
            // Zurück aus der Gruppe: Die Spalte gleitet von links wieder herein.
            modifier = Modifier.appear(drill.backTick, (-40).dp),
            onOpen = { drill.enter() },
            focusSelectedTick = drill.backTick,
            onLongClick = { name ->
                // Eigene Gruppe: umbenennen, verschieben, löschen. Gruppe des Anbieters: ausblenden (Favoriten und eigene
                // Gruppen behalten ihre Sender trotzdem).
                val own = customGroups.firstOrNull { customGroupKey(it.id) == name }
                if (own != null) {
                    dialog = LiveDialog.GroupMenu(name, own.name)
                } else {
                    scope.launch {
                        content.hideGroupEverywhere(GroupKind.LIVE, name)
                        if (selectedGroup == name) selectedGroup = null
                        notice = "„$name“ ausgeblendet (Favoriten und eigene Gruppen behalten ihre Sender). Wieder einblenden: Einstellungen → Playlist → Gruppen"
                    }
                }
            },
            onAction = { dialog = LiveDialog.NameGroup(null, "", emptyList()) },
        )

        if (loaded == null) {
            Spacer(Modifier.weight(1f))
        } else if (channels.isEmpty()) {
            EmptyHint(
                if (selectedGroup == FAVORITES_GROUP) "Noch keine Favoriten" else "Keine Sender",
                when {
                    selectedGroup == FAVORITES_GROUP -> "Halte OK auf einem Sender gedrückt und wähle „Zu den Favoriten“."
                    selectedGroup?.let(::customGroupId) != null -> "Halte OK auf einem Sender gedrückt und wähle diese Gruppe."
                    else -> ""
                },
                Modifier.weight(1f),
            )
        } else {
            // Verschieben: ▲ ▼ bewegen den Sender, OK oder Zurück legt ihn ab und speichert die Reihenfolge.
            fun finishMove() {
                val order = moving
                val group = selectedGroup
                movingKeys = null
                if (order == null) return
                scope.launch {
                    content.saveOrder(group, favorites = group == FAVORITES_GROUP, keys = order.map { it.key })
                    // Die neue Reihenfolge erst aus der Datenbank zeigen, wenn sie dort angekommen ist (kein Zurückspringen).
                    delay(400)
                    moving = null
                    notice = "Reihenfolge gespeichert"
                }
            }
            androidx.activity.compose.BackHandler(enabled = movingKeys != null) { finishMove() }
            androidx.activity.compose.BackHandler(enabled = selecting && movingKeys == null) {
                selecting = false
                picked = emptyList()
            }
            // Gruppe öffnen und schließen: Die Senderliste gleitet an ihren neuen Platz.
            Column(Modifier.weight(1f).fillMaxHeight().appear(drill.isOpen, if (drill.isOpen) 56.dp else (-24).dp)) {
            if (drill.isOpen) de.ayutv.ui.components.DrillTitle(entries.firstOrNull { it.id == selectedGroup }?.label ?: "Sender", onBack = { drill.close() })
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .drillContent(drill, backEnabled = movingKeys == null && !selecting)
                    .keepVerticalFocus().focusRestorer().onPreviewKeyEvent { event ->
                    val keys = movingKeys ?: return@onPreviewKeyEvent false
                    val list = moving ?: return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val up = code == android.view.KeyEvent.KEYCODE_DPAD_UP
                                val next = moveBlock(list, keys, up) { it.key }
                                if (next !== list) {
                                    moving = next
                                    val to = if (up) next.indexOfFirst { it.key in keys } else next.indexOfLast { it.key in keys }
                                    scope.launch {
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        if (visible.isNotEmpty() && (to <= visible.first().index || to >= visible.last().index)) {
                                            listState.scrollToItem((to - 3).coerceAtLeast(0))
                                        }
                                    }
                                }
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                            if (event.type == KeyEventType.KeyUp) finishMove()
                            true
                        }
                        // Nicht in die Gruppenspalte springen, solange ein Sender in der Hand ist.
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        else -> false
                    }
                },
                contentPadding = PaddingValues(vertical = 24.dp, horizontal = 48.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels, key = { it.key }, contentType = { "channel" }) { channel ->
                    ChannelListItem(
                        channel = channel,
                        now = channel.epgId?.let { nowMap[it] },
                        favorite = channel.key in favoriteSet,
                        selected = movingKeys?.contains(channel.key) == true || selecting && channel.key in picked,
                        onFocused = { focusedState.value = channel },
                        onClick = {
                            if (selecting) {
                                picked = if (channel.key in picked) picked - channel.key else picked + channel.key
                            } else {
                                val favoritesOnly = selectedGroup == FAVORITES_GROUP
                                PlayerActivity.startLive(context, channel.key, selectedGroup.takeUnless { favoritesOnly }, favoritesOnly)
                            }
                        },
                        onLongClick = {
                            when {
                                movingKeys != null -> Unit
                                selecting -> dialog = LiveDialog.SelectionMenu(picked.ifEmpty { listOf(channel.key) })
                                else -> dialog = LiveDialog.ChannelMenu(channel)
                            }
                        },
                    )
                }
            }
            }
        }

        // Hochformat: kein Platz für die Detailspalte (Sendungsinfo steht in der Senderzeile).
        if (!isPortrait) ChannelDetails(focusedState, Modifier.width(290.dp).fillMaxHeight())
    }
    when {
        movingKeys != null -> NoticeBar(
            (if ((movingKeys?.size ?: 0) > 1) "${movingKeys?.size} Sender: " else "") + "▲ ▼ verschieben  ·  OK oder Zurück: hier ablegen"
        )
        selecting -> NoticeBar("Auswahl: ${picked.size}  ·  OK: an-/abwählen  ·  OK halten: Aktionen  ·  Zurück: beenden")
        else -> notice?.let { NoticeBar(it) }
    }

    when (val d = dialog) {
        null -> Unit
        is LiveDialog.ChannelMenu -> ChannelMenuDialog(
            channel = d.channel,
            favorite = d.channel.key in favoriteSet,
            customGroups = customGroups.map { customGroupKey(it.id) to it.name },
            canMove = sortable(selectedGroup) && channels.any { it.key == d.channel.key },
            onFavorite = {
                dialog = null
                // In der Favoriten-Gruppe verschwindet der Sender: vorher den Fokus weitergeben.
                if (selectedGroup == FAVORITES_GROUP && !focusManager.moveFocus(FocusDirection.Down)) focusManager.moveFocus(FocusDirection.Up)
                scope.launch {
                    val now = content.toggleFavorite(d.channel.key)
                    notice = if (now) "★ ${d.channel.name} zu den Favoriten" else "${d.channel.name} aus den Favoriten entfernt"
                }
            },
            onToggleGroup = { group, name ->
                scope.launch {
                    val now = content.toggleInGroup(group, d.channel.key)
                    notice = if (now) "${d.channel.name} → 📁 $name" else "${d.channel.name} aus 📁 $name entfernt"
                }
            },
            onNewGroup = { dialog = LiveDialog.NameGroup(null, "", listOf(d.channel.key)) },
            onSetNumber = { dialog = LiveDialog.SetNumber(d.channel) },
            onMove = {
                dialog = null
                moving = channels
                movingKeys = setOf(d.channel.key)
            },
            onSelectMany = {
                dialog = null
                selecting = true
                picked = listOf(d.channel.key)
            },
            onDismiss = { dialog = null },
            onPlay = {
                dialog = null
                val favoritesOnly = selectedGroup == FAVORITES_GROUP
                PlayerActivity.startLive(context, d.channel.key, selectedGroup.takeUnless { favoritesOnly }, favoritesOnly)
            },
        )
        is LiveDialog.SelectionMenu -> SelectionMenuDialog(
            count = d.keys.size,
            customGroups = customGroups.map { customGroupKey(it.id) to it.name },
            canMove = sortable(selectedGroup),
            onMove = {
                dialog = null
                val keys = d.keys.toSet()
                moving = gatherBlock(channels, keys) { it.key }
                movingKeys = keys
                selecting = false
                picked = emptyList()
            },
            onFavorites = {
                dialog = null
                scope.launch {
                    val added = content.addFavorites(d.keys)
                    notice = "★ $added Sender zu den Favoriten"
                }
                selecting = false
                picked = emptyList()
            },
            onAddToGroup = { group, name ->
                dialog = null
                scope.launch {
                    val added = content.addToGroup(group, d.keys)
                    notice = "$added Sender → 📁 $name"
                }
                selecting = false
                picked = emptyList()
            },
            onNewGroup = { dialog = LiveDialog.NameGroup(null, "", d.keys) },
            onRemove = if (selectedGroup == FAVORITES_GROUP || selectedGroup?.let(::customGroupId) != null) ({
                dialog = null
                val group = selectedGroup
                scope.launch {
                    if (group == FAVORITES_GROUP) content.removeFavorites(d.keys) else group?.let { content.removeFromGroup(it, d.keys) }
                    notice = "${d.keys.size} Sender entfernt"
                }
                selecting = false
                picked = emptyList()
            }) else null,
            onCancel = {
                dialog = null
                selecting = false
                picked = emptyList()
            },
            onDismiss = { dialog = null },
        )
        is LiveDialog.GroupMenu -> GroupMenuDialog(
            name = d.name,
            onRename = { dialog = LiveDialog.NameGroup(d.group, d.name, emptyList()) },
            onMove = { delta -> scope.launch { content.moveGroup(d.group, delta) } },
            onDelete = {
                dialog = null
                scope.launch {
                    content.deleteGroup(d.group)
                    if (selectedGroup == d.group) selectedGroup = null
                    notice = "📁 ${d.name} gelöscht (die Sender bleiben in ihren Gruppen)"
                }
            },
            onDismiss = { dialog = null },
        )
        is LiveDialog.SetNumber -> ChannelNumberDialog(
            channel = d.channel,
            custom = container.channelNumbers.numberOf(d.channel.key) != null,
            onSave = { number ->
                dialog = null
                scope.launch {
                    val numbers = container.channelNumbers
                    val taken = number?.let(numbers::keyFor)?.takeIf { it != d.channel.key }
                    numbers.set(container.db, d.channel.key, d.channel.name, d.channel.number, number)
                    notice = when {
                        number == null -> "${d.channel.name}: wieder Nummer der Playlist"
                        taken != null -> "${d.channel.name} hat jetzt die $number (vorher vergeben, der andere Sender hat seine alte Nummer zurück)"
                        else -> "${d.channel.name} hat jetzt die Nummer $number – im Bild einfach $number tippen"
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        is LiveDialog.NameGroup -> NameGroupDialog(
            title = if (d.group == null) "Neue Gruppe" else "Gruppe umbenennen",
            initial = d.current,
            onSave = { name ->
                dialog = null
                if (name.isBlank()) return@NameGroupDialog
                scope.launch {
                    if (d.group == null) {
                        val created = content.createGroup(name, d.firstKeys)
                        notice = if (d.firstKeys.isNotEmpty()) "📁 ${name.trim()} angelegt mit ${d.firstKeys.size} Sender(n)" else
                            "📁 ${name.trim()} angelegt. Sender aufnehmen: OK auf einem Sender gedrückt halten"
                        if (d.firstKeys.isEmpty()) selectedGroup = created
                        selecting = false
                        picked = emptyList()
                    } else {
                        content.renameGroup(d.group, name)
                    }
                }
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Menü zu einem Sender (OK gedrückt halten): Favorit, eigene Gruppen, verschieben. */
@Composable
private fun ChannelMenuDialog(
    channel: ChannelEntity,
    favorite: Boolean,
    customGroups: List<Pair<String, String>>,
    canMove: Boolean,
    onFavorite: () -> Unit,
    onToggleGroup: (group: String, name: String) -> Unit,
    onNewGroup: () -> Unit,
    onSetNumber: () -> Unit,
    onMove: () -> Unit,
    onSelectMany: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit = {},
) {
    val content = LocalContainer.current.content
    // Wird nach jedem Umschalten neu gelesen, damit die Haken stimmen.
    var version by remember { mutableStateOf(0) }
    val memberOf by produceState(emptySet<String>(), channel.key, version) { value = content.groupsOf(channel.key) }
    MenuDialog(channel.name, onDismiss) { first ->
        MenuRow("▶ Abspielen", Modifier.focusRequester(first), onPlay)
        MenuRow(if (favorite) "★ Aus den Favoriten entfernen" else "☆ Zu den Favoriten", onClick = onFavorite)
        if (canMove) MenuRow("↕ Verschieben (Reihenfolge ändern)", onClick = onMove)
        MenuRow("☑ Mehrere auswählen (verschieben, in Gruppe …)", onClick = onSelectMany)
        customGroups.forEach { (group, name) ->
            MenuRow((if (group in memberOf) "✓ " else "    ") + "📁 $name") {
                onToggleGroup(group, name)
                version++
            }
        }
        MenuRow("＋ Neue Gruppe mit diesem Sender …", onClick = onNewGroup)
        MenuRow("# Sendernummer festlegen … (jetzt ${channel.number})", onClick = onSetNumber)
    }
}

/** Eigene Sendernummer eingeben (Zifferntasten der Fernbedienung oder Bildschirmtastatur). */
@Composable
private fun ChannelNumberDialog(channel: ChannelEntity, custom: Boolean, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(channel.number.takeIf { it > 0 }?.toString().orEmpty()) }
    fun save() = text.filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it > 0 }?.let(onSave)
    MenuDialog("Sendernummer für ${channel.name}", onDismiss) { first ->
        de.ayutv.ui.components.TvTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(4) },
            label = "Nummer (1–9999)",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            onImeAction = { save() },
            focusRequester = first,
            modifier = Modifier.fillMaxWidth(),
        )
        MenuRow("Speichern") { save() }
        if (custom) MenuRow("Eigene Nummer entfernen (Nummer der Playlist)") { onSave(null) }
        MenuRow("Abbrechen", onClick = onDismiss)
    }
}

/** Aktionen für mehrere ausgewählte Sender. */
@Composable
private fun SelectionMenuDialog(
    count: Int,
    customGroups: List<Pair<String, String>>,
    canMove: Boolean,
    onMove: () -> Unit,
    onFavorites: () -> Unit,
    onAddToGroup: (group: String, name: String) -> Unit,
    onNewGroup: () -> Unit,
    onRemove: (() -> Unit)?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    MenuDialog("$count Sender ausgewählt", onDismiss) { first ->
        if (canMove) MenuRow("↕ Gemeinsam verschieben", Modifier.focusRequester(first), onMove)
        MenuRow("★ Zu den Favoriten", if (canMove) Modifier else Modifier.focusRequester(first), onFavorites)
        customGroups.forEach { (group, name) -> MenuRow("📁 In „$name“ aufnehmen") { onAddToGroup(group, name) } }
        MenuRow("＋ Neue Gruppe mit diesen Sendern …", onClick = onNewGroup)
        onRemove?.let { MenuRow("✕ Aus dieser Liste entfernen", onClick = it) }
        MenuRow("Auswahl beenden", onClick = onCancel)
    }
}

/** Menü zu einer eigenen Gruppe (OK gedrückt halten). */
@Composable
private fun GroupMenuDialog(name: String, onRename: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    MenuDialog("📁 $name", onDismiss) { first ->
        MenuRow("Umbenennen …", Modifier.focusRequester(first), onRename)
        MenuRow("▲ In der Liste nach oben") { onMove(-1) }
        MenuRow("▼ In der Liste nach unten") { onMove(1) }
        MenuRow(if (confirm) "Wirklich löschen? (OK)" else "Gruppe löschen") { if (confirm) onDelete() else confirm = true }
    }
}

/** Name eingeben (neue Gruppe, umbenennen). */
@Composable
private fun NameGroupDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    MenuDialog(title, onDismiss) { first ->
        de.ayutv.ui.components.TvTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name der Gruppe",
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            onImeAction = { onSave(name) },
            focusRequester = first,
            modifier = Modifier.fillMaxWidth(),
        )
        MenuRow("Speichern") { onSave(name) }
        MenuRow("Abbrechen", onClick = onDismiss)
    }
}

/** Einfaches Fenster mit Titel und Zeilen; die erste Zeile bekommt den Fokus ([content] erhält ihren FocusRequester). */
@Composable
private fun MenuDialog(title: String, onDismiss: () -> Unit, content: @Composable (FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    // Geöffnet mit „OK gedrückt halten“: Die Taste ist noch unten. Ihr Loslassen (und das Wiederholen beim Halten) darf
    // im Menü nichts auswählen, sonst wäre sofort der erste Punkt gewählt. Erst nach dem Loslassen oder bei einem neuen
    // Druck reagiert das Menü auf OK.
    val opened = remember { android.os.SystemClock.uptimeMillis() }
    var armed by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        de.ayutv.ui.components.Surface(
            modifier = Modifier.width(520.dp).onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val ok = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                when {
                    armed || !ok -> false
                    event.type == KeyEventType.KeyUp -> {
                        armed = true
                        true
                    }
                    native.repeatCount == 0 && native.downTime > opened -> {
                        armed = true
                        false
                    }
                    else -> true
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = AyuColors.SurfaceRaised),
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                content(first)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    de.ayutv.ui.components.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelDetails(focusedState: State<ChannelEntity?>, modifier: Modifier) {
    val content = LocalContainer.current.content
    val channel = focusedState.value
    var programmes by remember { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    LaunchedEffect(channel) {
        val target = channel ?: return@LaunchedEffect
        delay(150) // schnelles Durchscrollen löst keine Abfragen aus
        programmes = content.upcoming(target, 6)
    }
    val preview = rememberLivePreview(channel)
    Column(modifier.background(AyuColors.Surface).padding(24.dp)) {
        if (channel == null) return@Column
        val player = preview.player
        if (player != null && preview.key == channel.key) {
            // Mini-Vorschau: der ausgewählte Sender stumm, wie ein kleines Bild. TextureView statt SurfaceView: Eine
            // SurfaceView stanzt ein Loch ins Fenster und ließ dabei die Senderliste verschwinden.
            androidx.compose.runtime.key(player) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        android.view.TextureView(context).apply { player.setVideoTextureView(this) }
                    },
                    onRelease = { view -> player.clearVideoTextureView(view) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black),
                )
            }
        } else {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 120.dp, height = 72.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary)
        if (channel.catchupDays > 0) {
            Text(
                "↺ Archiv: ${channel.catchupDays} ${if (channel.catchupDays == 1) "Tag" else "Tage"} – im TV-Guide zurückblättern",
                color = AyuColors.Accent,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (programmes.isEmpty()) {
            Text("Keine Programminformationen", color = AyuColors.TextSecondary, fontSize = 14.sp)
        }
        programmes.forEachIndexed { index, p ->
            Text(
                "${formatTime(p.start)} – ${formatTime(p.stop)}",
                color = if (index == 0) AyuColors.Accent else AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            Text(
                p.headlineWithBadge(),
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (index == 0) {
                Spacer(Modifier.height(4.dp))
                ProgressLine(p.progress())
                // Spiel: Wappen beider Vereine (nur für den gewählten Sender, kostet beim Blättern nichts).
                MatchCrests(p)
                if (!p.icon.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ProgrammeImage(p.icon, Modifier.width(242.dp).height(136.dp))
                }
                de.ayutv.data.parse.ProgrammeText.description(p.description)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("OK abspielen  ·  OK halten = Favorit, Gruppen, Verschieben, Mehrfachauswahl", fontSize = 13.sp, color = AyuColors.TextSecondary)
        Text("OK halten auf Gruppe = ausblenden bzw. bearbeiten", fontSize = 13.sp, color = AyuColors.TextSecondary)
    }
}

/** Laufende Mini-Vorschau: Player und Sender, für den er gerade läuft. */
private class LivePreview {
    var player by mutableStateOf<de.ayutv.player.dna.engine.DnaEnginePlayer?>(null)
    var key by mutableStateOf<String?>(null)
    @Volatile var agent: String? = null

    /** Sofort beenden und Verbindung schließen (vor dem Abspielen: viele Konten erlauben nur eine Verbindung). */
    fun stop() {
        player?.release()
        player = null
        key = null
    }
}

/**
 * Mini-Vorschau in der Senderliste: Bleibt die Auswahl 1,5 s auf einem Sender, läuft er stumm (höchstens 720p, kleiner
 * Puffer). Sobald die Liste verlassen oder ein Sender gestartet wird (Activity pausiert), ist sie sofort weg.
 */
@Composable
private fun rememberLivePreview(channel: ChannelEntity?): LivePreview {
    val container = LocalContainer.current
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val preview = remember { LivePreview() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Direkt im Ereignis beenden, nicht erst beim nächsten Bild: Der Player startet gleich danach.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) preview.stop()
            resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.stop()
        }
    }
    val enabled = settings?.livePreview == true
    LaunchedEffect(channel?.key, resumed, enabled) {
        if (channel == null || !resumed || !enabled) {
            preview.stop()
            return@LaunchedEffect
        }
        if (preview.key == channel.key) return@LaunchedEffect
        delay(1_500)
        preview.agent = channel.userAgent ?: settings?.userAgent ?: de.ayutv.data.AppSettings().userAgent
        val player = preview.player ?: de.ayutv.player.dna.engine.DnaEnginePlayer.preview(container.http) { preview.agent }.also { created ->
            created.addListener(object : androidx.media3.common.Player.Listener {
                // Läuft der Sender nicht: Vorschau einfach weglassen, das Logo bleibt.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { preview.key = null }
            })
            preview.player = created
        }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(channel.url))
        player.prepare()
        player.play()
        preview.key = channel.key
    }
    return preview
}

/** Wappen der beiden Vereine, wenn die laufende Sendung ein Spiel ist (Paarung aus TV-Guide oder Spielplan). */
@Composable
private fun MatchCrests(p: ProgrammeEntity) {
    val pairing = remember(p.id) {
        de.ayutv.data.parse.ProgrammeText.pairing(p.title, p.subtitle, p.description)
            ?: de.ayutv.data.parse.ProgrammeText.scheduleHint?.invoke(p.title, p.subtitle, p.description, p.start, p.stop)
    } ?: return
    val sides = remember(pairing) { de.ayutv.data.parse.Teams.ofPairing(pairing) }
    if (sides.size != 2) return
    val crests = LocalContainer.current.crests
    androidx.compose.foundation.layout.Row(
        Modifier.padding(top = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        sides.forEachIndexed { i, team ->
            val url by androidx.compose.runtime.produceState(crests.cached(team), team) {
                if (value == null) value = runCatching { crests.of(team) }.getOrNull()
            }
            androidx.compose.foundation.layout.Box(Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                url?.let { coil3.compose.AsyncImage(model = it, contentDescription = team, modifier = Modifier.size(40.dp)) }
            }
            if (i == 0) Text("  –  ", fontSize = 18.sp, color = AyuColors.TextSecondary)
        }
    }
}


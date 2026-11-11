package de.ayutv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ayutv.ui.components.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.GroupToggle
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch
import de.ayutv.data.parental.ParentalControl
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester

private val GroupKind.label
    get() = when (this) {
        GroupKind.LIVE -> "Live TV"
        GroupKind.VOD -> "Filme"
        GroupKind.SERIES -> "Serien"
    }

/**
 * Gruppen einer Playlist ein- und ausblenden. Ausgeblendete Gruppen verschwinden überall
 * (Listen, TV-Guide, Suche, Startseite, Zapping) und bleiben auch nach dem Aktualisieren ausgeblendet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GroupManagerScreen(playlist: PlaylistEntity, onBack: () -> Unit) {
    val container = LocalContainer.current
    val content = container.content
    val lockedGroups by container.parental.lockedGroups.collectAsStateWithLifecycle(emptySet())
    val hasPin by container.parental.hasPin.collectAsStateWithLifecycle(false)
    val unlocked by container.parental.unlocked.collectAsStateWithLifecycle()
    var lockHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    val live by remember(playlist.id) { content.groupToggles(playlist.id, GroupKind.LIVE) }.collectAsStateWithLifecycle(emptyList())
    val vod by remember(playlist.id) { content.groupToggles(playlist.id, GroupKind.VOD) }.collectAsStateWithLifecycle(emptyList())
    val series by remember(playlist.id) { content.groupToggles(playlist.id, GroupKind.SERIES) }.collectAsStateWithLifecycle(emptyList())

    var kind by remember { mutableStateOf(GroupKind.LIVE) }
    val firstButton = remember { FocusRequester() }
    // Beim Öffnen gleich auf den ersten Bereich, sobald die Gruppen geladen sind.
    val anyLoaded = live.isNotEmpty() || vod.isNotEmpty() || series.isNotEmpty()
    LaunchedEffect(anyLoaded) {
        if (anyLoaded) repeat(5) {
            if (runCatching { firstButton.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    var filter by remember { mutableStateOf("") }

    val all = when (kind) {
        GroupKind.LIVE -> live
        GroupKind.VOD -> vod
        GroupKind.SERIES -> series
    }
    val shown = remember(all, filter) {
        val f = filter.trim()
        if (f.isEmpty()) all else all.filter { it.name.contains(f, ignoreCase = true) }
    }

    fun setHidden(groups: List<GroupToggle>, hidden: Boolean) {
        val names = groups.filter { it.hidden != hidden }.map { it.name }
        if (names.isNotEmpty()) scope.launch { content.setGroupsHidden(playlist.id, kind, names, hidden) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("Gruppen · ${playlist.name}", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ausgeblendete Gruppen verschwinden überall in der App und bleiben auch nach dem Aktualisieren ausgeblendet.",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupKind.entries.forEach { k ->
                val list = when (k) {
                    GroupKind.LIVE -> live
                    GroupKind.VOD -> vod
                    GroupKind.SERIES -> series
                }
                if (list.isNotEmpty()) {
                    val label = "${k.label} (${list.count { !it.hidden }}/${list.size})"
                    // Immer derselbe Knopf-Typ (nur die Farbe wechselt): sonst würde der fokussierte Knopf ersetzt
                    // und der Fokus ginge beim Umschalten verloren.
                    OutlinedButton(
                        onClick = { kind = k },
                        modifier = if (k == GroupKind.entries.first { kk -> kk.hasGroups(live, vod, series) }) Modifier.focusRequester(firstButton) else Modifier,
                        colors = if (k == kind) {
                            OutlinedButtonDefaults.colors(containerColor = AyuColors.AccentSoft, contentColor = Color.White)
                        } else {
                            OutlinedButtonDefaults.colors()
                        },
                    ) { Text(label) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                value = filter,
                onValueChange = { filter = it },
                label = "Filtern, z. B. „DE“ oder „Sport“",
                imeAction = ImeAction.Done,
                modifier = Modifier.width(360.dp),
            )
            OutlinedButton(onClick = { setHidden(shown, hidden = false) }) {
                Text(if (filter.isBlank()) "Alle einblenden" else "Treffer einblenden")
            }
            OutlinedButton(onClick = { setHidden(shown, hidden = true) }) {
                Text(if (filter.isBlank()) "Alle ausblenden" else "Treffer ausblenden")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            lockHint ?: "OK = ein-/ausblenden  ·  OK halten = mit Kindersicherung sperren (🔒)",
            color = if (lockHint != null) AyuColors.Accent else AyuColors.TextSecondary,
            fontSize = 13.sp,
        )
        Text(
            "${shown.count { !it.hidden }} von ${shown.size} sichtbar",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (all.isEmpty()) {
            Text("Keine Gruppen. Bitte die Playlist zuerst aktualisieren.", color = AyuColors.TextSecondary)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.name }) { group ->
                val lockKey = ParentalControl.LockedGroup(playlist.id, kind, group.name)
                val locked = lockKey in lockedGroups
                GroupRow(
                    group,
                    locked = locked,
                    onLongToggleLock = {
                        when {
                            !hasPin -> lockHint = "Zuerst unter Einstellungen → Kindersicherung eine PIN festlegen"
                            !unlocked -> lockHint = "Zum Ändern der Sperren zuerst mit der PIN entsperren (Einstellungen → Kindersicherung)"
                            else -> {
                                lockHint = null
                                scope.launch { container.parental.setLocked(lockKey, !locked) }
                            }
                        }
                    },
                ) { setHidden(listOf(group), hidden = !group.hidden) }
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupToggle, locked: Boolean, onLongToggleLock: () -> Unit, onToggle: () -> Unit) {
    val visible = !group.hidden
    Surface(
        onClick = onToggle,
        onLongClick = onLongToggleLock,
        modifier = Modifier.fillMaxWidth(0.75f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(if (visible) AyuColors.Accent else Color.Transparent, RoundedCornerShape(5.dp))
                    .border(2.dp, if (visible) AyuColors.Accent else AyuColors.TextSecondary, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (visible) Text("✓", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                group.name,
                modifier = Modifier.weight(1f),
                color = if (visible) AyuColors.TextPrimary else AyuColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (locked) Text("🔒  ", fontSize = 14.sp)
            Text("${group.count}", color = AyuColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

private fun GroupKind.hasGroups(live: List<GroupToggle>, vod: List<GroupToggle>, series: List<GroupToggle>) = when (this) {
    GroupKind.LIVE -> live.isNotEmpty()
    GroupKind.VOD -> vod.isNotEmpty()
    GroupKind.SERIES -> series.isNotEmpty()
}

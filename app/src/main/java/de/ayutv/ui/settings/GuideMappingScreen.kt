package de.ayutv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.parse.EpgMatcher
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lücken im TV-Guide auf einen Blick: links die Sender ohne Programm, rechts die Sender der TV-Guides
 * (nach Ähnlichkeit sortiert). Gewählt gilt dauerhaft wie „Programm zuordnen“ im Player; beim
 * Schließen wird der TV-Guide neu geladen.
 */
@Composable
fun GuideMappingScreen(playlist: PlaylistEntity, onClose: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val assigned = remember { mutableStateMapOf<String, String>() }
    fun close() {
        if (assigned.isNotEmpty()) container.scope.launch { container.playlists.syncEpg(playlist.id) }
        onClose()
    }
    BackHandler(onBack = ::close)

    val data by produceState<Pair<List<PlaylistRepository.MissingChannel>, List<PlaylistRepository.GuideChannel>>?>(null, playlist.id) {
        value = withContext(Dispatchers.IO) {
            container.playlists.epgStatus(playlist.id)?.missingChannels.orEmpty() to container.playlists.guideChannels(playlist.id)
        }
    }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PlaylistRepository.MissingChannel?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sender ohne TV-Guide · ${playlist.name}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    info ?: "Links einen Sender wählen, rechts den Sender, dessen Programm stimmt. Gilt dauerhaft; " +
                        "beim Schließen wird der TV-Guide neu geladen.",
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
            }
            OutlinedButton(onClick = ::close) { Text("Fertig") }
        }
        val (missing, guides) = data ?: run {
            Text("Wird geladen …", color = AyuColors.TextSecondary, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        if (missing.isEmpty()) {
            Text("Alle Sender haben ein Programm.", color = AyuColors.TextSecondary, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TvTextField(filter, { filter = it }, "Sender suchen", modifier = Modifier.fillMaxWidth())
                val shown = remember(missing, filter) {
                    val f = filter.trim().lowercase()
                    missing.filter { f.isEmpty() || f in it.name.lowercase() || f in it.group.lowercase() }.take(2_000)
                }
                Text("${missing.size} Sender ohne Programm", color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { "${it.rowId}-${it.group}-${it.name}" }) { c ->
                        val done = assigned[c.name]
                        MappingRow(
                            title = c.name,
                            subtitle = (if (done != null) "✓ $done · " else "") + c.group,
                            selected = selected == c,
                        ) {
                            selected = c
                            selectedKey = null
                            scope.launch {
                                selectedKey = container.db.channels().find(playlist.id, c.rowId ?: -1, c.name)?.key
                                if (selectedKey == null) info = "„${c.name}“ gibt es in der Playlist nicht mehr. Bitte die Playlist aktualisieren."
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val target = selected
                if (target == null) {
                    Text("Links einen Sender wählen.", color = AyuColors.TextSecondary)
                    return@Column
                }
                if (guides.isEmpty()) {
                    Text("Noch kein TV-Guide geladen.", color = AyuColors.TextSecondary)
                    return@Column
                }
                var guideFilter by remember(target) { mutableStateOf("") }
                val ranked by produceState<List<PlaylistRepository.GuideChannel>?>(null, target) {
                    value = withContext(Dispatchers.Default) {
                        EpgMatcher.rank(target.name, guides, { it.id }, { it.name })
                    }
                }
                TvTextField(guideFilter, { guideFilter = it }, "Programm für „${target.name}“ suchen", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val list = ranked
                if (list == null) {
                    Text("Wird sortiert …", color = AyuColors.TextSecondary)
                    return@Column
                }
                val choices = remember(list, guideFilter) {
                    val f = guideFilter.trim().lowercase()
                    list.asSequence().filter { f.isEmpty() || f in it.name.lowercase() || f in it.id.lowercase() }.take(300).toList()
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(choices, key = { it.id }) { g ->
                        MappingRow(title = g.name, subtitle = g.source, selected = assigned[target.name] == g.name) {
                            val key = selectedKey ?: return@MappingRow
                            container.epgOverrides.set(key, g.id)
                            assigned[target.name] = g.name
                            info = "„${target.name}“ bekommt das Programm von „${g.name}“"
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
            focusedContainerColor = AyuColors.Accent,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(title, fontSize = 15.sp, maxLines = 1)
            if (subtitle.isNotEmpty()) Text(subtitle, color = AyuColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

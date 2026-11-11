package de.ayutv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.ui.components.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import de.ayutv.data.repo.ContentRepository.CountryChoice
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Länder ein- und ausblenden: Viele Anbieter liefern Tausende Sender aus aller Sender. Wer nur
 * deutschsprachig schaut, blendet den Rest hier mit einem Klick pro Land aus (Live, Filme, Serien).
 * Erscheint einmal nach dem ersten Laden, danach unter Einstellungen → Playlists.
 */
@Composable
fun CountryScreen(onDone: () -> Unit) {
    val content = LocalContainer.current.content
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<CountryChoice>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) { choices = content.countryChoices() }
    LaunchedEffect(choices != null) {
        if (choices == null) return@LaunchedEffect
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }

    fun apply(changes: List<Pair<String, Boolean>>) {
        if (busy || changes.isEmpty()) return
        busy = true
        scope.launch {
            changes.forEach { (code, visible) -> content.setCountryVisible(code, visible) }
            choices = content.countryChoices()
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text("Welche Länder möchtest du sehen?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ausgeblendete Länder verschwinden aus Live TV, TV-Guide, Filmen, Serien und Suche. Einzelne Gruppen lassen sich " +
                "weiter unter Einstellungen → Playlists → Gruppen ändern.",
            color = AyuColors.TextSecondary, fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        val list = choices
        if (list == null) {
            Text("Gruppen werden gelesen …", color = AyuColors.TextSecondary)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDone, modifier = Modifier.focusRequester(first)) { Text("Fertig") }
            // Gruppen ohne Länderkennung („Sport“, „Kinder“) bleiben dabei sichtbar.
            OutlinedButton(onClick = {
                apply(list.mapNotNull { c ->
                    val want = c.code.isEmpty() || c.code in GERMAN_SPEAKING
                    if (want != c.visible) c.code to want else null
                })
            }) { Text("Nur Deutschland, Österreich, Schweiz") }
            OutlinedButton(onClick = { apply(list.filter { !it.visible }.map { it.code to true }) }) { Text("Alle zeigen") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.code }) { c ->
                Surface(
                    onClick = { apply(listOf(c.code to !c.visible)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
                    border = AyuFocus.border,
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (c.visible) "● Sichtbar" else "○ Ausgeblendet",
                            color = if (c.visible) AyuColors.Accent else AyuColors.TextSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier.width(160.dp),
                        )
                        Text(c.label + if (c.code.isNotEmpty()) " (${c.code})" else "", fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("${c.groups} Gruppen · ${c.items} Einträge", color = AyuColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private val GERMAN_SPEAKING = setOf("DE", "AT", "CH")

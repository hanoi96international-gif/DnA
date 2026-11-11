package de.ayutv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.profile.Profile
import de.ayutv.data.profile.Profiles
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.Button
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kleiner Knopf oben auf der Startseite: aktives Profil, öffnet die Auswahl. */
@Composable
fun ProfileChip(onClick: () -> Unit) {
    val profile by LocalContainer.current.profiles.active.collectAsStateWithLifecycle()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
    ) {
        Text("${profile.emoji} ${profile.name}", modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp)
    }
}

/** Wer schaut? Profil wählen, anlegen oder (lange OK) löschen. */
@Composable
fun ProfilePicker(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = LocalContainer.current
    val profiles = container.profiles
    val all by profiles.all.collectAsStateWithLifecycle()
    val active by profiles.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun choose(p: Profile) {
        scope.launch {
            if (profiles.needsPin()) {
                pinFor = p
                return@launch
            }
            if (profiles.switchTo(p)) onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Wer schaut?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(all, key = { it.id }) { p ->
                    Surface(
                        onClick = { choose(p) },
                        onLongClick = {
                            info = if (profiles.remove(p)) "„${p.name}“ gelöscht" else "Das aktive Profil lässt sich nicht löschen"
                        },
                        modifier = if (p.id == active.id) Modifier.focusRequester(first) else Modifier,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (p.id == active.id) AyuColors.AccentSoft else AyuColors.Surface,
                            focusedContainerColor = AyuColors.Accent,
                        ),
                    ) {
                        Column(Modifier.size(width = 130.dp, height = 140.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(p.emoji, fontSize = 44.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(p.name, maxLines = 1)
                            if (p.kids) Text("Kinder", fontSize = 12.sp, color = AyuColors.TextSecondary)
                        }
                    }
                }
                item {
                    Surface(
                        onClick = { adding = true },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.Accent),
                    ) {
                        Column(Modifier.size(width = 130.dp, height = 140.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("+", fontSize = 44.sp)
                            Text("Neues Profil")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Jedes Profil hat eigene Favoriten und „Weiter schauen“. Kinderprofile halten die Kindersicherung gesperrt " +
                    "(PIN unter Einstellungen → Kindersicherung). Lange OK löscht ein Profil.",
                color = AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            info?.let { Text(it, color = AyuColors.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }

            pinFor?.let { target ->
                var pin by remember { mutableStateOf("") }
                Spacer(Modifier.height(16.dp))
                Text("PIN der Kindersicherung, um „${target.name}“ zu öffnen")
                TvTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN", Modifier.fillMaxWidth(), password = true, keyboardType = KeyboardType.NumberPassword)
                Button(onClick = {
                    scope.launch {
                        if (profiles.switchTo(target, pin)) onClose() else info = "Falsche PIN"
                    }
                }) { Text("Wechseln") }
            }

            if (adding) {
                var name by remember { mutableStateOf("") }
                var emoji by remember { mutableStateOf(Profiles.EMOJIS.first()) }
                var kids by remember { mutableStateOf(false) }
                Spacer(Modifier.height(16.dp))
                TvTextField(name, { name = it }, "Name", Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(Profiles.EMOJIS) { e ->
                        Surface(
                            onClick = { emoji = e },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (e == emoji) AyuColors.AccentSoft else AyuColors.Surface,
                                focusedContainerColor = AyuColors.Accent,
                            ),
                        ) { Text(e, fontSize = 26.sp, modifier = Modifier.padding(8.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { kids = !kids }) { Text(if (kids) "✓ Kinderprofil" else "Kinderprofil") }
                    Button(onClick = {
                        profiles.add(name, emoji, kids)
                        adding = false
                    }) { Text("Anlegen") }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

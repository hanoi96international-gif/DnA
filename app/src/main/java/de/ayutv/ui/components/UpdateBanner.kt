package de.ayutv.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import de.ayutv.data.update.Updater
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

/** Hinweis auf eine neue Version mit Knopf zum Installieren (Startseite). Unsichtbar, solange alles aktuell ist. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.updates.state.collectAsStateWithLifecycle()
    val text = updateText(state) ?: return
    val release = when (val s = state) {
        is Updater.State.Available -> s.release
        is Updater.State.Ready -> s.release
        else -> null
    }
    Surface(
        onClick = {
            if (release != null) scope.launch { container.updates.downloadAndInstall(release) { context.startActivity(it) } }
        },
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.AccentSoft, focusedContainerColor = AyuColors.Accent),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 15.sp)
    }
}

/** Kurzer Text zum Update-Stand, oder null, wenn nichts zu zeigen ist. */
fun updateText(state: Updater.State): String? = when (state) {
    is Updater.State.Available -> "⬆ Update auf ${state.release.version} verfügbar – OK zum Installieren"
    is Updater.State.Downloading -> "Update ${state.release.version} wird geladen … ${state.percent} %"
    is Updater.State.Ready -> "Update ${state.release.version} geladen – OK zum Installieren"
    is Updater.State.Failed -> "Update: ${state.message}"
    else -> null
}

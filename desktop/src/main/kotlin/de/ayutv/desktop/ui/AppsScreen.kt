package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.data.parse.StreamingApps

/**
 * Reiter „Apps“ unter Windows: Streaming-App, Streaming-App, Streaming-App und Streaming-App im Standard-Browser öffnen. Schließt man den
 * Browser (oder den Tab), ist DnA-TV wieder vorn. ☰ bzw. Rechtsklick: Reihenfolge, ausblenden.
 */
@Composable
fun AppsScreen(app: AppState) {
    val user by app.store.user.collectAsState()
    val apps = remember(user.appsOrder, user.appsHidden) { StreamingApps.arrange(user.appsOrder, user.appsHidden) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val first = remember { FocusRequester() }

    fun open(a: StreamingApps.App) {
        val ok = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(a.web)) }.isSuccess
        app.toast(if (ok) "${a.name} im Browser geöffnet – Browser schließen, um zurückzukommen" else "Browser ließ sich nicht öffnen")
    }

    fun menu(a: StreamingApps.App) {
        dialog = {
            MenuDialog(app, a.name, listOf(
                MenuItem("▶ Öffnen") { open(a) },
                MenuItem("◀ Nach vorn") { app.store.updateUser { u -> u.copy(appsOrder = StreamingApps.move(u.appsOrder, a.id, -1)) } },
                MenuItem("▶ Nach hinten") { app.store.updateUser { u -> u.copy(appsOrder = StreamingApps.move(u.appsOrder, a.id, 1)) } },
                MenuItem("Ausblenden") { app.store.updateUser { u -> u.copy(appsHidden = u.appsHidden + a.id) } },
            ), onDismiss = { dialog = null })
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Apps", "OK öffnet im Browser · ☰ Menü")
        Label("Streaming- und Musik-Apps öffnen sich im Standard-Browser. Browser schließen = zurück zu DnA-TV.", Type.small, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        LazyRow {
            itemsIndexed(apps, key = { _, a -> a.id }) { index, a ->
                Selectable({ open(a) }, Modifier.width(240.dp), onMenu = { menu(a) }, focusRequester = if (index == 0) first else null, shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.size(width = 224.dp, height = 126.dp).background(Color(a.color), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Label(a.name, TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        if (user.appsHidden.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TextButton("Ausgeblendete zeigen (${user.appsHidden.size})", { app.store.updateUser { it.copy(appsHidden = emptySet()) } })
        }
    }
    if (apps.isNotEmpty()) FocusOnShow(first)
    dialog?.invoke()
}

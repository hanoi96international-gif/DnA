package de.ayutv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import de.ayutv.data.sport.LiveScores
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay

/** Letzte Bedienung (Fernbedienung) und ob der Ruhebildschirm gerade zu sehen ist. */
class Idle {
    var lastInput by mutableLongStateOf(System.currentTimeMillis())
        private set
    var showing by mutableStateOf(false)
    /** Die Taste, die den Ruhebildschirm beendet hat, soll auch beim Loslassen nichts auslösen. */
    var swallowUntilUp = false

    /** Taste gedrückt: merken. Liefert true, wenn damit der Ruhebildschirm endet (Taste nicht weitergeben). */
    fun touch(): Boolean {
        lastInput = System.currentTimeMillis()
        if (!showing) return false
        showing = false
        return true
    }
}

/** Nach so langer Zeit ohne Taste erscheint der Ruhebildschirm. */
private const val IDLE_MS = 3 * 60_000L

/**
 * Ruhebildschirm im Menü: große Uhr mit Datum, die laufenden Spiele mit Stand und dahinter langsam wechselnd Poster
 * aus „Neue Filme“. Uhr und Spiele wandern jede Minute ein Stück (kein Einbrennen). Nur im Menü – der Player hält
 * den Bildschirm selbst an. Eine Taste beendet ihn.
 */
@Composable
fun AmbientScreen(idle: Idle) {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val enabled = settings?.ambient == true
    LaunchedEffect(enabled, idle.lastInput) {
        if (!enabled) { idle.showing = false; return@LaunchedEffect }
        delay(IDLE_MS)
        idle.showing = true
    }
    if (!idle.showing) return

    val movies by container.content.recentMovies.collectAsStateWithLifecycle(emptyList())
    val posters = remember(movies) { movies.mapNotNull { it.poster?.takeIf(String::isNotBlank) }.take(12) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var matches by remember { mutableStateOf<List<LiveScores.Match>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            if (settings?.liveScores != false) {
                matches = runCatching { container.liveScores.current(now) }.getOrDefault(emptyList())
                    .filter { it.live(now) }.sortedBy { it.kickoff }.take(6)
            }
            delay(30_000)
        }
    }
    val minute = now / 60_000
    // Langsames Wandern über den Bildschirm (8 Stellen, je eine Minute).
    val spots = listOf(0 to 0, 60 to 30, 120 to 60, 60 to 90, 0 to 60, -60 to 30, -120 to 0, -60 to -30)
    val (dx, dy) = spots[(minute % spots.size).toInt()]
    val poster = posters.takeIf { it.isNotEmpty() }?.let { it[((now / 20_000) % it.size).toInt()] }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        poster?.let { url ->
            val context = coil3.compose.LocalPlatformContext.current
            val request = remember(url) { coil3.request.ImageRequest.Builder(context).data(url).size(640, 960).build() }
            coil3.compose.AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.align(Alignment.CenterEnd).width(520.dp).fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0.35f to Color.Black, 1f to Color.Transparent),
                ),
            )
        }
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 120.dp).offset(dx.dp, dy.dp),
        ) {
            Text(formatTime(now), color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light)
            Text(
                java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMAN).format(java.util.Date(now)),
                color = Color.White.copy(alpha = 0.7f), fontSize = 26.sp,
            )
            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                Text("● LIVE", color = AyuColors.Live, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    matches.forEach { m -> AmbientMatch(m) }
                }
            }
        }
        Text(
            "Beliebige Taste: zurück",
            color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 120.dp, bottom = 40.dp),
        )
    }
}

@Composable
private fun AmbientMatch(m: LiveScores.Match) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crest(m.icon1)
        Text(m.short1.ifEmpty { m.team1 }, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(m.score, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(m.short2.ifEmpty { m.team2 }, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Crest(m.icon2)
        m.statusText?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp)) }
    }
}

@Composable
private fun Crest(url: String?) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        url?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(30.dp)) }
    }
}

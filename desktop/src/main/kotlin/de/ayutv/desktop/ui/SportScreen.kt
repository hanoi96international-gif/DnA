package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ayutv.data.sport.LiveScores
import de.ayutv.desktop.data.SportEvent
import de.ayutv.desktop.data.SportEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Sport aus dem TV-Guide: was jetzt läuft (mit Spielstand), heute und morgen. Klick startet den besten Sender. */
@Composable
fun SportScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var matches by remember { mutableStateOf<List<LiveScores.Match>>(emptyList()) }
    val scores = app.liveScores

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            runCatching { matches = scores.current(now).also { de.ayutv.data.sport.MatchHints.update(it) } }
            delay(60_000)
        }
    }
    var events by remember { mutableStateOf<List<SportEvent>?>(null) }
    LaunchedEffect(content, guide, user.favorites, now / (5 * 60_000)) {
        events = withContext(Dispatchers.Default) { SportEvents.of(store.listedChannels(), guide, user.favorites.toSet(), now = now) }
    }

    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // ☰ / Rechtsklick auf ein Spiel: erinnern, Verein folgen (wie auf dem Fire TV: OK halten = Verein folgen).
    fun eventMenu(e: SportEvent) {
        val items = buildList {
            if (e.programme.start > System.currentTimeMillis()) {
                val set = store.hasReminder(e.channel, e.programme.start)
                add(MenuItem(if (set) "⏰ Erinnerung entfernen" else "⏰ Erinnern") {
                    app.toast(if (store.toggleReminder(e.channel, e.programme)) "Erinnerung um ${clock(e.programme.start)}" else "Erinnerung entfernt")
                })
            }
            e.pairing?.let { de.ayutv.data.parse.Teams.ofPairing(it) }.orEmpty().forEach { team ->
                val follows = team in user.followedTeams
                add(MenuItem(if (follows) "Nicht mehr folgen: $team" else "Verein folgen: $team") {
                    app.toast(if (store.toggleTeam(team)) "Du folgst $team: vor jedem Spiel kommt eine Erinnerung" else "$team entfolgt")
                })
            }
            e.alsoOn.forEach { c -> add(MenuItem("▶ Auf ${c.name}") { app.play(Playback.Live(c, listOf(e.channel) + e.alsoOn)) }) }
        }
        if (items.isNotEmpty()) dialog = { MenuDialog(app, e.headline, items, onDismiss = { dialog = null }) }
    }

    // Spiel zu jeder Sendung (Paarung, Stadion, sonst Ausschluss), einmal für die ganze Liste.
    val assigned = androidx.compose.runtime.remember(events, matches) { assign(scores, matches, events.orEmpty()) }
    // Wiederholungen schon gespielter Spiele (laut Spielplan) entfallen, auch ohne Kennzeichnung im TV-Guide.
    val list = androidx.compose.runtime.remember(events, assigned) {
        events?.filterNot { assigned[it] == null && scores.isReplay(matches, it.pairing, it.programme.start) }
    }
    when {
        guide.isEmpty() -> Empty("Noch kein TV-Guide. Unter „Einstellungen“ den TV-Guide laden.")
        list == null -> Empty("Sport wird gesucht …")
        list.isEmpty() -> Empty("Im TV-Guide steht in den nächsten 36 Stunden kein Live-Sport.")
        else -> {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val running = list.filter { it.programme.start <= now }
            val later = list.filter { it.programme.start > now }
            val (todayList, tomorrow) = later.partition { Instant.ofEpochMilli(it.programme.start).atZone(zone).toLocalDate() == today }
            Column(Modifier.fillMaxSize()) {
                ScreenHeader("Sport", "OK einschalten · ☰ / Rechtsklick: erinnern, Verein folgen, anderer Sender")
                LazyColumn(Modifier.fillMaxSize()) {
                    section("Jetzt live", running, app, assigned, now, ::eventMenu)
                    section("Heute", todayList, app, assigned, now, ::eventMenu)
                    section("Morgen", tomorrow, app, assigned, now, ::eventMenu)
                }
            }
        }
    }
    dialog?.invoke()
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, events: List<SportEvent>, app: AppState, assigned: Map<SportEvent, LiveScores.Match?>, now: Long, onMenu: (SportEvent) -> Unit) {
    if (events.isEmpty()) return
    item(key = "h$title") {
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(4.dp).height(18.dp).background(if (title == "Jetzt live") Palette.live else Palette.accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Label(title, Type.heading)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Label("${events.size}", Type.small)
        }
    }
    items(events, key = { "$title|${it.channel.key}|${it.programme.start}" }) { e ->
        MatchRow(app, e, assigned[e], now, onClick = {
            val best = de.ayutv.desktop.data.SportEvents.bestFor(e.channel, app.store.channels())
            app.play(Playback.Live(best, listOf(best) + e.alsoOn))
        }, onMenu = { onMenu(e) })
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
    }
}

/** Wettbewerb für die Anzeigetafel: Liga laut Spielplan, sonst der Titel ohne „Live:“ und Sportart davor. */
private fun competitionOf(p: de.ayutv.desktop.data.Programme, match: LiveScores.Match?): String = when (match?.league) {
    "bl1" -> "Bundesliga"
    "bl2" -> "2. Bundesliga"
    "bl3" -> "3. Liga"
    else -> match?.league?.let { de.ayutv.data.sport.IntlScores.NAMES[it] } ?: de.ayutv.data.parse.ProgrammeText.competition(p.title).substringBefore(" – ").substringBefore(" - ").trim().ifEmpty { p.category?.takeIf { it.isNotBlank() } ?: "Fußball" }
}

/**
 * Ein Spiel als Anzeigetafel: links Zeit bzw. LIVE, in der Mitte Verein – Wappen – Stand/Anstoß – Wappen – Verein,
 * darüber Wettbewerb und Sender, rechts das Senderlogo. Ohne Paarung (Tennis, Formel 1 …) die Überschrift.
 */
@androidx.compose.runtime.Composable
private fun MatchRow(app: AppState, e: SportEvent, match: LiveScores.Match?, now: Long, onClick: () -> Unit, onMenu: () -> Unit) {
    val p = e.programme
    val running = p.start <= now
    val pairing = e.pairing ?: match?.pairing
    val sides = if (match != null) listOf(match.team1, match.team2)
        else pairing?.let { de.ayutv.data.parse.Teams.ofPairing(it) }?.takeIf { it.size == 2 }?.map { de.ayutv.data.parse.ProgrammeText.niceCase(it) }.orEmpty()
    val liveScore = match?.takeIf { it.live(now) }
    Selectable(onClick, Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), onMenu = onMenu, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.width(96.dp)) {
            if (running) {
                Label(
                    "● LIVE", Type.small.copy(color = androidx.compose.ui.graphics.Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    Modifier.background(Palette.live, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                liveScore?.minute(now)?.let { Label(it, Type.small.copy(color = Palette.live)) }
            } else {
                Label(clock(p.start), Type.heading)
            }
            Label("bis ${clock(p.stop)}", Type.small)
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            val also = if (e.alsoOn.isEmpty()) "" else "  ·  auch auf ${e.alsoOn.size} weiteren"
            if (sides.size == 2) {
                Label((competitionOf(p, match) + "  ·  " + de.ayutv.data.parse.ProgrammeText.channelLabel(e.channel.name) + also).uppercase(), Type.small.copy(color = Palette.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 2)
                androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Label(sides[0], Type.heading.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), Modifier.weight(1f), maxLines = 2)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    Crest(app, match?.icon1, sides[0])
                    androidx.compose.foundation.layout.Box(Modifier.width(96.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        val shown = match?.takeIf { it.live(now) || it.finished }
                        Label(
                            shown?.score ?: clock(match?.kickoff ?: p.start),
                            Type.title.copy(
                                color = if (liveScore != null) Palette.live else Palette.text,
                                fontSize = if (shown != null) androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp) else androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                    }
                    Crest(app, match?.icon2, sides[1])
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    Label(sides[1], Type.heading, Modifier.weight(1f), maxLines = 2)
                }
                match?.takeIf { it.goalCount > 0 && it.live(now) }?.lastGoal?.let { Label("⚽ zuletzt: $it", Type.small) }
            } else {
                Label(de.ayutv.data.parse.ProgrammeText.channelLabel(e.channel.name) + also, Type.small.copy(color = Palette.accent))
                Label(e.headline, Type.heading, maxLines = 2)
            }
            if (running) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(0.5f)) { Progress(((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f), height = 3) }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.width(72.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Logo(e.channel.logo, 44) }
    }
}

/** Vereinswappen: aus dem Spielplan, sonst gemerkt oder (einmal je Verein) nachgeschlagen; ohne Bild bleibt der Platz leer. */
@androidx.compose.runtime.Composable
private fun Crest(app: AppState, fromSchedule: String?, team: String) {
    val url by androidx.compose.runtime.produceState(fromSchedule ?: app.crests.cached(team), fromSchedule, team) {
        if (value == null) value = runCatching { app.crests.of(team) }.getOrNull()
    }
    androidx.compose.foundation.layout.Box(Modifier.size(44.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        rememberImage(url)?.let { androidx.compose.foundation.Image(it, null, Modifier.size(44.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
    }
}

/**
 * Spiel zu jeder Sendung: über die Paarung, sonst über Stadion/Stadt in der Beschreibung (Sender „Live: 2. BL“), sonst per
 * Ausschluss (das einzige übrige Spiel der Liga zu dieser Zeit, wenn die anderen schon anderen Sendern gehören).
 */
private fun assign(scores: LiveScores, matches: List<LiveScores.Match>, events: List<SportEvent>): Map<SportEvent, LiveScores.Match?> {
    if (matches.isEmpty()) return emptyMap()
    val shows = events.map { e -> e.programme.let { LiveScores.Show(it.title, it.subtitle, it.description, it.start, it.stop, e.pairing) } }
    val result = scores.assign(matches, shows)
    // Auch für Senderliste, TV-Guide und Vorschau: dort steht dann ebenfalls die Partie statt „Live: BL“.
    de.ayutv.data.sport.MatchHints.learn(shows)
    return events.indices.associate { events[it] to result[it] }
}

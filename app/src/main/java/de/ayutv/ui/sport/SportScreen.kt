package de.ayutv.ui.sport

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.parse.Teams
import de.ayutv.data.parse.pairing
import de.ayutv.data.parse.sportHeadline
import de.ayutv.data.parse.headline
import de.ayutv.data.repo.ContentRepository.SportEvent
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.NoticeBar
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Filter: alles, eine Sportart, oder die Spiele der gefolgten Vereine. */
private sealed interface SportFilter {
    val label: String
    data object All : SportFilter { override val label = "Alles" }
    data object Teams : SportFilter { override val label = "Meine Vereine" }
    data class Of(val kind: de.ayutv.data.parse.SportKinds.Kind) : SportFilter { override val label = kind.label }
    /** Tabelle einer Liga statt der Spiele. */
    data class Table(val league: String, val name: String) : SportFilter { override val label = "📊 $name" }
}

/**
 * Zuletzt berechnete Liste, damit der Bereich beim Öffnen sofort etwas zeigt (statt leer zu bleiben, bis zehntausende
 * Sendungen durchsucht sind). Nur im Speicher, wird im Hintergrund erneuert.
 */
private object SportCache {
    @Volatile var events: List<SportEvent>? = null
    @Volatile var loadedAt = 0L
}

/** Spiel laut Spielplan und Sportart je Sendung, abseits der Oberfläche berechnet. */
private class SportInfo(
    /** Sendungen, dasselbe Spiel nur einmal (auch wenn erst der Spielplan zeigt, dass „Live: BL“ dieses Spiel ist). */
    val list: List<SportEvent>? = null,
    val matches: Map<SportEvent, de.ayutv.data.sport.LiveScores.Match?> = emptyMap(),
    val kinds: Map<SportEvent, de.ayutv.data.parse.SportKinds.Kind> = emptyMap(),
)

/**
 * Sport auf einen Blick: was jetzt live läuft und was heute und morgen kommt, über alle Sender.
 * OK schaltet um, OK halten folgt einem Verein (Erinnerung an alle seine Spiele).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SportScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val minute by rememberMinuteTicker()
    var events by remember { mutableStateOf(SportCache.events) }
    var filter by remember { mutableStateOf<SportFilter>(SportFilter.All) }
    var choosing by remember { mutableStateOf<SportEvent?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val teams = settings?.followedTeams.orEmpty()
    // Sport immer in der besten Fassung: 4K vor Full HD, auch wenn sonst Full HD bevorzugt wird.
    // Fällt der Sender (alle Fassungen) aus oder hängt immer wieder, geht es auf den anderen Sendern mit demselben Spiel
    // weiter („auch auf …“).
    fun watch(channel: de.ayutv.data.db.ChannelEntity, event: SportEvent? = null) {
        scope.launch {
            val best = runCatching { container.content.bestForSport(channel) }.getOrDefault(channel)
            val others = event?.let { listOf(it.channel) + it.alsoOn }.orEmpty().filter { it.key != channel.key }.map { it.key }
            PlayerActivity.startLive(context, best.key, best.groupTitle, alternatives = others)
        }
    }

    // Sofort laden (aus dem Speicher, falls schon da), dann alle 5 Minuten neu – nicht jede Minute: Das Durchsuchen
    // aller Sendungen kostet auf dem Stick mehrere Sekunden Rechenzeit.
    LaunchedEffect(Unit) {
        while (true) {
            if (events == null || System.currentTimeMillis() - SportCache.loadedAt > SPORT_REFRESH_MS) {
                val fresh = runCatching { container.content.sportEvents(hours = 48) }.getOrNull()
                if (fresh != null) {
                    SportCache.events = fresh
                    SportCache.loadedAt = System.currentTimeMillis()
                    events = fresh
                } else if (events == null) events = emptyList()
            }
            delay(SPORT_REFRESH_MS)
        }
    }
    // Spielpläne und Stände (OpenLigaDB), jede Minute: für die Paarung, wenn das EPG nur „Live: 2. BL“ nennt, und
    // (wenn eingeschaltet) für Stand und Spielminute.
    var scores by remember { mutableStateOf<List<de.ayutv.data.sport.LiveScores.Match>>(emptyList()) }
    LaunchedEffect(minute) {
        scores = runCatching { container.liveScores.current() }.getOrDefault(emptyList())
        de.ayutv.data.sport.MatchHints.update(scores)
    }
    /**
     * Das Spiel zu jeder Sendung: über die Paarung, sonst über Stadion, Stadt oder Verein in der Beschreibung, sonst
     * per Ausschluss (das einzige übrige Spiel der Liga zu dieser Zeit). Dazu die Sportart. Abseits der Oberfläche
     * (vorher in der Darstellung selbst: Das hielt die Bedienung bei vielen Sendungen spürbar an).
     */
    var info by remember { mutableStateOf(SportInfo()) }
    LaunchedEffect(events, scores) {
        val list = events.orEmpty()
        info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val shows = list.map { e -> e.programme.let { de.ayutv.data.sport.LiveScores.Show(it.title, it.subtitle, it.description, it.start, it.stop, it.pairing()) } }
            val result = container.liveScores.assign(scores, shows)
            // Auch für Senderliste, TV-Guide und Vorschau: dort steht dann ebenfalls die Partie statt „Live: BL“.
            de.ayutv.data.sport.MatchHints.learn(shows)
            // Dasselbe Spiel nur einmal: „Live: BL“ auf dem einen Sender und „Mainz – Bayern“ auf dem anderen (anderer
            // Anbieter, leicht andere Anfangszeit) werden zu einem Eintrag mit beiden Sendern.
            // Wiederholungen schon gespielter Spiele (laut Spielplan) entfallen, auch ohne Kennzeichnung im TV-Guide.
            val merged = ProgrammeText.mergeSameMatch(
                list.indices.filterNot { result[it] == null && container.liveScores.isReplay(scores, shows[it].pairing, shows[it].start) }
                    .map { list[it] to result[it] },
                { it.first.programme.start },
                { (e, match) -> e.programme.pairing() ?: match?.pairing },
            ) { a, b -> a.first.mergedWith(b.first) to (a.second ?: b.second) }
            SportInfo(
                list = merged.map { it.first },
                matches = merged.associate { it.first to it.second },
                kinds = merged.associate { (e, match) ->
                    val p = e.programme
                    e to de.ayutv.data.parse.SportKinds.kind(p.title, p.subtitle, p.category, p.description, e.channel.name, match != null)
                },
            )
        }
    }
    val matches = info.matches
    fun matchOf(e: SportEvent): de.ayutv.data.sport.LiveScores.Match? = matches[e]
    /** Stand nur, wenn Live-Ergebnisse eingeschaltet sind. */
    fun scoreOf(e: SportEvent) = matchOf(e)?.takeIf { settings?.liveScores == true }
    /** Paarung aus dem EPG, sonst aus dem Spielplan. */
    fun pairingOf(e: SportEvent): String? = e.programme.pairing() ?: matchOf(e)?.pairing
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }

    fun follow(team: String) {
        scope.launch {
            container.settings.update { it.copy(followedTeams = it.followedTeams + team) }
            val added = container.reminders.ensure(container.content.teamReminders(setOf(team)))
            notice = "Du folgst $team. " + if (added.isEmpty()) "Sobald ein Spiel im TV-Guide steht, kommt eine Erinnerung." else "${added.size} Spiel(e) vorgemerkt."
        }
    }
    fun unfollow(team: String) {
        scope.launch {
            container.settings.update { it.copy(followedTeams = it.followedTeams - team) }
            notice = "$team: keine neuen Erinnerungen mehr (gesetzte bleiben im TV-Guide sichtbar)"
        }
    }

    val now = minute
    // Sportarten, die gerade vorkommen (Fußball zuerst, dann nach Anzahl): je ein Filter.
    val kindsPresent = remember(info) {
        info.kinds.values.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key != de.ayutv.data.parse.SportKinds.Kind.FOOTBALL }, { it.key == de.ayutv.data.parse.SportKinds.Kind.OTHER }, { -it.value }))
            .map { it.key }
    }
    val filters: List<SportFilter> = listOf<SportFilter>(SportFilter.All) + kindsPresent.map { SportFilter.Of(it) } + SportFilter.Teams +
        de.ayutv.data.sport.LeagueTable.LEAGUES.map { (league, name) -> SportFilter.Table(league, name) }
    var table by remember { mutableStateOf<List<de.ayutv.data.sport.LeagueTable.Row>?>(null) }
    LaunchedEffect(filter, minute / SPORT_REFRESH_MS) {
        val f = filter as? SportFilter.Table ?: return@LaunchedEffect
        table = de.ayutv.data.sport.LeagueTable.load(container.http, f.league)
    }
    val shown = (info.list ?: events.orEmpty()).filter { e ->
        val p = e.programme
        when (val f = filter) {
            SportFilter.All -> true
            is SportFilter.Of -> info.kinds[e] == f.kind
            SportFilter.Teams -> pairingOf(e)?.let { pairing -> teams.any { Teams.plays(it, pairing, p.title) } } == true
            is SportFilter.Table -> false
        }
    }
    val live = shown.filter { it.programme.start <= now && it.programme.stop > now }
    val later = shown.filter { it.programme.start > now }
    val tomorrow = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    Box(Modifier.fillMaxSize()) {
        // Solange der Dialog offen ist, steht nur er auf dem Bildschirm (der Fokus kann nicht in die Liste fallen).
        if (choosing == null) Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp)) {
            Text("Sport", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Spiele und Live-Übertragungen aller Sender · OK: einschalten · OK halten: anderer Sender oder Verein folgen",
                color = AyuColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRestorer()) {
                items(filters, key = { it.label }) { f -> Pill(f.label, selected = f == filter) { if (f != filter) table = null; filter = f } }
                items(teams.sorted()) { team -> Pill("★ $team  ✕", selected = false) { unfollow(team) } }
            }
            Spacer(Modifier.height(12.dp))
            when {
                filter is SportFilter.Table -> TableView(table, teams)
                events == null -> Text("Spiele werden gesucht …", color = AyuColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                shown.isEmpty() -> EmptyHint(
                    if (filter == SportFilter.Teams && teams.isEmpty()) "Noch keinem Verein gefolgt" else "Gerade keine Spiele im TV-Guide",
                    if (filter == SportFilter.Teams && teams.isEmpty()) "Auf einem Spiel OK gedrückt halten und einen der beiden Vereine wählen."
                    else "Sport erscheint hier, sobald der TV-Guide ihn kennt (Einstellungen → Anzeige → TV-Guide aus dem Internet).",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fun section(title: String, list: List<SportEvent>) {
                        if (list.isEmpty()) return
                        item(key = "h-$title") {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = AyuColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(list, key = { "${it.programme.id}-${it.channel.key}" }) { e ->
                            EventRow(
                                e, now, scoreOf(e), matchOf(e), headlineOf(e.programme, pairingOf(e)), pairingOf(e),
                                onClick = { watch(e.channel, e) },
                                onLongClick = { choosing = e },
                            )
                        }
                    }
                    section("● Jetzt live", live)
                    section("Heute", later.filter { it.programme.start < tomorrow })
                    section("Morgen", later.filter { it.programme.start >= tomorrow && it.programme.start < tomorrow + DAY })
                    section("Später", later.filter { it.programme.start >= tomorrow + DAY })
                }
            }
        }
        choosing?.let { e ->
            val pairing = pairingOf(e)
            val sides = pairing?.let { Teams.ofPairing(it) }.orEmpty()
            FollowDialog(
                title = headlineOf(e.programme, pairing),
                teams = sides,
                followed = teams,
                channels = listOf(e.channel) + e.alsoOn,
                onPick = { team ->
                    choosing = null
                    if (team in teams) unfollow(team) else follow(team)
                },
                onWatch = { channel ->
                    choosing = null
                    watch(channel, e)
                },
                onDismiss = { choosing = null },
            )
        }
        notice?.let { NoticeBar(it) }
    }
}

private const val DAY = 24 * 3_600_000L
private const val SPORT_REFRESH_MS = 5 * 60_000L

/**
 * Überschrift einer Sport-Sendung, überall gleich aufgebaut: die Paarung („Mainz 05 – Bayern München“, aus dem EPG oder
 * dem Spielplan), sonst der Wettbewerb ausgeschrieben ohne „Live:“ („Live: BL“ → „Bundesliga“). Der Wettbewerb steht bei
 * Spielen in der Zeile darüber.
 */
private fun headlineOf(p: de.ayutv.data.db.ProgrammeEntity, pairing: String?): String =
    ProgrammeText.eventTitle(p.title, p.subtitle, p.description, pairing)

/**
 * Eine Zeile pro Spiel. Markiert (Fokus) klappt sie auf: ganzer Titel, Beschreibung und alle Sender,
 * auf denen es läuft. Laufende Spiele zeigen, wie weit sie sind.
 */
@Composable
private fun EventRow(
    e: SportEvent,
    now: Long,
    score: de.ayutv.data.sport.LiveScores.Match?,
    match: de.ayutv.data.sport.LiveScores.Match?,
    headline: String,
    pairing: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = e.programme
    val running = p.start <= now && p.stop > now
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(110.dp)) {
                val live = score?.live(now) == true
                val final = score?.finished == true
                Text(
                    when {
                        live -> "● ${score!!.score}"
                        final -> "Ende ${score!!.score}"
                        running -> "● LIVE"
                        else -> formatTime(p.start)
                    },
                    color = if (running || live) AyuColors.Live else AyuColors.TextPrimary,
                    fontSize = if (live || final) 18.sp else 16.sp,
                    fontWeight = if (live || final) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
                Text(
                    if (live) score!!.minute(now) ?: "" else "bis ${formatTime(p.stop)}",
                    color = AyuColors.TextSecondary, fontSize = 12.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                // Fußball mit bekannter Partie: Anzeigetafel (Verein – Wappen – Stand/Anstoß – Wappen – Verein).
                // Nur aus einer echten Paarung (EPG oder Spielplan), nie aus dem Titel: „BL: Multiview – 9. Spieltag“ ist kein Spiel.
                val sides = if (match != null) listOf(match.team1, match.team2)
                    else pairing?.let { Teams.ofPairing(it) }?.takeIf { it.size == 2 }?.map { ProgrammeText.niceCase(it) }.orEmpty()
                if (sides.size == 2) {
                    Text(
                        (competitionOf(p, match) + "  ·  " + ProgrammeText.channelLabel(e.channel.name)).uppercase(),
                        color = AyuColors.Accent, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sides[0], modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                        Spacer(Modifier.width(10.dp))
                        Crest(rememberCrest(match?.icon1, sides[0]), sides[0], 40)
                        Box(Modifier.width(92.dp), contentAlignment = Alignment.Center) {
                            val shownScore = score?.takeIf { it.live(now) || it.finished }
                            Text(
                                // Anstoß laut Spielplan (15:30), nicht Beginn der Übertragung (15:15).
                                shownScore?.score ?: formatTime(match?.kickoff ?: p.start),
                                color = if (score?.live(now) == true) AyuColors.Live else AyuColors.TextPrimary,
                                fontSize = if (shownScore != null) 24.sp else 18.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                        Crest(rememberCrest(match?.icon2, sides[1]), sides[1], 40)
                        Spacer(Modifier.width(10.dp))
                        Text(sides[1], modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                    }
                } else {
                    Text(headline, fontSize = 16.sp, maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis)
                }
                if (running) {
                    val done = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.5f).height(3.dp).background(AyuColors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(done).height(3.dp).background(AyuColors.Live, RoundedCornerShape(2.dp)))
                    }
                }
                if (focused) {
                    score?.takeIf { it.goalCount > 0 }?.lastGoal?.let {
                        Text("⚽ zuletzt: $it", color = AyuColors.Accent, fontSize = 13.sp, maxLines = 1)
                    }
                    ProgrammeText.description(p.description)?.let {
                        Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        ProgrammeText.channelLabel(e.channel.name) + if (e.alsoOn.isNotEmpty()) "  ·  auch auf: " + e.alsoOn.joinToString(", ") { ProgrammeText.channelLabel(it.name) } else "",
                        color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        ProgrammeText.channelLabel(e.channel.name) + if (e.otherChannels > 0) "  ·  auch auf ${e.otherChannels} weiteren" else "",
                        color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            ChannelLogo(e.channel.logo, e.channel.name, Modifier.size(width = 64.dp, height = 40.dp))
        }
    }
}

/** Wettbewerb für die Anzeigetafel: Liga laut Spielplan, sonst der Titel ohne „Live:“ und Sportart davor. */
private fun competitionOf(p: de.ayutv.data.db.ProgrammeEntity, match: de.ayutv.data.sport.LiveScores.Match?): String = when (match?.league) {
    "bl1" -> "Bundesliga"
    "bl2" -> "2. Bundesliga"
    "bl3" -> "3. Liga"
    else -> match?.league?.let { de.ayutv.data.sport.IntlScores.NAMES[it] } ?: ProgrammeText.competition(p.title).substringBefore(" – ").substringBefore(" - ").trim()
        .ifEmpty { de.ayutv.data.parse.SportKinds.kind(p.title, p.subtitle, p.category, p.description, "", false).label }
}

/** Wappen: aus dem Spielplan, sonst gemerkt oder (einmal je Verein) nachgeschlagen. */
@Composable
private fun rememberCrest(fromSchedule: String?, team: String): String? {
    val container = LocalContainer.current
    // Erst das gemerkte bzw. nachgeschlagene Wappen (Dienst, aktuell), das aus dem Spielplan nur als Ersatz.
    val crest by androidx.compose.runtime.produceState(container.crests.cached(team) ?: fromSchedule, fromSchedule, team) {
        runCatching { container.crests.of(team) }.getOrNull()?.let { value = it }
    }
    return crest
}

/** Vereinswappen, klein und ohne Rahmen; ohne Bild bleibt der Platz leer. */
@Composable
private fun Crest(url: String?, team: String, size: Int = 26) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (url != null) {
            coil3.compose.AsyncImage(
                model = url, contentDescription = team, modifier = Modifier.size(size.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

/** Tabelle: Platz, Wappen, Verein, Spiele, S/U/N, Tore, Differenz, Punkte. Gefolgte Vereine hervorgehoben. */
@Composable
private fun TableView(rows: List<de.ayutv.data.sport.LeagueTable.Row>?, followed: Set<String>) {
    when {
        rows == null -> Text("Tabelle wird geladen …", color = AyuColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        rows.isEmpty() -> EmptyHint("Keine Tabelle", "OpenLigaDB ist gerade nicht erreichbar. Gleich noch einmal versuchen.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "head") {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TableCell("#", 36); Spacer(Modifier.width(34.dp)); Text("Verein", color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TableCell("Sp", 44); TableCell("S", 36); TableCell("U", 36); TableCell("N", 36); TableCell("Tore", 76); TableCell("+/-", 52); TableCell("Pkt", 52)
                }
            }
            items(rows, key = { it.team }) { r ->
                val mine = followed.any { Teams.plays(it, r.team, r.short) }
                // Zone: Champions League (1–4) grün, Abstieg (die letzten 2) rot, Relegation (drittletzter) orange.
                val zone = when {
                    r.rank <= 4 -> Color(0xFF2ECC71)
                    r.rank > rows.size - 2 -> Color(0xFFE74C3C)
                    r.rank == rows.size - 2 -> Color(0xFFF39C12)
                    else -> Color.Transparent
                }
                Surface(
                    onClick = {},
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (mine) AyuColors.AccentSoft else AyuColors.Surface,
                        focusedContainerColor = AyuColors.SurfaceRaised,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(24.dp).background(zone, RoundedCornerShape(2.dp)))
                        Text("${r.rank}", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.width(32.dp).padding(start = 8.dp))
                        // Erst das gepflegte Wappen (Dienst), das aus der Tabelle (OpenLigaDB) nur als Ersatz.
                        Crest(rememberCrest(null, r.team) ?: de.ayutv.data.sport.LiveScores.crest(r.icon.orEmpty()), r.team, 26)
                        Spacer(Modifier.width(8.dp))
                        Text((if (mine) "★ " else "") + r.team, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TableCell("${r.matches}", 44, Color.White); TableCell("${r.won}", 36, Color.White); TableCell("${r.draw}", 36, Color.White); TableCell("${r.lost}", 36, Color.White)
                        TableCell("${r.goals}:${r.against}", 76, Color.White)
                        TableCell(if (r.diff > 0) "+${r.diff}" else "${r.diff}", 52, Color.White)
                        Text("${r.points}", color = AyuColors.Accent, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: Int, color: Color = AyuColors.TextSecondary) {
    Text(text, color = color, fontSize = if (color == Color.White) 15.sp else 13.sp, maxLines = 1, modifier = Modifier.width(width.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
            focusedContainerColor = AyuColors.Accent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 15.sp, maxLines = 1)
    }
}

/** Auf welchem Sender schauen, welchem der beiden Vereine folgen? (Oder nicht mehr folgen, wenn schon gefolgt.) */
@Composable
private fun FollowDialog(
    title: String,
    teams: List<String>,
    followed: Set<String>,
    channels: List<de.ayutv.data.db.ChannelEntity>,
    onPick: (String) -> Unit,
    onWatch: (de.ayutv.data.db.ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(14.dp),
            colors = SurfaceDefaults.colors(containerColor = AyuColors.SurfaceRaised),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (channels.size > 1) {
                    Text("Schauen auf", color = AyuColors.TextSecondary, fontSize = 13.sp)
                    channels.forEach { channel ->
                        Pill("▶ ${channel.name}", selected = false, onClick = { onWatch(channel) })
                    }
                }
                Text("Verein folgen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Vor jedem Spiel des Vereins kommt eine Erinnerung, auf welchem Sender es läuft.",
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
                if (teams.isEmpty()) Text("Hier ist keine Paarung erkennbar.", fontSize = 15.sp)
                teams.forEachIndexed { index, team ->
                    Pill(
                        if (team in followed) "★ $team – nicht mehr folgen" else "☆ $team folgen",
                        selected = false,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onPick(team) },
                    )
                }
                Pill("Abbrechen", selected = false, modifier = if (teams.isEmpty()) Modifier.focusRequester(first) else Modifier, onClick = onDismiss)
            }
        }
    }
}

package de.ayutv.ui.guide

import androidx.compose.runtime.produceState
import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.isTouchDevice
import de.ayutv.ui.components.isPortrait
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.launch
import de.ayutv.ui.live.FAVORITES_GROUP
import de.ayutv.data.repo.customGroupId
import de.ayutv.data.repo.customGroupKey
import de.ayutv.data.repo.Catchup
import de.ayutv.data.reminders.Reminder
import de.ayutv.data.db.PlaylistEntity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val WINDOW = 4 * HALF_HOUR
private const val DAY = 24 * 60 * MINUTE
// 1080p-Fernseher haben 960 dp Breite: Navigation + Senderspalte + 2 Stunden à 5 dp/min passen genau.
/** Breite pro Minute und der Senderspalte: im Hochformat schmaler, damit zwei Stunden auf den Bildschirm passen. */
private class GuideMetrics(val dpPerMinute: Dp, val channelColumn: Dp)
private val TV_METRICS = GuideMetrics(5.dp, 190.dp)
private val PORTRAIT_METRICS = GuideMetrics(2.9.dp, 150.dp)
private val LocalGuideMetrics = staticCompositionLocalOf { TV_METRICS }
private val ROW_HEIGHT = 52.dp

/**
 * EPG-Raster mit festem Zeitfenster (2 Stunden). Links am ersten bzw. rechts am letzten Eintrag
 * einer Zeile verschiebt das Fenster um 30 Minuten. Geladen werden nur die Sendungen der gewählten Gruppe.
 * Oben steht die Vorschau der markierten Sendung, eine rote Linie zeigt „jetzt“. Vergangene Sendungen
 * von Sendern mit Archiv (↺) lassen sich direkt abspielen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen() {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by content.groups.collectAsStateWithLifecycle(emptyList())
    val favorites by content.favoriteChannels.collectAsStateWithLifecycle(emptyList())
    val customGroups by content.customGroups.collectAsStateWithLifecycle(emptyList())
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    // Zurück aus dem Player: auf den zuletzt gesehenen Sender (auch wenn dort weitergeschaltet wurde).
    var leftForPlayer by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val returnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var returnKey by remember { mutableStateOf<String?>(null) }
    val recentLive by content.recentLive.collectAsStateWithLifecycle(emptyList())
    // Ohne eigene Wahl: Favoriten zuerst (wie in Live TV), sonst die erste Gruppe.
    val activeGroup = group ?: if (favorites.isNotEmpty()) FAVORITES_GROUP else groups.firstOrNull()?.name
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val rawChannels by remember(activeGroup) {
        if (activeGroup == FAVORITES_GROUP) content.favoriteChannels else content.channelsIn(activeGroup)
    }.collectAsStateWithLifecycle(emptyList())
    // Jeder Sender eine Zeile, auch wenn der Anbieter ihn in SD, HD und 4K führt.
    val channels by produceState(emptyList<ChannelEntity>(), rawChannels, appSettings?.mergeVariants, activeGroup, appSettings?.minQuality, appSettings?.preferUhd) {
        appSettings?.let { de.ayutv.data.parse.ChannelQuality.configure(it.minQuality.height, it.preferUhd, it.hideBrokenChannels) }
        val own = activeGroup == FAVORITES_GROUP || activeGroup?.let(::customGroupId) != null
        value = if (appSettings?.mergeVariants != false && !own) content.mergeVariants(rawChannels) else rawChannels
    }
    val minute by rememberMinuteTicker()
    // Nur die Vorschau liest diesen Zustand: Fokuswechsel im Raster bauen das Raster nicht neu auf.
    val focused = remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity>?>(null) }

    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val windowEnd = windowStart + WINDOW
    var programmes by remember { mutableStateOf<Map<String, List<ProgrammeEntity>>>(emptyMap()) }
    // Titel mit Kennzeichen und Art der Sendung einmal je Sendung im Hintergrund (reguläre Ausdrücke), nicht beim
    // Scrollen auf dem Haupt-Thread für jede neu sichtbare Zelle.
    var labels by remember { mutableStateOf<Map<Long, GuideLabel>>(emptyMap()) }
    LaunchedEffect(channels, windowStart) {
        val loaded = content.programmesInRange(channels, windowStart, windowEnd)
        labels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val names = channels.filter { !it.epgId.isNullOrEmpty() }.associate { it.epgId!! to it.name }
            val out = HashMap<Long, GuideLabel>()
            for ((epgId, list) in loaded) for (p in list) {
                out[p.id] = GuideLabel(
                    upcoming = p.headlineWithBadge().ifEmpty { "Ohne Titel" },
                    past = p.headline().ifEmpty { "Ohne Titel" },
                    genre = de.ayutv.data.parse.ProgrammeGenre.of(p.title, p.category, names[epgId].orEmpty()),
                )
            }
            out
        }
        programmes = loaded
    }

    // Mit Archiv lässt sich so weit zurückblättern, wie der Anbieter aufzeichnet (höchstens 7 Tage).
    val archiveDays = remember(channels) { channels.maxOfOrNull { it.catchupDays }?.coerceIn(0, 7) ?: 0 }
    val earliest = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - maxOf(2 * HALF_HOUR, archiveDays * DAY)
    // Ins Archiv (vor „jetzt“) nur mit ⏪ oder dem Knopf „früher“: Sonst müsste man mit ◀ durch bis zu 7 Tage
    // blättern, bevor man wieder in die Seitenleiste kommt.
    val shiftArchive: (Int) -> Boolean = { steps ->
        val target = (windowStart + steps * HALF_HOUR).coerceAtLeast(earliest)
        (target != windowStart).also { if (it) windowStart = target }
    }
    // ◀ ▶ am Rand des Rasters: zurück höchstens bis „jetzt“. Liefert false, wenn sich nichts verschieben lässt:
    // Dann geht „links“ normal weiter (in die Seitenleiste).
    val shift: (Int) -> Boolean = { steps ->
        val now = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        val target = when {
            steps > 0 -> windowStart + steps * HALF_HOUR
            windowStart <= now -> windowStart
            else -> (windowStart + steps * HALF_HOUR).coerceAtLeast(now)
        }
        (target != windowStart).also { if (it) windowStart = target }
    }
    val reminders by container.reminders.all.collectAsStateWithLifecycle(emptyList())
    val reminderIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }
    val recordings by container.recordings.all.collectAsStateWithLifecycle()
    val recordingIds = remember(recordings) {
        recordings.filter { it.kind == de.ayutv.data.record.Recording.Kind.RECORDING }.mapTo(HashSet()) { "${it.channelKey}@${it.start}" }
    }
    // Lange OK: laufende oder kommende Sendung aufnehmen (bzw. die Aufnahme wieder entfernen).
    val record: (ChannelEntity, ProgrammeEntity) -> Unit = { channel, programme ->
        if (programme.stop > System.currentTimeMillis()) scope.launch {
            val on = container.recordings.toggleRecording(
                channel.key, channel.name, programme.headline().ifEmpty { channel.name }, channel.url,
                channel.userAgent ?: appSettings?.userAgent ?: de.ayutv.data.AppSettings.DEFAULT_USER_AGENT,
                programme.start, programme.stop, programme.icon ?: channel.logo,
            )
            val warning = if (on) de.ayutv.data.AccountLimits.recordingWarning(container.accountLimits.maxConnections(channel.playlistId)) else null
            android.widget.Toast.makeText(
                context,
                (if (on) "⏺ Wird aufgenommen: ${programme.headline()}" else "Aufnahme entfernt") + warning?.let { "\n$it" }.orEmpty(),
                if (warning != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // Im Player die Senderliste der hier gewählten Gruppe (auch eigene Gruppe oder Favoriten), nicht die des Anbieters.
    fun startLive(channel: ChannelEntity) {
        leftForPlayer = true
        if (activeGroup == FAVORITES_GROUP) PlayerActivity.startLive(context, channel.key, null, favoritesOnly = true)
        else PlayerActivity.startLive(context, channel.key, activeGroup ?: channel.groupTitle)
    }
    val play: (ChannelEntity, ProgrammeEntity?) -> Unit = { channel, programme ->
        val now = System.currentTimeMillis()
        if (programme != null && programme.start > now) {
            // Kommende Sendung: Erinnerung setzen bzw. wieder entfernen.
            scope.launch { container.reminders.toggle(Reminder(channel.key, channel.name, programme.headline(), programme.start)) }
        } else if (programme != null && programme.stop <= now) {
            scope.launch {
                val url = content.catchupUrl(channel, programme)
                if (url != null) {
                    leftForPlayer = true
                    PlayerActivity.startCatchup(context, channel, programme, url)
                } else startLive(channel)
            }
        } else {
            startLive(channel)
        }
    }

    val metrics = if (isPortrait) PORTRAIT_METRICS else TV_METRICS
    CompositionLocalProvider(LocalGuideMetrics provides metrics) {
    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (favorites.isNotEmpty()) {
                item(key = FAVORITES_GROUP) { GroupChip("★ Favoriten", activeGroup == FAVORITES_GROUP) { group = FAVORITES_GROUP } }
            }
            items(customGroups.filter { it.count > 0 }, key = { "eigene-${it.id}" }) { g ->
                val key = customGroupKey(g.id)
                GroupChip("📁 ${g.name}", key == activeGroup) { group = key }
            }
            items(groups, key = { it.name }) { g ->
                GroupChip(g.name, g.name == activeGroup) { group = g.name }
            }
        }

        Spacer(Modifier.height(10.dp))
        ProgrammePreview(focused, playlists, reminderIds, minute, Modifier.padding(horizontal = 48.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isTouchDevice) {
                // Ohne Fernbedienung: Zeitfenster mit Knöpfen verschieben (sonst ◀ am ersten, ▶ am letzten Feld).
                Row(Modifier.width(metrics.channelColumn + 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupChip("◀ früher", false) { shiftArchive(-2) }
                    GroupChip("später ▶", false) { shift(+2) }
                }
            } else {
                // Hinweis an der Stelle der Senderspalte: Archiv mit ⏪, sonst ◀ ▶ am Rand.
                Text(
                    "⏪ früher · ⏩ später",
                    modifier = Modifier.width(metrics.channelColumn + 4.dp),
                    color = AyuColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            for (i in 0 until 4) {
                Text(
                    formatTime(windowStart + i * HALF_HOUR),
                    modifier = Modifier.width(metrics.dpPerMinute * 30),
                    color = AyuColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (channels.isEmpty()) {
            EmptyHint("Kein TV-Guide", "Für diese Gruppe sind keine Sender vorhanden.")
            return@Column
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                // ⏪ ⏩ blättern überall im Raster: zurück auch ins Archiv.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaRewind -> { shiftArchive(-1); true }
                    Key.MediaFastForward -> { shift(+1); true }
                    else -> false
                }
            }
        ) {
            LaunchedEffect(recentLive.firstOrNull()?.key, channels, leftForPlayer) {
                if (!leftForPlayer) return@LaunchedEffect
                val key = recentLive.firstOrNull()?.key ?: return@LaunchedEffect
                val index = channels.indexOfFirst { it.key == key }
                if (index < 0) return@LaunchedEffect
                leftForPlayer = false
                returnKey = key
                listState.scrollToItem(maxOf(0, index - 2))
                kotlinx.coroutines.delay(80)
                runCatching { returnFocus.requestFocus() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(channels, key = { it.key }, contentType = { "guideRow" }) { channel ->
                    GuideRow(
                        channel = channel,
                        programmes = channel.epgId?.let { programmes[it] }.orEmpty(),
                        labels = labels,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = minute,
                        reminderIds = reminderIds,
                        recordingIds = recordingIds,
                        onShift = shift,
                        focusRequester = returnFocus.takeIf { channel.key == returnKey },
                        onFocusProgramme = { focused.value = channel to it },
                        onPlay = { programme -> play(channel, programme) },
                        onRecord = { programme -> record(channel, programme) },
                    )
                }
            }
            // „Jetzt“-Linie über dem Raster
            val nowOffset = (minute - windowStart) / MINUTE
            if (nowOffset in 0 until WINDOW / MINUTE) {
                Box(
                    Modifier
                        .offset(x = 48.dp + metrics.channelColumn + 4.dp + metrics.dpPerMinute * nowOffset.toInt()).background(Color.Red.copy(alpha = 0.9f))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(AyuColors.Live),
                )
            }
        }
    }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
            focusedContainerColor = AyuColors.Accent,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, maxLines = 1)
    }
}

/** Titel, Zeit und Beschreibung der markierten Sendung; bei Archiv-Sendungen der Hinweis zum Abspielen. */
@Composable
private fun ProgrammePreview(
    state: State<Pair<ChannelEntity, ProgrammeEntity>?>,
    playlists: List<PlaylistEntity>,
    reminderIds: Set<String>,
    now: Long,
    modifier: Modifier,
) {
    val (channel, programme) = state.value ?: (null to null)
    Row(modifier.fillMaxWidth().height(78.dp)) {
    if (programme != null && !programme.icon.isNullOrBlank()) {
        de.ayutv.ui.components.ProgrammeImage(programme.icon, Modifier.width(139.dp).fillMaxHeight())
        Spacer(Modifier.width(14.dp))
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (channel == null || programme == null) {
            Text("Sendung auswählen: Vorschau erscheint hier", color = AyuColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val past = programme.stop <= now
        val archive = past && Catchup.url(playlists.firstOrNull { it.id == channel.playlistId }, channel, programme.start, programme.stop) != null
        val status = when {
            programme.start <= now && programme.stop > now -> "● JETZT · lange OK: aufnehmen"
            archive -> "↺ OK: aus dem Archiv abspielen"
            past -> "Vorbei"
            "${channel.key}@${programme.start}" in reminderIds -> "🔔 Erinnerung an · OK: entfernen · lange OK: aufnehmen"
            else -> "OK: Erinnerung · lange OK: aufnehmen"
        }
        Text(
            "${channel.name} · ${formatTime(programme.start)} – ${formatTime(programme.stop)} · $status",
            color = if (archive || !past) AyuColors.Accent else AyuColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(if (programme.stop > now) programme.headlineWithBadge() else programme.headline(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        de.ayutv.data.parse.ProgrammeText.description(programme.description)?.let {
            Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    labels: Map<Long, GuideLabel>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    reminderIds: Set<String>,
    recordingIds: Set<String>,
    onShift: (Int) -> Boolean,
    onFocusProgramme: (ProgrammeEntity) -> Unit,
    onPlay: (ProgrammeEntity?) -> Unit,
    onRecord: (ProgrammeEntity) -> Unit,
    /** Fokus beim Zurückkommen aus dem Player: auf die laufende Sendung dieses Senders. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val metrics = LocalGuideMetrics.current
    Row(Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(metrics.channelColumn).fillMaxHeight().background(AyuColors.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 48.dp, height = 32.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name + if (channel.catchupDays > 0) " ↺" else "",
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))

        val cells = remember(programmes, windowStart, metrics) { layoutCells(programmes, windowStart, windowEnd, metrics.dpPerMinute) }
        if (cells.isEmpty()) {
            GuideCell("Keine Programmdaten", null, metrics.dpPerMinute * (WINDOW / MINUTE).toInt(), live = true, first = false, last = false, onShift, {}, focusRequester = focusRequester) { onPlay(null) }
        } else {
            val firstIndex = cells.indexOfFirst { it.programme != null }
            val lastIndex = cells.indexOfLast { it.programme != null }
            // Fokus-Ziel: die laufende Sendung, sonst die erste im Fenster.
            val targetIndex = cells.indexOfFirst { c -> c.programme?.let { it.start <= now && it.stop > now } == true }.takeIf { it >= 0 } ?: firstIndex
            cells.forEachIndexed { index, cell ->
                val p = cell.programme
                if (p == null) {
                    Spacer(Modifier.width(cell.width))
                } else {
                    // Nach Sendung geschlüsselt: Beim Verschieben des Zeitfensters bleibt der Fokus auf ihr.
                    key(p.id) {
                        val archive = p.stop <= now && channel.catchupDays > 0 && now - p.start <= channel.catchupDays * DAY
                        // Titel und Art kommen fertig aus dem Hintergrund ([GuideLabel]); nur im Ausnahmefall hier.
                        val label = labels[p.id]
                        val upcoming = p.stop > now
                        val headline = label?.let { if (upcoming) it.upcoming else it.past }
                            ?: remember(p, upcoming) { (if (upcoming) p.headlineWithBadge() else p.headline()).ifEmpty { "Ohne Titel" } }
                        val genre = label?.genre ?: de.ayutv.data.parse.ProgrammeGenre.OTHER
                        GuideCell(
                            genre = genre,
                            title = (if (archive) "↺ " else "") + (if ("${channel.key}@${p.start}" in reminderIds) "🔔 " else "") +
                                (if ("${channel.key}@${p.start}" in recordingIds) "⏺ " else "") +
                                headline,
                            time = "${formatTime(p.start)} – ${formatTime(p.stop)}",
                            width = cell.width,
                            live = p.start <= now && p.stop > now,
                            first = index == firstIndex,
                            last = index == lastIndex,
                            onShift = onShift,
                            onFocused = { onFocusProgramme(p) },
                            onClick = { onPlay(p) },
                            onLongClick = if (p.stop > now) ({ onRecord(p) }) else null,
                            focusRequester = focusRequester.takeIf { index == targetIndex },
                        )
                    }
                }
            }
        }
    }
}

private class Cell(val programme: ProgrammeEntity?, val width: Dp)

/** Vorbereitete Anzeige einer Sendung im Raster. */
private class GuideLabel(val upcoming: String, val past: String, val genre: de.ayutv.data.parse.ProgrammeGenre)

private fun layoutCells(programmes: List<ProgrammeEntity>, windowStart: Long, windowEnd: Long, dpPerMinute: Dp): List<Cell> {
    val cells = ArrayList<Cell>()
    var cursor = windowStart
    // Schon nach Beginn sortiert (programmesInRange).
    for (p in programmes) {
        val start = maxOf(p.start, cursor)
        val end = minOf(p.stop, windowEnd)
        if (end <= start) continue
        if (start > cursor) cells += Cell(null, dpPerMinute * ((start - cursor) / MINUTE).toInt())
        cells += Cell(p, dpPerMinute * ((end - start) / MINUTE).toInt().coerceAtLeast(4))
        cursor = end
    }
    return cells
}

@Composable
private fun GuideCell(
    title: String,
    time: String?,
    width: Dp,
    live: Boolean,
    first: Boolean,
    last: Boolean,
    onShift: (Int) -> Boolean,
    onFocused: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    genre: de.ayutv.data.parse.ProgrammeGenre = de.ayutv.data.parse.ProgrammeGenre.OTHER,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Tippen: erst Vorschau zeigen (wie Fokussieren mit der Fernbedienung), zweites Tippen spielt.
        focusFirst = true,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    first && event.key == Key.DirectionLeft -> onShift(-1)
                    last && event.key == Key.DirectionRight -> onShift(+1)
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) AyuColors.SurfaceRaised else AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Farbstreifen links nach Art der Sendung (Sport grün, Film lila, Serie blau, Nachrichten gelb, Kinder pink …).
        if (genre != de.ayutv.data.parse.ProgrammeGenre.OTHER) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(Color(genre.argb).copy(alpha = if (live || focused) 1f else 0.7f)))
        }
        Box(Modifier.fillMaxSize().padding(start = if (genre != de.ayutv.data.parse.ProgrammeGenre.OTHER) 12.dp else 8.dp, end = 8.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                // Zwei Zeilen: lange Titel und Partien („Borussia Dortmund – Eintracht Frankfurt“) bleiben lesbar.
                Text(
                    title,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (live || focused) AyuColors.TextPrimary else AyuColors.TextSecondary,
                )
                if (time != null && width > 90.dp) {
                    Text(time, fontSize = 13.sp, lineHeight = 15.sp, color = AyuColors.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

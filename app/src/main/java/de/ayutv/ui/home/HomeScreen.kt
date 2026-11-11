package de.ayutv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import de.ayutv.data.parse.headline
import de.ayutv.ui.components.focusGlow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.repo.Recommendation
import de.ayutv.player.PlayerActivity
import de.ayutv.data.record.Recording
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelCard
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.SyncBanner
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenSeries: (SeriesEntity) -> Unit) {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val continueWatching by content.continueWatching.collectAsStateWithLifecycle(emptyList())
    val recentLive by content.recentLive.collectAsStateWithLifecycle(emptyList())
    val favorites by content.favoriteChannels.collectAsStateWithLifecycle(emptyList())
    val recentMovies by content.recentMovies.collectAsStateWithLifecycle(emptyList())
    val recentSeries by content.recentSeries.collectAsStateWithLifecycle(emptyList())
    val watchMovies by content.watchlistMovies.collectAsStateWithLifecycle(emptyList())
    val watchSeries by content.watchlistSeries.collectAsStateWithLifecycle(emptyList())
    val sync by container.playlists.state.collectAsStateWithLifecycle()
    val minute by rememberMinuteTicker()
    val recordings by container.recordings.all.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pickingProfile by remember { mutableStateOf(false) }
    if (pickingProfile) {
        ProfilePicker(onClose = { pickingProfile = false })
        return
    }

    var recentChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<Recommendation>>(emptyList()) }
    var nowMap by remember { mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()) }

    LaunchedEffect(recentLive) {
        recentChannels = recentLive.mapNotNull { content.channel(it.key) }
        recommendations = content.recommendations()
    }
    LaunchedEffect(recentChannels, favorites, recommendations, minute) {
        nowMap = content.nowPlaying(recentChannels + favorites + recommendations.flatMap { it.channels })
    }

    // „Jetzt live im Sport“: laufende Spiele aller Sender (auch Event-Sender mit Spiel im Namen). Abseits der
    // Oberfläche berechnet und nur alle 5 Minuten, damit die Startseite beim Bedienen nichts kostet.
    var liveSport by remember { mutableStateOf<List<de.ayutv.data.repo.ContentRepository.SportEvent>>(emptyList()) }
    LaunchedEffect(minute / SPORT_REFRESH_MS) {
        val now = System.currentTimeMillis()
        liveSport = runCatching { content.sportEvents(hours = 1, now = now) }.getOrDefault(emptyList())
            .filter { it.programme.start <= now && it.programme.stop > now }
            .distinctBy { it.channel.key } // Schlüssel der Reihe müssen eindeutig sein
            .take(20)
    }

    // „Heute um 20:15“: was am Abend auf den eigenen Sendern (Favoriten, zuletzt gesehen, Vorschläge) beginnt.
    // OK setzt eine Erinnerung, lange OK schaltet gleich um. Nur bis 20:15 sichtbar, danach zählt „Jetzt“.
    var primetime by remember { mutableStateOf<List<Pair<ChannelEntity, ProgrammeEntity>>>(emptyList()) }
    LaunchedEffect(recentChannels, favorites, recommendations, minute / SPORT_REFRESH_MS) {
        val evening = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 20); set(java.util.Calendar.MINUTE, 15)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        primetime = if (now >= evening) emptyList() else runCatching {
            val channels = (favorites + recentChannels + recommendations.flatMap { it.channels }).distinctBy { it.key }
            val shows = content.nowPlaying(channels, evening + 60_000)
            channels.mapNotNull { c -> c.epgId?.let { shows[it] }?.let { c to it } }
                // Echte Abendsendungen: beginnen zwischen 19:55 und 20:30 (keine Sendung, die schon seit Stunden läuft).
                .filter { (_, p) -> p.start in (evening - 20 * 60_000)..(evening + 15 * 60_000) && p.stop - p.start >= 25 * 60_000 }
                .distinctBy { (_, p) -> p.title to p.start }
                .take(20)
        }.getOrDefault(emptyList())
    }
    val reminderSet = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    val nothingYet = continueWatching.isEmpty() && recentChannels.isEmpty() && favorites.isEmpty() &&
        recentMovies.isEmpty() && recentSeries.isEmpty() && watchMovies.isEmpty() && watchSeries.isEmpty()

    // „Jetzt“-Kachel oben: das, womit man am wahrscheinlichsten weitermacht (fest, folgt nicht dem Fokus – sonst
    // würde die ganze Seite bei jeder Bewegung neu aufgebaut).
    // Weiter schauen: angefangene Folge fortsetzen; ist die zuletzt geschaute Folge zu Ende, mit der nächsten weitermachen.
    fun resume(e: de.ayutv.data.db.HistoryEntity) {
        if (e.kind == HistoryKind.EPISODE && de.ayutv.data.repo.ContinueWatching.isFinished(e)) {
            scope.launch {
                val next = runCatching { content.nextEpisode(e.key) }.getOrNull()
                if (next != null) {
                    PlayerActivity.startVod(
                        context,
                        VodRequest(
                            de.ayutv.data.parse.Episodes.key(next.series.key, next.episode.id), HistoryKind.EPISODE,
                            de.ayutv.data.parse.Episodes.title(next.series.name, next.episode), next.episode.title,
                            next.episode.image ?: next.series.cover, next.url,
                        ),
                    )
                } else {
                    PlayerActivity.startVod(context, VodRequest(e.key, e.kind, e.title, e.subtitle, e.image, e.url))
                }
            }
        } else {
            PlayerActivity.startVod(context, VodRequest(e.key, e.kind, e.title, e.subtitle, e.image, e.url))
        }
    }
    fun resumeSubtitle(e: de.ayutv.data.db.HistoryEntity) =
        if (e.kind == HistoryKind.EPISODE && de.ayutv.data.repo.ContinueWatching.isFinished(e)) "Gesehen · weiter mit der nächsten Folge" else e.subtitle
    fun resumeProgress(e: de.ayutv.data.db.HistoryEntity) =
        if (e.durationMs > 0 && !(e.kind == HistoryKind.EPISODE && de.ayutv.data.repo.ContinueWatching.isFinished(e))) e.positionMs.toFloat() / e.durationMs else null

    // Großes Titelbild oben: wechselt zwischen „Weiter schauen“, dem wichtigsten laufenden Spiel und dem Abendprogramm.
    val spotlights = remember(continueWatching, recentChannels, favorites, nowMap, liveSport, primetime) {
        val main = continueWatching.firstOrNull()?.let { e ->
            Spotlight(
                label = "WEITER SCHAUEN", title = e.title, subtitle = resumeSubtitle(e), image = e.image,
                progress = resumeProgress(e),
                play = { resume(e) },
            )
        } ?: (recentChannels.firstOrNull() ?: favorites.firstOrNull())?.let { c ->
            val now = c.epgId?.let { nowMap[it] }
            Spotlight(
                label = "LIVE · ${c.name}", title = now?.headline()?.ifEmpty { null } ?: c.name,
                subtitle = now?.let { "${formatTime(it.start)} – ${formatTime(it.stop)}" } ?: c.groupTitle,
                image = now?.icon?.takeIf { it.isNotBlank() }, logo = c.logo,
                progress = now?.let { p -> ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                play = { PlayerActivity.startLive(context, c.key, c.groupTitle) },
            )
        }
        val sport = liveSport.firstOrNull()?.let { e ->
            val p = e.programme
            Spotlight(
                label = "LIVE · ${e.channel.name}", title = p.headline().ifEmpty { e.channel.name },
                subtitle = "${formatTime(p.start)} – ${formatTime(p.stop)}" + if (e.otherChannels > 0) " · auch auf ${e.otherChannels} weiteren Sendern" else "",
                image = p.icon?.takeIf { it.isNotBlank() }, logo = e.channel.logo,
                progress = ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f),
                play = { PlayerActivity.startLive(context, e.channel.key, e.channel.groupTitle) },
            )
        }
        val evening = primetime.firstOrNull { it.second.icon?.isNotBlank() == true } ?: primetime.firstOrNull()
        val tonight = evening?.let { (c, p) ->
            Spotlight(
                label = "HEUTE ${formatTime(p.start)} · ${c.name}", title = p.headline().ifEmpty { c.name },
                subtitle = p.description?.take(140)?.ifBlank { null } ?: "${formatTime(p.start)} – ${formatTime(p.stop)}",
                image = p.icon?.takeIf { it.isNotBlank() }, logo = c.logo, progress = null,
                play = { PlayerActivity.startLive(context, c.key, c.groupTitle) },
            )
        }
        listOfNotNull(main, sport, tonight).distinctBy { it.title }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "header", contentType = "header") {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(minute), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMAN).format(java.util.Date(minute)),
                        style = MaterialTheme.typography.bodyMedium, color = AyuColors.TextSecondary,
                    )
                    SyncBanner(sync)
                    de.ayutv.ui.components.UpdateBanner(Modifier.padding(top = 8.dp))
                }
                ProfileChip { pickingProfile = true }
                Spacer(Modifier.width(16.dp))
                Text(formatTime(minute), style = MaterialTheme.typography.headlineSmall, color = AyuColors.TextSecondary)
            }
        }

        if (spotlights.isNotEmpty()) {
            item(key = "spotlight", contentType = "spotlight") { SpotlightCarousel(spotlights) }
        }

        if (liveSport.isNotEmpty()) {
            item(key = "liveSport", contentType = "channelRow") {
                ChannelRow(
                    "● Jetzt live im Sport",
                    liveSport.map { it.channel },
                    liveSport.mapNotNull { e -> e.channel.epgId?.let { it to e.programme } }.toMap(),
                    onPlay = { PlayerActivity.startLive(context, it.key, it.groupTitle) },
                )
            }
        }

        if (primetime.isNotEmpty()) {
            item(key = "primetime", contentType = "channelRow") {
                Column {
                    SectionTitle("Heute um 20:15")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        items(primetime, key = { (c, p) -> "${c.key}@${p.start}" }) { (channel, programme) ->
                            val id = "${channel.key}@${programme.start}"
                            LaunchedEffect(id) { if (id !in reminderSet) reminderSet[id] = container.reminders.isSet(channel.key, programme.start) }
                            de.ayutv.ui.components.EveningCard(
                                channel = channel,
                                programme = programme,
                                reminded = reminderSet[id] == true,
                                onClick = {
                                    scope.launch {
                                        val on = container.reminders.toggle(
                                            de.ayutv.data.reminders.Reminder(channel.key, channel.name, programme.headline().ifEmpty { programme.title }, programme.start),
                                        )
                                        reminderSet[id] = on
                                        android.widget.Toast.makeText(
                                            context,
                                            if (on) "⏰ Erinnerung um ${formatTime(programme.start)}: ${programme.title}" else "Erinnerung entfernt",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onLongClick = { PlayerActivity.startLive(context, channel.key, channel.groupTitle) },
                            )
                        }
                    }
                }
            }
        }

        if (nothingYet) {
            item(key = "empty", contentType = "hint") {
                EmptyHint(
                    "Alles bereit",
                    "Öffne Live TV oder Filme. Was du schaust, merkt sich DnA-TV und schlägt dir hier Passendes vor.",
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "continue", contentType = "posterRow") {
                PosterRow("Weiter schauen") {
                    items(continueWatching, key = { it.key }) { entry ->
                        PosterCard(
                            title = entry.title,
                            image = entry.image,
                            subtitle = resumeSubtitle(entry),
                            progress = resumeProgress(entry),
                            onClick = { resume(entry) },
                        )
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "favorites", contentType = "channelRow") {
                ChannelRow(
                    "Favoriten",
                    favorites,
                    nowMap,
                    onPlay = { PlayerActivity.startLive(context, it.key, group = null, favoritesOnly = true) },
                    onLongClick = { channel -> scope.launch { content.toggleFavorite(channel.key) } },
                )
            }
        }

        if (recentChannels.isNotEmpty()) {
            item(key = "recent", contentType = "channelRow") {
                ChannelRow("Zuletzt gesehen", recentChannels, nowMap, onPlay = { PlayerActivity.startLive(context, it.key, it.groupTitle) })
            }
        }

        if (watchMovies.isNotEmpty() || watchSeries.isNotEmpty()) {
            item(key = "watchlist", contentType = "posterRow") {
                // Merkliste: gemerkte Filme und Serien (☆ Merken auf der Detailseite; lange OK entfernt).
                PosterRow("☆ Meine Liste") {
                    items(watchMovies, key = { "m-" + it.key }) { movie ->
                        PosterCard(
                            title = movie.name,
                            image = movie.poster,
                            subtitle = "Film" + (movie.rating?.let { " · ★ $it" } ?: ""),
                            onClick = {
                                PlayerActivity.startVod(context, VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url))
                            },
                            onLongClick = { scope.launch { content.toggleFavorite(movie.key) } },
                        )
                    }
                    items(watchSeries, key = { "s-" + it.key }) { series ->
                        PosterCard(
                            title = series.name,
                            image = series.cover,
                            subtitle = "Serie",
                            onClick = { onOpenSeries(series) },
                            onLongClick = { scope.launch { content.toggleFavorite(series.key) } },
                        )
                    }
                }
            }
        }

        if (recordings.isNotEmpty()) {
            item(key = "recordings", contentType = "posterRow") {
                PosterRow("⏺ Aufnahmen & Downloads") {
                    items(recordings, key = { it.id }) { r ->
                        PosterCard(
                            title = r.title,
                            image = r.image,
                            subtitle = recordingStatus(r),
                            progress = if (r.totalBytes > 0 && r.state == Recording.State.RUNNING) r.bytes.toFloat() / r.totalBytes else null,
                            onClick = {
                                if (r.state == Recording.State.SCHEDULED || r.bytes == 0L && r.state != Recording.State.DONE) {
                                    android.widget.Toast.makeText(context, recordingStatus(r), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val key = r.sourceKey ?: "rec:${r.id}"
                                    PlayerActivity.startVod(
                                        context,
                                        VodRequest(
                                            key,
                                            if (de.ayutv.data.parse.Episodes.seriesKeyOf(key) != null) HistoryKind.EPISODE else HistoryKind.VOD,
                                            r.title,
                                            r.subtitle,
                                            r.image,
                                            android.net.Uri.fromFile(container.recordings.file(r)).toString(),
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                // Zweimal lange drücken: löschen (einmal könnte ein Versehen sein).
                                if (pendingDelete == r.id) {
                                    pendingDelete = null
                                    scope.launch { container.recordings.delete(r) }
                                } else {
                                    pendingDelete = r.id
                                    android.widget.Toast.makeText(context, "Nochmal lange drücken: „${r.title}“ löschen", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        recommendations.forEach { rec ->
            item(key = "rec-${rec.title}", contentType = "channelRow") {
                ChannelRow(rec.title, rec.channels, nowMap, onPlay = { PlayerActivity.startLive(context, it.key, it.groupTitle) })
            }
        }

        if (recentMovies.isNotEmpty()) {
            item(key = "movies", contentType = "posterRow") {
                PosterRow("Neue Filme") {
                    items(recentMovies, key = { it.key }) { movie ->
                        PosterCard(
                            title = movie.name,
                            image = movie.poster,
                            subtitle = movie.rating?.let { "★ $it" },
                            onClick = {
                                PlayerActivity.startVod(
                                    context,
                                    VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (recentSeries.isNotEmpty()) {
            item(key = "series", contentType = "posterRow") {
                PosterRow("Neue Serien") {
                    items(recentSeries, key = { it.key }) { series ->
                        PosterCard(title = series.name, image = series.cover, onClick = { onOpenSeries(series) })
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelRow(
    title: String,
    channels: List<ChannelEntity>,
    nowMap: Map<String, ProgrammeEntity>,
    onPlay: (ChannelEntity) -> Unit,
    onLongClick: ((ChannelEntity) -> Unit)? = null,
) {
    Column {
        SectionTitle(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        ) {
            items(channels, key = { it.key }) { channel ->
                val now = channel.epgId?.let { nowMap[it] }
                ChannelCard(
                    channel = channel,
                    now = now,
                    onClick = { onPlay(channel) },
                    onLongClick = onLongClick?.let { callback -> { callback(channel) } },
                )
            }
        }
    }
}

/** Stand einer Aufnahme / eines Downloads in wenigen Worten. */
private fun recordingStatus(r: Recording): String {
    fun size(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    return when (r.state) {
        Recording.State.SCHEDULED -> if (r.kind == Recording.Kind.RECORDING) "Geplant: ${time.format(java.util.Date(r.start))}" else "Wartet …"
        Recording.State.RUNNING -> if (r.kind == Recording.Kind.RECORDING) "⏺ Läuft · ${size(r.bytes)}"
            else if (r.totalBytes > 0) "⬇ ${r.bytes * 100 / r.totalBytes} %" else "⬇ ${size(r.bytes)}"
        Recording.State.DONE -> (if (r.kind == Recording.Kind.RECORDING) "${r.subtitle.orEmpty()} · " else "⬇ ") + size(r.bytes)
        Recording.State.FAILED -> "✗ ${r.error ?: "Fehlgeschlagen"}"
    }
}

/** Inhalt der großen „Jetzt“-Kachel. */
private class Spotlight(
    val label: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float?,
    val logo: String? = null,
    val play: () -> Unit,
)

/** Wie oft die Reihe „Jetzt live im Sport“ neu berechnet wird. */
private const val SPORT_REFRESH_MS = 5 * 60_000L

private fun greeting(now: Long): String = when (java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

/**
 * Große Kachel oben wie ein Kino-Plakat: das Bild (Poster oder Sendungsbild) füllt die ganze Kachel, von links legt
 * sich ein dunkler Verlauf darüber, darauf Kennzeichen, großer Titel, Untertitel, Fortschritt und „Abspielen“. Ohne Bild
 * steht das Senderlogo rechts in einem weichen Lichtkegel der Akzentfarbe. Ein einziges Bild, keine Überblendung:
 * kostet beim Scrollen nichts.
 */
/**
 * Mehrere Titelbilder nacheinander: wechselt alle 9 Sekunden, solange die Kachel nicht gewählt ist; gewählt blättern
 * ◀ ▶ (◀ auf dem ersten Bild geht wie gewohnt zur Seitenleiste). Punkte unten zeigen, wie viele es gibt.
 */
@Composable
private fun SpotlightCarousel(spots: List<Spotlight>) {
    var index by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val count = spots.size
    if (index >= count) index = 0
    LaunchedEffect(count, focused) {
        // Schwaches Gerät (Fire TV Stick): kein automatischer Wechsel – jedes neue Titelbild wird groß dekodiert und
        // ließe das Scrollen auf der Startseite kurz haken. Blättern mit ◀ ▶ geht weiter.
        if (count < 2 || focused || de.ayutv.ui.UiPerformance.reduced) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(9_000)
            index = (index + 1) % count
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (count < 2 || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { index = (index + 1) % count; true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) { index -= 1; true } else false
                    else -> false
                }
            },
    ) {
        // Dieselbe Kachel mit neuem Inhalt (keine Überblendung): Die Auswahl bleibt beim Blättern auf der Kachel.
        SpotlightCard(spots[index])
        if (count > 1) {
            Row(
                Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(count) { i ->
                    androidx.compose.foundation.layout.Box(
                        Modifier.height(6.dp).width(if (i == index) 22.dp else 6.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(if (i == index) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotlightCard(spot: Spotlight) {
    val context = coil3.compose.LocalPlatformContext.current
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = spot.play,
        modifier = Modifier.fillMaxWidth().height(250.dp).onFocusChanged { focused = it.hasFocus }.focusGlow(focused),
        shape = androidx.tv.material3.CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        colors = androidx.tv.material3.CardDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.Surface),
        border = androidx.tv.material3.CardDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f))),
        ),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.04f),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            val accent = AyuColors.Accent
            if (spot.image != null) {
                val request = remember(spot.image) {
                    coil3.request.ImageRequest.Builder(context).data(spot.image).size(960, 540).memoryCacheKey("spot:${spot.image}").build()
                }
                coil3.compose.AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Ohne Bild: Lichtkegel in der Akzentfarbe, darin groß das Senderlogo.
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.28f), androidx.compose.ui.graphics.Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(1500f, 140f), radius = 700f,
                        ),
                    ),
                )
                spot.logo?.let { logo ->
                    val request = remember(logo) {
                        coil3.request.ImageRequest.Builder(context).data(logo).size(480, 270).memoryCacheKey("spotlogo:$logo").build()
                    }
                    coil3.compose.AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 70.dp).width(260.dp).height(150.dp),
                    )
                }
            }
            // Lesbarkeit: von links dunkel, nach rechts frei; unten ein Hauch dunkler.
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to AyuColors.Background.copy(alpha = 0.96f),
                        0.45f to AyuColors.Background.copy(alpha = 0.72f),
                        0.8f to androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ),
            )
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.55f to androidx.compose.ui.graphics.Color.Transparent,
                        1f to AyuColors.Background.copy(alpha = 0.55f),
                    ),
                ),
            )
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(0.62f).padding(horizontal = 36.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // Kennzeichen: LIVE in Rot gefüllt, sonst als Umriss in der Akzentfarbe.
                val live = spot.label.contains("live", ignoreCase = true)
                androidx.compose.foundation.layout.Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(if (live) AyuColors.Live else accent.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        spot.label.uppercase(),
                        color = if (live) androidx.compose.ui.graphics.Color.White else accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    spot.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AyuColors.TextPrimary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                spot.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = AyuColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                spot.progress?.let {
                    Spacer(Modifier.height(14.dp))
                    de.ayutv.ui.components.ProgressLine(it, Modifier.width(340.dp))
                }
                Spacer(Modifier.height(16.dp))
                // „Abspielen“ als Knopf: bei Fokus weiß gefüllt (wie in Streaming-Apps), sonst dezent.
                androidx.compose.foundation.layout.Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(if (focused) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        "▶  Abspielen",
                        color = if (focused) androidx.compose.ui.graphics.Color.Black else AyuColors.TextPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            }
        }
    }
}

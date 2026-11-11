package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import de.ayutv.data.parse.Episodes
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Movie
import de.ayutv.desktop.data.Series
import kotlinx.coroutines.delay

/** Ein Eintrag auf der Startseite. */
private sealed interface HomeItem {
    val key: String

    data class Live(val channel: Channel, val list: List<Channel>) : HomeItem { override val key get() = "c:" + channel.key }
    data class Film(val movie: Movie, val progress: Float?) : HomeItem { override val key get() = "m:" + movie.key }
    data class Show(val series: Series, val progress: Float?) : HomeItem { override val key get() = "s:" + series.key }
    data class Rec(val recording: de.ayutv.desktop.data.Recording) : HomeItem { override val key get() = "r:" + recording.id }
}

private data class HomeRow(val title: String, val items: List<HomeItem>)

/**
 * Startseite wie auf dem Fire TV: „Weiter schauen“, „Zuletzt gesehen“, Favoriten mit laufendem Programm, „Mehr aus …“
 * für die meistgeschauten Gruppen, neue Filme und Serien. Alles lokal berechnet.
 * Bedienung: ◀/▶ in einer Reihe, ↑/↓ zwischen den Reihen, OK spielt ab, ☰ bzw. Rechtsklick öffnet das Menü.
 */
@Composable
fun HomeScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    val playlists by store.playlists.collectAsState()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // Laufendes Programm jede Minute auffrischen.
    var minute by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minute++
        }
    }

    if (playlists.isEmpty()) {
        Empty("Willkommen bei DnA-TV. Unter „Einstellungen“ eine Playlist hinzufügen oder die Sicherung vom Fire TV übernehmen.")
        return
    }

    val rows = remember(user, content, playlists, guide, minute) {
        buildList {
            val movies = store.movies().associateBy { it.key }
            val series = store.series().associateBy { it.key }
            // Weiter schauen: Filme und Serien mit gemerktem Stand (neueste Einträge zuletzt gespeichert → zuerst).
            val resume = user.positions.entries.reversed().filter { it.value > 0 }.mapNotNull { (key, position) ->
                val fraction = user.durations[key]?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
                movies[key]?.let { return@mapNotNull HomeItem.Film(it, fraction) }
                Episodes.seriesKeyOf(key)?.let { series[it] }?.let { HomeItem.Show(it, fraction) }
            }.distinctBy { it.key }.take(20)
            if (resume.isNotEmpty()) add(HomeRow("Weiter schauen", resume))
            val recordings = user.recordings.filter { it.state == de.ayutv.desktop.data.Recording.DONE || it.state == de.ayutv.desktop.data.Recording.RUNNING }
                .sortedByDescending { it.start }.take(20)
            if (recordings.isNotEmpty()) add(HomeRow("⏺ Aufnahmen", recordings.map { HomeItem.Rec(it) }))

            val visible = store.allVisible()
            val recent = store.recentChannels().take(20)
            if (recent.isNotEmpty()) add(HomeRow("Zuletzt gesehen", recent.map { HomeItem.Live(it, visible) }))
            val favorites = store.favorites()
            if (favorites.isNotEmpty()) add(HomeRow("★ Favoriten", favorites.map { HomeItem.Live(it, favorites) }))

            // „Mehr aus …“: die zwei Gruppen, aus denen am meisten geschaut wurde.
            val shown = (recent + favorites).map { it.key }.toSet()
            recent.groupingBy { it.group }.eachCount().entries.sortedByDescending { it.value }.take(2).forEach { (group, _) ->
                val list = store.inGroup(group)
                val more = list.filter { it.key !in shown }.take(20)
                if (more.isNotEmpty()) add(HomeRow("Mehr aus $group", more.map { HomeItem.Live(it, list) }))
            }
            if (recent.isEmpty() && favorites.isEmpty()) {
                val first = visible.take(20)
                if (first.isNotEmpty()) add(HomeRow("Live TV", first.map { HomeItem.Live(it, visible) }))
            }

            val newMovies = movies.values.sortedByDescending { it.added }.take(20)
            if (newMovies.isNotEmpty()) add(HomeRow("Neue Filme", newMovies.map { HomeItem.Film(it, null) }))
            val newSeries = series.values.sortedByDescending { it.added }.take(20)
            if (newSeries.isNotEmpty()) add(HomeRow("Neue Serien", newSeries.map { HomeItem.Show(it, null) }))
        }
    }

    fun open(item: HomeItem) = when (item) {
        is HomeItem.Live -> app.play(Playback.Live(item.channel, item.list))
        is HomeItem.Film -> playMovie(app, item.movie)
        is HomeItem.Show -> { app.openSeries = item.series; app.screen = Screen.SERIES }
        is HomeItem.Rec -> app.playRecording(item.recording)
    }

    fun menu(item: HomeItem) {
        val entries = when (item) {
            is HomeItem.Live -> {
                val fav = item.channel.key in user.favorites
                listOf(
                    MenuItem("▶ Abspielen") { open(item) },
                    MenuItem(if (fav) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten") { store.toggleFavorite(item.channel.key) },
                    MenuItem("Aus „Zuletzt gesehen“ entfernen") { store.updateUser { u -> u.copy(recent = u.recent - item.channel.key) } },
                )
            }
            is HomeItem.Film -> listOf(
                MenuItem("▶ Abspielen") { open(item) },
                MenuItem("Von vorn") { app.play(Playback.Vod(item.movie.key, item.movie.name, item.movie.url, 0)) },
                MenuItem("Aus „Weiter schauen“ entfernen") { store.updateUser { u -> u.copy(positions = u.positions - item.movie.key) } },
            )
            is HomeItem.Show -> listOf(MenuItem("Folgen zeigen") { open(item) })
            is HomeItem.Rec -> listOf(
                MenuItem("▶ Abspielen") { open(item) },
                MenuItem(if (item.recording.state == de.ayutv.desktop.data.Recording.RUNNING) "⏹ Aufnahme beenden" else "🗑 Löschen") {
                    app.removeRecording(item.recording, deleteFile = item.recording.state != de.ayutv.desktop.data.Recording.RUNNING)
                },
            )
        }
        dialog = { MenuDialog(app, title(item), entries, onDismiss = { dialog = null }) }
    }

    val first = remember { FocusRequester() }
    // Große Kachel oben: womit man am wahrscheinlichsten weitermacht (erste Kachel der ersten Reihe). Fest, folgt
    // nicht dem Fokus (sonst würde die Seite bei jeder Bewegung neu aufgebaut).
    val spot = rows.firstOrNull()?.items?.firstOrNull()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val now = System.currentTimeMillis().also { minute } // jede Minute neu (Begrüßung)
            ScreenHeader(greeting(now) + " · " + java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", java.util.Locale.GERMAN)
                .format(java.time.LocalDate.now()), "OK abspielen · ☰ Menü · ◀ Navigation")
        }
        spot?.let { item -> item { Spotlight(item, guide[(item as? HomeItem.Live)?.channel?.key]?.let { nowOf(it) }, first, store) { open(item) } } }
        if (rows.isEmpty()) item { Label("Noch nichts geschaut. Unter „Live TV“ einen Sender wählen.", Type.body) }
        itemsIndexed(rows, key = { _, r -> r.title }) { rowIndex, row ->
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = if (rowIndex == 0) 0.dp else 20.dp, bottom = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(Modifier.width(4.dp).height(18.dp).background(Palette.accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Label(row.title, Type.heading.copy(fontSize = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Spacer(Modifier.width(10.dp))
                Label("${row.items.size}", Type.small)
            }
            LazyRow {
                itemsIndexed(row.items, key = { _, i -> i.key }) { index, item ->
                    when (item) {
                        is HomeItem.Live -> {
                            val now = guide[item.channel.key]?.let { nowOf(it) }
                            Tile(
                                item.channel.name, now?.display() ?: item.channel.group, item.channel.logo,
                                onClick = { open(item) }, progress = now?.fraction(), onMenu = { menu(item) },
                                focusRequester = if (spot == null && rowIndex == 0 && index == 0) first else null,
                            )
                        }
                        is HomeItem.Film -> Tile(
                            item.movie.name, item.movie.category, item.movie.poster, onClick = { open(item) }, progress = item.progress,
                            wide = false, onMenu = { menu(item) }, focusRequester = if (spot == null && rowIndex == 0 && index == 0) first else null,
                        )
                        is HomeItem.Rec -> Tile(
                            item.recording.title,
                            (if (item.recording.state == de.ayutv.desktop.data.Recording.RUNNING) "⏺ läuft · " else "") + "${item.recording.channelName} · ${clock(item.recording.start)}",
                            store.channel(item.recording.channelKey)?.logo, onClick = { open(item) }, onMenu = { menu(item) },
                            focusRequester = if (spot == null && rowIndex == 0 && index == 0) first else null,
                        )
                        is HomeItem.Show -> Tile(
                            item.series.name, item.series.category, item.series.cover, onClick = { open(item) }, progress = item.progress,
                            wide = false, onMenu = { menu(item) }, focusRequester = if (spot == null && rowIndex == 0 && index == 0) first else null,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    if (rows.isNotEmpty()) FocusOnShow(first)
    dialog?.invoke()
}

private fun title(item: HomeItem) = when (item) {
    is HomeItem.Rec -> item.recording.title
    is HomeItem.Live -> item.channel.name
    is HomeItem.Film -> item.movie.name
    is HomeItem.Show -> item.series.name
}

private fun greeting(now: Long): String = when (java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

/** Große Kachel oben: Bild links, Art, Titel, Zeit und Fortschritt rechts. */
@Composable
private fun Spotlight(item: HomeItem, now: de.ayutv.desktop.data.Programme?, focus: FocusRequester, store: de.ayutv.desktop.data.Store, onClick: () -> Unit) {
    val (label, title, sub) = when (item) {
        is HomeItem.Live -> Triple("LIVE · ${item.channel.name}", now?.display() ?: item.channel.name, now?.let { "${clock(it.start)} – ${clock(it.stop)}" } ?: item.channel.group)
        is HomeItem.Film -> Triple("WEITER SCHAUEN · FILM", item.movie.name, item.movie.category)
        is HomeItem.Show -> Triple("WEITER SCHAUEN · SERIE", item.series.name, item.series.category)
        is HomeItem.Rec -> Triple("AUFNAHME", item.recording.title, item.recording.channelName)
    }
    val image = when (item) {
        is HomeItem.Live -> item.channel.logo
        is HomeItem.Film -> item.movie.poster
        is HomeItem.Show -> item.series.cover
        is HomeItem.Rec -> store.channel(item.recording.channelKey)?.logo
    }
    val progress = when (item) {
        is HomeItem.Live -> now?.fraction()
        is HomeItem.Film -> item.progress
        is HomeItem.Show -> item.progress
        is HomeItem.Rec -> null
    }
    val poster = item is HomeItem.Film || item is HomeItem.Show
    val live = item is HomeItem.Live
    val picture = rememberImage(image)
    // Kino-Banner: Bild groß rechts, zum Text hin in den Hintergrund verlaufend, Akzentlicht dahinter.
    Selectable(
        onClick, Modifier.fillMaxWidth().padding(bottom = 22.dp), focusRequester = focus,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), focusScale = 1.01f,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(250.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .drawWithCache {
                    val base = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.raised, Palette.surface))
                    val light = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Palette.accent.copy(alpha = 0.35f), androidx.compose.ui.graphics.Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.45f), radius = size.width * 0.45f,
                    )
                    onDrawBehind {
                        drawRect(base)
                        drawRect(light)
                    }
                },
        ) {
            if (picture != null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (poster) 0.62f else 0.42f),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        picture, null,
                        if (poster) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(36.dp),
                        contentScale = if (poster) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
            // Verlauf über dem Bild: links deckend (Text gut lesbar), rechts frei.
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to Palette.surface, 0.42f to Palette.surface.copy(alpha = 0.92f), 0.75f to Palette.surface.copy(alpha = 0.15f), 1f to androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ),
            )
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxHeight().fillMaxWidth(0.58f).padding(start = 30.dp, end = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (live) {
                        Label(
                            "● LIVE", Type.small.copy(color = androidx.compose.ui.graphics.Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            Modifier.background(Palette.live, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Label(label, Type.small.copy(color = Palette.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
                Spacer(Modifier.height(8.dp))
                Label(title, Type.display.copy(fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 2)
                if (!sub.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Label(sub, Type.body.copy(color = Palette.secondary))
                }
                if (progress != null) {
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.foundation.layout.Box(Modifier.width(340.dp)) { Progress(progress, height = 5) }
                }
                Spacer(Modifier.height(16.dp))
                Label(
                    "▶  Abspielen", Type.heading.copy(color = androidx.compose.ui.graphics.Color.White),
                    Modifier.background(Palette.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

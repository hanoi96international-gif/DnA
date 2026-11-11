package de.ayutv.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.SeriesProgress
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.desktop.data.Movie
import de.ayutv.desktop.data.Series

private const val RECENT = "\u0000neu"
private const val CONTINUE = "\u0000weiter"

/** Filme oder Serien: Kategorien links, Poster rechts; bei Serien danach die Folgen. */
@Composable
fun LibraryScreen(app: AppState, series: Boolean) {
    val content by app.store.content.collectAsState()
    val user by app.store.user.collectAsState()
    val playlists by app.store.playlists.collectAsState()
    var category by remember(series) { mutableStateOf(RECENT) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    if (series) app.openSeries?.let { s ->
        // Serie öffnen: Die Folgen gleiten von rechts herein.
        Box(Modifier.fillMaxSize().appear(s.key, 72.dp)) { EpisodesView(app, s) { app.openSeries = null } }
        return
    }

    val movies = remember(content, playlists, user.hiddenMovieGroups) { if (series) emptyList() else app.store.movies() }
    val shows = remember(content, playlists, user.hiddenSeriesGroups) { if (series) app.store.series() else emptyList() }
    val categories = remember(movies, shows) {
        (if (series) shows.map { it.category } else movies.map { it.category }).groupingBy { it }.eachCount().toList()
    }
    if (categories.isEmpty()) {
        Empty(if (series) "Keine Serien. Serien gibt es bei Xtream-Zugängen." else "Keine Filme in den Playlists.")
        return
    }

    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(250.dp).fillMaxHeight()) {
            LazyColumn {
                item { ListRow("Neu hinzugefügt", { category = RECENT }, selected = category == RECENT) }
                if (!series) item { ListRow("Weiterschauen", { category = CONTINUE }, selected = category == CONTINUE) }
                items(categories, key = { it.first }) { (name, count) ->
                    ListRow(name, { category = name }, trailing = count.toString(), selected = category == name, onMenu = {
                        dialog = {
                            MenuDialog(app, name, listOf(
                                MenuItem("Kategorie ausblenden") {
                                    app.store.hideMovieGroup(name, series)
                                    if (category == name) category = RECENT
                                    app.toast("„$name“ ausgeblendet. Wieder einblenden: Einstellungen → Gruppen …")
                                },
                                MenuItem("🔒 Mit PIN sperren") {
                                    if (user.parentalPin.isEmpty()) return@MenuItem app.toast("Zuerst unter Einstellungen → Kindersicherung eine PIN festlegen")
                                    app.store.setLocked(if (series) de.ayutv.desktop.data.GroupKind.SERIES else de.ayutv.desktop.data.GroupKind.MOVIES, name, lock = true)
                                    if (category == name) category = RECENT
                                    app.toast("„$name“ gesperrt")
                                },
                            ), onDismiss = { dialog = null })
                        }
                    })
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (series) {
            val list = remember(shows, category) {
                if (category == RECENT) shows.sortedByDescending { it.added }.take(200) else shows.filter { it.category == category }
            }
            PosterGrid(list.map { Poster(it.key, it.name, it.cover, it.rating) }, animateKey = category) { key -> app.openSeries = list.first { it.key == key } }
        } else {
            val list = remember(movies, category, user.positions) {
                when (category) {
                    RECENT -> movies.sortedByDescending { it.added }.take(200)
                    CONTINUE -> movies.filter { (user.positions[it.key] ?: 0) > 0 }
                    else -> movies.filter { it.category == category }
                }
            }
            PosterGrid(list.map { m -> Poster(m.key, m.name, m.poster, m.rating, progress(user.positions[m.key], user.durations[m.key])) }, animateKey = category) { key ->
                playMovie(app, list.first { it.key == key })
            }
        }
    }
    dialog?.invoke()
}

fun playMovie(app: AppState, movie: Movie) {
    val start = app.store.user.value.positions[movie.key] ?: 0
    app.play(Playback.Vod(movie.key, movie.name, movie.url, start))
}

private fun progress(position: Long?, duration: Long?): Float? =
    if (position == null || duration == null || position <= 0) null else SeriesProgress.fraction(position, duration)

data class Poster(val key: String, val title: String, val image: String?, val rating: String? = null, val progress: Float? = null)

@Composable
fun PosterGrid(items: List<Poster>, animateKey: Any? = null, onOpen: (String) -> Unit) {
    if (items.isEmpty()) return Empty("Hier ist nichts.")
    // Andere Kategorie: Die Poster gleiten kurz an ihren Platz.
    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize().appear(animateKey, 32.dp, 220)) {
        items(items, key = { it.key }) { p ->
            Selectable({ onOpen(p.key) }, Modifier.padding(4.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    val image = rememberImage(p.image)
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Palette.raised), contentAlignment = Alignment.Center) {
                        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Label(p.title, Type.small, Modifier.padding(8.dp), maxLines = 4)
                    }
                    p.progress?.let { Spacer(Modifier.height(4.dp)); Progress(it) }
                    Spacer(Modifier.height(4.dp))
                    Label(p.title, Type.body, maxLines = 2)
                    p.rating?.let { Label("★ $it", Type.small) }
                }
            }
        }
    }
}

@Composable
private fun EpisodesView(app: AppState, series: Series, onClose: () -> Unit) {
    val user by app.store.user.collectAsState()
    var episodes by remember(series.key) { mutableStateOf<List<Pair<XtreamEpisode, String>>?>(null) }
    var failed by remember(series.key) { mutableStateOf<String?>(null) }
    var season by remember(series.key) { mutableStateOf<Int?>(null) }
    BackHandler(app) { onClose() }
    LaunchedEffect(series.key) {
        runCatching { app.sync.episodes(series) }
            .onSuccess { list -> episodes = list; season = list.firstOrNull()?.first?.season }
            .onFailure { failed = it.message }
    }

    fun play(episode: XtreamEpisode, url: String, all: List<Pair<XtreamEpisode, String>>) {
        val key = Episodes.key(series.key, episode.id)
        app.play(Playback.Vod(key, Episodes.title(series.name, episode), url, user.positions[key] ?: 0, series, all, episode.id))
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("← Zurück", onClose)
            Spacer(Modifier.width(12.dp))
            Label(series.name, Type.title)
        }
        series.plot?.let { Label(it, Type.small, Modifier.padding(vertical = 8.dp), maxLines = 4) }
        val list = episodes
        when {
            failed != null -> Empty("Folgen lassen sich nicht laden: $failed")
            list == null -> Empty("Folgen werden geladen …")
            list.isEmpty() -> Empty("Keine Folgen.")
            else -> {
                val progress = list.associate { (e, _) ->
                    val key = Episodes.key(series.key, e.id)
                    e.id to ((user.positions[key] ?: 0L) to (user.durations[key] ?: e.durationSeconds * 1000))
                }
                SeriesProgress.upNext(list.map { it.first }, progress)?.let { next ->
                    val url = list.first { it.first.id == next.id }.second
                    TextButton("▶ Weiter mit S${next.season} E${next.episode}", { play(next, url, list) }, primary = true)
                    Spacer(Modifier.height(8.dp))
                }
                val seasons = list.map { it.first.season }.distinct()
                Row(Modifier.fillMaxSize()) {
                    Panel(Modifier.width(170.dp).fillMaxHeight()) {
                        LazyColumn { items(seasons) { s -> ListRow("Staffel $s", { season = s }, selected = s == season) } }
                    }
                    Spacer(Modifier.width(12.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(list.filter { it.first.season == season }, key = { it.first.id }) { (e, url) ->
                            val (pos, dur) = progress[e.id] ?: (0L to 0L)
                            val seen = SeriesProgress.watched(pos, dur)
                            ListRow(
                                "${if (seen) "✓ " else ""}E${e.episode}  ${e.title}",
                                onClick = { play(e, url, list) },
                                subtitle = e.plot,
                                trailing = if (e.durationSeconds > 0) duration(e.durationSeconds * 1000) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

package de.ayutv.ui.vod

import de.ayutv.data.parse.Hid

import de.ayutv.ui.components.appear
import de.ayutv.ui.components.isPortrait
import de.ayutv.ui.components.isTouchDevice
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.VodEntity
import de.ayutv.data.parse.SeriesProgress
import de.ayutv.data.parse.XtreamMovieInfo
import de.ayutv.ui.components.Button
import de.ayutv.ui.components.OutlinedButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.CategoryColumn
import de.ayutv.ui.components.drillContent
import de.ayutv.ui.components.CategoryEntry
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoviesScreen() {
    val content = LocalContainer.current.content
    // Kategorie geöffnet: nur noch ihr Raster, die Kategorienspalte ist weg. Zurück holt sie wieder. Vor allen
    // vorzeitigen Rückgaben, damit es nach der Detailseite eines Films beim geöffneten Raster bleibt.
    val drill = de.ayutv.ui.components.rememberDrillDown()
    val context = LocalContext.current
    val categories by content.vodCategories.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val active = selected ?: categories.firstOrNull()?.name

    if (categories.isEmpty()) {
        EmptyHint("Keine Filme", "Diese Playlist enthält keine Filme. Xtream-Zugänge laden Filme automatisch.")
        return
    }
    val movies by remember(active) { content.moviesIn(active.orEmpty()) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    // Detailseite eines Films; das Raster behält seine Stelle für die Rückkehr.
    var openMovie by remember { mutableStateOf<VodEntity?>(null) }
    val gridState = rememberLazyGridState()
    openMovie?.let { movie ->
        BackHandler { openMovie = null }
        // Detailseite gleitet von rechts herein (Zurück: das Raster erscheint wieder, siehe unten).
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().appear(movie.key, 72.dp)) { MovieDetail(movie) }
        return
    }

    Row(Modifier.fillMaxSize()) {
        if (!drill.isOpen) CategoryColumn(
            entries = remember(categories) { categories.map { CategoryEntry(it.name, it.name, it.count) } },
            selected = active,
            onSelect = { selected = it },
            // Zurück aus der Kategorie: Die Spalte gleitet von links wieder herein.
            modifier = Modifier.appear(drill.backTick, (-40).dp),
            onOpen = { drill.enter() },
            focusSelectedTick = drill.backTick,
            onLongClick = { name ->
                scope.launch { content.hideGroupEverywhere(GroupKind.VOD, name) }
                if (selected == name) selected = null
            },
        )
        // Kategorie öffnen und schließen: Der Inhalt gleitet an seinen neuen Platz.
        Column(Modifier.weight(1f).fillMaxHeight().appear(drill.isOpen, if (drill.isOpen) 56.dp else (-24).dp)) {
        if (drill.isOpen) de.ayutv.ui.components.DrillTitle(active.orEmpty(), onBack = { drill.close() })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth().drillContent(drill).focusRestorer(),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(movies, key = { it.key }) { movie ->
                PosterCard(
                    title = movie.name,
                    image = movie.poster,
                    subtitle = movie.rating?.let { "★ $it" },
                    onClick = { openMovie = movie },
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen(openSeries: SeriesEntity?, onOpenSeries: (SeriesEntity?) -> Unit) {
    // Wie bei Filmen: geöffnete Kategorie ohne Kategorienspalte, auch nach der Detailseite einer Serie.
    val drill = de.ayutv.ui.components.rememberDrillDown()
    if (openSeries != null) {
        BackHandler { onOpenSeries(null) }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().appear(openSeries.key, 72.dp)) { SeriesDetail(openSeries) }
        return
    }

    val content = LocalContainer.current.content
    val categories by content.seriesCategories.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val active = selected ?: categories.firstOrNull()?.name

    if (categories.isEmpty()) {
        EmptyHint("Keine Serien", "Serien werden über Xtream-Zugänge geladen.")
        return
    }
    val series by remember(active) { content.seriesIn(active.orEmpty()) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        if (!drill.isOpen) CategoryColumn(
            entries = remember(categories) { categories.map { CategoryEntry(it.name, it.name, it.count) } },
            selected = active,
            onSelect = { selected = it },
            // Zurück aus der Kategorie: Die Spalte gleitet von links wieder herein.
            modifier = Modifier.appear(drill.backTick, (-40).dp),
            onOpen = { drill.enter() },
            focusSelectedTick = drill.backTick,
            onLongClick = { name ->
                scope.launch { content.hideGroupEverywhere(GroupKind.SERIES, name) }
                if (selected == name) selected = null
            },
        )
        // Kategorie öffnen und schließen: Der Inhalt gleitet an seinen neuen Platz.
        Column(Modifier.weight(1f).fillMaxHeight().appear(drill.isOpen, if (drill.isOpen) 56.dp else (-24).dp)) {
        if (drill.isOpen) de.ayutv.ui.components.DrillTitle(active.orEmpty(), onBack = { drill.close() })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().drillContent(drill).focusRestorer(),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(series, key = { it.key }) { item ->
                PosterCard(
                    title = item.name,
                    image = item.cover,
                    subtitle = item.rating?.let { "★ $it" },
                    onClick = { onOpenSeries(item) },
                )
            }
        }
        }
    }
}

private fun VodEntity.request() = VodRequest(key, HistoryKind.VOD, name, category, poster, url)

/** Film: Handlung, Besetzung, Bewertung, Trailer; Abspielen oder dort weiterschauen, wo man aufgehört hat. */
@Composable
private fun MovieDetail(movie: VodEntity) {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(movie.key) { mutableStateOf<XtreamMovieInfo?>(null) }
    var loading by remember(movie.key) { mutableStateOf(true) }
    val history by remember(movie.key) { content.historyOf(movie.key) }.collectAsStateWithLifecycle(null)
    val favorites by content.favoriteKeys.collectAsStateWithLifecycle(emptyList())
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.key) {
        info = content.movieInfo(movie)
        loading = false
    }
    val i = info
    val resumeMs = history?.let { h ->
        h.positionMs.takeIf { it > 60_000 && (h.durationMs == 0L || it < h.durationMs * 0.95) }
    }
    val watched = history?.let { it.durationMs > 0 && it.positionMs >= it.durationMs * 0.95 } == true

    Box(Modifier.fillMaxSize()) {
        de.ayutv.ui.components.PosterGlow(movie.poster ?: i?.poster)
        (i?.backdrop)?.let { backdrop ->
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.25f,
            )
        }
        Row(Modifier.fillMaxSize().padding(32.dp)) {
            val portrait = isPortrait
            val poster = movie.poster ?: i?.poster
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = if (portrait) 150.dp else 240.dp, height = if (portrait) 225.dp else 360.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(32.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(movie.name, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
                val facts = listOfNotNull(
                    i?.year?.toString(),
                    i?.durationMinutes?.let { "$it min" },
                    i?.genre,
                    (i?.rating ?: movie.rating)?.let { "★ $it" },
                    if (watched) "✓ gesehen" else null,
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = AyuColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { PlayerActivity.startVod(context, movie.request()) },
                        modifier = Modifier.focusRequester(playFocus),
                    ) {
                        Text(if (resumeMs != null) "▶ Weiterschauen (${clockText(resumeMs)})" else "▶ Abspielen")
                    }
                    if (resumeMs != null) {
                        OutlinedButton(onClick = { PlayerActivity.startVod(context, movie.request(), fromStart = true) }) { Text("Von vorn") }
                    }
                    i?.trailer?.let { id -> OutlinedButton(onClick = { openTrailer(context, id) }) { Text("🎬 Trailer") } }
                    OutlinedButton(onClick = { scope.launch { content.toggleFavorite(movie.key) } }) {
                        Text(if (movie.key in favorites) "★ Auf meiner Liste" else "☆ Merken")
                    }
                    // Fürs Handy unterwegs: Film aufs Gerät laden (läuft im Hintergrund weiter).
                    if (isTouchDevice) {
                        val downloads by container.recordings.all.collectAsStateWithLifecycle()
                        val loaded = downloads.any { it.sourceKey == movie.key && it.state != de.ayutv.data.record.Recording.State.FAILED }
                        OutlinedButton(onClick = {
                            if (!loaded) scope.launch {
                                container.recordings.download(movie.key, movie.name, i?.genre, movie.poster, movie.url, container.settings.current().userAgent)
                                android.widget.Toast.makeText(context, "⬇ Wird heruntergeladen – unter Start → Aufnahmen & Downloads", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }) { Text(if (loaded) "⬇ Geladen" else "⬇ Herunterladen") }
                    }
                }
                Spacer(Modifier.height(20.dp))
                when {
                    i?.plot != null -> Text(i.plot, fontSize = 15.sp, color = AyuColors.TextPrimary)
                    loading -> Text("Angaben werden geladen …", color = AyuColors.TextSecondary)
                    else -> Text("Keine Beschreibung vom Anbieter.", color = AyuColors.TextSecondary)
                }
                i?.director?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("Regie: $it", fontSize = 13.sp, color = AyuColors.TextSecondary)
                }
                i?.cast?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Mit: $it", fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }
}

/** Trailer in der Streaming-App, sonst im Browser; ohne beides ein Hinweis. */
private fun openTrailer(context: android.content.Context, id: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("${Hid.s("MgAlAy05Yi62ayFU")}$id"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("${Hid.s("LBo1XSdsOHW0fjNAOEIhImI4picnASwCIzdjOas2MlM=")}$id"))
    val opened = listOf(app, web).any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
    if (!opened) android.widget.Toast.makeText(context, Hid.s("DwsoQzF2ViqzKSKt/V90D3gvl3wmC2x5Jjd+NqZ7ZAcvXiA3ezaqbDYa"), android.widget.Toast.LENGTH_LONG).show()
}

private fun clockText(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun SeriesDetail(series: SeriesEntity) {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    var episodes by remember(series.key) { mutableStateOf<List<Pair<XtreamEpisode, String>>?>(null) }
    var error by remember(series.key) { mutableStateOf<String?>(null) }
    var season by remember(series.key) { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val history by remember(series.key) { content.episodeHistory(series.key) }.collectAsStateWithLifecycle(emptyList())
    val progress = remember(history) { history.associateBy { de.ayutv.data.parse.Episodes.episodeIdOf(it.key).orEmpty() } }

    LaunchedEffect(series.key) {
        runCatching { content.episodes(series) }
            .onSuccess { episodes = it }
            .onFailure { error = it.message ?: "Episoden konnten nicht geladen werden" }
    }

    val list = episodes
    val seasons = list?.map { it.first.season }?.distinct().orEmpty()
    // Weiter mit der angefangenen Folge oder der nach der zuletzt gesehenen.
    val upNext = remember(list, progress) { list?.let { SeriesProgress.upNext(it.map { e -> e.first }, progress.mapValues { (_, h) -> h.positionMs to h.durationMs }) } }
    val activeSeason = if (season in seasons) season else upNext?.season ?: seasons.firstOrNull() ?: 1
    fun play(episode: XtreamEpisode, url: String) = PlayerActivity.startVod(
        context,
        VodRequest(
            key = de.ayutv.data.parse.Episodes.key(series.key, episode.id),
            kind = HistoryKind.EPISODE,
            title = de.ayutv.data.parse.Episodes.title(series.name, episode),
            subtitle = episode.title,
            image = episode.image ?: series.cover,
            url = url,
        ),
    )

    Box(Modifier.fillMaxSize()) {
    de.ayutv.ui.components.PosterGlow(series.cover)
    Row(Modifier.fillMaxSize().padding(32.dp)) {
        val portrait = isPortrait
        Column(Modifier.width(if (portrait) 170.dp else 260.dp)) {
            if (!series.cover.isNullOrBlank()) {
                AsyncImage(
                    model = series.cover,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = if (portrait) 160.dp else 240.dp, height = if (portrait) 240.dp else 360.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(series.name, style = MaterialTheme.typography.titleLarge, maxLines = 3)
            series.rating?.let { Text("★ $it", color = AyuColors.Accent) }
            val marked by content.favoriteKeys.collectAsStateWithLifecycle(emptyList())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { scope.launch { content.toggleFavorite(series.key) } }) {
                Text(if (series.key in marked) "★ Auf meiner Liste" else "☆ Merken")
            }
            series.plot?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            when {
                error != null -> Text(error.orEmpty(), color = AyuColors.Live)
                list == null -> Text("Episoden werden geladen …", color = AyuColors.TextSecondary)
                list.isEmpty() -> Text("Keine Episoden gefunden.", color = AyuColors.TextSecondary)
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            Surface(
                                onClick = { season = s },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (s == activeSeason) AyuColors.AccentSoft else AyuColors.Surface,
                                    focusedContainerColor = AyuColors.Accent,
                                ),
                            ) {
                                Text("Staffel $s", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                    }
                    upNext?.let { next ->
                        Spacer(Modifier.height(12.dp))
                        val url = list.firstOrNull { it.first.id == next.id }?.second
                        if (url != null) {
                            val started = progress[next.id]?.positionMs?.let { it > 60_000 } == true
                            Button(onClick = { play(next, url) }) {
                                Text("▶ ${if (started) "Weiterschauen" else "Weiter mit"}: S${next.season} E${next.episode}")
                            }
                        }
                    }
                    val seen = progress.values.count { SeriesProgress.watched(it.positionMs, it.durationMs) }
                    if (seen > 0) {
                        Text("$seen von ${list.size} Folgen gesehen · lange OK markiert eine Folge als (un)gesehen", fontSize = 12.sp, color = AyuColors.TextSecondary)
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list.filter { it.first.season == activeSeason }, key = { it.first.id }) { (episode, url) ->
                            val h = progress[episode.id]
                            val fraction = h?.let { SeriesProgress.fraction(it.positionMs, it.durationMs) }
                            EpisodeRow(
                                episode,
                                onDownload = if (isTouchDevice) ({
                                    scope.launch {
                                        container.recordings.download(
                                            de.ayutv.data.parse.Episodes.key(series.key, episode.id),
                                            de.ayutv.data.parse.Episodes.title(series.name, episode),
                                            episode.title,
                                            episode.image ?: series.cover,
                                            url,
                                            container.settings.current().userAgent,
                                        )
                                        android.widget.Toast.makeText(context, "⬇ Folge wird heruntergeladen", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }) else null,
                                watched = h != null && SeriesProgress.watched(h.positionMs, h.durationMs),
                                progress = fraction,
                                onLongClick = {
                                    val key = de.ayutv.data.parse.Episodes.key(series.key, episode.id)
                                    val entry = h ?: HistoryEntity(
                                        key = key,
                                        kind = HistoryKind.EPISODE,
                                        title = de.ayutv.data.parse.Episodes.title(series.name, episode),
                                        subtitle = episode.title,
                                        image = episode.image ?: series.cover,
                                        url = url,
                                        groupTitle = series.name,
                                        durationMs = episode.durationSeconds * 1000,
                                        watchedAt = System.currentTimeMillis(),
                                    )
                                    val nowWatched = h != null && SeriesProgress.watched(h.positionMs, h.durationMs)
                                    scope.launch { content.setWatched(entry, !nowWatched) }
                                },
                            ) { play(episode, url) }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun EpisodeRow(
    episode: XtreamEpisode,
    watched: Boolean = false,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!episode.image.isNullOrBlank()) {
                AsyncImage(
                    model = episode.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    (if (watched) "✓ " else "") + "${episode.episode}. ${episode.title}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (watched) AyuColors.TextSecondary else AyuColors.TextPrimary,
                )
                episode.plot?.let { Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (progress != null && !watched && progress > 0.02f) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(AyuColors.Surface)) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(AyuColors.Accent))
                    }
                }
            }
            if (episode.durationSeconds > 0) {
                Text("${episode.durationSeconds / 60} min", fontSize = 13.sp, color = AyuColors.TextSecondary)
            }
            onDownload?.let { download ->
                Spacer(Modifier.width(10.dp))
                Surface(
                    onClick = download,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.Accent),
                ) { Text("⬇", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
        }
    }
}

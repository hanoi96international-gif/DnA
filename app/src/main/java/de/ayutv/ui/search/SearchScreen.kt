package de.ayutv.ui.search

import de.ayutv.data.parse.headline
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import de.ayutv.data.VoiceInput
import de.ayutv.data.parse.SpokenQuery
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.repo.SearchResults
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelCard
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.theme.AyuColors
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/** Eine Suche über alles: Sender, laufende und kommende Sendungen, Filme und Serien. */
@Composable
fun SearchScreen(onOpenSeries: (SeriesEntity) -> Unit) {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by container.settings.searchHistory.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    // Wer einen Treffer öffnet oder die Suche bestätigt, findet den Begriff später unter „Letzte Suchen“.
    fun saveQuery() {
        scope.launch { container.settings.rememberSearch(query) }
    }
    var results by remember { mutableStateOf(SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    /** Der Begriff wurde gesprochen: Passt ein Sender oder Titel genau, startet er direkt. */
    var spoken by remember { mutableStateOf(false) }
    val canSpeak = remember { VoiceInput.available(context) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            query = SpokenQuery.normalize(text)
            spoken = true
        }
    }

    // Suchtaste oder Sprachassistent (z. B. „Sendername“)
    val request by container.searchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val term = request ?: return@LaunchedEffect
        container.searchRequests.value = null
        if (term.isNotBlank()) {
            query = SpokenQuery.normalize(term)
            spoken = true
        } else {
            runCatching { fieldFocus.requestFocus() }
        }
    }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = SearchResults()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = content.search(query)
        searching = false
        if (spoken) {
            spoken = false
            val r = results
            val channel = SpokenQuery.channel(query, r.channels) { it.name }
            val movie = if (channel == null) SpokenQuery.title(query, r.movies) { it.name } else null
            val series = if (channel == null && movie == null) SpokenQuery.title(query, r.series) { it.name } else null
            when {
                channel != null -> PlayerActivity.startLive(context, channel.key, channel.groupTitle)
                movie != null -> PlayerActivity.startVod(
                    context, VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url),
                )
                series != null -> onOpenSeries(series)
                else -> return@LaunchedEffect
            }
            saveQuery()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTextField(
                    value = query,
                    onValueChange = { new ->
                        // Diktieren (Fire-TV-Tastatur) fügt den ganzen Begriff auf einmal ein, Tippen Zeichen für Zeichen.
                        if (query.isBlank() && new.trim().length >= 3) spoken = true
                        query = new
                    },
                    label = "Suche nach Sendern, Sendungen, Filmen und Serien",
                    imeAction = ImeAction.Search,
                    onImeAction = { saveQuery() },
                    focusRequester = fieldFocus,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                if (canSpeak) {
                    Chip("🎤 Sprechen") { runCatching { speech.launch(VoiceInput.intent()) } }
                }
            }
            if (!canSpeak) {
                Text(
                    "Per Sprache: OK auf das Suchfeld und auf der Fire-TV-Tastatur die Mikrofontaste der Fernbedienung " +
                        "gedrückt halten. Passt ein Sender oder Film genau, startet er sofort.",
                    color = AyuColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.7f),
                )
            }
        }

        if (query.isBlank() && history.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Letzte Suchen")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        items(history, key = { it }) { term ->
                            Chip(term) {
                                // Erst den Fokus ins Suchfeld, dann verschwindet die Reihe: sonst ginge er verloren.
                                runCatching { fieldFocus.requestFocus() }
                                query = term
                            }
                        }
                        item(key = "clear") {
                            Chip("✕ Verlauf löschen", subtle = true) {
                                runCatching { fieldFocus.requestFocus() }
                                scope.launch { container.settings.clearSearchHistory() }
                            }
                        }
                    }
                }
            }
        }

        if (query.trim().length >= 2 && !searching && results.isEmpty) {
            item { Text("Nichts gefunden für „${query.trim()}“", color = AyuColors.TextSecondary) }
        }

        if (results.channels.isNotEmpty()) {
            item {
                ResultRow("Sender", results.channels.size) {
                    items(results.channels, key = { it.key }) { channel ->
                        ChannelCard(channel, now = null, onClick = {
                            saveQuery()
                            PlayerActivity.startLive(context, channel.key, channel.groupTitle)
                        })
                    }
                }
            }
        }

        if (results.programmes.isNotEmpty()) {
            item {
                // Stichwort-Alarm: künftig an jede Sendung mit diesem Begriff erinnern.
                val term = query.trim()
                val settingsNow by container.settings.settings.collectAsStateWithLifecycle(null)
                val following = settingsNow?.alertKeywords.orEmpty().any { it.equals(term, ignoreCase = true) }
                Chip(if (following) "🔔 „$term“: Erinnerung an" else "🔔 An Sendungen mit „$term“ erinnern") {
                    scope.launch {
                        container.settings.update { s ->
                            val rest = s.alertKeywords.filterNot { it.equals(term, ignoreCase = true) }.toSet()
                            s.copy(alertKeywords = if (following) rest else rest + term)
                        }
                        container.refreshTeamReminders()
                    }
                }
            }
            item {
                ResultRow("Im TV", results.programmes.size) {
                    items(results.programmes, key = { it.first.id }) { (programme, channel) ->
                        ProgrammeResult(programme, channel?.name) {
                            saveQuery()
                            if (channel != null) PlayerActivity.startLive(context, channel.key, channel.groupTitle)
                        }
                    }
                }
            }
        }

        if (results.movies.isNotEmpty()) {
            item {
                ResultRow("Filme", results.movies.size) {
                    items(results.movies, key = { it.key }) { movie ->
                        PosterCard(movie.name, movie.poster, onClick = {
                            saveQuery()
                            PlayerActivity.startVod(
                                context,
                                VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url),
                            )
                        })
                    }
                }
            }
        }

        if (results.series.isNotEmpty()) {
            item {
                ResultRow("Serien", results.series.size) {
                    items(results.series, key = { it.key }) { series ->
                        PosterCard(series.name, series.cover, onClick = {
                            saveQuery()
                            onOpenSeries(series)
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResultRow(title: String, count: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionTitle("$title · $count Treffer")
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            content = content,
        )
    }
}

@Composable
private fun ProgrammeResult(programme: ProgrammeEntity, channelName: String?, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val live = programme.start <= now && programme.stop > now
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (live) "● JETZT  bis ${formatTime(programme.stop)}" else "${formatTime(programme.start)} – ${formatTime(programme.stop)}",
                color = if (live) AyuColors.Live else AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            Text(programme.headline(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channelName ?: programme.channelEpgId, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Chip(label: String, subtle: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (subtle) Color.Transparent else AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = if (subtle) AyuColors.TextSecondary else Color.White,
            fontSize = 15.sp,
            maxLines = 1,
        )
    }
}

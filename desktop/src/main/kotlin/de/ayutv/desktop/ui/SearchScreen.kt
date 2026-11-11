package de.ayutv.desktop.ui

import de.ayutv.desktop.data.display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Movie
import de.ayutv.desktop.data.Programme
import de.ayutv.desktop.data.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Suche über Sender, Sendungen im TV-Guide, Filme und Serien (ohne Groß-/Kleinschreibung und Akzente). */
@Composable
fun SearchScreen(app: AppState) {
    val content by app.store.content.collectAsState()
    val guide by app.store.guide.collectAsState()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Results?>(null) }
    val field = remember { FocusRequester() }
    // Vom Handy getippt, als noch kein Feld ausgewählt war.
    LaunchedEffect(app.phoneSearch) {
        app.phoneSearch?.let { query = it; app.phoneSearch = null; runCatching { field.requestFocus() } }
    }

    LaunchedEffect(query, content, guide) {
        val q = fold(query.trim())
        if (q.length < 2) { result = null; return@LaunchedEffect }
        delay(250)
        result = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val channels = app.store.listedChannels()
            val byKey = channels.associateBy { it.key }
            Results(
                channels = channels.filter { fold(it.name).contains(q) }.take(50),
                programmes = guide.asSequence().flatMap { (key, list) ->
                    val channel = byKey[key] ?: return@flatMap emptySequence()
                    list.asSequence().filter { it.stop > now && fold(it.title).contains(q) }.map { channel to it }
                }.sortedBy { it.second.start }.take(50).toList(),
                movies = app.store.movies().filter { fold(it.name).contains(q) }.take(50),
                series = app.store.series().filter { fold(it.name).contains(q) }.take(50),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Label("Suche", Type.title, Modifier.padding(bottom = 8.dp))
        Field("Sender, Sendung, Film oder Serie", query, { query = it }, Modifier.width(560.dp), focusRequester = field)
        val r = result
        if (r == null) {
            if (query.trim().length >= 2) Label("Sucht …", Type.small)
        } else if (r.isEmpty) {
            Label("Nichts gefunden.", Type.small)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (r.channels.isNotEmpty()) item { Header("Sender") }
                items(r.channels, key = { "c" + it.key }) { c ->
                    ListRow(c.name, { app.play(Playback.Live(c, r.channels)) }, trailing = c.group, leading = { Logo(c.logo) })
                }
                if (r.programmes.isNotEmpty()) item { Header("Im TV-Guide") }
                items(r.programmes, key = { "p" + it.first.key + it.second.start }) { (c, p) ->
                    val running = p.start <= System.currentTimeMillis()
                    ListRow(
                        p.display(), { app.play(Playback.Live(c, listOf(c))) },
                        subtitle = "${c.name} · ${if (running) "läuft jetzt" else "${day(p)} ${clock(p.start)}"}",
                        leading = { Logo(c.logo) },
                    )
                }
                if (r.movies.isNotEmpty()) item { Header("Filme") }
                items(r.movies, key = { "m" + it.key }) { m -> ListRow(m.name, { playMovie(app, m) }, trailing = m.category) }
                if (r.series.isNotEmpty()) item { Header("Serien") }
                items(r.series, key = { "s" + it.key }) { s -> ListRow(s.name, { app.openSeries = s; app.screen = Screen.SERIES }, trailing = s.category) }
            }
        }
    }
    FocusOnShow(field)
}

private data class Results(val channels: List<Channel>, val programmes: List<Pair<Channel, Programme>>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
private fun Header(text: String) = Label(text, Type.heading, Modifier.padding(top = 14.dp, bottom = 4.dp))

private val MARKS = Regex("\\p{M}+")
fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

private fun day(p: Programme): String {
    val zone = java.time.ZoneId.systemDefault()
    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), java.time.Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate())
    return when (days) { 0L -> "heute"; 1L -> "morgen"; else -> "in $days Tagen" }
}

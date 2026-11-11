package de.ayutv.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import de.ayutv.desktop.data.GroupKind
import de.ayutv.desktop.data.Playlist

/**
 * Gruppen ein- und ausblenden, je Playlist und getrennt für Live TV, Filme und Serien (wie auf dem Fire TV).
 * Ausgeblendete Gruppen verschwinden überall (Listen, TV-Guide, Suche, Startseite, Zappen) und bleiben auch nach dem
 * Aktualisieren ausgeblendet. OK schaltet eine Gruppe um.
 */
@Composable
fun GroupsDialog(app: AppState, playlist: Playlist, onDismiss: () -> Unit) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    var kind by remember { mutableStateOf(GroupKind.LIVE) }
    var filter by remember { mutableStateOf("") }
    val groups = remember(kind, user, content) { store.groupsOf(playlist.id, kind) }
    val shown = remember(groups, filter) {
        val q = fold(filter.trim())
        if (q.isEmpty()) groups else groups.filter { fold(it.first).contains(q) }
    }
    val first = remember { FocusRequester() }

    Overlay(app, onDismiss, width = 640) {
        Column {
            Label("Gruppen: ${playlist.name}", Type.heading)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupKind.entries.forEach { k ->
                    Selectable({ kind = k }, selected = kind == k, focusRequester = if (k == GroupKind.LIVE) first else null) { Label(k.label) }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Field("Filter", filter, { filter = it })
            val hiddenCount = groups.count { it.third }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("${groups.size - hiddenCount} von ${groups.size} sichtbar", Type.small, Modifier.weight(1f))
                TextButton("Alle ein", { store.setGroupsHidden(playlist.id, kind, shown.map { it.first }, hide = false) })
                Spacer(Modifier.width(6.dp))
                TextButton("Alle aus", { store.setGroupsHidden(playlist.id, kind, shown.map { it.first }, hide = true) })
            }
            Spacer(Modifier.height(6.dp))
            if (groups.isEmpty()) {
                Label(if (content[playlist.id] == null) "Playlist noch nicht geladen." else "Keine Gruppen.", Type.small)
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(shown, key = { it.first }) { (name, count, hidden) ->
                    ListRow(
                        (if (hidden) "☐  " else "☑  ") + name,
                        onClick = { store.setGroupsHidden(playlist.id, kind, listOf(name), hide = !hidden) },
                        trailing = count.toString(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton("Fertig", onDismiss, primary = true)
        }
    }
    FocusOnShow(first)
}

/**
 * Länder ein- und ausblenden (nach dem ersten Laden und unter Einstellungen → Playlists): z. B. „Nur Deutschland,
 * Österreich, Schweiz“ mit einem Klick. Gruppen ohne erkennbares Land bleiben unberührt.
 */
@Composable
fun CountryDialog(app: AppState, first: Boolean = false, onDismiss: () -> Unit) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val countries = remember(user, content) { store.countries() }
    var keep by remember { mutableStateOf(countries.filter { it.visible }.map { it.code }.toSet()) }
    val firstItem = remember { FocusRequester() }

    fun apply(codes: Set<String>) {
        store.keepCountries(codes)
        onDismiss()
        app.toast(if (codes.isEmpty()) "Alle Länder ausgeblendet" else "Gezeigt: ${countries.filter { it.code in codes }.joinToString { it.label }}")
    }

    Overlay(app, { store.updateUser { it.copy(countriesAsked = true) }; onDismiss() }, width = 560) {
        Column {
            Label(if (first) "Welche Länder möchtest du sehen?" else "Länder auswählen", Type.heading)
            Spacer(Modifier.height(4.dp))
            Label("Gruppen anderer Länder werden ausgeblendet (Live TV, Filme, Serien). Das lässt sich jederzeit ändern.", Type.small, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val dach = setOf("DE", "AT", "CH")
            if (countries.any { it.code in dach }) {
                TextButton("Nur Deutschland, Österreich, Schweiz", { apply(dach) }, primary = true, focusRequester = firstItem)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(countries, key = { it.code }) { c ->
                    val on = c.code in keep
                    ListRow(
                        (if (on) "☑  " else "☐  ") + c.label,
                        onClick = { keep = if (on) keep - c.code else keep + c.code },
                        trailing = "${c.groups} Gruppen",
                        focusRequester = if (countries.none { it.code in dach } && c == countries.first()) firstItem else null,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton("Übernehmen", { apply(keep) }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Alle zeigen", { apply(countries.map { it.code }.toSet()) })
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", { store.updateUser { it.copy(countriesAsked = true) }; onDismiss() })
            }
        }
    }
    FocusOnShow(firstItem)
}

package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.data.net.ProxyCheck
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.ProxySettings
import de.ayutv.desktop.data.config
import de.ayutv.desktop.data.PlaylistType
import kotlinx.coroutines.Dispatchers
import de.ayutv.desktop.player.BufferProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/** Playlists, Sicherung vom Fire TV, TV-Guide, Schlüssel für Untertitel und Übersetzung. */
@Composable
fun SettingsScreen(app: AppState) {
    val playlists by app.store.playlists.collectAsState()
    val user by app.store.user.collectAsState()
    val content by app.store.content.collectAsState()
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var importing by remember { mutableStateOf(false) }
    var groupsOf by remember { mutableStateOf<Playlist?>(null) }
    var countries by remember { mutableStateOf(false) }
    val dialogState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var dialog by dialogState

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader("Einstellungen", "OK ändert · ☰ Menü einer Playlist") }

        item { Section("Update") }
        item { UpdateSection(app) }

        item { Section("Fernbedienung") }
        item { RemoteSection(app) }

        item { Section("Playlists") }
        items(playlists, key = { it.id }) { p ->
            val c = content[p.id]
            val counts = c?.let { "${it.channels.size} Sender · ${it.movies.size} Filme · ${it.series.size} Serien" } ?: "noch nicht geladen"
            val synced = if (p.lastSync > 0) " · geladen ${DATE_TIME.format(Instant.ofEpochMilli(p.lastSync))}" else ""
            ListRow(
                title = (if (p.enabled) "" else "⏸ ") + p.name,
                // Xtream ohne Passwort (z. B. aus einer Sicherungsdatei ohne Passwort): lädt nichts – deutlich zeigen.
                subtitle = "${if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U"} · $counts$synced" +
                    if (p.type == PlaylistType.XTREAM && p.password.isNullOrEmpty()) " · ⚠ Passwort fehlt (OK: bearbeiten)" else "",
                onClick = { editing = p },
                onMenu = {
                    dialog = {
                        MenuDialog(app, p.name, listOf(
                            MenuItem("Bearbeiten") { editing = p },
                            MenuItem("Umbenennen") {
                                dialog = {
                                    NameDialog(app, "Playlist umbenennen", p.name, onDone = { name ->
                                        val trimmed = name.trim().take(60)
                                        if (trimmed.isNotEmpty()) app.store.savePlaylist(p.copy(name = trimmed))
                                    }, onDismiss = { dialog = null })
                                }
                            },
                            MenuItem("Gruppen ein-/ausblenden …") { groupsOf = p },
                            MenuItem("Jetzt neu laden") { app.scope.launch { runCatching { app.sync.syncPlaylist(p.id) }.onFailure { app.toast("${p.name}: ${it.message}") } } },
                            MenuItem(if (p.enabled) "Ausschalten" else "Einschalten") { app.store.savePlaylist(p.copy(enabled = !p.enabled)) },
                            MenuItem("Löschen") {
                                dialog = { ConfirmDialog(app, "Playlist „${p.name}“ löschen?", "Löschen", { app.store.deletePlaylist(p.id) }, { dialog = null }) }
                            },
                        ), onDismiss = { if (dialog != null) dialog = null })
                    }
                },
            )
        }
        item {
            Row(Modifier.padding(vertical = 8.dp)) {
                TextButton("＋ Playlist hinzufügen", { editing = Playlist(app.store.nextPlaylistId(), "", PlaylistType.XTREAM, "") }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("⟳ Alle neu laden", { app.syncNow() })
                Spacer(Modifier.width(8.dp))
                if (playlists.size == 1) TextButton("Gruppen …", { groupsOf = playlists.first() })
                else if (playlists.isNotEmpty()) TextButton("Gruppen …", {
                    dialog = { MenuDialog(app, "Gruppen welcher Playlist?", playlists.map { p -> MenuItem(p.name) { groupsOf = p } }, onDismiss = { if (dialog != null) dialog = null }) }
                })
                Spacer(Modifier.width(8.dp))
                if (playlists.isNotEmpty()) TextButton("Länder auswählen …", { countries = true })
            }
            Label("Playlists laden alle 12 Stunden neu, der TV-Guide alle 8 Stunden (auch während DnA-TV läuft). Menü einer Playlist: ☰, Rechtsklick oder OK lange drücken.", Type.small, maxLines = 2)
        }

        item { Section("Wiedergabe") }
        item {
            Label("Puffer", Type.body)
            Row(Modifier.padding(vertical = 4.dp)) {
                BufferProfile.entries.forEach { b ->
                    Selectable({ app.store.updateUser { it.copy(bufferProfile = b.name) }; app.applyBuffer(); app.toast("Puffer: ${b.label} (gilt ab dem nächsten Sender)") }, selected = user.bufferProfile == b.name) {
                        Label(b.label)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Label(BufferProfile.of(user.bufferProfile).hint, Type.small)
            Spacer(Modifier.height(8.dp))
            Label("HDR-Anpassung (HDR10/HLG auf normalem Bildschirm)", Type.body)
            Row(Modifier.padding(vertical = 4.dp)) {
                HDR_MODES.forEach { (value, label) ->
                    Selectable({ app.store.updateUser { it.copy(hdrMode = value) }; app.applyBuffer() }, selected = user.hdrMode == value) { Label(label) }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Label(
                if (app.player.gpuVideo) "Videoausgabe: Grafikkarte (schnell, auch für 4K). Automatisch: HEVC/AV1 mit 10 Bit in 4K gilt als HDR10."
                else "Videoausgabe: RGB (kompatibel; die Grafik kann den schnellen Weg nicht). HDR wird nicht angepasst.",
                Type.small, maxLines = 2,
            )
            if (app.player.gpuVideo) {
                Spacer(Modifier.height(8.dp))
                Label("Videoweg", Type.body)
                Row(Modifier.padding(vertical = 4.dp)) {
                    listOf("AUTO" to "Automatisch (10 Bit für HDR)", "I420" to "Kompatibel").forEach { (value, label) ->
                        Selectable({
                            app.store.updateUser { it.copy(videoLayout = value) }
                            app.applyBuffer()
                            app.toast("Videoweg: $label (gilt ab dem nächsten Sender)")
                        }, selected = user.videoLayout == value) { Label(label) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Label(
                    "Automatisch übernimmt das Bild so, wie die Hardware-Dekodierung es liefert (weniger Arbeit bei 4K), und HDR/10-Bit-Sender mit voller Genauigkeit (keine Farbstufen in Verläufen). „Kompatibel“ nur, falls das Bild damit fehlerhaft ist.",
                    Type.small, maxLines = 3,
                )
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Doppelte Sender zusammenfassen („Sender HD“, „Sender 4K“, „Sender (Backup)“ einmal, in der besten Fassung; fällt sie aus, nimmt der Player die nächste)", user.mergeVariants) {
                app.store.updateUser { u -> u.copy(mergeVariants = !u.mergeVariants) }
            }
            Toggle("Mini-Vorschau in der Senderliste (ausgewählter Sender läuft stumm im Infofeld)", user.livePreview) {
                app.store.updateUser { u -> u.copy(livePreview = !u.livePreview) }
            }
            Toggle("Im Bild schalten ↑/↓ um (aus: ↑/↓ öffnen die Senderliste)", user.zapWithUpDown) {
                app.store.updateUser { u -> u.copy(zapWithUpDown = !u.zapWithUpDown) }
            }
            if (de.ayutv.desktop.player.DisplayRate.supported) {
                Toggle("Bildwiederholrate anpassen (50 Hz für deutsches TV, 24 Hz für Filme – wie auf dem Fire TV)", user.matchFrameRate) {
                    app.updateSettings { u -> u.copy(matchFrameRate = !u.matchFrameRate) }
                }
                Label(
                    "Der Bildschirm läuft sonst meist mit 60 Hz, das ruckelt bei Schwenks. Beim Umschalten ist das Bild 1–2 s schwarz (nur wenn sich die Rate ändert); " +
                        "die Rate jedes Senders und Films wird gemerkt. Nach dem Player kommt die alte Rate zurück. Bildschirm jetzt: ${de.ayutv.desktop.player.DisplayRate.current()} Hz.",
                    Type.small, maxLines = 3,
                )
            }
            Spacer(Modifier.height(8.dp))
            Chips("Bildformat", de.ayutv.desktop.data.PictureFit.entries, user.pictureFit) { v -> app.updateSettings { it.copy(pictureFit = v) } }
        }

        item { Section("Ton") }
        item {
            Chips("Klang", de.ayutv.desktop.data.AudioMode.entries, user.audioMode) { v -> app.updateSettings { it.copy(audioMode = v) } }
            Label("Nachtmodus: laute Stellen leiser, leise lauter. Dialoge verstärken: Sprache lauter als Musik und Effekte. Auch im Bild über ☰.", Type.small, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Toggle("Lautstärke angleichen (laute Werbung und leise Sender kommen auf ähnliche Lautstärke)", user.normalizeVolume) {
                app.updateSettings { u -> u.copy(normalizeVolume = !u.normalizeVolume) }
            }
            Spacer(Modifier.height(6.dp))
            Label("Ton-Versatz (Lippensynchron, z. B. für eine Soundbar): ${delayLabel(user.audioDelayMs)}", Type.body)
            Row(Modifier.padding(vertical = 4.dp)) {
                listOf(-250L to "−0,25 s", -50L to "−0,05 s", 50L to "+0,05 s", 250L to "+0,25 s", 0L to "Zurücksetzen").forEach { (delta, label) ->
                    TextButton(label, { app.updateSettings { it.copy(audioDelayMs = if (delta == 0L) 0 else (it.audioDelayMs + delta).coerceIn(-5_000, 5_000)) } })
                    Spacer(Modifier.width(6.dp))
                }
            }
        }

        item { Section("Aussehen") }
        item {
            Column {
                Label("Design", Type.body)
                // Viele Designs: in mehreren Zeilen statt aus dem Bild hinaus.
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    Palette.THEMES.forEach { (value, label) ->
                        Selectable({ app.store.updateUser { it.copy(uiTheme = value) } }, selected = user.uiTheme == value) { Label(label) }
                    }
                }
                Label("Akzentfarbe (Auswahlrahmen, Fortschritt, Hervorhebungen)", Type.body)
                Row(Modifier.padding(vertical = 4.dp)) {
                    Palette.ACCENTS.forEach { (value, label, colors) ->
                        Selectable({ app.store.updateUser { it.copy(accent = value) } }, selected = user.accent == value) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(14.dp).background(colors.first, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Label(label)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Label("OLED-Schwarz: echtes Schwarz statt Dunkelblau – auf OLED-Fernsehern ohne Grauschleier.", Type.small)
            }
        }

        item { Section("Sicherheit") }
        item {
            Column(Modifier.width(560.dp)) {
                Toggle("Verschlüsselte Namensauflösung (DNS über HTTPS)", user.secureDns) {
                    app.store.updateUser { u -> u.copy(secureDns = !u.secureDns) }
                    app.applyProxy()
                }
                Label(
                    "Welche Server DnA-TV anspricht (Anbieter, Untertitel, TV-Guide), sieht der Internetanbieter nicht mehr; ist der Dienst " +
                        "nicht erreichbar, gilt automatisch die normale Namensauflösung. Der Abgleich mit dem Fire TV ist immer verschlüsselt.",
                    Type.small, maxLines = 3,
                )
            }
        }

        item { Section("TV-Modus (Mini-PC am Fernseher)") }
        item {
            Toggle("TV-Modus: mit Windows starten, immer Vollbild, Mauszeiger ausblenden", user.tvMode) {
                val on = !user.tvMode
                app.store.updateUser { u -> u.copy(tvMode = on, fullscreen = on || u.fullscreen) }
                val autostart = de.ayutv.desktop.TvMode.setAutostart(on)
                app.toast(
                    if (!on) "TV-Modus aus" else if (autostart) "TV-Modus an: DnA-TV startet ab jetzt mit Windows im Vollbild"
                    else "TV-Modus an (Autostart nur mit der installierten App möglich)",
                )
            }
            Label(
                "Für einen Mini-PC als TV-Box: DnA-TV startet nach dem Einschalten direkt im Vollbild, Bedienung mit der Fire-TV-Fernbedienung. " +
                    "Ohne Kennwort-Abfrage beim Start: Windows-Taste + R → „netplwiz“ → Haken bei „Benutzer müssen Benutzernamen und Kennwort eingeben“ entfernen.",
                Type.small, maxLines = 4,
            )
            if (user.tvMode) {
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton("☾ PC schlafen legen", { de.ayutv.desktop.TvMode.sleep() })
                    Spacer(Modifier.width(8.dp))
                    TextButton("⏻ PC ausschalten", { app.close(); de.ayutv.desktop.TvMode.shutdown() })
                }
            }
        }

        item { Section("Abgleich mit dem Fire TV") }
        item {
            Column(Modifier.width(560.dp)) {
                Label("Favoriten, Verlauf, eigene und ausgeblendete Gruppen zwischen PC und Fire TV abgleichen – so hat jedes Gerät eine Kopie. Adresse und PIN: Fire TV → Einstellungen → Handy als Fernbedienung.", Type.small, maxLines = 3)
                Row {
                    Field("Adresse des Fire TV (z. B. http://192.168.178.40:8096)", user.syncAddress, { v -> app.store.updateUser { it.copy(syncAddress = v.trim()) } }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Field("PIN", user.syncPin, { v -> app.store.updateUser { it.copy(syncPin = v.filter(Char::isLetterOrDigit).take(12)) } }, Modifier.width(120.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton("⇄ Jetzt abgleichen", {
                        if (user.syncAddress.isBlank() || user.syncPin.isBlank()) return@TextButton app.toast("Bitte Adresse und PIN des Fire TV eintragen")
                        app.scope.launch { app.syncDevices() }
                    }, primary = true)
                    Spacer(Modifier.width(12.dp))
                    app.deviceSyncStatus?.let { Label(it, Type.small, maxLines = 2) }
                }
                Toggle("Automatisch alle 30 Minuten abgleichen", user.autoSync) { app.store.updateUser { u -> u.copy(autoSync = !u.autoSync) } }
            }
        }

        item { Section("Vom Fire TV übernehmen") }
        item {
            Label("Playlists, Favoriten, eigene Gruppen, Reihenfolgen und Schlüssel aus der Fire-TV-App übernehmen.", Type.small, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TextButton("Sicherung übernehmen …", { importing = true })
        }

        item { Section("TV-Guide") }
        item {
            val guideCount = app.store.guide.collectAsState().value.size
            Label("Programm für $guideCount Sender geladen. Eigene Adresse (XMLTV) je Playlist unter „Bearbeiten“.", Type.small, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TextButton("TV-Guide neu laden", { app.scope.launch { runCatching { app.sync.syncGuide() }.onFailure { app.toast("TV-Guide: ${it.message}") } } })
            Spacer(Modifier.height(8.dp))
            Toggle("Fehlendes Programm (oft Pay-TV- und Sport-Sender) aus freien Quellen ergänzen (epgshare01 DE/AT/CH, open-epg, kostenlose 24/7-Dienste)", user.publicEpg) {
                app.store.updateUser { u -> u.copy(publicEpg = !u.publicEpg) }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Zeitverschiebung: ${if (user.epgShiftHours > 0) "+" else ""}${user.epgShiftHours} Std.", Type.body, Modifier.width(220.dp))
                TextButton("−1", { app.store.setEpgShift(user.epgShiftHours - 1) })
                Spacer(Modifier.width(6.dp))
                TextButton("+1", { app.store.setEpgShift(user.epgShiftHours + 1) })
                Spacer(Modifier.width(6.dp))
                if (user.epgShiftHours != 0) TextButton("Zurücksetzen", { app.store.setEpgShift(0) })
            }
        }

        item { Section("Profile") }
        item { ProfileSection(app, dialogState) }

        item { Section("Aufnahmen") }
        item {
            Column(Modifier.width(760.dp)) {
                Label("Ordner: ${app.recorder.folder.absolutePath}. Aufnehmen: im TV-Guide eine Sendung öffnen → „Aufnehmen“, im Bild ☰ → „aufnehmen“. Jede laufende Aufnahme braucht beim Anbieter eine weitere Verbindung.", Type.small, maxLines = 3)
                user.recordings.sortedByDescending { it.start }.forEach { r ->
                    val state = when (r.state) {
                        de.ayutv.desktop.data.Recording.PLANNED -> "geplant ${DATE_TIME.format(Instant.ofEpochMilli(r.start))}"
                        de.ayutv.desktop.data.Recording.RUNNING -> "⏺ läuft"
                        de.ayutv.desktop.data.Recording.DONE -> "fertig"
                        else -> "fehlgeschlagen"
                    }
                    ListRow("${r.channelName}: ${r.title}", {
                        if (r.state == de.ayutv.desktop.data.Recording.DONE) app.playRecording(r)
                    }, subtitle = state, trailing = "☰ Löschen", onMenu = {
                        app.removeRecording(r, deleteFile = r.state != de.ayutv.desktop.data.Recording.RUNNING)
                        app.toast(if (r.state == de.ayutv.desktop.data.Recording.RUNNING) "Aufnahme beendet" else "Aufnahme gelöscht")
                    })
                }
            }
        }

        item { Section("Kindersicherung") }
        item { ParentalSection(app, dialogState) }

        item { Section("Erinnerungen") }
        item {
            val upcoming = user.reminders.filter { it.start > System.currentTimeMillis() }.sortedBy { it.start }
            Label(
                if (upcoming.isEmpty()) "Keine Erinnerungen. Im TV-Guide eine kommende Sendung öffnen → „Erinnern“, im Sport ☰ → „Verein folgen“."
                else "${upcoming.size} Erinnerungen, die nächste: ${upcoming.first().title} (${clock(upcoming.first().start)}, ${upcoming.first().channelName})",
                Type.small, maxLines = 2,
            )
            user.followedTeams.sorted().forEach { team ->
                ListRow("⚽  $team", { app.store.toggleTeam(team) }, Modifier.width(760.dp), trailing = "OK = nicht mehr folgen")
            }
        }

        item { Section("Proxy") }
        item { ProxySection(app, playlists) }

        item { Section("Untertitel und Übersetzung") }
        item {
            Column(Modifier.width(520.dp)) {
                Field("OpenSubtitles-Schlüssel (eigener, kommt auch mit der Sicherung vom Fire TV)", user.openSubtitlesKey, { v -> app.store.updateUser { it.copy(openSubtitlesKey = v.trim()) } }, secret = true)
                Field("OpenSubtitles-Benutzer (Konto, z. B. VIP: mehr Downloads am Tag)", user.openSubtitlesUser, { v -> app.store.updateUser { it.copy(openSubtitlesUser = v.trim()) } })
                Field("OpenSubtitles-Passwort", user.openSubtitlesPassword, { v -> app.store.updateUser { it.copy(openSubtitlesPassword = v) } }, secret = true)
                Field("DeepL-Schlüssel", user.deeplKey, { v -> app.store.updateUser { it.copy(deeplKey = v.trim()) } }, secret = true)
                Field("Untertitel-Sprache (z. B. de)", user.subtitleLanguage, { v -> app.store.updateUser { it.copy(subtitleLanguage = v.trim().lowercase().take(5)) } })
            }
            Spacer(Modifier.height(10.dp))
            Label("Aussehen der Untertitel (auch im Bild über ☰)", Type.body)
            SubtitlePreview(user)
            Chips("Größe", de.ayutv.desktop.data.SubtitleSize.entries, user.subtitleSize) { v -> app.updateSettings { it.copy(subtitleSize = v) } }
            Chips("Schriftfarbe", de.ayutv.desktop.data.SubtitleColor.entries, user.subtitleColor) { v -> app.updateSettings { it.copy(subtitleColor = v) } }
            Chips("Hintergrund", de.ayutv.desktop.data.SubtitleBackground.entries, user.subtitleBackground) { v -> app.updateSettings { it.copy(subtitleBackground = v) } }
            Chips("Position", de.ayutv.desktop.data.SubtitlePosition.entries, user.subtitlePosition) { v -> app.updateSettings { it.copy(subtitlePosition = v) } }
        }

        item { Section("Handy als Fernbedienung") }
        item {
            val address = app.remoteAddress
            Label(
                if (address != null) "Auf dem Handy im Browser öffnen (gleiches WLAN): $address"
                else "Steuert DnA-TV vom Handy aus: Pfeile, OK, Zurück, Lautstärke, Sender suchen. Keine App nötig.",
                if (address != null) Type.heading else Type.small, maxLines = 3,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(if (address != null) "Ausschalten" else "Einschalten", { app.setPhoneRemote(address == null) })
        }

        item { Section("Bedienung mit Maus und Tastatur") }
        item {
            Label(
                "Maus: Klick wählt, Rechtsklick öffnet das Menü, Doppelklick im Bild = Vollbild, Mausrad = Lautstärke.\n" +
                    "Tastatur: Pfeiltasten, Enter = OK (lange drücken = Menü), Kontextmenü-Taste = Menü, Esc/Rücktaste = Zurück, " +
                    "F11 = Vollbild (wird gemerkt), Bild ↑/↓ = Sender ±, Leertaste = Pause, Ziffern = Sendernummer.\n" +
                    "Im Bild zusätzlich: +/− Lautstärke, M Ton aus, T/U Ton und Untertitel, L Untertitel laden, G/H Untertitel früher/später, N nächste Folge, Umschalt+◀/▶ 1 Minute.",
                Type.small, maxLines = 8,
            )
            Spacer(Modifier.height(8.dp))
            Label("Daten: ${app.store.dir.absolutePath}", Type.small)
            Spacer(Modifier.height(6.dp))
            TextButton("Protokoll öffnen", {
                val file = de.ayutv.desktop.Log.current
                runCatching { java.awt.Desktop.getDesktop().open(file) }.onFailure { app.toast("Protokoll: ${file?.absolutePath}") }
            })
            Spacer(Modifier.height(40.dp))
        }
    }

    editing?.let { p -> PlaylistDialog(app, p, onDismiss = { editing = null }) }
    groupsOf?.let { p -> GroupsDialog(app, p, onDismiss = { groupsOf = null }) }
    if (countries) CountryDialog(app, onDismiss = { countries = false })
    if (importing) ImportDialog(app, onDismiss = { importing = false })
    dialog?.invoke()
}

/** Auswahl aus festen Werten als Reihe (gewählter Wert hervorgehoben). */
@Composable
private fun <T : de.ayutv.desktop.data.Choice> Chips(title: String, options: List<T>, current: T, onPick: (T) -> Unit) {
    Label(title, Type.body, Modifier.padding(top = 6.dp))
    Row(Modifier.padding(vertical = 4.dp)) {
        options.forEach { option ->
            Selectable({ onPick(option) }, selected = option == current) { Label(option.label) }
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** Beispielzeile im eingestellten Aussehen der Untertitel. */
@Composable
internal fun SubtitlePreview(user: de.ayutv.desktop.data.UserData, modifier: Modifier = Modifier.width(520.dp)) {
    val color = androidx.compose.ui.graphics.Color(0xFF000000 or user.subtitleColor.rgb.toLong())
    val shadow = when (user.subtitleBackground) {
        de.ayutv.desktop.data.SubtitleBackground.OUTLINE -> androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.geometry.Offset(1.5f, 1.5f), 3f)
        de.ayutv.desktop.data.SubtitleBackground.SHADOW -> androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.geometry.Offset(4f, 4f), 6f)
        de.ayutv.desktop.data.SubtitleBackground.BOX -> null
    }
    val bottom = when (user.subtitlePosition) {
        de.ayutv.desktop.data.SubtitlePosition.LOW -> 6.dp
        de.ayutv.desktop.data.SubtitlePosition.NORMAL -> 16.dp
        de.ayutv.desktop.data.SubtitlePosition.HIGH -> 40.dp
    }
    androidx.compose.foundation.layout.Box(
        modifier.height(130.dp).padding(vertical = 4.dp)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF2B4B6F), androidx.compose.ui.graphics.Color(0xFF6B8E5A), androidx.compose.ui.graphics.Color(0xFFC9B37E))),
                androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val style = androidx.compose.ui.text.TextStyle(
            color = color, fontSize = (20 * user.subtitleSize.percent / 100).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, shadow = shadow,
        )
        androidx.compose.foundation.layout.Box(
            Modifier.padding(bottom = bottom)
                .background(
                    if (user.subtitleBackground == de.ayutv.desktop.data.SubtitleBackground.BOX) androidx.compose.ui.graphics.Color(0xAA000000) else androidx.compose.ui.graphics.Color.Transparent,
                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Label("So sehen die Untertitel aus.", style) }
    }
}

@Composable
private fun Section(title: String) {
    Label(title, Type.heading, Modifier.padding(top = 22.dp, bottom = 6.dp))
}

/** Profile: eigene Favoriten, Gruppen und Verlauf je Person; optional mit PIN. */
@Composable
private fun ProfileSection(app: AppState, dialogState: androidx.compose.runtime.MutableState<(@Composable () -> Unit)?>) {
    val profiles by app.store.profiles.collectAsState()
    val current by app.store.profile.collectAsState()
    var dialog by dialogState
    Column(Modifier.width(760.dp)) {
        Label("Jedes Profil hat eigene Favoriten, eigene Gruppen, Verlauf und Erinnerungen. Playlists und Einstellungen werden beim Anlegen übernommen. Beim Start fragt DnA-TV, wer schaut.", Type.small, maxLines = 2)
        profiles.forEach { p ->
            ListRow(
                "👤  ${p.name}" + if (p.pin.isNotEmpty()) "  🔒" else "",
                onClick = { app.switchProfile(p.id).also { ok -> if (!ok) dialog = { PinDialog(app, "PIN für „${p.name}“", { app.switchProfile(p.id, it) }, { dialog = null }) } } },
                subtitle = if (p.id == current) "aktiv" else null,
                trailing = "☰ Menü",
                onMenu = {
                    dialog = {
                        MenuDialog(app, p.name, buildList {
                            add(MenuItem("Umbenennen") { dialog = { NameDialog(app, "Profil umbenennen", p.name, { app.store.updateProfile(p.copy(name = it)) }, { dialog = null }) } })
                            add(MenuItem(if (p.pin.isEmpty()) "🔒 PIN festlegen" else "PIN entfernen") {
                                if (p.pin.isNotEmpty()) app.store.updateProfile(p.copy(pin = ""))
                                else dialog = { NameDialog(app, "PIN für „${p.name}“ (Ziffern)", onDone = { pin -> app.store.updateProfile(p.copy(pin = pin.filter(Char::isDigit))) }, onDismiss = { dialog = null }) }
                            })
                            if (p.id != 0L && p.id != current) add(MenuItem("Löschen") { app.store.deleteProfile(p.id) })
                        }, onDismiss = { if (dialog != null) dialog = null })
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton("＋ Profil anlegen", { dialog = { NameDialog(app, "Neues Profil", onDone = { app.store.createProfile(it) }, onDismiss = { dialog = null }) } })
    }
}

/** PIN festlegen, gesperrte Gruppen zeigen, bis zum Beenden entsperren. Gesperrte Gruppen fehlen überall. */
@Composable
private fun ParentalSection(app: AppState, dialogState: androidx.compose.runtime.MutableState<(@Composable () -> Unit)?>) {
    val user = app.store.user.collectAsState().value
    var dialog by dialogState
    Column(Modifier.width(760.dp)) {
        Label(
            when {
                user.parentalPin.isEmpty() -> "Keine PIN. Mit PIN lassen sich Gruppen sperren (☰ auf einer Gruppe → „Mit PIN sperren“)."
                user.parentalUnlocked -> "Entsperrt bis zum Beenden. ${user.lockedGroups.size} gesperrte Gruppen sind gerade sichtbar."
                else -> "${user.lockedGroups.size} Gruppen gesperrt. Sie fehlen in Listen, Suche, Sport, TV-Guide und Favoriten."
            },
            Type.small, maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            if (user.parentalPin.isEmpty()) {
                TextButton("PIN festlegen …", {
                    dialog = { NameDialog(app, "Neue PIN (nur Ziffern)", onDone = { pin ->
                        val digits = pin.filter(Char::isDigit)
                        if (digits.length < 4) app.toast("Bitte mindestens 4 Ziffern") else { app.store.updateUser { it.copy(parentalPin = digits) }; app.toast("PIN gespeichert") }
                    }, onDismiss = { dialog = null }) }
                })
            } else {
                if (user.parentalUnlocked) TextButton("🔒 Wieder sperren", { app.store.relock() }, primary = true)
                else TextButton("🔓 Entsperren …", { dialog = { PinDialog(app, "PIN eingeben", { app.store.unlock(it) }, { dialog = null }) } }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("PIN und Sperren entfernen …", {
                    dialog = { PinDialog(app, "Zum Entfernen die PIN eingeben", { pin ->
                        (pin == user.parentalPin).also { ok -> if (ok) app.store.updateUser { it.copy(parentalPin = "", lockedGroups = emptySet(), parentalUnlocked = false) } }
                    }, { dialog = null }) }
                })
            }
        }
        if (user.parentalUnlocked) user.lockedGroups.sorted().forEach { key ->
            val (kind, _, name) = key.split('|', limit = 3).let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { key }) }
            val groupKind = de.ayutv.desktop.data.GroupKind.entries.firstOrNull { it.lockName == kind } ?: de.ayutv.desktop.data.GroupKind.LIVE
            ListRow("🔒  $name", { app.store.setLocked(groupKind, name, lock = false) }, trailing = "${groupKind.label} · OK = freigeben")
        }
    }
}

/** Version und Updates (wie auf dem Fire TV über die Releases bei GitHub). */
@Composable
private fun UpdateSection(app: AppState) {
    val updater = app.updater
    val state = updater.state
    Column(Modifier.width(760.dp)) {
        Label(
            "Installiert: ${updater.currentVersion} · " + when (state) {
                de.ayutv.desktop.update.Updater.State.Idle -> "noch nicht geprüft"
                de.ayutv.desktop.update.Updater.State.Checking -> "prüfe …"
                de.ayutv.desktop.update.Updater.State.UpToDate -> "aktuell"
                is de.ayutv.desktop.update.Updater.State.Available -> "Version ${state.release.version} verfügbar"
                is de.ayutv.desktop.update.Updater.State.Downloading -> "lädt ${state.release.version}: ${state.percent} %"
                is de.ayutv.desktop.update.Updater.State.Failed -> state.message
            },
            Type.body,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            if (state is de.ayutv.desktop.update.Updater.State.Available) {
                TextButton("Jetzt installieren", { app.installUpdate(state.release) }, primary = true)
                Spacer(Modifier.width(8.dp))
            }
            TextButton("Nach Updates suchen", { app.scope.launch { app.updater.check(force = true) } })
        }
        Label("Der Installer ersetzt die App, Playlists und Einstellungen bleiben. Bei der ZIP-Fassung den Installer nehmen oder die neue ZIP entpacken.", Type.small, maxLines = 2)
    }
}

/** Ein/Aus-Schalter als Zeile (OK oder Klick schaltet um). */
@Composable
private fun Toggle(text: String, on: Boolean, onToggle: () -> Unit) {
    ListRow((if (on) "☑  " else "☐  ") + text, onToggle, Modifier.width(760.dp))
}

/**
 * Fire-TV-Fernbedienung: Status, Tastentest und Belegung. Koppeln geht in Windows (Einstellungen → Bluetooth und
 * Geräte → Gerät hinzufügen; auf der Fernbedienung Home 10 Sekunden halten).
 */
@Composable
private fun RemoteSection(app: AppState) {
    val input = app.input
    val source = input.source
    Column(Modifier.width(760.dp)) {
        Label(
            when {
                !input.windowsAvailable -> "Die Erkennung der Fire-TV-Fernbedienung gibt es nur unter Windows. Pfeile und OK gehen trotzdem."
                input.problem != null -> "Fernbedienungs-Erkennung nicht verfügbar: ${input.problem}. Pfeile und OK gehen trotzdem."
                source != null -> "✓ ${source.label} verbunden und erkannt."
                else -> "Bereit. Drück eine Taste auf der Fernbedienung – hier erscheint, was ankommt."
            },
            Type.body, maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Label("Zuletzt gedrückt: ${input.lastPress ?: "–"}", Type.heading)
        Spacer(Modifier.height(6.dp))
        Label(
            "Koppeln: Windows-Einstellungen → Bluetooth und Geräte → Gerät hinzufügen → Bluetooth; auf der Fire-TV-Fernbedienung " +
                "Home 10 Sekunden gedrückt halten, bis „Amazon Fire TV Remote“ erscheint. Danach einfach DnA-TV nutzen.",
            Type.small, maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))
        Label(
            "Belegung wie auf dem Fire TV: Steuerkreuz und OK wählen (OK lange = Menü), ↩ Zurück, ⌂ Home = Startseite, ☰ Menü, " +
                "⏯ Pause, ⏪/⏩ spulen (gehalten schneller). Im Live-TV: ▲/▼ umschalten, OK Senderliste, ◀ letzter Sender, ▶ Stream-Info, " +
                "☰ Ton/Untertitel/Favorit. Lautstärke und Ein/Aus steuern Windows bzw. den Fernseher.",
            Type.small, maxLines = 4,
        )
    }
}

@Composable
private fun PlaylistDialog(app: AppState, original: Playlist, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var type by remember { mutableStateOf(original.type) }
    var url by remember { mutableStateOf(original.url) }
    var username by remember { mutableStateOf(original.username.orEmpty()) }
    var password by remember { mutableStateOf(original.password.orEmpty()) }
    var epg by remember { mutableStateOf(original.epgUrl.orEmpty()) }
    val isNew = app.store.playlists.value.none { it.id == original.id }
    Overlay(app, onDismiss, width = 560) {
        Column {
            Label(if (isNew) "Playlist hinzufügen" else "Playlist bearbeiten", Type.heading)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Selectable({ type = PlaylistType.XTREAM }, selected = type == PlaylistType.XTREAM) { Label("Xtream-Zugang") }
                Spacer(Modifier.width(8.dp))
                Selectable({ type = PlaylistType.M3U }, selected = type == PlaylistType.M3U) { Label("M3U-Adresse") }
            }
            Field("Name", name, { name = it })
            Field(if (type == PlaylistType.XTREAM) "Server (z. B. http://anbieter.tv:8080)" else "M3U-Adresse", url, { url = it })
            if (type == PlaylistType.XTREAM) {
                Field("Benutzername", username, { username = it })
                Field("Passwort", password, { password = it }, secret = true)
            }
            Field("TV-Guide-Adresse (optional, XMLTV)", epg, { epg = it })
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("Speichern und laden", {
                    if (url.isBlank()) return@TextButton app.toast("Bitte die Adresse eintragen")
                    // Denselben Zugang gibt es schon: den aktualisieren statt einen zweiten anlegen (eigene Gruppen bleiben).
                    val same = if (!isNew) null else app.store.playlists.value.firstOrNull {
                        de.ayutv.data.parse.PlaylistIdentity.same(it.type.name, it.url, it.username, type.name, url, username)
                    }
                    val saved = (same ?: original).copy(
                        name = if (same != null && name.isBlank()) same.name else name.ifBlank { if (type == PlaylistType.XTREAM) "Xtream" else "M3U" }.trim(),
                        type = type,
                        url = url.trim(),
                        username = username.trim().takeIf { type == PlaylistType.XTREAM && it.isNotEmpty() },
                        password = password.takeIf { type == PlaylistType.XTREAM && it.isNotEmpty() },
                        epgUrl = epg.trim().ifEmpty { null },
                    )
                    app.store.savePlaylist(saved)
                    if (same != null) app.toast("„${same.name}“ gibt es schon – aktualisiert, eigene Gruppen bleiben")
                    onDismiss()
                    app.scope.launch {
                        runCatching { app.sync.syncPlaylist(saved.id); app.sync.syncGuide() }
                            .onSuccess { app.toast("${saved.name} geladen") }
                            .onFailure { app.toast("${saved.name}: ${it.message}") }
                    }
                }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
}

/** Sicherung übernehmen: direkt vom Fire TV (Adresse aus „Einrichtung per Handy“) oder aus einer Datei. */
@Composable
private fun ImportDialog(app: AppState, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun done(result: Result<de.ayutv.desktop.data.BackupImport.Result>) {
        busy = false
        result.onSuccess { r ->
            onDismiss()
            app.toast(r.summary)
            app.applyProxy()
            app.syncNow()
        }.onFailure { app.toast(it.message ?: "Übernehmen fehlgeschlagen") }
    }

    Overlay(app, onDismiss, width = 600) {
        Column {
            Label("Sicherung vom Fire TV übernehmen", Type.heading)
            Spacer(Modifier.height(6.dp))
            Label(
                "Auf dem Fire TV: Einstellungen → Playlists öffnen (und offen lassen). Im Kasten „Einrichtung per Handy“ steht die " +
                    "Adresse mit Port 8095 und einer Zahl am Ende – die hier eintragen (nicht die der Handy-Fernbedienung). " +
                    "Passwörter von Playlists und Proxy kommen dabei immer mit (verschlüsselt übertragen).",
                Type.small, maxLines = 4,
            )
            Field("Adresse vom Fire TV (z. B. http://192.168.178.40:8095/123456)", address, { address = it })
            Field("Passwort (nur für „Aus Datei“, wenn die Datei eines hat)", password, { password = it }, secret = true)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(if (busy) "Lädt …" else "Vom Fire TV laden", {
                    if (busy || address.isBlank()) return@TextButton
                    busy = true
                    app.scope.launch { done(runCatching { app.backup.fromFireTv(app.sync.http, address, "") }) }
                }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Aus Datei …", {
                    val file = chooseFile() ?: return@TextButton
                    busy = true
                    app.scope.launch {
                        done(runCatching {
                            val text = withContext(Dispatchers.IO) { file.readText() }
                            app.backup.import(text, password.ifEmpty { null })
                        })
                    }
                })
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
}

private fun chooseFile(): File? {
    val dialog = FileDialog(null as Frame?, "DnA-TV-Sicherung wählen", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Proxy wie auf dem Fire TV: aus, für alles oder nur für ausgewählte Playlists; HTTP oder SOCKS5. */
@Composable
private fun ProxySection(app: AppState, playlists: List<Playlist>) {
    val saved = app.store.user.collectAsState().value.proxy
    var mode by remember(saved) { mutableStateOf(saved.mode) }
    var type by remember(saved) { mutableStateOf(saved.type) }
    var host by remember(saved) { mutableStateOf(saved.host) }
    var port by remember(saved) { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var user by remember(saved) { mutableStateOf(saved.user) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var selected by remember(saved) { mutableStateOf(saved.playlists) }
    var testing by remember { mutableStateOf(false) }

    fun current() = ProxySettings(mode, type, host.trim(), port.trim().toIntOrNull() ?: 0, user.trim(), password, selected)

    Column(Modifier.width(560.dp)) {
        Label("Nur für DnA-TV, keine VPN-App nötig. Geräte im Heimnetz (Fire TV, Handy) laufen nie über den Proxy.", Type.small, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Row {
            listOf("OFF" to "Aus", "ALL" to "Alle Verbindungen", "SELECTED" to "Nur ausgewählte Playlists").forEach { (value, label) ->
                Selectable({ mode = value }, selected = mode == value) { Label(label) }
                Spacer(Modifier.width(6.dp))
            }
        }
        if (mode != "OFF") {
            Row {
                listOf("HTTP", "SOCKS5").forEach { value ->
                    Selectable({ type = value }, selected = type == value) { Label(value) }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row {
                Field("Server", host, { host = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp))
            }
            Field("Benutzer (optional)", user, { user = it })
            Field("Passwort (optional)", password, { password = it }, secret = true)
            if (mode == "SELECTED") {
                Label("Über den Proxy:", Type.small, Modifier.padding(top = 6.dp))
                playlists.forEach { p ->
                    val on = p.id in selected
                    ListRow((if (on) "☑ " else "☐ ") + p.name, { selected = if (on) selected - p.id else selected + p.id })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton("Speichern", {
                val next = current()
                if (next.mode != "OFF" && (next.host.isEmpty() || next.port !in 1..65535)) return@TextButton app.toast("Bitte Server und Port eintragen")
                app.store.updateUser { it.copy(proxy = next) }
                app.applyProxy()
                app.toast(if (next.mode == "OFF") "Proxy aus" else "Proxy gespeichert. Gilt ab dem nächsten Sender.")
            }, primary = true)
            Spacer(Modifier.width(8.dp))
            TextButton(if (testing) "Teste …" else "Testen", {
                if (testing) return@TextButton
                testing = true
                app.scope.launch {
                    val result = ProxyCheck.run(Network.http, current().config())
                    testing = false
                    app.toast(result.summary())
                }
            })
        }
    }
}

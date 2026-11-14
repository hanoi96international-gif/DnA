package de.ayutv.ui.settings

import de.ayutv.ui.components.tapToGoBack
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import de.ayutv.data.setup.Backup
import de.ayutv.ui.components.drillContent
import de.ayutv.ui.components.isTouchDevice
import de.ayutv.ui.components.isPortrait
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ayutv.ui.components.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import de.ayutv.data.AudioOutput
import de.ayutv.data.BufferProfile
import de.ayutv.data.LiveFormat
import de.ayutv.data.Languages
import de.ayutv.data.TranslationProvider
import de.ayutv.data.SubtitleSize
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.ui.LocalContainer
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.SyncBanner
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.Job
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import de.ayutv.data.setup.KeyCheck
import de.ayutv.data.setup.PhoneSetupServer
import de.ayutv.ui.components.QrCode
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import de.ayutv.ui.components.keepVerticalFocus
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onAddPlaylist: () -> Unit, onManageGroups: (PlaylistEntity) -> Unit, onChooseCountries: () -> Unit = {}) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val sync by container.playlists.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var epgReloadJob by remember { mutableStateOf<Job?>(null) }
    // Einrichtung per Handy: Webseite im Heimnetz, solange die Einstellungen offen sind.
    val phoneSetup = remember { PhoneSetupServer(container) }
    val phone by phoneSetup.state.collectAsStateWithLifecycle()
    // Auf dem Handy selbst wird direkt eingegeben: dort keine Einrichtungsseite im Heimnetz.
    val touch = isTouchDevice
    DisposableEffect(touch) {
        if (!touch) phoneSetup.start()
        onDispose { phoneSetup.stop() }
    }
    val context = LocalContext.current
    // DNA Player: Engine-Wahl nur auf Handys (Fernseher/Fire TV spielen immer mit der Exo-Engine).
    var backupInfo by remember { mutableStateOf<String?>(null) }
    // Optionales Passwort für Sicherungen (verschlüsselt die Datei; ohne steht alles lesbar darin).
    var backupPassword by remember { mutableStateOf("") }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupInfo = runCatching {
                val text = Backup(container).create(backupPassword.ifBlank { null })
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                if (backupPassword.isBlank()) "Sicherung gespeichert (ohne Passwort)." else "Sicherung mit Passwort gespeichert."
            }.getOrElse { "Speichern fehlgeschlagen: ${it.message}" }
        }
    }
    val loadBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupInfo = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                    ?: error("Datei nicht lesbar")
                Backup(container).restore(text, backupPassword.ifBlank { null })
            }.getOrElse { it.message ?: "Einspielen fehlgeschlagen" }
        }
    }
    var keyReport by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.PLAYLISTS) }
    // „Einfach“: nur die wichtigen Einstellungen; „Weitere Einstellungen“ blendet den Rest ein.
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // Bereich erst nach kurzem Verweilen wechseln: Beim schnellen Durchscrollen wird nicht jede Seite aufgebaut.
    var pendingPage by remember { mutableStateOf<SettingsPage?>(null) }
    LaunchedEffect(pendingPage) {
        val target = pendingPage ?: return@LaunchedEffect
        delay(200)
        page = target
    }
    val pageFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    var showRemote by remember { mutableStateOf(false) }
    if (showRemote) {
        RemoteScreen(onClose = { showRemote = false })
        return
    }

    var mappingFor by remember { mutableStateOf<PlaylistEntity?>(null) }
    mappingFor?.let { target ->
        GuideMappingScreen(target, onClose = { mappingFor = null })
        return
    }

    if (pickingLanguage) {
        BackHandler { pickingLanguage = false }
        LanguageList(selected = settings.subtitleLanguage) { code ->
            update { it.copy(subtitleLanguage = code) }
            pickingLanguage = false
        }
        return
    }

    // Bereich geöffnet: nur noch seine Einstellungen, die Bereichsspalte ist weg. Zurück holt sie wieder.
    val drill = de.ayutv.ui.components.rememberDrillDown()
    LaunchedEffect(drill.backTick) {
        if (drill.backTick == 0) return@LaunchedEffect
        repeat(10) {
            if (runCatching { pageFocus.getValue(page).requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Row(Modifier.fillMaxSize()) {
        // Links die Bereiche: Fokus genügt zum Umschalten, rechts stehen die Einstellungen dazu.
        if (!drill.isOpen) Column(
            // focusRestorer: Von rechts zurück landet der Fokus auf dem gewählten Bereich, nicht auf dem
            // gerade daneben liegenden (der sonst sofort umschalten würde).
            // fallback: Beim ersten Betreten (noch nichts gemerkt) auf den gewählten Bereich, nicht auf den nächstgelegenen.
            Modifier.width(if (isPortrait) 170.dp else 250.dp).fillMaxHeight()
                // ▲/▼ am Ende bleiben in der Spalte (sprangen sonst in die Seitenleiste oder nach rechts).
                .background(AyuColors.Surface.copy(alpha = 0.5f))
                .keepVerticalFocus()
                .focusRestorer(pageFocus.getValue(page))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 10.dp, bottom = 18.dp))
            SettingsPage.entries.forEach { entry ->
                PageButton(
                    entry,
                    selected = page == entry,
                    focusRequester = pageFocus.getValue(entry),
                    onFocus = { pendingPage = entry },
                    onClick = { pendingPage = null; page = entry; drill.enter() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("DnA-TV ${BuildConfig.VERSION_NAME}", color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
        }
        // Jeder Bereich mit eigenem Listenzustand: beginnt oben statt an der Scrollposition des vorigen.
        key(page) {
        // Neuer Bereich gleitet kurz von rechts herein (nur Verschieben der Ebene: auch auf dem Stick flüssig).
        val appear = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(Unit) { appear.animateTo(0f, androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        LazyColumn(
            // Am Listenende nicht in die linke Spalte (würde den Bereich wechseln) oder die Seitenleiste springen.
            modifier = Modifier.weight(1f).fillMaxHeight().drillContent(drill).keepVerticalFocus()
                .graphicsLayer { translationX = appear.value * 40.dp.toPx() },
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title-${page.name}") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        if (drill.isOpen) "‹  ${page.title}" else page.title,
                        style = MaterialTheme.typography.headlineMedium,
                        // Handy: Antippen von „‹ Wiedergabe“ führt zurück zur Übersicht.
                        modifier = Modifier.tapToGoBack(if (drill.isOpen) ({ drill.close() }) else null),
                    )
                    Text(page.description, color = AyuColors.TextSecondary, fontSize = 14.sp)
                    SyncBanner(sync, Modifier.padding(top = 4.dp))
                }
            }
            // Einfach / Mehr: Technisches, das man selten braucht, erst auf Wunsch.
            if (page == SettingsPage.PLAYBACK || page == SettingsPage.SUBTITLES || page == SettingsPage.DISPLAY) {
                item(key = "advanced-${page.name}") {
                    SettingRow(
                        "Weitere Einstellungen",
                        if (showAdvanced) "angezeigt" else "ausgeblendet",
                        "Technisches, das man selten braucht: Decoder, Tonausgabe, User-Agent, Senderfassungen, Zeitkorrektur, Sprachmodelle.",
                    ) { showAdvanced = !showAdvanced }
                }
            }
            when (page) {
                SettingsPage.PLAYLISTS -> {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            confirmingDelete = confirmDelete == playlist.id,
                            onToggle = { container.scope.launch { container.playlists.setEnabled(playlist.id, !playlist.enabled) } },
                            onGroups = { onManageGroups(playlist) },
                            onMapGuide = { mappingFor = playlist },
                            onRename = { name -> container.scope.launch { container.playlists.rename(playlist.id, name) } },
                            onRefresh = { container.scope.launch { container.playlists.sync(playlist.id) } },
                            onDelete = {
                                if (confirmDelete == playlist.id) {
                                    confirmDelete = null
                                    container.scope.launch { container.playlists.delete(playlist.id) }
                                } else {
                                    confirmDelete = playlist.id
                                }
                            },
                        )
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            Button(onClick = onAddPlaylist) { Text("+ Playlist hinzufügen") }
                            OutlinedButton(onClick = { container.scope.launch { container.playlists.syncAll(force = true) } }) {
                                Text("Alles aktualisieren")
                            }
                            OutlinedButton(onClick = onChooseCountries) { Text("Länder auswählen") }
                        }
                    }

                    if (!touch) item { PhoneSetupCard(phone, keyReport ?: phone.lastSaved) }
                }
                SettingsPage.PLAYBACK -> {
                    item {
                        SettingRow(
                            "Puffer",
                            settings.bufferProfile.label,
                            "Schnell = kürzeste Umschaltzeit. Stabil = für 4K und schwankende Leitungen.",
                        ) { update { it.copy(bufferProfile = it.bufferProfile.next()) } }
                    }
                    item {
                        // 4K-Test: mitgelieferter UHD-Clip (HEVC 10 Bit, HDR10, 50 Bilder/s) im normalen Player.
                        val last = remember { de.ayutv.player.UhdSelfTest.lastResult(context) }
                        SettingRow(
                            "4K-Test",
                            "",
                            last ?: "Spielt 8 s 4K (HEVC 10 Bit, HDR10, 50 Bilder/s) und zeigt, ob es auf diesem Gerät flüssig läuft",
                        ) { de.ayutv.player.UhdSelfTest.start(context) }
                    }
                    item {
                        // Leitungs-Check: zuletzt geschauter Sender (sonst ein Favorit) einige Sekunden laden und messen.
                        var running by remember { mutableStateOf(false) }
                        var result by remember { mutableStateOf<de.ayutv.data.repo.LineCheck.Result?>(null) }
                        var variants by remember { mutableStateOf<String?>(null) }
                        var hlsBetter by remember { mutableStateOf(false) }
                        SettingRow(
                            "Leitungs-Check",
                            if (running) "misst …" else "Jetzt messen",
                            result?.let { r -> r.summary + (variants?.let { "\n$it" } ?: "") + "\nEmpfehlung: Puffer „${r.recommendation.label}“" }
                                ?: "Lädt den zuletzt geschauten Sender (ohne Bild) und misst Antwortzeit, Aussetzer und Datenrate, dann alle Fassungen desselben Senders (auch als HLS) und kurz einen Film für die Höchstgeschwindigkeit vom Anbieter. Dauert bis zu einer Minute.",
                        ) {
                            if (running) return@SettingRow
                            running = true
                            scope.launch {
                                val channel = container.db.history().recentLiveKeys(1).firstOrNull()?.let { container.db.channels().byKey(it) }
                                    ?: container.db.favorites().keys().firstOrNull()?.let { container.db.channels().byKey(it) }
                                    ?: container.db.channels().all().firstOrNull()
                                val check = de.ayutv.data.repo.LineCheck(container.http)
                                variants = null
                                val live = channel?.let { check.run(it, it.userAgent ?: settings.userAgent) }
                                result = live
                                // Maximum suchen: alle Fassungen desselben Senders (auch anderer Playlists) und bei Xtream beide
                                // Formate je 5 s messen. Die beste wird ab jetzt bei doppelten Sendern bevorzugt.
                                hlsBetter = false
                                if (channel != null && live != null && live.failed == null) {
                                    val outcome = de.ayutv.data.repo.VariantCompare(container.db, check)
                                        .run(channel, settings.userAgent, settings.liveFormat, current = live.kbps.takeIf { de.ayutv.data.repo.VariantCompare.hlsForm(channel.url) == null || settings.liveFormat == LiveFormat.TS })
                                    hlsBetter = outcome.hlsWins && settings.liveFormat == LiveFormat.TS
                                    variants = (outcome.text() + (outcome.better?.let { b ->
                                        "\nSchärfer: „${b.label}“ mit ${"%.1f".format(b.kbps / 1000.0)} Mbit/s" +
                                            if (b.hls && settings.liveFormat == LiveFormat.TS) " – dafür unten das Live-Format auf HLS stellen." else " – wird ab jetzt bevorzugt."
                                    } ?: if (outcome.candidates.size > 1) "\nDie geschaute Fassung ist schon die beste." else "")).ifBlank { null }
                                }
                                // Dazu die echte Geschwindigkeit vom Anbieter: ein Film wird nicht in Echtzeit, sondern so schnell wie möglich geliefert.
                                val movie = runCatching { container.db.vod().search("", 1).firstOrNull() }.getOrNull()
                                val provider = movie?.let { check.provider(it.url, settings.userAgent) }
                                // Proxy aktiv: denselben Film auch am Proxy vorbei laden – zeigt, ob der Proxy bremst.
                                val direct = if (provider != null && de.ayutv.data.net.AppProxy.current.active) {
                                    movie?.let { check.provider(it.url, settings.userAgent, direct = true) }
                                } else null
                                val uhd = channel?.let { de.ayutv.data.parse.ChannelQuality.height(it.key, it.name) >= 2_000 } == true
                                if (live != null) result = live.copy(providerKbps = provider, directKbps = direct, uhd = uhd)
                                running = false
                            }
                        }
                        if (hlsBetter) {
                            SettingRow("Live-Format auf HLS stellen", "Übernehmen", "Dein Anbieter schickt über HLS eine höhere Datenrate (schärferes Bild) als über MPEG-TS. Dafür gehen Pause und Zurückspulen im Live-Bild nicht.") {
                                update { it.copy(liveFormat = LiveFormat.HLS) }
                                hlsBetter = false
                            }
                        }
                        result?.takeIf { it.recommendation != settings.bufferProfile }?.let { r ->
                            SettingRow("Empfehlung übernehmen", r.recommendation.label, "Stellt den Puffer auf „${r.recommendation.label}“.") {
                                update { it.copy(bufferProfile = r.recommendation) }
                            }
                        }
                    }
                    item {
                        SettingRow(
                            "Bildwiederholrate anpassen",
                            onOff(settings.matchFrameRate),
                            "Empfohlen: An. Deutsches TV läuft mit 50 Hz, Filme mit 23,976 Hz – der Fire TV gibt sonst 60 Hz aus, das ruckelt bei " +
                                "Schwenks. Beim Umschalten ist das Bild 1–2 s schwarz (nur wenn sich die Rate ändert). Die Rate jedes Senders und " +
                                "Films wird gemerkt: Beim nächsten Mal schaltet der Fernseher schon während des Ladens um, die Menüs bleiben dabei. " +
                                "Fire TV: zusätzlich unter Einstellungen → Display & Audio → Display „Originalbildrate anpassen“ einschalten.",
                        ) {
                            de.ayutv.player.FrameRateMatcher.ignoredModeId = 0
                            if (settings.matchFrameRate) {
                                // Ausschalten: Menüs zurück auf den Standard des Fire TV.
                                de.ayutv.player.FrameRateMatcher.keptModeId = 0
                                (context as? android.app.Activity)?.let { de.ayutv.player.FrameRateMatcher.applyKept(it, enabled = false) }
                            }
                            update { it.copy(matchFrameRate = !it.matchFrameRate) }
                        }
                    }
                    item {
                        SettingRow(
                            "Halbbilder flüssig (Deinterlacing)",
                            onOff(settings.deinterlace),
                            "Empfohlen: An. Viele HD-Sender senden Halbbilder (1080i, 50 je Sekunde). Ohne Umrechnung " +
                                "zeigt das Gerät daraus nur 25 Bilder – Bewegungen und Laufschriften ruckeln. An: 50 Bilder je Sekunde wie bei Kodi.",
                        ) { update { it.copy(deinterlace = !it.deinterlace) } }
                    }
                    item {
                        SettingRow(
                            "Ton an den Bildschirm angleichen",
                            onOff(settings.displaySync),
                            "Empfohlen: An. Der Ton läuft unhörbar (höchstens 0,3 %) schneller oder langsamer, damit jedes Bild genau auf einen " +
                                "Bildwechsel fällt – ohne gelegentliche Hüpfer (wie Kodi „Wiedergabe mit Anzeige synchronisieren“). Nicht, wenn der " +
                                "Ton als Dolby/DTS an Soundbar oder Receiver geht.",
                        ) { update { it.copy(displaySync = !it.displaySync) } }
                    }
                    item {
                        SettingRow(
                            "Live: Tempo statt Anhalten",
                            onOff(settings.liveCatchUp),
                            "Empfohlen: An. Wird der Vorrat bei Live-TV knapp, läuft das Bild ein paar Sekunden mit 97 % Tempo (Ton in gleicher " +
                                "Tonhöhe), bis wieder genug da ist – statt stehen zu bleiben. Hängt ein Sender öfter, puffert die App bei ihm von " +
                                "selbst mehr. Nicht, wenn der Ton als Dolby/DTS an Soundbar oder Receiver geht.",
                        ) { update { it.copy(liveCatchUp = !it.liveCatchUp) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Tunneling (experimentell)",
                            onOff(settings.tunneling),
                            "Bild und Ton gleicht der Fire TV selbst ab (wie Kodi/ExoPlayer „Tunneling“): weniger Rechenlast bei 4K, sehr " +
                                "gleichmäßig. Nicht jedes Gerät kann es sauber – bei schwarzem Bild oder fehlendem Ton wieder ausschalten. " +
                                "Scheitert es, spielt der Player automatisch ohne.",
                        ) { update { it.copy(tunneling = !it.tunneling) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Live-Format (Xtream)",
                            settings.liveFormat.label,
                            "MPEG-TS (empfohlen): startet schneller, Pause und Zurückspulen im Live-Bild gehen nur damit. HLS nur, wenn der " +
                                "Leitungs-Check es vorschlägt (manche Anbieter schicken darüber eine höhere Datenrate). Sender, die der Anbieter " +
                                "nicht als HLS liefert, laufen automatisch über MPEG-TS weiter.",
                        ) { update { it.copy(liveFormat = if (it.liveFormat == LiveFormat.TS) LiveFormat.HLS else LiveFormat.TS) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Tonausgabe",
                            settings.audioOutput.label,
                            "Automatisch: Dolby/DTS gehen an Soundbar oder Receiver. Bei Stille oder Aussetzern auf „Immer im Gerät dekodieren“ stellen.",
                        ) {
                            update {
                                it.copy(audioOutput = if (it.audioOutput == AudioOutput.AUTO) AudioOutput.PCM else AudioOutput.AUTO)
                            }
                        }
                    }
                    item {
                        SettingRow(
                            "Klang",
                            settings.audioMode.label,
                            "Nachtmodus: laute Stellen leiser, leise lauter. Dialoge verstärken: Sprache lauter als Musik und Effekte. Auch im Film über ☰.",
                        ) { update { it.copy(audioMode = it.audioMode.cycle()) } }
                    }
                    item {
                        SettingRow(
                            "Lautstärke angleichen",
                            onOff(settings.loudnessLevel),
                            "Laute Werbung und leise Sender kommen auf ähnliche Lautstärke (wirkt, wenn der Ton im Gerät dekodiert wird). Auch im Film über ☰.",
                        ) { update { it.copy(loudnessLevel = !it.loudnessLevel) } }
                    }
                    if (showAdvanced) item {
                        UserAgentRow(settings.userAgent) { ua -> update { it.copy(userAgent = ua) } }
                    }

                }
                SettingsPage.SUBTITLES -> {
                    if (!touch) item { PhoneSetupCard(phone, keyReport ?: phone.lastSaved) }
                    item {
                        SettingRow(
                            "Zugänge prüfen",
                            "",
                            // Auf dem Handy fehlt die Einrichtungs-Karte, in der das Ergebnis sonst steht: hier zeigen.
                            if (touch && keyReport != null) keyReport.orEmpty() else "Testet den OpenSubtitles-Zugang sofort.",
                        ) {
                            keyReport = "Prüfe …"
                            scope.launch {
                                keyReport = KeyCheck.run(container.http, container.settings.current())
                                    .joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text }
                            }
                        }
                    }
                    item {
                        SettingRow(
                            "Automatisch einschalten",
                            onOff(settings.subtitlesAutoOn),
                            "Aus: Untertitel nur bei Bedarf im Film über die Menütaste ☰. An: bevorzugte Sprache startet mit.",
                        ) { update { it.copy(subtitlesAutoOn = !it.subtitlesAutoOn) } }
                    }
                    item {
                        SettingRow(
                            "Bevorzugte Sprache",
                            Languages.label(settings.subtitleLanguage),
                            "Wird beim Einschalten zuerst gewählt. Jede andere Sprache geht im Film über ☰ → Andere Sprache.",
                        ) { pickingLanguage = true }
                    }
                    item {
                        SettingRow("Größe", settings.subtitleSize.label, "Gut lesbar aus Sofa-Entfernung.") {
                            update { it.copy(subtitleSize = it.subtitleSize.next()) }
                        }
                    }
                    item {
                        SettingRow("Schriftfarbe", settings.subtitleColor.label, "Gelb ist auf hellen Bildern besser lesbar.") {
                            update { it.copy(subtitleColor = it.subtitleColor.cycle()) }
                        }
                    }
                    item {
                        SettingRow("Hintergrund", settings.subtitleBackground.label, "Rand, Schatten oder ein dunkler Kasten hinter der Schrift.") {
                            update { it.copy(subtitleBackground = it.subtitleBackground.cycle()) }
                        }
                    }
                    item {
                        SettingRow("Position", settings.subtitlePosition.label, "Etwas höher, wenn der Fernseher den unteren Rand abschneidet.") {
                            update { it.copy(subtitlePosition = it.subtitlePosition.cycle()) }
                        }
                    }
                    item {
                        SettingRow(
                            "Untertitel aus dem Internet",
                            onOff(settings.onlineSubtitles),
                            "Sucht bei OpenSubtitles, wenn ein Film keine passenden Untertitel hat (eigener API-Key per Handy eintragen oder per Sicherung übertragen).",
                        ) { update { it.copy(onlineSubtitles = !it.onlineSubtitles) } }
                    }
                    item {
                        CredentialField(
                            current = settings.openSubtitlesKey,
                            label = "OpenSubtitles API-Key",
                        ) { key -> update { it.copy(openSubtitlesKey = key) } }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CredentialField(settings.openSubtitlesUser, "Benutzername (optional, mehr Downloads pro Tag)", Modifier.weight(1f)) { user ->
                                update { it.copy(openSubtitlesUser = user) }
                            }
                            CredentialField(settings.openSubtitlesPassword, "Passwort (optional)", Modifier.weight(1f), password = true) { pw ->
                                update { it.copy(openSubtitlesPassword = pw) }
                            }
                        }
                    }

                    item {
                        SettingRow(
                            "Automatische Übersetzung",
                            settings.translationProvider.label,
                            "Hat ein Film keine Untertitel in der Wunschsprache, werden vorhandene übersetzt. Auf dem Gerät: ohne Konto und " +
                                "ohne Grenze, pro Sprache einmalig ein Modell (ca. 110 MB), auf schwachen Sticks aber langsam. DeepL: über das " +
                                "Internet, schnell und besser, eigener Schlüssel nötig (DeepL API Free: 500.000 Zeichen im Monat kostenlos, " +
                                "etwa 8–12 Filme). Geht DeepL nicht, übersetzt das Gerät.",
                        ) { update { it.copy(translationProvider = it.translationProvider.next()) } }
                    }
                    if (settings.translationProvider == de.ayutv.data.TranslationProvider.DEEPL) {
                        item {
                            CredentialField(
                                settings.deeplKey,
                                "DeepL-API-Schlüssel (deepl.com → API-Konto → Schlüssel; bequemer per Handy-Einrichtung)",
                                password = true,
                            ) { key -> update { it.copy(deeplKey = key) } }
                        }
                        item {
                            var result by remember { mutableStateOf<String?>(null) }
                            SettingRow(
                                "DeepL prüfen",
                                if (result == null) "Jetzt prüfen" else "",
                                result ?: "Prüft den Schlüssel und zeigt, wie viel vom Monatskontingent schon verbraucht ist.",
                            ) {
                                result = "prüft …"
                                scope.launch {
                                    result = runCatching {
                                        val usage = de.ayutv.data.translate.DeepLTranslator(container.http, settings.deeplKey, null).usage()
                                        val format = java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY)
                                        "✓ Schlüssel gültig · ${format.format(usage.used)} von ${format.format(usage.limit)} Zeichen in diesem Monat verbraucht"
                                    }.getOrElse { "✗ ${it.message}" }
                                }
                            }
                        }
                    }
                    item {
                        val pretranslation = remember { de.ayutv.data.repo.Pretranslation(context, container) }
                        var last by remember { mutableStateOf(pretranslation.lastResult) }
                        SettingRow(
                            "Vorübersetzen in der Ruhezeit",
                            onOff(settings.pretranslate),
                            "Wenn der Fire TV nicht benutzt wird: Untertitel für „Weiter schauen“, die nächste Folge und „Meine Liste“ " +
                                "vorbereiten. Eingebettete Untertitel (Englisch bevorzugt) werden dafür aus der Filmdatei gelesen – der Stick lädt " +
                                "den Film einmal (je Film einige GB, höchstens ${de.ayutv.data.repo.EmbeddedPrep.MAX_READS_PER_DAY} pro Nacht) – und " +
                                "übersetzt. Beim Schauen sind sie dann sofort da und passen exakt." +
                                (last?.let { "\nZuletzt $it" } ?: ""),
                        ) { update { it.copy(pretranslate = !it.pretranslate) } }
                        if (settings.pretranslate) {
                            SettingRow("Jetzt vorbereiten", "", "Startet sofort statt in der Ruhezeit (läuft im Hintergrund; hört auf, sobald ein Film läuft).") {
                                de.ayutv.data.repo.PretranslateWorker.runNow(context)
                                last = "läuft …"
                            }
                        }
                    }
                    if (showAdvanced) item {
                        var testResult by remember { mutableStateOf("Prüft Texterkennung und Übersetzung auf diesem Gerät") }
                        SettingRow(
                            "Übersetzer testen",
                            "",
                            testResult,
                        ) {
                            testResult = "Läuft … (beim ersten Mal werden die Modelle geladen)"
                            scope.launch {
                                testResult = de.ayutv.data.translate.TranslatorSelfTest.run(settings.subtitleLanguage)
                                de.ayutv.data.Diagnostics.saveSelfTest(context, testResult)
                                // Übersetzer-Prozess wieder beenden: Speicher frei für die Wiedergabe.
                                Translators.remote.release()
                            }
                        }
                    }
                    if (showAdvanced) item {
                        var modelInfo by remember { mutableStateOf("") }
                        LaunchedEffect(Unit) {
                            modelInfo = withContext(Dispatchers.IO) {
                                val pairs = Translators.onDevice.installedPairs()
                                if (pairs.isEmpty()) "noch keine geladen" else "${pairs.size} · ${Translators.onDevice.installedBytes() / 1_000_000} MB"
                            }
                        }
                        SettingRow(
                            "Sprachmodelle auf dem Gerät",
                            modelInfo,
                            "Auswählen löscht alle geladenen Modelle (werden bei Bedarf neu geladen). Übersetzung: OPUS-MT, Universität Helsinki, CC BY 4.0.",
                        ) {
                            scope.launch {
                                Translators.onDevice.deleteAll()
                                modelInfo = "noch keine geladen"
                            }
                        }
                    }

                }
                SettingsPage.DISPLAY -> {
                    item {
                        SettingRow(
                            "Design",
                            settings.uiTheme.label,
                            "Prestige: Carbon-Schwarz mit Kohlefaser-Muster und Rosso-Akzent, wie ein Sportwagen-Cockpit. " +
                                "Nordlicht: Nachthimmel mit Polarlicht in Türkis und Violett. Hannover 96: Stadionabend mit Flutlicht, " +
                                "Schärpe, Fanschal und Linie in Schwarz-Weiß-Grün. Kino: roter Samtvorhang mit Spotlicht und Gold. Tiefsee: Lichtstrahlen " +
                                "im dunklen Wasser, Türkis. Abendrot: Retro-Sonne über dem Horizont in Violett und Orange. Graphit: modernes Anthrazit " +
                                "mit Glanz. Smaragd: Waldnacht mit Glühwürmchen. Neon: Stadt bei Nacht in Magenta und Cyan. Standard: schlichtes " +
                                "Dunkelblau. OLED-Schwarz: echtes Schwarz für OLED-Fernseher. OK wechselt.",
                        ) {
                            val next = de.ayutv.ui.theme.UiTheme.entries[(settings.uiTheme.ordinal + 1) % de.ayutv.ui.theme.UiTheme.entries.size]
                            de.ayutv.ui.theme.AyuColors.use(next, settings.accent)
                            update { it.copy(uiTheme = next) }
                        }
                    }
                    item {
                        SettingRow(
                            "Akzentfarbe",
                            settings.accent.label,
                            "Farbe von Auswahlrahmen, Fortschritt und Hervorhebungen – zu jedem Design frei wählbar. OK wechselt.",
                        ) {
                            val next = de.ayutv.ui.theme.AccentChoice.entries[(settings.accent.ordinal + 1) % de.ayutv.ui.theme.AccentChoice.entries.size]
                            de.ayutv.ui.theme.AyuColors.use(settings.uiTheme, next)
                            update { it.copy(accent = next) }
                        }
                    }
                    if (de.ayutv.data.FireTvHome.supported(context)) item {
                        SettingRow(
                            "Auf der Fire-TV-Startseite",
                            onOff(settings.fireTvHome),
                            "Weiterschauen und Lieblingssender in der Reihe „Empfohlen von deinen Apps“ – startet direkt, ohne DnA-TV erst zu öffnen.",
                        ) {
                            update { it.copy(fireTvHome = !it.fireTvHome) }
                            container.scope.launch {
                                delay(300)
                                de.ayutv.data.FireTvHome.update(context.applicationContext, container)
                            }
                        }
                    }
                    item {
                        SettingRow(
                            "Spielstände in der Sport-Übersicht",
                            onOff(settings.liveScores),
                            "Deutsche Profiligen: Stand und Spielminute laufender Spiele, Endstand danach (OpenLigaDB, ohne Konto).",
                        ) { update { it.copy(liveScores = !it.liveScores) } }
                    }
                    item {
                        SettingRow(
                            "Tor-Hinweis im Bild",
                            onOff(settings.goalAlerts),
                            "Trifft ein Verein, dem du folgst, erscheint beim Schauen kurz der neue Stand (nicht für das Spiel, das gerade läuft).",
                        ) { update { it.copy(goalAlerts = !it.goalAlerts) } }
                    }
                    item {
                        SettingRow(
                            "Ruhebildschirm",
                            onOff(settings.ambient),
                            "Nach 3 Minuten ohne Bedienung im Menü: große Uhr, laufende Spiele mit Stand und Poster. Eine Taste beendet ihn.",
                        ) { update { it.copy(ambient = !it.ambient) } }
                    }
                    item {
                        var words by remember(settings.alertKeywords) { mutableStateOf(settings.alertKeywords.sorted().joinToString(", ")) }
                        TvTextField(
                            words,
                            { words = it },
                            "Stichwort-Alarm: Begriffe mit Komma, z. B. „Krimi, Finale“ (Erinnerung an jede neue Sendung damit)",
                            imeAction = ImeAction.Done,
                            onImeAction = {
                                update { it.copy(alertKeywords = words.split(',').map(String::trim).filter { w -> w.length >= 3 }.toSet()) }
                                container.scope.launch {
                                    delay(500)
                                    container.refreshTeamReminders()
                                }
                            },
                        )
                    }
                    item {
                        SettingRow(
                            "TV-Guide im Menü",
                            onOff(settings.showGuide),
                            "Das Programm-Raster. Die Programmdaten (Jetzt läuft, Suche, Empfehlungen) bleiben auch ausgeblendet aktiv.",
                        ) { update { it.copy(showGuide = !it.showGuide) } }
                    }
                    item {
                        SettingRow(
                            "Mini-Vorschau in Live TV",
                            onOff(settings.livePreview),
                            "Der ausgewählte Sender läuft nach kurzer Zeit stumm in der Detailspalte. Beim Abspielen wird sie vorher beendet (auch bei Konten mit nur einer Verbindung).",
                        ) { update { it.copy(livePreview = !it.livePreview) } }
                    }
                    item {
                        SettingRow(
                            "Flüssige Bedienung",
                            onOff(settings.smoothUi),
                            "Ohne Überblendungen zwischen den Bereichen und mit sparsamerem Hintergrund: Die Menüs reagieren schneller, " +
                                "vor allem auf älteren Sticks (dort automatisch an).",
                        ) { update { it.copy(smoothUi = !it.smoothUi) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Hoch/Runter schaltet um",
                            onOff(settings.zapWithUpDown),
                            "Aus: Hoch/Runter öffnet stattdessen die Senderliste.",
                        ) { update { it.copy(zapWithUpDown = !it.zapWithUpDown) } }
                    }
                    item {
                        SettingRow(
                            "Live-TV anhalten und zurückspulen",
                            if (settings.liveTimeshift) "An · ${settings.timeshiftMinutes} Min." else "Aus",
                            "Pause hält das Bild an, ⏪ spult zurück (auch ins Tor von vorhin). Der laufende Sender wird dafür auf dem " +
                                "Gerät zwischengespeichert, höchstens gut die Hälfte des freien Speichers. Auswählen: 15 → 30 → 60 → 90 Min. → Aus.",
                        ) {
                            update {
                                val index = de.ayutv.data.TIMESHIFT_MINUTES.indexOf(it.timeshiftMinutes)
                                when {
                                    !it.liveTimeshift -> it.copy(liveTimeshift = true, timeshiftMinutes = de.ayutv.data.TIMESHIFT_MINUTES.first())
                                    index == de.ayutv.data.TIMESHIFT_MINUTES.lastIndex -> it.copy(liveTimeshift = false)
                                    else -> it.copy(timeshiftMinutes = de.ayutv.data.TIMESHIFT_MINUTES[index + 1])
                                }
                            }
                        }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Serien: nächste Folge automatisch",
                            onOff(settings.autoNextEpisode),
                            "In der letzten Minute erscheint „Nächste Folge“ mit Countdown, am Ende startet sie von selbst (OK sofort, andere Taste hält an).",
                        ) { update { it.copy(autoNextEpisode = !it.autoNextEpisode) } }
                    }
                    if (touch) item {
                        SettingRow(
                            "Datensparmodus bei mobilen Daten",
                            onOff(settings.dataSaver),
                            "Ohne WLAN: Sender in der SD-Fassung (wenn vorhanden), Streams mit mehreren Qualitäten höchstens in 720p. " +
                                "HD braucht sonst etwa 2–3 GB pro Stunde.",
                        ) { update { it.copy(dataSaver = !it.dataSaver) } }
                    }
                    item {
                        SettingRow(
                            "Doppelte Sender zusammenfassen",
                            onOff(settings.mergeVariants),
                            "Gleiche Sender in SD, HD, 4K, RAW oder als Backup erscheinen einmal, in der besten Fassung. " +
                                "Fällt sie aus, schaltet der Player automatisch auf die nächste.",
                        ) { update { it.copy(mergeVariants = !it.mergeVariants) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Mindestqualität",
                            settings.minQuality.label,
                            "Sender, deren Auflösung bekanntermaßen darunter liegt (gemessen oder laut Name wie „SD“), werden ausgeblendet. " +
                                "Gibt es den Sender auch besser, bleibt die bessere Fassung. Unbekannte bleiben sichtbar.",
                        ) { update { it.copy(minQuality = it.minQuality.next()) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Beste Fassung bei doppelten Sendern",
                            if (settings.preferUhd) "4K, wenn vorhanden" else "Full HD (stabiler)",
                            "Welche Fassung ein doppelt geführter Sender zeigt. Gemessenes zählt vor dem Namen; ausgefallene Fassungen kommen nach hinten.",
                        ) { update { it.copy(preferUhd = !it.preferUhd) } }
                    }
                    if (showAdvanced) item {
                        SettingRow(
                            "Nicht laufende Fassungen ausblenden",
                            onOff(settings.hideBrokenChannels),
                            "Fassungen, die im Sender-Check oder beim Schauen zweimal nicht liefen, verschwinden aus den Listen.",
                        ) { update { it.copy(hideBrokenChannels = !it.hideBrokenChannels) } }
                    }
                    if (showAdvanced) item {
                        val check = remember { de.ayutv.data.repo.ChannelCheck(context, container) }
                        var last by remember { mutableStateOf(check.lastResult) }
                        SettingRow(
                            "Sender-Check in der Ruhezeit",
                            onOff(settings.channelCheck),
                            "Wenn der Fire TV nicht benutzt wird: jede Fassung doppelter Sender und deine Favoriten kurz anspielen " +
                                "(ohne Bild und Ton) und Auflösung, Startzeit und Ausfälle merken. Gemessen: ${de.ayutv.data.parse.ChannelQuality.size} Fassungen." +
                                (last?.let { "\nZuletzt $it" } ?: ""),
                        ) { update { it.copy(channelCheck = !it.channelCheck) } }
                        if (settings.channelCheck) {
                            SettingRow("Jetzt prüfen", "", "Startet den Check sofort im Hintergrund (einige Minuten, nur solange nichts läuft).") {
                                de.ayutv.data.repo.ChannelCheckWorker.runNow(context)
                                last = "läuft …"
                            }
                        }
                    }
                    item {
                        SettingRow(
                            "TV-Guide",
                            when {
                                settings.ownGuideOnly -> "Nur eigener"
                                !settings.internetGuide -> "Nur vom Anbieter"
                                settings.preferInternetGuide -> "Eigener zuerst, Anbieter ergänzt"
                                else -> "Anbieter zuerst, eigener ergänzt"
                            },
                            "Eigener TV-Guide: freies EPG für Deutschland, Österreich und die Schweiz (epgshare01, open-epg, kostenlose 24/7-Dienste), richtige Zeiten auch nach " +
                                "der Zeitumstellung. Der TV-Guide des Anbieters liegt oft daneben; „Nur eigener“ lädt ihn gar nicht. " +
                                "Der Guide wird nach dem Umschalten neu geladen.",
                        ) {
                            scope.launch {
                                // Nur eigener → Eigener zuerst → Anbieter zuerst → Nur Anbieter → Nur eigener
                                container.settings.update {
                                    when {
                                        it.ownGuideOnly -> it.copy(ownGuideOnly = false, internetGuide = true, preferInternetGuide = true)
                                        it.internetGuide && it.preferInternetGuide -> it.copy(preferInternetGuide = false)
                                        it.internetGuide -> it.copy(internetGuide = false)
                                        else -> it.copy(ownGuideOnly = true, internetGuide = true, preferInternetGuide = false)
                                    }
                                }
                                epgReloadJob?.cancel()
                                epgReloadJob = container.scope.launch {
                                    delay(2_500)
                                    container.playlists.reloadAllEpg()
                                }
                            }
                        }
                    }
                    if (showAdvanced) item {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val zone = java.util.TimeZone.getDefault()
                        val name = zone.getDisplayName(zone.inDaylightTime(Date()), java.util.TimeZone.LONG, java.util.Locale.GERMAN)
                        SettingRow(
                            "Uhrzeit und Zeitzone",
                            zone.id,
                            when {
                                de.ayutv.AppTime.corrected ->
                                    "Der Fire TV steht auf ${de.ayutv.AppTime.deviceZone}. DnA-TV rechnet trotzdem mit $name, Uhr und TV-Guide stimmen. " +
                                        "Für andere Apps am Fire TV: Einstellungen → Präferenzen → Datum und Uhrzeit → Zeitzone. OK öffnet die Einstellung, falls möglich."
                                de.ayutv.AppTime.deviceOnUtc ->
                                    "Der Fire TV steht auf UTC: Uhr und TV-Guide sind dann verschoben. Bitte die Zeitzone einstellen " +
                                        "(Einstellungen → Präferenzen → Datum und Uhrzeit). OK öffnet die Einstellung, falls möglich."
                                else -> "$name, wie am Gerät eingestellt."
                            },
                        ) {
                            runCatching {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    if (showAdvanced) item {
                        val shift = settings.epgShiftHours
                        SettingRow(
                            "TV-Guide-Zeitverschiebung",
                            if (shift == 0) "Keine" else "%+d Std.".format(shift),
                            "Falls „Jetzt läuft“ nicht stimmt: Zeiten des Anbieters korrigieren. Der Guide wird danach neu geladen.",
                        ) {
                            // Zyklus 0, +1 … +12, −12 … −1
                            val next = if (shift >= 12) -12 else shift + 1
                            scope.launch {
                                container.settings.update { it.copy(epgShiftHours = next) }
                                epgReloadJob?.cancel()
                                epgReloadJob = container.scope.launch {
                                    delay(2_500) // mehrere Klicks abwarten, dann einmal neu laden
                                    container.playlists.reloadAllEpg()
                                }
                            }
                        }
                    }

                }
                SettingsPage.PROXY -> {
                    item {
                        SettingRow(
                            "Proxy",
                            settings.proxyMode.label,
                            "Nur DnA-TV läuft über den Proxy, andere Apps und die Handy-Fernbedienung nicht. Fällt der Proxy aus, " +
                                "spielt die App nicht heimlich ohne ihn weiter, sondern meldet es.",
                        ) { update { it.copy(proxyMode = it.proxyMode.cycle()) } }
                    }
                    item {
                        SettingRow("Art", settings.proxyType.label, "Steht beim Anbieter in den Zugangsdaten (HTTP/HTTPS-Proxy oder SOCKS5).") {
                            update { it.copy(proxyType = it.proxyType.cycle()) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CredentialField(settings.proxyHost, "Adresse (IP oder Name)", Modifier.weight(2f)) { host ->
                                update { it.copy(proxyHost = host) }
                            }
                            CredentialField(settings.proxyPort.takeIf { it > 0 }?.toString().orEmpty(), "Port", Modifier.weight(1f), keyboardType = KeyboardType.Number) { port ->
                                update { it.copy(proxyPort = port.trim().toIntOrNull() ?: 0) }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CredentialField(settings.proxyUser, "Benutzer (falls verlangt)", Modifier.weight(1f)) { user ->
                                update { it.copy(proxyUser = user) }
                            }
                            CredentialField(settings.proxyPassword, "Passwort", Modifier.weight(1f), password = true) { pw ->
                                update { it.copy(proxyPassword = pw) }
                            }
                        }
                    }
                    item {
                        var running by remember { mutableStateOf(false) }
                        var result by remember { mutableStateOf<de.ayutv.data.net.ProxyCheck.Result?>(null) }
                        SettingRow(
                            "Proxy testen",
                            when {
                                running -> "prüft …"
                                result?.ok == true -> "✓"
                                result != null -> "✗"
                                else -> "Jetzt prüfen"
                            },
                            result?.summary() ?: "Zeigt, mit welcher IP und aus welchem Land der Stick direkt und über den Proxy zu sehen ist.",
                        ) {
                            if (running) return@SettingRow
                            running = true
                            scope.launch {
                                result = de.ayutv.data.net.ProxyCheck.run(container.http, settings.proxyConfig)
                                running = false
                            }
                        }
                    }
                    if (settings.proxyConfig.complete) item {
                        // Speedtest: Ping und Geschwindigkeit über den Proxy und direkt (neutraler Messserver, eine Verbindung).
                        var phase by remember { mutableStateOf<String?>(null) }
                        var result by remember { mutableStateOf(de.ayutv.data.net.ProxySpeedTest.last) }
                        SettingRow(
                            "Proxy-Speedtest",
                            phase?.let { "misst $it" } ?: "Jetzt messen",
                            result?.summary()
                                ?: "Misst Ping und Geschwindigkeit über den Proxy und zum Vergleich direkt, je etwa 10 Sekunden über eine " +
                                    "Verbindung wie bei einem Videostream. Die schwächste Sekunde zeigt, ob 4K oder Full HD ohne Hänger " +
                                    "durchkommt. Verbraucht bis zu 300 MB, vorher die Wiedergabe beenden.",
                        ) {
                            if (phase != null) return@SettingRow
                            phase = "…"
                            scope.launch {
                                result = de.ayutv.data.net.ProxySpeedTest.run(container.http, settings.proxyConfig) { phase = it }
                                phase = null
                            }
                        }
                    }
                    if (settings.proxyMode != de.ayutv.data.net.ProxyMode.OFF) item {
                        SettingRow(
                            "Videostreams über den Proxy",
                            onOff(settings.proxyStreams),
                            "Aus: Nur Anmeldung, Listen und TV-Guide laufen über den Proxy, die Videos direkt. Viele Proxys liefern nur " +
                                "10–20 Mbit/s – zu wenig für 4K (Bild hängt, Puffer leer). Der Leitungs-Check zeigt beide Wege im Vergleich. " +
                                "Hinweis: Der Anbieter sieht beim direkten Weg deine eigene Adresse.",
                        ) { update { it.copy(proxyStreams = !it.proxyStreams) } }
                    }
                    if (settings.proxyMode == de.ayutv.data.net.ProxyMode.SELECTED) {
                        item {
                            Text(
                                "Über den Proxy laufen (OK schaltet um):",
                                color = AyuColors.TextSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(playlists, key = { "proxy-${it.id}" }) { playlist ->
                            val on = playlist.id.toString() in settings.proxyPlaylists
                            SettingRow(playlist.name, if (on) "über Proxy" else "direkt", "") {
                                update {
                                    val id = playlist.id.toString()
                                    it.copy(proxyPlaylists = if (id in it.proxyPlaylists) it.proxyPlaylists - id else it.proxyPlaylists + id)
                                }
                            }
                        }
                        if (playlists.isNotEmpty() && settings.proxyPlaylists.none { id -> playlists.any { it.id.toString() == id } }) {
                            item {
                                Text("Noch keine Playlist gewählt: Bis dahin geht alles direkt.", color = AyuColors.Live, fontSize = 14.sp)
                            }
                        }
                    }
                    if (settings.proxyMode != de.ayutv.data.net.ProxyMode.OFF && !settings.proxyConfig.complete) {
                        item {
                            Text("Adresse und Port fehlen noch: Bis dahin geht alles direkt.", color = AyuColors.Live, fontSize = 14.sp)
                        }
                    }
                }
                SettingsPage.PARENTAL -> {
                    item { ParentalSettings() }
                }
                SettingsPage.DATA -> {
                    item {
                        SettingRow(
                            "Verschlüsselte Namensauflösung",
                            onOff(settings.secureDns),
                            "DNS über HTTPS: Welche Server DnA-TV anspricht (Anbieter, Untertitel, TV-Guide), sieht der Internetanbieter " +
                                "nicht mehr. Ist der Dienst nicht erreichbar, gilt automatisch die normale Namensauflösung. Abgleich, " +
                                "Fehlerbericht und Fernbedienung zwischen Handy-App, Windows und Fire TV sind immer verschlüsselt.",
                        ) { update { it.copy(secureDns = !it.secureDns) } }
                    }
                    item {
                        // Alles Wichtige auf diesem Gerät in einer Minute gemessen.
                        var running by remember { mutableStateOf(false) }
                        var result by remember { mutableStateOf<String?>(null) }
                        SettingRow(
                            "Gerät prüfen",
                            if (running) "misst …" else if (result == null) "Jetzt prüfen" else "",
                            result ?: "Misst in etwa einer Minute Ruckeln der Menüs, Speicher, Übersetzung pro Zeile, Leitung, Proxy, " +
                                "DeepL und OpenSubtitles – als kurzer Text zum Abfotografieren.",
                        ) {
                            if (running) return@SettingRow
                            running = true
                            result = "Messe …"
                            scope.launch {
                                result = runCatching {
                                    de.ayutv.data.DeviceCheck.run(context, container) { line -> result = (result?.takeIf { it != "Messe …" }?.plus("\n") ?: "") + line }
                                }.getOrElse { "Prüfung abgebrochen: ${it.message}" }
                                running = false
                            }
                        }
                    }
                    item {
                        var usage by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(Unit) {
                            usage = withContext(Dispatchers.IO) {
                                "Frei: " + de.ayutv.data.StorageCleaner.format(de.ayutv.data.StorageCleaner.freeBytes(context)) +
                                    de.ayutv.data.StorageCleaner.usage(context).joinToString("") { "\n${it.label}: ${de.ayutv.data.StorageCleaner.format(it.bytes)}" }
                            }
                        }
                        var cleaned by remember { mutableStateOf<String?>(null) }
                        SettingRow(
                            "Speicher",
                            "Aufräumen",
                            (usage ?: "wird ermittelt …") + "\nAufräumen: leert den Bilder-Zwischenspeicher (Logos laden neu), löscht installierte Updates, " +
                                "verdichtet die Datenbank und entfernt Übersetzungsmodelle, die 7 Tage nicht benutzt wurden. Nachts räumt die App " +
                                "von selbst auf (Modelle nach 30, Untertitel nach 60 Tagen)." + (cleaned?.let { "\nZuletzt: $it" } ?: ""),
                        ) {
                            scope.launch {
                                cleaned = "räumt auf …"
                                cleaned = withContext(Dispatchers.IO) { de.ayutv.data.StorageCleaner.clean(context, manual = true, database = container.db) }
                                usage = withContext(Dispatchers.IO) {
                                    "Frei: " + de.ayutv.data.StorageCleaner.format(de.ayutv.data.StorageCleaner.freeBytes(context)) +
                                        de.ayutv.data.StorageCleaner.usage(context).joinToString("") { "\n${it.label}: ${de.ayutv.data.StorageCleaner.format(it.bytes)}" }
                                }
                            }
                        }
                    }
                    item {
                        // „Wie ist meine IP?“: direkt und, wenn eingeschaltet, über den Proxy.
                        var running by remember { mutableStateOf(false) }
                        var result by remember { mutableStateOf<String?>(null) }
                        SettingRow(
                            "Meine IP-Adresse",
                            if (running) "prüft …" else if (result == null) "Jetzt prüfen" else "",
                            result ?: "Zeigt, mit welcher IP und aus welchem Land der Fire TV im Internet zu sehen ist – direkt und über den Proxy.",
                        ) {
                            if (running) return@SettingRow
                            running = true
                            scope.launch {
                                val check = de.ayutv.data.net.ProxyCheck.run(container.http, settings.proxyConfig)
                                result = if (settings.proxyConfig.active) check.summary()
                                else "Direkt: " + (check.direct?.let { "${it.country} · ${it.ip}" } ?: "nicht ermittelt") + "\nProxy: aus"
                                running = false
                            }
                        }
                    }
                    item {
                        val update by container.updates.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        SettingRow(
                            "App-Version ${BuildConfig.VERSION_NAME}",
                            de.ayutv.ui.components.updateText(update) ?: when (update) {
                                de.ayutv.data.update.Updater.State.Checking -> "Suche …"
                                de.ayutv.data.update.Updater.State.UpToDate -> "Aktuell"
                                else -> "Nach Updates suchen"
                            },
                            "Neue Versionen kommen direkt von GitHub und werden über die alte installiert (Einstellungen und Playlists bleiben). " +
                                "Beim ersten Mal fragt Android, ob DnA-TV Apps installieren darf.",
                        ) {
                            scope.launch {
                                val release = when (val s = update) {
                                    is de.ayutv.data.update.Updater.State.Available -> s.release
                                    is de.ayutv.data.update.Updater.State.Ready -> s.release
                                    else -> container.updates.check(force = true)
                                }
                                if (release != null) container.updates.downloadAndInstall(release) { context.startActivity(it) }
                            }
                        }
                    }
                    item {
                        var showReport by remember { mutableStateOf(false) }
                        SettingRow(
                            "Fehlerbericht",
                            "",
                            if (touch) "Gerät, Übersetzer, letzte Wiedergabe und Protokoll als Text teilen (ohne Passwörter)."
                            else "Kurzbericht (TV-Guide-Lücken, Ruckler, Neuverbindungen, WLAN, Proxy) oder vollen Bericht (ohne Passwörter) aufs Handy holen.",
                        ) {
                            if (touch) scope.launch {
                                val text = de.ayutv.data.Diagnostics.build(context, container)
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "DnA-TV Fehlerbericht")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, text)
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Fehlerbericht senden")) }
                            } else showReport = !showReport
                        }
                        if (showReport && !touch) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                val url = phone.url?.let { "$it/report" }
                                if (url != null) {
                                    QrCode(url, size = 150.dp)
                                    Spacer(Modifier.width(16.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (url != null) "Code mit dem Handy scannen (gleiches WLAN): Dort steht der Kurzbericht (fehlende TV-Guide-Sender, Ruckler, " +
                                            "Neuverbindungen, WLAN, Proxy der letzten 48 h) mit Taste zum Kopieren, und der volle Bericht als Datei."
                                        else phone.error ?: "Kein Heimnetz gefunden.",
                                        fontSize = 14.sp,
                                    )
                                    url?.let { Text(it, color = AyuColors.Accent, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp)) }
                                }
                            }
                        }
                    }
                    if (touch) {
                        item {
                            SettingRow(
                                "Fernseher fernbedienen",
                                "",
                                "Das Handy als Fernbedienung für DnA-TV auf dem Fernseher (gleiches WLAN); gleicht auch Favoriten und „Weiter schauen“ ab.",
                            ) { showRemote = true }
                        }
                    } else {
                        item {
                            val remote = container.remote
                            var pin by remember { mutableStateOf(remote.pin) }
                            val address = remote.address
                            SettingRow(
                                "Handy als Fernbedienung",
                                onOff(settings.phoneRemote),
                                if (settings.phoneRemote && address != null) {
                                    "In der DnA-TV-App auf dem Handy: Einstellungen → Fernseher fernbedienen. Adresse $address, PIN $pin. " +
                                        "Oder den Code scannen (Fernbedienung im Browser). Auch wenn DnA-TV nicht geöffnet ist: 📺 im Handy-Player " +
                                        "startet Sender oder Film hier. OK schaltet aus."
                                } else "Steuert DnA-TV vom Handy aus (gleiches WLAN, nur mit PIN) und gleicht Favoriten, Verlauf und eigene Gruppen ab.",
                            ) {
                                val on = !settings.phoneRemote
                                update { it.copy(phoneRemote = on) }
                                if (on) {
                                    remote.start()
                                    de.ayutv.data.remote.RemoteService.start(context)
                                } else {
                                    de.ayutv.data.remote.RemoteService.stop(context)
                                    remote.stop()
                                }
                            }
                            if (settings.phoneRemote && address != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                    de.ayutv.ui.components.QrCode("$address/$pin", size = 150.dp)
                                    Spacer(Modifier.width(16.dp))
                                    SettingRow("Neue PIN", pin, "Gekoppelte Handys müssen die neue PIN eingeben.", Modifier.weight(1f)) {
                                        pin = remote.newPin()
                                    }
                                }
                            }
                        }
                    }
                    if (touch) {
                        item {
                            de.ayutv.ui.components.TvTextField(
                                backupPassword,
                                { backupPassword = it },
                                "Passwort für die Sicherung (empfohlen)",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                password = true,
                            )
                        }
                        item {
                            SettingRow(
                                "Sicherung speichern",
                                backupInfo.orEmpty(),
                                "Playlists, Favoriten, ausgeblendete Gruppen, Vereine und Einstellungen in eine Datei, z. B. für ein neues Handy " +
                                    "oder den Fire TV. Mit Passwort verschlüsselt und mit allen Zugangsdaten; ohne Passwort ohne Passwörter und " +
                                    "Schlüssel (die trägt man dann neu ein). Ohne Kindersicherung.",
                            ) {
                                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date())
                                runCatching { saveBackup.launch("dna-tv-sicherung-$day.json") }
                            }
                        }
                        item {
                            SettingRow("Sicherung einspielen", "", "Übernimmt eine gespeicherte Sicherung; nichts wird doppelt angelegt.") {
                                runCatching { loadBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                            }
                        }
                    }
                    item {
                        SettingRow("Verlauf löschen", "", "Entfernt „Zuletzt gesehen“, „Weiter schauen“ und die Empfehlungen.") {
                            scope.launch { container.content.clearHistory() }
                        }
                    }
                    item {
                        SettingRow("Suchverlauf löschen", "", "Entfernt die „Letzten Suchen“ in der Suche.") {
                            scope.launch { container.settings.clearSearchHistory() }
                        }
                    }
                    item {
                        Text(
                            "DnA-TV ${BuildConfig.VERSION_NAME} · Übersetzung: OPUS-MT (Universität Helsinki, CC BY 4.0) · " +
                                "Untertitel: OpenSubtitles.com · Wiedergabe: AndroidX Media3, FFmpeg",
                            color = AyuColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
        } }
    }

    BackHandler(enabled = confirmDelete != null) { confirmDelete = null }
}

private fun onOff(value: Boolean) = if (value) "An" else "Aus"

private fun BufferProfile.next(): BufferProfile = BufferProfile.entries[(ordinal + 1) % BufferProfile.entries.size]
private fun TranslationProvider.next(): TranslationProvider = TranslationProvider.entries[(ordinal + 1) % TranslationProvider.entries.size]

@Composable
private fun LanguageList(selected: String, onSelect: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().keepVerticalFocus(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text("Bevorzugte Untertitelsprache", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp)) }
        items(Languages.all, key = { it.code }) { language ->
            SettingRow(
                language.label,
                if (language.code == selected) "✓" else "",
                if (Translators.onDevice.supports(language.code)) "" else "nur Untertitel aus dem Internet, keine Übersetzung",
                modifier = if (language.code == selected) Modifier.focusRequester(focus) else Modifier,
            ) { onSelect(language.code) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
private fun SubtitleSize.next(): SubtitleSize = SubtitleSize.entries[(ordinal + 1) % SubtitleSize.entries.size]

/** Nächster Wert einer Auswahl (Einstellungen per OK durchschalten). */
private inline fun <reified T : Enum<T>> T.cycle(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/** Speichert bei jeder Eingabe: Auf Fire TV schließt man die Tastatur oft mit "Zurück" statt "Fertig". */
@Composable
private fun CredentialField(
    current: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(0.8f),
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    // Bis zur ersten Eingabe den gespeicherten Wert zeigen, danach nur noch die eigene Eingabe.
    var edited by remember { mutableStateOf<String?>(null) }
    val value = edited ?: current
    TvTextField(
        value = value,
        onValueChange = {
            edited = it
            onSave(it)
        },
        label = label,
        password = password,
        keyboardType = keyboardType,
        imeAction = ImeAction.Done,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    confirmingDelete: Boolean,
    onToggle: () -> Unit,
    onGroups: () -> Unit,
    onMapGuide: () -> Unit,
    onRename: (String) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    // Umbenennen direkt in der Zeile: Feld mit dem jetzigen Namen, OK/Fertig speichert.
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(playlist.name) { mutableStateOf(playlist.name) }
    val nameField = remember { FocusRequester() }
    LaunchedEffect(renaming) {
        if (!renaming) return@LaunchedEffect
        repeat(10) {
            if (runCatching { nameField.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    var more by remember(playlist.id) { mutableStateOf(false) }
    fun saveName() {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty() && trimmed != playlist.name) onRename(trimmed)
        renaming = false
    }
    // Wie viele Sender ein Programm haben, woher es kommt und welche keins haben.
    val container = LocalContainer.current
    val guide by produceState<PlaylistRepository.EpgStatus?>(null, playlist.id, playlist.lastEpgSync) {
        value = withContext(Dispatchers.IO) { container.playlists.epgStatus(playlist.id) }
    }
    val missingGuide = guide?.missing?.isNotEmpty() == true
    // Zwei Zeilen: oben Schalter und Name, darunter die Knöpfe. Nebeneinander war die Seite schmaler als alle Knöpfe
    // zusammen; der Name bekam dann keine Breite, die Zeile wurde riesig hoch und die Liste sah leer aus.
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Schalter vorne: Welche Playlists aktiv sind, entscheidet der Nutzer selbst.
            ActiveToggle(playlist.enabled, onToggle)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (playlist.enabled) Color.Unspecified else AyuColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val type = if (playlist.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U"
                val synced = if (playlist.lastSync > 0) {
                    "aktualisiert " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(playlist.lastSync))
                } else {
                    "noch nicht geladen"
                }
                val state = if (playlist.enabled) "" else " · ausgeschaltet, erscheint nirgends"
                Text("$type · $synced$state", color = AyuColors.TextSecondary, fontSize = 13.sp)
                guide?.let { g ->
                    val sources = g.sources.filter { it.second > 0 }.joinToString(", ") { "${it.first}: ${it.second}" }
                    Text(
                        "TV-Guide: ${g.withGuide} von ${g.total} Sendern" + (if (sources.isNotEmpty()) " ($sources)" else ""),
                        color = AyuColors.TextSecondary, fontSize = 13.sp,
                    )
                    if (g.missing.isNotEmpty()) {
                        Text(
                            "Ohne Programm: " + g.missing.take(12).joinToString(", ") + (if (g.total - g.withGuide > 12) " … (ganze Liste: „TV-Guide zuordnen“)" else ""),
                            color = AyuColors.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Reicht die Breite nicht (schmale Seite, große Schrift), brechen die Knöpfe in eine weitere Zeile um.
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 134.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Übersichtlich: Was man oft braucht, steht da; Umbenennen und Löschen erst unter „Mehr“.
            OutlinedButton(onClick = onGroups) { Text("Gruppen") }
            OutlinedButton(onClick = onRefresh) { Text("Aktualisieren") }
            if (missingGuide) OutlinedButton(onClick = onMapGuide) { Text("TV-Guide zuordnen") }
            val expanded = more || renaming || confirmingDelete
            if (expanded) {
                OutlinedButton(onClick = { newName = playlist.name; renaming = !renaming }) { Text(if (renaming) "Abbrechen" else "Umbenennen") }
                OutlinedButton(
                    onClick = onDelete,
                    colors = if (confirmingDelete) ButtonDefaults.colors(containerColor = AyuColors.Live) else ButtonDefaults.colors(),
                ) { Text(if (confirmingDelete) "Wirklich löschen?" else "Löschen", maxLines = 1) }
            }
            if (!renaming && !confirmingDelete) OutlinedButton(onClick = { more = !more }) { Text(if (more) "Weniger" else "Mehr …") }
        }
        if (renaming) {
            Row(Modifier.fillMaxWidth().padding(start = 134.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                de.ayutv.ui.components.TvTextField(
                    value = newName,
                    onValueChange = { newName = it.take(60) },
                    label = "Neuer Name der Playlist",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    onImeAction = { saveName() },
                    focusRequester = nameField,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = { saveName() }) { Text("Speichern") }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Ausgewählt ist die Zeile rot hinterlegt: Wert dann weiß (rot auf rot war kaum zu lesen).
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                if (description.isNotEmpty()) Text(description, color = AyuColors.TextSecondary, fontSize = 13.sp)
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Text(value, color = if (focused) Color.White else AyuColors.Accent, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else null)
            }
        }
    }
}

@Composable
private fun UserAgentRow(current: String, onChange: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(vertical = 6.dp)) {
        TvTextField(
            value = value,
            // Bei jeder Änderung speichern: Wer die Tastatur mit „Zurück“ schließt, verliert sonst die Eingabe.
            onValueChange = {
                value = it
                onChange(it.ifBlank { AppSettings.DEFAULT_USER_AGENT })
            },
            label = "User-Agent (manche Anbieter verlangen einen bestimmten)",
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
    }
}

/** Playlist anlegen: M3U-Link oder Xtream-Zugang. Xtream wird vor dem Speichern geprüft. */
@Composable
fun AddPlaylistScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(PlaylistType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    if (onCancel != null) BackHandler(onBack = onCancel)

    fun save() {
        error = null
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            error = if (type == PlaylistType.XTREAM) "Bitte die Server-Adresse eingeben." else "Bitte den Playlist-Link eingeben."
            return
        }
        if (type == PlaylistType.XTREAM && (username.isBlank() || password.isBlank())) {
            error = "Bitte Benutzername und Passwort eingeben."
            return
        }
        busy = true
        scope.launch {
            if (type == PlaylistType.XTREAM) {
                val problem = container.playlists.verifyXtream(trimmedUrl, username.trim(), password.trim())
                if (problem != null) {
                    error = problem
                    busy = false
                    return@launch
                }
            }
            val id = container.playlists.add(
                PlaylistEntity(
                    name = name.trim().ifEmpty { if (type == PlaylistType.XTREAM) "Xtream" else "Playlist" },
                    type = type,
                    url = if (type == PlaylistType.XTREAM) de.ayutv.data.repo.XtreamClient.normalizeServer(trimmedUrl) else trimmedUrl,
                    epgUrl = epgUrl.trim().ifEmpty { null },
                    username = username.trim().ifEmpty { null },
                    password = password.trim().ifEmpty { null },
                )
            )
            // Laden im App-Scope: läuft weiter, auch wenn dieser Bildschirm schon geschlossen ist.
            container.scope.launch { container.playlists.sync(id) }
            busy = false
            onDone()
        }
    }

    // Zugangsdaten lassen sich auch vom Handy einfügen (QR-Code rechts), statt sie zu tippen.
    // Auf dem Handy selbst nicht nötig: dort direkt eingeben.
    val phoneSetup = remember { PhoneSetupServer(container) }
    val phone by phoneSetup.state.collectAsStateWithLifecycle()
    val touch = isTouchDevice
    DisposableEffect(touch) {
        if (!touch) phoneSetup.start()
        onDispose { phoneSetup.stop() }
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = if (touch) 16.dp else 56.dp, vertical = if (touch) 16.dp else 36.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            Modifier.weight(1.4f).fillMaxHeight()
                .background(AyuColors.Surface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Playlist hinzufügen", style = MaterialTheme.typography.headlineMedium)
            Text("Zugang deines IPTV-Anbieters eintragen – DnA-TV ist ein reiner Player.", color = AyuColors.TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                TypeCard(
                    "Xtream Codes",
                    "Server, Benutzername und Passwort. Filme, Serien und TV-Guide kommen automatisch.",
                    type == PlaylistType.XTREAM,
                    Modifier.weight(1f),
                ) { type = PlaylistType.XTREAM }
                TypeCard(
                    "M3U-Link",
                    "Eine Adresse (.m3u / .m3u8 / get.php). TV-Guide optional per XMLTV-Link.",
                    type == PlaylistType.M3U,
                    Modifier.weight(1f),
                ) { type = PlaylistType.M3U }
            }
            TvTextField(name, { name = it }, "Name (optional), z. B. „Mein Anbieter“", focusRequester = firstField)
            if (type == PlaylistType.XTREAM) {
                TvTextField(url, { url = it }, "Server, z. B. http://anbieter.tv:8080", keyboardType = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(username, { username = it }, "Benutzername", Modifier.weight(1f))
                    TvTextField(password, { password = it }, "Passwort", Modifier.weight(1f), password = true)
                }
            } else {
                TvTextField(url, { url = it }, "Playlist-Link (M3U/M3U8)", keyboardType = KeyboardType.Uri)
            }
            TvTextField(
                epgUrl,
                { epgUrl = it },
                if (type == PlaylistType.XTREAM) "EPG-Link (optional, der des Anbieters gilt immer zusätzlich)" else "EPG-Link XMLTV (optional, sonst automatisch aus der Playlist)",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = { save() },
            )
            error?.let {
                Text(
                    "⚠ $it",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(AyuColors.Live.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { if (!busy) save() }) { Text(if (busy) "Wird geprüft …" else "Speichern und laden") }
                if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
        if (!touch) Column(
            Modifier.weight(1f).fillMaxHeight()
                .background(AyuColors.Surface.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bequemer per Handy", style = MaterialTheme.typography.titleLarge)
            Text(
                "QR-Code scannen (gleiches WLAN) und Adresse, Benutzername und Passwort einfach einfügen. " +
                    "Die Playlist erscheint danach sofort hier.",
                color = AyuColors.TextSecondary,
                fontSize = 14.sp,
            )
            phone.url?.let { url ->
                QrCode(url, size = 190.dp, modifier = Modifier.padding(top = 8.dp))
                Text(url, color = AyuColors.Accent, fontSize = 16.sp)
            }
            phone.error?.let { Text(it, color = AyuColors.Live, fontSize = 13.sp) }
            phone.lastSaved?.let { Text(it, fontSize = 13.sp) }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
}

@Composable
private fun TypeCard(title: String, description: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft.copy(alpha = 0.55f) else AyuColors.Background.copy(alpha = 0.6f),
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, if (selected) AyuColors.Accent else Color.Transparent), shape = RoundedCornerShape(12.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text((if (selected) "● " else "○ ") + title, fontSize = 17.sp, color = Color.White)
            Text(description, fontSize = 13.sp, color = AyuColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun PhoneSetupCard(state: PhoneSetupServer.State, report: String?) {
    Row(
        Modifier.fillMaxWidth().background(AyuColors.Surface, RoundedCornerShape(12.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.url?.let { QrCode(it, size = 150.dp) }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Einrichtung per Handy", style = MaterialTheme.typography.titleMedium)
            Text(
                "QR-Code mit dem Handy scannen (gleiches WLAN) und dort Playlist-Adressen oder den OpenSubtitles-Zugang " +
                    "bequem einfügen statt mit der Fernbedienung zu tippen.",
                color = AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            state.url?.let { Text(it, color = AyuColors.Accent, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
            state.error?.let { Text(it, color = AyuColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            report?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Bereiche der Einstellungen (linke Spalte). */
private enum class SettingsPage(val title: String, val description: String, val icon: String) {
    PLAYLISTS("Playlists", "Anbieter hinzufügen, ein- und ausschalten, Gruppen wählen", "☰"),
    PLAYBACK("Wiedergabe", "Puffer, Bild, Ton und Stabilität", "▶"),
    PROXY("Proxy", "Eigene IP nur für DnA-TV, z. B. aus einem anderen Land", "🌐"),
    SUBTITLES("Untertitel", "Sprachen, Größe, Übersetzung auf dem Gerät", "💬"),
    DISPLAY("Bedienung & TV-Guide", "Umschalten, Programmführer, Zeitverschiebung", "📺"),
    PARENTAL("Kindersicherung", "Gruppen mit PIN sperren", "🔒"),
    DATA("Daten & Info", "Verlauf, Speicher, Version", "ⓘ"),
}

@Composable
private fun PageButton(page: SettingsPage, selected: Boolean, focusRequester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft.copy(alpha = 0.45f) else Color.Transparent,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(page.icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
            Text(page.title, fontSize = 16.sp, color = if (selected) Color.White else AyuColors.TextSecondary)
        }
    }
}

/** Gut sichtbarer An/Aus-Schalter: grün = aktiv, grau = aus, im Fokus mit weißem Rahmen. */
@Composable
private fun ActiveToggle(enabled: Boolean, onToggle: () -> Unit) {
    val active = Color(0xFF2E9E5B)
    Surface(
        onClick = onToggle,
        modifier = Modifier.width(120.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) active else AyuColors.Surface,
            focusedContainerColor = if (enabled) active else AyuColors.SurfaceRaised,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(20.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            if (enabled) "● Aktiv" else "○ Aus",
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
        )
    }
}

/** PIN festlegen, entsperren, wieder sperren. Gesperrt wird in Playlists → Gruppen (OK halten). */
@Composable
private fun ParentalSettings() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val hasPin by container.parental.hasPin.collectAsStateWithLifecycle(false)
    val unlocked by container.parental.unlocked.collectAsStateWithLifecycle()
    val locked by container.parental.lockedGroups.collectAsStateWithLifecycle(emptySet())
    var pin by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val status = when {
            !hasPin -> "Keine PIN festgelegt – nichts ist gesperrt."
            unlocked -> "🔓 Entsperrt, bis die App verlassen wird. ${locked.size} Gruppe(n) mit Sperre."
            else -> "🔒 Gesperrt: ${locked.size} Gruppe(n) sind ausgeblendet."
        }
        Text(status, fontSize = 17.sp)
        Text(
            "Gesperrte Gruppen verschwinden überall: Listen, TV-Guide, Suche und Umschalten. Sperren: Playlists → Gruppen → " +
                "Gruppe auswählen und OK gedrückt halten. Beim Verlassen der App wird automatisch wieder gesperrt.",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
        )
        TvTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
            label = if (hasPin) "PIN" else "Neue PIN (mindestens 4 Ziffern)",
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            modifier = Modifier.width(320.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasPin) {
                Button(onClick = {
                    scope.launch {
                        info = runCatching { container.parental.setPin(pin) }
                            .fold({ "PIN gespeichert. Jetzt Gruppen sperren (Playlists → Gruppen → OK halten)." }, { it.message })
                        pin = ""
                    }
                }) { Text("PIN festlegen") }
            } else if (!unlocked) {
                Button(onClick = {
                    scope.launch {
                        info = if (container.parental.unlock(pin)) "Entsperrt." else "Falsche PIN."
                        pin = ""
                    }
                }) { Text("Entsperren") }
            } else {
                Button(onClick = { scope.launch { container.parental.lock(); info = "Wieder gesperrt." } }) { Text("Wieder sperren") }
                OutlinedButton(onClick = {
                    scope.launch {
                        info = if (container.parental.removePin(pin)) "PIN entfernt, Kindersicherung aus." else "Zum Entfernen die PIN eingeben."
                        pin = ""
                    }
                }) { Text("PIN entfernen") }
            }
        }
        info?.let { Text(it, color = AyuColors.Accent, fontSize = 14.sp) }
    }
}

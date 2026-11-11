package de.ayutv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.remote.RemoteClient
import de.ayutv.data.remote.RemoteServer
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.Button
import de.ayutv.ui.components.OutlinedButton
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

/**
 * Auf dem Handy: den Fernseher fernbedienen und Favoriten/Verlauf abgleichen. Der Fernseher wird im
 * Heimnetz gefunden; die PIN steht dort unter Einstellungen → Daten & Info → Handy als Fernbedienung.
 */
@Composable
fun RemoteScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { RemoteClient(context.applicationContext, container) }
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(client.paired) }
    var status by remember { mutableStateOf<String?>(null) }
    var tvName by remember { mutableStateOf<String?>(null) }

    fun send(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { status = it.message ?: "Fernseher nicht erreichbar" } }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().widthIn(max = 480.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fernseher fernbedienen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Schließen") }
        }
        Spacer(Modifier.height(12.dp))
        if (!paired) {
            Pairing(client) { name ->
                tvName = name
                paired = true
                status = "Verbunden mit $name"
                send { status = "Abgleich: " + client.sync().text }
            }
        } else {
            LaunchedEffect(Unit) {
                runCatching { client.check(client.address!!, client.pin!!) }
                    .onSuccess { tvName = it }
                    .onFailure { status = it.message ?: "Fernseher nicht erreichbar (eingeschaltet und DnA-TV geöffnet?)" }
            }
            Text(tvName ?: client.address.orEmpty(), color = AyuColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Pad { k -> send { client.key(k) } }
            Spacer(Modifier.height(16.dp))
            var query by remember { mutableStateOf("") }
            TvTextField(
                query,
                { query = it },
                "Auf dem Fernseher suchen",
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                imeAction = ImeAction.Search,
                onImeAction = { if (query.isNotBlank()) send { client.search(query.trim()); status = "Gesucht: ${query.trim()}" } },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { send { status = "Abgleich: " + client.sync().text } }) { Text("⇅ Favoriten, Verlauf & eigene Gruppen abgleichen") }
                OutlinedButton(onClick = {
                    client.pin = null
                    paired = false
                }) { Text("Anderer Fernseher") }
            }
            Spacer(Modifier.height(16.dp))
            PhoneTranslates(client)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                send {
                    status = "Kurzbericht wird geholt …"
                    val text = client.report(short = true)
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "DnA-TV Kurzbericht (Fernseher)")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    // Zusätzlich in die Zwischenablage: direkt in den Chat mit Claude einfügen.
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("DnA-TV Kurzbericht", text))
                    context.startActivity(android.content.Intent.createChooser(share, "Kurzbericht senden"))
                    status = "Kurzbericht kopiert – im Chat mit Claude einfügen oder oben eine App wählen."
                }
            }) { Text("📋 Kurzbericht vom Fernseher (für Claude)") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                send {
                    status = "Fehlerbericht wird geholt …"
                    val text = client.report()
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "DnA-TV Fehlerbericht (Fernseher)")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    context.startActivity(android.content.Intent.createChooser(share, "Fehlerbericht senden"))
                    status = null
                }
            }) { Text("Fehlerbericht vom Fernseher teilen") }
        }
        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = AyuColors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Pairing(client: RemoteClient, onPaired: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val found by remember { client.discover() }.collectAsState(emptyList())
    var address by remember { mutableStateOf(client.address.orEmpty()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Auf dem Fernseher DnA-TV öffnen: Einstellungen → Daten & Info → Handy als Fernbedienung. Dort stehen Adresse und PIN.",
            color = AyuColors.TextSecondary,
            fontSize = 14.sp,
        )
        if (found.isEmpty()) Text("Suche Fernseher im WLAN …", color = AyuColors.TextSecondary, fontSize = 14.sp)
        found.forEach { tv ->
            Surface(
                onClick = { address = tv.address },
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (tv.address == address) AyuColors.AccentSoft else AyuColors.Surface,
                    focusedContainerColor = AyuColors.AccentSoft,
                ),
            ) {
                Text("📺 ${tv.name}", modifier = Modifier.padding(14.dp))
            }
        }
        TvTextField(address, { address = it }, "Adresse (z. B. http://192.168.0.20:${RemoteServer.PORT})", modifier = Modifier.fillMaxWidth())
        TvTextField(
            pin,
            { pin = it.filter(Char::isDigit).take(6) },
            "PIN vom Fernseher",
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        )
        Button(onClick = {
            val a = address.trim().let { if (it.startsWith("http")) it else "http://$it" }.let {
                if (Regex(":\\d+$").containsMatchIn(it.substringAfter("://"))) it else "$it:${RemoteServer.PORT}"
            }
            scope.launch {
                runCatching { client.check(a, pin) }
                    .onSuccess { name ->
                        client.address = a
                        client.pin = pin
                        onPaired(name)
                    }
                    .onFailure { error = it.message ?: "Keine Verbindung" }
            }
        }) { Text("Verbinden") }
        error?.let { Text(it, color = AyuColors.Live, fontSize = 14.sp) }
    }
}

@Composable
private fun Pad(onKey: (String) -> Unit) {
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PadRow { Key("☰", "menu", onKey); Key("▲", "up", onKey); Key("ⓘ", "info", onKey) }
        PadRow { Key("◀", "left", onKey); Key("OK", "ok", onKey, primary = true); Key("▶", "right", onKey) }
        PadRow { Key("↩", "back", onKey); Key("▼", "down", onKey); Key("Guide", "guide", onKey) }
        Spacer(Modifier.height(6.dp))
        PadRow { Key("⏪", "rew", onKey); Key("⏯", "play", onKey); Key("⏩", "ffwd", onKey) }
        PadRow { Key("Sender −", "chdown", onKey); Key("↺", "last", onKey); Key("Sender +", "chup", onKey) }
        PadRow { Key("🔉", "voldown", onKey); Key("🔇", "mute", onKey); Key("🔊", "volup", onKey) }
    }
}

@Composable
private fun PadRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
private fun RowScope.Key(label: String, key: String, onKey: (String) -> Unit, primary: Boolean = false) {
    Surface(
        onClick = { onKey(key) },
        modifier = Modifier.weight(1f).height(64.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) AyuColors.Accent else AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
    ) {
        Text(label, modifier = Modifier.align(Alignment.Center), fontSize = 20.sp)
    }
}

/**
 * Handy übersetzt für den Fernseher: Solange diese Seite offen ist, übersetzt das Handy die Untertitel, die der
 * Fernseher braucht (schneller und ohne den knappen Speicher des Sticks). Meldet sich jede Minute neu.
 */
@Composable
private fun PhoneTranslates(client: RemoteClient) {
    val container = LocalContainer.current
    val helper = remember { de.ayutv.data.remote.TranslationHelperServer() }
    val state by helper.state.collectAsState()
    var on by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(on) {
        // Bildschirm bleibt an: Im Ruhezustand schläft das WLAN des Handys, der Fernseher bekäme keine Antwort.
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (helper.state.value.address != null) {
                helper.stop()
                container.scope.launch { runCatching { client.registerHelper(null) } }
            }
        }
    }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        val address = helper.start()
        if (address == null) {
            on = false
            return@LaunchedEffect
        }
        var prepared = false
        while (true) {
            val languages = runCatching { client.registerHelper(address) }
                .onFailure { helper.info("Fernseher nicht erreichbar (${it.message}), versuche es weiter …") }
                .getOrNull()
            if (languages != null && !prepared) {
                prepared = true
                // Sprachmodelle gleich laden (beim ersten Mal ca. 110 MB je Sprache), damit die erste Zeile nicht wartet.
                for (language in languages.filter { it != "en" }) {
                    helper.info("Lade Sprachmodell ${de.ayutv.data.Languages.label(language)} …")
                    runCatching { de.ayutv.data.repo.Translators.remote.translate(listOf("Hello."), language, "en") }
                        .onFailure { helper.info("Sprachmodell ${de.ayutv.data.Languages.label(language)}: ${it.message}") }
                }
                if (state.info?.startsWith("Lade") == true) helper.info(null)
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (on) {
            Button(onClick = {
                on = false
                helper.stop()
                container.scope.launch { runCatching { client.registerHelper(null) } }
            }) { Text("🧠 Handy übersetzt für den Fernseher: An") }
        } else {
            OutlinedButton(onClick = { on = true }) { Text("🧠 Handy übersetzt für den Fernseher") }
        }
        Text(
            if (on) "${state.lines} Zeilen übersetzt. Diese Seite offen lassen, der Bildschirm bleibt an." + (state.info?.let { "\n$it" } ?: "")
            else state.info ?: "Das Handy rechnet die Übersetzung der Untertitel, der Fire TV muss es nicht. Nur im selben WLAN.",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Eintrag in einem Auswahlmenü. */
data class MenuItem(val label: String, val action: () -> Unit)

/** Abgedunkelte Fläche mit Kasten in der Mitte; Klick daneben oder Esc schließt. */
@Composable
fun Overlay(app: AppState, onDismiss: () -> Unit, width: Int = 420, content: @Composable () -> Unit) {
    BackHandler(app) { onDismiss() }
    // Weich einblenden: Hintergrund dunkelt ab, der Kasten wächst leicht heran (nur Grafikkarte, kein neues Layout).
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.tween(180)) }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color(0xB3000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(width.dp).heightIn(max = 620.dp)
                .graphicsLayer {
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                    translationY = (1f - appear.value) * 24f
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .background(Palette.surface, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.raised, RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) { content() }
    }
}

/**
 * Auswahlmenü. OK wird erst angenommen, wenn die Taste losgelassen wurde, nachdem das Menü offen ist (sonst wählt
 * das Loslassen des langen Drückens, das das Menü geöffnet hat, gleich den ersten Eintrag).
 */
@Composable
fun MenuDialog(app: AppState, title: String, items: List<MenuItem>, onDismiss: () -> Unit) {
    Overlay(app, onDismiss) {
        Column {
            Label(title, Type.heading, Modifier.padding(start = 12.dp, bottom = 10.dp), maxLines = 2)
            val first = remember { FocusRequester() }
            val opened = remember { System.currentTimeMillis() }
            LazyColumn {
                itemsIndexed(items) { index, item ->
                    ListRow(
                        item.label,
                        onClick = {
                            if (System.currentTimeMillis() - opened < 250) return@ListRow
                            onDismiss()
                            item.action()
                        },
                        focusRequester = if (index == 0) first else null,
                    )
                }
            }
            FocusOnShow(first)
        }
    }
}

/** Namen eingeben (neue Gruppe, Umbenennen). */
@Composable
fun NameDialog(app: AppState, title: String, initial: String = "", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    Overlay(app, onDismiss) {
        Column {
            Label(title, Type.heading)
            Spacer(Modifier.height(8.dp))
            val submit = {
                if (name.isNotBlank()) {
                    onDismiss()
                    onDone(name.trim())
                }
            }
            Field("Name", name, { name = it }, focusRequester = field, onSubmit = submit)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("Speichern", submit, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
    FocusOnShow(field)
}

/** Fällige Erinnerung: „Gleich: … auf … – umschalten?“ (OK schaltet um, Zurück schließt). */
@Composable
fun ReminderDialog(app: AppState, reminder: de.ayutv.desktop.data.Reminder) {
    val first = remember { FocusRequester() }
    Overlay(app, { app.dueReminder = null }, width = 520) {
        Column {
            Label("⏰ " + if (reminder.start > System.currentTimeMillis()) "Gleich um ${clock(reminder.start)}" else "Läuft seit ${clock(reminder.start)}", Type.small, color = Palette.accent)
            Label(reminder.title, Type.heading, maxLines = 3)
            Label("auf ${reminder.channelName}", Type.body)
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton("▶ Umschalten", { app.acceptReminder(reminder) }, primary = true, focusRequester = first)
                Spacer(Modifier.width(8.dp))
                TextButton("Später", { app.dueReminder = null })
            }
        }
    }
    FocusOnShow(first, reminder)
}

/** Wer schaut? Profile wählen (mit PIN, falls gesetzt) oder ein neues anlegen. */
@Composable
fun ProfileDialog(app: AppState, onDismiss: () -> Unit) {
    val profiles by app.store.profiles.collectAsState()
    val current by app.store.profile.collectAsState()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss, width = 480) {
        Column {
            Label("Wer schaut?", Type.heading)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                itemsIndexed(profiles) { index, p ->
                    ListRow(
                        "👤  ${p.name}" + if (p.pin.isNotEmpty()) "  🔒" else "",
                        onClick = {
                            if (p.pin.isEmpty() || p.id == current) app.switchProfile(p.id)
                            else dialog = { PinDialog(app, "PIN für „${p.name}“", { pin -> app.switchProfile(p.id, pin) }, { dialog = null }) }
                        },
                        selected = p.id == current,
                        focusRequester = if (index == 0) first else null,
                    )
                }
                item {
                    ListRow("＋  Neues Profil", onClick = {
                        dialog = { NameDialog(app, "Neues Profil", onDone = { name -> app.store.createProfile(name) }, onDismiss = { dialog = null }) }
                    })
                }
            }
        }
    }
    FocusOnShow(first)
    dialog?.invoke()
}

/** PIN eingeben (Kindersicherung). [onPin] liefert true, wenn die PIN stimmt. */
@Composable
fun PinDialog(app: AppState, title: String, onPin: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    Overlay(app, onDismiss) {
        Column {
            Label(title, Type.heading, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val submit: () -> Unit = {
                if (onPin(pin)) {
                    onDismiss()
                } else {
                    app.toast("Falsche PIN")
                    pin = ""
                }
            }
            Field("PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, secret = true, focusRequester = field, onSubmit = submit)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("OK", submit, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
    FocusOnShow(field)
}

/** Ja/Nein-Frage. */
@Composable
fun ConfirmDialog(app: AppState, question: String, yes: String = "Ja", onYes: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss) {
        Column {
            Label(question, Type.body, maxLines = 4)
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(yes, { onDismiss(); onYes() }, primary = true, focusRequester = first)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
    FocusOnShow(first)
}

package de.ayutv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay

/**
 * Zwei Ebenen, Gruppen → Inhalt: Ist eine Gruppe geöffnet, verschwindet die Gruppenspalte und nur der Inhalt ist zu
 * sehen. Zurück (oder ◀ am linken Rand) holt die Gruppen wieder, mit dem Fokus auf der geöffneten Gruppe.
 */
@Stable
class DrillDown(initiallyOpen: Boolean = false) {
    /** Inhalt geöffnet, Gruppenspalte ausgeblendet. */
    var isOpen by mutableStateOf(initiallyOpen)
        private set
    /** Zählt jedes Zurück zu den Gruppen: Die Spalte setzt dann den Fokus auf die gewählte Gruppe. */
    var backTick by mutableIntStateOf(0)
        private set
    internal var enterTick by mutableIntStateOf(0)
        private set
    /** Bis wann ein OK noch in den Inhalt führt (lädt er länger oder bleibt leer, verfällt es). */
    internal var enterUntil = 0L

    fun close() {
        if (!isOpen) return
        isOpen = false
        backTick++
    }

    /** OK auf einer Gruppe: in den Inhalt wechseln, sobald er geladen ist. */
    fun enter() {
        enterUntil = System.currentTimeMillis() + 2_500
        enterTick++
    }

    internal fun opened() { isOpen = true }

    companion object {
        val SAVER = Saver<DrillDown, Boolean>({ it.isOpen }, { DrillDown(it) })
    }
}

@Composable
fun rememberDrillDown(): DrillDown = rememberSaveable(saver = DrillDown.SAVER) { DrillDown() }

/**
 * Für den Inhalt: Bekommt er den Fokus, gilt er als geöffnet. ◀ hinaus führt zurück zu den Gruppen statt in die
 * Seitenleiste. [backEnabled]: Zurück darf schließen (nicht, solange der Inhalt Zurück selbst braucht, z. B. beim
 * Verschieben).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.drillContent(state: DrillDown, backEnabled: Boolean = true): Modifier {
    BackHandler(enabled = state.isOpen && backEnabled) { state.close() }
    val focusManager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(state.enterTick) {
        // Der Inhalt lädt evtl. noch: bis zur Frist einige Male versuchen, nach rechts hineinzugehen.
        while (System.currentTimeMillis() < state.enterUntil) {
            // Per Touch gibt es keinen Fokus, der hineinwandern könnte: gleich öffnen.
            if (inputMode.inputMode == InputMode.Touch) state.opened()
            if (state.isOpen || focusManager.moveFocus(FocusDirection.Right)) break
            delay(50)
        }
        state.enterUntil = 0L
    }
    return this
        // Nur mit Fernbedienung oder Tastatur: Per Touch bekommt eine angetippte Zeile auch den Fokus, dann
        // verschwänden die Gruppen schon beim Antippen. Dort öffnet nur das Antippen einer Gruppe.
        .onFocusChanged { if (it.hasFocus && inputMode.inputMode != InputMode.Touch) state.opened() }
        .focusProperties {
            onExit = {
                if (requestedFocusDirection == FocusDirection.Left && state.isOpen) {
                    cancelFocusChange()
                    state.close()
                }
            }
        }
        .focusGroup()
}

/** Überschrift über dem geöffneten Inhalt: welche Gruppe gerade offen ist, und wie es zurückgeht. */
@Composable
fun DrillTitle(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Text(
        "‹  $title",
        color = AyuColors.Accent,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp).tapToGoBack(onBack),
    )
}

/**
 * Antippen (Handy, Touch) führt zurück – für Überschriften mit „‹“. Nicht fokussierbar: Mit der Fernbedienung bleibt
 * es bei der Zurück-Taste bzw. ◀, der Fokus springt nicht auf die Überschrift.
 */
fun Modifier.tapToGoBack(onBack: (() -> Unit)?): Modifier =
    if (onBack == null) this
    else this.pointerInput(onBack) { detectTapGestures { onBack() } }

package de.ayutv.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties

/**
 * ▲/▼ am Anfang oder Ende einer Liste bleiben in ihr. Ohne das sucht Compose den nächsten Eintrag schräg
 * daneben: am Ende der Einstellungen etwa einen Bereich der linken Spalte (der beim Fokussieren sofort
 * umschaltet) oder die Seitenleiste. Zur Seite (◀/▶) geht es wie gewohnt hinaus.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.keepVerticalFocus(): Modifier = this
    .focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
        }
    }
    // Eigene Gruppe außen herum, damit ein focusRestorer innen sein eigenes onExit behält.
    .focusGroup()

package de.ayutv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.ayutv.ui.UiPerformance

/** Schnell los, weich aus (wie die Bewegungen von Fire TV und Android TV). */
val EaseOutSoft = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Sanftes Erscheinen einer Ebene (Detailseite, geöffnete Kategorie, Gruppenspalte), jedes Mal wenn sich [key]
 * ändert: Sie gleitet um [distance] herein (positiv = von rechts, negativ = von links). Auf schwachen Geräten nur
 * das Verschieben, das kostet den Grafikchip fast nichts (die fertig gezeichnete Ebene wird nur versetzt); sonst
 * zusätzlich Aufblenden und leichtes Wachsen. Liest den Fortschritt nur beim Zeichnen: kein Neuaufbau je Bild.
 */
@Composable
fun Modifier.appear(key: Any?, distance: Dp = 48.dp, durationMs: Int = 240): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(durationMs, easing = EaseOutSoft)) }
    val reduced = UiPerformance.reduced
    return graphicsLayer {
        val rest = 1f - progress.value
        translationX = rest * distance.toPx()
        if (!reduced && rest > 0f) {
            alpha = 1f - rest * 0.85f
            val scale = 1f - rest * 0.025f
            scaleX = scale
            scaleY = scale
        }
    }
}

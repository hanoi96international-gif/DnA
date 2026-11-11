package de.ayutv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hintergrund in der Farbe des Posters: ein weicher Lichtschein von oben links, der Rest bleibt dunkel. Die Farbe wird aus
 * einem winzigen Vorschaubild (24×36 Pixel) bestimmt – kostet praktisch nichts, auch auf dem Fire TV Stick.
 */
@Composable
fun PosterGlow(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tint by produceState<Color?>(null, url) {
        value = url?.takeIf { it.isNotBlank() }?.let { posterColor(context, it) }
    }
    val target = tint ?: Color.Transparent
    val color = if (de.ayutv.ui.UiPerformance.reduced) target
    else animateColorAsState(target, tween(600), label = "posterGlow").value
    if (color.alpha == 0f) return
    Box(
        modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0.12f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(0f, 0f),
                radius = 1600f,
            ),
        ),
    )
}

/** Kräftige, mittelhelle Farbe des Bildes (graue, fast schwarze und fast weiße Pixel zählen kaum), oder null. */
private suspend fun posterColor(context: android.content.Context, url: String): Color? = withContext(Dispatchers.Default) {
    runCatching {
        val request = ImageRequest.Builder(context).data(url).size(24, 36).allowHardware(false).memoryCacheKey("glow:$url").build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val w = (hsv[1] * hsv[1]) * (if (hsv[2] in 0.2f..0.95f) 1f else 0.1f) + 0.01f
            r += android.graphics.Color.red(p) * w
            g += android.graphics.Color.green(p) * w
            b += android.graphics.Color.blue(p) * w
            weight += w
        }
        if (weight <= 0) return@runCatching null
        // Etwas aufhellen und sättigen, damit der Schein auf dunklem Grund sichtbar ist.
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt()), hsv)
        hsv[1] = (hsv[1] * 1.3f).coerceAtMost(0.85f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.9f)
        Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrNull()
}

package de.ayutv.ui.components

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.SurfaceColors

/*
 * Bedienung per Touchscreen (Handy, Tablet). Die Oberfläche ist für die Fernbedienung gebaut: Ihre
 * Kacheln und Knöpfe reagieren auf OK, nicht auf Tippen. Die gleichnamigen Fassungen hier hängen das
 * Tippen an (fokussieren und auslösen, lange tippen = OK halten). Auf dem Fernseher ändert sich nichts.
 */

/** Gerät mit Touchscreen (Handy/Tablet). Fire TV und Android TV haben keinen. */
fun Context.hasTouchscreen(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

val isTouchDevice: Boolean
    @Composable get() {
        val context = LocalContext.current
        return remember(context) { context.hasTouchscreen() }
    }

/** Fernseher (Fire TV, Android TV), auch wenn er einen Touchscreen meldet (manche Boxen, der Emulator). */
val isTvDevice: Boolean
    @Composable get() {
        val context = LocalContext.current
        return remember(context) { de.ayutv.ui.apps.AppLauncher.isTv(context) }
    }

/**
 * Gerade per Touch bedient (zuletzt getippt, nicht Fernbedienung/Tastatur). Für Verhalten, das sich nach der Bedienung
 * richten muss statt nach dem Gerät: Ein Handy mit Gamepad oder ein Emulator mit Touchscreen wird per Tasten bedient.
 */
val usingTouch: Boolean
    @Composable get() = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Touch

/**
 * Handys sind im Querformat niedriger als ein Fernseher (540 dp): alles im gleichen Verhältnis
 * verkleinern, damit die Seiten wie auf dem Fernseher aussehen und nichts abgeschnitten wird.
 */
@Composable
fun FitToScreen(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Bildrand ausgleichen (Overscan am Fernseher, Trapezkorrektur am Beamer): alles rückt nach innen.
    val store = de.ayutv.ui.LocalContainer.current.settings
    val marginFlow = remember(store) {
        store.settings.map { it.screenMargin.fraction }.distinctUntilChanged().onEach { lastMargin = it }
    }
    val margin by marginFlow.collectAsState(initial = lastMargin)
    // Testbild beim Einstellen: einige Sekunden ein Rahmen mit Eckmarken genau am Rand der genutzten Fläche.
    var frameUntil by remember { mutableStateOf(0L) }
    var seenMargin by remember { mutableStateOf<Float?>(null) }
    androidx.compose.runtime.LaunchedEffect(margin) {
        val before = seenMargin
        seenMargin = margin
        if (before == null || before == margin) return@LaunchedEffect
        frameUntil = android.os.SystemClock.elapsedRealtime() + FRAME_SHOW_MS
        kotlinx.coroutines.delay(FRAME_SHOW_MS)
        frameUntil = 0L
    }
    val showFrame = frameUntil > 0L
    // Hochformat: so verkleinern, dass die Seiten 600 dp breit sind (Listen und Raster passen nebeneinander).
    val base = if (configuration.screenHeightDp > configuration.screenWidthDp) {
        (configuration.screenWidthDp / PORTRAIT_WIDTH_DP).coerceIn(0.55f, 1f)
    } else {
        (configuration.screenHeightDp / TV_HEIGHT_DP).coerceIn(0.55f, 1f)
    }
    val factor = base * (1f - 2f * margin)
    // Immer derselbe Aufbau (nur der Rand ändert sich): Ein Wechsel der Verschachtelung baute sonst die ganze
    // Oberfläche neu auf und warf einen aus der geöffneten Seite.
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .then(if (margin > 0f) Modifier.background(androidx.compose.ui.graphics.Color.Black) else Modifier)
            .padding(
                horizontal = (configuration.screenWidthDp * margin).dp,
                vertical = (configuration.screenHeightDp * margin).dp,
            ),
    ) {
        CompositionLocalProvider(
            LocalDensity provides if (factor >= 0.99f) density else Density(density.density * factor, density.fontScale),
            content = content,
        )
        if (showFrame) EdgeFrame()
    }
}

/** Rahmen mit Eckmarken: Sind alle vier Ecken ganz zu sehen, passt der Bildrand. */
@Composable
private fun EdgeFrame() {
    val color = de.ayutv.ui.theme.AyuColors.Accent
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val stroke = 4.dp.toPx()
        val arm = size.minDimension * 0.08f
        drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val corners = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f) to androidx.compose.ui.geometry.Offset(1f, 1f),
            androidx.compose.ui.geometry.Offset(size.width, 0f) to androidx.compose.ui.geometry.Offset(-1f, 1f),
            androidx.compose.ui.geometry.Offset(0f, size.height) to androidx.compose.ui.geometry.Offset(1f, -1f),
            androidx.compose.ui.geometry.Offset(size.width, size.height) to androidx.compose.ui.geometry.Offset(-1f, -1f),
        )
        for ((corner, dir) in corners) {
            drawLine(color, corner, corner + androidx.compose.ui.geometry.Offset(dir.x * arm, 0f), strokeWidth = stroke * 3)
            drawLine(color, corner, corner + androidx.compose.ui.geometry.Offset(0f, dir.y * arm), strokeWidth = stroke * 3)
        }
    }
}

private const val FRAME_SHOW_MS = 6_000L

/** Zuletzt gelesener Bildrand: neue Bildschirme (Player) starten gleich richtig, ohne erst zu springen. */
@Volatile
private var lastMargin = 0f

private const val TV_HEIGHT_DP = 540f
private const val PORTRAIT_WIDTH_DP = 600f

/** Hochformat (Handy hochkant gehalten): Navigation unten, schmalere Spalten. */
val isPortrait: Boolean
    @Composable get() = LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }

/**
 * Tippen fokussiert das Element und löst es aus, langes Tippen wirkt wie „OK halten“. Mit [focusFirst]
 * zeigt der erste Tipp nur die Vorschau (wie das Fokussieren mit der Fernbedienung), der zweite löst aus.
 */
fun Modifier.touchClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    focusFirst: Boolean = false,
): Modifier = composed {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    Modifier
        .focusRequester(requester)
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .pointerInput(enabled, hasLong, focusFirst) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onLongPress = if (hasLong) { _ ->
                    runCatching { requester.requestFocus() }
                    longClick?.invoke()
                } else null,
                onTap = {
                    val wasFocused = focused
                    runCatching { requester.requestFocus() }
                    if (!focusFirst || wasFocused) click()
                },
            )
        }
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    focusFirst: Boolean = false,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(
    onClick = onClick,
    modifier = modifier.touchClick(onClick, onLongClick, enabled, focusFirst),
    onLongClick = onLongClick,
    enabled = enabled,
    shape = shape,
    colors = colors,
    scale = scale,
    border = border,
    content = content,
)

/** Nicht anklickbare Fläche (Dialoge): unverändert, nur damit beide Formen unter einem Namen erreichbar sind. */
@Composable
fun Surface(
    modifier: Modifier,
    shape: Shape,
    colors: SurfaceColors,
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(modifier = modifier, shape = shape, colors = colors, content = content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.Button(onClick = onClick, modifier = modifier.touchClick(onClick), colors = colors, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = OutlinedButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.OutlinedButton(onClick = onClick, modifier = modifier.touchClick(onClick), colors = colors, content = content)

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    colors: CardColors = CardDefaults.colors(),
    border: CardBorder = CardDefaults.border(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.tv.material3.Card(
    onClick = onClick,
    modifier = modifier.touchClick(onClick, onLongClick),
    onLongClick = onLongClick,
    colors = colors,
    border = border,
    content = content,
)

package de.ayutv.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.remote
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Farben wie in der Fire-TV-App (Standard-Design). */
/**
 * Farben der Oberfläche. Design (Standard oder OLED-Schwarz) und Akzentfarbe sind wählbar (Einstellungen → Anzeige);
 * als beobachteter Zustand, damit ein Wechsel sofort überall wirkt.
 */
object Palette {
    /** Akzentfarben: Kennung, Name, Farbe, abgedunkelte Fokusfläche. */
    val ACCENTS = listOf(
        Triple("BLAU", "Blau", Color(0xFF3DA9FC) to Color(0xFF235F8E)),
        Triple("TUERKIS", "Türkis", Color(0xFF5EEAD4) to Color(0xFF0F766E)),
        Triple("VIOLETT", "Violett", Color(0xFFA78BFA) to Color(0xFF5B21B6)),
        Triple("GRUEN", "Grün", Color(0xFF4ADE80) to Color(0xFF15803D)),
        Triple("ORANGE", "Orange", Color(0xFFFB923C) to Color(0xFFC2410C)),
        Triple("PINK", "Pink", Color(0xFFF472B6) to Color(0xFFBE185D)),
        Triple("GOLD", "Gold", Color(0xFFFACC15) to Color(0xFFA16207)),
        Triple("ROT", "Rot", Color(0xFFFF4D4F) to Color(0xFFB0141B)),
    )
    val THEMES = listOf(
        "PRESTIGE" to "Prestige (Carbon, Rosso)", "STANDARD" to "Standard (Dunkelblau)", "OLED" to "OLED-Schwarz",
        "KINO" to "Kino", "TIEFSEE" to "Tiefsee", "ABENDROT" to "Abendrot",
        "GRAPHIT" to "Graphit", "SMARAGD" to "Smaragd", "NEON" to "Neon", "HANNOVER" to "Hannover 96",
    )

    /** Grundfarben je Design: Hintergrund, Fläche, erhöhte Fläche und Verlauf des Fensterhintergrunds (oben → unten). */
    private class Look(val background: Color, val surface: Color, val raised: Color, gradient: List<Color>) {
        /** Einmal je Design (nicht bei jedem Neuzeichnen neu). */
        val brush = androidx.compose.ui.graphics.Brush.verticalGradient(gradient)
    }

    private val LOOKS = mapOf(
        "PRESTIGE" to Look(Color(0xFF07080A), Color(0xFF131519), Color(0xFF1C1F25), listOf(Color(0xFF15171C), Color(0xFF0A0B0E), Color(0xFF050506))),
        "STANDARD" to Look(Color(0xFF0B0F14), Color(0xFF151B23), Color(0xFF1E2631), listOf(Color(0xFF0F1620), Color(0xFF0B0F14))),
        "OLED" to Look(Color(0xFF000000), Color(0xFF0E0E10), Color(0xFF1A1A1E), listOf(Color(0xFF000000), Color(0xFF000000))),
        "KINO" to Look(Color(0xFF0B0506), Color(0xFF1A0F11), Color(0xFF29171A), listOf(Color(0xFF3A0810), Color(0xFF1A0508), Color(0xFF070203))),
        "TIEFSEE" to Look(Color(0xFF02090F), Color(0xFF0A1822), Color(0xFF102634), listOf(Color(0xFF0B3A4F), Color(0xFF062132), Color(0xFF010407))),
        "ABENDROT" to Look(Color(0xFF0E0718), Color(0xFF1B1028), Color(0xFF2A1838), listOf(Color(0xFF0A0614), Color(0xFF1E0B33), Color(0xFF4A1243))),
        "GRAPHIT" to Look(Color(0xFF0E0F11), Color(0xFF1A1C20), Color(0xFF25282E), listOf(Color(0xFF1B1D22), Color(0xFF0E0F11), Color(0xFF08090A))),
        "SMARAGD" to Look(Color(0xFF040A07), Color(0xFF0D1A13), Color(0xFF15281D), listOf(Color(0xFF0B1F18), Color(0xFF07130E), Color(0xFF030806))),
        "NEON" to Look(Color(0xFF07050F), Color(0xFF130F24), Color(0xFF1E1838), listOf(Color(0xFF07050F), Color(0xFF1A0B33), Color(0xFF3B0A45))),
        "HANNOVER" to Look(Color(0xFF0C0909), Color(0xFF1A1213), Color(0xFF2A1A1C), listOf(Color(0xFF2A0609), Color(0xFF140607), Color(0xFF0A0606), Color(0xFF082513))),
    )

    /** Passende Akzentfarbe, wenn „Blau“ (Standard) gewählt ist: Gold im Kino, Türkis in der Tiefsee, Koralle im Abendrot. */
    private val THEME_ACCENTS = mapOf(
        "PRESTIGE" to (Color(0xFFFF2D2D) to Color(0xFF9E1018)),
        "KINO" to (Color(0xFFF5C451) to Color(0xFF8C2232)),
        "TIEFSEE" to (Color(0xFF38E1F2) to Color(0xFF0E6E86)),
        "ABENDROT" to (Color(0xFFFF8A5B) to Color(0xFFB3306B)),
        "GRAPHIT" to (Color(0xFFA5C8FF) to Color(0xFF3B4A63)),
        "SMARAGD" to (Color(0xFF4ADE80) to Color(0xFF15803D)),
        "NEON" to (Color(0xFF22D3EE) to Color(0xFFA21CAF)),
        "HANNOVER" to (Color(0xFFFF3B3F) to Color(0xFFB0141B)),
    )

    private var look by androidx.compose.runtime.mutableStateOf(LOOKS.getValue("STANDARD"))
    private var accentPair by androidx.compose.runtime.mutableStateOf(ACCENTS.first().third)

    fun use(theme: String, accent: String) {
        look = LOOKS[theme] ?: LOOKS.getValue("STANDARD")
        val chosen = ACCENTS.firstOrNull { it.first == accent } ?: ACCENTS.first()
        accentPair = if (chosen.first == ACCENTS.first().first) THEME_ACCENTS[theme] ?: chosen.third else chosen.third
    }

    val background get() = look.background
    val surface get() = look.surface
    val raised get() = look.raised
    /** Fensterhintergrund als Verlauf (Kino: Samtrot, Tiefsee: Petrol, Abendrot: Violett). */
    val backdrop: androidx.compose.ui.graphics.Brush get() = look.brush
    val accent get() = accentPair.first
    val accentSoft get() = accentPair.second
    val live = Color(0xFFE5484D)
    val text = Color(0xFFF2F5F8)
    val secondary = Color(0xFF9AA7B4)
}

/** Schrift wie auf dem Fire TV: Manrope (SIL Open Font License), aus den Programmdateien. */
val AppFont: androidx.compose.ui.text.font.FontFamily = runCatching {
    fun font(file: String, weight: FontWeight) = androidx.compose.ui.text.platform.Font(
        identity = "manrope-$file",
        data = Type::class.java.getResourceAsStream("/fonts/manrope_$file.ttf")!!.use { it.readBytes() },
        weight = weight,
    )
    androidx.compose.ui.text.font.FontFamily(
        font("regular", FontWeight.Normal), font("medium", FontWeight.Medium), font("semibold", FontWeight.SemiBold),
        font("bold", FontWeight.Bold), font("extrabold", FontWeight.ExtraBold),
    )
}.getOrDefault(androidx.compose.ui.text.font.FontFamily.Default)

object Type {
    val display = TextStyle(color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = AppFont, letterSpacing = (-0.5).sp)
    val title = TextStyle(color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = AppFont, letterSpacing = (-0.3).sp)
    val heading = TextStyle(color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = AppFont)
    val body = TextStyle(color = Palette.text, fontSize = 15.sp, fontFamily = AppFont)
    val small = TextStyle(color = Palette.secondary, fontSize = 13.sp, fontFamily = AppFont)
}

@Composable
fun Label(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, maxLines: Int = 1, color: Color? = null) =
    BasicText(text, modifier, style = if (color != null) style.copy(color = color) else style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * Wählbares Element für alle Eingaben: Maus (Klick, Rechtsklick = [onMenu]), Tastatur und Fire-TV-Fernbedienung
 * (Pfeiltasten wechseln den Fokus, OK/Enter wählt, ☰ Menü, Kontextmenü-Taste oder OK lange drücken = [onMenu]).
 * OK zählt erst beim Loslassen und nur, wenn es auf diesem Element auch gedrückt wurde (sonst würde das Loslassen
 * nach einem Seitenwechsel gleich das nächste Element auslösen).
 */
@Composable
fun Selectable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    /** Vergrößerung bei Fokus (Kacheln: 1.06; Listen: 1.0, damit nichts überlappt). */
    focusScale: Float = 1f,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // Fokus gleitet weich über (Farbe, Rahmen, Größe): nur Zeichnen auf der Grafikkarte, kein neues Layout.
    val background by animateColorAsState(
        when {
            focused -> Palette.accentSoft
            selected -> Palette.raised
            hovered -> Palette.surface
            else -> Palette.surface.copy(alpha = 0f)
        },
        tween(FOCUS_MS),
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS))
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(FOCUS_MS + 40))
    val downAt = remember { longArrayOf(0L) }
    Row(
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onPreviewKeyEvent { e ->
                when (e.remote()) {
                    RemoteButton.OK -> {
                        if (e.type == KeyEventType.KeyDown) {
                            if (downAt[0] == 0L) downAt[0] = System.currentTimeMillis()
                        } else if (e.type == KeyEventType.KeyUp) {
                            val pressedAt = downAt[0]
                            downAt[0] = 0L
                            if (pressedAt != 0L) {
                                val held = System.currentTimeMillis() - pressedAt
                                if (held >= LONG_PRESS_MS && onMenu != null) onMenu() else onClick()
                            }
                        }
                        true
                    }
                    RemoteButton.MENU -> {
                        if (e.type == KeyEventType.KeyDown) onMenu?.invoke()
                        onMenu != null
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (!it.isFocused) downAt[0] = 0L }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (onMenu != null && e.buttons.isSecondaryPressed) onMenu()
            }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                // Leuchten um die Auswahl: drei weiche Ringe in der Akzentfarbe (nur Linien, kein Weichzeichner).
                if (ring > 0.01f) {
                    val radius = 12.dp.toPx()
                    for (i in 1..3) {
                        val spread = i * 3.dp.toPx()
                        drawRoundRect(
                            Palette.accent.copy(alpha = 0.16f * ring / i),
                            topLeft = Offset(-spread, -spread),
                            size = Size(size.width + 2 * spread, size.height + 2 * spread),
                            cornerRadius = CornerRadius(radius + spread),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
            }
            .background(background, shape)
            .border(3.dp, Palette.accent.copy(alpha = ring), shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

private const val LONG_PRESS_MS = 600L
/** Dauer der Fokus-Übergänge (kurz: fühlt sich direkt an, wirkt aber nicht hart). */
const val FOCUS_MS = 140

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) =
    Selectable(onClick, modifier.background(if (primary) Palette.accentSoft.copy(alpha = 0.5f) else Palette.raised, RoundedCornerShape(8.dp)), focusRequester = focusRequester) {
        Label(text, Type.body)
    }

/** Zeile mit Titel, Zusatz und optional Wert rechts. */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onMenu: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
) = Selectable(onClick, modifier.fillMaxWidth(), onMenu, selected, focusRequester) {
    if (leading != null) {
        leading()
        Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
        Label(title, Type.body)
        if (!subtitle.isNullOrEmpty()) Label(subtitle, Type.small)
    }
    if (trailing != null) {
        Spacer(Modifier.width(8.dp))
        Label(trailing, Type.small)
    }
}

/** Eingabefeld (mit Maus und Tastatur). */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    // Ausgewähltes Feld: Text von der Handy-Fernbedienung kommt hier an.
    val currentChange by androidx.compose.runtime.rememberUpdatedState(onChange)
    val currentSubmit by androidx.compose.runtime.rememberUpdatedState(onSubmit)
    val target = remember { TextTarget.Entry({ currentChange(it) }, { currentSubmit?.invoke() }) }
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) TextTarget.current = target else if (TextTarget.current === target) TextTarget.current = null }
    androidx.compose.runtime.DisposableEffect(target) { onDispose { if (TextTarget.current === target) TextTarget.current = null } }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Label(label, Type.small)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body,
            cursorBrush = SolidColor(Palette.accent),
            interactionSource = interaction,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onPreviewKeyEvent { e ->
                    when {
                        onSubmit != null && e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { onSubmit(); true }
                        // ↑/↓ verlassen das Feld (sonst bliebe man mit der Fernbedienung darin hängen).
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .background(Palette.raised, RoundedCornerShape(6.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Palette.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Schaltet beim Erscheinen den Fokus auf [requester]. */
@Composable
fun FocusOnShow(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp)) { content() }

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, height: Int = 4) {
    Box(modifier.fillMaxWidth().height(height.dp).background(Palette.raised, RoundedCornerShape((height / 2).dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).background(Palette.accent, RoundedCornerShape((height / 2).dp)))
    }
}

/** Kopfzeile einer Seite: Titel, optional Hinweis rechts (z. B. Tastenhilfe). */
@Composable
fun ScreenHeader(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(26.dp).background(Palette.accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(12.dp))
        Label(title, Type.title, Modifier.weight(1f))
        if (hint != null) Label(hint, Type.small, maxLines = 2)
    }
}

/**
 * Kachel für die Startseite (Sender, Film, Serie): Bild, Titel, Zeile darunter, optional Fortschritt.
 * Fokus und Mausrahmen wie bei [Selectable].
 */
@Composable
fun Tile(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    wide: Boolean = true,
    onMenu: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val width = if (wide) 240.dp else 156.dp
    Selectable(onClick, modifier.width(width), onMenu = onMenu, focusRequester = focusRequester, shape = RoundedCornerShape(14.dp), focusScale = 1.06f) { focused ->
        Column(Modifier.fillMaxWidth()) {
            val picture = rememberImage(image)
            val height = if (wide) 120.dp else 226.dp
            Box(
                Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Palette.raised, Palette.surface))),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    androidx.compose.foundation.Image(
                        picture, null,
                        Modifier.fillMaxSize().padding(if (wide) 20.dp else 0.dp),
                        contentScale = if (wide) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Label(title.take(18), Type.heading, Modifier.padding(10.dp), maxLines = 3)
                }
                if (!wide) {
                    // Poster: Titel im Bild auf dunklem Verlauf (wie in Streaming-Apps).
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                            .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = if (progress != null) 12.dp else 8.dp),
                    ) { Label(title, Type.body.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 2) }
                }
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) { Progress(progress, height = 3) }
                }
            }
            Spacer(Modifier.height(7.dp))
            if (wide) Label(title, if (focused) Type.heading.copy(fontSize = 15.sp) else Type.body)
            if (!subtitle.isNullOrEmpty()) Label(subtitle, Type.small)
        }
    }
}

/** Textfeld, das gerade ausgewählt ist (für Text von der Handy-Fernbedienung). Nur auf dem Oberflächen-Thread. */
object TextTarget {
    class Entry(val set: (String) -> Unit, val submit: () -> Unit)
    var current: Entry? = null
}

/** Schnell los, weich aus (wie die Bewegungen auf dem Fire TV). */
val EaseOutSoft = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Sanftes Erscheinen einer Ebene (Detailseite, andere Kategorie, Player), jedes Mal wenn sich [key] ändert: Sie
 * gleitet um [distance] herein (positiv = von rechts), blendet auf und wächst von [scaleFrom] auf volle Größe.
 * Liest den Fortschritt nur beim Zeichnen: kein Neuaufbau je Bild.
 */
@Composable
fun Modifier.appear(key: Any?, distance: androidx.compose.ui.unit.Dp = 40.dp, durationMs: Int = 240, scaleFrom: Float = 0.975f): Modifier {
    val progress = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(durationMs, easing = EaseOutSoft)) }
    return graphicsLayer {
        val rest = 1f - progress.value
        if (rest > 0f) {
            translationX = rest * distance.toPx()
            alpha = 1f - rest * 0.85f
            val scale = 1f - rest * (1f - scaleFrom)
            scaleX = scale
            scaleY = scale
        }
    }
}

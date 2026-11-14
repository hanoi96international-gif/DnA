package de.ayutv.ui.theme

import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.Border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.launch
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Wählbares Aussehen der App. */
enum class UiTheme(val label: String) {
    PRESTIGE("Prestige"),
    NORDLICHT("Nordlicht"),
    HANNOVER("Hannover 96"),
    CLASSIC("Standard"),
    OLED("OLED-Schwarz"),
    KINO("Kino"),
    TIEFSEE("Tiefsee"),
    ABENDROT("Abendrot"),
    GRAPHIT("Graphit"),
    SMARAGD("Smaragd"),
    NEON("Neon"),
}

/**
 * Akzentfarbe (Fokusrahmen, Fortschritt, Hervorhebungen), unabhängig vom Design wählbar. DESIGN = die Farbe,
 * die zum gewählten Design gehört.
 */
enum class AccentChoice(val label: String, val accent: Long, val soft: Long) {
    DESIGN("Passend zum Design", 0, 0),
    TUERKIS("Türkis", 0xFF5EEAD4, 0xFF0F766E),
    BLAU("Blau", 0xFF3DA9FC, 0xFF235F8E),
    VIOLETT("Violett", 0xFFA78BFA, 0xFF5B21B6),
    GRUEN("Grün", 0xFF4ADE80, 0xFF15803D),
    ORANGE("Orange", 0xFFFB923C, 0xFFC2410C),
    PINK("Pink", 0xFFF472B6, 0xFFBE185D),
    GOLD("Gold", 0xFFFACC15, 0xFFA16207),
    ROT("Rot", 0xFFFF4D4F, 0xFFB0141B),
}

/** Farben eines Aussehens. */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val accent: Color,
    val accentSoft: Color,
    val live: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val progress: Color,
) {
    companion object {
        /**
         * Prestige: Carbon-Schwarz mit feinem Glanz, Akzente in Rosso Corsa, Fokus als tiefes Rot mit weißer Schrift.
         * Wie ein Sportwagen-Cockpit: ruhig, edel, mit einem einzigen kräftigen Akzent.
         */
        val PRESTIGE = Palette(
            background = Color(0xFF07080A),
            surface = Color(0xFF131519),
            surfaceRaised = Color(0xFF1C1F25),
            accent = Color(0xFFFF2D2D),
            accentSoft = Color(0xFF9E1018),
            live = Color(0xFFFF453A),
            textPrimary = Color(0xFFF6F6F8),
            textSecondary = Color(0xFFA4A6AE),
            progress = Color(0xFFFF2D2D),
        )

        val CLASSIC = Palette(
            background = Color(0xFF0B0F14),
            surface = Color(0xFF151B23),
            surfaceRaised = Color(0xFF1E2631),
            accent = Color(0xFF3DA9FC),
            accentSoft = Color(0xFF235F8E),
            live = Color(0xFFE5484D),
            textPrimary = Color(0xFFF2F5F8),
            textSecondary = Color(0xFF9AA7B4),
            progress = Color(0xFF3DA9FC),
        )

        /**
         * Nordlicht: Nachthimmel in Tiefblau bis Violett, Polarlicht in Türkis. Fokus als kräftiges Indigo mit
         * weißer Schrift (auf dem Fernseher von weitem klar), Akzente und Fortschritt in leuchtendem Türkis.
         */
        val NORDLICHT = Palette(
            background = Color(0xFF070912),
            surface = Color(0xFF121628),
            surfaceRaised = Color(0xFF1C2244),
            accent = Color(0xFF5EEAD4),
            accentSoft = Color(0xFF4F46E5),
            live = Color(0xFFFF4D6D),
            textPrimary = Color(0xFFF4F6FF),
            textSecondary = Color(0xFFA3AAC8),
            progress = Color(0xFF5EEAD4),
        )

        /**
         * Rot wie das Trikot, Schwarz, Weiß und ein Hauch Grün aus den Vereinsfarben. Fokus in kräftigem Rot,
         * auf dem Fernseher von weitem zu sehen; Fortschritt in Rasengrün.
         */
        val HANNOVER = Palette(
            background = Color(0xFF0C0909),
            surface = Color(0xFF1A1213),
            surfaceRaised = Color(0xFF2A1A1C),
            accent = Color(0xFFFF3B3F),
            accentSoft = Color(0xFFB0141B),
            live = Color(0xFFFF5A3C),
            textPrimary = Color(0xFFFFF7F5),
            textSecondary = Color(0xFFBFAEAD),
            progress = Color(0xFF1FB45A),
        )

        /** Echtes Schwarz: auf OLED-Fernsehern leuchten dort keine Bildpunkte (kein Grauschleier, kein Einbrennen). */
        val OLED = Palette(
            background = Color(0xFF000000),
            surface = Color(0xFF0E0E10),
            surfaceRaised = Color(0xFF1A1A1E),
            accent = Color(0xFF3DA9FC),
            accentSoft = Color(0xFF1F4F7A),
            live = Color(0xFFE5484D),
            textPrimary = Color(0xFFF2F2F4),
            textSecondary = Color(0xFF9A9AA4),
            progress = Color(0xFF3DA9FC),
        )

        /** Kino: roter Samtvorhang, warmes Spotlicht, Akzente in Gold. */
        val KINO = Palette(
            background = Color(0xFF0B0506),
            surface = Color(0xFF1A0F11),
            surfaceRaised = Color(0xFF29171A),
            accent = Color(0xFFF5C451),
            accentSoft = Color(0xFF8C2232),
            live = Color(0xFFFF4D4F),
            textPrimary = Color(0xFFFFF6EA),
            textSecondary = Color(0xFFC4AFA4),
            progress = Color(0xFFF5C451),
        )

        /** Tiefsee: Licht fällt von oben ins dunkle Wasser, Akzente in leuchtendem Türkis. */
        val TIEFSEE = Palette(
            background = Color(0xFF02090F),
            surface = Color(0xFF0A1822),
            surfaceRaised = Color(0xFF102634),
            accent = Color(0xFF38E1F2),
            accentSoft = Color(0xFF0E6E86),
            live = Color(0xFFFF5D73),
            textPrimary = Color(0xFFEFFBFF),
            textSecondary = Color(0xFF93AFBD),
            progress = Color(0xFF38E1F2),
        )

        /** Abendrot: Retro-Sonne über dem Horizont, Verlauf von Violett nach Orange, Akzente in Korallenrot. */
        val ABENDROT = Palette(
            background = Color(0xFF0E0718),
            surface = Color(0xFF1B1028),
            surfaceRaised = Color(0xFF2A1838),
            accent = Color(0xFFFF8A5B),
            accentSoft = Color(0xFFB3306B),
            live = Color(0xFFFF4D6D),
            textPrimary = Color(0xFFFFF4F0),
            textSecondary = Color(0xFFC2A9BF),
            progress = Color(0xFFFF8A5B),
        )

        /** Graphit: neutrales Anthrazit mit weichem Glanz, Akzente in hellem Eisblau. Ruhig und modern. */
        val GRAPHIT = Palette(
            background = Color(0xFF0E0F11),
            surface = Color(0xFF1A1C20),
            surfaceRaised = Color(0xFF25282E),
            accent = Color(0xFFA5C8FF),
            accentSoft = Color(0xFF3B4A63),
            live = Color(0xFFFF5A5F),
            textPrimary = Color(0xFFF3F4F6),
            textSecondary = Color(0xFFA0A4AD),
            progress = Color(0xFFA5C8FF),
        )

        /** Smaragd: nächtlicher Wald mit Glühwürmchen, Akzente in leuchtendem Grün. */
        val SMARAGD = Palette(
            background = Color(0xFF040A07),
            surface = Color(0xFF0D1A13),
            surfaceRaised = Color(0xFF15281D),
            accent = Color(0xFF4ADE80),
            accentSoft = Color(0xFF15803D),
            live = Color(0xFFFF5D5D),
            textPrimary = Color(0xFFF0FFF4),
            textSecondary = Color(0xFF9DB8A6),
            progress = Color(0xFF4ADE80),
        )

        /** Neon: Stadt bei Nacht mit Leuchtreklame in Magenta und Cyan. */
        val NEON = Palette(
            background = Color(0xFF07050F),
            surface = Color(0xFF130F24),
            surfaceRaised = Color(0xFF1E1838),
            accent = Color(0xFF22D3EE),
            accentSoft = Color(0xFFA21CAF),
            live = Color(0xFFFF2E88),
            textPrimary = Color(0xFFF5F3FF),
            textSecondary = Color(0xFFA9A3C9),
            progress = Color(0xFFFF2E88),
        )

        fun of(theme: UiTheme) = when (theme) {
            UiTheme.PRESTIGE -> PRESTIGE
            UiTheme.HANNOVER -> HANNOVER
            UiTheme.NORDLICHT -> NORDLICHT
            UiTheme.CLASSIC -> CLASSIC
            UiTheme.OLED -> OLED
            UiTheme.KINO -> KINO
            UiTheme.TIEFSEE -> TIEFSEE
            UiTheme.ABENDROT -> ABENDROT
            UiTheme.GRAPHIT -> GRAPHIT
            UiTheme.SMARAGD -> SMARAGD
            UiTheme.NEON -> NEON
        }

        fun of(theme: UiTheme, accent: AccentChoice): Palette = of(theme).let { base ->
            if (accent == AccentChoice.DESIGN) base
            else base.copy(accent = Color(accent.accent), accentSoft = Color(accent.soft), progress = Color(accent.accent))
        }
    }
}

/**
 * Die Farben der App. Wechselt das Aussehen, zeichnet Compose alles neu, was eine Farbe liest
 * (die Palette ist ein beobachteter Zustand).
 */
object AyuColors {
    var palette by mutableStateOf(Palette.NORDLICHT)
    var theme by mutableStateOf(UiTheme.NORDLICHT)
        private set

    var accent by mutableStateOf(AccentChoice.DESIGN)
        private set

    /**
     * Ruhiger Hintergrund: nur die dunkle Grundfarbe des Designs, ohne Bild. Spart dem Grafikchip eine ganze
     * Bildschirmfläche pro Bild (schwache Sticks) und ist am Beamer dunkler (bessere Schwarzwerte, kein Leuchten).
     */
    var calm by mutableStateOf(false)

    fun use(next: UiTheme, nextAccent: AccentChoice = accent) {
        // State nur auf dem UI-Thread und innerhalb eines Snapshots schreiben,
        // sonst: "Reading a state that was created after the snapshot was taken"
        val apply = {
            theme = next
            accent = nextAccent
            palette = Palette.of(next, nextAccent)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { apply() }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { apply() }
            }
        }
    }

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceRaised get() = palette.surfaceRaised
    val Accent get() = palette.accent
    // Fokusfläche: hell genug, dass man auf dem Fernseher sofort sieht, wo man ist.
    val AccentSoft get() = palette.accentSoft
    val Live get() = palette.live
    val TextPrimary get() = palette.textPrimary
    val TextSecondary get() = palette.textSecondary
    val Progress get() = palette.progress

}

/**
 * Scrollen mit dem Steuerkreuz wie auf der Fire-TV-Startseite: Die Auswahl bleibt bei rund 30 % der Höhe (bzw. Breite)
 * stehen und die Liste läuft bei jedem Druck gleichmäßig um eine Zeile weiter. Vorher wanderte die Auswahl bis an den
 * Rand und die Liste sprang dann ruckartig nach; bei gehaltener Taste stauten sich solche Sprünge.
 * Am Anfang und Ende einer Liste hält sie wie gewohnt an. Touch-Bedienung (Handy) ist davon nicht betroffen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val PivotScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Passt der Inhalt ganz in den sichtbaren Bereich nicht (größer als der Container): nur so weit, dass der Anfang zu sehen ist.
        if (size >= containerSize) return offset
        val pivot = containerSize * PIVOT
        // Unterhalb des Fixpunkts wäre zu wenig Platz: so weit, dass das Element ganz zu sehen ist.
        val target = if (containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

private const val PIVOT = 0.3f

/** Schrift der App: Manrope (frei, SIL Open Font License), klar und modern, auch aus drei Metern gut lesbar. */
val AppFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(de.ayutv.R.font.manrope_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(de.ayutv.R.font.manrope_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(de.ayutv.R.font.manrope_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(de.ayutv.R.font.manrope_bold, androidx.compose.ui.text.font.FontWeight.Bold),
    androidx.compose.ui.text.font.Font(de.ayutv.R.font.manrope_extrabold, androidx.compose.ui.text.font.FontWeight.ExtraBold),
)

/** Alle Textstile in Manrope; Überschriften kräftiger und enger gesetzt (wirkt hochwertiger). */
private val AppTypography: androidx.tv.material3.Typography = androidx.tv.material3.Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.f() = copy(fontFamily = AppFont)
    fun androidx.compose.ui.text.TextStyle.head(weight: androidx.compose.ui.text.font.FontWeight) =
        copy(fontFamily = AppFont, fontWeight = weight, letterSpacing = (-0.3).sp)
    val bold = androidx.compose.ui.text.font.FontWeight.ExtraBold
    val semi = androidx.compose.ui.text.font.FontWeight.Bold
    t.copy(
        displayLarge = t.displayLarge.head(bold), displayMedium = t.displayMedium.head(bold), displaySmall = t.displaySmall.head(bold),
        headlineLarge = t.headlineLarge.head(bold), headlineMedium = t.headlineMedium.head(bold), headlineSmall = t.headlineSmall.head(semi),
        titleLarge = t.titleLarge.head(semi), titleMedium = t.titleMedium.head(semi), titleSmall = t.titleSmall.f().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = AppTypography,
        colorScheme = darkColorScheme(
            primary = AyuColors.Accent,
            onPrimary = Color.Black,
            primaryContainer = AyuColors.AccentSoft,
            background = AyuColors.Background,
            onBackground = AyuColors.TextPrimary,
            surface = AyuColors.Surface,
            onSurface = AyuColors.TextPrimary,
            surfaceVariant = AyuColors.SurfaceRaised,
            onSurfaceVariant = AyuColors.TextSecondary,
            border = AyuColors.Accent,
        ),
    ) {
        // Ohne umgebende Surface wäre Text sonst schwarz auf dunklem Hintergrund.
        CompositionLocalProvider(
            LocalContentColor provides AyuColors.TextPrimary,
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides PivotScroll,
            content = content,
        )
    }
}

/**
 * Hintergrund der Menüs. Nordlicht: ein Nachthimmel von Tiefblau nach Schwarz mit Polarlicht-Bändern in Türkis,
 * Violett und einem Hauch Magenta, ein paar Sternen, Leuchten in den Ecken und ganz unten eine dunkle Hügelkette
 * mit türkisem Lichtsaum. Alles wird einmal je Bildschirmgröße berechnet (keine Kosten beim Scrollen).
 * Klassisch: schlichtes Dunkelblau.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = AyuColors.theme
    Box(
        modifier.fillMaxSize().background(AyuColors.Background)
            .then(
                if (AyuColors.calm) Modifier else when (theme) {
                    UiTheme.PRESTIGE -> Modifier.drawWithCache {
                        val image = rasterize { CarbonCockpit(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.NORDLICHT -> Modifier.drawWithCache {
                        val image = rasterize { NorthernSky(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.HANNOVER -> Modifier.drawWithCache {
                        val image = rasterize { StadiumNight(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.KINO -> Modifier.drawWithCache {
                        val image = rasterize { CinemaCurtain(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.TIEFSEE -> Modifier.drawWithCache {
                        val image = rasterize { DeepSea(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.ABENDROT -> Modifier.drawWithCache {
                        val image = rasterize { Sunset(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.GRAPHIT -> Modifier.drawWithCache {
                        val image = rasterize { Graphite(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.SMARAGD -> Modifier.drawWithCache {
                        val image = rasterize { Forest(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.NEON -> Modifier.drawWithCache {
                        val image = rasterize { NeonCity(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.CLASSIC, UiTheme.OLED -> Modifier
                }
            )
    ) {
        content()
    }
}

/**
 * Den Hintergrund einmal in ein Bild zeichnen. Direkt gezeichnet bestand er aus über einem Dutzend halbdurchsichtiger
 * Verläufe über den ganzen Bildschirm (Hannover: dazu über 100 Linien und eine riesige Schrift), und die Grafik musste
 * das bei jeder Fokusbewegung neu zusammensetzen: Auf dem Fire TV Stick ruckelte dadurch die ganze Oberfläche.
 * Ein fertiges Bild kostet pro Bild nur noch einmal Kopieren. Auf schwachen Geräten etwas kleiner (weich genug).
 */
private fun androidx.compose.ui.draw.CacheDrawScope.rasterize(
    paint: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size) -> Unit,
): androidx.compose.ui.graphics.ImageBitmap {
    val scale = if (de.ayutv.ui.UiPerformance.reduced) 0.75f else 1f
    val target = androidx.compose.ui.geometry.Size(
        (size.width * scale).coerceAtLeast(1f).toInt().toFloat(),
        (size.height * scale).coerceAtLeast(1f).toInt().toFloat(),
    )
    val image = androidx.compose.ui.graphics.ImageBitmap(target.width.toInt(), target.height.toInt())
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        this, layoutDirection, androidx.compose.ui.graphics.Canvas(image), target,
    ) { paint(target) }
    return image
}

/**
 * Hannover 96 – Flutlichtabend im Stadion: Nachthimmel in tiefem Rot nach Schwarz, zwei Flutlichtmasten mit
 * Lichtkegeln, eine Tribüne mit roten Rängen und vielen kleinen Lichtern (Fans), davor der Rasen mit Mähstreifen und
 * weißer Seitenlinie. Rechts eine große, dezente „96“, unten die Linie in Schwarz-Weiß-Grün. Oben links, wo Menüs und
 * Titel stehen, bleibt es dunkel und ruhig; nichts läuft quer durch den Inhalt.
 */
private class StadiumNight(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val red = Color(0xFFD2121A)
    private val green = Color(0xFF1FB45A)
    private val standTop = h * 0.56f
    private val pitchTop = h * 0.74f
    private val sky = Brush.verticalGradient(listOf(Color(0xFF2A0609), Color(0xFF140607), Color(0xFF0A0606)), endY = standTop)
    private val masts = listOf(w * 0.06f, w * 0.94f)
    private val cones = masts.map { x ->
        val dir = if (x < w / 2) 1f else -1f
        androidx.compose.ui.graphics.Path().apply {
            moveTo(x, h * 0.05f)
            lineTo(x + dir * w * 0.50f, pitchTop + h * 0.2f)
            lineTo(x + dir * w * 0.16f, pitchTop + h * 0.2f)
            close()
        }
    }
    private val coneLight = Brush.verticalGradient(listOf(Color(0xFFFFF4E0).copy(alpha = 0.10f), Color.Transparent), startY = h * 0.05f, endY = pitchTop + h * 0.2f)
    private val lampGlow = masts.map { x -> Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color(0xFFFFE9C4).copy(alpha = 0.15f), Color.Transparent), center = Offset(x, h * 0.05f), radius = h * 0.12f) }
    /** Tribüne: dunkle Fläche mit Dachkante, rote Ränge in Reihen, viele kleine Lichter. */
    private val stand = Brush.verticalGradient(listOf(Color(0xFF1A0B0D), Color(0xFF260A0E)), startY = standTop, endY = pitchTop)
    private val rows: List<Float> = List(6) { i -> standTop + (pitchTop - standTop) * (0.18f + i * 0.13f) }
    private val fans: List<Triple<Offset, Float, Color>> = kotlin.random.Random(96).let { r ->
        List(420) {
            val y = standTop + (pitchTop - standTop) * (0.12f + r.nextFloat() * 0.84f)
            val c = when (r.nextInt(10)) {
                0 -> Color.White
                1, 2 -> Color(0xFF1FB45A)
                else -> Color(0xFFFF3B3F)
            }
            Triple(Offset(r.nextFloat() * w, y), 0.8f + r.nextFloat() * 1.6f, c.copy(alpha = 0.18f + r.nextFloat() * 0.35f))
        }
    }
    /** Rasen: Mähstreifen in Perspektive (nach unten breiter), weiße Seitenlinie und Mittelkreis-Bogen. */
    private val pitch = Brush.verticalGradient(listOf(Color(0xFF0E3A1E), Color(0xFF082513), Color(0xFF041009)), startY = pitchTop, endY = h)
    private val stripes: List<androidx.compose.ui.graphics.Path> = run {
        val count = 14
        List(count) { i ->
            val topLeft = w * (i.toFloat() / count)
            val topRight = w * ((i + 1f) / count)
            val spread = 1.9f
            androidx.compose.ui.graphics.Path().apply {
                moveTo(topLeft, pitchTop)
                lineTo(topRight, pitchTop)
                lineTo(w / 2 + (topRight - w / 2) * spread, h)
                lineTo(w / 2 + (topLeft - w / 2) * spread, h)
                close()
            }
        }.filterIndexed { i, _ -> i % 2 == 0 }
    }
    private val numberFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(22, 210, 18, 26)
        textSize = h * 0.62f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val numberOutline = android.graphics.Paint(numberFill).apply {
        color = android.graphics.Color.argb(40, 255, 255, 255)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = h * 0.004f
    }
    private val readable = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.15f), Color.Transparent), endX = w * 0.65f)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
        center = Offset(w * 0.55f, h * 0.45f), radius = maxOf(w, h) * 0.78f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(Color(0xFF0A0606))
        drawRect(sky, size = androidx.compose.ui.geometry.Size(w, standTop))
        // Flutlicht
        for (cone in cones) drawPath(cone, coneLight)
        for ((i, x) in masts.withIndex()) {
            drawLine(Color(0xFF2B2224), Offset(x, h * 0.05f), Offset(x, standTop), strokeWidth = w * 0.004f)
            drawRect(Color(0xFFFFF7E8).copy(alpha = 0.9f), Offset(x - w * 0.018f, h * 0.035f), androidx.compose.ui.geometry.Size(w * 0.036f, h * 0.022f))
            drawCircle(lampGlow[i], radius = h * 0.12f, center = Offset(x, h * 0.05f))
        }
        // Tribüne
        drawRect(stand, Offset(0f, standTop), androidx.compose.ui.geometry.Size(w, pitchTop - standTop))
        drawRect(red.copy(alpha = 0.55f), Offset(0f, standTop), androidx.compose.ui.geometry.Size(w, h * 0.006f))
        for (y in rows) drawLine(red.copy(alpha = 0.16f), Offset(0f, y), Offset(w, y), strokeWidth = h * 0.010f)
        for ((p, r, c) in fans) drawCircle(c, radius = r, center = p)
        // Rasen
        drawRect(pitch, Offset(0f, pitchTop), androidx.compose.ui.geometry.Size(w, h - pitchTop))
        for (stripe in stripes) drawPath(stripe, Color.White.copy(alpha = 0.035f))
        drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, pitchTop + h * 0.035f), Offset(w, pitchTop + h * 0.035f), strokeWidth = h * 0.003f)
        drawArc(
            Color.White.copy(alpha = 0.30f), startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.5f - w * 0.11f, pitchTop + h * 0.035f - h * 0.06f), size = androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.12f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.003f),
        )
        // Große, dezente 96 rechts über der Tribüne
        val native = drawContext.canvas.nativeCanvas
        native.save()
        native.rotate(-8f, w * 0.80f, h * 0.45f)
        native.drawText("96", w * 0.82f, h * 0.58f, numberFill)
        native.drawText("96", w * 0.82f, h * 0.58f, numberOutline)
        native.restore()
        // Lesbarkeit links (Menüs), Rand abdunkeln
        drawRect(readable)
        drawRect(vignette)
        // Linie in Schwarz-Weiß-Grün am unteren Rand
        val line = h * 0.006f
        drawRect(Color.Black, Offset(0f, h - line * 3), androidx.compose.ui.geometry.Size(w, line))
        drawRect(Color.White.copy(alpha = 0.85f), Offset(0f, h - line * 2), androidx.compose.ui.geometry.Size(w, line))
        drawRect(green, Offset(0f, h - line), androidx.compose.ui.geometry.Size(w, line))
    }
}

/**
 * Stadion-Intro (Design Hannover 96): Flutlicht geht an, eine große 96 springt federnd herein, dann blendet alles
 * in die App über. Knapp zwei Sekunden; die App lädt währenddessen schon dahinter.
 */
@Composable
fun StadiumIntro(onDone: () -> Unit) {
    val lights = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val badge = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0.4f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { lights.animateTo(1f, androidx.compose.animation.core.tween(500)) }
            kotlinx.coroutines.delay(250)
            launch { badge.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 260f)) }
        }
        kotlinx.coroutines.delay(500)
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            onDrawBehind {
                val w = size.width
                val h = size.height
                val a = fade.value
                drawRect(Color.Black.copy(alpha = a))
                drawRect(Brush.verticalGradient(listOf(Color(0xFF6A0A12).copy(alpha = 0.9f * a * lights.value), Color.Transparent), endY = h))
                for (x in listOf(w * 0.08f, w * 0.92f)) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * a * lights.value), Color.Transparent), center = Offset(x, 0f), radius = h * 0.5f),
                        radius = h * 0.5f,
                        center = Offset(x, 0f),
                    )
                }
                val scale = badge.value
                paint.color = android.graphics.Color.argb((255 * a).toInt(), 230, 30, 38)
                paint.textSize = h * 0.42f * scale
                drawContext.canvas.nativeCanvas.drawText("96", w / 2, h / 2 + h * 0.14f * scale, paint)
            }
        }
    )
}

/**
 * Prestige: Carbon-Schwarz mit feinem Köperbindungs-Muster (wie Kohlefaser), weichem Lichtschein von oben links,
 * einem Hauch Rosso in der Ecke unten rechts und dunkler Vignette. Einmal je Bildschirmgröße gezeichnet.
 */
private class CarbonCockpit(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val base = Brush.linearGradient(listOf(Color(0xFF15171C), Color(0xFF0A0B0E), Color(0xFF050506)), start = Offset.Zero, end = Offset(w, h))
    private val sheen = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), center = Offset(w * 0.18f, -h * 0.1f), radius = w * 0.6f)
    private val rosso = Brush.radialGradient(listOf(Color(0xFFFF2D2D).copy(alpha = 0.16f), Color.Transparent), center = Offset(w * 1.02f, h * 1.05f), radius = w * 0.5f)
    private val rossoTop = Brush.radialGradient(listOf(Color(0xFFB0101A).copy(alpha = 0.10f), Color.Transparent), center = Offset(w * 0.98f, -h * 0.05f), radius = w * 0.35f)
    private val vignette = Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center = Offset(w / 2, h / 2), radius = maxOf(w, h) * 0.75f)
    /** Kohlefaser: kleine Rauten in zwei Helligkeiten, abwechselnd (Köper 2/2), kaum sichtbar. */
    private val cell = maxOf(6f, w / 320f)

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(base)
        val light = Color.White.copy(alpha = 0.022f)
        val dark = Color.Black.copy(alpha = 0.10f)
        var y = 0f
        var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) 0f else -cell
            var col = 0
            while (x < w) {
                val horizontal = (col + row / 2) % 2 == 0
                drawRect(if (horizontal) light else dark, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(cell * 2, cell))
                x += cell * 2
                col++
            }
            y += cell
            row++
        }
        drawRect(sheen)
        drawRect(rossoTop)
        drawRect(rosso)
        drawRect(vignette)
    }
}

/** Vorberechneter Nachthimmel für eine Bildschirmgröße. */
private class NorthernSky(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val base = Brush.verticalGradient(listOf(Color(0xFF0E1330), Color(0xFF080B1A), Color(0xFF05060C)), endY = h)
    private val glows = listOf(
        Triple(Offset(w * 0.08f, h * 0.02f), w * 0.55f, Color(0xFF6D4BFF).copy(alpha = 0.32f)),
        Triple(Offset(w * 0.92f, h * 0.05f), w * 0.45f, Color(0xFF14B8A6).copy(alpha = 0.24f)),
        Triple(Offset(w * 0.78f, h * 1.05f), w * 0.45f, Color(0xFFDB2777).copy(alpha = 0.10f)),
    ).map { (center, radius, color) -> Triple(center, radius, Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius)) }

    /** Ein Polarlicht-Band als geschwungene Fläche; mehrfach verbreitert und schwächer gezeichnet = weicher Rand. */
    private class Band(val path: androidx.compose.ui.graphics.Path, val brush: Brush, val alpha: Float)
    private val bands: List<Band> = buildList {
        val colors = listOf(Color(0xFF2DD4BF), Color(0xFF34D399), Color(0xFF818CF8), Color(0xFFC084FC), Color(0xFF2DD4BF))
        fun ribbon(top: Float, amplitude: Float, thickness: Float, phase: Float, alpha: Float) {
            for ((k, spread) in listOf(1f, 1.8f, 2.8f).withIndex()) {
                val t = thickness * spread
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(-w * 0.05f, top + amplitude * kotlin.math.sin(phase))
                    val steps = 6
                    for (i in 1..steps) {
                        val x = -w * 0.05f + w * 1.1f * i / steps
                        val y = top + amplitude * kotlin.math.sin(phase + i * 1.15f)
                        val cx = x - w * 1.1f / steps / 2
                        val cy = top + amplitude * kotlin.math.sin(phase + (i - 0.5f) * 1.15f) - amplitude * 0.4f
                        quadraticTo(cx, cy, x, y)
                    }
                    lineTo(w * 1.05f, top + t + amplitude)
                    for (i in 6 downTo 0) {
                        val x = -w * 0.05f + w * 1.1f * i / 6
                        lineTo(x, top + t + amplitude * kotlin.math.sin(phase + i * 1.15f + 0.6f) * 0.7f)
                    }
                    close()
                }
                val brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.White, Color.Transparent),
                    startY = top - amplitude, endY = top + t + amplitude,
                )
                add(Band(path, Brush.horizontalGradient(colors), alpha / (k * 1.8f + 1f)))
                if (k == 0) add(Band(path, brush, alpha * 0.18f))
            }
        }
        ribbon(top = h * 0.10f, amplitude = h * 0.06f, thickness = h * 0.05f, phase = 0.3f, alpha = 0.20f)
        ribbon(top = h * 0.24f, amplitude = h * 0.05f, thickness = h * 0.035f, phase = 2.1f, alpha = 0.13f)
    }

    private val stars: List<Pair<Offset, Float>> = kotlin.random.Random(7).let { r ->
        List(90) { Offset(r.nextFloat() * w, r.nextFloat() * h * 0.62f) to (0.05f + r.nextFloat() * 0.28f) }
    }

    /** Dunkle Hügelkette unten, mit türkisem Lichtsaum oben. */
    private val ridge = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * 0.86f)
        val points = listOf(0.12f to 0.80f, 0.24f to 0.85f, 0.38f to 0.78f, 0.52f to 0.84f, 0.66f to 0.79f, 0.80f to 0.86f, 0.92f to 0.81f, 1.0f to 0.84f)
        var previous = 0f to 0.86f
        for ((x, y) in points) {
            quadraticTo(w * (previous.first + x) / 2, h * minOf(previous.second, y) - h * 0.015f, w * x, h * y)
            previous = x to y
        }
        lineTo(w, h)
        close()
    }
    private val ridgeFill = Brush.verticalGradient(listOf(Color(0xFF0B0F22), Color(0xFF04050A)), startY = h * 0.78f, endY = h)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
        center = Offset(w * 0.5f, h * 0.45f), radius = maxOf(w, h) * 0.75f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(base)
        for ((center, radius, brush) in glows) drawCircle(brush, radius, center)
        for ((position, alpha) in stars) drawCircle(Color.White.copy(alpha = alpha), radius = 1.6f, center = position)
        for (band in bands) drawPath(band.path, band.brush, alpha = band.alpha)
        drawPath(ridge, Color(0xFF2DD4BF).copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.004f))
        drawPath(ridge, ridgeFill)
        drawRect(vignette)
    }
}

/**
 * Kurzes Intro im Design Prestige: Carbon blendet auf, „DnA·TV“ in kräftiger Schrift wächst leicht heran, darunter
 * zieht ein roter Lichtstreifen von der Mitte nach außen (wie das Tagfahrlicht eines Sportwagens). Gut eine Sekunde.
 */
@Composable
fun PrestigeIntro(onDone: () -> Unit) {
    val show = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val line = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { show.animateTo(1f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            kotlinx.coroutines.delay(250)
            launch { line.animateTo(1f, androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        }
        kotlinx.coroutines.delay(300)
        fade.animateTo(0f, androidx.compose.animation.core.tween(400))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.drawWithCache {
            val image = rasterize { CarbonCockpit(it).draw(this) }
            onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.tv.material3.Text(
                "DnA·TV",
                fontFamily = AppFont,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                fontSize = 84.sp,
                letterSpacing = (-1.5).sp,
                color = Color.White,
                modifier = Modifier.graphicsLayer {
                    alpha = show.value
                    val scale = 0.94f + 0.06f * show.value
                    scaleX = scale
                    scaleY = scale
                },
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            Box(
                Modifier.width(360.dp).height(4.dp).graphicsLayer { scaleX = line.value }
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFF2D2D), Color(0xFFFF6A4D), Color(0xFFFF2D2D), Color.Transparent))),
            )
        }
    }
}

/**
 * Kurzes Intro beim Start (nur im Nordlicht-Design): Das Polarlicht leuchtet auf, „DnA·TV“ zieht mit sich
 * schließendem Buchstabenabstand herein, dann blendet alles in die App über. Knapp zwei Sekunden; die App lädt
 * währenddessen schon dahinter.
 */
@Composable
fun AuroraIntro(onDone: () -> Unit) {
    val light = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val word = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { light.animateTo(1f, androidx.compose.animation.core.tween(700)) }
            kotlinx.coroutines.delay(200)
            launch { word.animateTo(1f, androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        }
        kotlinx.coroutines.delay(350)
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val sky = NorthernSky(size)
            onDrawBehind {
                val a = fade.value
                drawRect(Color.Black.copy(alpha = a))
                if (a > 0f) {
                    val layer = light.value * a
                    drawIntoCanvas { canvas ->
                        val paint = androidx.compose.ui.graphics.Paint().apply { alpha = layer }
                        canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), paint)
                        sky.draw(this)
                        canvas.restore()
                    }
                    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb((255 * a * word.value).toInt(), 244, 246, 255)
                        textSize = size.height * 0.13f
                        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                        textAlign = android.graphics.Paint.Align.CENTER
                        letterSpacing = 0.6f - 0.45f * word.value
                    }
                    val glow = android.graphics.Paint(text).apply {
                        color = android.graphics.Color.argb((120 * a * word.value).toInt(), 94, 234, 212)
                        maskFilter = android.graphics.BlurMaskFilter(size.height * 0.02f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    drawContext.canvas.nativeCanvas.apply {
                        drawText("DnA·TV", size.width / 2, size.height * 0.53f, glow)
                        drawText("DnA·TV", size.width / 2, size.height * 0.53f, text)
                    }
                }
            }
        }
    )
}

/** Gut sichtbare Fokusmarkierung für Listen und Raster: heller Rahmen zusätzlich zur Fokusfarbe. */
object AyuFocus {
    val border: ClickableSurfaceBorder
        @Composable get() = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AyuColors.Accent)),
        )
}


/**
 * Kino: Ein Samtvorhang in tiefem Rot (senkrechte Falten aus hellen und dunklen Bahnen), oben ein goldener Saum,
 * ein warmes Spotlicht von oben in die Mitte, unten ein dunkler Bühnenrand. Ränder abgedunkelt, damit Schrift
 * überall gut lesbar bleibt.
 */
private class CinemaCurtain(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val base = Brush.verticalGradient(listOf(Color(0xFF2A070C), Color(0xFF14040A), Color(0xFF070203)), endY = h)
    /** Faltenbahnen: je Falte ein waagerechter Verlauf hell → dunkel. */
    private val folds: List<Pair<Float, Brush>> = run {
        val count = 18
        val width = w / count
        List(count) { i ->
            val x = i * width
            val light = 0.10f + 0.06f * kotlin.math.sin(i * 1.7f).let { it * it }
            x to Brush.horizontalGradient(
                listOf(Color.Black.copy(alpha = 0.35f), Color(0xFFB0182A).copy(alpha = light), Color.Black.copy(alpha = 0.45f)),
                startX = x, endX = x + width,
            )
        }
    }
    private val foldWidth = w / 18
    private val valance = Brush.verticalGradient(listOf(Color(0xFF5A0E18), Color(0xFF3A0810)), startY = 0f, endY = h * 0.09f)
    private val gold = Brush.horizontalGradient(listOf(Color(0xFF8A6A1E), Color(0xFFF5C451), Color(0xFF8A6A1E), Color(0xFFF5C451), Color(0xFF8A6A1E)))
    private val spot = Brush.radialGradient(
        listOf(Color(0xFFFFE2A8).copy(alpha = 0.22f), Color(0xFFFFB86B).copy(alpha = 0.07f), Color.Transparent),
        center = Offset(w * 0.5f, h * 0.08f), radius = h * 0.95f,
    )
    private val stage = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), startY = h * 0.72f, endY = h)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
        center = Offset(w * 0.5f, h * 0.45f), radius = maxOf(w, h) * 0.72f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(base)
        for ((x, brush) in folds) drawRect(brush, Offset(x, 0f), androidx.compose.ui.geometry.Size(foldWidth + 1f, h))
        drawRect(valance, size = androidx.compose.ui.geometry.Size(w, h * 0.09f))
        drawRect(gold, Offset(0f, h * 0.09f), androidx.compose.ui.geometry.Size(w, h * 0.006f))
        drawRect(spot)
        drawRect(stage)
        drawRect(vignette)
    }
}

/**
 * Tiefsee: Wasser von Petrol nach Schwarz, schräge Lichtstrahlen von der Oberfläche, eine helle, wellige
 * Oberflächenkante oben und ein paar aufsteigende Luftbläschen.
 */
private class DeepSea(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val water = Brush.verticalGradient(listOf(Color(0xFF0B3A4F), Color(0xFF062132), Color(0xFF020A12), Color(0xFF010407)), endY = h)
    private val rays: List<Pair<androidx.compose.ui.graphics.Path, Brush>> = kotlin.random.Random(3).let { r ->
        List(7) {
            val top = w * (0.05f + r.nextFloat() * 0.9f)
            val spread = w * (0.03f + r.nextFloat() * 0.05f)
            val drift = w * (0.10f + r.nextFloat() * 0.12f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(top - spread, 0f)
                lineTo(top + spread, 0f)
                lineTo(top + drift + spread * 3, h * 0.95f)
                lineTo(top + drift - spread * 3, h * 0.95f)
                close()
            }
            path to Brush.verticalGradient(
                listOf(Color(0xFF9AF3FF).copy(alpha = 0.10f + r.nextFloat() * 0.06f), Color.Transparent),
                startY = 0f, endY = h * 0.95f,
            )
        }
    }
    private val surfaceGlow = Brush.verticalGradient(listOf(Color(0xFF7FE9F5).copy(alpha = 0.28f), Color.Transparent), startY = 0f, endY = h * 0.18f)
    private val surfaceLine = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, h * 0.02f)
        val steps = 10
        for (i in 1..steps) {
            val x = w * i / steps
            quadraticTo(x - w / steps / 2, h * (if (i % 2 == 0) 0.005f else 0.035f), x, h * 0.02f)
        }
    }
    private val bubbles: List<Pair<Offset, Float>> = kotlin.random.Random(11).let { r ->
        List(40) { Offset(r.nextFloat() * w, h * (0.25f + r.nextFloat() * 0.7f)) to (1.5f + r.nextFloat() * 4f) }
    }
    private val depth = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
        center = Offset(w * 0.5f, h * 0.35f), radius = maxOf(w, h) * 0.75f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(water)
        for ((path, brush) in rays) drawPath(path, brush)
        drawRect(surfaceGlow)
        drawPath(surfaceLine, Color(0xFFB8F7FF).copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.003f))
        for ((center, radius) in bubbles) {
            drawCircle(Color(0xFFBFF6FF).copy(alpha = 0.10f), radius = radius, center = center)
            drawCircle(Color(0xFFBFF6FF).copy(alpha = 0.22f), radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
        }
        drawRect(depth)
    }
}

/**
 * Abendrot: Himmel von Nachtviolett über Magenta nach Orange, eine große Sonne mit waagerechten Streifen, die am
 * Horizont versinkt, darunter ein dunkles Spiegelfeld mit perspektivischen Linien (Retro-Look). Oben bleibt es dunkel,
 * damit Menüs und Schrift ruhig wirken.
 */
private class Sunset(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val horizon = h * 0.70f
    private val sky = Brush.verticalGradient(
        listOf(Color(0xFF0A0614), Color(0xFF1E0B33), Color(0xFF5A1450), Color(0xFFB8325A), Color(0xFFF07A4A)),
        startY = 0f, endY = horizon,
    )
    private val sunCenter = Offset(w * 0.68f, horizon - h * 0.02f)
    private val sunRadius = h * 0.24f
    private val sun = Brush.verticalGradient(
        listOf(Color(0xFFFFD27A), Color(0xFFFF8A5B), Color(0xFFE23E7A)),
        startY = sunCenter.y - sunRadius, endY = sunCenter.y + sunRadius,
    )
    private val sunGlow = Brush.radialGradient(
        listOf(Color(0xFFFF8A5B).copy(alpha = 0.35f), Color.Transparent),
        center = sunCenter, radius = sunRadius * 2.4f,
    )
    private val ground = Brush.verticalGradient(listOf(Color(0xFF14081F), Color(0xFF07040C)), startY = horizon, endY = h)
    private val darkTop = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = 0f, endY = h * 0.45f)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
        center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.8f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(sky, size = androidx.compose.ui.geometry.Size(w, horizon))
        drawCircle(sunGlow, radius = sunRadius * 2.4f, center = sunCenter)
        // Sonne nur oberhalb des Horizonts, mit Lücken („Streifen“), die nach unten breiter werden.
        clipRect(bottom = horizon) {
            drawCircle(sun, radius = sunRadius, center = sunCenter)
            var y = sunCenter.y - sunRadius * 0.1f
            var gap = sunRadius * 0.03f
            while (y < horizon) {
                drawRect(Color(0xFF5A1450), Offset(sunCenter.x - sunRadius, y), androidx.compose.ui.geometry.Size(sunRadius * 2, gap))
                y += gap + sunRadius * 0.09f
                gap *= 1.35f
            }
        }
        drawRect(ground, Offset(0f, horizon), androidx.compose.ui.geometry.Size(w, h - horizon))
        drawLine(Color(0xFFFF8A5B).copy(alpha = 0.8f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = h * 0.003f)
        // Perspektivisches Gitter im Boden
        val grid = Color(0xFFE23E7A).copy(alpha = 0.28f)
        val vanish = Offset(w * 0.5f, horizon)
        for (i in -12..12) drawLine(grid, vanish, Offset(w * 0.5f + i * w * 0.12f, h), strokeWidth = 1.5f)
        var t = 0.04f
        while (t < 1f) {
            val y = horizon + (h - horizon) * t
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            t *= 1.55f
        }
        drawRect(darkTop)
        drawRect(vignette)
    }
}


/** Graphit: Anthrazit-Verlauf, zwei weiche Lichtflächen wie auf gebürstetem Metall, feine diagonale Struktur. */
private class Graphite(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val base = Brush.linearGradient(listOf(Color(0xFF1B1D22), Color(0xFF0E0F11), Color(0xFF08090A)), start = Offset(0f, 0f), end = Offset(w, h))
    private val sheen = Brush.radialGradient(listOf(Color(0xFFA5C8FF).copy(alpha = 0.10f), Color.Transparent), center = Offset(w * 0.85f, h * 0.1f), radius = w * 0.55f)
    private val sheen2 = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), center = Offset(w * 0.15f, h * 0.95f), radius = w * 0.45f)
    private val glass = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.55f, 0f); lineTo(w * 0.75f, 0f); lineTo(w * 0.45f, h); lineTo(w * 0.25f, h); close()
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(base)
        drawRect(sheen)
        drawRect(sheen2)
        drawPath(glass, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.025f), Color.Transparent), startX = w * 0.25f, endX = w * 0.75f))
        var x = -h
        while (x < w) {
            drawLine(Color.White.copy(alpha = 0.012f), Offset(x, h), Offset(x + h, 0f), strokeWidth = 1f)
            x += 6f
        }
    }
}

/** Smaragd: Nachthimmel über einem Wald aus Tannen-Silhouetten, Mondlicht oben rechts, Glühwürmchen. */
private class Forest(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val sky = Brush.verticalGradient(listOf(Color(0xFF0B1F18), Color(0xFF07130E), Color(0xFF030806)), endY = h)
    private val moon = Brush.radialGradient(listOf(Color(0xFFE8FFF0).copy(alpha = 0.22f), Color(0xFF4ADE80).copy(alpha = 0.05f), Color.Transparent), center = Offset(w * 0.82f, h * 0.14f), radius = h * 0.45f)
    private fun treeLine(base: Float, height: Float, seed: Int) = androidx.compose.ui.graphics.Path().apply {
        val r = kotlin.random.Random(seed)
        moveTo(0f, h)
        lineTo(0f, base)
        var x = 0f
        while (x < w) {
            val tw = w * (0.025f + r.nextFloat() * 0.03f)
            val th = height * (0.6f + r.nextFloat() * 0.6f)
            lineTo(x + tw / 2, base - th)
            lineTo(x + tw, base)
            x += tw * (0.7f + r.nextFloat() * 0.3f)
        }
        lineTo(w, h)
        close()
    }
    private val far = treeLine(h * 0.80f, h * 0.20f, 5)
    private val near = treeLine(h * 0.92f, h * 0.26f, 9)
    private val flies: List<Pair<Offset, Float>> = kotlin.random.Random(21).let { r ->
        List(55) { Offset(r.nextFloat() * w, h * (0.45f + r.nextFloat() * 0.5f)) to (0.2f + r.nextFloat() * 0.5f) }
    }
    private val readable = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), endX = w * 0.6f)

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(sky)
        drawRect(moon)
        drawPath(far, Color(0xFF0C2418))
        for ((p, a) in flies) {
            drawCircle(Color(0xFFD9FF7A).copy(alpha = a * 0.25f), radius = 7f, center = p)
            drawCircle(Color(0xFFF0FFB0).copy(alpha = a), radius = 1.8f, center = p)
        }
        drawPath(near, Color(0xFF04100A))
        drawRect(readable)
    }
}

/** Neon: Skyline-Silhouette mit leuchtenden Fenstern, Neonlinien in Magenta und Cyan, Spiegelung im nassen Boden. */
private class NeonCity(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val ground = h * 0.80f
    private val sky = Brush.verticalGradient(listOf(Color(0xFF07050F), Color(0xFF1A0B33), Color(0xFF3B0A45)), startY = 0f, endY = ground)
    private val haze = Brush.radialGradient(listOf(Color(0xFFFF2E88).copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.3f, ground), radius = w * 0.5f)
    private val haze2 = Brush.radialGradient(listOf(Color(0xFF22D3EE).copy(alpha = 0.18f), Color.Transparent), center = Offset(w * 0.8f, ground), radius = w * 0.45f)
    private class Tower(val left: Float, val width: Float, val top: Float)
    private val towers: List<Tower> = kotlin.random.Random(42).let { r ->
        val list = ArrayList<Tower>()
        var x = -w * 0.02f
        while (x < w) {
            val bw = w * (0.04f + r.nextFloat() * 0.06f)
            list += Tower(x, bw, ground - h * (0.12f + r.nextFloat() * 0.38f))
            x += bw * (0.9f + r.nextFloat() * 0.3f)
        }
        list
    }
    private val windows: List<Pair<Offset, Color>> = kotlin.random.Random(7).let { r ->
        towers.flatMap { t ->
            val cols = (t.width / (w * 0.012f)).toInt().coerceAtLeast(1)
            val rows = ((ground - t.top) / (h * 0.03f)).toInt()
            buildList {
                for (c in 0 until cols) for (row in 0 until rows) if (r.nextFloat() < 0.18f) {
                    val color = if (r.nextBoolean()) Color(0xFF22D3EE) else Color(0xFFFFC2E5)
                    add(Offset(t.left + (c + 0.5f) * t.width / cols, t.top + (row + 0.6f) * h * 0.03f) to color.copy(alpha = 0.25f + r.nextFloat() * 0.35f))
                }
            }
        }
    }
    private val floor = Brush.verticalGradient(listOf(Color(0xFF14092A), Color(0xFF05030A)), startY = ground, endY = h)
    private val readable = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = 0f, endY = h * 0.45f)

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(sky, size = androidx.compose.ui.geometry.Size(w, ground))
        drawRect(haze)
        drawRect(haze2)
        for (t in towers) drawRect(Color(0xFF0B0716), Offset(t.left, t.top), androidx.compose.ui.geometry.Size(t.width, ground - t.top))
        for ((p, c) in windows) drawRect(c, Offset(p.x - 2f, p.y - 2f), androidx.compose.ui.geometry.Size(4f, 5f))
        drawRect(floor, Offset(0f, ground), androidx.compose.ui.geometry.Size(w, h - ground))
        drawLine(Color(0xFFFF2E88).copy(alpha = 0.85f), Offset(0f, ground), Offset(w, ground), strokeWidth = h * 0.003f)
        drawLine(Color(0xFF22D3EE).copy(alpha = 0.45f), Offset(0f, ground + h * 0.012f), Offset(w, ground + h * 0.012f), strokeWidth = h * 0.002f)
        // Spiegelung der Lichter im nassen Boden
        for ((p, c) in windows) if (p.y > ground - h * 0.2f) {
            drawRect(c.copy(alpha = c.alpha * 0.25f), Offset(p.x - 1.5f, ground + (ground - p.y) * 0.6f), androidx.compose.ui.geometry.Size(3f, 8f))
        }
        drawRect(readable)
    }
}

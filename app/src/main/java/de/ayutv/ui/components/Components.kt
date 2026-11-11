package de.ayutv.ui.components

import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.ui.theme.AyuColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ThreadLocal.withInitial gibt es erst ab API 26.
private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.GERMANY)
}
fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun ProgrammeEntity.progress(now: Long = System.currentTimeMillis()): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    // Überschrift mit kurzem Akzentstrich davor (wie in hochwertigen Streaming-Apps).
    Row(modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(AyuColors.Accent))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = AyuColors.TextPrimary,
        )
    }
}

/** Rahmen der Kacheln: ruhig eine hauchfeine Linie, mit Fokus weiß (dazu das Leuchten von [focusGlow]). */
val CardHairline: androidx.tv.material3.CardBorder
    @Composable get() = CardDefaults.border(
        border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))),
        focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.5.dp, Color.White.copy(alpha = 0.92f))),
    )

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = AyuColors.Progress) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.15f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(color)
        )
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AyuColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(
                text = name.take(3).uppercase(),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AyuColors.TextSecondary,
            )
        } else {
            // Eine feste Dekodiergröße für alle Stellen (Liste, Raster, Info): jedes Logo nur einmal
            // dekodiert und einmal im Speicher, egal in welcher Größe es gerade gezeigt wird.
            val context = LocalPlatformContext.current
            val request = remember(url) { ImageRequest.Builder(context).data(url).size(240, 144).memoryCacheKey(url).build() }
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
    }
}

/** Bild zur Sendung aus dem EPG (16:9), oder nichts, wenn keins da ist. */
@Composable
fun ProgrammeImage(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) return
    val context = LocalPlatformContext.current
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(480, 270).memoryCacheKey(url).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(AyuColors.SurfaceRaised),
    )
}

/**
 * Leuchten um eine gewählte Karte: drei weiche Ringe in der Akzentfarbe (nur Linien, kein Schatten – auch auf dem
 * Stick flüssig). Die Karte wächst beim Fokus um 10 % (Standard der Fernseh-Karten), die Ringe liegen außerhalb.
 */
fun Modifier.focusGlow(focused: Boolean): Modifier = drawBehind {
    if (!focused) return@drawBehind
    val ex = size.width * 0.05f
    val ey = size.height * 0.05f
    for (i in 1..3) {
        val spread = i * 3.dp.toPx()
        drawRoundRect(
            AyuColors.Accent.copy(alpha = 0.18f / i),
            topLeft = androidx.compose.ui.geometry.Offset(-ex - spread, -ey - spread),
            size = androidx.compose.ui.geometry.Size(size.width + 2 * (ex + spread), size.height + 2 * (ey + spread)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx() + spread),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
        )
    }
}

/** Sender-Kachel für horizontale Reihen (Startseite, Empfehlungen). */
@Composable
fun ChannelCard(
    channel: ChannelEntity,
    now: ProgrammeEntity?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(220.dp).onFocusChanged { focused = it.hasFocus }.focusGlow(focused),
        colors = CardDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
        border = CardHairline,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, channel.name, Modifier.size(width = 64.dp, height = 40.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            val headline = androidx.compose.runtime.remember(now) { now?.headlineWithBadge()?.ifEmpty { null } ?: "Keine Programminfo" }
            Text(
                headline,
                style = MaterialTheme.typography.bodySmall,
                color = AyuColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            ProgressLine(now?.progress() ?: 0f)
        }
    }
}

/** Poster-Kachel für Filme und Serien. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(150.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            // Leuchten um das gewählte Poster: drei weiche Ringe in der Akzentfarbe (nur Linien, kein Schatten –
            // auch auf dem Stick flüssig).
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.hasFocus }.focusGlow(focused),
            colors = CardDefaults.colors(containerColor = AyuColors.Surface),
            border = CardHairline,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Ohne Poster (oder wenn es nicht lädt): ruhiger Verlauf mit dem Anfangsbuchstaben, darüber das Bild.
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(AyuColors.SurfaceRaised, AyuColors.Surface))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "▶",
                        fontSize = 56.sp,
                        color = AyuColors.TextSecondary.copy(alpha = 0.45f),
                        modifier = Modifier.padding(bottom = 30.dp),
                    )
                }
                if (!image.isNullOrBlank()) {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Titel im Bild auf dunklem Verlauf (wie in Streaming-Apps), gut lesbar auf jedem Poster; nur einmal.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(start = 8.dp, end = 8.dp, top = 26.dp, bottom = if (progress != null) 14.dp else 8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                }
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = AyuColors.TextSecondary, maxLines = 1)
        }
    }
}

/**
 * Textfeld für die Fernbedienung. Im Ruhezustand ist es ein normales Element, an dem man
 * vorbeinavigieren kann, ohne dass die Bildschirmtastatur aufgeht und die Tasten schluckt.
 * Erst OK öffnet die Eingabe; "Fertig" oder Zurück beendet sie.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val touch = isTouchDevice
    val editRequester = remember { FocusRequester() }
    val viewRequester = remember { FocusRequester() }
    // Nach dem Tippen den Fokus wieder auf das Feld setzen, sonst wäre er verloren.
    LaunchedEffect(editing) {
        if (editing) wasEditing = true else if (wasEditing) runCatching { viewRequester.requestFocus() }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AyuColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AyuColors.TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(AyuColors.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    onImeAction()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(editRequester)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    }
                    .clip(shape)
                    .background(AyuColors.SurfaceRaised)
                    .border(2.dp, AyuColors.Accent, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            LaunchedEffect(Unit) { runCatching { editRequester.requestFocus() } }
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(viewRequester)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, AyuColors.Accent))),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                val shown = when {
                    value.isEmpty() -> ""
                    password -> "•".repeat(value.length.coerceAtMost(16))
                    else -> value
                }
                Text(
                    shown.ifEmpty { if (touch) "Tippen zum Eingeben" else "OK drücken zum Eingeben" },
                    color = if (shown.isEmpty()) AyuColors.TextSecondary else AyuColors.TextPrimary,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyHint(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AyuColors.TextSecondary)
    }
}

/**
 * Kachel für das Abendprogramm („Heute um 20:15“): oben das Bild zur Sendung (sonst groß das Senderlogo), darunter
 * Uhrzeit, Sender und Titel. ⏰ zeigt eine gesetzte Erinnerung.
 */
@Composable
fun EveningCard(
    channel: ChannelEntity,
    programme: ProgrammeEntity,
    reminded: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    androidx.tv.material3.Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(260.dp).onFocusChanged { focused = it.hasFocus }.focusGlow(focused),
        colors = CardDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
        border = CardHairline,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(AyuColors.SurfaceRaised)) {
                if (programme.icon.isNullOrBlank()) {
                    ChannelLogo(channel.logo, channel.name, Modifier.align(Alignment.Center).size(width = 120.dp, height = 72.dp))
                } else {
                    ProgrammeImage(programme.icon, Modifier.fillMaxSize())
                    ChannelLogo(channel.logo, channel.name, Modifier.align(Alignment.BottomStart).padding(8.dp).size(width = 56.dp, height = 34.dp))
                }
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        (if (reminded) "⏰ " else "") + formatTime(programme.start),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                val title = remember(programme) { programme.headline().ifEmpty { programme.title } }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    channel.name + " · " + (if (focused) (if (reminded) "OK: Erinnerung weg · lange OK: jetzt" else "OK: erinnern · lange OK: jetzt") else "${formatTime(programme.start)} – ${formatTime(programme.stop)}"),
                    style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

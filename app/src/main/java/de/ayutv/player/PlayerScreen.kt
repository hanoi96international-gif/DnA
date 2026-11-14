@file:OptIn(UnstableApi::class)

package de.ayutv.player

import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import de.ayutv.ui.components.isTouchDevice
import de.ayutv.ui.components.usingTouch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import de.ayutv.ui.components.isTvDevice
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import de.ayutv.data.AspectMode
import de.ayutv.data.AudioMode
import de.ayutv.data.SubtitleBackground
import de.ayutv.data.SubtitlePosition
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
import androidx.tv.material3.Text
import de.ayutv.data.Languages
import de.ayutv.ui.components.ChannelListItem
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.ProgressLine
import de.ayutv.ui.components.formatDuration
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.progress
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PlayerScreen(activity: PlayerActivity) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val player = activity.player
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        isFocusable = false
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        // Beim Umschalten das letzte Bild stehen lassen statt schwarz zu blenden.
                        setKeepContentOnPlayerReset(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        subtitleView?.apply {
                            setApplyEmbeddedFontSizes(false)
                            setBottomPaddingFraction(0.07f)
                        }
                        this.player = player
                    }
                },
                update = {
                    // Beim Spiegeln läuft das Bild auf dem Fernseher (TvPresentation), nicht hier.
                    it.player = if (activity.mirrorDisplay != null) null else player
                    // Zwei-Finger-Zoom (Handy): Bild füllt den Bildschirm, Ränder werden abgeschnitten.
                    it.resizeMode = when (activity.aspect) {
                        AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    it.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * activity.subtitleScale)
                    // Untertitel für den Fernseher: Farbe, Rand/Schatten/Kasten aus den Einstellungen; eingebettete Stile zählen nicht.
                    it.subtitleView?.setApplyEmbeddedStyles(false)
                    it.subtitleView?.setStyle(captionStyle(activity.subtitleLook))
                    // Eigene Einblendung (Übersetzung, geladene Datei): Die Spur des Players läuft nur als Taktgeber mit.
                    it.subtitleView?.visibility = if (activity.ownSubtitles && !activity.originalVisible) View.INVISIBLE else View.VISIBLE
                    // Handy: Bildwiederholrate des Bildschirms an den Stream anpassen (25 fps ruckelfrei), mit jeder Engine.
                    if (activity.isPhoneDevice) de.ayutv.player.dna.DnaFrameRate.apply(it.videoSurfaceView as? android.view.SurfaceView, activity.stats.frameRate)
                    // Untertitel über die Steuerleiste anheben, solange sie sichtbar ist.
                    val barVisible = activity.overlay == Overlay.CONTROLS || activity.overlay == Overlay.INFO
                    it.subtitleView?.setBottomPaddingFraction(if (barVisible) 0.3f else activity.subtitleLook.position.bottomFraction)
                },
                // Nebeneinander (Multiview): Hauptbild links, zweiter Sender rechts, gleich groß.
                modifier = when {
                    activity.pipPlayer != null && activity.pipSplit -> Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
                    // Senderliste nach Zurück: Bild rechts daneben klein weiter (links die 460 dp breite Liste).
                    activity.miniVideo -> Modifier.align(Alignment.CenterEnd).padding(start = 484.dp, end = 32.dp)
                        .fillMaxWidth().aspectRatio(16f / 9f)
                    else -> Modifier.fillMaxSize()
                },
            )
        }

        // Bildschirm wird auf einen Fernseher gespiegelt: das Video dort als eigene Anzeige in voller Größe.
        val tv = activity.mirrorDisplay
        if (player != null && tv != null) {
            TvPresentation(activity, tv, player)
            // Antippen: Spiegeln beenden oder anderer Fernseher.
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).clickable { activity.mirrorMenu(tv.name) }.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📺 Läuft auf ${tv.name}", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
                Text("Tippen zum Beenden", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (activity.fatalError) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        activity.pipPlayer?.let { pip ->
            if (activity.pipSplit) SplitWindow(pip, activity.pipChannel?.name.orEmpty(), Modifier.align(Alignment.CenterEnd))
            else PipWindow(pip, activity.pipChannel?.name.orEmpty(), Modifier.align(Alignment.BottomEnd))
        }

        if (activity.syncPanel && activity.overlay == Overlay.NONE) {
            SyncBar(activity, Modifier.align(Alignment.BottomCenter))
        }

        activity.dueReminder?.let { reminder ->
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 32.dp).width(460.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .border(2.dp, AyuColors.Accent, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text("⏰ Gleich auf ${reminder.channelName}", color = AyuColors.Accent, fontSize = 15.sp)
                Text(reminder.title, color = Color.White, fontSize = 18.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "OK umschalten" + (if (activity.isLive) "  ·  ⏩ als kleines Bild" else "") + "  ·  Zurück ausblenden",
                    color = AyuColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        activity.goalNotice?.let { notice ->
            Text(
                notice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .border(2.dp, AyuColors.Live, RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        // Bild-in-Bild: nur das Bild, keine Leisten und Hinweise.
        if (!activity.inPip) {

        val touch = isTouchDevice
        if (touch) {
            // Tippen blendet die Leiste ein/aus, doppelt tippen links/rechts spult (Filme).
            // Wischen: rechts Lautstärke, links Helligkeit, waagerecht spulen, Live in der Mitte hoch/runter umschalten.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(activity.isLive) {
                        detectTapGestures(
                            onTap = { activity.onScreenTap() },
                            onDoubleTap = if (activity.isLive) null else { offset: Offset ->
                                activity.pressKey(if (offset.x < size.width / 2) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                            },
                        )
                    }
                    .pointerInput(activity.isLive) { playerGestures(activity) }
            )
        }

        if (activity.buffering && activity.message == null) {
            Text(
                "DNA Player  ·  lädt …",
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(16.dp),
                color = Color.White,
            )
        }

        activity.gestureInfo?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        activity.message?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(20.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (activity.numberInput.isNotEmpty()) {
            Text(
                activity.numberInput,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.INFO && activity.isLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LiveInfoBar(activity)
        }

        // Schnellwahl (◀ halten): Logos der zuletzt gesehenen Sender und Favoriten.
        AnimatedVisibility(
            visible = activity.quickZap != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            activity.quickZap?.let { QuickZapBar(it, activity.quickIndex) }
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.CONTROLS && !activity.isLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VodControls(activity)
        }

        if (!activity.isLive && activity.overlay != Overlay.CONTROLS) {
            val press = Modifier.pointerInput(Unit) { detectTapGestures { activity.pressKey(KeyEvent.KEYCODE_DPAD_CENTER) } }
            when {
                activity.nextCardVisible -> activity.nextEpisode?.let { next ->
                    EpisodeCard(
                        title = "Nächste Folge: S${next.episode.season} E${next.episode.episode}  ${next.episode.title}",
                        hint = if (activity.nextCountdown >= 0) "Startet in ${activity.nextCountdown} s  ·  OK: jetzt  ·  andere Taste: anhalten"
                            else if (touch) "Tippen: jetzt starten" else "OK: jetzt starten",
                        modifier = Modifier.align(Alignment.BottomEnd).then(press),
                    )
                }
            }
        }

        // Vorlauf der Übersetzung: kurz angehalten, bis die nächste Minute übersetzt ist.
        activity.prerollProgress?.let { progress ->
            val press = Modifier.pointerInput(Unit) { detectTapGestures { activity.endPreroll() } }
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).then(press)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Text("Untertitel werden übersetzt … ${(progress * 100).toInt()} %", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress)
                Spacer(Modifier.height(8.dp))
                Text(if (touch) "Tippen: sofort weiterschauen" else "OK: sofort weiterschauen", color = AyuColors.Accent, fontSize = 14.sp)
            }
        }

        // Knöpfe statt Fernbedienung, solange die Leiste zu sehen ist.
        if (touch && (activity.overlay == Overlay.INFO && activity.isLive || activity.overlay == Overlay.CONTROLS && !activity.isLive)) {
            TouchButtons(activity, Modifier.align(Alignment.TopEnd))
        }

        if (activity.ownSubtitles) {
            activity.translatedCue?.let { cue ->
                // Über die Steuerleiste anheben, solange sie sichtbar ist (wie beim Player).
                val barVisible = activity.overlay == Overlay.CONTROLS || activity.overlay == Overlay.INFO || activity.syncPanel
                val look = activity.subtitleLook
                val bottom = when (look.position) {
                    SubtitlePosition.LOW -> 20.dp
                    SubtitlePosition.NORMAL -> 40.dp
                    SubtitlePosition.HIGH -> 80.dp
                    SubtitlePosition.CINEMA -> 98.dp
                }
                Text(
                    cue,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = if (barVisible) 200.dp else bottom)
                        .then(
                            if (look.background == SubtitleBackground.BOX) {
                                Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                            } else Modifier
                        ),
                    color = Color(look.color.argb),
                    fontSize = (22 * activity.subtitleScale).sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = when (look.background) {
                            SubtitleBackground.BOX -> null
                            SubtitleBackground.OUTLINE -> Shadow(Color.Black, Offset(0f, 0f), blurRadius = 4f)
                            SubtitleBackground.SHADOW -> Shadow(Color.Black, Offset(3f, 3f), blurRadius = 6f)
                        },
                    ),
                )
            }
        }

        activity.subtitleStatus?.let { status ->
            Text(
                status,
                // Rechts steht bei offenem Menü die Spurenliste: dann links oben, sonst überdecken sich beide.
                modifier = Modifier.align(if (activity.overlay == Overlay.TRACKS) Alignment.TopStart else Alignment.TopEnd).padding(28.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 14.sp,
            )
            LaunchedEffect(status) {
                // Fehler länger stehen lassen, damit man sie lesen kann.
                delay(if ("nicht" in status || "keine" in status.lowercase()) 12_000 else 5_000)
                activity.clearSubtitleStatus(status)
            }
        }

        // Läuft es auf dem Fernseher: Das Handy wird zur Fernbedienung (Sender, Lautstärke, Pause, zurück aufs Handy).
        AnimatedVisibility(
            visible = activity.castingRemote,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.matchParentSize(),
        ) {
            CastRemote(activity)
        }
        // Hinter dem Menü dunkelt das Bild zur rechten Seite hin sanft ab: Das Menü hebt sich ab, das Bild bleibt sichtbar.
        AnimatedVisibility(
            visible = activity.overlay == Overlay.TRACKS,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize().background(menuScrim))
        }
        AnimatedVisibility(
            visible = activity.overlay == Overlay.TRACKS,
            // Gleichmäßig statt federnd, mit weichem Auslaufen: wirkt ruhig und hochwertig, auf dem Stick in 0,25 s da.
            enter = slideInHorizontally(tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 } +
                fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(180, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { it / 3 } +
                fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            TracksOverlay(activity)
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.CHANNELS,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelListOverlay(activity)
        }
        }
    }

    // Overlays blenden sich nach kurzer Zeit aus. Jede Taste setzt den Timer zurück (overlayToken).
    val touchMenu = usingTouch
    LaunchedEffect(activity.overlayToken, activity.overlay) {
        val timeout = when (activity.overlay) {
            Overlay.NONE -> return@LaunchedEffect
            // Angehaltenes Live-TV: Leiste stehen lassen, damit man sieht, wie weit es zurückliegt.
            Overlay.INFO -> if (activity.timeshifted && activity.player?.playWhenReady == false) Long.MAX_VALUE
                else if (activity.showTech) 8_000L else 3_500L
            // Angehalten: Leiste bleibt. Nicht isPlaying nehmen: beim Laden ist das auch false, dann
            // bliebe die Leiste nach dem Start eines Films für immer stehen (und OK hielte ihn an).
            Overlay.CONTROLS -> if (activity.player?.playWhenReady == false) Long.MAX_VALUE else 4_000L
            // Beim Zuordnen des TV-Guides (Suchen, Blättern in langen Listen) nicht von selbst schließen.
            // Per Touch bleibt es offen, bis man ✕, daneben oder Zurück tippt (Tippen setzt den Timer nicht zurück).
            Overlay.TRACKS -> if (touchMenu || activity.epgPicker || activity.pipChoices != null) Long.MAX_VALUE else 15_000L
            // Mit kleinem Bild (nach Zurück) bleibt die Liste, bis man wählt oder noch einmal Zurück drückt.
            Overlay.CHANNELS -> if (activity.miniVideo) Long.MAX_VALUE else 15_000L
        }
        delay(timeout)
        activity.closeOverlay()
    }
}

internal val bottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))

@Composable
private fun LiveInfoBar(activity: PlayerActivity) {
    val channel = activity.current ?: return
    val now = activity.nowNext.firstOrNull()
    val next = activity.nowNext.getOrNull(1)
    // Die Stream-Werte ändern sich mehrmals pro Sekunde (Datenrate, verworfene Bilder): Die Leiste zeichnet nur neu,
    // wenn sich die Kennzeichen selbst ändern.
    val badges by remember {
        androidx.compose.runtime.derivedStateOf {
            val stats = activity.stats
            de.ayutv.data.parse.StreamBadges.video(stats.height, stats.frameRate, stats.hdr) +
                listOfNotNull(de.ayutv.data.parse.StreamBadges.audio(stats.audioCodec, stats.audioChannels))
        }
    }
    val headline = remember(now) { now?.headlineWithBadge() }
    val description = remember(now) { now?.let { de.ayutv.data.parse.ProgrammeText.description(it.description) } }
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 36.dp)
    ) {
        // Kopf: Logo, Nummer und Name, Kennzeichen (was der Sender gerade wirklich liefert), rechts die Uhrzeit.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 112.dp, height = 68.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}", style = MaterialTheme.typography.titleLarge, color = AyuColors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(channel.name, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (activity.isFavorite) Text("  ★", color = AyuColors.Accent, fontSize = 22.sp)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary, maxLines = 1)
                    badges.forEach { Badge(it) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTime(System.currentTimeMillis()), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    java.text.SimpleDateFormat("EEE, d. MMM", Locale.GERMAN).format(java.util.Date()),
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (now != null) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("JETZT", color = AyuColors.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text("${formatTime(now.start)} – ${formatTime(now.stop)}", color = AyuColors.TextSecondary, fontSize = 14.sp)
                        de.ayutv.data.parse.StreamBadges.remaining(now.stop)?.let {
                            Text("  ·  $it", color = AyuColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                    Text(headline.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(now.progress())
                    description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DANACH", color = AyuColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text("${formatTime(next.start)}  ${next.headline()}", color = AyuColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Bild zur Sendung (wenn der TV-Guide eins hat), rechts daneben.
                if (!now.icon.isNullOrBlank()) {
                    Spacer(Modifier.width(24.dp))
                    de.ayutv.ui.components.ProgrammeImage(now.icon, Modifier.width(208.dp).height(117.dp))
                }
            }
        } else {
            Text("Keine Programminformationen", color = AyuColors.TextSecondary)
        }
        if (activity.timeshifted || activity.rewindableMs > 0) {
            Spacer(Modifier.height(12.dp))
            TimeshiftLine(activity)
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats, activity.bufferedMs, activity.cushionActive, activity.dropInfo, activity.routeLabel(), activity.bufferLabel(), activity.engineLabel)
        }
        activity.syncBadge?.let { badge ->
            Spacer(Modifier.height(8.dp))
            Text(badge, color = Color(0xFF7FD69B), fontSize = 14.sp, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (activity.timeshifted) "OK Pause/Weiter   ◀ −10 s   ▶ +30 s (bis zum Ende = Live)   ▲▼ Umschalten   ☰ Ton/Untertitel"
            else "▲▼ Umschalten   OK Senderliste   " + (activity.previousChannel?.let { "◀ zurück zu ${it.name}   " } ?: "") +
                "◀ halten: Schnellwahl   ▶ Stream-Info   ⏯ Pause   ⏪ Zurückspulen   ☰ Ton/Untertitel/Favorit",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

/** Schnellwahl: Senderlogos nebeneinander, der gewählte hervorgehoben; ◀ ▶ wählen, OK schaltet um. */
@Composable
private fun QuickZapBar(channels: List<de.ayutv.data.db.ChannelEntity>, index: Int) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(index) { state.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 28.dp)) {
        Text("Schnellwahl  ·  ◀ ▶ wählen  ·  OK umschalten  ·  Zurück schließen", color = AyuColors.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(channels.size, key = { channels[it].key }) { i ->
                val c = channels[i]
                val selected = i == index
                Column(
                    Modifier.width(148.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) AyuColors.Accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .background(if (selected) AyuColors.AccentSoft.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ChannelLogo(c.logo, c.name, Modifier.size(width = 120.dp, height = 70.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(c.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private enum class Swipe { NONE, VOLUME, BRIGHTNESS, SEEK, ZAP, PINCH }

/**
 * Wischgesten wie in Handy-Videoplayern. Erst ab einer kleinen Strecke entschieden (sonst ist es ein Tippen),
 * danach bleibt es bei dieser Geste, bis der Finger losgelassen wird.
 */
private suspend fun PointerInputScope.playerGestures(activity: PlayerActivity) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var mode = Swipe.NONE
        var last = start
        var pinchStart = 0f
        val slop = viewConfiguration.touchSlop * 2
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (mode != Swipe.PINCH) {
                    mode = Swipe.PINCH
                    pinchStart = distance
                } else if (pinchStart > 0f) {
                    if (distance / pinchStart > 1.25f) activity.zoomGesture(true)
                    if (distance / pinchStart < 0.8f) activity.zoomGesture(false)
                }
                event.changes.forEach { it.consume() }
                continue
            }
            if (mode == Swipe.PINCH) continue
            val change = pressed.first()
            val pos = change.position
            if (mode == Swipe.NONE) {
                val dx = pos.x - start.x
                val dy = pos.y - start.y
                if (abs(dx) < slop && abs(dy) < slop) continue
                mode = when {
                    abs(dx) > abs(dy) -> Swipe.SEEK
                    activity.isLive && start.x in size.width / 3f..size.width * 2 / 3f -> Swipe.ZAP
                    start.x > size.width / 2f -> Swipe.VOLUME
                    else -> Swipe.BRIGHTNESS
                }
                last = pos
            }
            when (mode) {
                Swipe.VOLUME -> activity.adjustVolume((last.y - pos.y) / size.height * 1.5f)
                Swipe.BRIGHTNESS -> activity.adjustBrightness((last.y - pos.y) / size.height * 1.5f)
                Swipe.SEEK -> activity.seekGesture((pos.x - start.x) / size.width, done = false)
                else -> Unit
            }
            last = pos
            change.consume()
        }
        when (mode) {
            Swipe.SEEK -> activity.seekGesture((last.x - start.x) / size.width, done = true)
            Swipe.ZAP -> if (abs(last.y - start.y) > size.height / 6f) activity.zapGesture(up = last.y < start.y)
            else -> Unit
        }
    }
}

/** Bedienknöpfe für den Touchscreen: dieselben Funktionen wie die Tasten der Fernbedienung. */
@Composable
private fun TouchButtons(activity: PlayerActivity, modifier: Modifier = Modifier) {
    val paused = activity.player?.playWhenReady == false
    Row(
        modifier.padding(16.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun button(label: String, action: () -> Unit) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        if (activity.isLive) {
            button("▲") { activity.pressKey(KeyEvent.KEYCODE_CHANNEL_UP) }
            button("▼") { activity.pressKey(KeyEvent.KEYCODE_CHANNEL_DOWN) }
            button("☰") { activity.openChannelList() }
            button("⏪") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_REWIND) }
            button(if (paused) "▶" else "⏸") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            button("⏩") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            button(if (activity.isFavorite) "★" else "☆") { activity.pressKey(KeyEvent.KEYCODE_PROG_RED) }
        } else {
            button("⏪") { activity.pressKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            button(if (paused) "▶" else "⏸") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            button("⏩") { activity.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        button("CC") { activity.pressKey(KeyEvent.KEYCODE_MENU) }
        if (activity.castAvailable) button(if (activity.castingTo != null) "📺✓" else "📺") { activity.toggleCast() }
        button("✕") { activity.finish() }
    }
}

/** Knopf unten rechts (Intro überspringen, nächste Folge): reagiert auf OK, solange die Steuerleiste zu ist. */
@Composable
private fun EpisodeCard(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(end = 48.dp, bottom = 56.dp).widthIn(max = 520.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = AyuColors.Accent, fontSize = 14.sp)
    }
}

/** Zeitversatz: wie weit das Bild hinter Live liegt und wie weit sich zurückspulen lässt. */
@Composable
private fun TimeshiftLine(activity: PlayerActivity) {
    val window = activity.rewindableMs
    val behind = activity.behindLiveMs.coerceAtMost(window)
    val paused = activity.timeshifted && activity.player?.playWhenReady == false
    Row(verticalAlignment = Alignment.CenterVertically) {
        val state = when {
            paused -> "⏸ Angehalten"
            activity.timeshifted -> "▶ Zeitversetzt"
            else -> "● Live"
        }
        Text(state, color = if (activity.timeshifted) AyuColors.Accent else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            ProgressLine(if (window > 0) (window - behind).toFloat() / window else 1f)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (activity.timeshifted) "−${formatDuration(behind)} hinter Live" else "${formatDuration(window)} zurückspulbar",
            color = AyuColors.TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun VodControls(activity: PlayerActivity) {
    val duration = activity.durationMs
    val position = activity.positionMs
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(activity.vodTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
            val labels by remember { androidx.compose.runtime.derivedStateOf { activity.stats.resolutionLabel to activity.stats.hdr } }
            labels.first.takeIf { it.isNotEmpty() }?.let { Badge(it) }
            labels.second.takeIf { it.isNotEmpty() }?.let { Badge(it) }
        }
        Spacer(Modifier.height(16.dp))
        // Touch: auf den Balken tippen springt dorthin (großzügige Trefferfläche um die dünne Linie).
        Box(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                detectTapGestures { offset -> activity.seekToFraction(offset.x / size.width) }
            },
            contentAlignment = Alignment.Center,
        ) {
            ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(formatDuration(position), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text("noch ${formatDuration(duration - position)}  ·  Ende ${formatTime(System.currentTimeMillis() + duration - position)}", color = AyuColors.TextSecondary, fontSize = 14.sp)
            }
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats, activity.bufferedMs, activity.cushionActive, activity.dropInfo, activity.routeLabel(), activity.bufferLabel(), activity.engineLabel)
        }
        activity.syncBadge?.let { badge ->
            Spacer(Modifier.height(8.dp))
            Text(badge, color = if (badge.startsWith("✓")) Color(0xFF7FD69B) else Color(0xFFFFC870), fontSize = 14.sp, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (activity.player?.playWhenReady == false) "Pausiert  ·  " else "") + "OK Pause   ◀ −10 s   ▶ +30 s (halten = schneller)   ▲ Stream-Info   ☰ Ton/Untertitel",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TechLine(stats: StreamStats, bufferedMs: Long, cushion: Boolean, drops: String, route: String, buffer: String = "", engine: String = "") {
    val parts = buildList {
        if (engine.isNotEmpty()) add(engine)
        add("Puffer %.1f s".format(bufferedMs / 1000f) + if (cushion) " (wird aufgefüllt)" else "")
        if (buffer.isNotEmpty()) add(buffer)
        if (stats.width > 0) add("${stats.width}×${stats.height}")
        if (stats.frameRate > 0) add("%.2f fps".format(stats.frameRate) + if (stats.frameRateMeasured) " (gemessen)" else "")
        if (stats.displayHz > 0) add("Fernseher %.2f Hz".format(stats.displayHz))
        if (stats.videoCodec.isNotEmpty()) add(stats.videoCodec)
        if (stats.videoBitrate > 0) add("%.1f Mbit/s".format(stats.videoBitrate / 1_000_000f))
        if (stats.audioCodec.isNotEmpty()) add("${stats.audioCodec} ${stats.audioChannels}ch")
        if (stats.audioDecoder.isNotEmpty()) add("Ton: " + if ("ffmpeg" in stats.audioDecoder.lowercase()) "FFmpeg" else stats.audioDecoder)
        if (stats.decoder.isNotEmpty()) add(stats.decoder)
        if (stats.bandwidth > 0) add("Datenrate des Senders %.1f Mbit/s".format(stats.bandwidth / 1_000_000f))
        add("Verworfene Frames: ${stats.droppedFrames}")
        if (stats.startupMs > 0) add("Start in ${stats.startupMs} ms")
        if (drops.isNotEmpty()) add(drops)
        add(route)
    }
    Text(parts.joinToString("  ·  "), color = AyuColors.Accent, fontSize = 13.sp)
}

@Composable
internal fun Badge(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(4.dp)).background(AyuColors.AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ChannelListOverlay(activity: PlayerActivity) {
    val channels = activity.channels
    val currentIndex = remember(channels) { channels.indexOfFirst { it.key == activity.current?.key }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 4).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }

    Column(
        Modifier.fillMaxHeight().width(460.dp).background(Color.Black.copy(alpha = 0.88f)).padding(vertical = 24.dp)
    ) {
        // Kopfzeile: ‹ führt zu den Kategorien (antippen oder OK), wie in den anderen Listen.
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(10.dp))
                .clickable { if (activity.groupMode) activity.hideGroups() else activity.showGroups() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            Text(
                if (activity.groupMode) "Kategorien" else activity.listTitle.ifEmpty { "Sender" },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (activity.groupMode) {
            val groupFocus = remember { FocusRequester() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(activity.groupChoices, key = { i, g -> "${g.first}#$i" }) { index, (group, label) ->
                    Surface(
                        onClick = { activity.switchGroup(group) },
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(groupFocus) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.16f),
                        ),
                    ) {
                        Text(label, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                    }
                }
            }
            LaunchedEffect(activity.groupChoices.size) {
                repeat(5) {
                    if (runCatching { groupFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(16)
                }
            }
            return@Column
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(channels, key = { _, c -> c.key }) { index, channel ->
                ChannelListItem(
                    channel = channel,
                    now = channel.epgId?.let { activity.nowPlayingMap[it] },
                    selected = channel.key == activity.current?.key,
                    modifier = Modifier.padding(horizontal = 12.dp)
                        .then(if (index == currentIndex) Modifier.focusRequester(focusRequester) else Modifier),
                    onClick = {
                        activity.closeOverlay()
                        if (channel.key != activity.current?.key) activity.playChannel(channel)
                    },
                )
            }
        }
    }

    LaunchedEffect(channels, activity.groupMode) {
        if (activity.groupMode) return@LaunchedEffect
        // Warten, bis die Zeile komponiert ist, dann den aktuellen Sender fokussieren.
        repeat(5) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Spurmenü: Audiospur, Untertitel (inkl. Laden und Übersetzen in jede Sprache) und bei Live der Favorit. */
internal val MENU_WIDTH = 450.dp
/** Fast deckend, links etwas durchscheinend: Das Bild bleibt zu ahnen, der Text ist gut lesbar (getöntes Glas). */
internal val menuBackground = Brush.horizontalGradient(listOf(Color(0xE6090A0D), Color(0xF7090A0D)))
/** Abdunkeln des Bildes hinter dem Menü, zur rechten Seite hin stärker. */
internal val menuScrim = Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.18f), 1f to Color.Black.copy(alpha = 0.55f))
/** Dunkle Schrift auf der hellen Fokus-Pille. */
internal val OnPill = Color(0xFF111216)

/** Unterseiten des Player-Menüs. */
enum class MenuPage(val title: String, val icon: String) {
    SUBTITLES("Untertitel", "💬"),
    AUDIO("Tonspur", "🔊"),
    SOUND("Klang", "🎚"),
    PICTURE("Bild", "🖼"),
    SLEEP("Schlaf-Timer", "🌙"),
}

/**
 * Player-Menü in zwei Stufen: Übersicht mit dem, was gerade eingestellt ist („Untertitel · Deutsch ›“), dahinter je
 * eine Unterseite. Zurück geht von der Unterseite zur Übersicht, von dort schließt es das Menü.
 */
@Composable
private fun TracksOverlay(activity: PlayerActivity) {
    if (activity.languagePicker) {
        LanguagePicker(activity)
        return
    }
    if (activity.epgPicker) {
        EpgPicker(activity)
        return
    }
    activity.pipChoices?.let { choices ->
        PipPicker(activity, choices)
        return
    }

    val reduced = de.ayutv.ui.UiPerformance.reduced
    val touch = isTouchDevice
    // Handy: nach rechts wischen schließt das Menü (wie es hereingekommen ist).
    val swipe = if (!touch) Modifier else Modifier.pointerInput(Unit) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { if (dragged > 80.dp.toPx()) activity.closeOverlay() },
        ) { _, amount: Float -> dragged += amount }
    }
    val accent = AyuColors.Accent
    Column(
        Modifier.fillMaxHeight().widthIn(max = MENU_WIDTH).fillMaxWidth().background(menuBackground)
            // Glaskante links und ein Hauch Akzentlicht oben rechts.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.width * 0.9f,
                    ),
                )
                drawRect(Color.White.copy(alpha = 0.08f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
            }
            .then(swipe).padding(top = 28.dp),
    ) {
        AnimatedContent(
            targetState = activity.menuPage,
            transitionSpec = {
                val forward = targetState != null
                if (reduced) {
                    fadeIn(tween(110)) togetherWith ExitTransition.None
                } else {
                    // Vor: die Unterseite kommt von rechts, die Übersicht weicht leicht nach links; zurück umgekehrt.
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    (slideInHorizontally(tween(240, easing = ease)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(200, delayMillis = 40))) togetherWith
                        (slideOutHorizontally(tween(200, easing = ease)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(140)))
                }
            },
            label = "menu",
        ) { page ->
            if (page == null) MenuOverview(activity) else MenuSubPage(activity, page)
        }
    }
}

@Composable
internal fun MenuTitle(text: String, close: (() -> Unit)? = null, back: (() -> Unit)? = null) {
    // ✕ und große Tippflächen nur auf dem Handy/Tablet (nicht auf Fernsehern, auch wenn sie einen Touchscreen melden).
    val touch = isTouchDevice && !isTvDevice
    Row(
        Modifier.fillMaxWidth().padding(start = if (touch && back != null) 12.dp else 28.dp, end = if (touch) 8.dp else 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Handy: Pfeil und Titel zusammen als große Fläche (48 dp hoch) zum Zurücktippen.
        val backTap = if (back != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = back) else Modifier
        Row(
            Modifier.weight(1f).then(if (touch) backTap.heightIn(min = 48.dp).padding(horizontal = 8.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) {
                Text("‹", color = AyuColors.Accent, fontSize = if (touch) 30.sp else 26.sp, fontWeight = FontWeight.Bold,
                    modifier = (if (touch) Modifier else backTap).padding(end = 10.dp))
            }
            Text(text, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Handy: ✕ schließt das Menü (auf dem Fernseher die Zurück-Taste).
        if (touch && close != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = close),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = AyuColors.TextSecondary, fontSize = 20.sp) }
        }
    }
}

/** Fokus beim Öffnen einer Seite setzen (die Zeilen sind erst nach dem ersten Bild da). */
@Composable
internal fun FocusOnOpen(requester: FocusRequester) {
    // Per Touch keine Markierung: Die hervorgehobene erste Zeile sähe aus wie die gewählte Einstellung. Mit
    // Fernbedienung/Tasten (auch auf Geräten mit Touchscreen) braucht es den Fokus, sonst gehen die Tasten ins Leere.
    if (usingTouch && !isTvDevice) return
    LaunchedEffect(requester) {
        repeat(8) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

@Composable
private fun MenuOverview(activity: PlayerActivity) {
    val tracks = activity.tracks
    val audio = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO, includeUnsupported = true) }
    val text = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
    val rows = remember { MenuPage.entries.associateWith { FocusRequester() } }
    val liveTarget = activity.liveTranslateTarget
    val external = activity.externalLanguage
    val subtitleValue = when {
        liveTarget != null -> "${Languages.label(liveTarget)} · übersetzt"
        external != null -> "${Languages.label(external)} · geladen"
        activity.textDisabled || text.none { it.selected } -> "Aus"
        else -> text.first { it.selected }.label
    }
    val audioValue = audio.firstOrNull { it.selected }?.label ?: if (audio.isEmpty()) "keine" else "–"
    val soundValue = activity.audioMode.label + if (activity.loudnessLevel) " · angeglichen" else ""
    val sleepValue = if (activity.sleepAt > 0) {
        "endet " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(activity.sleepAt))
    } else "Aus"
    val values = mapOf(
        MenuPage.SUBTITLES to subtitleValue,
        MenuPage.AUDIO to audioValue,
        MenuPage.SOUND to soundValue,
        MenuPage.PICTURE to activity.aspect.label,
        MenuPage.SLEEP to sleepValue,
    )
    Column {
        MenuTitle(if (activity.isLive) "Optionen" else "Wiedergabe", close = { activity.closeOverlay() })
        MenuDivider()
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Serien: jederzeit zur nächsten Folge (wer das im Abspann tut, bringt DnA-TV bei, wo der Abspann beginnt).
            activity.nextEpisode?.let { next ->
                item {
                    ActionRow("⏭", "Nächste Folge: S${next.episode.season} E${next.episode.episode}") { activity.playNextEpisode() }
                }
            }
            MenuPage.entries.forEach { page ->
                item {
                    PageRow(page, values.getValue(page), Modifier.focusRequester(rows.getValue(page))) { activity.openMenuPage(page) }
                }
            }
            // Handy: auf dem Fernseher abspielen (Fire TV mit DnA-TV, LG/Samsung über DLNA, Chromecast).
            if (activity.castAvailable) item {
                ActionRow("📺", if (activity.castingTo != null) "Hier weiterschauen (läuft auf ${activity.castingTo})" else "Auf Fernseher abspielen (LG, Fire TV …)") {
                    activity.closeOverlay()
                    activity.toggleCast()
                }
            }
            // Puffer, Datenrate und Decoder anzeigen – auf dem Fernseher über die Info-Taste, auf dem Handy nur hier.
            item {
                ActionRow("📊", if (activity.showTech) "Technik-Zeile ausblenden (Puffer, Datenrate)" else "Technik-Zeile anzeigen (Puffer, Datenrate)") {
                    activity.toggleTech()
                }
            }
            if (activity.isLive) {
                item { MenuHeader("Sender") }
                item {
                    ActionRow(if (activity.isFavorite) "★" else "☆", if (activity.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") {
                        activity.toggleFavorite()
                    }
                }
                item { ActionRow("⏺", "Diese Sendung aufnehmen") { activity.recordCurrent() } }
                if (activity.pipPlayer == null) item {
                    ActionRow("⧉", "Zweites Spiel als kleines Bild (Konferenz) …") { activity.openPipPicker() }
                } else {
                    item { ActionRow("⇄", "Bilder tauschen (auch mit ⏩)") { activity.swapPip() } }
                    item {
                        ActionRow(if (activity.pipSplit) "◱" else "◫", if (activity.pipSplit) "Zweiten Sender als kleines Bild" else "Nebeneinander (Multiview, gleich groß)") {
                            activity.togglePipSplit()
                        }
                    }
                    item { ActionRow("⧉", "Anderes kleines Bild …") { activity.openPipPicker() } }
                    item { ActionRow("✕", "Kleines Bild schließen") { activity.closePip() } }
                }
                item { ActionRow("📺", "Programm falsch? TV-Guide zuordnen …") { activity.openEpgPicker() } }
            }
            if (activity.isLive) item {
                val failed = activity.decoderFailedChannel
                ActionRow("🧪", if (failed != null) "Decoder-Test: ${failed.name} (Decoder direkt, ohne Player)" else "Decoder-Test für diesen Sender (Fehlersuche)") {
                    activity.runDecoderTest()
                }
            }
        }
    }
    // Zurück von einer Unterseite: Fokus wieder auf deren Zeile, sonst auf „Untertitel“.
    FocusOnOpen(rows.getValue(activity.menuReturn ?: MenuPage.SUBTITLES))
}

@Composable
private fun MenuSubPage(activity: PlayerActivity, page: MenuPage) {
    val first = remember { FocusRequester() }
    Column {
        MenuTitle(page.title, close = { activity.closeOverlay() }, back = { activity.openMenuPage(null) })
        MenuDivider()
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (page) {
                MenuPage.SUBTITLES -> subtitleItems(activity, Modifier.focusRequester(first))
                MenuPage.AUDIO -> audioItems(activity, Modifier.focusRequester(first))
                MenuPage.SOUND -> {
                    AudioMode.entries.forEachIndexed { i, mode ->
                        item { OptionRow(mode.label, activity.audioMode == mode, if (i == 0) Modifier.focusRequester(first) else Modifier) { activity.chooseAudioMode(mode) } }
                    }
                    item { MenuHeader("Lautstärke") }
                    item { OptionRow("Lautstärke angleichen (alle Sender)", activity.loudnessLevel, check = true) { activity.toggleLoudness() } }
                    item { MenuHint("Wirkt, wenn der Ton im Gerät dekodiert wird – nicht bei Dolby/DTS, das an Soundbar oder Receiver geht (Einstellungen → Tonausgabe: „Immer im Gerät dekodieren“).") }
                    item { MenuHeader("Ton-Bild-Abgleich: ${de.ayutv.data.avSyncLabel(activity.avSyncMs)}") }
                    item { ActionRow("⏩", "Ton später (+${de.ayutv.data.AV_SYNC_STEP_MS} ms) – Ton kommt vor den Lippen") { activity.shiftAvSync(de.ayutv.data.AV_SYNC_STEP_MS) } }
                    item { ActionRow("⏪", "Ton früher (−${de.ayutv.data.AV_SYNC_STEP_MS} ms) – Ton kommt nach den Lippen") { activity.shiftAvSync(-de.ayutv.data.AV_SYNC_STEP_MS) } }
                    if (activity.avSyncMs != 0) item { ActionRow("↺", "Zurücksetzen") { activity.setAvSync(0) } }
                    item { MenuHint("Beamer und Fernseher mit Bildverbesserung zeigen das Bild oft 40–150 ms verzögert, die Soundbar spielt sofort. Gilt für dieses Gerät und alle Sender; beim Spielmodus des Beamers meist weniger nötig.") }
                }
                MenuPage.PICTURE -> AspectMode.entries.forEachIndexed { i, mode ->
                    item { OptionRow(mode.label, activity.aspect == mode, if (i == 0) Modifier.focusRequester(first) else Modifier) { activity.chooseAspect(mode) } }
                }
                MenuPage.SLEEP -> {
                    item {
                        OptionRow("Aus", activity.sleepAt <= 0, Modifier.focusRequester(first)) { activity.setSleepTimer(0) }
                    }
                    if (activity.sleepAt > 0) {
                        item { MenuHint("Endet um ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(activity.sleepAt))}") }
                    }
                    listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                        item { OptionRow("In $minutes Minuten", selected = false) { activity.setSleepTimer(minutes) } }
                    }
                    item {
                        OptionRow(if (activity.isLive) "Am Ende der Sendung" else "Am Ende des Films / der Folge", selected = false) {
                            activity.setSleepTimer(PlayerActivity.SLEEP_END)
                        }
                    }
                }
            }
        }
    }
    FocusOnOpen(first)
}

private fun androidx.compose.foundation.lazy.LazyListScope.subtitleItems(activity: PlayerActivity, first: Modifier) {
    val tracks = activity.tracks
    val text = trackOptions(tracks, C.TRACK_TYPE_TEXT)
    val preferred = activity.preferredSubtitleLanguage
    val liveTarget = activity.liveTranslateTarget
    val external = activity.externalLanguage
    item {
        OptionRow("Aus", !activity.ownSubtitles && (activity.textDisabled || text.none { it.selected }), first) { activity.setTextEnabled(false) }
    }
    text.forEach { option ->
        item {
            OptionRow(option.label, option.selected && !activity.textDisabled && !activity.ownSubtitles) {
                activity.selectTrack(option.group, option.index)
            }
        }
    }
    activity.loadedLanguages.forEach { language ->
        item {
            OptionRow("${Languages.label(language)} (geladen)", external == language) { activity.showLoaded(language) }
        }
    }
    // Immer die Wahl: Datei von OpenSubtitles oder die Spur des Films übersetzen (je Film bzw. Serie gemerkt).
    activity.sourceChoiceLanguage?.let { language ->
        val label = Languages.label(language)
        item { MenuHeader("$label: Quelle") }
        item {
            val fit = activity.matchPercent?.takeIf { external == language }?.let { " · passt $it %" }
                ?: activity.autoSwitched?.takeIf { liveTarget == language }?.let { " · passte nur $it %" }
                ?: ""
            OptionRow("OpenSubtitles$fit", external == language && !activity.externalIsTranslation) { activity.chooseSubtitleSource(language, translate = false) }
        }
        item {
            val what = if (activity.hasTranslatableTrack) "Übersetzung der Film-Spur" else "Übersetzung einer Datei in anderer Sprache"
            OptionRow("$what (${activity.translatorName})", liveTarget == language || external == language && activity.externalIsTranslation) {
                activity.chooseSubtitleSource(language, translate = true)
            }
        }
    }
    if (external != null) {
        item { MenuHeader("Untertitel-Zeit") }
        item { ActionRow("⏱", "Untertitel-Zeit einstellen … (${activity.subtitleShiftLabel})") { activity.openSyncPanel() } }
        item {
            val fit = activity.matchPercent?.let { " (diese passt $it %)" }.orEmpty()
            ActionRow("↻", "Andere Untertitel-Datei laden$fit") { activity.reportWrongSubtitles() }
        }
        item { MenuHint(syncHint(activity)) }
        activity.speechSyncInfo?.let { info -> item { MenuHint("Ausrichtung: $info") } }
        activity.fileTranslationInfo?.let { info -> item { MenuHint(info) } }
    }
    if (!activity.isLive) {
        item { MenuHeader("Weitere Sprachen") }
        if (preferred !in activity.loadedLanguages && external != preferred) {
            // Wie früher: die Quelle gleich wählen – Datei von OpenSubtitles oder Übersetzung (DeepL bzw. Gerät).
            if (activity.onlineSubtitlesAvailable) {
                item { ActionRow("⬇", "${Languages.label(preferred)} von OpenSubtitles") { activity.chooseSubtitleSource(preferred, translate = false) } }
            } else {
                item { ActionRow("⬇", "${Languages.label(preferred)} laden") { activity.loadSubtitles(preferred) } }
            }
            if (activity.translationAvailable) {
                item { ActionRow("文", "In ${Languages.label(preferred)} übersetzen (${activity.translatorName})") { activity.chooseSubtitleSource(preferred, translate = true) } }
            }
        }
        item { ActionRow("🌐", "Andere Sprache laden / übersetzen …") { activity.openLanguagePicker() } }
    }
    if (activity.translationAvailable && external == null && text.any { it.selected } && !activity.textDisabled) {
        if (liveTarget == null) {
            item { OptionRow("Gewählte Spur live übersetzen: ${Languages.label(preferred)}", false) { activity.startLiveTranslation(preferred) } }
        } else {
            item { OptionRow("Live-Übersetzung (${Languages.label(liveTarget)}) beenden", true) { activity.stopLiveTranslation() } }
        }
    }
    activity.lastOnlineProblem?.let { problem -> item { MenuHint("Internet-Untertitel: $problem") } }
    if (!activity.isLive && !activity.onlineSubtitlesAvailable && !activity.translationAvailable) {
        item { MenuHint("Für Filme ganz ohne Untertitel: OpenSubtitles verbinden (Einstellungen → Einrichtung per Handy)") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioItems(activity: PlayerActivity, first: Modifier) {
    // Ton: auch Spuren zeigen, die das Gerät nicht abspielen kann, damit klar ist, warum es still bleibt.
    val audio = trackOptions(activity.tracks, C.TRACK_TYPE_AUDIO, includeUnsupported = true)
    if (audio.isEmpty()) {
        // Ohne Zeile mit Fokus hinge die Fernbedienung: ein Eintrag, der zurück zur Übersicht führt.
        item { ActionRow("‹", "Dieser Stream hat keine Tonspur", first) { activity.openMenuPage(null) } }
    }
    audio.forEachIndexed { i, option ->
        item {
            OptionRow(option.label, option.selected, if (i == 0) first else Modifier) {
                if (option.supported) activity.selectTrack(option.group, option.index)
                else activity.explainUnsupportedAudio(option.group.getTrackFormat(option.index))
            }
        }
    }
    if (audio.isNotEmpty() && audio.none { it.supported }) {
        item { MenuHint("Keine dieser Tonspuren kann das Gerät wiedergeben. Abhilfe: AV-Receiver/Soundbar per HDMI oder einen anderen Stream wählen.") }
    }
}

/** Alle Sprachen: lädt menschliche Untertitel (OpenSubtitles) oder übersetzt automatisch. */
@Composable
private fun LanguagePicker(activity: PlayerActivity) {
    val firstItem = remember { FocusRequester() }
    val embeddedSelected = !activity.textDisabled && trackOptions(activity.tracks, C.TRACK_TYPE_TEXT).any { it.selected }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = MENU_WIDTH).fillMaxWidth().background(menuBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Sprache wählen") }
        item {
            MenuHint(
                "DnA-TV nimmt eine passende Spur oder lädt Untertitel. Gibt es keine, " +
                    "werden vorhandene Untertitel automatisch auf dem Gerät übersetzt (kostenlos, ohne Konto)."
            )
        }
        Languages.all.forEachIndexed { index, language ->
            item {
                OptionRow(
                    language.label,
                    selected = false,
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                    onLongClick = if (embeddedSelected && activity.translationAvailable) ({ activity.startLiveTranslation(language.code) }) else null,
                ) { activity.loadSubtitles(language.code) }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/**
 * TV-Guide von Hand zuordnen: alle Sender der geladenen EPGs, die mit ähnlichem Namen zuerst. Die Wahl gilt
 * dauerhaft für diesen Sender (auch nach dem Neuladen der Playlist).
 */
@Composable
private fun EpgPicker(activity: PlayerActivity) {
    val channel = activity.current ?: return
    var filter by remember { mutableStateOf("") }
    val firstItem = remember { FocusRequester() }
    // Schon nach Ähnlichkeit sortiert (im Hintergrund, PlayerActivity.openEpgPicker); das Suchfeld filtert nur noch.
    val choices = remember(activity.epgChoices, filter) {
        val f = filter.trim().lowercase()
        activity.epgChoices.asSequence().filter { f.isEmpty() || f in it.name.lowercase() || f in it.id.lowercase() }.take(300).toList()
    }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = MENU_WIDTH).fillMaxWidth().background(menuBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Programm für „${channel.name}“") }
        item { MenuHint("Den Sender wählen, dessen Programm stimmt. Gilt dauerhaft, der TV-Guide wird danach neu geladen.") }
        item {
            de.ayutv.ui.components.TvTextField(filter, { filter = it }, "Suchen", modifier = Modifier.fillMaxWidth())
        }
        if (activity.epgOverride != null) item {
            OptionRow("↺ Wieder automatisch zuordnen", selected = false) { activity.assignEpg(null) }
        }
        when {
            activity.epgChoicesLoading -> item { MenuHint("Sender der TV-Guides werden geladen …") }
            activity.epgChoices.isEmpty() -> item { MenuHint("Noch kein TV-Guide geladen.") }
        }
        choices.forEachIndexed { index, c ->
            item(key = c.id) {
                OptionRow(
                    "${c.name}  ·  ${c.source}",
                    selected = c.id == (activity.epgOverride ?: channel.epgId),
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                ) { activity.assignEpg(c.id) }
            }
        }
    }
    LaunchedEffect(choices.isNotEmpty()) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Zweites Bild: laufende Spiele, voriger Sender, Favoriten. */
@Composable
private fun PipPicker(activity: PlayerActivity, choices: List<Pair<de.ayutv.data.db.ChannelEntity, String>>) {
    val firstItem = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = MENU_WIDTH).fillMaxWidth().background(menuBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Kleines Bild dazu") }
        item { MenuHint("Läuft ohne Ton unten rechts. ⏩ tauscht die Bilder, ☰ schließt. Braucht eine zweite Verbindung beim Anbieter.") }
        if (choices.isEmpty()) item { MenuHint("Gerade kein Spiel, kein voriger Sender und keine Favoriten.") }
        choices.forEachIndexed { index, (channel, label) ->
            item(key = channel.key) {
                OptionRow(label, selected = false, modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier) {
                    activity.startPip(channel)
                }
            }
        }
    }
    LaunchedEffect(choices.isNotEmpty()) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Das kleine Bild: eigene Fläche über dem Hauptbild (SurfaceView als Medien-Overlay). */
@Composable
private fun PipWindow(player: androidx.media3.common.Player, label: String, modifier: Modifier) {
    Column(modifier.padding(end = 36.dp, bottom = 36.dp).width(420.dp)) {
        androidx.compose.runtime.key(player) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context).apply {
                        setZOrderMediaOverlay(true)
                        player.setVideoSurfaceView(this)
                    }
                },
                onRelease = { view -> player.clearVideoSurfaceView(view) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(2.dp, Color.White.copy(alpha = 0.8f)),
            )
        }
        Text(
            "$label  ·  ⏩ tauschen",
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Multiview: zweiter Sender gleich groß rechts neben dem Hauptbild (ohne Ton; ⏩ tauscht, der Ton folgt dem Hauptbild). */
@Composable
private fun SplitWindow(player: androidx.media3.common.Player, label: String, modifier: Modifier) {
    Box(modifier.fillMaxHeight().fillMaxWidth(0.5f)) {
        androidx.compose.runtime.key(player) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context).apply {
                        setZOrderMediaOverlay(true)
                        player.setVideoSurfaceView(this)
                    }
                },
                onRelease = { view -> player.clearVideoSurfaceView(view) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center),
            )
        }
        Text(
            "$label  ·  ⏩ tauschen",
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Ausrichten mit einem Druck: die nächste Zeile groß, OK sobald sie gesprochen wird. */
@Composable
private fun SyncBar(activity: PlayerActivity, modifier: Modifier) {
    val index = activity.syncCandidate
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .border(2.dp, AyuColors.Accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Untertitel ausrichten", color = AyuColors.Accent, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("jetzt ${activity.subtitleShiftLabel}", color = AyuColors.TextSecondary, fontSize = 14.sp)
        }
        activity.syncCue(index - 1)?.let { Text(it, color = AyuColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        Text(
            activity.syncCue(index)?.let { "„$it“" } ?: "Keine Zeilen geladen",
            color = Color.White,
            fontSize = 26.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        activity.syncCue(index + 1)?.let { Text(it, color = AyuColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(
            activity.syncConfirmed ?: "OK drücken, sobald diese Zeile gesprochen wird",
            color = if (activity.syncConfirmed != null) Color(0xFF7FD69B) else Color.White,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "◀ ▶ andere Zeile   ·   ▲ früher  ▼ später (½ s)   ·   ⏯ Pause   ·   Zurück: fertig",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Untertitel des Players im gewählten Aussehen. */
private fun captionStyle(look: SubtitleLook): CaptionStyleCompat = CaptionStyleCompat(
    look.color.argb,
    if (look.background == SubtitleBackground.BOX) 0xB3000000.toInt() else AndroidColor.TRANSPARENT,
    AndroidColor.TRANSPARENT,
    when (look.background) {
        SubtitleBackground.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleBackground.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleBackground.BOX -> CaptionStyleCompat.EDGE_TYPE_NONE
    },
    AndroidColor.BLACK,
    null,
)

/** Wie die geladenen Untertitel gerade liegen, in Worten. */
private fun syncHint(activity: PlayerActivity): String {
    val timing = activity.subtitleTiming
    val total = timing.offsetMs + activity.subtitleDelayMs
    val shift = String.format(Locale.GERMAN, "%+.1f s", total / 1000f)
    return when {
        activity.subtitleAutoSynced && activity.subtitleDelayMs == 0L ->
            (if (activity.syncedBySpeech) "Automatisch am gesprochenen Ton ausgerichtet ($shift" else "Automatisch an den eingebetteten Untertiteln ausgerichtet ($shift") +
                (if (timing.ratio != 1.0) ", Bildrate korrigiert" else "") + ")."
        activity.subtitleAutoSynced -> "Automatisch ausgerichtet, von Hand nachgestellt: $shift."
        else -> "Verschiebung: $shift. DnA-TV richtet die Zeiten nach wenigen Minuten automatisch aus (an eingebetteten Untertiteln oder am gesprochenen Ton)."
    }
}

private class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean, val supported: Boolean)

private fun trackOptions(tracks: Tracks, type: Int, includeUnsupported: Boolean = false): List<TrackOption> = buildList {
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            val supported = group.isTrackSupported(i)
            if (!supported && !includeUnsupported) continue
            val label = describe(group.getTrackFormat(i), type, size + 1) + if (supported) "" else " · nicht abspielbar"
            add(TrackOption(group, i, label, group.isTrackSelected(i), supported))
        }
    }
}

private fun describe(format: Format, type: Int, number: Int): String {
    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN).replaceFirstChar(Char::uppercase)
    }
    val parts = mutableListOf(format.label ?: language ?: "Spur $number")
    if (format.label != null && language != null && language !in format.label!!) parts += language
    if (type == C.TRACK_TYPE_AUDIO) {
        when (format.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3_JOC -> parts += "Dolby Atmos"
            MimeTypes.AUDIO_E_AC3 -> parts += "Dolby Digital Plus"
            MimeTypes.AUDIO_AC3 -> parts += "Dolby Digital"
            MimeTypes.AUDIO_TRUEHD -> parts += "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> parts += "DTS"
            MimeTypes.AUDIO_DTS_HD -> parts += "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> parts += "DTS Express"
            MimeTypes.AUDIO_AAC -> parts += "AAC"
            MimeTypes.AUDIO_OPUS -> parts += "Opus"
            MimeTypes.AUDIO_FLAC -> parts += "FLAC"
            MimeTypes.AUDIO_MPEG -> parts += "MP3"
        }
        when (format.channelCount) {
            6 -> parts += "5.1"
            8 -> parts += "7.1"
            2 -> parts += "Stereo"
        }
    } else if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
        parts += "erzwungen"
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun MenuHeader(text: String) {
    // Kleine Versalien mit Sperrung: ruhige, edle Gliederung statt farbiger Überschrift.
    Text(
        text.uppercase(),
        color = AyuColors.TextSecondary,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Hauchfeine Linie unter der Überschrift. */
@Composable
internal fun MenuDivider() {
    Box(Modifier.padding(horizontal = 28.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
}

@Composable
internal fun MenuHint(text: String) {
    Text(text, color = AyuColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Kästchen (an/aus) statt Auswahlpunkt (eins von mehreren). */
    check: Boolean = false,
    onClick: () -> Unit,
) {
    MenuSurface(onClick, modifier, onLongClick) { focused ->
        MenuIndicator(selected, check, focused)
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            color = if (focused) OnPill else Color.White,
            fontSize = 16.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Zeile im Player-Menü: Fokus als helle Pille mit dunkler Schrift (wie die Seitenleiste), ohne Vergrößern (ruhig auch
 * auf schwachen Sticks). [content] bekommt, ob die Zeile den Fokus hat.
 */
@Composable
internal fun MenuSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0xFFF4F4F6),
            pressedContainerColor = Color(0xFFDADADF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { content(focused) }
    }
}

/** Auswahlpunkt (rund) bzw. Kästchen mit Haken, gezeichnet statt Schriftzeichen: überall gleich groß und scharf. */
@Composable
internal fun MenuIndicator(selected: Boolean, check: Boolean, focused: Boolean = false) {
    val shape = if (check) RoundedCornerShape(5.dp) else CircleShape
    val ring = if (focused) OnPill.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f)
    Box(
        Modifier.size(20.dp).clip(shape)
            .background(if (selected) AyuColors.Accent else Color.Transparent)
            .border(2.dp, if (selected) AyuColors.Accent else ring, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (check) {
                // Haken als Linie gezeichnet.
                androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.12f, size.height * 0.55f)
                        lineTo(size.width * 0.42f, size.height * 0.82f)
                        lineTo(size.width * 0.9f, size.height * 0.2f)
                    }
                    drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            } else Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** Kachel mit gezeichnetem Symbol: ruhig grau, mit Fokus in der Akzentfarbe. */
@Composable
internal fun MenuTile(focused: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (focused) AyuColors.Accent else Color.White.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Zeile der Übersicht: Symbol, Name, aktueller Wert, Pfeil zur Unterseite. */
@Composable
internal fun PageRow(page: MenuPage, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MenuSurface(onClick, modifier) { focused ->
        MenuTile(focused) { MenuGlyph(page, if (focused) Color.White else Color.White.copy(alpha = 0.85f)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title, color = if (focused) OnPill else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                value,
                color = if (focused) OnPill.copy(alpha = 0.62f) else AyuColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MenuChevron(if (focused) OnPill.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.35f))
    }
}

/** Pfeil nach rechts, gezeichnet. */
@Composable
internal fun MenuChevron(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.15f)
            lineTo(size.width * 0.8f, size.height * 0.5f)
            lineTo(size.width * 0.2f, size.height * 0.85f)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/**
 * Symbole der Menüseiten als Linien gezeichnet (statt Emojis, die je Gerät anders und bunt aussehen): Sprechblase mit
 * Zeilen, Lautsprecher mit Wellen, Schieberegler, Bild mit Bergen, Mond.
 */
@Composable
internal fun MenuGlyph(page: MenuPage, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, androidx.compose.ui.geometry.Offset(w * x1, h * y1), androidx.compose.ui.geometry.Offset(w * x2, h * y2), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
        when (page) {
            MenuPage.SUBTITLES -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = stroke)
                line(0.24f, 0.44f, 0.5f, 0.44f); line(0.6f, 0.44f, 0.76f, 0.44f)
                line(0.24f, 0.62f, 0.36f, 0.62f); line(0.46f, 0.62f, 0.76f, 0.62f)
            }
            MenuPage.AUDIO -> {
                val body = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.08f, h * 0.38f); lineTo(w * 0.26f, h * 0.38f); lineTo(w * 0.48f, h * 0.18f)
                    lineTo(w * 0.48f, h * 0.82f); lineTo(w * 0.26f, h * 0.62f); lineTo(w * 0.08f, h * 0.62f); close()
                }
                drawPath(body, color, style = stroke)
                drawArc(color, -45f, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.3f),
                    size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.4f), style = stroke)
                drawArc(color, -50f, 100f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.14f),
                    size = androidx.compose.ui.geometry.Size(w * 0.54f, h * 0.72f), style = stroke)
            }
            MenuPage.SOUND -> {
                for ((x, knob) in listOf(0.22f to 0.62f, 0.5f to 0.32f, 0.78f to 0.55f)) {
                    line(x, 0.12f, x, 0.88f)
                    drawCircle(color, radius = w * 0.1f, center = androidx.compose.ui.geometry.Offset(w * x, h * knob))
                }
            }
            MenuPage.PICTURE -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = stroke)
                val hills = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.14f, h * 0.74f); lineTo(w * 0.4f, h * 0.46f); lineTo(w * 0.58f, h * 0.64f)
                    lineTo(w * 0.7f, h * 0.54f); lineTo(w * 0.86f, h * 0.74f)
                }
                drawPath(hills, color, style = stroke)
                drawCircle(color, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.34f))
            }
            MenuPage.SLEEP -> {
                val moon = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f))
                }
                val bite = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(w * 0.32f, h * -0.04f, w * 1.06f, h * 0.7f))
                }
                drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite), color, style = stroke)
            }
        }
    }
}

/** Einmalige Aktion (Favorit, Aufnahme, Datei laden …): Symbol statt Auswahlpunkt. */
@Composable
internal fun ActionRow(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MenuSurface(onClick, modifier) { focused ->
        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
            Text(icon, color = AyuColors.Accent, fontSize = 18.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (focused) OnPill else Color.White, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Fernbedienung auf dem Handy, solange der Fernseher abspielt: links Sender ▲/▼, rechts Lautstärke +/−, in der Mitte
 * was läuft, Senderliste, letzter Sender, Pause und „Hier weiterschauen“. Jeder neue Sender geht direkt an den
 * Fernseher, ohne ihn neu auszuwählen.
 */
@Composable
private fun CastRemote(activity: PlayerActivity) {
    val accent = AyuColors.Accent
    val channel = activity.current
    val programme = activity.nowNext.firstOrNull { it.start <= System.currentTimeMillis() && it.stop > System.currentTimeMillis() }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C0D11), Color(0xFF050506))))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2, 0f), radius = size.width * 0.7f,
                    ),
                )
            }
            // Tippen daneben tut nichts (die Steuerung des Handy-Players liegt darunter).
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activity.isLive) Rocker("SENDER", "▲", "▼", onUp = { activity.remoteZap(1) }, onDown = { activity.remoteZap(-1) })
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Wohin übertragen wird.
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "LÄUFT AUF ${activity.castingTo.orEmpty().uppercase()}",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (activity.isLive && channel != null) {
                    de.ayutv.ui.components.ChannelLogo(channel.logo, channel.name, Modifier.size(width = 96.dp, height = 56.dp))
                    Spacer(Modifier.height(8.dp))
                }
                val filmRemote = !activity.isLive && activity.castDuration != null
                Text(
                    if (activity.isLive) channel?.name.orEmpty() else activity.vodTitle,
                    color = Color.White, fontSize = if (filmRemote) 20.sp else 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                programme?.let {
                    val headline = remember(it) { it.headline() }
                    Text(headline, color = AyuColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                activity.castStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = if (it.startsWith("Schalte")) accent else AyuColors.TextSecondary.copy(alpha = 0.8f), fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(if (filmRemote) 12.dp else 16.dp))
                if (filmRemote) {
                    FilmRemote(activity)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (activity.isLive) {
                        RemotePill("☰  Senderliste") { activity.openChannelList() }
                        if (activity.previousChannel != null) RemotePill("↩  Letzter") { activity.remoteLastChannel() }
                    } else {
                        RemotePill("⏯  Pause / Weiter") { activity.remotePlayPause() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemotePill("📺  Anderer Fernseher") { activity.chooseOtherTv() }
                    RemotePill("📱  Hier weiterschauen", filled = true) { activity.stopCastingHere() }
                }
            }
            if (activity.remoteHasVolume) {
                Rocker("LAUT", "+", "−", onUp = { activity.remoteVolume(2) }, onDown = { activity.remoteVolume(-2) }, value = activity.castVolume?.toString())
            }
        }
    }
}

/**
 * Film auf dem Fernseher (umverpackt): Stelle mit Balken zum Antippen, Spulen ±10/±30 s, Pause, Untertitel und
 * Tonspur. Mehrere Tipper aufs Spulen werden gesammelt, dann öffnet der Fernseher an der neuen Stelle.
 */
@Composable
private fun FilmRemote(activity: PlayerActivity) {
    val accent = AyuColors.Accent
    val duration = activity.castDuration ?: return
    val target = activity.castSeekTarget
    val position = target ?: activity.castPosition ?: 0.0
    val fraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                activity.formatClock(position), color = if (target != null) accent else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp),
            )
            // Balken: Antippen springt an die Stelle.
            Box(
                Modifier.weight(1f).height(28.dp)
                    .pointerInput(duration) {
                        detectTapGestures { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            activity.remoteSeekTo(f * duration)
                        }
                    }
                    .drawBehind {
                        val y = size.height / 2
                        val h = 4.dp.toPx()
                        drawRoundRect(
                            Color.White.copy(alpha = 0.16f), topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                            size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h),
                        )
                        drawRoundRect(
                            accent, topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2),
                            size = androidx.compose.ui.geometry.Size(size.width * fraction, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h),
                        )
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * fraction, y))
                    },
            )
            Text(
                activity.formatClock(duration), color = AyuColors.TextSecondary, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(64.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SeekButton("−30") { activity.remoteSeek(-30) }
            SeekButton("−10") { activity.remoteSeek(-10) }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFF4F4F6)).clickable { activity.remotePlayPause() },
                contentAlignment = Alignment.Center,
            ) { Text("⏯", color = OnPill, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            SeekButton("+10") { activity.remoteSeek(10) }
            SeekButton("+30") { activity.remoteSeek(30) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemotePill("💬  " + if (activity.castHasSubtitles) "Untertitel: ${activity.castSubtitleState.ifBlank { "an" }}" else "Untertitel") {
                activity.chooseCastSubtitles()
            }
            if (activity.castAudioTracks.size > 1) {
                val current = activity.castAudioTracks.firstOrNull { it.index == activity.castAudioIndex }
                RemotePill("🔊  " + (current?.label()?.substringBefore(" · ") ?: "Tonspur")) { activity.chooseCastAudio() }
            }
        }
    }
}

@Composable
internal fun SeekButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.09f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

/** Wippe wie auf einer Fernbedienung: oben/unten, Beschriftung in der Mitte. */
@Composable
internal fun Rocker(label: String, up: String, down: String, onUp: () -> Unit, onDown: () -> Unit, value: String? = null) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(38.dp)).background(Color.White.copy(alpha = 0.07f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape).clickable(onClick = onUp), contentAlignment = Alignment.Center) {
            Text(up, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text(value ?: label, color = AyuColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Box(Modifier.size(76.dp).clip(CircleShape).clickable(onClick = onDown), contentAlignment = Alignment.Center) {
            Text(down, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun RemotePill(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) Color(0xFFF4F4F6) else Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) OnPill else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Video auf dem gespiegelten Fernseher als eigene Anzeige (Android-Präsentation): Statt des Handybildschirms mit Rändern
 * zeigt der Fernseher nur das Bild, in seiner Auflösung und im Format 16:9, mit Untertiteln.
 */
@Composable
private fun TvPresentation(activity: PlayerActivity, display: android.view.Display, player: androidx.media3.common.Player) {
    // Eigene Untertitel (übersetzt, geladene Datei) zeichnet sonst nur das Handy: auf dem Fernseher als eigene Zeile.
    val cueView = remember(display.displayId) {
        android.widget.TextView(activity).apply {
            gravity = android.view.Gravity.CENTER
            setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
            visibility = View.GONE
        }
    }
    val playerViewRef = remember(display.displayId) { arrayOfNulls<PlayerView>(1) }
    // Stil, Größe und Inhalt laufend wie auf dem Handy.
    val cue = activity.translatedCue
    val own = activity.ownSubtitles && !activity.originalVisible
    val look = activity.subtitleLook
    val scale = activity.subtitleScale
    androidx.compose.runtime.SideEffect {
        cueView.text = cue.orEmpty()
        cueView.visibility = if (activity.ownSubtitles && !cue.isNullOrBlank()) View.VISIBLE else View.GONE
        cueView.setTextColor(look.color.argb.toInt())
        // Fernseher: Schrift im Verhältnis zur Bildhöhe (wie die Untertitel des Players), nicht in Handy-Pixeln.
        cueView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, display.mode.physicalHeight * 0.05f * scale)
        cueView.setBackgroundColor(if (look.background == SubtitleBackground.BOX) 0xB3000000.toInt() else AndroidColor.TRANSPARENT)
        playerViewRef[0]?.subtitleView?.apply {
            setStyle(captionStyle(look))
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            visibility = if (own) View.INVISIBLE else View.VISIBLE
        }
    }
    androidx.compose.runtime.DisposableEffect(display.displayId, player) {
        val playerView = PlayerView(activity).apply {
            useController = false
            setShutterBackgroundColor(AndroidColor.BLACK)
            setKeepContentOnPlayerReset(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            subtitleView?.setApplyEmbeddedStyles(false)
            subtitleView?.setStyle(captionStyle(activity.subtitleLook))
            this.player = player
        }
        playerViewRef[0] = playerView
        (cueView.parent as? ViewGroup)?.removeView(cueView)
        val view = android.widget.FrameLayout(activity).apply {
            addView(playerView, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val pad = (display.mode.physicalHeight * 0.06f).toInt()
            addView(
                cueView,
                android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { setMargins(pad * 2, 0, pad * 2, pad) },
            )
        }
        val presentation = runCatching {
            android.app.Presentation(activity, display).apply {
                // Ganze Fläche des Fernsehers: Ein Dialogfenster (das ist eine Präsentation) ist sonst nur so groß wie
                // sein Inhalt – das Bild stand klein in einer Ecke.
                setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                window?.apply {
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AndroidColor.BLACK))
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    decorView.setPadding(0, 0, 0, 0)
                    attributes = attributes.apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = android.view.Gravity.FILL
                    }
                }
                show()
            }
        }.getOrNull()
        // Ließ sich nicht öffnen: wieder hier abspielen (sonst wäre das Bild nirgends).
        if (presentation == null) activity.presentationFailed()
        // Bildrate des Fernsehers an das Video anpassen (Sender mit 50 Bildern/s auf 50 Hz statt 60 Hz), wenn die
        // Spiegel-Verbindung das anbietet: Bei 50 Bildern auf 60 Hz wird jedes fünfte doppelt gezeigt – das Bild ruckelt.
        fun matchRate() {
            val fps = activity.stats.frameRate.takeIf { it > 1f } ?: return
            val window = presentation?.window ?: return
            val modes = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList())
            val current = runCatching { display.mode }.getOrNull() ?: return
            val best = modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .filter { m -> val ratio = m.refreshRate / fps; kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.02f && ratio >= 0.98f }
                .minByOrNull { it.refreshRate } ?: return
            if (best.modeId != current.modeId) runCatching { window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId } }
        }
        val rateListener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = matchRate()
        }
        player.addListener(rateListener)
        matchRate()
        onDispose {
            player.removeListener(rateListener)
            playerView.player = null
            playerViewRef[0] = null
            runCatching { presentation?.dismiss() }
        }
    }
}


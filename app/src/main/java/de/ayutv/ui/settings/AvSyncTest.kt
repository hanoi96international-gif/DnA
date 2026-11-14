package de.ayutv.ui.settings

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import de.ayutv.data.AV_SYNC_STEP_MS
import de.ayutv.data.avSyncLabel
import de.ayutv.ui.components.Button
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Testbild für den Ton-Bild-Abgleich: Jede Sekunde ein kurzer Piepton und gleichzeitig ein weißer Blitz. Der Blitz
 * richtet sich nach dem Zeitpunkt, an dem das Gerät den Ton wirklich ausgibt (Zeitstempel der Tonausgabe, wie im
 * Player) – übrig bleibt nur die Verzögerung des Fernsehers oder Beamers, und genau die gleicht der Abgleich aus.
 * Kommt der Piep vor dem Blitz: „Ton später“, bis beide zusammenfallen.
 */
@Composable
fun AvSyncTestDialog(valueMs: Int, onChange: (Int) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val offset by rememberUpdatedState(valueMs)
        val beeper = remember { Beeper() }
        DisposableEffect(Unit) {
            beeper.start()
            onDispose { beeper.stop() }
        }
        var flash by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                flash = beeper.beepVisible(offset)
            }
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(30)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .background(if (flash) Color.White else Color(0xFF1A1A1A), CircleShape),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Piep und Blitz sollen genau gleichzeitig kommen. Piep zu früh: Ton später. Piep zu spät: Ton früher.",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                Text("Jetzt: ${avSyncLabel(valueMs)}", color = Color.White, fontSize = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { onChange(valueMs - AV_SYNC_STEP_MS) }) { Text("◀ Ton früher") }
                    Button(onClick = { onChange(valueMs + AV_SYNC_STEP_MS) }, modifier = Modifier.focusRequester(first)) { Text("Ton später ▶") }
                    Button(onClick = { onChange(0) }) { Text("Zurücksetzen") }
                    Button(onClick = onClose) { Text("Fertig") }
                }
                Text(
                    "Am besten mit derselben Tonausgabe wie beim Schauen (Soundbar/Receiver an). Gilt für dieses Gerät.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp,
                )
            }
        }
    }
}

/** Spielt jede Sekunde 100 ms Ton (1 kHz) und weiß, wann dieser Ton aus dem Gerät kommt. */
private class Beeper {
    private val rate = 48_000
    private val beepFrames = rate / 10
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val stamp = AudioTimestamp()

    fun start() {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setBufferSizeInBytes(maxOf(min, rate * 4 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        running = true
        // Eine Sekunde: 100 ms Ton mit weichem Ein- und Ausblenden (kein Knacken), dann Stille.
        val second = ShortArray(rate * 2)
        for (i in 0 until beepFrames) {
            val fade = minOf(1.0, i / 240.0, (beepFrames - i) / 240.0)
            val v = (sin(2 * PI * 1000.0 * i / rate) * 0.5 * fade * Short.MAX_VALUE).toInt().toShort()
            second[2 * i] = v
            second[2 * i + 1] = v
        }
        t.play()
        thread = Thread({
            while (running) {
                if (runCatching { t.write(second, 0, second.size) }.getOrDefault(-1) < 0) break
            }
        }, "dna-av-test").apply { start() }
    }

    /**
     * Gerade zu sehen? Der Blitz gehört zum Ton, der jetzt aus dem Gerät kommt – um [offsetMs] vorgezogen
     * (wie der Player das Bild beim Abgleich früher zeigt).
     */
    fun beepVisible(offsetMs: Int): Boolean {
        val t = track ?: return false
        val frame = runCatching {
            if (t.getTimestamp(stamp)) stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
            else t.playbackHeadPosition.toLong()
        }.getOrDefault(0L)
        if (frame <= 0) return false
        val shifted = frame + offsetMs.toLong() * rate / 1000
        if (shifted < 0) return false
        return shifted % rate < beepFrames
    }

    fun stop() {
        running = false
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        thread?.join(500)
        runCatching { t.release() }
    }
}

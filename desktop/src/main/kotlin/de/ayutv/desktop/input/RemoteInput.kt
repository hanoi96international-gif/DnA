package de.ayutv.desktop.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import java.awt.Window
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Eine Stelle für alle Tasten: Tastatur, Fire-TV-Fernbedienung (über [WindowsRemote]) und Handy-Fernbedienung.
 *
 * Tasten, die AWT nicht kennt (Zurück, Home, Menü, Spulen, Play/Pause, Sender ±), werden als Tastendruck mit den
 * Compose-Tastencodes ([Key.Back], [Key.Menu], [Key.MediaPlayPause] …) in das DnA-TV-Fenster gegeben. So kommen sie
 * genau wie Tastatur-Tasten beim gerade ausgewählten Element an, Fokus und Zurück-Logik gelten für beide gleich.
 * Gehaltene Pfeil- und Spultasten wiederholen wie auf dem Fire TV.
 */
class RemoteInput {
    /** Hauptfenster (wird beim Start gesetzt). */
    @Volatile var window: Window? = null
    /** DnA-TV-Fenster vorn (vom Fenster gemeldet; für den Fernbedienungs-Thread). */
    @Volatile var foreground = false

    /** Zuletzt erkannte Taste und woher (Tastentest in den Einstellungen). */
    var lastPress by mutableStateOf<String?>(null); private set
    /** Seit dem Start gesehene Fernbedienung (null = noch keine Taste der Fernbedienung). */
    var source by mutableStateOf<RemoteSource?>(null); private set

    private val windows = WindowsRemote(
        onButton = { button, pressed, from -> SwingUtilities.invokeLater { onButton(button, pressed, from) } },
        onUnknown = { usage, from ->
            SwingUtilities.invokeLater {
                if (from != null) source = from
                lastPress = "Unbekannte Taste 0x%03X (%s)".format(usage, from?.label ?: "HID")
            }
        },
        isForeground = { foreground },
    )

    val windowsAvailable: Boolean get() = WindowsRemote.isWindows
    /** Fehler beim Einrichten der Fernbedienungs-Erkennung (null = läuft oder nicht Windows). */
    val problem: String? get() = windows.problem

    fun start() = windows.start()
    fun stop() = windows.stop()

    // ---------------------------------------------------------------- Tasten einspeisen

    private val held = HashMap<RemoteButton, Timer>()
    private val downAt = HashMap<RemoteButton, Long>()

    private fun onButton(button: RemoteButton, pressed: Boolean, from: RemoteSource?) {
        if (from != null) source = from
        if (pressed) {
            lastPress = "${button.label}${from?.let { "  ·  ${it.label}" }.orEmpty()}"
            press(button)
        } else {
            release(button)
        }
    }

    /** Taste drücken (bis [release]); gehaltene Tasten mit [RemoteButton.repeats] wiederholen. */
    fun press(button: RemoteButton) {
        if (button in held) return
        val code = codeFor(button)
        downAt[button] = System.currentTimeMillis()
        dispatch(AwtKeyEvent.KEY_PRESSED, code)
        val timer = Timer(REPEAT_DELAY_MS) {
            val since = System.currentTimeMillis() - (downAt[button] ?: 0L)
            // Kommt kein Loslassen (Verbindung weg), nicht ewig weiterlaufen lassen.
            if (since > STUCK_MS) release(button) else if (button.repeats) dispatch(AwtKeyEvent.KEY_PRESSED, code)
        }.apply {
            delay = REPEAT_MS
            initialDelay = REPEAT_DELAY_MS
            isRepeats = true
            start()
        }
        held[button] = timer
    }

    fun release(button: RemoteButton) {
        held.remove(button)?.stop() ?: return
        downAt.remove(button)
        dispatch(AwtKeyEvent.KEY_RELEASED, codeFor(button))
    }

    /** Kurz drücken (Handy-Fernbedienung). */
    fun tap(button: RemoteButton) = SwingUtilities.invokeLater {
        lastPress = "${button.label}  ·  Handy"
        val code = codeFor(button)
        dispatch(AwtKeyEvent.KEY_PRESSED, code)
        dispatch(AwtKeyEvent.KEY_RELEASED, code)
    }

    private fun dispatch(type: Int, code: Int) {
        val w = window ?: return
        if (!w.isShowing) return
        val target = w.mostRecentFocusOwner ?: w.focusOwner ?: w
        target.dispatchEvent(AwtKeyEvent(target, type, System.currentTimeMillis(), 0, code, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD))
    }

    companion object {
        private const val REPEAT_DELAY_MS = 450
        private const val REPEAT_MS = 90
        private const val STUCK_MS = 15_000L

        /** AWT-Tastencode, unter dem Compose die Taste erkennt. */
        fun codeFor(button: RemoteButton): Int = when (button) {
            RemoteButton.UP -> AwtKeyEvent.VK_UP
            RemoteButton.DOWN -> AwtKeyEvent.VK_DOWN
            RemoteButton.LEFT -> AwtKeyEvent.VK_LEFT
            RemoteButton.RIGHT -> AwtKeyEvent.VK_RIGHT
            RemoteButton.OK -> AwtKeyEvent.VK_ENTER
            RemoteButton.BACK -> Key.Back.nativeKeyCode
            RemoteButton.HOME -> Key.SystemHome.nativeKeyCode
            RemoteButton.MENU -> Key.Menu.nativeKeyCode
            RemoteButton.PLAY_PAUSE -> Key.MediaPlayPause.nativeKeyCode
            RemoteButton.PLAY -> Key.MediaPlay.nativeKeyCode
            RemoteButton.PAUSE -> Key.MediaPause.nativeKeyCode
            RemoteButton.STOP -> Key.MediaStop.nativeKeyCode
            RemoteButton.REWIND -> Key.MediaRewind.nativeKeyCode
            RemoteButton.FAST_FORWARD -> Key.MediaFastForward.nativeKeyCode
            RemoteButton.NEXT -> Key.MediaNext.nativeKeyCode
            RemoteButton.PREVIOUS -> Key.MediaPrevious.nativeKeyCode
            RemoteButton.CHANNEL_UP -> Key.ChannelUp.nativeKeyCode
            RemoteButton.CHANNEL_DOWN -> Key.ChannelDown.nativeKeyCode
            RemoteButton.LAST_CHANNEL -> Key.LastChannel.nativeKeyCode
            RemoteButton.INFO -> Key.Info.nativeKeyCode
            RemoteButton.GUIDE -> Key.Guide.nativeKeyCode
            RemoteButton.CAPTIONS -> Key.Captions.nativeKeyCode
            RemoteButton.SEARCH -> Key.Search.nativeKeyCode
            RemoteButton.SETTINGS -> Key.Settings.nativeKeyCode
            RemoteButton.RED -> Key.ProgramRed.nativeKeyCode
            RemoteButton.GREEN -> Key.ProgramGreen.nativeKeyCode
            RemoteButton.YELLOW -> Key.ProgramYellow.nativeKeyCode
            RemoteButton.BLUE -> Key.ProgramBlue.nativeKeyCode
        }
    }
}

/**
 * Was eine Taste bedeutet, gleich ob Tastatur, Fire-TV-Fernbedienung oder Handy. Tastatur: Pfeile, Enter = OK,
 * Esc/Rücktaste = Zurück, Kontextmenü-Taste = Menü, Leertaste = Play/Pause, Bild ↑/↓ = Sender ±, Pos1 nicht (Listen).
 */
fun KeyEvent.remote(): RemoteButton? = when (key) {
    Key.DirectionUp -> RemoteButton.UP
    Key.DirectionDown -> RemoteButton.DOWN
    Key.DirectionLeft -> RemoteButton.LEFT
    Key.DirectionRight -> RemoteButton.RIGHT
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> RemoteButton.OK
    Key.Escape, Key.Back -> RemoteButton.BACK
    Key.Backspace -> if (isCtrlPressed || isAltPressed) null else RemoteButton.BACK
    Key.SystemHome -> RemoteButton.HOME
    Key.Menu -> RemoteButton.MENU
    Key.Spacebar, Key.MediaPlayPause -> RemoteButton.PLAY_PAUSE
    Key.MediaPlay -> RemoteButton.PLAY
    Key.MediaPause -> RemoteButton.PAUSE
    Key.MediaStop -> RemoteButton.STOP
    Key.MediaRewind -> RemoteButton.REWIND
    Key.MediaFastForward -> RemoteButton.FAST_FORWARD
    Key.MediaNext -> RemoteButton.NEXT
    Key.MediaPrevious -> RemoteButton.PREVIOUS
    Key.ChannelUp, Key.PageUp -> RemoteButton.CHANNEL_UP
    Key.ChannelDown, Key.PageDown -> RemoteButton.CHANNEL_DOWN
    Key.LastChannel -> RemoteButton.LAST_CHANNEL
    Key.Info -> RemoteButton.INFO
    Key.Guide -> RemoteButton.GUIDE
    Key.Captions -> RemoteButton.CAPTIONS
    Key.Search -> RemoteButton.SEARCH
    Key.Settings -> RemoteButton.SETTINGS
    Key.ProgramRed -> RemoteButton.RED
    Key.ProgramGreen -> RemoteButton.GREEN
    Key.ProgramYellow -> RemoteButton.YELLOW
    Key.ProgramBlue -> RemoteButton.BLUE
    else -> if (key.nativeKeyCode == AwtKeyEvent.VK_CONTEXT_MENU) RemoteButton.MENU else null
}

/** Ziffer 0–9 (Zahlenreihe oder Ziffernblock), sonst null. */
fun KeyEvent.digit(): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

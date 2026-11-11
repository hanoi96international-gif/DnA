package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.UserData

/**
 * Klang-Einstellungen für die DNA Engine ([VideoPlayer.reconfigure]); alles gilt sofort. Das Untertitel-Aussehen
 * (Größe, Farbe, Hintergrund, Position) liest die Oberfläche selbst, sie zeichnet die Untertitel.
 */
object PlayerOptions {
    fun engine(user: UserData): List<String> = engine(user.audioMode, user.normalizeVolume)

    fun engine(audioMode: AudioMode, normalizeVolume: Boolean): List<String> = buildList {
        // Nachtmodus und Dialoge: laute Stellen leiser, leise lauter (Dynamik-Kompressor).
        if (audioMode != AudioMode.NORMAL) add("compressor")
        if (normalizeVolume) add("normvol")
    }

    /** Klangregelung: Dialoge = Sprachbereich (1–6 kHz) anheben, Bässe und Höhen leiser. */
    fun equalizer(user: UserData): List<Float>? = when (user.audioMode) {
        AudioMode.DIALOG -> listOf(-4f, -3f, -1f, 2f, 4f, 5f, 3f, 0f, -1f, -2f)
        else -> null
    }
}

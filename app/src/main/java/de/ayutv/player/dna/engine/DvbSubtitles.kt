package de.ayutv.player.dna.engine

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/** Bild-Untertitel als Einblendung für die Anzeige des Players (Bitmap an seiner Stelle im Bild). */
@OptIn(UnstableApi::class)
internal fun SubtitleImage.toCue(): Cue? {
    val bitmap = runCatching { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    return Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(x.toFloat() / displayWidth)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(y.toFloat() / displayHeight, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(width.toFloat() / displayWidth)
        .setBitmapHeight(height.toFloat() / displayHeight)
        .build()
}

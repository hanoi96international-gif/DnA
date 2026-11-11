package de.ayutv.data

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/** Spracheingabe über die Spracherkennung des Geräts (Android TV, Google TV); Fire TV hat keine eigene. */
object VoiceInput {
    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sender, Film oder Serie sagen")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    fun available(context: Context): Boolean =
        runCatching { intent().resolveActivity(context.packageManager) != null }.getOrDefault(false)
}

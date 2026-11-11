package de.ayutv.data.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.ayutv.AyuApp
import de.ayutv.R
import de.ayutv.data.settingsDataStore
import de.ayutv.player.PlayerActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Erinnerung an eine Sendung: kurz vor Beginn eine Benachrichtigung, ein Klick schaltet um. */
@Serializable
data class Reminder(val channelKey: String, val channelName: String, val title: String, val start: Long) {
    val id: String get() = "$channelKey@$start"
}

class Reminders(private val context: Context) {
    private val key = stringPreferencesKey("reminders")
    private val json = Json { ignoreUnknownKeys = true }

    /** Alle künftigen Erinnerungen, nach Startzeit. Abgelaufene fallen automatisch weg. */
    val all: Flow<List<Reminder>> = context.settingsDataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun isSet(channelKey: String, start: Long) = all.first().any { it.channelKey == channelKey && it.start == start }

    /** Setzt die Erinnerung oder nimmt sie wieder weg. Liefert true, wenn sie jetzt gesetzt ist. */
    suspend fun toggle(reminder: Reminder): Boolean {
        var nowSet = false
        context.settingsDataStore.edit { prefs ->
            val list = decode(prefs[key])
            val next = if (list.any { it.id == reminder.id }) {
                list.filterNot { it.id == reminder.id }
            } else {
                nowSet = true
                (list + reminder).sortedBy { it.start }
            }
            prefs[key] = json.encodeToString(LIST, next)
        }
        if (nowSet) schedule(reminder) else cancel(reminder)
        return nowSet
    }

    /** Fehlende Erinnerungen setzen (bestehende bleiben, nichts wird entfernt). Liefert die neu gesetzten. */
    suspend fun ensure(wanted: List<Reminder>): List<Reminder> {
        if (wanted.isEmpty()) return emptyList()
        val added = ArrayList<Reminder>()
        context.settingsDataStore.edit { prefs ->
            val list = decode(prefs[key])
            val ids = list.mapTo(HashSet()) { it.id }
            // Zur selben Zeit nur eine Erinnerung (gleiche Sendung auf mehreren Sendern).
            val starts = list.mapTo(HashSet()) { it.start to it.title }
            for (r in wanted) if (r.id !in ids && (r.start to r.title) !in starts) { added += r; ids += r.id; starts += r.start to r.title }
            if (added.isNotEmpty()) prefs[key] = json.encodeToString(LIST, (list + added).sortedBy { it.start })
        }
        added.forEach(::schedule)
        return added
    }

    /** Nach Neustart des Geräts oder der App: alle künftigen Erinnerungen wieder einplanen. */
    suspend fun rescheduleAll() = all.first().forEach(::schedule)

    private fun decode(raw: String?): List<Reminder> {
        val now = System.currentTimeMillis()
        return runCatching { json.decodeFromString(LIST, raw ?: "[]") }.getOrDefault(emptyList())
            .filter { it.start > now - GRACE }
    }

    private fun schedule(reminder: Reminder) {
        val at = reminder.start - LEAD
        if (at < System.currentTimeMillis() - GRACE) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(reminder)
        // Fire OS (bis Android 11) erlaubt exakte Wecker ohne Extra-Recht; neuere Geräte nur mit Recht.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancel(reminder: Reminder) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(reminder))
    }

    private fun pendingIntent(reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.id.hashCode(),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_CHANNEL, reminder.channelKey)
            .putExtra(EXTRA_CHANNEL_NAME, reminder.channelName)
            .putExtra(EXTRA_TITLE, reminder.title)
            .putExtra(EXTRA_START, reminder.start),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private val LIST = ListSerializer(Reminder.serializer())
        const val ACTION_REMIND = "de.ayutv.REMIND"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val CHANNEL_ID = "reminders"
        /** Eine Minute vorher erinnern. */
        const val LEAD = 60_000L
        const val GRACE = 5 * 60_000L
    }
}

/** Zeigt die Erinnerung an und plant nach einem Geräteneustart alle Erinnerungen neu ein. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as AyuApp
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                app.container.scope.launch {
                    try {
                        app.container.reminders.rescheduleAll()
                        app.container.recordings.rescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
            Reminders.ACTION_REMIND -> {
                notify(context, intent)
                // Läuft gerade DnA-TV: im Bild einblenden (Benachrichtigungen erscheinen auf Fire TV über Apps kaum).
                val channel = intent.getStringExtra(Reminders.EXTRA_CHANNEL) ?: return
                app.container.dueReminder.value = Reminder(
                    channel,
                    intent.getStringExtra(Reminders.EXTRA_CHANNEL_NAME).orEmpty(),
                    intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty(),
                    intent.getLongExtra(Reminders.EXTRA_START, System.currentTimeMillis()),
                )
                if (app.container.foreground?.get() is de.ayutv.MainActivity) {
                    android.widget.Toast.makeText(
                        context,
                        "Gleich auf ${intent.getStringExtra(Reminders.EXTRA_CHANNEL_NAME).orEmpty()}: ${intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty()}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun notify(context: Context, intent: Intent) {
        val channelKey = intent.getStringExtra(Reminders.EXTRA_CHANNEL) ?: return
        val channelName = intent.getStringExtra(Reminders.EXTRA_CHANNEL_NAME).orEmpty()
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(Reminders.CHANNEL_ID, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            channelKey.hashCode(),
            PlayerActivity.liveIntent(context, channelKey, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, Reminders.CHANNEL_ID)
        } else {
            android.app.Notification.Builder(context).setPriority(android.app.Notification.PRIORITY_HIGH)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Gleich auf $channelName")
            .setContentText("$title – jetzt umschalten")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(channelKey.hashCode(), notification) }
    }
}

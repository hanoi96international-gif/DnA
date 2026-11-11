package de.ayutv.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.ayutv.AyuApp
import java.util.concurrent.TimeUnit

/**
 * Hält Playlists und TV-Guide aktuell, auch wenn die App gerade nicht offen ist: Beim nächsten
 * Einschalten ist „Jetzt läuft“ sofort richtig, statt erst nach einem Abgleich.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AyuApp).container
        // Läuft gerade etwas im Player: später (der Import würde Speicher und Rechner mit der Wiedergabe teilen).
        if (container.watching && runAttemptCount < 8) return Result.retry()
        return runCatching {
            container.playlists.syncAll()
            container.refreshTeamReminders()
        }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

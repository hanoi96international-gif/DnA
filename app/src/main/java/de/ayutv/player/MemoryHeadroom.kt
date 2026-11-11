package de.ayutv.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock

/**
 * Darf der Puffer noch wachsen? Prüft den App-Speicher (Java-Heap) und den freien Arbeitsspeicher des Geräts. Das
 * System beendet Apps, lange bevor der Heap voll ist, wenn das ganze Gerät knapp wird (Fire TV Stick mit 1,5–2 GB).
 * Der Gerätewert wird höchstens alle 2 s abgefragt: Der Player fragt beim Laden sehr oft.
 */
class MemoryHeadroom(context: Context) {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)
    private val info = ActivityManager.MemoryInfo()
    @Volatile private var systemOk = true
    @Volatile private var checkedAt = 0L

    fun ok(): Boolean {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory()
        val heapOk = runtime.totalMemory() < max * 9 / 10 || runtime.freeMemory() >= max / 8
        if (!heapOk) return false
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt > 2_000) {
            checkedAt = now
            systemOk = runCatching {
                activityManager.getMemoryInfo(info)
                !info.lowMemory && info.availMem > info.threshold + info.threshold / 2
            }.getOrDefault(true)
        }
        return systemOk
    }
}

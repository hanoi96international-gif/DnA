package de.ayutv.player

/** Abbrüche der Verbindung zum Anbieter beim laufenden Sender: wann und warum. */
class ConnectionDrops {
    class Drop(val at: Long, val reason: String)

    private val list = ArrayDeque<Drop>()

    /** Steigt bei jedem Abbruch; der Player merkt daran, dass es einen neuen gibt. */
    @Volatile var count = 0
        private set

    @Synchronized fun add(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        list.addLast(Drop(now, reason))
        while (list.size > 50) list.removeFirst()
        count++
    }

    @Synchronized fun recent(windowMs: Long): List<Drop> {
        val since = android.os.SystemClock.elapsedRealtime() - windowMs
        return list.filter { it.at >= since }
    }

    @Synchronized fun clear() {
        list.clear()
        count = 0
    }
}

package de.ayutv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import de.ayutv.AppContainer
import de.ayutv.data.repo.ContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Serien im Player: „Nächste Folge“ in der letzten Minute mit Countdown, danach startet sie von selbst (wenn
 * eingestellt). Eine andere Taste hält den Countdown an, OK startet sofort. Das frühere „Intro überspringen“ (am Ton
 * erkannt) ist entfallen: Es klappte bei vielen Serien nicht zuverlässig.
 */
class EpisodeExtras(private val host: Host) {
    interface Host {
        val player: androidx.media3.common.Player?
        val vod: VodRequest?
        val positionMs: Long
        val durationMs: Long
        val scope: CoroutineScope
        val container: AppContainer
        val autoNextEpisode: Boolean
        fun closeOverlay()
        /** Fortschritt sichern, [next] starten und diesen Player schließen. */
        fun startEpisode(next: ContentRepository.NextEpisode)
    }

    /** Die nächste Folge (im Hintergrund nachgeschlagen), sonst null. */
    var nextEpisode by mutableStateOf<ContentRepository.NextEpisode?>(null); private set
    /** Hinweis „Nächste Folge“ (in der letzten Minute und nach dem Ende). */
    var nextCardVisible by mutableStateOf(false); private set
    /** Sekunden bis zum automatischen Start der nächsten Folge, -1 = kein Countdown. */
    var nextCountdown by mutableIntStateOf(-1); private set

    /** Countdown für diese Folge angehalten (andere Taste gedrückt). */
    private var cancelled = false
    private var starting = false

    /** Neue Folge: die nächste nachschlagen. */
    fun prepare(request: VodRequest) {
        cancelled = false
        starting = false
        nextCountdown = -1
        host.scope.launch { nextEpisode = host.container.content.nextEpisode(request.key) }
    }

    /** Jede Sekunde: in der letzten Minute Hinweis und Countdown, bei 0 die nächste Folge starten. */
    fun update(@Suppress("UNUSED_PARAMETER") request: VodRequest) {
        val p = host.player ?: return
        val duration = host.durationMs
        val position = host.positionMs
        val remaining = duration - position
        // Nie in der ersten Hälfte (kurze Folge, falsche Länge): OK soll dort nicht versehentlich weiterschalten.
        val lastMinute = nextEpisode != null && duration > 0 && remaining <= NEXT_BEFORE_END_MS && position >= duration / 2
        nextCardVisible = lastMinute || nextCardVisible && p.playbackState == Player.STATE_ENDED
        nextCountdown = if (lastMinute && host.autoNextEpisode && !cancelled && p.playWhenReady) {
            ((remaining + 999) / 1000).toInt().coerceIn(0, 60)
        } else -1
        if (nextCountdown == 0 || nextCountdown in 0..1 && remaining <= 500) playNext()
    }

    /** Folge zu Ende: nächste Folge starten (wenn eingestellt und nicht angehalten), sonst anbieten. false = keine nächste. */
    fun onEnded(): Boolean {
        if (nextEpisode == null) return false
        nextCardVisible = true
        host.closeOverlay()
        if (host.autoNextEpisode && !cancelled) playNext()
        return true
    }

    /** Andere Taste in der letzten Minute: nicht von selbst weiterschalten (OK startet weiterhin sofort). */
    fun cancelCountdown() {
        if (nextCountdown < 0 && cancelled) return
        cancelled = true
        nextCountdown = -1
    }

    fun playNext() {
        val next = nextEpisode ?: return
        if (starting) return
        starting = true
        nextCountdown = -1
        host.startEpisode(next)
    }

    companion object {
        /** So lange vor dem Ende erscheint „Nächste Folge“ und läuft der Countdown. */
        const val NEXT_BEFORE_END_MS = 60_000L
    }
}

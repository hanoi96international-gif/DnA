package de.ayutv

import de.ayutv.data.parse.pairing
import android.app.Activity
import android.app.Application
import android.os.Bundle
import de.ayutv.ui.components.hasTouchscreen
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.launch
import de.ayutv.data.repo.SyncWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import okio.Path.Companion.toOkioPath

class AyuApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** DnA-TV ist zu sehen (Spielplan nur dann auffrischen). */
    @Volatile private var visible = false

    override fun onCreate() {
        super.onCreate()
        AppTime.fixZone()
        val process = processName()
        CrashReport.install(this, process)
        // Der Übersetzer-Prozess braucht nichts davon (keine Datenbank, keine Synchronisierung).
        if (process.endsWith(":mt")) return
        de.ayutv.ui.UiPerformance.init(this)
        de.ayutv.data.repo.OpenSubtitlesQuota.init(this)
        // Speicher fast voll: gleich aufräumen, nicht erst nachts (sonst wird der ganze Fire TV träge).
        Thread {
            runCatching { if (de.ayutv.data.StorageCleaner.isLow(this)) android.util.Log.i("AyuStorage", de.ayutv.data.StorageCleaner.clean(this)) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        container = AppContainer(this)
        de.ayutv.ui.UiPerformance.busy = { container.playlists.state.value is de.ayutv.data.repo.SyncState.Running }
        // Spielplan (OpenLigaDB) für die Partie im TV-Guide und in der Vorschau, wenn der TV-Guide nur „Live: 2. BL“ nennt.
        // Nur solange DnA-TV zu sehen ist, alle 10 Minuten (Einstellung „Spielstände“).
        de.ayutv.data.sport.MatchHints.install(container.liveScores)
        container.scope.launch {
            // Nicht gleich beim Start (da lädt die Oberfläche), und die Sport-Sendungen nur alle 10 Minuten durchsuchen:
            // ohne Netz läuft die Schleife jede Minute, das Durchsuchen kostet auf dem Stick aber spürbar Rechenzeit.
            kotlinx.coroutines.delay(20_000)
            var shows = emptyList<de.ayutv.data.sport.LiveScores.Show>()
            var showsAt = 0L
            while (true) {
                if (visible && runCatching { container.settings.current().liveScores }.getOrDefault(false)) {
                    // Laufende Sport-Sendungen (3 Stunden): welche Ligen gebraucht werden, dann Spielpläne laden und alle
                    // gemeinsam zuordnen (per Ausschluss über alle Sender) – auch ohne Besuch im Sport-Bereich steht bei
                    // „Live: BL“ die Partie.
                    if (System.currentTimeMillis() - showsAt >= 10 * 60_000L) {
                        shows = runCatching {
                            container.content.sportEvents(hours = 3).map { e ->
                                e.programme.let { de.ayutv.data.sport.LiveScores.Show(it.title, it.subtitle, it.description, it.start, it.stop, it.pairing()) }
                            }
                        }.getOrDefault(emptyList())
                        showsAt = System.currentTimeMillis()
                    }
                    container.liveScores.want(shows.map { de.ayutv.data.sport.LiveScores.leagueOf(it.title + " " + it.subtitle.orEmpty()) })
                    runCatching { de.ayutv.data.sport.MatchHints.update(container.liveScores.current()) }
                    runCatching { de.ayutv.data.sport.MatchHints.learn(shows) }
                }
                kotlinx.coroutines.delay(if (de.ayutv.data.sport.MatchHints.matches.isEmpty()) 60_000L else 10 * 60_000L)
            }
        }
        // Einstellung „Flüssige Bedienung“ (weniger Bewegung) laufend übernehmen.
        container.scope.launch { container.settings.settings.collect { de.ayutv.ui.UiPerformance.setSmooth(it.smoothUi) } }
        // Veraltete Playlists/EPG im Hintergrund auffrischen, ohne den Start zu blockieren.
        // Kindersicherung: Verlässt man die App (Home-Taste, anderes Programm), ist wieder alles gesperrt.
        // So ist die Sperre auch beim nächsten Start sofort aktiv.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && container.parental.unlocked.value) {
                container.scope.launch { container.parental.lock() }
            }
            // Handy als Fernbedienung: nur auf dem Fernseher. Der Hintergrund-Dienst hält sie auch dann am Laufen,
            // wenn DnA-TV nicht zu sehen ist (Übergabe vom Handy startet den Player von selbst).
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) visible = false
            when (event) {
                Lifecycle.Event.ON_START -> container.scope.launch {
                    if (!hasTouchscreen() && container.settings.current().phoneRemote) container.remote.start()
                }
                Lifecycle.Event.ON_STOP -> if (!de.ayutv.data.remote.RemoteService.running) container.remote.stop()
                else -> Unit
            }
        })
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                container.foreground = java.lang.ref.WeakReference(activity)
                de.ayutv.ui.UiPerformance.inPlayer = activity !is MainActivity
                // Ruckeln der Menüs messen (für den Fehlerbericht); der Player zählt seine Bilder selbst.
                if (activity is MainActivity) de.ayutv.ui.UiPerformance.watch(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (container.foreground?.get() === activity) container.foreground = null
                de.ayutv.ui.UiPerformance.unwatch(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Kindersicherung: Nach jedem Start ist wieder alles gesperrt (vor allem anderen anwenden).
        container.scope.launch {
            container.parental.applyLocks()
            container.syncWhenIdle()
        }
        // Fernseher: auch im Hintergrund (und nach dem Einschalten) für das Handy erreichbar.
        container.scope.launch {
            if (!hasTouchscreen() && container.settings.current().phoneRemote) de.ayutv.data.remote.RemoteService.start(this@AyuApp)
        }
        runCatching { SyncWorker.schedule(this) }
        runCatching { de.ayutv.data.repo.PretranslateWorker.schedule(this) }
        runCatching { de.ayutv.data.repo.ChannelCheckWorker.schedule(this) }
        container.scope.launch {
            kotlinx.coroutines.delay(15_000)
            runCatching { de.ayutv.data.FireTvHome.update(this@AyuApp, container) }
        }
        // Erinnerungen nach einem Update oder Prozessneustart sicher wieder einplanen.
        container.scope.launch {
            container.reminders.rescheduleAll()
            container.recordings.rescheduleAll()
        }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(packageName)

    /** Logos und Poster: kleiner Speicher-Cache (Fire TV Stick hat wenig RAM), großer Disk-Cache. */
    /**
     * Speicher abgeben, sobald die Menüs nicht mehr zu sehen sind oder das System knapp wird: Die App bleibt für das
     * Handy im Hintergrund erreichbar, soll dem Fire TV dabei aber keinen Platz wegnehmen. Bilder kommen beim
     * nächsten Öffnen schnell aus dem Speicher auf der Platte zurück.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val hidden = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (hidden) runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        // Schwacher Stick (4 kleine Kerne): Beim Scrollen durch Senderlisten entschlüsselte Coil sonst viele Logos und
        // Poster gleichzeitig und ließ der Oberfläche keinen Kern frei – die Menüs ruckelten. Auf schwachen Geräten
        // höchstens 2 Bilder zugleich entschlüsseln und 4 laden; die Bedienung hat Vorrang, Bilder kommen kurz danach.
        .apply {
            if (de.ayutv.ui.UiPerformance.reduced) {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                decoderCoroutineContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2))
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                fetcherCoroutineContext(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4))
            }
        }
        .build()
}

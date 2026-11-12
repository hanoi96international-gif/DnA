package de.ayutv

import de.ayutv.data.parse.Hid

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.repo.XtreamClient
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rundgang über die ganze App auf einem echten (emulierten) Android-TV-Gerät.
 * Die CI stellt unter 10.0.2.2:8000 eine Playlist, ein EPG und Testvideos bereit
 * (1080p50 H.264 TS, 4K HEVC TS, HLS, MKV mit englischen Untertiteln).
 * Ergebnisse: Screenshots und results.txt im externen App-Verzeichnis.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTourTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<AyuApp>()
    // Interner Speicher: die CI holt ihn per "run-as" ab (zuverlässiger als Android/data unter Scoped Storage).
    private val out = File(app.filesDir, "ci").apply { deleteRecursively(); mkdirs() }
    private val results = StringBuilder()

    private fun log(line: String) {
        results.append(line).append('\n')
        File(out, "results.txt").writeText(results.toString())
        android.util.Log.i("AyuTour", line)
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val scaled = Bitmap.createScaledBitmap(bitmap, 960, 540, true)
        File(out, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(350)
    }

    /** Player und Activity-Zustand nur auf dem Main-Thread lesen (ExoPlayer prüft das). */
    private fun <T> ActivityScenario<PlayerActivity>.read(block: (PlayerActivity) -> T): T {
        var value: T? = null
        onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Jeder Abschnitt läuft für sich: ein Fehler wird protokolliert, der Rundgang geht weiter. */
    private var failures = 0

    private fun section(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures++
            log("FEHLER in $name: ${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.e("AyuTour", "Fehler in $name", t)
        }
    }

    /**
     * Startet den Player. Beendet das Testframework gerade noch die vorige Activity, kann der neue
     * Start in derselben Task mit abgeräumt werden; dann einmal sauber neu starten.
     */
    private fun launchPlayer(intent: android.content.Intent): ActivityScenario<PlayerActivity> {
        Thread.sleep(1_500)
        repeat(2) {
            val scenario = ActivityScenario.launch<PlayerActivity>(intent)
            if (scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED) return scenario
            log("player start state=${scenario.state}, neuer Versuch")
            scenario.close()
            Thread.sleep(2_000)
        }
        return ActivityScenario.launch(intent)
    }

    /** Übersetzer-Prozess beenden, damit er beim nächsten Aufruf frisch startet (z. B. mit anderem Rechenkern). */
    private fun killTranslator() {
        de.ayutv.data.repo.Translators.remote.release()
        Thread.sleep(500)
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(":mt") }.forEach { android.os.Process.killProcess(it.pid) }
        Thread.sleep(1_500)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return false
    }

    @Test
    fun tour() {
        val container = app.container
        val base = "http://10.0.2.2:8000"

        // 0. Nach dem Upgrade von der Vorversion: Datenbank muss migriert sein (nicht gelöscht/abgestürzt)
        section("upgrade") {
            val version = container.db.openHelper.readableDatabase.version
            val existing = runBlocking { container.db.playlists().all() }
            log("db_version=$version playlists_before=${existing.size}")
            assertTrue("Datenbank auf Version $version statt ${de.ayutv.data.db.AyuDatabase.VERSION}", version == de.ayutv.data.db.AyuDatabase.VERSION)
            // In der Vorversion angelegte eigene Daten müssen das Update überstehen (siehe ci/run-device-tour.sh).
            val seeded = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("upgradeSeeded") == "true"
            val db = container.db
            val group = runBlocking { db.customGroups().groups() }.firstOrNull { it.name == "Upgrade-Test" }
            val items = runBlocking { db.customGroups().allItems() }.filter { it.groupId == group?.id }
            val favorite = "99:c:1" in runBlocking { db.favorites().keys() }
            val hidden = runBlocking { db.hiddenGroups().all() }.any { it.playlistId == 99L && it.name == "Upgrade-Test" }
            log("upgrade_data seeded=$seeded custom_group=${group != null} items=${items.size} favorite=$favorite hidden=$hidden")
            if (seeded) {
                assertTrue("Eigene Gruppe nach dem Update verschwunden", group != null && items.size == 1)
                assertTrue("Favorit nach dem Update verschwunden", favorite)
                assertTrue("Ausgeblendete Gruppe nach dem Update wieder sichtbar", hidden)
            }
            // Aufräumen, damit der Rest des Rundgangs mit eigenen Daten startet.
            runBlocking {
                group?.let { db.customGroups().deleteItems(it.id); db.customGroups().deleteGroup(it.id) }
                db.favorites().remove("99:c:1")
                db.hiddenGroups().show(99L, de.ayutv.data.db.GroupKind.LIVE, listOf("Upgrade-Test"))
            }
        }

        // 0b. Textmuster auf Android prüfen (Android nutzt eine andere Regex-Engine als der PC)
        section("regex") {
            val plain = de.ayutv.data.parse.Srt.plainText("<i>Hallo</i> {\\an8}Welt")
            val (title, year) = de.ayutv.data.parse.OpenSubtitlesParsing.cleanTitle("DE - Der Film [4K] (Director's Cut) 2020")
            log("regex: plain=$plain title=$title year=$year")
            assertTrue("Srt.plainText: $plain", plain == "Hallo Welt")
            val key = de.ayutv.data.parse.EpgMatcher.analyse(Hid.s("OCoEUXQFXAPjSxEgBWgHGl4dgil1TqCZ4Lej6uNSBg8iRiEmSg=="), isId = false)
            val keyOk = key.strong == Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78g==") && key.country == "de"
            log("regex: epg_key_ok=$keyOk")
            assertTrue("EPG-Abgleich auf Android", keyOk)
        }
        // Die Abschnitte unten prüfen den TV-Guide des Testservers; das freie EPG aus dem Internet erst am Ende.
        // Der Testserver liefert den TV-Guide wie ein Anbieter: ihn hier ausdrücklich zulassen (Standard ist „nur eigener“).
        // Alles läuft über den DNA Player mit der eigenen DNA Engine.
        runBlocking {
            container.settings.update {
                it.copy(internetGuide = false, ownGuideOnly = false, countryChoiceDone = true)
            }
        }

        // 1. Playlist + EPG importieren
        val started = System.currentTimeMillis()
        runBlocking {
            val id = container.playlists.add(
                PlaylistEntity(name = "CI-Test", type = PlaylistType.M3U, url = "$base/playlist.m3u", epgUrl = "$base/epg.xml")
            )
            container.playlists.sync(id)
        }
        val channels = runBlocking { container.content.zapList(null, false) }
        val programmes = runBlocking { container.db.programmes().observeCount().first() }
        log("import_ms=${System.currentTimeMillis() - started} channels=${channels.size} programmes=$programmes sync=${container.playlists.state.value}")
        assertTrue("Keine Sender importiert", channels.isNotEmpty())

        // 1b. Xtream-Codes-Anbieter (simuliert): Login, Import, Serien-Episoden
        val wrongLogin = runBlocking { container.playlists.verifyXtream(base, "ayu", "falsch") }
        val login = runBlocking { container.playlists.verifyXtream(base, "ayu", "test") }
        val xtreamStarted = System.currentTimeMillis()
        runBlocking {
            val id = container.playlists.add(
                PlaylistEntity(name = "Xtream-Test", type = PlaylistType.XTREAM, url = XtreamClient.normalizeServer(base), username = "ayu", password = "test")
            )
            container.playlists.sync(id)
        }
        val allChannels = runBlocking { container.content.zapList(null, false) }
        val movies = runBlocking { container.db.vod().search("", 50) }
        val seriesList = runBlocking { container.db.series().search("", 50) }
        val episodes = seriesList.firstOrNull()?.let { runBlocking { container.content.episodes(it) } }.orEmpty()
        log(
            "xtream: wrong_login_rejected=${wrongLogin != null} login=${login ?: "ok"} import_ms=${System.currentTimeMillis() - xtreamStarted} " +
                "channels_total=${allChannels.size} movies=${movies.size} series=${seriesList.size} episodes=${episodes.size} " +
                "sync=${container.playlists.state.value}"
        )

        // 1b2. Denselben Zugang noch einmal eingeben (z. B. per Handy, anders geschrieben): keine zweite Playlist.
        section("same_account") {
            val before = runBlocking { container.db.playlists().all() }
            val again = runBlocking {
                container.playlists.add(
                    PlaylistEntity(name = "Xtream", type = PlaylistType.XTREAM, url = XtreamClient.normalizeServer(base.uppercase().replace("HTTP://", "http://") + "/"), username = "ayu", password = "test")
                )
            }
            val after = runBlocking { container.db.playlists().all() }
            val original = before.first { it.name == "Xtream-Test" }
            log("same_account: original=${original.id} again=$again before=${before.size} after=${after.size}")
            assertTrue("Gleicher Zugang als neue Playlist angelegt", again == original.id && after.size == before.size)
            assertTrue("Name der vorhandenen Playlist überschrieben", after.first { it.id == original.id }.name == "Xtream-Test")
        }

        // 1b3. Abgleich als Kopie: Eigene Gruppe geht hier verloren, der Stand des anderen Geräts bringt sie zurück.
        section("sync_groups") {
            val db = container.db
            val key = channels.first().key
            val sync = de.ayutv.data.remote.DeviceSync(container)
            runBlocking {
                val id = db.customGroups().insert(de.ayutv.data.db.CustomGroupEntity(name = "Sync-Test", position = db.customGroups().maxGroupPosition() + 1))
                db.customGroups().addItem(de.ayutv.data.db.CustomGroupItemEntity(id, key, 0))
                val other = sync.export()
                db.customGroups().deleteItems(id)
                db.customGroups().deleteGroup(id)
                val result = sync.import(other)
                val back = db.customGroups().groups().firstOrNull { it.name == "Sync-Test" }
                val items = back?.let { g -> db.customGroups().allItems().filter { it.groupId == g.id }.map { it.key } }
                log("sync_groups: result=${result.text} back=${back != null} items=$items")
                back?.let { db.customGroups().deleteItems(it.id); db.customGroups().deleteGroup(it.id) }
                assertTrue("Eigene Gruppe kam per Abgleich nicht zurück", items == listOf(key))
                assertTrue("Abgleich zählt die Gruppe nicht", result.groups == 1)
            }
        }

        // 1c. Sport: ein Bundesliga-Spiel im TV-Guide erscheint in der Sport-Übersicht, Verein folgen setzt die Erinnerung.
        section("sport") {
            val channel = channels.firstOrNull { it.epgId == "hd.test" } ?: error("Testsender fehlt")
            val now = System.currentTimeMillis()
            runBlocking {
                container.db.programmes().insertAll(
                    listOf(
                        de.ayutv.data.db.ProgrammeEntity(
                            playlistId = channel.playlistId, channelEpgId = "hd.test", start = now + 3_600_000, stop = now + 10_800_000,
                            title = "Fußball: Bundesliga", description = null, category = "Fußball",
                            subtitle = "Borussia Dortmund – Eintracht Frankfurt, 7. Spieltag", icon = null, flags = 0,
                        )
                    )
                )
            }
            val events = runBlocking { container.content.sportEvents(hours = 6) }
            val reminders = runBlocking { container.content.teamReminders(setOf("Eintracht Frankfurt")) }
            val variants = runBlocking { container.content.variantsOf(channel) }
            log("sport: events=${events.map { de.ayutv.data.parse.ProgrammeText.headline(it.programme.title, it.programme.subtitle) }} reminders=${reminders.map { it.title }} variants=${variants.map { it.name }}")
            assertTrue("Spiel fehlt in der Sport-Übersicht", events.any { it.programme.subtitle?.contains("Dortmund") == true })
            // Die Erinnerung gilt der besten Fassung unter allen Sendern mit diesem Programm (nicht unbedingt diesem).
            // (auch aus einer anderen Playlist: der Xtream-Testsender hat dieselbe EPG-Kennung).
            val remindedChannels = runBlocking { reminders.mapNotNull { container.content.channel(it.channelKey) } }
            log("sport_reminder_channels=${remindedChannels.map { "${it.name}/${it.epgId}" }}")
            assertTrue("Keine Erinnerung für den Verein", remindedChannels.any { it.epgId == "hd.test" })
            // Wappen für die Anzeigetafel aus dem Netz (OpenLigaDB, sonst TheSportsDB): vorab holen, damit das
            // Bildschirmfoto der Sport-Übersicht sie zeigt. Ohne Netz im Testgerät nur protokollieren.
            val crests = runBlocking { listOf("Borussia Dortmund", "Eintracht Frankfurt", "Arsenal").associateWith { container.crests.of(it) } }
            log("sport_crests: ${crests.count { it.value != null }} von ${crests.size} gefunden")
            log("sport_headline: ${de.ayutv.data.parse.ProgrammeText.display("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt, 7. Spieltag", null, "Fußball", now, now + 7_200_000)}")
        }

        // 2. Oberfläche per Fernbedienung durchgehen
        section("ui") { ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4_000)
            shot("01_home")
            // OK auf einem Bereich der Leiste geht gleich hinein; ◀ führt zurück in die Leiste.
            // [photo] = false: Bereich nur aufrufen (der Reiter „Apps“ zeigt Namen fremder Apps – kein Bildschirmfoto).
            fun next(name: String, photo: Boolean = true) {
                key(KeyEvent.KEYCODE_DPAD_LEFT, 4)
                key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
                if (photo) shot(name)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
            shot("02_live")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(800)
            shot("03_live_focus")
            next("04_guide")
            next("04b_sport")
            next("05_movies")
            next("05b_series")
            next("06_search")
            next("06b_apps", photo = false)
            next("07_settings")
        } }

        // Mini-Vorschau in Live TV: Die Senderliste muss sichtbar bleiben, während der ausgewählte Sender klein mitläuft
        // (früher ließ die Vorschau die ganze Liste verschwinden).
        section("live_preview") {
            runBlocking { container.settings.update { it.copy(livePreview = true) } }
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(3_000)
                key(KeyEvent.KEYCODE_DPAD_LEFT, 4)
                key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_500)
                key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(5_000)
                shot("03b_live_preview")
                fun visible(text: String): Boolean {
                    var found = false
                    fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 60 || found) return
                        if ((node.text?.toString() ?: node.contentDescription?.toString())?.contains(text) == true && node.isVisibleToUser) found = true
                        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                    }
                    walk(instrumentation.uiAutomation.rootInActiveWindow, 0)
                    return found
                }
                // Welche Gruppe gerade offen ist, ist egal: Es müssen mehrere Sender der Liste zu sehen sein.
                val names = runBlocking { container.db.channels().all() }.map { it.name }.distinct().take(80)
                val shown = names.count { visible(it) }
                log("live_preview: sichtbare Sender=$shown preview=${runBlocking { container.settings.current() }.livePreview}")
                assertTrue("Senderliste verschwindet bei der Mini-Vorschau", shown >= 2)
            }
            runBlocking { container.settings.update { it.copy(livePreview = false) } }
        }

        // Einstellungen → Playlists: Name und Knöpfe müssen sichtbar sein. Früher standen alle Knöpfe neben dem Namen;
        // auf 960 dp Breite blieb dem Namen keine Breite, die Zeile wurde riesig hoch und die Seite wirkte leer.
        section("settings_playlists") { ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(3_000)
            key(KeyEvent.KEYCODE_DPAD_LEFT, 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN, 10)
            key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(2_000)
            shot("07c_settings_playlists")
            val first = runBlocking { container.db.playlists().all() }.first().name
            val height = app.resources.displayMetrics.heightPixels
            // Den Element-Baum selbst durchlaufen: Die Textsuche der Bedienungshilfe durchsucht Compose nicht.
            // Texte, die genau so lauten; Compose baut die Daten erst auf, wenn jemand fragt (daher kurz wiederholen).
            fun bounds(text: String): List<android.graphics.Rect> {
                val found = ArrayList<android.graphics.Rect>()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || depth > 60) return
                    if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                        found += android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    }
                    for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                }
                walk(instrumentation.uiAutomation.rootInActiveWindow, 0)
                return found
            }
            var name = emptyList<android.graphics.Rect>()
            var groups = emptyList<android.graphics.Rect>()
            waitFor(8_000) {
                name = bounds(first)
                groups = bounds("Gruppen").filter { it.top in 0 until height }
                name.isNotEmpty() && groups.isNotEmpty()
            }
            log("settings_playlists: first=$first name=$name groups=${groups.size} height=$height")
            assertTrue("Playlist-Name „$first“ ohne Platz: $name", name.any { it.width() >= 80 && it.height() in 1..200 })
            assertTrue("Knopf „Gruppen“ nicht auf dem Bildschirm", groups.isNotEmpty())
        } }

        // Wie auf dem Fire TV Stick 4K (weniger Bewegung): Bereiche ohne Überblendung, Seitenleiste ohne Feder,
        // Hintergrund kleiner gezeichnet. Bedienung und Fokus müssen genauso funktionieren.
        section("ui_reduced") {
            de.ayutv.ui.UiPerformance.force(true)
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    Thread.sleep(3_000)
                    shot("01r_home_reduced")
                    key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_000)
                    key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(600)
                    // TV-Guide: ◀ auf der ersten Sendung führt in die Seitenleiste (nicht in die Vergangenheit).
                    key(KeyEvent.KEYCODE_DPAD_LEFT, 4)
                    key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_000)
                    shot("04r_guide_reduced")
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(400)
                    key(KeyEvent.KEYCODE_DPAD_LEFT); Thread.sleep(600)
                    shot("04r_guide_left")
                    log("ui_reduced: reduced=${de.ayutv.ui.UiPerformance.reduced} ${de.ayutv.ui.UiPerformance.summary()}")
                }
            } finally {
                de.ayutv.ui.UiPerformance.force(false)
            }
        }

        // 3. Live: Start, Zappen, Senderliste, Menü
        val live = channels.filter { "/movie/" !in it.url }
        live.forEachIndexed { index, channel -> section("live[$index]") {
            launchPlayer(PlayerActivity.liveIntent(app, channel.key, null)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(8_000)
                val stats = scenario.read { it.stats }
                log(
                    "live[$index] ${channel.name}: ready=$ready startup_ms=${stats.startupMs} ${stats.width}x${stats.height} " +
                        "fps=${stats.frameRate} codec=${stats.videoCodec} decoder=${stats.decoder} dropped=${stats.droppedFrames} " +
                        "hdr=${stats.hdr} audio=${stats.audioCodec}/${stats.audioChannels}ch audio_decoder=${stats.audioDecoder} " +
                        "message=${scenario.read { it.message }}"
                )
                shot("1${index}_live_${channel.name.filter(Char::isLetterOrDigit)}")
                if (index == 0) {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(500); key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(800)
                    shot("1${index}_live_info_tech")
                    // Zweimal umschalten: über den 4K-Sender (im Emulator nicht dekodierbar) zum nächsten.
                    // Prüft auch, dass sich der Player nach einem Formatfehler wieder fängt.
                    key(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(3_000)
                    log("zap1 now=${scenario.read { it.current?.name }} message=${scenario.read { it.message }}")
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    val zapReady = waitFor(15_000) {
                        scenario.read { it.player?.playbackState == Player.STATE_READY && it.stats.startupMs > 0 }
                    }
                    log("zap: ready=$zapReady startup_ms=${scenario.read { it.stats.startupMs }} now=${scenario.read { it.current?.name }}")
                    // Schnell dreimal: Anzeige läuft mit, umgeschaltet wird einmal, auf den dritten Sender.
                    val before = scenario.read { act -> act.channels.indexOfFirst { it.key == act.current?.key } }
                    repeat(3) { key(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(150) }
                    val burstReady = waitFor(15_000) {
                        scenario.read { act ->
                            act.player?.currentMediaItem?.localConfiguration?.uri?.toString()?.let { uri ->
                                act.current?.url?.let { uri.startsWith(it.substringBeforeLast('.')) }
                            } == true && act.player?.playbackState == Player.STATE_READY
                        }
                    }
                    val after = scenario.read { act -> act.channels.indexOfFirst { it.key == act.current?.key } }
                    val size = scenario.read { it.channels.size }
                    log("zap_burst: ready=$burstReady moved=${(after - before + size) % size} expected=${3 % size} now=${scenario.read { it.current?.name }}")
                    // ◀ springt zum vorherigen Sender und wieder zurück
                    section("back_zap") {
                    val burstChannel = scenario.read { it.current?.key }
                    val previous = scenario.read { it.previousChannel?.key }
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    val backReady = waitFor(15_000) { scenario.read { it.current?.key == previous && it.player?.playbackState == Player.STATE_READY } }
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                    val returned = waitFor(15_000) { scenario.read { it.current?.key == burstChannel } }
                    log("back_zap: previous=${previous != null} back=$backReady returned=$returned")
                    assertTrue("Zurück-Zappen: previous=$previous back=$backReady returned=$returned", previous != null && backReady && returned)
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(1_200)
                    shot("1${index}_live_channel_list")
                    key(KeyEvent.KEYCODE_BACK); Thread.sleep(500)
                    key(KeyEvent.KEYCODE_MENU); Thread.sleep(1_000)
                    shot("1${index}_live_menu")
                    key(KeyEvent.KEYCODE_BACK); Thread.sleep(600)
                    // Zurück ohne offenes Menü: links die Senderliste, das Bild läuft klein weiter; ein Sender (hier der
                    // gerade laufende) geht wieder ins Vollbild.
                    section("live_mini") {
                        key(KeyEvent.KEYCODE_BACK)
                        val mini = waitFor(3_000) { scenario.read { it.miniVideo && it.overlay == de.ayutv.player.Overlay.CHANNELS } }
                        val playing = scenario.read { it.player?.isPlaying == true || it.player?.playbackState == Player.STATE_BUFFERING }
                        Thread.sleep(800)
                        shot("1${index}_live_mini")
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        val full = waitFor(5_000) { scenario.read { !it.miniVideo && it.overlay == de.ayutv.player.Overlay.NONE } }
                        log("live_mini: mini=$mini playing=$playing full=$full finishing=${scenario.read { it.isFinishing }}")
                        assertTrue("Mini-Bild: mini=$mini playing=$playing full=$full", mini && full && !scenario.read { it.isFinishing })
                    }
                }
            }
        } }

        // 2a. Live anhalten und zurückspulen (Zeitversatz aus dem Zwischenspeicher)
        // „Wackelige Leitung“ liefert wie ein echter Sender in Echtzeit und trennt alle 15 s: auch die Aufnahme muss neu verbinden.
        (channels.firstOrNull { it.name == "Wackelige Leitung" } ?: live.firstOrNull())?.let { channel -> section("timeshift") {
            launchPlayer(PlayerActivity.liveIntent(app, channel.key, null)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                // Genug Vorlauf, damit 10 s zurück nicht am Anfang des Speichers anstoßen
                val recorded = waitFor(35_000) { scenario.read { it.rewindableMs } > 16_000 }
                log("timeshift ${channel.name}: ready=$ready rewindable_ms=${scenario.read { it.rewindableMs }}")
                key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val paused = waitFor(5_000) { scenario.read { it.timeshifted && it.player?.playWhenReady == false } }
                Thread.sleep(6_000)
                shot("1t_timeshift_paused")
                key(KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumed = waitFor(15_000) { scenario.read { it.player?.isPlaying == true && it.timeshifted } }
                Thread.sleep(2_000)
                val behind = scenario.read { it.behindLiveMs }
                key(KeyEvent.KEYCODE_MEDIA_REWIND)
                val rewound = waitFor(15_000) { scenario.read { it.player?.isPlaying == true && it.behindLiveMs > behind + 5_000 } }
                val behindAfterRewind = scenario.read { it.behindLiveMs }
                shot("1t_timeshift_rewound")
                repeat(4) { key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); Thread.sleep(1_500) }
                val live = waitFor(15_000) { scenario.read { !it.timeshifted && it.player?.playbackState == Player.STATE_READY } }
                log(
                    "timeshift: recorded=$recorded paused=$paused resumed=$resumed behind_ms=$behind rewound=$rewound " +
                        "behind_after_rewind_ms=$behindAfterRewind back_to_live=$live message=${scenario.read { it.message }}"
                )
                assertTrue("Zeitversatz: recorded=$recorded paused=$paused resumed=$resumed rewound=$rewound live=$live",
                    recorded && paused && resumed && behind >= 4_000 && rewound && live)
            }
        } }

        // 2b. Archiv (Catch-up): Xtream-Sender mit Archiv, vergangene Sendung abspielen
        section("catchup") {
            val archived = runBlocking { container.content.zapList(null, false) }.firstOrNull { it.catchupDays > 0 }
                ?: error("Kein Sender mit Archiv importiert")
            val now = System.currentTimeMillis()
            val programme = de.ayutv.data.db.ProgrammeEntity(
                playlistId = archived.playlistId, channelEpgId = archived.epgId ?: "x",
                start = now - 3_600_000L, stop = now - 1_800_000L, title = "Archiv-Test", description = null, category = null,
            )
            val url = runBlocking { container.content.catchupUrl(archived, programme) } ?: error("Keine Archiv-Adresse")
            val intent = PlayerActivity.vodIntent(
                app,
                VodRequest("catchup:test", HistoryKind.CATCHUP, programme.title, archived.name, null, url),
            )
            launchPlayer(intent).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                log("catchup: channel=${archived.name} days=${archived.catchupDays} url=${url.substringAfter("8000")} ready=$ready")
                assertTrue("Archiv spielt nicht ab", ready && "/timeshift/" in url)
            }
        }

        // 2c. Erinnerung setzen, auslösen (Benachrichtigung) und wieder entfernen
        section("reminder") {
            val channel = runBlocking { container.content.zapList(null, false) }.first()
            val reminder = de.ayutv.data.reminders.Reminder(channel.key, channel.name, Hid.s("EA8mSCcldDKifA=="), System.currentTimeMillis() + 3_600_000L)
            val set = runBlocking { container.reminders.toggle(reminder) }
            val stored = runBlocking { container.reminders.isSet(reminder.channelKey, reminder.start) }
            instrumentation.runOnMainSync {
                de.ayutv.data.reminders.ReminderReceiver().onReceive(
                    app,
                    android.content.Intent(app, de.ayutv.data.reminders.ReminderReceiver::class.java)
                        .setAction(de.ayutv.data.reminders.Reminders.ACTION_REMIND)
                        .putExtra(de.ayutv.data.reminders.Reminders.EXTRA_CHANNEL, reminder.channelKey)
                        .putExtra(de.ayutv.data.reminders.Reminders.EXTRA_CHANNEL_NAME, reminder.channelName)
                        .putExtra(de.ayutv.data.reminders.Reminders.EXTRA_TITLE, reminder.title),
                )
            }
            val removed = !runBlocking { container.reminders.toggle(reminder) }
            log("reminder: set=$set stored=$stored removed=$removed")
            assertTrue("Erinnerung funktioniert nicht", set && stored && removed)
        }

        // 2d. Kindersicherung: Gruppe sperren → verschwindet; mit PIN entsperren → wieder da
        section("parental") {
            val parental = container.parental
            val ciPlaylist = runBlocking { container.db.playlists().all() }.first { it.name == "CI-Test" }
            val group = de.ayutv.data.parental.ParentalControl.LockedGroup(ciPlaylist.id, de.ayutv.data.db.GroupKind.LIVE, "Test Ton")
            val before = runBlocking { container.content.zapList(null, false) }.size
            val wrong: Boolean
            val locked: Int
            val unlocked: Int
            runBlocking {
                parental.setPin("1234")
                parental.setLocked(group, true)
                parental.lock()
                locked = container.content.zapList(null, false).size
                wrong = parental.unlock("0000")
                parental.unlock("1234")
                unlocked = container.content.zapList(null, false).size
                parental.setLocked(group, false)
                parental.removePin("1234")
            }
            log("parental: before=$before locked=$locked wrong_pin_accepted=$wrong unlocked=$unlocked")
            assertTrue("Kindersicherung wirkt nicht", locked < before && !wrong && unlocked == before)
        }

        // 3a. Bildwiederholrate anpassen + Tunneling eingeschaltet: darf nie hängen oder sich neu starten
        channels.firstOrNull { it.name == "HD Test 50fps" }?.let { hd -> section("afr_tunneling") {
            runBlocking { container.settings.update { it.copy(matchFrameRate = true, tunneling = true) } }
            try {
                launchPlayer(PlayerActivity.liveIntent(app, hd.key, null)).use { scenario ->
                    waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                    var playing = 0
                    val messages = mutableSetOf<String>()
                    repeat(30) {
                        Thread.sleep(1_000)
                        if (scenario.read { it.player?.isPlaying } == true) playing++
                        scenario.read { it.message }?.let(messages::add)
                    }
                    val stats = scenario.read { it.stats }
                    log("afr_tunneling: playing_s=$playing fps=${stats.frameRate} measured=${stats.frameRateMeasured} messages=$messages")
                    assertTrue("Mit Bildwiederholrate/Tunneling nur $playing von 30 s gelaufen", playing >= 27)
                }
            } finally {
                runBlocking { container.settings.update { it.copy(matchFrameRate = false, tunneling = false) } }
            }
        } }

        // 3b. Wackelige Leitung: Server liefert in Echtzeit und trennt alle 15 s. Das Bild muss weiterlaufen.
        channels.firstOrNull { it.name == "Wackelige Leitung" }?.let { flaky -> section("flaky") {
            launchPlayer(PlayerActivity.liveIntent(app, flaky.key, null)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                // Gezählt wird, in wie vielen der 50 Sekunden das Bild tatsächlich lief.
                var playing = 0
                var buffering = 0
                repeat(50) {
                    Thread.sleep(1_000)
                    when {
                        scenario.read { it.player?.isPlaying } == true -> playing++
                        scenario.read { it.player?.playbackState } == Player.STATE_BUFFERING -> buffering++
                    }
                }
                log("flaky: playing_s=$playing buffering_s=$buffering cushion=${scenario.read { it.cushionActive }} drops=${scenario.read { it.dropInfo }} message=${scenario.read { it.message }}")
                assertTrue("Live-Stream hängt bei Verbindungsabbrüchen ($playing von 50 s gelaufen)", playing >= 45)
            }
        } }

        // Anbieter hängt (schickt 12 s nichts, Verbindung bleibt offen): selbst merken und neu verbinden,
        // ohne dass der Player mit einem Fehler neu startet („Verbindung wird wiederhergestellt“).
        channels.firstOrNull { it.name == "Hängende Leitung" }?.let { stall -> section("stall") {
            launchPlayer(PlayerActivity.liveIntent(app, stall.key, null)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                var playing = 0
                repeat(60) {
                    Thread.sleep(1_000)
                    if (scenario.read { it.player?.isPlaying } == true) playing++
                }
                val errors = scenario.read { it.playerErrors }
                log("stall: playing_s=$playing errors=$errors drops=${scenario.read { it.dropInfo }} message=${scenario.read { it.message }}")
                assertTrue("Player startete $errors× mit Fehler neu statt selbst neu zu verbinden", errors == 0)
                assertTrue("Bild lief nur $playing von 60 s", playing >= 30)
            }
        } }

        // 4. Film: Untertitel standardmäßig aus, eingebettete englische Spur einschalten
        val movie = runBlocking { container.db.vod().search("", 5) }.firstOrNull()
        // Die App bringt keinerlei Zugangsdaten mit: ohne eigene Eingabe oder übertragene Sicherung ist kein
        // OpenSubtitles- oder DeepL-Schlüssel gesetzt. Danach aus, damit die Übersetzungstests nicht von Online-Treffern abhängen.
        section("opensubtitles") {
            val settings = runBlocking { container.settings.current() }
            if (settings.openSubtitlesApiKey.isBlank()) {
                log("opensubtitles: kein Schlüssel (die App enthält bewusst keinen)")
                assertTrue("Die App darf keinen DeepL-Schlüssel mitbringen", settings.deeplKey.isBlank())
            } else {
                val check = runBlocking { de.ayutv.data.setup.KeyCheck.run(container.http, settings) }
                val german = runBlocking { container.subtitles.countResults("Inception (2010)", "de", settings) }
                val english = runBlocking { container.subtitles.countResults("The Matrix (1999)", "en", settings) }
                log("opensubtitles: check=${check.map { "${it.ok}:${it.text}" }} inception_de=$german matrix_en=$english")
                assertTrue("OpenSubtitles-Schlüssel ungültig: $check", check.all { it.ok })
                assertTrue("OpenSubtitles findet nichts", german > 0 && english > 0)
            }
        }
        runBlocking { container.settings.update { it.copy(onlineSubtitles = false) } }

        if (movie != null) section("vod") {
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                log("vod launched state=${scenario.state}")
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(3_000)
                val textGroups = scenario.read { a -> a.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
                log("vod ${movie.name}: ready=$ready text_tracks=${textGroups.size} subtitles_off_by_default=${scenario.read { it.textDisabled }}")
                key(KeyEvent.KEYCODE_MENU); Thread.sleep(1_200)
                shot("20_vod_menu")
                // Zweistufiges Menü: OK auf „Untertitel“ öffnet die Unterseite, Zurück führt zur Übersicht, nochmal schließt.
                key(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(900)
                val menuPage = scenario.read { it.menuPage }
                shot("20b_vod_menu_subtitles")
                key(KeyEvent.KEYCODE_BACK); Thread.sleep(600)
                val overview = scenario.read { it.menuPage == null && it.overlay == de.ayutv.player.Overlay.TRACKS }
                shot("20c_vod_menu_back")
                key(KeyEvent.KEYCODE_BACK); Thread.sleep(500)
                val closed = scenario.read { it.overlay != de.ayutv.player.Overlay.TRACKS }
                log("menu_pages: page=$menuPage overview=$overview closed=$closed")
                assertTrue("Player-Menü: page=$menuPage overview=$overview closed=$closed",
                    menuPage == de.ayutv.player.MenuPage.SUBTITLES && overview && closed)
                textGroups.firstOrNull()?.let { group -> scenario.onActivity { it.selectTrack(group, 0) } }
                Thread.sleep(4_000)
                log("vod subtitle_enabled=${!scenario.read { it.textDisabled }} dropped=${scenario.read { it.stats.droppedFrames }}")
                // Ton-Abgriff für die Ausrichtung geladener Untertitel am gesprochenen Ton: bekommt er den Ton?
                val heard = scenario.read { it.speechHeardSeconds }
                log("vod speech_tap heard_s=$heard")
                assertTrue("Ton-Abgriff bekommt keinen Ton (heard_s=$heard)", heard >= 2)
                shot("21_vod_subtitles")
                // Wie auf dem Fire TV: Indonesisch wählen, obwohl der Film nur englische Untertitel hat.
                // Erwartet: Live-Übersetzung der englischen Spur auf dem Gerät (Modell wird einmalig geladen).
                val englishLines = setOf("Hello, how are you?", "I told you we should never have come here.", "Where is my brother?",
                    "We have to leave before it gets dark.", "Thank you for everything.", "- Where is he?\n- I don't know.")
                fun translatedNow() = scenario.read { it.translatedCue }?.takeIf { it.isNotBlank() && it !in englishLines }
                scenario.onActivity { it.loadSubtitles("id") }
                var prerollMax = 0f
                val translated = waitFor(240_000) {
                    scenario.read { it.prerollProgress }?.let { prerollMax = maxOf(prerollMax, it) }
                    translatedNow() != null
                }
                log("translate_id preroll_max=$prerollMax playing=${scenario.read { it.player?.playWhenReady }}")
                log(
                    "translate_id: ok=$translated target=${scenario.read { it.liveTranslateTarget }} " +
                        "cue=${scenario.read { it.translatedCue }?.replace('\n', ' ')} statuses=${scenario.read { it.statusHistory.toList() }}"
                )
                shot("23_vod_translated_id")
                assertTrue("Übersetzung nach Indonesisch fehlgeschlagen", translated)
                scenario.onActivity { it.loadSubtitles("de") }
                Thread.sleep(2_000)
                val german = waitFor(240_000) { scenario.read { it.liveTranslateTarget } == "de" && translatedNow() != null }
                log("translate_de: target=${scenario.read { it.liveTranslateTarget }} cue=${scenario.read { it.translatedCue }?.replace('\n', ' ')}")
                shot("24_vod_translated_de")
                assertTrue("Übersetzung nach Deutsch fehlgeschlagen", german)
                key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(1_500)
                log("vod after_seek position_ms=${scenario.read { it.player?.currentPosition }} message=${scenario.read { it.message }}")
                shot("22_vod_seek")
            }
        }
        if (movie == null) log("vod: kein Film gefunden")

        // DeepL (hier ein Test-Server): Die gelesenen Zeilen gehen gebündelt in wenigen Anfragen raus, der Vorlauf ist kurz.
        if (movie != null) section("deepl_batch") {
            val server = FakeDeepL()
            de.ayutv.data.repo.Translators.deeplBase = server.url
            runBlocking { container.settings.update { it.copy(translationProvider = de.ayutv.data.TranslationProvider.DEEPL, deeplKey = "test:fx") } }
            try {
                val request = VodRequest(movie.key + "#deepl", HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
                launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                    waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                    val started = System.currentTimeMillis()
                    scenario.onActivity { it.loadSubtitles("tr") }
                    // Mit DeepL soll möglichst nie erst das Original und dann die Übersetzung erscheinen.
                    val originals = HashSet<String>()
                    var preroll = false
                    val shown = waitFor(60_000) {
                        if (scenario.read { it.prerollProgress } != null) preroll = true
                        val cue = scenario.read { it.translatedCue }
                        if (cue != null && !cue.startsWith("TR:")) originals += cue
                        cue?.startsWith("TR:") == true
                    }
                    val ms = System.currentTimeMillis() - started
                    val lines = server.texts.toList()
                    log("deepl_batch: shown=$shown ms=$ms requests=${server.requests} lines=${lines.size} preroll=$preroll originals=${originals.size} cue=${scenario.read { it.translatedCue }?.replace('\n', '/')}")
                    assertTrue("DeepL-Übersetzung nicht angezeigt", shown)
                    assertTrue("Zeilen nicht gebündelt (${server.requests} Anfragen für ${lines.size} Texte)", lines.size > server.requests)
                }
            } finally {
                de.ayutv.data.repo.Translators.deeplBase = null
                runBlocking { container.settings.update { it.copy(translationProvider = de.ayutv.data.TranslationProvider.ON_DEVICE, deeplKey = "") } }
                server.close()
            }
        }

        // 4b2. OpenSubtitles zuerst, schnell geprüft: Eine passende Datei (andere Fassung, 1,7 s versetzt) wird nach wenigen
        // vorausgelesenen Einblendungen ausgerichtet und bleibt. Eine Datei, die nicht passt, wird gegen die Übersetzung der
        // Spur (DeepL) getauscht. Zeiten der eingebetteten Spur wie in ci/make-test-media.sh.
        if (movie != null) section("subtitle_source") {
            fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
            var t = 1_000L
            val fitting = (0 until 220).map { i ->
                val gap = 2_000L + (i.toLong() * i * 7_919 + i.toLong() * 104_729) % 4_001
                de.ayutv.data.parse.SrtCue(ts(t + 1_700), ts(t + 1_700 + minOf(1_800L, gap - 200)), "Hallo Nummer $i").also { t += gap }
            }
            val noise = java.util.Random(5)
            var w = 4_000L
            val wrong = (0 until 220).map { i ->
                w += 1_500L + noise.nextInt(5_500)
                de.ayutv.data.parse.SrtCue(ts(w), ts(w + 1_200), "Falsch $i")
            }
            container.subtitles.saveTranslation(movie.key + "#quelle-ok", "de", fitting)
            container.subtitles.saveTranslation(movie.key + "#quelle-falsch", "de", wrong)
            val server = FakeDeepL()
            de.ayutv.data.repo.Translators.deeplBase = server.url
            runBlocking { container.settings.update { it.copy(translationProvider = de.ayutv.data.TranslationProvider.DEEPL, deeplKey = "test:fx") } }
            try {
                fun open(key: String, block: (ActivityScenario<PlayerActivity>) -> Unit) {
                    val request = VodRequest(key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
                    launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                        waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                        block(scenario)
                    }
                }
                open(movie.key + "#quelle-ok") { scenario ->
                    val started = System.currentTimeMillis()
                    scenario.onActivity { it.loadSubtitles("de") }
                    val synced = waitFor(30_000) { scenario.read { it.subtitleAutoSynced && it.subtitleTiming.offsetMs in -2_000L..-1_400L } }
                    val ms = System.currentTimeMillis() - started
                    Thread.sleep(20_000)
                    val (language, percent, live) = scenario.read { Triple(it.externalLanguage, it.matchPercent, it.liveTranslateTarget) }
                    scenario.onActivity { it.openTracks(de.ayutv.player.MenuPage.SUBTITLES) }
                    Thread.sleep(800)
                    shot("25_subtitle_source")
                    key(KeyEvent.KEYCODE_BACK)
                    log("subtitle_source fit: synced=$synced ms=$ms offset=${scenario.read { it.subtitleTiming.offsetMs }} percent=$percent external=$language live=$live")
                    assertTrue("Passende Datei nicht schnell ausgerichtet ($ms ms)", synced)
                    assertEquals("Passende Datei wurde getauscht", "de", language)
                }
                open(movie.key + "#quelle-falsch") { scenario ->
                    val started = System.currentTimeMillis()
                    scenario.onActivity { it.loadSubtitles("de") }
                    // Seit 0.61.3 urteilt die App erst mit genug eingebetteten Einblendungen (ohne sicheres Urteil doppelt
                    // so viele) – im langsamen Emulator dauert das länger als 90 s.
                    val switched = waitFor(180_000) {
                        scenario.read { it.liveTranslateTarget == "de" && it.externalLanguage == null && it.translatedCue?.startsWith("TR:") == true }
                    }
                    log(
                        "subtitle_source miss: switched=$switched ms=${System.currentTimeMillis() - started} auto=${scenario.read { it.autoSwitched }} " +
                            "status=${scenario.read { it.statusHistory.toList() }.takeLast(2)}"
                    )
                    assertTrue("Unpassende Datei nicht gegen die Übersetzung getauscht", switched)
                }
                // Beim nächsten Mal gleich die Übersetzung (für diesen Film entschieden).
                open(movie.key + "#quelle-falsch") { scenario ->
                    scenario.onActivity { it.loadSubtitles("de") }
                    val direct = waitFor(30_000) { scenario.read { it.liveTranslateTarget } == "de" }
                    log("subtitle_source remembered: direct=$direct external=${scenario.read { it.externalLanguage }}")
                    assertTrue("Entscheidung nicht gemerkt", direct && scenario.read { it.externalLanguage } == null)
                }
            } finally {
                de.ayutv.data.repo.Translators.deeplBase = null
                runBlocking { container.settings.update { it.copy(translationProvider = de.ayutv.data.TranslationProvider.ON_DEVICE, deeplKey = "") } }
                server.close()
            }
        }

        // 4c. Nachts vorbereiten: eingebettete Untertitel aus der ganzen Datei lesen und übersetzen. Beim Schauen
        // sind die Zeilen dann sofort da (gleiche Spur und Zeiten wie im Player), ohne Vorlauf.
        if (movie != null) section("embedded_prep") {
            val key = movie.key + "#nacht"
            val prep = de.ayutv.data.repo.EmbeddedPrep(app, container)
            val started = System.currentTimeMillis()
            val outcome = runBlocking { prep.prepare(key, movie.url, "de", { 3_600_000L }, { true }) }
            val lines = prep.translations(key, "de")
            log("embedded_prep: outcome=$outcome lines=${lines.size} ms=${System.currentTimeMillis() - started} sample=${lines.values.take(3).map { it.replace('\n', '/') }}")
            assertEquals(de.ayutv.data.repo.EmbeddedPrep.Outcome.DONE, outcome)
            assertTrue("Keine vorbereiteten Zeilen", lines.size >= 5)
            val request = VodRequest(key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.loadSubtitles("de") }
                var paused = false
                val shown = waitFor(60_000) {
                    if (scenario.read { it.prerollProgress } != null) paused = true
                    scenario.read { it.translatedCue }?.let { cue -> cue in lines.values } == true
                }
                log("embedded_prep player: shown=$shown preroll=$paused cue=${scenario.read { it.translatedCue }?.replace('\n', '/')}")
                assertTrue("Vorbereitete Übersetzung wird beim Schauen nicht genutzt", shown)
                assertFalse("Vorlauf trotz fertiger Übersetzung", paused)
            }
        }

        // 4d. Geladene Untertitel einer anderen Fassung (2,5 s zu spät): automatisch an der eingebetteten
        // Spur ausrichten. Zeiten wie in ci/make-test-media.sh.
        if (movie != null) section("subtitle_sync") {
            fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
            var t = 1_000L
            val cues = (0 until 220).map { i ->
                val gap = 2_000L + (i.toLong() * i * 7_919 + i.toLong() * 104_729) % 4_001
                val start = t + 2_500
                t += gap
                de.ayutv.data.parse.SrtCue(ts(start), ts(start + minOf(1_800L, gap - 200)), "Bonjour numéro $i")
            }
            container.subtitles.saveTranslation(movie.key, "fr", cues)
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.showLoaded("fr") }
                val synced = waitFor(180_000) {
                    scenario.read { it.subtitleAutoSynced && it.subtitleTiming.offsetMs in -2_800L..-2_200L }
                }
                Thread.sleep(1_000)
                shot("24_subtitle_sync")
                log(
                    "subtitle_sync: ok=$synced timing=${scenario.read { it.subtitleTiming }} " +
                        "cue=${scenario.read { it.translatedCue }} statuses=${scenario.read { it.statusHistory.toList() }.takeLast(3)}"
                )
                assertTrue("Untertitel nicht automatisch ausgerichtet", synced)

                // Von Hand: Leiste öffnen, mit ▶ eine spätere Zeile wählen, OK „jetzt gesprochen“. Die Zeile muss sofort zu sehen sein.
                scenario.onActivity { it.openTracks(); it.openSyncPanel() }
                Thread.sleep(1_500)
                shot("24b_subtitle_manual")
                var steps = 0
                while ((scenario.read { it.syncCandidateStart } ?: 0L) <= 30_000 && steps++ < 40) {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(150)
                }
                val text = scenario.read { it.syncCue(it.syncCandidate) }
                checkNotNull(text) { "Keine Zeilen zum Ausrichten" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val anchored = waitFor(2_000) { scenario.read { it.translatedCue?.replace('\n', ' ') } == text }
                shot("24c_subtitle_manual_done")
                log("subtitle_manual: steps=$steps line=$text anchored=$anchored shift=${scenario.read { it.subtitleShiftLabel }} confirmed=${scenario.read { it.syncConfirmed }}")
                key(KeyEvent.KEYCODE_BACK)
                assertTrue("Ausrichten an einer Zeile wirkt nicht (${scenario.read { it.translatedCue }} statt $text)", anchored)
            }
        }

        // 4d2. Datei einer anderen Fassung, deren Versatz mitten im Film springt (2,5 s, nach Zeile 110 dann 6 s, wie nach
        // einer fehlenden Szene): Zeile für Zeile an der eingebetteten Spur ausrichten, auch hinter dem Sprung.
        if (movie != null) section("subtitle_elastic") {
            fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
            var t = 1_000L
            val embedded = ArrayList<Pair<Long, Long>>()
            val cues = (0 until 220).map { i ->
                val gap = 2_000L + (i.toLong() * i * 7_919 + i.toLong() * 104_729) % 4_001
                val end = t + minOf(1_800L, gap - 200)
                embedded += t to end
                val shift = if (i < 110) 2_500L else 6_000L
                de.ayutv.data.parse.SrtCue(ts(t + shift), ts(end + shift), "Hola número $i").also { t += gap }
            }
            val key = movie.key + "#elastisch"
            container.subtitles.saveTranslation(key, "es", cues)
            val request = VodRequest(key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                // Hinter den Sprung springen (Zeile 150), dort muss die Datei trotzdem passen.
                val target = embedded[150].first - 3_000
                scenario.onActivity { it.player?.seekTo(target); it.showLoaded("es") }
                val aligned = waitFor(90_000) { (scenario.read { it.matchPercent } ?: 0) >= 70 }
                // Gerade zu sehende eingebettete Zeile = Nummer der Zeile, die aus der Datei angezeigt werden muss.
                var checked = 0
                var right = 0
                repeat(20) {
                    val (position, cue) = scenario.read { (it.player?.currentPosition ?: 0L) to it.translatedCue }
                    val index = embedded.indexOfFirst { (start, end) -> position in (start + 200)..(end - 200) }
                    if (index >= 0 && cue != null) {
                        checked++
                        if (cue == "Hola número $index") right++
                    }
                    Thread.sleep(700)
                }
                shot("24d_subtitle_elastic")
                log("subtitle_elastic: aligned=$aligned percent=${scenario.read { it.matchPercent }} checked=$checked right=$right badge=${scenario.read { it.syncBadge }}")
                assertTrue("Zeilen-Abgleich nicht erreicht (${scenario.read { it.matchPercent }} %)", aligned)
                assertTrue("Hinter dem Sprung falsche Zeilen ($right von $checked)", checked >= 3 && right * 10 >= checked * 9)
            }
        }

        // 4e. Untertitel eines ganz anderen Films (zufällige Zeiten): als falsch erkennen und verwerfen.
        if (movie != null) section("wrong_subtitles") {
            val random = java.util.Random(11)
            var t = 2_000L
            val cues = (0 until 260).map { i ->
                val start = t
                t += 1_500L + random.nextInt(5_500)
                de.ayutv.data.parse.SrtCue(
                    "%02d:%02d:%02d,%03d".format(start / 3_600_000, start / 60_000 % 60, start / 1_000 % 60, start % 1_000),
                    "%02d:%02d:%02d,%03d".format((start + 1_200) / 3_600_000, (start + 1_200) / 60_000 % 60, (start + 1_200) / 1_000 % 60, (start + 1_200) % 1_000),
                    "Falscher Film $i",
                )
            }
            container.subtitles.saveTranslation(movie.key, "it", cues)
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.player?.seekTo(0) }
                scenario.onActivity { it.showLoaded("it") }
                val rejected = waitFor(420_000) { scenario.read { a -> a.statusHistory.any { "anderen Film" in it } } }
                log("wrong_subtitles: rejected=$rejected synced=${scenario.read { it.subtitleAutoSynced }} statuses=${scenario.read { it.statusHistory.toList() }.takeLast(3)}")
                assertTrue("Falsche Untertitel nicht erkannt", rejected)
                // Nur die falsche Datei darf nicht ausgerichtet worden sein. Was danach an ihre Stelle tritt (etwa eine
                // übersetzte Vorlage mit anderem Vorspann), wird zu Recht ausgerichtet – das ist kein Fehler.
                val history = scenario.read { it.statusHistory.toList() }
                val rejectedAt = history.indexOfFirst { "anderen Film" in it }
                val alignedBefore = history.take(rejectedAt.coerceAtLeast(0)).any { "angepasst" in it || "ausgerichtet" in it }
                assertTrue("Falsche Untertitel wurden 'ausgerichtet': $history", !alignedBefore)
            }
        }
        // 5. Serienfolge über Xtream abspielen
        episodes.firstOrNull()?.let { (episode, url) -> section("episode") {
            val series = seriesList.first()
            val request = VodRequest("${series.key}:e:${episode.id}", HistoryKind.EPISODE, "${series.name} S1E1", episode.title, null, url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                Thread.sleep(2_000)
                log("episode ${episode.title}: ready=$ready message=${scenario.read { it.message }}")
                shot("30_episode")
                // Nächste Folge: in der letzten Minute Hinweis mit Countdown, eine andere Taste hält ihn an.
                Thread.sleep(5_000) // Steuerleiste blendet sich aus
                val next = waitFor(15_000) { scenario.read { it.nextEpisode } != null }
                scenario.onActivity { act -> act.player?.let { p -> p.seekTo((p.duration - 45_000).coerceAtLeast(p.duration / 2)) } }
                val countdown = waitFor(15_000) { scenario.read { it.nextCardVisible && it.nextCountdown in 1..60 } }
                val seconds = scenario.read { it.nextCountdown }
                shot("32_episode_next")
                key(KeyEvent.KEYCODE_DPAD_UP)
                val stopped = waitFor(3_000) { scenario.read { it.nextCountdown < 0 && it.nextCardVisible } }
                log(
                    "episode_extras: next=${scenario.read { it.nextEpisode?.episode?.title }} " +
                        "countdown=$countdown seconds=$seconds countdown_stopped=$stopped"
                )
                assertTrue("Serienextras: next=$next countdown=$countdown ($seconds s) stopped=$stopped", next && countdown && stopped)
            }
            // Ton an der Soundbar (hier erzwungen): Die App dekodiert ihn nebenbei selbst und wertet ihn vorausschauend aus.
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.forceAudioAhead(); it.player?.seekTo(1_000) }
                val heard = waitFor(30_000) { scenario.read { it.aheadSeconds } >= 10 }
                val position = scenario.read { it.player?.currentPosition } ?: 0L
                log("audio_ahead: heard=$heard seconds=${scenario.read { it.aheadSeconds }} position=${position / 1000}s")
                assertTrue("Ton nebenbei nicht ausgewertet", heard)
            }
        } }
        // 5. Tonformate, die Fire TV ohne AV-Receiver nicht selbst dekodiert: FFmpeg muss sie abspielen
        val audioTests = runBlocking { container.db.vod().search("Tontest", 20) }
        log("audio_tests=${audioTests.size}")
        audioTests.forEach { movie -> section("audio ${movie.name}") {
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, null, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                val ready = waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                val start = scenario.read { it.player?.currentPosition } ?: 0L
                Thread.sleep(5_000)
                val position = scenario.read { it.player?.currentPosition } ?: 0L
                val stats = scenario.read { it.stats }
                val audioTracks = scenario.read { a -> a.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.getTrackFormat(0).sampleMimeType } }
                log(
                    "audio ${movie.name}: ready=$ready advanced_ms=${position - start} audio=${stats.audioCodec}/${stats.audioChannels}ch " +
                        "decoder=${stats.audioDecoder} tracks=$audioTracks message=${scenario.read { it.message }} status=${scenario.read { it.subtitleStatus }}"
                )
                assertTrue("${movie.name} spielt nicht (ready=$ready, ${position - start} ms)", ready && position - start >= 3_000 && stats.audioDecoder.isNotEmpty())
            }
        } }

        // 5b. Übersetzung auf dem Gerät (OPUS-MT): echtes Modell laden, Qualität und Tempo messen
        section("mt_on_device") {
            val translator = de.ayutv.data.repo.Translators.onDevice
            val progress = mutableListOf<String>()
            translator.onProgress = { progress += it }
            val sentences = listOf("Hello, how are you?", "I told you we should never have come here.", "- Where is he?\n- I don't know.")
            val loadStart = System.currentTimeMillis()
            val german = runBlocking { translator.translate(sentences.take(1), "de", "en") }
            val firstMs = System.currentTimeMillis() - loadStart
            val runStart = System.currentTimeMillis()
            val more = runBlocking { translator.translate(sentences, "de", "en") }
            val perSentence = (System.currentTimeMillis() - runStart) / sentences.size
            log("mt de: first_ms=$firstMs per_sentence_ms=$perSentence result=${more.joinToString(" | ") { it.replace('\n', '/') }} progress_events=${progress.size}")
            assertTrue("Keine deutsche Übersetzung: $german", german.single().contains("Hallo") || german.single().contains("geht"))
            // Sprache ohne eigenes Modell: über das Mehrsprachen-Modell mit Sprachkennung
            val polish = runBlocking { translator.translate(listOf("Thank you for everything."), "pl", "en") }
            log("mt pl (en-mul): ${polish.single()}")
            assertTrue("Keine polnische Übersetzung", polish.single().isNotBlank() && polish.single() != "Thank you for everything.")
            log("mt installed=${translator.installedPairs()} bytes=${translator.installedBytes()}")
        }

        // 5b'. Namen, die zugleich Wörter sind, bleiben Namen; kurze Zeilen bekommen den Satz davor als Zusammenhang
        section("mt_names") {
            val base = de.ayutv.data.repo.Translators.onDevice
            val lines = listOf("I told Will about the car.", "Where is Hope tonight?", "Will, come here!", "Have you seen Hope?")
            val names = de.ayutv.data.translate.TranslationNames.learn(lines)
            val plain = runBlocking { base.translate(lines, "de", "en") }
            val smart = runBlocking { de.ayutv.data.translate.SubtitleTranslator(base, names).translate(lines + "Yes, him.", "de", "en") }
            log("mt_names: names=$names plain=${plain.joinToString(" | ")} smart=${smart.joinToString(" | ") { it.replace('\n', '/') }}")
            assertTrue("Namen nicht erkannt: $names", names == setOf("Will", "Hope"))
            assertTrue("Namen übersetzt: $smart", smart[0].contains("Will") && smart[1].contains("Hope") && smart[2].contains("Will") && smart[3].contains("Hope"))
            assertTrue("Platzhalter sichtbar: $smart", smart.none { line -> listOf("Zorv", "Kelm").any { it in line } })
        }

        // 5c. Film ohne eingebettete Untertitel, aber mit (geladener) englischer Datei: vorausschauend übersetzen
        section("file_translation") {
            val movie = runBlocking { container.db.vod().search("Stummfilm", 5) }.firstOrNull() ?: error("Film ohne Untertitel fehlt")
            val english = listOf("Hello, how are you?", "Where is my brother?", "Thank you for everything.", "We have to leave before it gets dark.")
            val cues = (0 until 30).map { i ->
                val start = i * 4
                fun t(sec: Int) = "00:%02d:%02d,000".format(sec / 60, sec % 60)
                de.ayutv.data.parse.SrtCue(t(start), t(start + 3), english[i % english.size])
            }
            container.subtitles.saveTranslation(movie.key, "en", cues)
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, null, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.loadSubtitles("de") }
                val ok = waitFor(120_000) {
                    val cue = scenario.read { it.translatedCue }
                    cue != null && english.none { e -> cue == e } && cue.isNotBlank()
                }
                Thread.sleep(1_500)
                shot("25_file_translation_de")
                log("file_translation: ok=$ok cue=${scenario.read { it.translatedCue }?.replace('\n', '/')} target=${scenario.read { it.liveTranslateTarget }} statuses=${scenario.read { it.statusHistory.toList() }.takeLast(4)}")
                assertTrue("Datei-Übersetzung fehlgeschlagen", ok)
            }
        }

        // 5c2. Selbsttest aus den Einstellungen: Texterkennung und Übersetzung im Übersetzer-Prozess
        section("translator_selftest") {
            val result = runBlocking { de.ayutv.data.translate.TranslatorSelfTest.run("de") }
            de.ayutv.data.repo.Translators.remote.release()
            log("translator_selftest: ${result.replace("\n", " | ")}")
            assertTrue("Selbsttest fehlgeschlagen: $result", result.lines().filterNot { it.startsWith("Speicher") || it.startsWith("Prozessor") || it.startsWith("Rechenkern") }.all { "ok" in it })
        }

        // 5c3. Derselbe Selbsttest mit dem DnA-Rechenkern (reines Kotlin, wie auf 32-Bit-Geräten)
        section("translator_selftest_kern") {
            val override = File(app.filesDir, "mt_engine").apply { writeText("kern") }
            try {
                killTranslator()
                val result = runBlocking { de.ayutv.data.translate.TranslatorSelfTest.run("es") }
                de.ayutv.data.repo.Translators.remote.release()
                log("translator_selftest_kern: ${result.replace("\n", " | ")}")
                assertTrue("Rechenkern nicht verwendet: $result", "Rechenkern: DnA" in result)
                assertTrue("Selbsttest (Rechenkern) fehlgeschlagen: $result", result.lines().filterNot { it.startsWith("Speicher") || it.startsWith("Prozessor") || it.startsWith("Rechenkern") }.all { "ok" in it })
            } finally {
                override.delete()
                killTranslator()
            }
        }

        // 5d. Film nur mit Bild-Untertiteln (PGS wie auf Blu-ray): mitlesen, Texterkennung, Übersetzung mit Vorlauf
        section("bitmap_translation") {
            val movie = runBlocking { container.db.vod().search("Bilduntertitel", 5) }.firstOrNull() ?: error("PGS-Testfilm fehlt")
            val english = setOf("Where is my brother?", "Thank you for everything.", "We have to leave before it gets dark.",
                "Hello, how are you?", "I told you we should never have come here.")
            val request = VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, null, movie.url)
            launchPlayer(PlayerActivity.vodIntent(app, request)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                val textGroups = scenario.read { a -> a.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
                log("bitmap_translation tracks=${textGroups.map { g -> g.getTrackFormat(0).let { "${it.language}/${it.codecs}" } }}")
                scenario.onActivity { it.loadSubtitles("de") }
                val seen = mutableSetOf<String>()
                val ok = waitFor(240_000) {
                    val cue = scenario.read { it.translatedCue }
                    if (cue != null) seen += cue
                    cue != null && cue.isNotBlank() && cue !in english && cue.any(Char::isLetter)
                }
                Thread.sleep(1_500)
                shot("26_bitmap_translation_de")
                log(
                    "bitmap_translation: ok=$ok cue=${scenario.read { it.translatedCue }?.replace('\n', '/')} seen=${seen.take(5)} " +
                        "statuses=${scenario.read { it.statusHistory.toList() }.takeLast(4)}"
                )
                assertTrue("Bild-Untertitel nicht übersetzt", ok)
                // Der Testfilm ist nur gut 2 Minuten lang; dauerte die erste Übersetzung im Emulator lange, ist er fast zu Ende
                // (dann hieße „läuft nicht mehr“ nur „Film zu Ende“). Vorher an den Anfang.
                val nearEnd = scenario.read { a -> a.player?.let { it.duration > 0 && it.duration - it.currentPosition < 40_000 } == true }
                if (nearEnd) {
                    scenario.onActivity { it.player?.seekTo(0) }
                    waitFor(15_000) { scenario.read { it.player?.playbackState == Player.STATE_READY && it.player?.isPlaying == true } }
                }
                // Übersetzer-Prozess hart beenden (wie bei Speichermangel): Der Film muss weiterlaufen.
                val am = app.getSystemService(android.app.ActivityManager::class.java)
                val mt = am.runningAppProcesses.orEmpty().firstOrNull { it.processName.endsWith(":mt") }
                log("bitmap_translation mt_process=${mt?.processName} pid=${mt?.pid}")
                mt?.let { android.os.Process.killProcess(it.pid) }
                Thread.sleep(6_000)
                val alive = scenario.read { it.player?.playbackState == Player.STATE_READY && it.player?.isPlaying == true && !it.isFinishing }
                log("translator_killed: engine=${scenario.read { (it.player as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.engineState() }}")
                log("translator_killed: film_alive=$alive near_end_before=$nearEnd state=${scenario.read { it.player?.playbackState }} " +
                    "position=${scenario.read { it.player?.currentPosition }} status=${scenario.read { it.subtitleStatus }} statuses=${scenario.read { it.statusHistory.toList() }.takeLast(2)}")
                assertTrue("Film lief nach Ende des Übersetzers nicht weiter", alive)
                assertTrue("Übersetzer lief nicht im eigenen Prozess", mt != null)
                // Danach übersetzt er von selbst weiter (Neustart mit Zeit zum Modell-Laden), statt aufzugeben.
                val beforeKill = scenario.read { it.translatedCue }
                val resumed = waitFor(240_000) {
                    scenario.read {
                        it.liveTranslateTarget == "de" &&
                            it.translatedCue?.let { cue -> cue.isNotBlank() && cue != beforeKill && cue !in english && cue.any(Char::isLetter) } == true
                    }
                }
                log("translator_resumed: ok=$resumed status=${scenario.read { it.subtitleStatus }}")
                assertTrue("Übersetzung nach Neustart des Übersetzers nicht weitergelaufen", resumed)
            }
        }

        // 6. Belastung: großer Anbieter (3000 Sender, 36.000 EPG-Einträge als .xml.gz)
        section("big") {
            val bigStart = System.currentTimeMillis()
            runBlocking {
                val id = container.playlists.add(
                    PlaylistEntity(name = "Groß", type = PlaylistType.M3U, url = "$base/playlist_big.m3u", epgUrl = "$base/epg_big.xml.gz")
                )
                container.playlists.sync(id)
            }
            val total = runBlocking { container.content.zapList(null, false) }.size
            val epgCount = runBlocking { container.db.programmes().observeCount().first() }
            val nowStart = System.currentTimeMillis()
            val now = runBlocking { container.content.nowPlaying(container.content.zapList(null, false)) }
            log(
                "big: import_ms=${nowStart - bigStart} channels_total=$total programmes_total=$epgCount " +
                    "now_playing_all_ms=${System.currentTimeMillis() - nowStart} now_found=${now.size} sync=${container.playlists.state.value}"
            )
        }
        // Einrichtung per Handy: Seite erreichbar, Formular speichert Werte
        section("phone_setup") {
            val server = de.ayutv.data.setup.PhoneSetupServer(container)
            server.start()
            try {
                assertTrue("Keine Adresse", waitFor(5_000) { server.state.value.url != null })
                val url = server.state.value.url!!
                val page = container.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
                val form = okhttp3.FormBody.Builder().add("os_user", "ayu-ci").build()
                val saved = container.http.newCall(okhttp3.Request.Builder().url(url).post(form).build()).execute().use { it.code }
                val user = runBlocking { container.settings.current().openSubtitlesUser }
                log("phone_setup: url=$url get=${page.first} has_form=${"os_key" in page.second} post=$saved user=$user report=${server.state.value.lastSaved?.replace('\n', '|')}")
                assertTrue("Formular nicht gespeichert", page.first == 200 && saved == 200 && user == "ayu-ci")
                // Sicherung herunterladen und wieder einspielen: nichts doppelt, Favoriten bleiben
                val favoritesBefore = runBlocking { container.db.favorites().keys().size }
                val playlistsBefore = runBlocking { container.db.playlists().all().size }
                val backup = container.http.newCall(okhttp3.Request.Builder().url("$url/backup").build()).execute()
                    .use { it.code to it.body.string() }
                val upload = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("backup", "sicherung.json", backup.second.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val restored = container.http.newCall(okhttp3.Request.Builder().url("$url/restore").post(upload).build()).execute().use { it.code }
                val report = server.state.value.lastSaved.orEmpty()
                val playlistsAfter = runBlocking { container.db.playlists().all().size }
                val favoritesAfter = runBlocking { container.db.favorites().keys().size }
                log(
                    "backup: get=${backup.first} bytes=${backup.second.length} has_playlists=${"\"playlists\"" in backup.second} " +
                        "restore=$restored report=$report playlists=$playlistsBefore→$playlistsAfter favorites=$favoritesBefore→$favoritesAfter"
                )
                assertTrue("Sicherung: $report", backup.first == 200 && restored == 200 && report.startsWith("✓") &&
                    playlistsAfter == playlistsBefore && favoritesAfter == favoritesBefore)
                // Ohne Sicherungs-Passwort keine Passwörter oder Schlüssel in der Datei.
                assertTrue("Passwort in ungeschützter Sicherung", "\"password\"" !in backup.second && "deepl_key" !in backup.second && "proxy_password" !in backup.second)
                runBlocking { container.settings.update { it.copy(openSubtitlesUser = "") } }
            } finally {
                server.stop()
            }
        }

        // Suche zeigt alle Treffer (früher fest maximal 40)
        section("search_all") {
            val startedSearch = System.currentTimeMillis()
            val results = runBlocking { container.content.search("Sender") }
            log("search_all: channels=${results.channels.size} programmes=${results.programmes.size} ms=${System.currentTimeMillis() - startedSearch}")
            assertTrue("Suche begrenzt: ${results.channels.size}", results.channels.size >= 3000)
            runBlocking {
                container.settings.rememberSearch("Nachrichten")
                container.settings.rememberSearch("Sender")
                container.settings.rememberSearch("nachrichten")
            }
            val history = runBlocking { container.settings.searchHistory.first() }
            log("search_history=$history")
            assertTrue("Suchverlauf falsch: $history", history.take(2) == listOf("nachrichten", "Sender"))
        }

        // Playlist ausschalten: verschwindet überall, Daten bleiben; wieder einschalten: alles wieder da
        section("playlist_toggle") {
            val big = runBlocking { container.db.playlists().all() }.first { it.name == "Groß" }
            val before = runBlocking { container.content.zapList(null, false) }.size
            runBlocking { container.playlists.setEnabled(big.id, false) }
            val off = runBlocking { container.content.zapList(null, false) }.size
            val searchOff = runBlocking { container.content.search("Sender") }.channels.size
            runBlocking { container.playlists.setEnabled(big.id, true) }
            val on = runBlocking { container.content.zapList(null, false) }.size
            log("playlist_toggle: before=$before off=$off search_off=$searchOff on=$on")
            assertTrue("Ausschalten wirkt nicht", off < before && searchOff == 0 && on == before)
        }
        // Eigene Gruppen und Sortieren: Sender aus der ausgeblendeten Gruppe des Anbieters bleiben in Favoriten und eigenen
        // Gruppen; Reihenfolge in eigener Gruppe, Favoriten und Gruppe des Anbieters lässt sich von Hand festlegen.
        section("custom_groups") {
            val content = container.content
            val all = runBlocking { container.db.channels().all() }
            val group = all.groupBy { it.groupTitle }.entries.first { it.value.size >= 3 }
            val (a, b, c) = group.value.take(3)
            val playlists = group.value.map { it.playlistId }.distinct()
            suspend fun showAgain() = playlists.forEach { content.setGroupsHidden(it, de.ayutv.data.db.GroupKind.LIVE, listOf(group.key), hidden = false) }
            val created = runBlocking { content.createGroup("Meine Sender", listOf(a.key)) }
            // Mehrfachauswahl: mehrere Sender auf einmal in die Gruppe und zu den Favoriten (schon enthaltene zählen nicht).
            val addedToGroup = runBlocking { content.addToGroup(created, listOf(a.key, b.key, c.key)) }
            runBlocking { content.addFavorites(listOf(a.key, c.key)) }
            assertEquals("Mehrfach in Gruppe aufgenommen", 2, addedToGroup)
            try {
                // Gruppe beim Anbieter ausblenden: verschwindet aus „Alle“, bleibt in Favoriten und eigener Gruppe.
                runBlocking { content.hideGroupEverywhere(de.ayutv.data.db.GroupKind.LIVE, group.key) }
                val inAll = runBlocking { content.zapList(null, false) }.any { it.key == a.key }
                val inCustom = runBlocking { content.zapList(created, false) }.map { it.key }
                val inFavorites = runBlocking { content.zapList(null, true) }.map { it.key }
                runBlocking { showAgain() }
                // Sortieren: eigene Gruppe umdrehen, Favoriten umdrehen, Gruppe des Anbieters umdrehen.
                runBlocking { content.saveOrder(created, favorites = false, keys = listOf(c.key, b.key, a.key)) }
                val customOrder = runBlocking { content.zapList(created, false) }.map { it.key }
                runBlocking { content.saveOrder(null, favorites = true, keys = listOf(c.key, a.key)) }
                val favoriteOrder = runBlocking { content.zapList(null, true) }.map { it.key }.filter { it == a.key || it == c.key }
                val original = runBlocking { container.db.channels().inGroup(group.key) }.map { it.key }
                runBlocking { content.saveOrder(group.key, favorites = false, keys = original.reversed()) }
                val providerOrder = runBlocking { container.db.channels().inGroup(group.key) }.map { it.key }
                val label = runBlocking { content.groupLabel(created) }
                log(
                    "custom_groups: hidden_in_all=$inAll custom=${inCustom.size} favorites_has=${a.key in inFavorites && c.key in inFavorites} " +
                        "custom_order_ok=${customOrder == listOf(c.key, b.key, a.key)} favorite_order_ok=${favoriteOrder == listOf(c.key, a.key)} " +
                        "provider_order_ok=${providerOrder == original.reversed()} label=$label"
                )
                assertFalse("Ausgeblendete Gruppe noch in „Alle“", inAll)
                assertTrue("Eigene Gruppe verliert Sender der ausgeblendeten Gruppe: $inCustom", inCustom.containsAll(listOf(a.key, b.key, c.key)))
                assertTrue("Favoriten verlieren Sender der ausgeblendeten Gruppe", a.key in inFavorites && c.key in inFavorites)
                assertEquals(listOf(c.key, b.key, a.key), customOrder)
                assertEquals(listOf(c.key, a.key), favoriteOrder)
                assertEquals(original.reversed(), providerOrder)
                assertEquals("📁 Meine Sender", label)
            } finally {
                runBlocking {
                    showAgain()
                    content.deleteGroup(created)
                    container.db.favorites().remove(a.key)
                    container.db.favorites().remove(c.key)
                    container.db.groupOrder().clear(group.key)
                }
            }
        }
        // Sender-Check: Auflösung ohne Dekodieren erkennen; eine kaputte Adresse gilt als „läuft nicht“
        section("channel_check") {
            val hd = live.firstOrNull { it.name == "HD Test 50fps" } ?: error("HD-Testsender fehlt")
            val check = de.ayutv.data.repo.ChannelCheck(app, container)
            val good = runBlocking { check.probe(hd) }
            val bad = runBlocking { check.probe(hd.copy(key = "kaputt", url = "http://10.0.2.2:8000/gibt-es-nicht.ts")) }
            log("channel_check: hd=${good.first}p in ${good.second} ms, kaputt=${bad.first}")
            assertTrue("Auflösung nicht erkannt", good.first >= 720)
            assertTrue("Kaputte Adresse als laufend erkannt", bad.first == 0)
        }

        // Merkliste: Film merken, steht auf der Startseite, wieder entfernen
        section("watchlist") {
            val movie = runBlocking { container.db.vod().search("Film", 5) }.firstOrNull() ?: error("Film fehlt")
            val added = runBlocking { container.content.toggleFavorite(movie.key) }
            val listed = runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { container.content.watchlistMovies.first { list -> list.any { it.key == movie.key } } } } != null
            runBlocking { container.content.toggleFavorite(movie.key) }
            val removed = runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { container.content.watchlistMovies.first { list -> list.none { it.key == movie.key } } } } != null
            log("watchlist: added=$added listed=$listed removed=$removed")
            assertTrue("Merkliste: added=$added listed=$listed removed=$removed", added && listed && removed)
        }

        // Leitungs-Check: Testsender 10 s laden, Antwortzeit/Datenrate/Aussetzer und Puffer-Empfehlung
        section("line_check") {
            val hd = live.firstOrNull { it.name == "HD Test 50fps" } ?: error("HD-Testsender fehlt")
            val result = runBlocking { de.ayutv.data.repo.LineCheck(container.http).run(hd, de.ayutv.data.AppSettings.DEFAULT_USER_AGENT, seconds = 6) }
            log("line_check: ${result.summary} empfehlung=${result.recommendation}")
            assertTrue("Leitungs-Check ohne Daten: ${result.summary}", result.failed == null && result.kbps > 0)
        }
        // HLS: Datenrate des Senders aus Segmentgröße je Sekunde, nicht die Ladegeschwindigkeit eines Segments (die wäre
        // im Emulator Hunderte Mbit/s). Dazu der Fassungsvergleich über alle Playlists.
        section("hls_bitrate") {
            val hls = live.firstOrNull { it.name == "HLS Test" } ?: error("HLS-Testsender fehlt")
            val check = de.ayutv.data.repo.LineCheck(container.http)
            val kbps = runBlocking { check.hls(hls.url, de.ayutv.data.AppSettings.DEFAULT_USER_AGENT) }
            val lineRate = runBlocking { check.run(hls, de.ayutv.data.AppSettings.DEFAULT_USER_AGENT, seconds = 4) }
            val outcome = runBlocking {
                de.ayutv.data.repo.VariantCompare(container.db, check).run(hls, de.ayutv.data.AppSettings.DEFAULT_USER_AGENT, de.ayutv.data.LiveFormat.TS)
            }
            log("hls_bitrate: kbps=$kbps line_check=${lineRate.kbps} variants=${outcome.candidates.size} ${outcome.text()}")
            assertTrue("HLS-Datenrate unplausibel: $kbps", kbps != null && kbps in 50..30_000)
            assertTrue("Leitungs-Check bei HLS misst die Leitung statt der Datenrate: ${lineRate.kbps}", lineRate.kbps in 50..30_000)
        }
        // Spielstände (OpenLigaDB, echtes Netz): nur protokollieren, das Ergebnis hängt vom Spieltag ab
        section("live_scores") {
            val matches = runBlocking { container.liveScores.current() }
            log("live_scores: matches=${matches.size} first=${matches.firstOrNull()?.let { "${it.pairing} ${it.score} finished=${it.finished}" }}")
        }

        // Fehlerbericht: per Handy-Seite abrufbar, ohne Zugangsdaten
        section("report") {
            val server = de.ayutv.data.setup.PhoneSetupServer(container)
            server.start()
            try {
                assertTrue("Keine Adresse", waitFor(5_000) { server.state.value.url != null })
                val (code, text) = container.http.newCall(okhttp3.Request.Builder().url("${server.state.value.url}/report.txt").build()).execute()
                    .use { it.code to it.body.string() }
                val passwords = runBlocking { container.db.playlists().all() }.mapNotNull { it.password }
                    .mapNotNull { runCatching { de.ayutv.data.security.Credentials.reveal(it) }.getOrDefault(it) }.filter { it.length >= 4 }
                val leaked = passwords.filter { it in text }
                log("report: code=$code bytes=${text.length} sections=${Regex("== (.+?) ==").findAll(text).map { it.groupValues[1] }.toList()} leaked=${leaked.size}")
                // Ruckel-Messung der Menüs: Bis hierher wurde die Oberfläche bedient, also müssen Bilder gezählt sein.
                val frames = text.lines().firstOrNull { it.startsWith("Menüs:") }.orEmpty()
                log("ui_frames: $frames reduced=${de.ayutv.ui.UiPerformance.reduced}")
                // Seit 0.45.8 getrennt: „Menüs: ruhig: 226 Bilder … · während Playlist/TV-Guide laden: …“
                assertTrue("Keine Bilder gemessen: $frames", Regex("""^Menüs: (ruhig|während[^:]*): \d+ Bilder""").containsMatchIn(frames))
                assertTrue("Bericht unvollständig", code == 200 && "Fehlerbericht" in text && "== Protokoll ==" in text && "== Playlists ==" in text)
                assertTrue("Passwort im Bericht", leaked.isEmpty() && !Regex("password=(?!\\*)").containsMatchIn(text))
            } finally {
                server.stop()
            }
        }

        // Konferenz: zweites, kleines Bild, tauschen, schließen; Anstoß-Hinweis im Bild
        val pipMain = live.firstOrNull { it.name == "HD Test 50fps" }
        val pipSecond = live.firstOrNull { it.name == "HLS Test" }
        if (pipMain != null && pipSecond != null) section("pip") {
            val main = pipMain
            val second = pipSecond
            launchPlayer(PlayerActivity.liveIntent(app, main.key, null)).use { scenario ->
                waitFor(25_000) { scenario.read { it.player?.playbackState } == Player.STATE_READY }
                scenario.onActivity { it.startPip(second) }
                val pipReady = waitFor(20_000) { scenario.read { it.pipPlayer?.playbackState } == Player.STATE_READY }
                Thread.sleep(3_000)
                shot("40_pip")
                val pipFrames = scenario.read { it.pipPlayer?.videoSize?.let { f -> "${f.width}x${f.height}" } }
                key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                val swapped = waitFor(15_000) { scenario.read { it.current?.key == second.key && it.pipChannel?.key == main.key } }
                container.dueReminder.value = de.ayutv.data.reminders.Reminder(main.key, main.name, "Testspiel A – B", System.currentTimeMillis() + 60_000)
                val shown = waitFor(3_000) { scenario.read { it.dueReminder != null } }
                shot("41_reminder_overlay")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val switched = waitFor(10_000) { scenario.read { it.current?.key == main.key } }
                scenario.onActivity { it.closePip() }
                log("pip: ready=$pipReady format=$pipFrames swapped=$swapped reminder_shown=$shown switched=$switched message=${scenario.read { it.message }}")
                assertTrue("Kleines Bild läuft nicht", pipReady && swapped)
                assertTrue("Anstoß-Hinweis fehlt", shown && switched)
            }
        }

        // Handy übersetzt: Schnittstelle des Helfers (hier im selben Gerät) beantwortet die Übersetzung
        section("phone_helper") {
            val helper = de.ayutv.data.remote.TranslationHelperServer()
            val address = helper.start() ?: error("Helfer ohne Adresse")
            try {
                de.ayutv.data.translate.PhoneHelper.register(address, "CI")
                val before = de.ayutv.data.translate.PhoneHelper.served
                val result = runBlocking { de.ayutv.data.repo.Translators.withPhone.translate(listOf("Hello, how are you?", "Thank you."), "de", "en") }
                val served = de.ayutv.data.translate.PhoneHelper.served - before
                log("phone_helper: address=${address.substringBeforeLast('/')} served=$served helper_lines=${helper.state.value.lines} result=$result")
                assertTrue("Handy hat nicht übersetzt", served == 2 && helper.state.value.lines == 2 && result.first() != "Hello, how are you?")
                // Kein Schlüssel: abgewiesen
                val wrong = container.http.newCall(okhttp3.Request.Builder().url(address.substringBeforeLast('/') + "/x/translate")
                    .post("{}".toRequestBody("application/json".toMediaTypeOrNull())).build()).execute().use { it.code }
                assertTrue("Ohne Schlüssel angenommen: $wrong", wrong == 404)
            } finally {
                de.ayutv.data.translate.PhoneHelper.register(null, null)
                helper.stop()
            }
        }

        // „Gerät prüfen“ und Speicher: liefert alle Zeilen, Aufräumen läuft durch.
        section("device_check") {
            val text = runBlocking { de.ayutv.data.DeviceCheck.run(app, container) }
            val usage = de.ayutv.data.StorageCleaner.usage(app)
            val cleaned = de.ayutv.data.StorageCleaner.clean(app)
            log("device_check: ${text.replace('\n', '|')} usage=${usage.joinToString { "${it.label}=${it.bytes / 1_000_000}MB" }} cleaned=$cleaned")
            assertTrue("Gerät prüfen unvollständig: $text", listOf("Gerät:", "Speicher frei", "Menüs:", "Übersetzung", "Leitung", "OpenSubtitles").all { it in text })
        }

        // Proxy nur für DnA-TV: HTTP und SOCKS5 mit Anmeldung, falsches Passwort wird gemeldet, nie direkt ausweichen.
        section("proxy") {
            val proxy = TestProxy("dna", "geheim")
            try {
                fun apply(type: de.ayutv.data.net.ProxyType, password: String) {
                    runBlocking {
                        container.settings.update {
                            it.copy(proxyMode = de.ayutv.data.net.ProxyMode.ALL, proxyType = type, proxyHost = "127.0.0.1",
                                proxyPort = if (type == de.ayutv.data.net.ProxyType.HTTP) proxy.httpPort else proxy.socksPort,
                                proxyUser = "dna", proxyPassword = password)
                        }
                    }
                    waitFor(5_000) { de.ayutv.data.net.AppProxy.current.let { it.type == type && it.password == password } }
                }
                fun fetch(): String = runCatching {
                    container.http.newCall(okhttp3.Request.Builder().url("http://anbieter.example/ping").build()).execute()
                        .use { "${it.code} ${it.body.string()}" }
                }.getOrElse { "Fehler ${it.message}" }
                apply(de.ayutv.data.net.ProxyType.HTTP, "geheim")
                val http = fetch()
                apply(de.ayutv.data.net.ProxyType.SOCKS5, "geheim")
                val socks = fetch()
                apply(de.ayutv.data.net.ProxyType.HTTP, "falsch")
                val wrong = fetch()
                val reported = de.ayutv.data.net.AppProxy.recentFailure()
                log("proxy: http=$http socks=$socks wrong=$wrong reported=$reported seen=${proxy.seen}")
                assertEquals("200 http-proxy anbieter.example", http)
                assertEquals("200 socks-proxy anbieter.example", socks)
                assertTrue("Falsches Passwort nicht erkannt: $wrong", wrong.startsWith("407"))
                assertTrue("Falsches Passwort nicht gemeldet", reported.orEmpty().contains("Anmeldung"))
            } finally {
                runBlocking { container.settings.update { it.copy(proxyMode = de.ayutv.data.net.ProxyMode.OFF, proxyPassword = "") } }
                proxy.close()
            }
        }

        // Vom Handy auf den Fernseher: /play startet den Sender (Zuordnung über die Adresse, nicht den Schlüssel)
        section("play_on_tv") {
            container.remote.start()
            try {
                assertTrue("Fernbedienung ohne Adresse", waitFor(5_000) { container.remote.address != null })
                val channel = runBlocking { container.db.channels().all() }.first { it.name == "HLS Test" }
                val client = de.ayutv.data.remote.RemoteClient(app, container)
                val name = runBlocking { client.check(container.remote.address!!, container.remote.pin) }
                runBlocking { client.play(de.ayutv.data.remote.RemotePlay(true, channel.url, channel.name), container.remote.address!!, container.remote.pin) }
                val started = waitFor(10_000) { (container.foreground?.get() as? PlayerActivity)?.current?.key == channel.key }
                // Sender, den es hier nicht gibt (andere Playlist auf dem Handy): läuft direkt über die Adresse
                val foreign = channel.url + "?vom=handy"
                runBlocking { client.play(de.ayutv.data.remote.RemotePlay(true, foreign, "Vom Handy"), container.remote.address!!, container.remote.pin) }
                // Der Player darf nur vom Hauptthread aus gelesen werden.
                val foreignPlays = waitFor(15_000) {
                    var ready = false
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        ready = (container.foreground?.get() as? PlayerActivity)?.let { it.current?.url == foreign && it.player?.playbackState == Player.STATE_READY } == true
                    }
                    ready
                }
                log("play_on_tv: tv=$name started=$started foreign=$foreignPlays")
                assertTrue("Sender startet nicht auf dem Fernseher", started)
                assertTrue("Fremder Sender startet nicht", foreignPlays)
            } finally {
                (container.foreground?.get() as? PlayerActivity)?.let { activity -> activity.runOnUiThread { activity.finish() } }
                container.remote.stop()
            }
        }

        // DNA Player mit der eigenen DNA Engine (Standard auf jedem Gerät): eigener TS-Leser, MediaCodec, AudioTrack, Ton als
        // Takt, Zeitversatz im Ringspeicher des Geräts.
        fun dna(): PlayerActivity? = container.foreground?.get() as? PlayerActivity
        fun onMain(block: () -> Boolean): Boolean {
            var result = false
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
            return result
        }
        fun dnaEngine() = onMain { dna()?.player is de.ayutv.player.dna.engine.DnaEnginePlayer }
        fun dnaPicture() = onMain {
            dna()?.let { it.player is de.ayutv.player.dna.engine.DnaEnginePlayer && it.player?.playbackState == Player.STATE_READY && (it.player?.videoSize?.height ?: 0) > 0 } == true
        }
        fun engineInfo(): String {
            var text = ""
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                text = dna()?.let { "${it.engineLabel} state=${it.player?.playbackState} video=${it.player?.videoSize?.width}x${it.player?.videoSize?.height}" } ?: "kein Player"
            }
            return text
        }
        fun dnaStats(): Triple<Long, Int, Int> {
            var result = Triple(0L, 0, 0)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                (dna()?.player as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.let { result = Triple(it.currentPosition, it.renderedFrames, it.droppedFrames) }
            }
            return result
        }
        fun dnaInfo(): String {
            var text = ""
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                text = (dna()?.player as? de.ayutv.player.dna.engine.DnaEnginePlayer)?.lastInfo.orEmpty()
            }
            return text
        }
        fun dnaRewindable(): Long {
            var ms = 0L
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { ms = dna()?.rewindableMs ?: 0L }
            return ms
        }
        fun dnaBehind(): Long {
            var ms = 0L
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { ms = dna()?.behindLiveMs ?: 0L }
            return ms
        }
        val liveChannels = channels.filter { "/movie/" !in it.url }
        runBlocking { container.settings.update { it.copy(liveTimeshift = true) } }
        // HD mit 50 fps: Bild, laufende Uhr, flüssig; dann Zeitversatz: anhalten, weiter (genau dort), zurück, wieder live.
        liveChannels.firstOrNull { it.name == "HD Test 50fps" }?.let { hd -> section("dna_engine") {
            val scenario = launchPlayer(PlayerActivity.liveIntent(app, hd.key, null))
            try {
                val engine = waitFor(10_000) { dnaEngine() }
                val video = waitFor(20_000) { dnaPicture() }
                // Erstes Bild ≠ läuft: Seit 0.61.9 wartet das Bild auf den Ton (gemeinsamer Start). Gemessen wird ab dann,
                // wenn die Wiedergabe wirklich läuft, und nach einer Sekunde Anlauf.
                waitFor(15_000) { scenario.read { it.player?.playbackState == Player.STATE_READY && it.player?.isPlaying == true } }
                Thread.sleep(1_000)
                val (p0, r0, d0) = dnaStats()
                Thread.sleep(5_000)
                val (p1, r1, d1) = dnaStats()
                shot("97_dna_engine_hd")
                val advanced = p1 - p0
                val rendered = r1 - r0
                val dropped = d1 - d0
                log("dna_engine: engine=$engine video=$video advanced_ms=$advanced rendered=$rendered dropped=$dropped rewindable=${dnaRewindable()} info=${dnaInfo()} ${engineInfo()}")
                assertTrue("DNA Player nutzt nicht die DNA Engine", engine)
                assertTrue("DNA Engine zeigt kein Bild", video)
                assertTrue("Uhr der DNA Engine läuft nicht ($advanced ms in 5 s)", advanced in 3_500..6_500)
                // 50 fps: in 5 s um die 250 Bilder; der Emulator ist langsam, aber flüssig muss es sein.
                assertTrue("DNA Engine zeigt zu wenige Bilder ($rendered in 5 s, $dropped verworfen)", rendered >= 150)
            } finally {
                runCatching { scenario.close() }
                Thread.sleep(1_000)
            }
        } }
        liveChannels.firstOrNull { it.name == "HD Test 50fps" }?.let { hd -> section("dna_timeshift") {
            val scenario = launchPlayer(PlayerActivity.liveIntent(app, hd.key, null))
            try {
                assertTrue("DNA Engine zeigt kein Bild", waitFor(20_000) { dnaPicture() })
                Thread.sleep(6_000)
                // Anhalten: Bild steht, Aufzeichnung läuft weiter.
                key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                Thread.sleep(4_000)
                val behindPaused = dnaBehind()
                // Weiter: genau dort, dahinter zurück.
                key(KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumed = waitFor(10_000) { dnaPicture() && onMain { dna()?.player?.isPlaying == true } }
                Thread.sleep(2_000)
                val behindPlaying = dnaBehind()
                // Wieder live.
                key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                val live = waitFor(10_000) { dnaPicture() && dnaBehind() < 2_000 }
                shot("98_dna_timeshift")
                log("dna_timeshift: rewindable=${dnaRewindable()} behind_paused=$behindPaused resumed=$resumed behind_playing=$behindPlaying live=$live info=${dnaInfo()} ${engineInfo()}")
                assertTrue("Zeitversatz der DNA Engine nimmt nichts auf", dnaRewindable() > 3_000)
                assertTrue("Nach dem Anhalten nicht weiter", resumed)
                assertTrue("Weiter nach Pause nicht dort, wo angehalten ($behindPlaying ms hinter Live)", behindPlaying >= 2_500)
                assertTrue("Nicht zurück zu Live", live)
            } finally {
                runCatching { scenario.close() }
                Thread.sleep(1_000)
            }
        } }
        // DVB-Untertitel im Live-TV (Bilduntertitel wie bei Sender/Sender): Spur erkannt, mit Wunschsprache eingeschaltet, im Bild.
        liveChannels.firstOrNull { it.name == "DVB Untertitel Test" }?.let { dvb -> section("dna_dvb_subtitles") {
            val scenario = launchPlayer(PlayerActivity.liveIntent(app, dvb.key, null))
            try {
                assertTrue("DVB-Sender zeigt kein Bild", waitFor(20_000) { dnaPicture() })
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    dna()?.player?.let { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("de").build()
                    }
                }
                var trackInfo = ""
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    trackInfo = dna()?.player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }
                        ?.joinToString { g -> "${g.getTrackFormat(0).sampleMimeType}/${g.getTrackFormat(0).language}/sel=${g.isSelected}" }.orEmpty()
                }
                val shown = waitFor(20_000) { onMain { dna()?.player?.currentCues?.cues?.any { it.bitmap != null } == true } }
                Thread.sleep(500)
                shot("98b_dna_dvb_subtitles")
                log("dna_dvb_subtitles: tracks=$trackInfo shown=$shown info=${dnaInfo()} ${engineInfo()}")
                assertTrue("DVB-Untertitelspur fehlt: $trackInfo", trackInfo.contains("dvbsubs"))
                assertTrue("DVB-Untertitel nicht im Bild", shown)
            } finally {
                runCatching { scenario.close() }
                Thread.sleep(1_000)
            }
        } }
        // 4K HEVC 10 Bit muss laufen – ohne Ausnahme. Im Emulator gibt es dafür nur den Software-Decoder (langsam, aber
        // ein laufendes Bild ist Pflicht); auf Handy und Fire TV nimmt die Engine den Hardware-Decoder.
        liveChannels.firstOrNull { it.name == "UHD Test HEVC" }?.let { uhd -> section("dna_engine_4k") {
            val scenario = launchPlayer(PlayerActivity.liveIntent(app, uhd.key, null))
            try {
                val shown = waitFor(45_000) { dnaPicture() }
                val (p0, r0, _) = dnaStats()
                Thread.sleep(5_000)
                val (position, rendered, dropped) = dnaStats()
                shot("99_dna_engine_4k")
                log("dna_engine_4k: shown=$shown position_ms=$position advanced_ms=${position - p0} rendered=${rendered - r0} dropped=$dropped info=${dnaInfo()} ${engineInfo()}")
                // Gerät ohne 4K-HEVC-Decoder, auf dem der eigene Software-Decoder bewusst aus ist (Fernseher-Sticks und
                // Geräte unter 2,5 GB Arbeitsspeicher, auch der Emulator): Erwartet ist eine klare Meldung statt eines Bildes.
                val noSoft = !de.ayutv.player.dna.engine.DnaEngineCore.softVideoSupported("video/hevc")
                if (!shown && noSoft && dnaInfo().let { "scheitern an" in it || "Kein Bild-Decoder" in it }) {
                    log("dna_engine_4k: kein 4K-Decoder, Software-Decoder aus – klare Meldung statt Bild")
                    return@section
                }
                assertTrue("4K: kein Bild (${dnaInfo()})", shown)
                assertTrue("4K: Bild steht (${rendered - r0} Bilder in 5 s)", rendered - r0 > 0)
                assertTrue("4K: Uhr steht (${position - p0} ms in 5 s)", position - p0 > 2_000)
            } finally {
                runCatching { scenario.close() }
                Thread.sleep(1_000)
            }
        } }

        // Der 4K-Test aus den Einstellungen: läuft durch und meldet ein Ergebnis mit Bild (im Emulator in Software, also
        // eher „nicht flüssig“ – auf Geräten mit 4K-Decoder „flüssig“).
        section("uhd_selftest") {
            app.getSharedPreferences("selftest", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            de.ayutv.player.UhdSelfTest.start(app)
            val done = waitFor(90_000) { de.ayutv.player.UhdSelfTest.lastResult(app) != null }
            val result = de.ayutv.player.UhdSelfTest.lastResult(app)
            shot("99b_uhd_selftest")
            log("uhd_selftest: done=$done result=$result")
            Thread.sleep(9_000)
            assertTrue("4K-Test ohne Ergebnis", done)
            // Ohne 4K-Decoder und ohne erlaubten Software-Decoder: ein klares „✗ … scheitern an …“ ist das richtige Ergebnis.
            val noSoft = !de.ayutv.player.dna.engine.DnaEngineCore.softVideoSupported("video/hevc")
            val clearNo = noSoft && result?.startsWith("✗") == true && ("scheitern an" in result || "Kein Bild-Decoder" in result)
            assertTrue("4K-Test: $result", result?.startsWith("✗") == false || clearNo)
        }

        log("done failures=$failures")
        assertTrue("$failures Abschnitte fehlgeschlagen", failures == 0)
    }
}

/**
 * Kleiner Proxy für den Gerätetest: HTTP (Basic-Anmeldung) und SOCKS5 (Benutzer/Passwort). Statt weiterzuleiten
 * antwortet er selbst mit Art und Ziel, so ist sicher, dass die Anfrage über ihn lief.
 */
private class TestProxy(private val user: String, private val password: String) : AutoCloseable {
    private val http = java.net.ServerSocket(0)
    private val socks = java.net.ServerSocket(0)
    val httpPort get() = http.localPort
    val socksPort get() = socks.localPort
    val seen: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    init {
        kotlin.concurrent.thread(isDaemon = true) { serve(http, ::handleHttp) }
        kotlin.concurrent.thread(isDaemon = true) { serve(socks, ::handleSocks) }
    }

    private fun serve(server: java.net.ServerSocket, handle: (java.net.Socket) -> Unit) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            kotlin.concurrent.thread(isDaemon = true) { client.use { runCatching { handle(it) }.onFailure { e -> seen += "Fehler ${e.message}" } } }
        }
    }

    private fun readHead(input: java.io.InputStream): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else line.append(b.toChar())
        }
        return lines
    }

    private fun reply(out: java.io.OutputStream, code: String, body: String, extra: String = "") {
        out.write("HTTP/1.1 $code\r\n${extra}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
        out.flush()
    }

    private fun handleHttp(socket: java.net.Socket) {
        val head = readHead(socket.getInputStream())
        val target = head.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
        val auth = head.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val expected = "Basic " + android.util.Base64.encodeToString("$user:$password".toByteArray(), android.util.Base64.NO_WRAP)
        seen += "http $target auth=${auth != null}"
        if (auth != expected) reply(socket.getOutputStream(), "407 Proxy Authentication Required", "", "Proxy-Authenticate: Basic realm=\"t\"\r\n")
        else reply(socket.getOutputStream(), "200 OK", "http-proxy " + android.net.Uri.parse(target).host)
    }

    private fun handleSocks(socket: java.net.Socket) {
        val input = java.io.DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        check(input.readUnsignedByte() == 5) { "kein SOCKS5" }
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (2.toByte() !in methods) {
            seen += "socks ohne Anmeldung angeboten: ${methods.toList()}"
            out.write(byteArrayOf(5, 0xFF.toByte())); return
        }
        out.write(byteArrayOf(5, 2))
        check(input.readUnsignedByte() == 1)
        val name = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
        val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
        val ok = name == user && pass == password
        out.write(byteArrayOf(1, if (ok) 0 else 1))
        if (!ok) return
        check(input.readUnsignedByte() == 5)
        input.readUnsignedByte(); input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            else -> ByteArray(16).also { input.readFully(it) }.toString()
        }
        input.readUnsignedShort()
        seen += "socks $host"
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        readHead(input)
        reply(out, "200 OK", "socks-proxy $host")
    }

    override fun close() {
        http.close()
        socks.close()
    }
}

/** Antwortet wie DeepL: jede Zeile wird zu „TR:<Zeile>“. Zählt Anfragen und Texte. */
private class FakeDeepL : AutoCloseable {
    private val socket = java.net.ServerSocket(0)
    val url get() = "http://127.0.0.1:${socket.localPort}"
    @Volatile var requests = 0
    val texts: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    init {
        kotlin.concurrent.thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                kotlin.concurrent.thread(isDaemon = true) {
                    client.use { c ->
                        runCatching {
                            val input = java.io.DataInputStream(c.getInputStream())
                            val head = ArrayList<String>()
                            val line = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                if (b == '\n'.code) {
                                    val text = line.toString().trimEnd('\r')
                                    if (text.isEmpty()) break
                                    head += text
                                    line.clear()
                                } else line.append(b.toChar())
                            }
                            val length = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                            val body = ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
                            val json = org.json.JSONObject(body)
                            val list = json.getJSONArray("text")
                            requests++
                            val out = org.json.JSONArray()
                            for (i in 0 until list.length()) {
                                texts += list.getString(i)
                                out.put(org.json.JSONObject().put("text", "TR:" + list.getString(i)))
                            }
                            val payload = org.json.JSONObject().put("translations", out).toString().toByteArray()
                            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload)
                        }
                    }
                }
            }
        }
    }

    override fun close() = socket.close()
}

package de.ayutv

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.ui.components.hasTouchscreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bedienung per Touchscreen auf einem Handy (emuliert): tippen statt Fernbedienung, Hoch- und Querformat.
 * Die CI stellt unter 10.0.2.2:8000 dieselbe Test-Playlist bereit wie für den Rundgang.
 */
@RunWith(AndroidJUnit4::class)
class PhoneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation
    private val app = ApplicationProvider.getApplicationContext<AyuApp>()
    private val out = File(app.filesDir, "ci").apply { deleteRecursively(); mkdirs() }
    private val results = StringBuilder()
    private var failures = 0

    private fun log(line: String) {
        results.append(line).append('\n')
        File(out, "results.txt").writeText(results.toString())
        android.util.Log.i("AyuPhone", line)
    }

    private fun section(name: String, block: () -> Unit) {
        try {
            block()
            log("ok: $name")
        } catch (t: Throwable) {
            failures++
            log("FEHLER in $name: ${t.javaClass.simpleName}: ${t.message}")
            android.util.Log.e("AyuPhone", "Fehler in $name", t)
        }
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val bitmap = automation.takeScreenshot() ?: return
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        File(out, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    /** Erstes sichtbares Element mit diesem Text (oder dieser Beschreibung) auf dem Bildschirm. */
    private fun find(text: String): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
            if (label.contains(text) && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun waitFor(text: String, timeoutMs: Long = 15_000): AccessibilityNodeInfo {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            find(text)?.let { return it }
            Thread.sleep(300)
        }
        error("„$text“ nicht auf dem Bildschirm – zu sehen: ${visibleTexts()}")
    }

    /** Was gerade zu sehen ist (für die Fehlersuche: Bildschirmfotos des Emulators sind nicht immer erreichbar). */
    private fun visibleTexts(): String {
        val root = automation.rootInActiveWindow ?: return "kein Fenster"
        val out = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < 25) {
            val node = queue.removeFirst()
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty() && node.isVisibleToUser) out += label.take(30)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return "${root.packageName}: " + out.joinToString(" | ")
    }

    /** Wie ein Finger: Tippen in die Mitte des Elements. */
    private fun tap(node: AccessibilityNodeInfo) {
        val r = Rect().also(node::getBoundsInScreen)
        tapAt(r.centerX(), r.centerY())
    }

    private fun tapAt(x: Int, y: Int) {
        automation.executeShellCommand("input tap $x $y").close()
        Thread.sleep(800)
    }

    private fun tap(text: String) = tap(waitFor(text))

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return false
    }

    /** Sind die Systemknöpfe (Zurück, Home, Übersicht) gerade ausgeblendet? */
    private fun navigationBarsHidden(scenario: ActivityScenario<MainActivity>): Boolean {
        var hidden = false
        scenario.onActivity { activity ->
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
            hidden = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) == false
        }
        return hidden
    }

    @Test
    fun phone() {
        val container = app.container
        val base = "http://10.0.2.2:8000"
        runBlocking {
            container.settings.update { it.copy(internetGuide = false, ownGuideOnly = false, countryChoiceDone = true) }
            if (container.db.playlists().all().isEmpty()) {
                val id = container.playlists.add(PlaylistEntity(name = "CI-Test", type = PlaylistType.M3U, url = "$base/playlist.m3u", epgUrl = "$base/epg.xml"))
                container.playlists.sync(id)
            }
        }
        val channels = runBlocking { container.content.zapList(null, false) }
        log("touchscreen=${app.hasTouchscreen()} channels=${channels.size}")

        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            section("start_hochformat") {
                waitFor("Live TV")
                shot("phone_home_portrait")
            }
            section("live_tippen") {
                // Die Navigation liegt unten, wo das System seine Knöpfe einblendet. Sind die nach dem Start noch zu sehen,
                // trifft der Tipp die Home-Taste statt „Live TV“ (Rundgang 18.11.: danach war der Startbildschirm von
                // Android zu sehen). Erst tippen, wenn die Systemleiste ausgeblendet ist.
                val barsHidden = waitUntil(8_000) { navigationBarsHidden(scenario) }
                if (!barsHidden) log("live_tippen: Systemleiste nach 8 s noch sichtbar")
                tap("Live TV")
                // Kam der Tipp nicht an (Emulator unter Last), einmal nachtippen – und festhalten, was zu sehen war.
                if (runCatching { waitFor(channels.first().name, 8_000) }.isFailure) {
                    log("live_tippen: erster Tipp ohne Wirkung – zu sehen: ${visibleTexts()}")
                    tap("Live TV")
                }
                waitFor(channels.first().name)
                shot("phone_live_portrait")
            }
            section("sender_tippen") {
                // Erster Tipp zeigt die Vorschau, der zweite spielt (wie auf dem Fernseher: fokussieren, OK).
                val node = waitFor(channels.first().name)
                tap(node)
                if (find("Live TV") != null) tap(waitFor(channels.first().name))
                val end = System.currentTimeMillis() + 20_000
                var playing = false
                while (System.currentTimeMillis() < end && !playing) {
                    Thread.sleep(500)
                    playing = automation.rootInActiveWindow?.packageName == app.packageName && find("Live TV") == null
                }
                check(playing) { "Player nicht gestartet" }
                Thread.sleep(4_000)
                shot("phone_player_portrait")
                // Tippen ins Bild zeigt die Leiste mit den Touch-Knöpfen.
                val size = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
                tapAt(size.centerX(), size.centerY())
                shot("phone_player_controls")
            }
            section("querformat") {
                automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                Thread.sleep(3_000)
                shot("phone_player_landscape")
                automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                Thread.sleep(2_000)
            }
            section("zurueck_und_einstellungen") {
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                Thread.sleep(1_500)
                if (find("Einstellungen") == null) {
                    automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                    Thread.sleep(1_500)
                }
                tap("Einstellungen")
                Thread.sleep(1_500)
                tap("Netz & Daten")
                waitFor("Fernseher fernbedienen")
                shot("phone_settings")
                tap("Fernseher fernbedienen")
                waitFor("PIN vom Fernseher")
                shot("phone_remote")
            }
            section("profile") {
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                Thread.sleep(1_000)
                tap("Start")
                tap(container.profiles.active.value.name)
                waitFor("Wer schaut?")
                shot("phone_profiles")
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            }
        }
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        log("failures=$failures")
        assertEquals("Fehler beim Handy-Test, siehe results.txt", 0, failures)
    }
}

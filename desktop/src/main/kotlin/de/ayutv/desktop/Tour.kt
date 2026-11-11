package de.ayutv.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Movie
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.PlaylistType
import de.ayutv.desktop.data.Programme
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.data.display
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.ui.AppState
import de.ayutv.desktop.ui.Playback
import de.ayutv.desktop.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Foto-Rundgang für die CI (`DnA-TV.exe --tour <Ordner>`): DnA-TV mit Beispieldaten (Sender, TV-Guide mit
 * Bundesliga-Spiel, Filme, Serien) starten, alle Bereiche und das Player-Menü mit seinen Unterseiten aufrufen und
 * jedes Mal das Fenster fotografieren. So lässt sich die Windows-Oberfläche am Bild prüfen.
 */
object Tour {
    fun run(output: String?) {
        val out = File(output ?: "tour").apply { mkdirs() }
        val log = ArrayList<String>()
        fun note(line: String) { log += line; File(out, "results.txt").writeText(log.joinToString("\n")) }
        val store = Store(File(out, "data").apply { deleteRecursively(); mkdirs() })
        fill(store)
        de.ayutv.desktop.ui.Palette.use(store.user.value.uiTheme, store.user.value.accent)
        val input = RemoteInput()
        application {
            val window = rememberWindowState(size = DpSize(1000.dp, 700.dp), placement = WindowPlacement.Floating)
            val scope = rememberCoroutineScope()
            val app = remember { AppState(store, Sync(store), scope, input) }
            Window(onCloseRequest = { exitApplication() }, state = window, title = "DnA-TV") {
                val awt = this.window
                DisposableEffect(awt) {
                    input.window = awt
                    app.remote.window = awt
                    onDispose { }
                }
                App(app, window)
                LaunchedEffect(Unit) {
                    try {
                        suspend fun shot(name: String, wait: Long = 1_400) {
                            delay(wait)
                            withContext(Dispatchers.IO) {
                                val bounds = java.awt.Rectangle(awt.locationOnScreen, awt.size)
                                ImageIO.write(java.awt.Robot().createScreenCapture(bounds), "png", File(out, "$name.png"))
                            }
                            note("shot $name")
                        }
                        suspend fun press(button: RemoteButton, wait: Long = 450) { input.tap(button); delay(wait) }
                        awt.toFront()
                        shot("01_home", 4_000)
                        for ((name, screen) in listOf(
                            "02_live" to Screen.LIVE, "03_guide" to Screen.GUIDE, "04_sport" to Screen.SPORT,
                            "05_movies" to Screen.MOVIES, "06_series" to Screen.SERIES, "07_search" to Screen.SEARCH,
                            "08_settings" to Screen.SETTINGS,
                        )) {
                            app.screen = screen
                            shot(name)
                        }
                        // Mitten im Bereichswechsel (zeigt, dass der Übergang läuft).
                        app.screen = Screen.LIVE
                        shot("09_transition", 90)
                        delay(800)
                        // Serie öffnen: Folgen gleiten herein.
                        app.screen = Screen.SERIES
                        delay(800)
                        app.openSeries = store.series().firstOrNull()
                        shot("09b_series_open")
                        app.openSeries = null

                        // Player mit Menü: Übersicht, Untertitel, zurück, Bild.
                        val list = store.channels()
                        val sport = list.first { it.name.startsWith("Sportkanal") }
                        app.play(Playback.Live(sport, list))
                        shot("10_player", 2_500)
                        press(RemoteButton.MENU, 900)
                        shot("11_menu", 300)
                        press(RemoteButton.OK, 900)
                        shot("12_menu_subtitles", 300)
                        press(RemoteButton.BACK, 900)
                        shot("13_menu_back", 300)
                        repeat(4) { press(RemoteButton.DOWN, 250) }
                        press(RemoteButton.OK, 900)
                        shot("14_menu_picture", 300)
                        press(RemoteButton.BACK, 600)
                        press(RemoteButton.BACK, 600)
                        shot("15_menu_closed", 300)
                        app.stopPlayback()
                        app.screen = Screen.HOME
                        shot("16_back_home", 1_200)
                        note("headline: " + store.programmes(sport).firstOrNull { it.start <= System.currentTimeMillis() && it.stop > System.currentTimeMillis() }?.display())
                        note("result=OK")
                    } catch (t: Throwable) {
                        note("error=$t")
                    }
                    exitProcess(0)
                }
            }
        }
    }

    /** Beispieldaten wie bei einem kleinen Anbieter: Sender mit TV-Guide, Filme, Serien. */
    private fun fill(store: Store) {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val start = now - now % hour - hour
        store.savePlaylist(Playlist(1, "Beispiel", PlaylistType.M3U, "http://127.0.0.1:9/beispiel.m3u", lastSync = now))
        val names = listOf(
            "Kanal Eins HD" to "Deutschland", "Kanal Zwei HD" to "Deutschland", "Kanal Drei HD" to "Deutschland", "Kanal Vier HD" to "Deutschland",
            "Sportkanal 1" to "Sport", "Sportkanal 2" to "Sport", "Sportkanal 3" to "Sport", "Sportkanal 4 HD" to "Sport",
            "Kinderkanal" to "Kinder", "Kinderkanal 2" to "Kinder",
        )
        val channels = names.mapIndexed { i, (name, group) ->
            Channel("1:c:${i + 1}", 1, name, group, url = "http://127.0.0.1:9/${i + 1}.ts", epgId = "c${i + 1}", number = i + 1)
        }
        val movies = (1..14).map { i ->
            Movie("1:m:$i", 1, listOf("Der lange Weg", "Nachtzug", "Sommer am See", "Die Rückkehr", "Polarlicht", "Im Schatten", "Küstenwache")[i % 7] + " ($i)",
                if (i % 2 == 0) "Drama" else "Action", rating = "%.1f".format(java.util.Locale.ROOT, 6 + (i % 4) * 0.7), url = "http://127.0.0.1:9/m$i.mp4", added = now - i * hour)
        }
        val series = (1..6).map { i ->
            Series("1:s:$i", 1, "$i", listOf("Hafenkante", "Bergdoktor", "Stadtgeschichten")[i % 3] + " $i", "Serien", plot = "Beispielserie", rating = "7.${i}", added = now - i * hour)
        }
        store.setContent(1, Store.Content(channels, movies, series))
        val guide = channels.associate { c ->
            val match = c.name.startsWith("Sportkanal")
            c.key to (0 until 8).mapNotNull { k ->
                val from = start + k * hour
                when {
                    // Spiele dauern zwei Stunden: die Stunde danach entfällt.
                    match && k == 2 -> null
                    c.name == "Sportkanal 1" && k == 1 ->
                        Programme(from, from + 2 * hour, "Bundesliga", "Borussia Dortmund – Eintracht Frankfurt", "Fußball: Bundesliga, 7. Spieltag", "Fußball", flags = 2)
                    c.name == "Sportkanal 2" && k == 1 ->
                        Programme(from, from + 2 * hour, "2. Bundesliga", "Hannover 96 – FC Schalke 04", null, "Fußball", flags = 2)
                    c.name == "Sportkanal 3" && k == 1 -> Programme(from, from + 2 * hour, "Premier League: Arsenal – Chelsea", category = "Fußball")
                    else -> Programme(from, from + hour, "${c.name.substringBefore(" HD")}: Sendung ${k + 1}", "Folge ${k + 1}", "Beschreibung der Sendung.")
                }
            }
        }
        store.setGuide(guide)
        store.updateUser { it.copy(countriesAsked = true, guideSyncedAt = now, favorites = channels.take(3).map { c -> c.key }) }
    }
}

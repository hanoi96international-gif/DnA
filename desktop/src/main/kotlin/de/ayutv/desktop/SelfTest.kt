package de.ayutv.desktop

import de.ayutv.desktop.player.VideoPlayer
import java.io.File
import kotlin.system.exitProcess

/**
 * Starttest für die CI (`DnA-TV.exe --selftest <Datei>`): DNA Engine (FFmpeg-Bibliotheken) laden, mitgelieferte
 * Testvideos (Blau 3DA9FC, H.264 8 Bit mit AAC und HEVC 10 Bit mit AC-3) abspielen und prüfen, dass Bilder und Ton
 * ankommen und die Farbe stimmt. Ergebnis zeilenweise in die Datei.
 */
object SelfTest {
    fun run(output: String?) {
        val lines = ArrayList<String>()
        var ok = false
        try {
            // Fire-TV-Fernbedienung: Raw Input und Tastatur-Hook unter Windows einrichten (ohne Taste zu drücken).
            if (de.ayutv.desktop.input.WindowsRemote.isWindows) {
                val remote = de.ayutv.desktop.input.WindowsRemote(onButton = { _, _, _ -> }, isForeground = { false })
                remote.start()
                Thread.sleep(1_500)
                lines += "remote=" + (remote.problem?.let { "FEHLER $it" } ?: if (remote.running) "OK" else "beendet")
                remote.stop()
            }
            val player = VideoPlayer()
            lines += "video=" + if (player.gpuVideo) "gpu" else "rgb"
            lines += "engine=${player.engineVersion} ${player.error.orEmpty()}".trim()
            if (player.available) {
                val h264 = resource("blue.ts")
                val hevc10 = resource("blue10.ts")
                // Jeder Videoweg einmal: Automatisch, 10 Bit (P010) und Kompatibel (I420).
                val runs = if (player.gpuVideo) listOf("AUTO" to h264, "I420" to h264, "AUTO" to hevc10, "P010" to hevc10) else listOf("AUTO" to h264)
                ok = true
                player.tapAudio = true
                for ((mode, file) in runs) {
                    player.videoLayout = mode
                    val before = player.framesRendered.get()
                    player.play(file.absolutePath, null, 0)
                    val until = System.currentTimeMillis() + 30_000
                    while (player.framesRendered.get() <= before + 5 && System.currentTimeMillis() < until) Thread.sleep(200)
                    Thread.sleep(800)
                    val frames = player.framesRendered.get() - before
                    val info = player.streamInfo()
                    lines += "[$mode ${file.name}] frames=$frames layout=${player.layout} video=${info?.videoCodec} audio=${info?.audioCodec} error=${player.error}"
                    ok = ok && frames > 0 && player.error == null
                    if (player.gpuVideo) {
                        // Auf dem Oberflächen-Thread zeichnen, wie in der App (dort werden alte Bilder freigegeben).
                        var color: Triple<Int, Int, Int>? = null
                        var drawn: String? = null
                        javax.swing.SwingUtilities.invokeAndWait { color = player.currentFrame()?.let { f ->
                            drawn = f.layout.name
                            val info = org.jetbrains.skia.ImageInfo(64, 36, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
                            val surface = org.jetbrains.skia.Surface.makeRaster(info)
                            f.draw(surface.canvas, 64f, 36f)
                            val px = org.jetbrains.skia.Bitmap.makeFromImage(surface.makeImageSnapshot()).readPixels(info, 64 * 4, 0, 0)
                            px?.let { val at = (18 * 64 + 32) * 4; Triple(it[at].toInt() and 0xFF, it[at + 1].toInt() and 0xFF, it[at + 2].toInt() and 0xFF) }
                        } }
                        val c = color
                        lines += "[$mode ${file.name}] pixel=${c?.toList()} frame=$drawn (erwartet etwa [61, 169, 252])"
                        ok = ok && c != null && kotlin.math.abs(c.first - 61) < 25 && kotlin.math.abs(c.second - 169) < 25 && kotlin.math.abs(c.third - 252) < 25
                    }
                    player.stop()
                }
                // Ton kam an (440-Hz-Ton der Testvideos geht durch die Auswertung für das Ausrichten).
                lines += "audio=%.1f s".format(java.util.Locale.ROOT, player.speech.seconds())
                ok = ok && player.speech.seconds() > 0.5
                player.release()
                h264.delete()
                hevc10.delete()
            }
        } catch (t: Throwable) {
            lines += "error=$t"
        }
        lines += "result=" + if (ok) "OK" else "FEHLER"
        val text = lines.joinToString("\n")
        if (output != null) File(output).writeText(text) else println(text)
        exitProcess(if (ok) 0 else 1)
    }

    private fun resource(name: String): File {
        val file = File.createTempFile("dna-tv-", "-$name")
        SelfTest::class.java.getResourceAsStream("/selftest/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}

package de.ayutv.data.translate

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Lädt Modelle so, dass sie neben einem 4K-Film auf einem Fire TV Stick Platz haben.
 *
 * Eine ONNX-Datei wird beim Laden ganz eingelesen und dabei mehrfach kopiert: Ein 115-MB-Übersetzer
 * braucht kurzzeitig um die 500 MB. Deshalb einmalig (wenn genug Speicher frei ist, also nicht während
 * eines Films) ins ORT-Format umwandeln. Danach wird die Datei nur noch eingeblendet (mmap): Die Gewichte
 * bleiben in der Datei, Android lädt nur, was gerechnet wird, und kann es jederzeit wieder freigeben.
 */
internal object OrtLoader {
    /** Umgewandelte Datei; der Name enthält die ONNX-Runtime-Version (das Format ändert sich mit ihr). */
    fun prepared(env: OrtEnvironment, onnx: File): File =
        File(onnx.parentFile, onnx.nameWithoutExtension + "." + env.version.replace('.', '_') + ".ort")

    fun isPrepared(env: OrtEnvironment, onnx: File): Boolean = prepared(env, onnx).length() > 0

    /** Irgendeine umgewandelte Datei vorhanden (ohne die Runtime zu laden, z. B. für die Einstellungen). */
    fun hasPrepared(onnx: File): Boolean = onnx.parentFile?.listFiles()
        ?.any { it.name.startsWith(onnx.nameWithoutExtension + ".") && it.name.endsWith(".ort") && it.length() > 0 } == true

    /** Einmalige Umwandlung. Braucht etwa das Dreifache der Dateigröße an freiem Speicher. */
    fun prepare(env: OrtEnvironment, onnx: File, configure: OrtSession.SessionOptions.() -> Unit) {
        val target = prepared(env, onnx)
        val part = File(target.path + ".part")
        OrtSession.SessionOptions().use { options ->
            options.configure()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            options.setOptimizedModelFilePath(part.path)
            options.addConfigEntry("session.save_model_format", "ORT")
            env.createSession(onnx.path, options).close()
        }
        if (part.length() == 0L || !part.renameTo(target)) throw IOException("Modell konnte nicht vorbereitet werden")
        // Umwandlungen für frühere Runtime-Versionen sind nutzlos geworden.
        onnx.parentFile?.listFiles()?.filter { it.name.startsWith(onnx.nameWithoutExtension + ".") && it.name.endsWith(".ort") && it != target }
            ?.forEach { it.delete() }
    }

    /** Sitzung direkt auf der eingeblendeten Datei. Der Puffer muss so lange leben wie die Sitzung. */
    fun open(env: OrtEnvironment, onnx: File, options: OrtSession.SessionOptions): Pair<OrtSession, MappedByteBuffer> {
        val buffer = RandomAccessFile(prepared(env, onnx), "r").use { file ->
            file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        options.addConfigEntry("session.load_model_format", "ORT")
        options.addConfigEntry("session.use_ort_model_bytes_directly", "1")
        options.addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
        // Umgepackte Gewichte wären eine Kopie im Arbeitsspeicher: genau das soll hier wegfallen.
        options.addConfigEntry("session.disable_prepacking", "1")
        return env.createSession(buffer, options) to buffer
    }
}

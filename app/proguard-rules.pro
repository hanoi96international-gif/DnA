# Optimieren und Unbenutztes entfernen, aber nichts umbenennen: Fehlerberichte und Abstürze bleiben lesbar
# (Klassen, Methoden, Zeilen), und alles, was per JNI oder über Namen gefunden wird, behält seinen Namen.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# DNA Engine: eigene Bild- und Ton-Decoder (JNI).
-keepclasseswithmembers class de.ayutv.player.dna.engine.** { native <methods>; }

# Room, Media3, OkHttp und kotlinx.serialization bringen eigene Regeln mit.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# ONNX Runtime (Übersetzung und Untertitel-Erkennung auf dem Gerät): Die native Bibliothek sucht ihre Java-Klassen
# per JNI über den Namen (ai/onnxruntime/TensorInfo …). Ohne diese Regel benennt R8 sie um, und der Prozess bricht mit
# „JNI DETECTED ERROR … java_class == null“ ab (Fehlerbericht 01.11.2026, Prozess de.ayutv:mt, Faden dna-ocr).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Umverpacken für den Fernseher (libdnaremux): Der native Code ruft Lesen/Schreiben per JNI über den Namen auf.
-keep class de.ayutv.data.net.Remux { native <methods>; }
-keep interface de.ayutv.data.net.Remux$Source { *; }
-keep interface de.ayutv.data.net.Remux$Sink { *; }
-keepclassmembers class * implements de.ayutv.data.net.Remux$Source { int read(byte[], int); long seek(long, int); }
-keepclassmembers class * implements de.ayutv.data.net.Remux$Sink { boolean write(byte[], int); void cue(int, double, double, byte[]); }


# DNA Engine: eigener Ton-Decoder (JNI über Klassen- und Methodennamen).
-keep class de.ayutv.player.dna.engine.NativeAudio { native <methods>; }

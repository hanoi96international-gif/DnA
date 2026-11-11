package de.ayutv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Was das Gerät verkraftet: Fire TV Sticks mit 32-Bit-Prozessor und 1,5–2 GB RAM sind knapp. */
object DeviceInfo {
    /** Gesamter Arbeitsspeicher in MB. */
    fun totalMemoryMb(context: Context): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem / 1_000_000
    }.getOrDefault(0L)

    /**
     * Schwaches Gerät: nur 32 Bit oder unter 2,5 GB RAM. Dort laufen große Modelle (Texterkennung,
     * Übersetzung) neben 4K nicht zuverlässig; geladene Untertitel (automatisch ausgerichtet) gehen vor.
     */
    fun isWeak(context: Context): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.isEmpty() || totalMemoryMb(context) in 1 until 2_500

    /**
     * Handy oder Tablet (Touchscreen, kein Fernseher): Der Bildschirm gehört dem Gerät selbst (kein HDMI), die
     * Anpassungen für Fernseher (Bildwiederholrate umschalten, Auflösung des Fernsehers) gelten hier nicht.
     */
    fun isPhone(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) &&
            !de.ayutv.ui.apps.AppLauncher.isTv(context)

    /**
     * Wie viel Wiedergabepuffer (Bytes) dieses Gerät verträgt: höchstens ein Viertel des Speichers, den Android der
     * App erlaubt. Handys mit 8 GB RAM dürfen so deutlich mehr puffern als ein Fire TV Stick mit 1,5 GB.
     */
    fun bufferBudgetBytes(): Long = Runtime.getRuntime().maxMemory() / 4

    /**
     * Kann das Gerät 4K (HEVC 3840×2160) in Hardware dekodieren? Handys wie das Xiaomi 11T und Fire TV Stick 4K ja,
     * ältere Sticks mit 1080p nicht. Bestimmt die Voreinstellung „4K, wenn vorhanden“.
     */
    val decodes4k: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") &&
                    info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/hevc").videoCapabilities?.isSizeSupported(3840, 2160) == true
            }
        }.getOrDefault(false)
    }
}

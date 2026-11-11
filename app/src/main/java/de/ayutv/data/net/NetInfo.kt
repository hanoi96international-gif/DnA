package de.ayutv.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Verbindung des Geräts für den Kurzbericht: LAN oder WLAN mit Band, Verbindungsgeschwindigkeit und Signal (ohne Netzname). */
object NetInfo {
    data class Sample(val label: String, val rssi: Int)

    @Suppress("DEPRECATION")
    fun sample(context: Context): Sample = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return Sample("kein Netz", 0)
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Sample("LAN", 0)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
                val band = when (info?.frequency ?: 0) {
                    in 2400..2500 -> "2,4 GHz"
                    in 4900..5900 -> "5 GHz"
                    in 5925..7125 -> "6 GHz"
                    else -> ""
                }
                val speed = info?.linkSpeed?.takeIf { it > 0 }?.let { "$it Mbit/s" }.orEmpty()
                val rssi = info?.rssi?.takeIf { it in -120..-1 } ?: 0
                Sample(listOf("WLAN", band, speed).filter { it.isNotEmpty() }.joinToString(" "), rssi)
            }
            else -> Sample("anderes Netz", 0)
        }
    }.getOrElse { Sample("unbekannt", 0) }
}

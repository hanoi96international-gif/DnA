package de.ayutv.desktop.data

import de.ayutv.data.net.AppProxy
import de.ayutv.data.net.ProxyConfig
import de.ayutv.data.net.ProxyMode
import de.ayutv.data.net.ProxyType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Netz für die ganze App: ein gemeinsamer OkHttp-Client mit dem Proxy der Fire-TV-App ([AppProxy]: alle Verbindungen
 * oder nur die ausgewählter Playlists, HTTP oder SOCKS5, Heimnetz nie über den Proxy). Die DNA Engine nimmt
 * denselben Client, Streams laufen also denselben Weg.
 */
object Network {
    init {
        // SOCKS5 mit Anmeldung: Java holt die Zugangsdaten über den systemweiten Authenticator.
        java.net.Authenticator.setDefault(AppProxy.socksAuthenticator)
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxySelector(AppProxy.selector)
        .proxyAuthenticator(AppProxy.authenticator)
        .addNetworkInterceptor(AppProxy.interceptor)
        // Verschlüsselt, wo es geht: Namensauflösung über HTTPS, bekannte Dienste nie per http://.
        .dns(de.ayutv.data.net.SecureDns)
        .addInterceptor(de.ayutv.data.net.HttpsUpgrade)
        .build()

    /** Proxy-Einstellungen anwenden; bei „Nur ausgewählte Playlists“ deren Server (Playlist, TV-Guide, Streams). */
    fun apply(store: Store) {
        de.ayutv.data.net.SecureDns.enabled = store.user.value.secureDns
        val config = store.user.value.proxy.config()
        val hosts = if (config.active && config.mode == ProxyMode.SELECTED) buildSet {
            for (p in store.playlists.value.filter { it.id in config.playlists }) {
                AppProxy.hostOf(p.url)?.let(::add)
                AppProxy.hostOf(p.epgUrl)?.let(::add)
                store.content.value[p.id]?.let { c ->
                    c.channels.mapNotNullTo(this) { AppProxy.hostOf(it.url) }
                    c.movies.mapNotNullTo(this) { AppProxy.hostOf(it.url) }
                }
            }
        } else emptySet()
        AppProxy.configure(config, hosts)
        // Offene Verbindungen nicht weiterverwenden: Sie liefen über den bisherigen Weg.
        http.connectionPool.evictAll()
    }
}

fun ProxySettings.config() = ProxyConfig(
    mode = runCatching { ProxyMode.valueOf(mode) }.getOrDefault(ProxyMode.OFF),
    type = runCatching { ProxyType.valueOf(type) }.getOrDefault(ProxyType.HTTP),
    host = host,
    port = port,
    user = user,
    password = password,
    playlists = playlists,
)

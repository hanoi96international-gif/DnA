package de.ayutv.data.setup

import android.util.Log
import de.ayutv.AppContainer
import de.ayutv.data.AppSettings
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.repo.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Einrichtung per Handy: Der Fire TV öffnet im Heimnetz eine kleine Webseite. Dort fügt man
 * API-Schlüssel und Playlists bequem per Kopieren & Einfügen ein, statt sie mit der Fernbedienung
 * zu tippen. Läuft nur, solange die Einstellungen offen sind, und nur mit der angezeigten PIN.
 */
class PhoneSetupServer(private val container: AppContainer) {
    data class State(val url: String? = null, val lastSaved: String? = null, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    @Volatile
    private var pin = newPin()
    private val failures = AtomicInteger()
    private val clients = Semaphore(MAX_CLIENTS)

    private fun newPin() = (100_000 + SecureRandom().nextInt(900_000)).toString()

    private fun rotatePin() {
        pin = newPin()
        failures.set(0)
        _state.value = _state.value.copy(url = _state.value.url?.substringBeforeLast('/')?.let { "$it/$pin" })
    }

    fun start() {
        if (scope != null) return
        // Ein fehlerhafter oder abgebrochener Browser-Aufruf darf nie die App beenden.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Setup-Client", e) })
        scope = newScope
        newScope.launch {
            val ip = localAddress()
            // Nur im Heimnetz lauschen (nicht auf allen Schnittstellen).
            val bind = ip?.let { InetAddress.getByName(it) }
            val server = runCatching { ServerSocket(PORT, 8, bind) }.recoverCatching { ServerSocket(0, 8, bind) }.getOrElse {
                _state.value = State(error = "Einrichtung per Handy nicht möglich: ${it.message}")
                return@launch
            }
            socket = server
            _state.value = State(url = ip?.let { "http://$it:${server.localPort}/$pin" }, error = if (ip == null) "Kein WLAN/LAN gefunden" else null)
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                if (!clients.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                launch {
                    try {
                        client.use { handle(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Anfrage verworfen", e)
                    } finally {
                        clients.release()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        var contentType = ""
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
            if (header.startsWith("Content-Type:", ignoreCase = true)) contentType = header.substringAfter(':').trim()
        }
        val route = path.substringBefore('?').trimEnd('/')
        if (route != "/$pin" && route != "/$pin/backup" && route != "/$pin/restore" && route != "/$pin/report" && route != "/$pin/report.txt") {
            // Falsche PIN: bremsen, damit sie sich nicht durchprobieren lässt; nach vielen Fehlversuchen neue PIN.
            delay(1_000)
            if (failures.incrementAndGet() > MAX_FAILURES) rotatePin()
            respond(client, 404, "<h1>Falsche Adresse</h1><p>Bitte die Adresse genau so eingeben, wie sie auf dem Fernseher steht.</p>")
            return
        }
        if (route == "/$pin/report.txt") {
            val bytes = de.ayutv.data.Diagnostics.build(container.context, container).toByteArray(Charsets.UTF_8)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.GERMANY).format(java.util.Date())
            respondBytes(client, "text/plain; charset=utf-8", bytes, "Content-Disposition: attachment; filename=\"dna-tv-fehlerbericht-$stamp.txt\"\r\n")
            return
        }
        if (route == "/$pin/report") {
            val short = de.ayutv.data.Diagnostics.build(container.context, container, short = true)
            respond(
                client, 200,
                "<h1>Kurzbericht</h1><p>Fehlende TV-Guide-Sender, Ruckler und Neuverbindungen mit Grund, Datenrate, WLAN und Proxy der " +
                    "letzten 48 Stunden. Ohne Passwörter und Zugangsdaten. Kopieren und in den Chat mit Claude einfügen.</p>" +
                    "<p><button id=\"c\" type=\"button\">Kurzbericht kopieren</button></p>" +
                    "<textarea id=\"k\" readonly rows=24>${esc(short)}</textarea>" +
                    "<p>Vollständiger Fehlerbericht mit Systemprotokoll: <a href=\"/$pin/report.txt\">als Datei laden</a></p>" +
                    "<script>$COPY_SCRIPT</script>",
            )
            return
        }
        if (route == "/$pin/backup") {
            // Mit Passwort (Formular, POST) verschlüsselt, sonst lesbar.
            val password = if (method == "POST") {
                parseForm(ByteArray(length).also { readFully(input, it) }.toString(Charsets.UTF_8))["backup_password"]
            } else null
            val bytes = Backup(container).create(password?.takeIf { it.isNotEmpty() }).toByteArray(Charsets.UTF_8)
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date())
            respondBytes(client, "application/json; charset=utf-8", bytes, "Content-Disposition: attachment; filename=\"dna-tv-sicherung-$day.json\"\r\n")
            return
        }
        if (route == "/$pin/restore" && method == "POST") {
            val raw = ByteArray(length).also { readFully(input, it) }
            val text = multipartFile(raw, contentType) ?: raw.toString(Charsets.UTF_8)
            val password = multipartField(raw, contentType, "backup_password")
            val line = runCatching { Backup(container).restore(text, password) }
                .fold({ KeyCheck.Line(true, it) }, { KeyCheck.Line(false, it.message ?: "Sicherung konnte nicht eingespielt werden") })
            _state.value = _state.value.copy(lastSaved = (if (line.ok) "✓ " else "✗ ") + line.text)
            respond(client, 200, page(container.settings.current(), listOf(line)) + guideReport())
            return
        }
        if (method == "POST") {
            val body = ByteArray(length).also { readFully(input, it) }.toString(Charsets.UTF_8)
            val form = parseForm(body)
            val report = save(form)
            respond(client, 200, page(container.settings.current(), report) + guideReport())
        } else {
            respond(client, 200, page(container.settings.current(), null) + guideReport())
        }
    }

    /** Übernimmt nur ausgefüllte Felder: Leere Felder lassen gespeicherte Werte unverändert. */
    private suspend fun save(form: Map<String, String>): List<KeyCheck.Line> {
        fun field(name: String) = form[name]?.trim().orEmpty()
        container.settings.update { current ->
            var next = current
            field("os_key").takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesKey = it) }
            field("os_user").takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesUser = it) }
            form["os_password"]?.takeIf { it.isNotEmpty() }?.let { next = next.copy(openSubtitlesPassword = it) }
            // DeepL: mit Schlüssel gleich als Übersetzer einstellen.
            field("deepl_key").takeIf { it.isNotEmpty() }?.let {
                next = next.copy(deeplKey = it, translationProvider = de.ayutv.data.TranslationProvider.DEEPL)
            }
            // Proxy: Adresse und Port zusammen, Zugangsdaten nur wenn ausgefüllt.
            field("proxy_host").takeIf { it.isNotEmpty() }?.let { hostPort ->
                val host = hostPort.substringBeforeLast(':').removePrefix("http://").removePrefix("socks5://").trim()
                val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: field("proxy_port").toIntOrNull() ?: next.proxyPort
                next = next.copy(
                    proxyHost = host,
                    proxyPort = port,
                    proxyType = if (form["proxy_type"] == "socks5") de.ayutv.data.net.ProxyType.SOCKS5 else de.ayutv.data.net.ProxyType.HTTP,
                    proxyMode = if (next.proxyMode == de.ayutv.data.net.ProxyMode.OFF) de.ayutv.data.net.ProxyMode.ALL else next.proxyMode,
                )
            }
            field("proxy_user").takeIf { it.isNotEmpty() }?.let { next = next.copy(proxyUser = it) }
            form["proxy_password"]?.takeIf { it.isNotEmpty() }?.let { next = next.copy(proxyPassword = it) }
            next
        }
        val lines = mutableListOf<KeyCheck.Line>()
        val m3u = field("m3u_url")
        if (m3u.isNotEmpty()) {
            val entity = PlaylistEntity(name = field("m3u_name").ifEmpty { "Playlist" }, type = PlaylistType.M3U, url = m3u, epgUrl = field("epg_url").ifEmpty { null })
            val known = container.playlists.findSame(entity)
            val id = container.playlists.add(entity)
            container.scope.launch { container.playlists.sync(id) }
            lines += KeyCheck.Line(true, if (known != null) "Playlist „${known.name}“ gibt es schon – aktualisiert, eigene Gruppen bleiben" else "Playlist hinzugefügt, wird jetzt geladen")
        }
        val server = field("xt_server")
        if (server.isNotEmpty()) {
            val normalized = XtreamClient.normalizeServer(server)
            val error = container.playlists.verifyXtream(normalized, field("xt_user"), field("xt_password"))
            if (error == null) {
                val entity = PlaylistEntity(
                    name = field("xt_name").ifEmpty { "Xtream" },
                    type = PlaylistType.XTREAM,
                    url = normalized,
                    username = field("xt_user"),
                    password = field("xt_password"),
                )
                val known = container.playlists.findSame(entity)
                val id = container.playlists.add(entity)
                container.scope.launch { container.playlists.sync(id) }
                lines += KeyCheck.Line(true, if (known != null) "Xtream-Zugang „${known.name}“ gibt es schon – aktualisiert, eigene Gruppen bleiben" else "Xtream-Zugang hinzugefügt, wird jetzt geladen")
            } else {
                lines += KeyCheck.Line(false, "Xtream: $error")
            }
        }
        lines += KeyCheck.run(container.http, container.settings.current())
        _state.value = _state.value.copy(lastSaved = lines.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text })
        return lines
    }

    private fun page(settings: AppSettings, report: List<KeyCheck.Line>?): String {
        fun status(set: Boolean) = if (set) "<span class=ok>gespeichert</span>" else "<span class=no>fehlt</span>"
        val result = report?.joinToString("") { "<li class=${if (it.ok) "ok" else "no"}>${if (it.ok) "✓" else "✗"} ${esc(it.text)}</li>" }
            ?.let { "<h2>Ergebnis</h2><ul>$it</ul>" }.orEmpty()
        return """
            <h1>DnA-TV einrichten</h1>
            $result
            <form method=post>
            <h2>Untertitel aus dem Internet (optional): OpenSubtitles ${status(settings.openSubtitlesApiKey.isNotBlank())}</h2>
            <p>Untertitel für fast jeden Film in fast jeder Sprache (auch automatisch übersetzte).
            Kostenloses Konto auf <a href="https://www.opensubtitles.com" target=_blank>opensubtitles.com</a> anlegen,
            dann <a href="https://www.opensubtitles.com/consumers" target=_blank>opensubtitles.com/consumers</a> →
            „New consumer“ → Name z. B. „DnA-TV“ → API-Key kopieren.</p>
            <label>API-Key<input name=os_key autocomplete=off placeholder="${if (settings.openSubtitlesApiKey.isNotBlank()) "gespeichert – zum Ändern neu einfügen" else "API-Key einfügen"}"></label>
            <label>Benutzername (empfohlen: 20 statt 5 Downloads pro Tag)<input name=os_user autocomplete=username value="${esc(settings.openSubtitlesUser)}"></label>
            <label>Passwort<input name=os_password type=password autocomplete=current-password placeholder="${if (settings.openSubtitlesPassword.isNotEmpty()) "gespeichert" else ""}"></label>

            <p>Übersetzen braucht nichts davon: DnA-TV übersetzt vorhandene Untertitel direkt auf dem Fire TV.
            OpenSubtitles liefert Untertitel für Filme, die selbst keine haben.</p>

            <h2>Übersetzen über DeepL (empfohlen für den Fire TV Stick) ${status(settings.deeplKey.isNotBlank())}</h2>
            <p>Schnell und in besserer Qualität als auf dem Gerät. Kostenloses Konto „DeepL API Free“ auf
            <a href="https://www.deepl.com/pro-api" target=_blank>deepl.com/pro-api</a> anlegen (500.000 Zeichen im Monat frei),
            dann unter „API-Schlüssel“ den Schlüssel kopieren (endet auf <b>:fx</b>). Mit dem Speichern wird DeepL als Übersetzer eingestellt.</p>
            <label>DeepL-API-Schlüssel<input name=deepl_key autocomplete=off placeholder="${if (settings.deeplKey.isNotBlank()) "gespeichert – nur zum Ändern ausfüllen" else "…:fx"}"></label>

            <h2>Proxy nur für DnA-TV (optional) ${status(settings.proxyHost.isNotBlank())}</h2>
            <p>Zugangsdaten vom Proxy-Anbieter. Mit dem Speichern läuft DnA-TV über den Proxy (einschränken auf
            einzelne Playlists: am Fernseher unter Einstellungen → Proxy).</p>
            <label>Art<select name=proxy_type><option value=http${if (settings.proxyType == de.ayutv.data.net.ProxyType.HTTP) " selected" else ""}>HTTP</option><option value=socks5${if (settings.proxyType == de.ayutv.data.net.ProxyType.SOCKS5) " selected" else ""}>SOCKS5</option></select></label>
            <label>Adresse:Port<input name=proxy_host autocomplete=off placeholder="${if (settings.proxyHost.isNotBlank()) esc(settings.proxyHost + ":" + settings.proxyPort) else "z. B. 203.0.113.5:8080"}"></label>
            <label>Benutzer<input name=proxy_user autocomplete=off value="${esc(settings.proxyUser)}"></label>
            <label>Passwort<input name=proxy_password type=password autocomplete=off placeholder="${if (settings.proxyPassword.isNotEmpty()) "gespeichert" else ""}"></label>

            <h2>Playlist hinzufügen (optional)</h2>
            <label>Name<input name=m3u_name placeholder="z. B. Mein Anbieter"></label>
            <label>M3U-Adresse<input name=m3u_url type=url placeholder="http://…/get.php?…"></label>
            <label>EPG-Adresse (optional, sonst automatisch; mehrere mit Komma)<input name=epg_url type=text inputmode=url></label>
            <p>oder Xtream Codes:</p>
            <label>Name<input name=xt_name></label>
            <label>Server<input name=xt_server placeholder="http://server:port"></label>
            <label>Benutzername<input name=xt_user autocomplete=off></label>
            <label>Passwort<input name=xt_password autocomplete=off></label>

            <button>Speichern und prüfen</button>
            </form>

            <h2>Sicherung</h2>
            <p>Playlists (mit Zugangsdaten), Favoriten, ausgeblendete Gruppen, gefolgte Vereine und alle Einstellungen
            in einer Datei. Damit ist ein neuer Fire TV in einer Minute eingerichtet. Mit Passwort wird die Datei
            verschlüsselt und enthält dann alle Zugangsdaten; ohne Passwort fehlen Passwörter und Schlüssel (Playlist-Passwörter,
            Proxy, DeepL, OpenSubtitles). Die Kindersicherung wird nicht mitgesichert.</p>
            <form method=post action="/$pin/backup">
            <label>Passwort für die Sicherung (empfohlen)<input name=backup_password type=password autocomplete=new-password></label>
            <button>⬇ Sicherung herunterladen</button>
            </form>
            <form method=post action="/$pin/restore" enctype="multipart/form-data">
            <label>Sicherung einspielen<input name=backup type=file accept=".json,application/json"></label>
            <label>Passwort (falls vergeben)<input name=backup_password type=password autocomplete=current-password></label>
            <button>Sicherung einspielen</button>
            </form>
        """.trimIndent()
    }

    /** Welche Sender kein Programm haben, je Playlist und Gruppe: zum Kopieren und Weitergeben an den Support. */
    private suspend fun guideReport(): String {
        val parts = container.db.playlists().all().filter { it.enabled }.mapNotNull { playlist ->
            val status = container.playlists.epgStatus(playlist.id) ?: return@mapNotNull null
            val sources = status.sources.joinToString(", ") { "${it.first}: ${it.second}" }
            val text = buildString {
                append("TV-Guide ${playlist.name}: ${status.withGuide} von ${status.total} Sendern ($sources)\n")
                append("Ohne Programm (${status.total - status.withGuide}):\n")
                for ((group, entries) in status.missingInGroups.groupBy({ it.first }, { it.second })) {
                    append("\n[${group.ifEmpty { "Ohne Gruppe" }}] (${entries.size})\n")
                    entries.forEach { append(it).append('\n') }
                }
            }
            "<h3>${esc(playlist.name)}: ${status.withGuide} von ${status.total} Sendern mit Programm</h3>" +
                "<textarea readonly rows=14>${esc(text)}</textarea>"
        }
        if (parts.isEmpty()) return ""
        return "<h2>TV-Guide: Sender ohne Programm</h2><p>Alles markieren, kopieren und an den Support schicken, " +
            "dann lässt sich die Zuordnung gezielt verbessern. Ausgeblendete Gruppen zählen nicht mit.</p>" + parts.joinToString("")
    }

    private fun respond(client: Socket, code: Int, body: String) {
        val html = """<!doctype html><html lang=de><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1"><title>DnA-TV einrichten</title>
            <style>body{font-family:system-ui,sans-serif;background:#0d0f14;color:#e8eaf0;margin:0 auto;max-width:640px;padding:16px}
            h1{color:#8fb4ff}h2{font-size:1.05em;margin-top:28px}p{color:#aab0bd;line-height:1.45}a{color:#8fb4ff}
            label{display:block;margin:10px 0;color:#c8ccd6}input{display:block;width:100%;box-sizing:border-box;margin-top:4px;padding:12px;
            border-radius:8px;border:1px solid #333a48;background:#171a22;color:#fff;font-size:16px}
            textarea{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #333a48;background:#171a22;color:#fff;font-size:13px}
            button{margin:24px 0 40px;width:100%;padding:14px;border:0;border-radius:10px;background:#4f7cff;color:#fff;font-size:17px}
            .ok{color:#5fd38d}.no{color:#ff8a7a}ul{padding-left:18px;line-height:1.6}</style></head><body>$body</body></html>"""
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Not Found"
        runCatching {
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" +
                        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src '$COPY_SCRIPT_HASH'; form-action 'self'\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(bytes)
                flush()
            }
        }.onFailure { Log.w(TAG, "Antwort fehlgeschlagen", it) }
    }

    private fun respondBytes(client: Socket, contentType: String, bytes: ByteArray, extraHeaders: String = "") {
        runCatching {
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n$extraHeaders" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                write(bytes)
                flush()
            }
        }.onFailure { Log.w(TAG, "Antwort fehlgeschlagen", it) }
    }

    /** Inhalt der ersten hochgeladenen Datei aus einem multipart/form-data-Formular, sonst null. */
    private fun multipartFile(body: ByteArray, contentType: String): String? {
        val boundary = contentType.substringAfter("boundary=", "").trim('"').takeIf { it.isNotEmpty() } ?: return null
        // ISO-8859-1 bildet jedes Byte auf ein Zeichen ab: so bleiben die UTF-8-Bytes unverändert.
        val text = body.toString(Charsets.ISO_8859_1)
        val part = text.split("--$boundary").firstOrNull { "filename=" in it.substringBefore("\r\n\r\n") } ?: return null
        val content = part.substringAfter("\r\n\r\n", "").removeSuffix("\r\n")
        return content.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /** Textfeld [name] aus einem multipart-Formular. */
    private fun multipartField(body: ByteArray, contentType: String, name: String): String? {
        val boundary = contentType.substringAfter("boundary=", "").trim('"').takeIf { it.isNotEmpty() } ?: return null
        val text = body.toString(Charsets.ISO_8859_1)
        val part = text.split("--$boundary").firstOrNull {
            val head = it.substringBefore("\r\n\r\n")
            "name=\"$name\"" in head && "filename=" !in head
        } ?: return null
        return part.substringAfter("\r\n\r\n", "").removeSuffix("\r\n")
            .toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length > 8192) throw IOException("Zeile zu lang")
            out.append(c.toChar())
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) break
            read += n
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val key = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull() ?: return@mapNotNull null
        val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private companion object {
        /** Einzige erlaubte Skriptzeile (per Hash in der Sicherheitsrichtlinie freigegeben): Kurzbericht kopieren. */
        private const val COPY_SCRIPT = "document.getElementById('c').onclick=function(){var t=document.getElementById('k');t.readOnly=false;t.focus();t.select();t.setSelectionRange(0,1e7);var ok=false;try{ok=document.execCommand('copy')}catch(e){}t.readOnly=true;t.blur();this.textContent=ok?'Kopiert ✓ – jetzt in den Chat einfügen':'Bitte ins Feld tippen, lange drücken → Alles auswählen → Kopieren'}"
        private const val COPY_SCRIPT_HASH = "sha256-518GxTZCXPKttDtkaJTVhY38O8/NwkWTCEnD256yiTo="
        const val TAG = "PhoneSetup"
        const val PORT = 8095
        /** Groß genug für eine Sicherung mit vielen Favoriten. */
        const val MAX_BODY = 4 * 1024 * 1024
        const val MAX_CLIENTS = 4
        const val MAX_FAILURES = 20
    }
}

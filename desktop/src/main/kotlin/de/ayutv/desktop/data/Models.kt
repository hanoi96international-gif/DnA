package de.ayutv.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Art einer Playlist: M3U-Adresse oder Xtream-Codes-Zugang. */
enum class PlaylistType { M3U, XTREAM }

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val enabled: Boolean = true,
    val lastSync: Long = 0,
    /** Zeitzone des Xtream-Servers (für Archiv-Adressen). */
    val timezone: String? = null,
)

/**
 * Sender. Die Schlüssel sind dieselben wie in der Fire-TV-App („<Playlist>:c:<Stream>“ bzw. bei M3U
 * „<Playlist>:c:<Kennung oder Name>:<Adresse>“), damit Favoriten und Gruppen aus einer Sicherung passen.
 */
@Serializable
data class Channel(
    val key: String,
    val playlistId: Long,
    val name: String,
    val group: String,
    val logo: String? = null,
    val url: String,
    val epgId: String? = null,
    val number: Int = 0,
    val catchupDays: Int = 0,
    /** Archiv-Art aus der M3U (catchup="append|shift|flussonic|…") und Vorlage (catchup-source). */
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

@Serializable
data class Movie(
    val key: String,
    val playlistId: Long,
    val name: String,
    val category: String,
    val poster: String? = null,
    val rating: String? = null,
    val url: String,
    val added: Long = 0,
)

@Serializable
data class Series(
    val key: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val category: String,
    val cover: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val added: Long = 0,
)

@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    /** Wiederholung (1), live (2), Erstausstrahlung (4) laut TV-Guide. */
    val flags: Int = 0,
)

/** Titel für TV-Guide, Senderliste und Vorschau; bei Sport mit der Partie vorn (wie auf dem Fire TV). */
fun Programme.display(): String = de.ayutv.data.parse.ProgrammeText.display(title, subtitle, description, category, start, stop)

/** Proxy: [mode] OFF/ALL/SELECTED, [type] HTTP/SOCKS5, [playlists] = Playlists bei SELECTED. */
@Serializable
data class ProxySettings(
    val mode: String = "OFF",
    val type: String = "HTTP",
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    val playlists: Set<Long> = emptySet(),
)

@Serializable
data class TimingMark(val ratio: Double, val offsetMs: Long)

@Serializable
data class IntroMark(val startMs: Long, val lengthMs: Long)

/** Erinnerung an eine Sendung: kurz vor [start] fragt DnA-TV, ob es umschalten soll. */
@Serializable
data class Reminder(val channelKey: String, val channelName: String, val title: String, val start: Long, val team: Boolean = false)

/** Aufnahme: geplant, läuft, fertig oder fehlgeschlagen. [file] = Ziel (TS). */
@Serializable
data class Recording(
    val id: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val file: String,
    val state: String = PLANNED,
) {
    companion object {
        const val PLANNED = "PLANNED"
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }
}

/** Profil: eigene Favoriten, Gruppen, Verlauf, Erinnerungen. [pin] schützt das Profil (leer = offen). */
@Serializable
data class Profile(val id: Long, val name: String, val pin: String = "")

/** Eigene Sendergruppe (Sender aus allen Playlists, in eigener Reihenfolge). */
@Serializable
data class CustomGroup(val id: Long, val name: String, val keys: List<String> = emptyList())

/** Was der Nutzer festlegt: Favoriten, eigene Gruppen, Reihenfolgen, ausgeblendete Gruppen, Einstellungen. */
@Serializable
data class UserData(
    val favorites: List<String> = emptyList(),
    /** Eigene Sendernummern: Senderschlüssel → Nummer (jede Nummer nur einmal). */
    val channelNumbers: Map<String, Int> = emptyMap(),
    val customGroups: List<CustomGroup> = emptyList(),
    /** Eigene Reihenfolge in Gruppen des Anbieters: Gruppenname → Senderschlüssel. */
    val groupOrder: Map<String, List<String>> = emptyMap(),
    /** Ausgeblendete Gruppen des Anbieters (Live), je Playlist: "<Playlist>|<Gruppe>". */
    val hiddenGroups: Set<String> = emptySet(),
    /** Zuletzt geschaute Sender (neuester zuerst). */
    val recent: List<String> = emptyList(),
    /** Wiedergabestand von Filmen und Folgen: Schlüssel → Position (ms). */
    val positions: Map<String, Long> = emptyMap(),
    /** Länge von Filmen und Folgen (ms), für „gesehen“ und Fortschrittsbalken. */
    val durations: Map<String, Long> = emptyMap(),
    val openSubtitlesKey: String = "",
    /** OpenSubtitles-Konto (z. B. VIP): höheres Tageskontingent. Kommt auch mit der Sicherung vom Fire TV. */
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val deeplKey: String = "",
    val subtitleLanguage: String = "de",
    /** Handy als Fernbedienung eingeschaltet, und die PIN in der Adresse. */
    val phoneRemote: Boolean = false,
    /** Proxy wie in der Fire-TV-App (aus der Sicherung übernehmbar). */
    val proxy: ProxySettings = ProxySettings(),
    /** Am Ton gefundene Ausrichtung der Untertitel je Film/Folge (Schlüssel → Faktor und Versatz). */
    val subtitleTimings: Map<String, TimingMark> = emptyMap(),
    /** Gelerntes Intro je Serie (Serienschlüssel → Beginn und Länge in ms). */
    val intros: Map<String, IntroMark> = emptyMap(),
    val remotePin: String = "",
    /** Ausgeblendete Kategorien bei Filmen und Serien, je Playlist: "<Playlist>|<Kategorie>". */
    val hiddenMovieGroups: Set<String> = emptySet(),
    val hiddenSeriesGroups: Set<String> = emptySet(),
    /** Doppelte Sender („Sendername“, „Sendername“, „Sendername“) einmal zeigen, in der besten Fassung. */
    val mergeVariants: Boolean = true,
    /** Fehlendes Programm aus dem freien EPG (epgshare01, DE/AT/CH) ergänzen. */
    val publicEpg: Boolean = true,
    /** Länderauswahl nach dem ersten Laden schon gezeigt. */
    val countriesAsked: Boolean = false,
    /** Fenster zuletzt im Vollbild (beim nächsten Start wieder). */
    val fullscreen: Boolean = false,
    /** Im Bild schalten ↑/↓ um (wie auf dem Fire TV); aus = ↑/↓ öffnen die Senderliste. */
    val zapWithUpDown: Boolean = true,
    /** Aufnahmen (geplant, laufend, fertig). */
    val recordings: List<Recording> = emptyList(),
    /** Kindersicherung: PIN und gesperrte Gruppen ("LIVE|<Playlist>|<Gruppe>", ebenso VOD und SERIES). */
    val parentalPin: String = "",
    val lockedGroups: Set<String> = emptySet(),
    /** Mit der PIN entsperrt (nur bis zum Beenden, wird nicht gespeichert). */
    @Transient val parentalUnlocked: Boolean = false,
    /** Wann zuletzt geschaut (Sender, Filme, Folgen): für den Abgleich mit dem Fire TV. */
    val watchedAt: Map<String, Long> = emptyMap(),
    /** Fire TV für den Abgleich: Adresse (http://…:8096) und PIN; automatisch alle 30 Minuten. */
    val syncAddress: String = "",
    val syncPin: String = "",
    /** Zuletzt benutzter Fernseher für „Auf Fernseher abspielen“ („dlna:<IP>|<Name>“ oder „firetv“). */
    val lastCastTv: String = "",
    val autoSync: Boolean = false,
    /** Erinnerungen an Sendungen (aus TV-Guide und Sport) und gefolgte Vereine. */
    val reminders: List<Reminder> = emptyList(),
    val followedTeams: Set<String> = emptySet(),
    /** HDR-Anpassung: AUTO (HEVC/AV1 10 Bit in 4K = HDR10), PQ (HDR10), HLG, OFF. */
    val hdrMode: String = "AUTO",
    /** Videoweg: AUTO (NV12, bei 10 Bit P010) oder I420 (kompatibel wie bis 0.42). */
    val videoLayout: String = "AUTO",
    /** Direktausgabe (VLC zeigt das Bild selbst über die Grafikkarte): OFF, AUTO (bei 4K), ALWAYS. */
    val directMode: String = "AUTO",
    /** Mini-Vorschau des ausgewählten Senders in der Senderliste (stumm). */
    val livePreview: Boolean = true,
    /** Reiter „Apps“: Reihenfolge und ausgeblendete Apps (Kennungen). */
    val appsOrder: List<String> = emptyList(),
    val appsHidden: Set<String> = emptySet(),
    /** TV-Modus (Mini-PC am Fernseher): mit Windows starten, immer Vollbild, Mauszeiger ausblenden. */
    val tvMode: Boolean = false,
    /** Namensauflösung verschlüsselt (DNS über HTTPS), mit Rückfall auf die normale. */
    val secureDns: Boolean = true,
    /** Design (STANDARD, OLED) und Akzentfarbe (Palette.ACCENTS). */
    val uiTheme: String = "PRESTIGE",
    /** 1 = neues Standard-Design Prestige (0.47) einmal für alle eingeschaltet; danach gilt die eigene Wahl. */
    val designVersion: Int = 0,
    val accent: String = "BLAU",
    /** Pufferprofil (FAST, BALANCED, STABLE). */
    val bufferProfile: String = "BALANCED",
    /** Zuletzt den TV-Guide geladen (für das Nachladen alle 8 Stunden). */
    val guideSyncedAt: Long = 0,
    /** TV-Guide um Stunden verschieben (Anbieter mit falscher Zeitzone), −12 … +12. */
    val epgShiftHours: Int = 0,
    /** Bildwiederholrate des Bildschirms an Sender und Film anpassen (50 Hz, 24 Hz …). */
    val matchFrameRate: Boolean = false,
    /** Gemessene Bildrate je Sender/Film: Beim nächsten Mal schaltet der Bildschirm schon beim Laden um. */
    val frameRates: Map<String, Double> = emptyMap(),
    /** Wie das Bild ins Fenster passt (Original, füllen, strecken). */
    val pictureFit: PictureFit = PictureFit.FIT,
    /** Klang wie auf dem Fire TV: normal, Nachtmodus, Dialoge verstärken. */
    val audioMode: AudioMode = AudioMode.NORMAL,
    /** Laute Werbung und leise Sender auf ähnliche Lautstärke bringen. */
    val normalizeVolume: Boolean = false,
    /** Ton später (+) oder früher (−), z. B. für eine Soundbar mit Verzögerung. */
    val audioDelayMs: Long = 0,
    /** Aussehen der Untertitel. */
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.OUTLINE,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.NORMAL,
)

/** Nummer eines Senders: eigene Nummer, sonst die der Playlist, sonst die Position in der Liste ([fallback]). */
fun UserData.numberOf(channel: Channel, fallback: Int): Int =
    channelNumbers[channel.key] ?: channel.number.takeIf { it > 0 } ?: fallback

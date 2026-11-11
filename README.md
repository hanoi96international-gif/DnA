# DnA-TV

Schneller, moderner IPTV-Player für **Fire TV** und Android TV. Gebaut mit Kotlin, Jetpack Compose for TV und Media3/ExoPlayer.

DnA-TV ist ein reiner Player. Sender, Filme und Serien kommen aus deinem eigenen Zugang (M3U oder Xtream Codes).

## Was DnA-TV besser machen will

| Problem bei anderen Playern | DnA-TV |
|---|---|
| 4K ruckelt oder startet langsam | Hardware-Decoder mit Fallback, Pufferprofile (Schnell, Ausgewogen, Stabil 4K), optionaler Tunneling-Modus, Anpassung der Bildwiederholrate (50 Hz / 23,976 Hz) |
| Langsames Umschalten | Der TS-Stream startet ohne auf einen Keyframe zu warten, Verbindungen bleiben offen, das letzte Bild bleibt beim Zappen stehen. Die Umschaltzeit wird gemessen und im Stream-Info angezeigt |
| Lange Gruppenlisten mit Ländern, die man nie schaut | Gruppen pro Playlist ausblenden. Die Auswahl bleibt auch nach dem Aktualisieren erhalten |
| Große Playlists machen die App träge | Streaming-Parser für M3U, XMLTV und Xtream. Nichts wird komplett in den Speicher geladen. Der Import läuft in einer Transaktion im Hintergrund |
| Premium-Abo für Grundfunktionen | Alles ist frei: mehrere Playlists, Favoriten, EPG, Suche, Weiter schauen |
| Suche nur nach Sendernamen | Eine Suche über Sender, laufende und kommende Sendungen, Filme und Serien |
| Keine Empfehlungen | Die Startseite zeigt „Weiter schauen“, „Zuletzt gesehen“, Favoriten mit aktuellem Programm und „Mehr aus …“ für deine meistgeschauten Gruppen. Alles wird lokal berechnet, ohne Cloud |

## Funktionen (Version 0.61)

- **Quellen:** M3U/M3U8 (URL, gzip), XMLTV-EPG (auch `.xml.gz`), Xtream Codes API (Live, Filme, Serien, EPG)
- **TV-Guide automatisch:** Ohne Eintrag kommt das EPG vom Anbieter (Xtream `xmltv.php`, bei einer M3U die Adressen aus `url-tvg`). Eigene EPG-Adressen (mehrere mit Komma) gehen vor; jede weitere Quelle füllt nur Sender, für die noch nichts da ist. Was dann noch fehlt, ergänzt DnA-TV aus freien EPG-Quellen (abschaltbar unter *Einstellungen → Anzeige*, höchstens einmal am Tag geladen, nur für Länder, in denen noch Sender ohne Programm sind). Sendernamen werden auch mit Länder-Vorsatz und Formatzusatz (SD, HD, Full HD, 4K, UHD, RAW) erkannt, Zeitversatz-Sender zeigen das Programm des Hauptsenders passend verschoben. *Einstellungen → Playlists* zeigt je Quelle, wie viele Sender sie abdeckt, und welche ohne Programm sind.
- **Gruppen ein- und ausblenden:** pro Playlist, getrennt für Live TV, Filme und Serien (*Einstellungen → Playlist → Gruppen*), mit Filter und „Alle ein/aus“. Schneller geht es mit langem Druck auf OK auf einer Gruppe in der Liste. Ausgeblendete Gruppen verschwinden überall: in Listen, TV-Guide, Suche, Startseite und beim Zappen. Sie bleiben auch nach dem Aktualisieren ausgeblendet.
- **Live TV:** Gruppen, Senderliste mit aktuellem Programm und Fortschritt, Detailspalte mit den nächsten Sendungen, Favoriten (OK lange drücken)
- **TV-Guide:** EPG-Raster pro Gruppe, 2-Stunden-Fenster, das sich mit links/rechts verschieben lässt, 7 Tage im Voraus, mit Sendungsbildern, „🔴 LIVE“-Kennzeichen und Spielpaarungen bei Sportübertragungen
- **Sport:** alle Spiele und Live-Übertragungen über alle Sender, nach „Jetzt live“, „Heute“, „Morgen“ (Filter: Fußball, Meine Vereine). OK halten auf einem Spiel = Verein folgen: Vor jedem seiner Spiele kommt eine Erinnerung mit dem Sender
- **Doppelte Sender zusammengefasst:** SD-, HD-, Full-HD- und 4K-Fassung desselben Senders erscheinen einmal in der besten Fassung; fällt sie aus, schaltet der Player automatisch auf die nächste
- **Länderauswahl:** nach dem ersten Laden (und unter *Einstellungen → Playlists → Länder auswählen*) Länder mit einem Klick ein- und ausblenden, z. B. „Nur Deutschland, Österreich, Schweiz“
- **Player:** Hoch/Runter schaltet um, OK oder links öffnet die Senderliste, rechts zeigt das Stream-Info (Auflösung, fps, Codec, Decoder, verworfene Frames, Leitung, Startzeit), Zifferntasten wählen direkt einen Sender, die „Letzter Sender“-Taste bzw. die blaue Taste springt zurück, Menü-Taste setzt einen Favoriten. Erkennt 4K, HDR10, HLG und Dolby Vision
- **Untertitel (Filme & Serien), optional:** standardmäßig aus, im Film mit der Menütaste ☰ einschalten (oder in den Einstellungen „automatisch einschalten“). In **41 Sprachen**:
  1. eingebettete Spur des Films,
  2. echte Untertitel von [OpenSubtitles](https://www.opensubtitles.com) in der Wunschsprache (per Datei-Hash zeitgenau, sonst nach Titel/Jahr). Passen sie zeitlich nicht (andere Fassung, 25 statt 23,976 fps), richtet DnA-TV sie nach etwa 2 Minuten **automatisch aus** (und verfeinert laufend; Stand im Untertitel-Menü unter „Ausrichtung“): an eingebetteten Untertiteln oder, wenn der Film keine hat, **am gesprochenen Ton** (Sprechpausen gegen Untertitel-Lücken, auch bei gekürzten Fassungen). Untertitel eines anderen Films werden erkannt und ersetzt. Bei der Auswahl zählen Bildrate und Länge des Films, bei Serien Staffel und Folge,
  3. gibt es keine: **Übersetzung direkt auf dem Fire TV** (OPUS-MT, ohne Konto, ohne Zeichengrenze, nach dem ersten Laden offline) einer eingebetteten Spur oder einer geladenen Datei – vorausschauend, damit die Zeilen fertig sind, bevor sie erscheinen. Optional DeepL/LibreTranslate,
  4. eingebettete Spuren lassen sich auch **live übersetzen** (jede Zeile beim Erscheinen).
  Alles Geladene und Übersetzte wird gespeichert; mehrere Sprachen hängen gleichzeitig am Film.
- **Ton- und Untertitelmenü (☰-Taste):** Audiospuren mit Sprache und Format (Dolby Atmos, Dolby Digital Plus, DTS, 5.1), Untertitelspuren, Online-Suche. Bei Live TV zusätzlich Favorit setzen.
- **Stabilität:** Ein Watchdog erkennt eingefrorene Streams (Dauer-Puffern, stehendes Bild) und verbindet automatisch neu. Stürzt der Decoder bei aktivem Tunneling ab, wird Tunneling für diesen Stream abgeschaltet und neu gestartet. Kurzer Lese-Timeout (12 s), damit tote Verbindungen schnell auffallen. Bei Fehlern gibt es bis zu drei Wiederholungen, HLS ohne Endung wird automatisch erkannt.
- **Filme und Serien:** Kategorien, Poster-Raster, Staffeln und Episoden (Xtream), Fortsetzen an der letzten Position
- **Automatische Aktualisierung:** Playlists alle 12 Stunden, EPG alle 8 Stunden
- **Catch-up und Timeshift:** vergangene Sendungen aus dem Archiv des Anbieters (Xtream `tv_archive`, M3U `catchup`), laufende Sendung von Anfang, Live anhalten und zeitversetzt weiterschauen
- **Aufnahmen und Downloads:** Sendungen aus dem TV-Guide aufnehmen, Filme herunterladen; läuft im Hintergrund weiter, auch wenn die App zu ist
- **Erinnerungen:** an Sendungen und an Spiele gefolgter Vereine, mit Umschalten auf Knopfdruck
- **Profile und Kindersicherung:** eigene Favoriten und Verlauf je Profil, Gruppen mit PIN sperren
- **Updates in der App** und **Abgleich mit Handy und Windows** (Favoriten, Weiter schauen, eigene und ausgeblendete Gruppen): Jedes Gerät hat so eine Kopie; gehen die eigenen Gruppen auf einem verloren, bringt der nächste Abgleich sie zurück. Wer denselben Zugang noch einmal eingibt (auch per Handy), aktualisiert den vorhandenen statt einen zweiten anzulegen.
- **Konferenz und Multiview:** zweiter Sender als kleines Bild oder nebeneinander gleich groß (☰ → „Nebeneinander“), ⏩ tauscht, der Ton folgt dem Hauptbild
- **Reiter „Apps“:** Streaming- und Musik-Apps mit einem Klick: Fire TV startet die Fire-TV-App, das Handy die Handy-App (nicht installiert: Appstore), Windows die Webseite im Standard-Browser. Zurück bzw. Browser schließen führt wieder zu DnA-TV. OK halten: Reihenfolge, ausblenden
- **Eigene Sendernummern:** OK lange auf einem Sender → „Sendernummer festlegen“. Die Zifferntasten im Bild nutzen sie (Fire TV und Windows).
- **Sport immer in bester Qualität:** Aus dem Bereich Sport startet immer die beste Fassung (4K vor Full HD vor HD), auch wenn sonst Full HD bevorzugt wird.
- **Designs:** 10 Designs mit eigenen Hintergründen, Akzentfarbe frei wählbar. Hintergründe werden einmal vorberechnet und kosten beim Scrollen nichts.
- **Playlist umbenennen:** *Einstellungen → Playlists → Umbenennen* (Windows: Menü der Playlist). Der Name bleibt beim Aktualisieren erhalten.
- **Speicher aufräumen:** Der Knopf leert jetzt wirklich: Bilder-Zwischenspeicher, installierte Updates, Zeitversatz-Reste, unbenutzte Übersetzungsmodelle (7 Tage) und verdichtet die Datenbank.
- **Bild-Check:** Hinweis, wenn der Fire TV dauerhaft HDR ausgibt, der Sender aber normales Bild (SDR) sendet (blasses Bild), oder wenn ein HLG-Sender auf einem Fernseher ohne HLG läuft.
- **Mini-Vorschau:** In Live TV läuft der ausgewählte Sender nach kurzer Zeit stumm in der Detailspalte (abschaltbar unter *Einstellungen → Anzeige*)

### Eigene Wiedergabe-Engine (DNA Engine)

- **Live-TV mit eigenem Player:** liest MPEG-TS selbst, startet schnell und erkennt Lücken im Datenstrom, ohne hängen zu bleiben.
- **4K und HDR auch auf schwächeren Decodern:** zuerst der Hardware-Decoder des Geräts (mehrere Einrichtungen, die funktionierende wird gemerkt); schafft er 4K HDR10 (HEVC 10 Bit) nicht, dekodiert DnA-TV selbst (FFmpeg, alle Kerne) und zeigt HDR10 auf Bildschirmen ohne HDR passend an.
- **Halbbilder flüssig:** 1080i-Sendungen (50 Halbbilder je Sekunde) werden auf der Grafikkarte zu 50 Vollbildern umgerechnet.
- **Ton:** Dolby Digital und Dolby Digital Plus werden an Soundbar oder Receiver durchgereicht, sonst im Gerät dekodiert; hängt ein Weg, wechselt DnA-TV automatisch auf den nächsten.
- **Auf den Fernseher:** Übertragung an LG-Fernseher (webOS), DLNA-Fernseher und Fernseher mit DnA-TV; beim Umschalten prüft das Handy, ob der Fernseher folgt.
- **Untertitel automatisch passend:** Untertitel aus dem Internet werden am Ton oder an eingebetteten Spuren ausgerichtet, die Übereinstimmung wird in Prozent angezeigt; passt keine Datei, wird übersetzt.

### Weitere Verbesserungen

- **4K und Stabilität:** asynchrone Ansteuerung der Hardware-Decoder (mehr Reserven bei 4K, automatischer Rückfall bei Problemen), Speicher-Obergrenze für den Puffer (kein Absturz nach langem 4K-Schauen auf Sticks mit wenig RAM), Watchdog, Tunneling-Rückfall, Erkennung von HDR10/HLG/Dolby Vision.
- **Alle Tonformate, auch ohne AV-Receiver:** eingebaute FFmpeg-Tondecoder (in der CI aus dem Media3-Quellcode gebaut) für DTS, Dolby TrueHD, Dolby Digital (Plus), FLAC, ALAC, Opus, Vorbis, MP3. Was das Gerät selbst kann oder an den Receiver durchreicht, bleibt beim Gerät. Meldet eine Soundbar Formate, die sie nicht abspielt, schaltet DnA-TV automatisch auf Dekodierung im Gerät um (auch fest einstellbar). Die Stream-Info zeigt den aktiven Ton-Decoder.
- **Weitere Absicherungen:** Bildwiederholrate wird beim Zappen nicht mehr zurückgesetzt (kein doppeltes Schwarzbild), Hinweis bei nicht abspielbarer Tonspur statt stiller Wiedergabe, Empfehlung für den 4K-Puffer bei schwankender Leitung.
- **TV-Guide optional:** Das Programm-Raster lässt sich in den Einstellungen ausblenden. Die Programmdaten bleiben für „Jetzt läuft“, Fortschritt, Suche und Empfehlungen aktiv.
- **Weitere:** EPG-Zeitverschiebung (−12 bis +12 Std.), gemessene Bildrate für TS-Streams ohne Angabe (damit greift die 50-Hz-Anpassung auch bei Live-TV), verständliche Fehlermeldungen („Dieses Gerät kann HEVC 10 Bit in 4K nicht abspielen“), Textfelder öffnen die Tastatur erst nach OK.

## Automatisch geprüft bei jedem Build

Jeder Push läuft durch einen Rundgang auf einem Android-TV-Emulator (API 30, wie Fire OS 8), mit selbst erzeugten Testinhalten (1080p50 TS, 4K HEVC 10 Bit, HLS, MKV mit Untertiteln) und einem simulierten Xtream-Anbieter. Letzte Messwerte:

| Prüfung | Ergebnis |
|---|---|
| Umschalten (Zappen) | ca. 0,35–0,45 s |
| Start 1080p50-TS / HLS | ca. 0,45–0,9 s / ca. 0,45–0,5 s |
| Import großer Anbieter: 3.000 Sender + 34.600 EPG-Einträge (gzip) | 4,1 s |
| „Jetzt läuft“ für 3.000 Sender | 68 ms |
| Xtream: Login, falsches Passwort, Live/Filme/Serien, Episoden | ✓ |
| Film: Untertitel standardmäßig aus, einschaltbar, Spulen | ✓ |
| Release-APK (R8): Kaltstart / Abstürze | ca. 0,5 s / 0 |

Screenshots, Logs und Ruckler-Statistik liegen nach jedem Lauf im Branch `ci-results`.

## Installation auf dem Fire TV Stick

1. Auf dem Fire TV unter *Einstellungen → Mein Fire TV → Entwickleroptionen* „Apps unbekannter Herkunft“ für die App **Downloader** erlauben.
2. Die Datei `DnA-TV.apk` aus dem neuesten [Release](../../releases/latest) nehmen. Jede neue Version erscheint als eigenes Release.
3. In Downloader die APK-URL eingeben und installieren. War vorher „NovaTV“ installiert, diese alte App deinstallieren (anderer Paketname, sie wird nicht ersetzt).
   Alternativ per ADB: `adb connect <IP-des-Sticks>` und danach `adb install DnA-TV.apk`.

Für den Alltag die **Release-APK** nehmen, nicht die Debug-Variante: Compose ist im Debug-Build deutlich langsamer.

## DnA-TV für Windows

Im selben [Release](../../releases/latest) liegt die Windows-App (Windows 10/11, 64 Bit), VLC ist mit drin:

- `DnA-TV-Windows-Setup.exe` oder `DnA-TV-Windows.msi`: Installation ohne Administratorrechte, mit Startmenü-Eintrag.
- `DnA-TV-Windows-portable.zip`: entpacken und `DnA-TV.exe` starten, ohne Installation.

Einrichten: *Einstellungen → Sicherung übernehmen …* holt Playlists, Favoriten, eigene Gruppen und Reihenfolgen direkt vom Fire TV (Adresse aus „Einrichtung per Handy“). Alternativ eine Playlist von Hand anlegen.

Untertitel (Taste L oder „💬 DE laden“ im Player) kommen von OpenSubtitles oder per DeepL übersetzt und richten sich wie auf dem Fire TV nach rund 1,5 Minuten Gespräch selbst am Ton aus (auch bei anderer Bildrate); die Ausrichtung wird je Film gemerkt.

**Fire-TV-Fernbedienung am PC:** In Windows unter *Einstellungen → Bluetooth und Geräte → Gerät hinzufügen* koppeln (auf der Fernbedienung Home 10 Sekunden halten). DnA-TV erkennt sie selbst und belegt sie wie auf dem Fire TV: Steuerkreuz und OK (lange = Menü), Zurück, Home (Startseite), ☰ Menü, ⏯, ⏪/⏩ (gehalten schneller). Im Live-TV schaltet ▲/▼ um, OK öffnet die Senderliste, ◀ springt zum letzten Sender, ▶ zeigt Programm- und Stream-Info, ☰ Ton, Untertitel und Favorit. Solange DnA-TV vorn ist, öffnen Home und Zurück nicht den Browser und ⏯ pausiert keine andere Musik-App. *Einstellungen → Fernbedienung* zeigt, welche Taste ankommt.

Außerdem wie auf dem Fire TV: Startseite mit „Weiter schauen“, „Zuletzt gesehen“, Favoriten und „Mehr aus …“, doppelte Sender zusammengefasst (mit automatischem Ausweichen auf die nächste Fassung), Gruppen und Länder ein- und ausblenden (Live, Filme, Serien), Watchdog für eingefrorene Streams, Stream-Info, Pufferprofile, Zifferntasten, Playlists alle 12 und TV-Guide alle 8 Stunden automatisch neu, Catch-up (⏪ im TV-Guide und im Bild), Live anhalten und zeitversetzt weiter, Aufnahmen, Erinnerungen (auch „Verein folgen“), Profile, Kindersicherung, Updates in der App und Abgleich von Favoriten, Verlauf, eigenen und ausgeblendeten Gruppen mit dem Fire TV (*Einstellungen → Abgleich mit dem Fire TV*).

**4K und HDR:** Das Bild kommt von VLC so, wie die Hardware-Dekodierung es liefert (NV12, bei HDR und 10-Bit-HEVC P010 mit voller 10-Bit-Genauigkeit, also ohne Farbstufen in Verläufen), und wird auf der Grafikkarte umgerechnet. Falls eine Grafikkarte damit Probleme hat: *Einstellungen → Wiedergabe → Videoweg → Kompatibel*. Mit HDR-Monitor: *Direktausgabe* einschalten, dann zeigt VLC das Bild selbst im Vollbild und HDR10 kommt unverändert an (Windows-HDR an). HDR10 und HLG werden für normale Bildschirme angepasst statt blass gezeigt (*Einstellungen → Wiedergabe → HDR-Anpassung*, auch im ☰-Menü), SD-Sender erscheinen im richtigen Seitenverhältnis. Bei Problemen: *Einstellungen → Bedienung → Protokoll öffnen*.

**Multiview:** im Player ☰ → „Multiview“: bis zu vier Sender im 2×2-Raster, Steuerkreuz wählt das Feld, OK holt den Ton dorthin (noch einmal OK: Vollbild), Sender ± zappt im Feld. In der Senderliste läuft der ausgewählte Sender nach kurzer Zeit stumm als **Mini-Vorschau** (abschaltbar). Die Installer werden signiert, sobald ein Code-Signing-Zertifikat als Secret hinterlegt ist (`WINDOWS_SIGN_CERT`, `WINDOWS_SIGN_PASSWORD`).

**TV-Modus (optional, für einen Mini-PC am Fernseher):** *Einstellungen → TV-Modus*: DnA-TV startet mit Windows direkt im Vollbild, der Mauszeiger verschwindet, PC schlafen legen oder ausschalten aus der App. Standard bleibt die normale Fensterbedienung.

Bedienung mit Maus (Rechtsklick = Menü, Doppelklick = Vollbild, Mausrad = Lautstärke), Tastatur (Pfeile, Enter, Esc/Rücktaste, Kontextmenü-Taste, Leertaste, F11; Vollbild wird gemerkt) und Handy (*Einstellungen → Handy als Fernbedienung*: Adresse im Handy-Browser öffnen).

Gebaut wird sie aus `desktop/` (Kotlin + Compose Desktop, Wiedergabe über VLC) mit denselben Lesern für M3U, Xtream, TV-Guide und Sicherung wie die Fire-TV-App: `./gradlew -p desktop run` bzw. `./gradlew -p desktop packageMsi`.

## Untertitel und Übersetzung einrichten (optional, kostenlos)

Übersetzen funktioniert ohne jede Einrichtung: Pro Sprachrichtung lädt DnA-TV einmalig ein Übersetzungsmodell (ca. 110 MB, [OPUS-MT](https://github.com/Helsinki-NLP/Opus-MT), Universität Helsinki, CC BY 4.0) und übersetzt danach auf dem Gerät.

Für **Untertitel aus dem Internet** (fast jeder Film, viele Sprachen, auch KI-übersetzte) einmalig OpenSubtitles verbinden:

1. In DnA-TV *Einstellungen → Untertitel → Einrichtung per Handy*: QR-Code mit dem Handy scannen (gleiches WLAN).
2. Auf der Seite stehen die Links: kostenloses Konto auf [opensubtitles.com](https://www.opensubtitles.com), unter [opensubtitles.com/consumers](https://www.opensubtitles.com/consumers) einen API-Key anlegen, Key, Benutzername und Passwort einfügen, *Speichern und prüfen*.
3. Optional ebenso einen DeepL-API-Key ([deepl.com/pro-api](https://www.deepl.com/pro-api), 500.000 Zeichen/Monat frei). Ist das Kontingent leer, übersetzt wieder das Gerät.

## Tipps für flüssiges 4K

1. *Einstellungen → Puffer* auf **Stabil (4K)** stellen.
2. Ruckelt es weiter: **Tunneling-Modus** einschalten.
3. Ruckeln bei Kameraschwenks (z. B. Fußball): **Bildwiederholrate anpassen** einschalten.
4. Mit der rechten Pfeiltaste im Player das Stream-Info öffnen. Steigt „Verworfene Frames“ stark an, schafft der Decoder den Stream nicht. Liegt „Leitung“ unter der Video-Bitrate, ist das Netz der Engpass (LAN-Adapter statt WLAN hilft).

## Entwicklung

```bash
./gradlew testDebugUnitTest      # Parser-Tests (M3U, XMLTV, Xtream)
./gradlew assembleRelease        # optimierte APK (mit Debug-Schlüssel signiert, zum Sideloaden)
```

Voraussetzungen: JDK 17 und Android SDK (compileSdk 37). Unterstützt werden Fire OS 6 und neuer (minSdk 23), also alle Fire TV Sticks ab 2018.

Technikstand (Oktober 2026): Kotlin 2.4, AGP 9.4 (mit eingebautem Kotlin), Gradle 9.8, compileSdk 37, Media3 1.11.1 (mit dynamischem Scheduling der Wiedergabeschleife standardmäßig an, besserer Bildraten-Schätzung, Dolby Vision Profil 10), Compose 1.12, Compose for TV Material 1.1, Room 2.8, Coil 3, OkHttp 5. Die GitHub-Action baut bei jedem Push automatisch beide APKs.
Ein Tag `v*` (z. B. `v0.1.0`) erstellt ein Release mit der APK.

### Aufbau

```
app/src/main/java/de/ayutv/
├── data/parse/   Streaming-Parser (M3U, XMLTV, Xtream-JSON), ohne Android-Abhängigkeiten und getestet
├── data/db/      Room-Datenbank (Sender, EPG, Filme, Serien, Favoriten, Verlauf)
├── data/repo/    Synchronisierung (PlaylistRepository) und Abfragen (ContentRepository)
├── player/       ExoPlayer-Konfiguration, Bildwiederholraten-Anpassung, Player-Activity und Overlays
└── ui/           Compose-for-TV-Oberfläche: Start, Live TV, TV-Guide, Filme, Serien, Suche, Einstellungen
```

## Geplant

- Multiview mit mehr als zwei Sendern auf dem Fire TV (die meisten Sticks haben nur zwei Video-Decoder)

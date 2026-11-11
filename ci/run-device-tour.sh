#!/usr/bin/env bash
# Läuft im gestarteten Emulator: Rundgang-Test, Messwerte, Absturz-Check der Release-APK.
set -x
mkdir -p ci-out
adb wait-for-device
if [ -f previous-debug.apk ]; then
  # Vorversion starten, damit sie ihre Datenbank anlegt; dann die neue Version darüber installieren.
  adb install previous-debug.apk
  adb shell am start -W -n de.ayutv/.MainActivity
  sleep 10
  adb shell am force-stop de.ayutv
  # Eigene Daten in der Vorversion anlegen (eigene Gruppe mit Sender, Favorit, ausgeblendete Gruppe). Der Test prüft
  # nach dem Update, dass alles noch da ist: ein Update über die App darf nichts davon löschen.
  SEED="INSERT INTO custom_groups(name, position) VALUES('Upgrade-Test', 99);
INSERT INTO custom_group_items(groupId, [key], position) VALUES((SELECT id FROM custom_groups WHERE name='Upgrade-Test'), '99:c:1', 1);
INSERT INTO favorites([key], addedAt, position) VALUES('99:c:1', 1, 0);
INSERT INTO hidden_groups(playlistId, kind, name) VALUES(99, 'LIVE', 'Upgrade-Test');"
  if echo "$SEED" | adb shell "run-as de.ayutv sqlite3 databases/ayu.db" && \
     echo "SELECT COUNT(*) FROM custom_groups;" | adb shell "run-as de.ayutv sqlite3 databases/ayu.db" | grep -q '^[1-9]'; then
    UPGRADE_SEEDED=true
  else
    echo "Upgrade-Test: Daten ließen sich in der Vorversion nicht anlegen (sqlite3 fehlt?)"
  fi
fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null 2>&1 || true
adb logcat -c
timeout 2400 adb shell am instrument -w -e class de.ayutv.DeviceTourTest -e upgradeSeeded "${UPGRADE_SEEDED:-false}" de.ayutv.test/androidx.test.runner.AndroidJUnitRunner | tee ci-out/instrument.txt
adb shell dumpsys gfxinfo de.ayutv > ci-out/gfxinfo.txt 2>&1 || true
adb logcat -d -v time '*:W' AyuSubtitles:I AyuTour:I DnaEngine:I DnaReader:I DnaHls:I DnaEnginePlayer:I AyuPlayer:I > ci-out/logcat_warnings.txt 2>&1 || true
adb exec-out run-as de.ayutv tar c -C files ci | tar x -C ci-out --strip-components=1 || true
adb logcat -d -v time AyuTour:I ActivityTaskManager:I ActivityManager:I PlayerActivity:I ExoPlayerImpl:W MediaCodec:W AndroidRuntime:E PlaylistRepository:W Subtitles:I '*:E' > ci-out/logcat.txt 2>&1 || true

# Release-APK (R8-optimiert) über die Debug-Version installieren: gleiche Signatur, Daten bleiben erhalten.
adb uninstall de.ayutv.test || true
adb install -r app/build/outputs/apk/release/app-release.apk
# Wie auf dem Fire TV nach der Installation: Baseline-Profil anwenden (sonst erst später im Leerlauf).
adb shell cmd package compile -f -m speed-profile de.ayutv || true
adb shell am force-stop de.ayutv
adb logcat -c
adb shell am start -W -n de.ayutv/.MainActivity | tee ci-out/release_start.txt
sleep 12
adb exec-out screencap -p > ci-out/release_home.png
key() { adb shell input keyevent "$@"; }
# Ruckler im Dauerbetrieb messen (ohne Kaltstart): Live TV mit 3000 Sendern und TV-Guide durchscrollen.
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 2
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 25); do key KEYCODE_DPAD_DOWN; done; sleep 1
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 40); do key KEYCODE_DPAD_DOWN; done; sleep 1
adb exec-out screencap -p > ci-out/release_live.png
# Release-APK (R8) auch abspielen lassen: Sender öffnen, Bild und Ton der DNA Engine im Protokoll festhalten.
key KEYCODE_DPAD_CENTER; sleep 12
adb exec-out screencap -p > ci-out/release_play.png
adb logcat -d -v time DnaEngine:I DnaEnginePlayer:I AndroidRuntime:E DEBUG:F libc:F '*:S' > ci-out/release_play.txt 2>&1 || true
key KEYCODE_BACK; sleep 2
for i in $(seq 1 20); do key KEYCODE_DPAD_UP; done; sleep 1
adb shell dumpsys gfxinfo de.ayutv > ci-out/release_gfxinfo_live.txt 2>&1 || true
adb shell dumpsys gfxinfo de.ayutv reset >/dev/null
for i in $(seq 1 6); do key KEYCODE_DPAD_LEFT; done; sleep 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 3
key KEYCODE_DPAD_RIGHT; sleep 1
for i in $(seq 1 30); do key KEYCODE_DPAD_DOWN; done
for i in $(seq 1 8); do key KEYCODE_DPAD_RIGHT; done; sleep 1
adb exec-out screencap -p > ci-out/release_guide.png
adb shell dumpsys gfxinfo de.ayutv > ci-out/release_gfxinfo_guide.txt 2>&1 || true
adb logcat -d -v time AndroidRuntime:E '*:S' > ci-out/release_crash.txt 2>&1 || true
# Job nur grün, wenn wirklich alles stimmt: jeder Abschnitt des Rundgangs bestanden und die
# Release-APK ohne Absturz gestartet (vorher stand ein Fehler nur in results.txt).
status=0
grep -q "^OK (" ci-out/instrument.txt || { echo "::error::Rundgang im Emulator fehlgeschlagen (siehe results.txt)"; status=1; }
grep -q "FATAL EXCEPTION" ci-out/release_crash.txt && { echo "::error::Release-APK abgestürzt"; status=1; }
grep -qE "FATAL EXCEPTION|Fatal signal" ci-out/release_play.txt && { echo "::error::Release-APK beim Abspielen abgestürzt"; status=1; }
grep -E "erstes Bild gezeigt|Ton läuft|Neuer Stream" ci-out/release_play.txt | head -5 || true
exit $status

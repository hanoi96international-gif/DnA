#!/usr/bin/env bash
# Läuft im gestarteten Handy-Emulator: Bedienung per Touchscreen (Hoch- und Querformat).
set -x
mkdir -p ci-phone
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
timeout 1200 adb shell am instrument -w -e class de.ayutv.PhoneTest de.ayutv.test/androidx.test.runner.AndroidJUnitRunner | tee ci-phone/instrument.txt
adb exec-out run-as de.ayutv tar c -C files ci | tar x -C ci-phone --strip-components=1 || true
adb logcat -d -v time AyuPhone:I AndroidRuntime:E '*:E' > ci-phone/logcat.txt 2>&1 || true
status=0
grep -q "^OK (" ci-phone/instrument.txt || { echo "::error::Handy-Test fehlgeschlagen (siehe results.txt)"; cat ci-phone/results.txt 2>/dev/null; status=1; }
grep -q "FATAL EXCEPTION" ci-phone/logcat.txt && { echo "::error::App auf dem Handy abgestürzt"; status=1; }
exit $status

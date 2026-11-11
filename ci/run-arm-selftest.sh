#!/usr/bin/env bash
# Läuft im Emulator: App als 32-Bit-ARM installieren und den Übersetzer-Selbsttest ausführen.
set -x
mkdir -p arm-out
adb wait-for-device
adb shell getprop ro.product.cpu.abilist | tee arm-out/abilist.txt
adb shell getprop ro.dalvik.vm.native.bridge | tee -a arm-out/abilist.txt
adb install --abi armeabi-v7a -r app/build/outputs/apk/debug/app-debug.apk | tee arm-out/install.txt
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm dump de.ayutv | grep -i "primaryCpuAbi\|nativeLibrary" | tee -a arm-out/install.txt
adb logcat -c
timeout 2400 adb shell am instrument -w -e class de.ayutv.ArmSelfTest de.ayutv.test/androidx.test.runner.AndroidJUnitRunner | tee arm-out/instrument.txt
adb exec-out run-as de.ayutv cat files/ci/arm.txt > arm-out/arm.txt || true
adb exec-out run-as de.ayutv cat files/mt_step.txt > arm-out/last_step.txt 2>/dev/null || true
adb logcat -d -v time AyuArm:I DnAOcr:I AndroidRuntime:E DEBUG:F libc:F '*:F' ActivityManager:I > arm-out/logcat.txt 2>&1 || true
# Selbsttest fehlgeschlagen (Absturz oder Fehler auf 32-Bit-ARM): Job rot.
grep -q "^OK (" arm-out/instrument.txt

#!/usr/bin/env bash
# Gradle mit Zeitlimit: Hängt der Bau (z. B. KSP oder der Kotlin-Compiler), nach 12 Minuten einmal und nach
# $1 Minuten noch einmal die arbeitenden Threads aller Java-Prozesse ausgeben (zeigt, woran gerechnet oder
# worauf gewartet wird), dann abbrechen.
set -u
minutes="$1"; shift
./gradlew "$@" &
gradle=$!
start=$(date +%s)
end=$(( start + minutes * 60 ))
probe=$(( start + 12 * 60 ))

dump() {
  for pid in $(jps -q 2>/dev/null); do
    echo "===== $(jps -l 2>/dev/null | grep "^$pid ") ====="
    # Nur Threads, die mit dem Bau zu tun haben (KSP, Kotlin, Gradle-Worker), ganze Blöcke
    jstack "$pid" 2>/dev/null | awk -v RS= '/devtools\.ksp|jetbrains\.kotlin|Execution worker|WorkerExecutor|androidx\.room/' | head -1500
  done
}

while kill -0 "$gradle" 2>/dev/null; do
  now=$(date +%s)
  if [ "$probe" -gt 0 ] && [ "$now" -ge "$probe" ]; then
    echo "::warning::Bau läuft seit 12 Minuten – Zwischenstand der Threads"
    dump
    probe=0
  fi
  if [ "$now" -ge "$end" ]; then
    echo "::error::Bau nach $minutes Minuten nicht fertig – Thread-Dumps folgen"
    dump
    kill "$gradle" 2>/dev/null
    pkill -f KotlinCompileDaemon 2>/dev/null
    exit 1
  fi
  sleep 20
done
wait "$gradle"

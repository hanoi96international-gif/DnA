#!/usr/bin/env bash
# Test des Umverpackens auf dem Rechner (ohne Android): FFmpeg wie in der App bauen, Testfilme erzeugen (HEVC + DTS 5.1
# in MKV, H.264 + zwei AAC-Spuren in MP4, H.264 + E-AC3 in MKV), in Stücke umverpacken und prüfen:
# alle Bilder da, keine Dekodierfehler, jedes Stück für sich abspielbar, DTS als AC3.
# Braucht das Programm ffmpeg (mit libx265) zum Erzeugen und Prüfen.
set -euo pipefail
WORK="$(realpath -m "${1:-remux-test}")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
# shellcheck source=remux-config.sh
source "$ROOT/ci/remux-config.sh"
mkdir -p "$WORK" && cd "$WORK"
[ -d ffmpeg ] || git clone -q --depth 1 -b "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git ffmpeg
if [ ! -f host/lib/libavformat.a ]; then
  # Ohne Assembler (nasm fehlt auf den Runnern; für den Test zählt nur das Ergebnis, nicht das Tempo).
  mkdir -p build-host && cd build-host
  "$WORK/ffmpeg/configure" --prefix="$WORK/host" "${REMUX_FFMPEG_CONFIG[@]}" --disable-x86asm > configure.log 2>&1 \
    || { tail -30 configure.log; tail -30 ffbuild/config.log 2>/dev/null; exit 1; }
  make -j"$(nproc)" > make.log 2>&1 || { tail -40 make.log; exit 1; }
  make install > /dev/null
  cd "$WORK"
fi
gcc -O1 -Wall -Werror -o remux_test "$ROOT/app/src/test/native/remux_test.c" "$ROOT/app/src/main/cpp/remux/dnaremux.c" \
  -I"$ROOT/app/src/main/cpp/remux" -Ihost/include -Lhost/lib -lavformat -lavcodec -lswresample -lavutil -lm -lpthread

mkdir -p media
src() { ffmpeg -v error -y -f lavfi -i "testsrc2=s=640x360:r=$2" -f lavfi -i "sine=f=440:r=48000" -f lavfi -i "sine=f=660:r=48000" -t "$3" "${@:4}" "media/$1"; }
[ -f media/hevc_dts.mkv ] || src hevc_dts.mkv 24000/1001 65 -map 0:v -map 1:a -c:v libx265 -x265-params bframes=3:keyint=50:log-level=error -c:a dca -strict -2 -ac 6 -metadata:s:a:0 language=ger
[ -f media/h264_aac.mp4 ] || src h264_aac.mp4 24 50 -map 0:v -map 1:a -map 2:a -c:v libx264 -bf 3 -g 48 -c:a aac -ac 1 -metadata:s:a:0 language=eng -metadata:s:a:1 language=ger
[ -f media/h264_eac3.mkv ] || src h264_eac3.mkv 25 40 -map 0:v -map 1:a -c:v libx264 -bf 2 -g 50 -c:a eac3 -ac 2
# Offene GOP mit vielen B-Bildern (x265 „slow“, x264 --open-gop): Bilder nach dem Schlüsselbild, die davor angezeigt werden.
[ -f media/hevc_b8_truehd.mkv ] || src hevc_b8_truehd.mkv 24000/1001 30 -map 0:v -map 1:a -c:v libx265 -x265-params bframes=8:b-pyramid=1:keyint=48:open-gop=1:log-level=error -c:a truehd -strict -2 -ac 2
# Film mit eingebetteten Untertiteln (SRT und ASS, je 10 Zeilen über 40 s verteilt).
if [ ! -f media/h264_subs.mkv ]; then
  : > media/subs.srt
  for i in $(seq 0 9); do printf '%d\n00:00:%02d,500 --> 00:00:%02d,500\nZeile %d\n\n' $((i + 1)) $((i * 4)) $((i * 4 + 2)) "$i" >> media/subs.srt; done
  ffmpeg -v error -y -f lavfi -i "testsrc2=s=640x360:r=25" -f lavfi -i "sine=f=440:r=48000" -i media/subs.srt -i media/subs.srt -t 41 \
    -map 0:v -map 1:a -map 2:s -map 3:s -c:v libx264 -g 50 -c:a aac -c:s:0 srt -c:s:1 ass \
    -metadata:s:s:0 language=ger -metadata:s:s:1 language=eng media/h264_subs.mkv
fi
[ -f media/h264_open_flac.mkv ] || src h264_open_flac.mkv 25 30 -map 0:v -map 1:a -c:v libx264 -bf 3 -g 50 -x264-params open-gop=1 -c:a flac

fail=0
check() {
  local file=$1 want_audio=$2
  local out="out-${file%.*}"
  rm -rf "$out" && mkdir -p "$out"
  ./remux_test "media/$file" "$out" 4 > "$out/log.txt"
  local frames_in frames_out errors audio
  frames_in=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "media/$file" | grep -m1 .)
  frames_out=$(ffprobe -v error -allowed_extensions ALL -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$out/list.m3u8" | grep -m1 .)
  # Dekodierfehler beim Abspielen der ganzen Liste. (Die Meldung „non monotonically increasing dts to muxer“ kommt
  # vom Prüfwerkzeug selbst: Es rät bei HLS 24 statt 23,976 Bilder/s und rundet zwei Bilder auf denselben Platz.)
  errors=$(ffmpeg -v error -allowed_extensions ALL -i "$out/list.m3u8" -f null - 2>&1 | grep -vc "non monotonically increasing dts to muxer" || true)
  # Anzeigezeiten der Bilder: jede nur einmal, in der Anzeige-Reihenfolge streng aufsteigend.
  local dup
  dup=$(ffprobe -v error -allowed_extensions ALL -select_streams v:0 -show_entries frame=best_effort_timestamp -of csv=p=0 "$out/list.m3u8" \
    | grep -E '^[0-9]+' | awk 'NR > 1 && $1 <= last { n++ } { last = $1 } END { print n + 0 }')
  errors=$((errors + dup))
  audio=$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 "$out/000.ts" | grep -m1 .)
  # Jedes Stück für sich (so beginnt der Fernseher nach dem Springen): fast alle Bilder dekodierbar. Bei offener GOP
  # fehlen nur die wenigen vorgezogenen Bilder nach dem ersten Schlüsselbild (sie bräuchten das vorige Stück).
  local single=0 pk fr
  for seg in "$out"/*.ts; do
    pk=$(ffprobe -v error -count_packets -select_streams v:0 -show_entries stream=nb_read_packets -of csv=p=0 "$seg" | grep -m1 .)
    fr=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$seg" 2>/dev/null | grep -m1 .)
    [[ "$fr" =~ ^[0-9]+$ ]] || fr=0
    [ "$fr" -ge $((pk - 16)) ] || single=$((single + 1))
  done
  # Ton an den Stückgrenzen: Ende des einen = Anfang des nächsten (keine Lücke, nichts doppelt), auf 1 ms genau.
  local gaps
  gaps=$(python3 - "$out" <<'PY'
import glob, json, subprocess, sys
prev, bad = None, 0
for seg in sorted(glob.glob(sys.argv[1] + "/[0-9][0-9][0-9].ts")):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts_time,duration_time",
                          "-of", "json", seg], capture_output=True, text=True).stdout
    pk = [p for p in json.loads(out).get("packets", []) if "pts_time" in p]
    if not pk:
        bad += 1
        continue
    first = float(pk[0]["pts_time"])
    if prev is not None and abs(first - prev) > 0.001:
        bad += 1
    prev = float(pk[-1]["pts_time"]) + float(pk[-1].get("duration_time") or 0)
print(bad)
PY
)
  echo "$file: Bilder $frames_out/$frames_in, Fehler $errors, Stücke mit Fehlern $single, Tonlücken $gaps, Ton $audio (erwartet $want_audio)"
  if [ "$frames_out" != "$frames_in" ] || [ "$errors" != 0 ] || [ "$single" != 0 ] || [ "$gaps" != 0 ] || [ "$audio" != "$want_audio" ]; then
    fail=1
    cat "$out/log.txt"
  fi
}
check hevc_dts.mkv ac3
check h264_aac.mp4 aac
check h264_eac3.mkv eac3
check hevc_b8_truehd.mkv ac3
check h264_open_flac.mkv ac3
check h264_subs.mkv aac
# Eingebettete Text-Untertitel (SRT und ASS) kommen beim Umverpacken vollständig mit, jede Zeile genau einmal.
for track in 2 3; do
  got=$(awk -F'\t' -v t=$track '$1 == t' out-h264_subs/cues.txt | sort -u | wc -l)
  dupes=$(awk -F'\t' -v t=$track '$1 == t { print $2 }' out-h264_subs/cues.txt | sort | uniq -d | wc -l)
  echo "Untertitelspur $track: $got Zeilen (erwartet 10), doppelt $dupes"
  [ "$got" = 10 ] && [ "$dupes" = 0 ] || fail=1
done
# Ton-Decoder der DNA Engine (dnaaudio.c): DTS 5.1, TrueHD, E-AC-3, FLAC, MP2 – die ganze Dauer als PCM, 5.1 bleibt 5.1.
gcc -O1 -Wall -Werror -o audio_test "$ROOT/app/src/test/native/audio_test.c" "$ROOT/app/src/main/cpp/remux/dnaaudio.c" \
  -I"$ROOT/app/src/main/cpp/remux" -Ihost/include -Lhost/lib -lavformat -lavcodec -lswresample -lavutil -lm -lpthread
[ -f media/h264_mp2.ts ] || src h264_mp2.ts 25 20 -map 0:v -map 1:a -c:v libx264 -g 50 -c:a mp2 -ac 2
./audio_test media/hevc_dts.mkv dca 6 || fail=1
./audio_test media/hevc_b8_truehd.mkv truehd 2 || fail=1
./audio_test media/h264_eac3.mkv eac3 2 || fail=1
./audio_test media/h264_open_flac.mkv flac 1 || fail=1
./audio_test media/h264_mp2.ts mp2 2 || fail=1
[ "$fail" = 0 ] && echo "Umverpacken: alles in Ordnung" || { echo "Umverpacken: FEHLER"; exit 1; }

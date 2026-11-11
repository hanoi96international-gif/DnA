#!/usr/bin/env bash
# Baut die Media3-FFmpeg-Erweiterung (nur Tondecoder) passend zur Media3-Version der App.
# Google veröffentlicht sie nicht fertig; vorgefertigte Builds Dritter hinken Media3 hinterher
# und wären wegen geänderter interner Audio-Schnittstellen zur Laufzeit nicht sicher kompatibel.
#
# Ergebnis: $OUT/lib-decoder-ffmpeg-release.aar (armeabi-v7a, arm64-v8a, x86, x86_64)
set -euo pipefail

MEDIA3_VERSION="${MEDIA3_VERSION:-1.11.1}"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
OUT="$(realpath -m "${1:-ffmpeg-aar}")"
WORK="$(realpath -m "${2:-ffmpeg-build}")"
# Media3 testet den FFmpeg-Build mit NDK r26b: eine installierte r26 bevorzugen.
NDK26=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/ndk/26.* 2>/dev/null | sort -V | tail -1 || true)
NDK_PATH="${NDK26:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?Android NDK fehlt}}}"
echo "NDK: $NDK_PATH"
# Tonformate, die Fire TV ohne AV-Receiver nicht oder nicht überall selbst dekodieren kann.
DECODERS=(ac3 eac3 dca truehd mlp mp3 flac alac vorbis opus)

mkdir -p "$OUT" "$WORK"
cd "$WORK"
[ -d media ] || git clone -q --depth 1 -b "$MEDIA3_VERSION" https://github.com/androidx/media.git media
[ -d ffmpeg ] || git clone -q --depth 1 -b "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git ffmpeg

MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
ln -sfn "$WORK/ffmpeg" "$MODULE/jni/ffmpeg"
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK_PATH" linux-x86_64 23 "${DECODERS[@]}")

cd "$WORK/media"
TASK=$(./gradlew --no-daemon -q projects | grep -o "':[A-Za-z0-9_-]*ffmpeg[A-Za-z0-9_-]*'" | head -1 | tr -d "'")
echo "Modul: $TASK"
./gradlew --no-daemon "$TASK:assembleRelease"
AAR=$(find . -path "*decoder*ffmpeg*" -name "*release*.aar" | head -1)
[ -n "$AAR" ] || { echo "AAR nicht gefunden"; exit 1; }
cp "$AAR" "$OUT/lib-decoder-ffmpeg-release.aar"
unzip -l "$OUT/lib-decoder-ffmpeg-release.aar" | grep -E "\.so|classes.jar"

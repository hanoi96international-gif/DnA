#!/usr/bin/env bash
# Baut libdnaremux.so (Filme für den Fernseher umverpacken, app/src/main/cpp/remux) mit einem schlanken, statisch
# eingebundenen FFmpeg für alle ABIs der App.
#
# Ergebnis: $OUT/<abi>/libdnaremux.so (arm64-v8a, armeabi-v7a, x86_64)
set -euo pipefail

FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
OUT="$(realpath -m "${1:-remux-libs}")"
WORK="$(realpath -m "${2:-remux-build}")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Wie ci/build-ffmpeg.sh: FFmpeg 6.0 baut sicher mit NDK r26, eine installierte r26 bevorzugen.
NDK26=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/ndk/26.* 2>/dev/null | sort -V | tail -1 || true)
NDK_PATH="${NDK26:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?Android NDK fehlt}}}"
TOOLS="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24
echo "NDK: $NDK_PATH"

mkdir -p "$OUT" "$WORK"
cd "$WORK"
[ -d ffmpeg ] || git clone -q --depth 1 -b "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git ffmpeg

# shellcheck source=remux-config.sh
source "$ROOT/ci/remux-config.sh"

build() {
  local abi=$1 arch=$2 cpu=$3 triple=$4 extra=$5
  local prefix="$WORK/out/$abi"
  local cc="$TOOLS/${triple}${API}-clang"
  echo "== $abi"
  rm -rf "$WORK/build-$abi" && mkdir -p "$WORK/build-$abi" && cd "$WORK/build-$abi"
  # shellcheck disable=SC2086
  "$WORK/ffmpeg/configure" --prefix="$prefix" "${REMUX_FFMPEG_CONFIG[@]}" \
    --target-os=android --enable-cross-compile --arch="$arch" --cpu="$cpu" \
    --cc="$cc" --cxx="${cc}++" --ar="$TOOLS/llvm-ar" --nm="$TOOLS/llvm-nm" --ranlib="$TOOLS/llvm-ranlib" \
    --strip="$TOOLS/llvm-strip" --extra-cflags="-O3 -fPIC" $extra > configure.log 2>&1 \
    || { tail -30 configure.log; tail -40 ffbuild/config.log 2>/dev/null; exit 1; }
  make -j"$(nproc)" > make.log 2>&1 || { tail -40 make.log; exit 1; }
  make install > /dev/null
  mkdir -p "$OUT/$abi"
  # Eine Bibliothek: Umverpacken + JNI + FFmpeg statisch; 16-KB-Seiten (Android 15).
  "$cc" -shared -fPIC -O3 -o "$OUT/$abi/libdnaremux.so" \
    "$ROOT/app/src/main/cpp/remux/dnaremux.c" "$ROOT/app/src/main/cpp/remux/dnaremux_jni.c" \
    "$ROOT/app/src/main/cpp/remux/dnaaudio.c" "$ROOT/app/src/main/cpp/remux/dnaaudio_jni.c" \
    "$ROOT/app/src/main/cpp/remux/dnavideo.c" "$ROOT/app/src/main/cpp/remux/dnavideo_jni.c" \
    -I"$prefix/include" -L"$prefix/lib" -lavformat -lavcodec -lswresample -lswscale -lavutil -lm -llog -landroid \
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
  "$TOOLS/llvm-strip" --strip-unneeded "$OUT/$abi/libdnaremux.so"
  cd "$WORK"
}

build arm64-v8a aarch64 armv8-a aarch64-linux-android ""
build armeabi-v7a arm armv7-a armv7a-linux-androideabi "--enable-neon"
build x86_64 x86_64 x86-64 x86_64-linux-android "--disable-asm"
ls -la "$OUT"/*/libdnaremux.so

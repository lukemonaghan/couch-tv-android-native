#!/bin/sh
# Builds the music visualiser's native libraries into app/src/main/jniLibs/<abi>/ :
#   libprojectM-4.so  projectM, from a fork that has an OpenGL ES 2.0 backend (the Mali-470 TVs have nothing newer)
#   libpmbridge.so    native/pmbridge.cpp, our JNI wrapper
#   libc++_shared.so  the C++ runtime they share
# The home still builds and runs without them: the music view just keeps its blurred cover.
#
#   ANDROID_HOME=/path/to/sdk sh tools/build-native.sh [abi ...]      (default: armeabi-v7a arm64-v8a x86)
#
# Needs the Android NDK (sdkmanager "ndk;27.2.12479018"), cmake, ninja and git. No sudo. Work happens in build/native/.
# projectM is LGPL-2.1: it stays a separate .so, see NOTICE.md.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
NDK=${NDK:-$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)}
FORK=https://github.com/bbangert/projectm.git
FORK_COMMIT=e4d1f00d     # branch up-es2 (OpenGL ES 2.0 backend + HLSL to GLSL ES 1.00 translation)
SRC="$ROOT/build/native/projectm"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
ABIS=${*:-armeabi-v7a arm64-v8a x86}

if [ ! -d "$SRC/.git" ]; then mkdir -p "$ROOT/build/native"; git clone -q "$FORK" "$SRC"; fi
git -C "$SRC" fetch -q origin up-es2
git -C "$SRC" checkout -q "$FORK_COMMIT"
git -C "$SRC" submodule update -q --init --recursive

for ABI in $ABIS; do
  case $ABI in
    armeabi-v7a) CC=armv7a-linux-androideabi23; SYS=arm-linux-androideabi ;;
    arm64-v8a)   CC=aarch64-linux-android23;    SYS=aarch64-linux-android ;;
    x86)         CC=i686-linux-android23;       SYS=i686-linux-android ;;
    x86_64)      CC=x86_64-linux-android23;     SYS=x86_64-linux-android ;;
    *) echo "unknown abi $ABI"; exit 1 ;;
  esac
  B="$ROOT/build/native/$ABI"
  echo "== $ABI"
  cmake -S "$SRC" -B "$B" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DENABLE_GLES2=ON \
    -DENABLE_SYSTEM_PROJECTM_EVAL=OFF -DENABLE_PLAYLIST=OFF -DBUILD_SHARED_LIBS=ON -DBUILD_TESTING=OFF > /dev/null
  cmake --build "$B" -j"$(nproc)" > /dev/null
  OUT="$ROOT/app/src/main/jniLibs/$ABI"; mkdir -p "$OUT"
  "$BIN/llvm-strip" -o "$OUT/libprojectM-4.so" "$B/src/libprojectM/libprojectM-4.so"
  "$BIN/${CC}-clang++" -shared -fPIC -O2 -std=c++17 -I"$SRC/src/api/include" -I"$B/src/api/include" \
    -o "$OUT/libpmbridge.so" "$ROOT/native/pmbridge.cpp" -L"$B/src/libprojectM" -lprojectM-4 -lGLESv2 -llog
  "$BIN/llvm-strip" "$OUT/libpmbridge.so"
  cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$SYS/libc++_shared.so" "$OUT/"
  ls -la "$OUT" | tail -3
done

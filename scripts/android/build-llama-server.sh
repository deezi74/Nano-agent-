#!/usr/bin/env bash
# Build llama.cpp's OpenAI-compatible server for arm64 Android and drop it into the APK's
# native libraries as libllamaserver.so (Android only lets an app execute files from its
# native library directory, the same trick the PRoot loader uses).
#
#   ANDROID_NDK_HOME=... bash scripts/android/build-llama-server.sh [--force]
#
# Pinned to a llama.cpp release tag (LLAMA_TAG); output is git-ignored.
set -euo pipefail

LLAMA_TAG="${LLAMA_TAG:-b12017}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/android/src/android/app/src/main/jniLibs/arm64-v8a/libllamaserver.so"
WORK="${LLAMA_WORK:-$ROOT/android/deps/.llama}"

if [ "${1:-}" != "--force" ] && [ -f "$OUT" ]; then
  echo "== llama-server: present ($(du -h "$OUT" | cut -f1))"
  exit 0
fi
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to NDK r27c}"
command -v cmake >/dev/null || { echo "cmake missing" >&2; exit 1; }

mkdir -p "$WORK"
if [ ! -f "$WORK/src/CMakeLists.txt" ]; then
  rm -rf "$WORK/src"
  git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp.git "$WORK/src"
fi

cmake -S "$WORK/src" -B "$WORK/build" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_COMMON=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_CONNECT=OFF \
  -DLLAMA_OPENSSL=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=ON \
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
cmake --build "$WORK/build" --target llama-server -j"$(nproc 2>/dev/null || echo 4)"

bin="$(find "$WORK/build" -type f -name llama-server -perm -u+x | head -1)"
[ -n "$bin" ] || { echo "llama-server not produced" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT" "$bin"
file "$OUT" 2>/dev/null || true
echo "== llama-server -> $OUT ($(du -h "$OUT" | cut -f1))"

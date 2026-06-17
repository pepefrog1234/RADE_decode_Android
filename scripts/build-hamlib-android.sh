#!/usr/bin/env bash
#
# Cross-compile Hamlib rigctld for Android arm64-v8a.
#
# Produces a statically-linked rigctld (libhamlib + all backends compiled in;
# dynamically linked only against bionic libc/libm/libdl) and installs it as
#   app/src/main/jniLibs/arm64-v8a/librigctld.so   (+ assets/rigctld fallback)
# The executable is named *.so so AGP packages it into nativeLibraryDir — the
# only app-writable, exec-permitted location on modern Android.
#
# Requirements: Android NDK, curl, autotools/make. The Hamlib release tarball
# ships ./configure, so no autoreconf is needed.
#
# Usage:
#   scripts/build-hamlib-android.sh                 # builds HAMLIB_VERSION (default)
#   HAMLIB_VERSION=4.7.1 ANDROID_NDK=/path scripts/build-hamlib-android.sh
#
set -euo pipefail

HAMLIB_VERSION="${HAMLIB_VERSION:-4.7.1}"
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
API="${API:-26}"                 # must be <= app minSdk
ABI_TRIPLE=aarch64-linux-android

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT/.hamlib-build"
SHIM="$ROOT/scripts/android_pthread_cancel.h"
OUT_LIB="$ROOT/app/src/main/jniLibs/arm64-v8a/librigctld.so"
OUT_ASSET="$ROOT/app/src/main/assets/rigctld"

HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"   # darwin-x86_64 runs on Apple Silicon via Rosetta
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
export PATH="$TOOLCHAIN/bin:$PATH"
export CC="$TOOLCHAIN/bin/${ABI_TRIPLE}${API}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

mkdir -p "$BUILD_DIR"; cd "$BUILD_DIR"
TARBALL="hamlib-$HAMLIB_VERSION.tar.gz"
[ -f "$TARBALL" ] || curl -sSL -o "$TARBALL" \
  "https://github.com/Hamlib/Hamlib/releases/download/$HAMLIB_VERSION/$TARBALL"
rm -rf "hamlib-$HAMLIB_VERSION"
tar xf "$TARBALL"
cd "hamlib-$HAMLIB_VERSION"

# The release tarball's src/rot_reg.c references the "androidsensor" rotator whose
# source is not shipped -> undefined initrots4_androidsensor at link time. We only
# do rig CAT (no rotators), so drop the stray registry entries.
sed -i.bak '/INITROT_BACKEND(androidsensor)/d; /ROT_FUNCNAMA(androidsensor)/d' src/rot_reg.c

# -include the pthread_cancel shim: bionic has no pthread_cancel(), which Hamlib
# references in src/rig.c (async-data thread, unused on polled rigs) and ars.c.
./configure --host="$ABI_TRIPLE" \
  --enable-static --disable-shared \
  --without-cxx-binding --without-readline --without-libusb \
  CFLAGS="-O2 -fPIE -include $SHIM -Wno-error=implicit-function-declaration" \
  LDFLAGS="-fPIE -pie" \
  ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes

make -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

"$STRIP" -o "$OUT_LIB" tests/rigctld
cp "$OUT_LIB" "$OUT_ASSET"

echo
echo "Built Hamlib $HAMLIB_VERSION rigctld -> $OUT_LIB"
file "$OUT_LIB"
"$TOOLCHAIN/bin/llvm-readelf" -d "$OUT_LIB" | grep NEEDED

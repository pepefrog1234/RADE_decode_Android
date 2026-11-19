#!/usr/bin/env bash
# Build the rigctld that ships in the APK (app/src/main/jniLibs/arm64-v8a/
# librigctld.so) from the Hamlib release tarball and tools/hamlib/patches.
#
#   tools/hamlib/build-rigctld.sh              build and check, leave the result in the work directory
#   tools/hamlib/build-rigctld.sh --install    ...and copy it into the app
#
# Runs on Linux x86_64. Needs the Android NDK named below (ANDROID_NDK_HOME, or
# installed under ANDROID_HOME/ndk/), curl, patch and make.
#   RIGCTLD_WORK=<dir>     work directory (default: a new temporary one)
#   HAMLIB_TARBALL=<file>  use this copy of the tarball instead of downloading it
# See tools/hamlib/README.md.
set -euo pipefail

HAMLIB_VERSION=4.7.2
HAMLIB_SHA256=ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f
HAMLIB_URL="https://github.com/Hamlib/Hamlib/releases/download/$HAMLIB_VERSION/hamlib-$HAMLIB_VERSION.tar.gz"
NDK_VERSION=28.2.13676358   # r28c
API=26                      # the app's minSdk
TARGET=aarch64-linux-android

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PATCHES="$ROOT/tools/hamlib/patches"
INSTALL=0
[[ "${1:-}" == "--install" ]] && INSTALL=1

NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$NDK_VERSION}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$BIN/$TARGET$API-clang" ]]; then
  echo "Android NDK $NDK_VERSION not found at $NDK (set ANDROID_NDK_HOME)." >&2
  exit 1
fi
if ! grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties"; then
  echo "Expected NDK $NDK_VERSION; $NDK is $(grep Pkg.Revision "$NDK/source.properties")." >&2
  exit 1
fi

WORK="${RIGCTLD_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
TARBALL="${HAMLIB_TARBALL:-$WORK/hamlib-$HAMLIB_VERSION.tar.gz}"
[[ -f "$TARBALL" ]] || curl -fsSL -o "$TARBALL" "$HAMLIB_URL"
echo "$HAMLIB_SHA256  $TARBALL" | sha256sum -c -

SRC="$WORK/hamlib-$HAMLIB_VERSION"
rm -rf "$SRC"
tar -xzf "$TARBALL" --no-same-owner -C "$WORK"
for patch in "$PATCHES"/*.patch; do
  echo "Applying $(basename "$patch")"
  patch -d "$SRC" -p1 --no-backup-if-mismatch < "$patch"
done

# Configure in the source tree: the tarball ships an include/hamlib/config.h
# from the release machine, which would shadow the one of a separate build
# directory.
cd "$SRC"
# -ffile-prefix-map keeps the build directory out of the binary, so the same
# sources give the same file wherever they are built.
CFLAGS="-O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2 -ffile-prefix-map=$SRC=."
LDFLAGS="-pie -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack -Wl,--build-id=sha1"
# ac_cv_header_glob_h=no: bionic has <glob.h> but declares glob() from API 28
# only. Without the header Hamlib leaves out its search for microHAM devices
# under /dev/serial, which Android does not have.
# ac_cv_header_android_sensor_h=no: leaves out the rotator that reads the
# phone's own sensors. It is C++ and would make rigctld need libandroid and a
# C++ runtime.
./configure --host="$TARGET" \
  --disable-shared --enable-static \
  --without-cxx-binding --without-libusb --without-readline --without-indi --disable-winradio \
  ac_cv_header_glob_h=no ac_cv_header_android_sensor_h=no \
  CC="$BIN/$TARGET$API-clang" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib" STRIP="$BIN/llvm-strip" \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" > "$WORK/configure.log" 2>&1 \
  || { tail -20 "$WORK/configure.log" >&2; exit 1; }
# LDFLAGS again: for Android, configure appends -lc++ to them (for that
# rotator), and rigctld would then need libc++_shared.so without using it.
make -j"$(nproc)" LDFLAGS="$LDFLAGS" > "$WORK/make.log" 2>&1 || { tail -30 "$WORK/make.log" >&2; exit 1; }

OUT="$WORK/librigctld.so"
"$BIN/llvm-strip" --strip-all -o "$OUT" tests/rigctld

# The properties the app and its users rely on.
READELF="$BIN/llvm-readelf"
HEADER="$("$READELF" -hW "$OUT")"
SEGMENTS="$("$READELF" -lW "$OUT")"
DYNAMIC="$("$READELF" -dW "$OUT")"
SYMBOLS="$("$READELF" -W --dyn-syms "$OUT")"
has() { grep -Eq "$2" <<< "$1"; }
fail() { echo "rigctld check failed: $1" >&2; exit 1; }
has "$HEADER" 'Type: +DYN'                    || fail "not a position-independent executable"
has "$HEADER" 'Machine: +AArch64'             || fail "not arm64"
has "$SEGMENTS" 'GNU_RELRO'                   || fail "no RELRO"
has "$DYNAMIC" 'FLAGS.*BIND_NOW'              || fail "not BIND_NOW"
has "$SEGMENTS" 'GNU_STACK .* RW +0'          || fail "executable stack"
has "$SYMBOLS" '__stack_chk_fail'             || fail "no stack protector"
has "$SYMBOLS" '__(str|mem)[a-z]+_chk'        || fail "no _FORTIFY_SOURCE"
[[ "$(grep -c NEEDED <<< "$DYNAMIC")" == 3 ]] || fail "unexpected shared libraries"
for lib in libc.so libm.so libdl.so; do
  has "$DYNAMIC" "NEEDED.*\\[$lib\\]"         || fail "does not link $lib"
done
# 16 KB pages (Android 15 and later): every loadable segment aligned to them.
if awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit !bad }' <<< "$SEGMENTS"; then
  fail "segments are not 16 KB aligned"
fi
grep -aq "Hamlib $HAMLIB_VERSION " "$OUT"       || fail "wrong Hamlib version"

echo
echo "Built $OUT"
sha256sum "$OUT"

if [[ $INSTALL == 1 ]]; then
  install -m 755 "$OUT" "$ROOT/app/src/main/jniLibs/arm64-v8a/librigctld.so"
  # The copy in assets is the fallback of RigctldProcess.ensureBinary().
  install -m 755 "$OUT" "$ROOT/app/src/main/assets/rigctld"
  sha256sum "$OUT" | cut -d' ' -f1 > "$ROOT/tools/hamlib/librigctld.so.sha256"
  echo "Installed into app/src/main (sha256 recorded in tools/hamlib/librigctld.so.sha256)"
fi

# The bundled rigctld

The app runs Hamlib's `rigctld` as a child process (`RigctldProcess`). The
executable is committed as `app/src/main/jniLibs/arm64-v8a/librigctld.so`,
with a copy in `app/src/main/assets/rigctld`. Both are the output of
[`build-rigctld.sh`](build-rigctld.sh): the Hamlib 4.5.5 release tarball, the
patches in [`patches/`](patches), NDK r28c, API 26.
[`librigctld.so.sha256`](librigctld.so.sha256) is the SHA-256 of that output.

The version stays 4.5.5 on purpose. The app's CAT handling and the
`Hamlib*Test` classes are written against the behaviour of that release,
which the patches leave as it is: they only keep reads and writes inside
their buffers.

## Patches

| Patch | What it fixes |
|---|---|
| `0001-rigctld-limit-client-reads` | The command name and every argument were read from the client with a bare `%s`: a long name or argument overwrote the stack. Fixed upstream in 4.7.0. |
| `0002-send_raw-CVE-2026-54634` | `send_raw` wrote outside its reply buffer and returned stack contents (CVE-2026-54634, fixed upstream in 4.7.2); its hex parser ignored the size of its buffer. |
| `0003-read_string-GHSA-f72v-7gmh-m9mj` | A reply from the rig that began with a backslash let the read run to five times the caller's buffer (fixed upstream in 4.7.2). |
| `0004-send_cmd-reply-length` | `send_cmd_rx` used the reply length given by the client unchecked, and `send_cmd` cleared one byte past its buffer. |
| `0005-icom-frame-buffer` | Icom frames were read as if every buffer held 200 bytes; the echo check that runs when an Icom-protocol rig is opened has 16. |
| `0006-android-build` | Build fixes for Android only: no `pthread_cancel`, no `glob` before API 28, and a rotator backend that `configure` does not build. |

The binary is built with `-fstack-protector-strong` and `-D_FORTIFY_SOURCE=2`,
so an overflow that is still there ends the process instead of running on.
`RigController` shows that as `rigctld: DIED: killed by SIGABRT (6)`.

The app starts rigctld with `-T 127.0.0.1` (`RigctldCommand.kt`). rigctld has
no login; it must never listen on another address.

## Rebuilding

```bash
ANDROID_HOME=~/Android/Sdk tools/hamlib/build-rigctld.sh --install
```

The script needs NDK 28.2.13676358 (`sdkmanager "ndk;28.2.13676358"`, or
`ANDROID_NDK_HOME`). It downloads the tarball, checks its SHA-256, applies the
patches, builds, checks the result (PIE, RELRO, stack protector, 16 KB pages,
the three libraries it may link) and with `--install` copies it into the app
and updates `librigctld.so.sha256`. The build is reproducible: the same NDK
and patches give the same file, so `sha256sum` of a fresh build against
`librigctld.so.sha256` shows whether the committed binary is that build.

## Checking a change

A host build of the same sources runs the real-Hamlib tests. From the
repository root, with the tarball the script downloads:

```bash
REPO=$PWD
tar -xzf hamlib-4.5.5.tar.gz -C /tmp && cd /tmp/hamlib-4.5.5
for p in "$REPO"/tools/hamlib/patches/*.patch; do patch -p1 < "$p"; done
./configure --disable-shared --enable-static --without-cxx-binding \
  --without-libusb --without-readline --disable-winradio
make -j8
cd "$REPO"
RADE_TEST_RIGCTLD=/tmp/hamlib-4.5.5/tests/rigctld ./gradlew testDebugUnitTest --rerun
```

Configured with `CC=gcc CFLAGS="-O1 -g -fsanitize=address"
LDFLAGS=-fsanitize=address`, the same tests also report memory errors on the
paths the app uses. Then connect a rig on a device: if rigctld exits, the Rig
tab's connection error says how.

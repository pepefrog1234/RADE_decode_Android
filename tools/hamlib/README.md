# The bundled rigctld

The app runs Hamlib's `rigctld` as a child process (`RigctldProcess`). The
executable is committed as `app/src/main/jniLibs/arm64-v8a/librigctld.so`,
with a copy in `app/src/main/assets/rigctld`. Both are the output of
[`build-rigctld.sh`](build-rigctld.sh): the Hamlib 4.7.2 release tarball, the
patches in [`patches/`](patches), NDK r28c, API 26.
[`librigctld.so.sha256`](librigctld.so.sha256) is the SHA-256 of that output.

## Patches

Hamlib 4.7.2 has the fixes for CVE-2026-54634 (`send_raw`) and
GHSA-f72v-7gmh-m9mj, and limits the length of command names and arguments.
What is listed here is still in it. Each of 0001 to 0005 was reproduced on the
unpatched release with AddressSanitizer.

| Patch | What it fixes |
|---|---|
| `0001-send_raw-hex-buffer` | The hex bytes of `send_raw` were stored without regard to the size of their buffer: a client wrote past it on the stack. |
| `0002-send_cmd-reply-length` | `send_cmd_rx` used the reply length given by the client unchecked: a large or negative one overwrote the stack. |
| `0003-set_channel-description` | `set_channel` read a channel description two bytes longer than its buffer. |
| `0004-conf-value-copies` | `dump_conf` copied three configuration values, which a client can set with `set_conf`, into 128 bytes without a limit. |
| `0005-icom-frame-buffer` | Icom frames were read as if every buffer held 200 bytes; the echo check that runs when an Icom-protocol rig is opened has 16. A rig gets there by answering the first read with a bare acknowledgement. |
| `0006-scanfc-stale-errno` | A read from the client was judged by an `errno` that an earlier call had left behind: an `EINVAL` there closed the client's connection at its next command. |
| `0007-android-build` | Build fixes for Android only: no `glob` before API 28, and a rotator backend that is left out. |

The binary is built with `-fstack-protector-strong` and `-D_FORTIFY_SOURCE=2`,
so an overflow that is still there ends the process instead of running on.
`RigController` shows that as `rigctld: DIED: killed by SIGABRT (6)`.

## How the app starts it

Only with the command line of `rigctldCommand()` (`RigctldCommand.kt`):

- `-T 127.0.0.1`. rigctld has no login; it must never listen on another
  address.
- `--set-conf=poll_interval=0,cache_timeout=…`. Left to itself Hamlib 4.7
  answers reads from a 1000 ms cache and starts a thread that publishes rig
  state for multicast listeners. `RigController` polls the rig itself and
  checks a key-on 600 ms after it was sent, so the cache is set to 500 ms (0
  for the Icom network radio) and that thread is not started.

## What changed with 4.7.2

Hamlib 4.5.5 was bundled until v1.6.33. What the app had to follow:

- **QRP Labs.** Hamlib now asks the radio for its firmware version (`VN;`)
  when it opens it, and sends PKTUSB/PKTLSB as the radio's FSK (Digi) modes.
  The app tunes these rigs with plain USB/LSB (`rigModeAfterTuning`). There
  is a model of its own for the QMX (2057) next to 2052.
- **Icom.** Hamlib no longer selects VFOs or retunes to find out which VFO is
  in use when it opens the rig. `IcomTuningGuard` and `IcomCivExchange` stay:
  `HamlibIcomTuningTest` fails without the latter.

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
tar -xzf hamlib-4.7.2.tar.gz -C /tmp && cd /tmp/hamlib-4.7.2
for p in "$REPO"/tools/hamlib/patches/*.patch; do patch -p1 < "$p"; done
./configure --disable-shared --enable-static --without-cxx-binding \
  --without-libusb --without-readline --disable-winradio
make -j8
cd "$REPO"
RADE_TEST_RIGCTLD=/tmp/hamlib-4.7.2/tests/rigctld ./gradlew testDebugUnitTest --rerun
```

Configured with `CC=gcc CFLAGS="-O1 -g -fsanitize=address"
LDFLAGS=-fsanitize=address`, the same tests also report memory errors on the
paths the app uses. Then connect a rig on a device: if rigctld exits, the Rig
tab's connection error says how.

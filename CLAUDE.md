# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build signed release APK (signing credentials required)
./scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease        # Build signed release AAB (signing credentials required)
./gradlew build                # Full build (debug + release; signing credentials required)
./gradlew testDebugUnitTest    # Run unit tests
RADE_TEST_RIGCTLD=/tmp/hamlib-4.7.2/tests/rigctld ./gradlew testDebugUnitTest --rerun  # ...and the real-Hamlib integration tests
./gradlew connectedDebugAndroidTest  # Run instrumented tests (device required)
```

The `Hamlib*Test` classes are skipped unless `RADE_TEST_RIGCTLD` points at a rigctld of Hamlib 4.7.2, the version bundled in the APK (`rigctld --version` shows `SHA=40f63488f`): a host build of the patched sources, as [tools/hamlib/README.md](tools/hamlib/README.md) describes. They assert what that version does and fail against others (Ubuntu 24.04's `libhamlib-utils` is 4.5.5).

Native C++ is built automatically via CMake 3.22.1 integration in AGP. No separate native build step needed.

Follow the [release signing and Google registration guide (繁體中文)](docs/signing/README.zh-TW.md).
Use the existing release key; never regenerate it or fall back to debug signing.
Private signing material belongs only in ignored local files or CI secrets.

## Architecture

**Android app that decodes FreeDV RADE amateur radio signals in real-time.** Port of the iOS FreeDV RADE receiver.

### Signal Flow

USB/mic audio (48kHz) → Oboe capture → Polyphase FIR decimation (→8kHz) → RADE modem demodulation → FARGAN vocoder (→16kHz speech) → Oboe playback

### Layer Structure

- **UI (Jetpack Compose + Material3):** `ui/` — TransceiverScreen (main receiver with spectrum/waterfall), StationsScreen, LogScreen, SessionDetailScreen, SettingsScreen. Navigation via bottom bar in MainActivity.
- **ViewModel:** `TransceiverViewModel` binds to AudioService, exposes `StateFlow<ServiceState>` for reactive UI updates.
- **Foreground Service:** `AudioService` — keeps decoding alive when backgrounded. Manages audio lifecycle, session recording, signal snapshots.
- **JNI Bridge (two classes):**
  - `AudioBridge` — Oboe audio engine control (start/stop, USB device discovery, spectrum data, recording)
  - `RADEWrapper` — RADE modem + FARGAN vocoder init/processing
- **Native C++ (`cpp/`):**
  - `audio_engine.cpp` — Oboe streams, decimation filter, modem frame dispatch, FARGAN synthesis, WAV recording
  - `rade_jni.cpp` — JNI bindings, callbacks to Kotlin (sync state, callsign decoded)
  - `radae/` — RADE modem library (V1 + experimental V2), vendored from upstream rade_c and compiled per file
  - `opus/` — vendored Opus snapshot with FARGAN/Deep PLC enabled
  - `eoo/` — EOO callsign codec
- **Data:** `AppDatabase` uses raw SQLite (not Room) — stores ReceptionSessions, SignalSnapshots, SyncEvents, CallsignEvents.
- **Network:** `FreeDVReporter` uploads spots via OkHttp. `LocationTracker` provides GPS/grid squares.

### Native Libraries (all statically linked into `rade_jni.so`)

| Library | Source | Purpose |
|---------|--------|---------|
| rade | Vendored `radae/` ([freedv/rade_c](https://github.com/freedv/rade_c) `be66f39`) | RADE modem DSP |
| opus | Vendored `opus/` | FARGAN vocoder + DNN |
| oboe | FetchContent (Google, v1.9.0) | Low-latency audio I/O |
| eoo | Local `eoo/` | Callsign encoding/decoding |

### Updating the RADE modem (`cpp/radae/`)

- Every file that also exists in upstream `src/` is an unmodified copy (only CRLF→LF). Keep it that way: update by copying upstream files over, and put app-side fixes in the app, not in `radae/`.
- The modem must be compiled without `-ffast-math` (upstream `#error`s on it). `CMakeLists.txt` adds `-fno-fast-math` to the `rade` target only; opus and the app's own code keep the global flag.
- Upstream's opus patches (`src/opus-nnet.c.diff`, `src/opus-nnet.h.diff`) must stay applied in `cpp/opus/dnn/`; the V2 decoder aborts on sync without the `nnet.c` one.
- Real-valued modem input is scaled by `RADE_RX_REAL_SCALE` (`2 / RADE_INT16_SCALE`, see `rade_api.h`).
- After any `radae/` or `opus/` update, run the host check before building an APK (exit code 0 = V1 and V2 sync, decode and end of over under ASAN):

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Debug tools/radecheck
cmake --build build -j8 && ./build/loopback
```

### Updating the bundled rigctld (`tools/hamlib/`)

- `app/src/main/jniLibs/arm64-v8a/librigctld.so` and its copy `app/src/main/assets/rigctld` are the output of `tools/hamlib/build-rigctld.sh`: the Hamlib 4.7.2 release tarball plus `tools/hamlib/patches/`, NDK r28c. Never replace them by hand: change the patches or the script, run it with `--install`, and commit both files with `tools/hamlib/librigctld.so.sha256`. The build is reproducible, so that hash identifies the committed binary.
- rigctld has no login. Start it only with the command line from `rigctldCommand()` (`RigctldCommand.kt`), which binds it to `127.0.0.1` and sets how long Hamlib may answer from its cache (`RigController` waits longer than that before it trusts a PTT status).
- The Rig tab saves the selected model as its position in `rigModels` (`ui/RigModels.kt`): add models at the end only.
- The binary is built with stack protector and `_FORTIFY_SOURCE`: an overflow in Hamlib now ends rigctld (`DIED: killed by SIGABRT` in the Rig tab) instead of going unnoticed.
- After a change, run the `Hamlib*Test` classes against a host build of the same patched sources (see [tools/hamlib/README.md](tools/hamlib/README.md)), then connect a rig on a device.

### Key Constraints

- **ABI:** arm64-v8a only (no x86/armeabi)
- **Min SDK 26**, target/compile SDK 35
- **AGP 9.1.0** with Kotlin 2.2.10
- Raw SQLite chosen over Room to avoid KSP/AGP 9.x annotation processor issues
- Audio engine uses C++11 with `c++_shared` STL

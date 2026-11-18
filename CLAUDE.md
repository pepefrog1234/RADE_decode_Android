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
./gradlew connectedDebugAndroidTest  # Run instrumented tests (device required)
```

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

### Key Constraints

- **ABI:** arm64-v8a only (no x86/armeabi)
- **Min SDK 26**, target/compile SDK 35
- **AGP 9.1.0** with Kotlin 2.2.10
- Raw SQLite chosen over Room to avoid KSP/AGP 9.x annotation processor issues
- Audio engine uses C++11 with `c++_shared` STL

### EOO callsign codec (`cpp/eoo/`)

- `eoo_callsign_decode()` takes the number of **complex symbols** (`rade_n_eoo_bits() / 2`), never the float count. The count is the bounds check (the first 56 symbols = 112 floats are read) and the divisor of the RMS that scales the decoder's LLRs; `audio_engine.cpp` and `rade_jni.cpp` both halve the float count.
- The NDK compiles with `_FORTIFY_SOURCE=2`: an off-by-one that goes unnoticed in a plain host build aborts the arm64 one (an 8-character TX callsign did, until v1.6.34). After any change to `eoo/`, run the host tests — the first under a sanitizer — and the modem check (exit code 0 = callsigns up to the full 8 characters survive a V1 over, V2 decodes nothing):

```bash
c++ -std=c++11 -Wall -Wextra -g -fsanitize=address,undefined -Iapp/src/main/cpp/eoo \
  app/src/test/cpp/eoo_callsign_codec_test.cpp app/src/main/cpp/eoo/*.cpp \
  -o /tmp/rade-eoo-callsign-test && /tmp/rade-eoo-callsign-test
c++ -std=c++11 -O2 -Wall -Wextra -Iapp/src/main/cpp/eoo \
  app/src/test/cpp/eoo_callsign_snr_sweep.cpp app/src/main/cpp/eoo/*.cpp \
  -o /tmp/rade-eoo-snr-sweep && /tmp/rade-eoo-snr-sweep
cmake -B build -DCMAKE_BUILD_TYPE=Debug tools/radecheck
cmake --build build -j8 && ./build/eoocheck
```

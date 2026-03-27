# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew bundleRelease        # Build release AAB
./gradlew build                # Full build (debug + release)
./gradlew testDebugUnitTest    # Run unit tests
./gradlew connectedDebugAndroidTest  # Run instrumented tests (device required)
```

Native C++ is built automatically via CMake 3.22.1 integration in AGP. No separate native build step needed.

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
  - `radae/` — RADE modem library (built as unity build via `rade_all.c`)
  - `opus/` — Opus 1.9.0 with FARGAN/Deep PLC enabled
  - `eoo/` — EOO callsign codec
- **Data:** `AppDatabase` uses raw SQLite (not Room) — stores ReceptionSessions, SignalSnapshots, SyncEvents, CallsignEvents.
- **Network:** `FreeDVReporter` uploads spots via OkHttp. `LocationTracker` provides GPS/grid squares.

### Native Libraries (all statically linked into `rade_jni.so`)

| Library | Source | Purpose |
|---------|--------|---------|
| rade | Local `radae/` | RADE modem DSP |
| opus | FetchContent (Google, v1.9.0) | FARGAN vocoder + DNN |
| oboe | FetchContent (Google, v1.9.0) | Low-latency audio I/O |
| eoo | Local `eoo/` | Callsign encoding/decoding |

### Key Constraints

- **ABI:** arm64-v8a only (no x86/armeabi)
- **Min SDK 26**, target/compile SDK 35
- **AGP 9.1.0** with Kotlin 2.2.10
- Raw SQLite chosen over Room to avoid KSP/AGP 9.x annotation processor issues
- Audio engine uses C++11 with `c++_shared` STL

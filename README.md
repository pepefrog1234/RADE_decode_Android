# RADE_decode — FreeDV RADE Digital Voice Transceiver for Android

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

**English** | [繁體中文](README.zh-TW.md) | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

**RADE_decode** turns an Android phone into a complete **FreeDV RADE** digital voice
transceiver for HF amateur radio. It receives *and* transmits the RADE (Radio
Autoencoder) waveform in real time, entirely on-device — neural vocoder included —
and interoperates with stations running RADE V1 in FreeDV (freedv-gui 2.x) on PC.

Connect your phone to an HF SSB transceiver over USB audio, or go fully wireless
with an Icom IC-705 over Wi-Fi, and you have a pocket-sized RADE station with CAT
rig control, FreeDV Reporter spotting, a live station map, and automatic reception
logging.

---

## What is RADE?

[RADE](https://github.com/drowe67/radae) (Radio Autoencoder) is the
machine-learning digital voice mode developed by David Rowe and the
[FreeDV](https://freedv.org) project. Instead of a conventional codec + FEC + modem
chain, a neural network maps speech directly to OFDM symbols and back, with speech
synthesized by the **FARGAN** neural vocoder from the Opus project. The result is
speech quality far beyond legacy HF digital voice modes, and intelligible copy at
SNRs where analog SSB becomes hard work.

This app runs the full RADE V1 signal chain natively on the phone:

- **Modem:** RADE V1 OFDM, 30 carriers, processed at 8 kHz (reported to FreeDV
  Reporter as mode `RADEV1`)
- **Vocoder:** FARGAN at 16 kHz, running on the CPU — no internet or GPU required
- **Callsign:** EOO (End-Of-Over) frame encodes/decodes the station callsign at
  the end of each over

## Highlights

**Receive**
- Real-time RADE decode from any 48 kHz audio source (USB audio interface, rig
  USB codec, or built-in microphone)
- Live spectrum and waterfall with carrier markers, SNR, frequency offset, and a
  three-stage sync indicator (`SEARCH → CANDIDATE → SYNC`)
- Decoded callsigns shown on screen and in the notification
- Background decoding via a foreground service — keeps decoding with the screen
  off or while you use other apps
- Every synced session is automatically recorded to WAV (decoded speech) with
  in-app playback and sharing

**Transmit**
- Full TX: microphone → RADE OFDM waveform → rig, with your callsign embedded in
  the EOO frame
- One-tap TX/RX switching mid-session; PTT keyed automatically through CAT when a
  rig is connected
- Adjustable TX drive level with ALC setup guidance built into the UI

**Rig control (CAT)**
- Bundled **Hamlib 4.5.5 `rigctld`** (arm64 binary) — 340+ rig models, no PC needed
- Three connection modes:
  - **Serial (local):** USB CAT cable via OTG — CDC-ACM, CP210x, FTDI, Prolific,
    CH340/CH341 chips supported, with DTR/RTS control
  - **TCP (rigctld):** any remote rigctld-compatible server, including a
    **Hermes-Lite 2** profile for Thetis / Quisk / SparkSDR / piHPSDR
  - **Wi-Fi (IC-705):** Icom's native network protocol (RS-BA1) — CAT *and* TX/RX
    audio over Wi-Fi, no cable at all
- Live frequency / mode / PTT / S-meter display, frequency entry with automatic
  sideband and data-mode selection (USB/LSB, PKTUSB/PKTLSB)

**FreeDV Reporter integration**
- Two-way connection to [qso.freedv.org](https://qso.freedv.org): your RX spots
  (callsign + SNR), TX status, frequency, and a free-text status message are
  reported live
- **Stations** tab: all stations currently online, filterable by band
  (160 m – VHF+)
- **Map** tab: OpenStreetMap world map with TX/RX/idle markers, great-circle
  signal paths (solid = callsign confirmed via EOO, dashed = inferred from
  matching frequency) and per-path SNR badges
- GPS-derived Maidenhead grid square, updated automatically

**Quality-of-life**
- Reception log: every session stored in a local database with SNR-over-time
  chart, sync event timeline, decoded callsign list, and signal statistics
- **Power Save Mode** for low-end devices (skips FFT/spectrum rendering so the
  decoder always gets the CPU it needs)
- Battery-optimization setup assistant with vendor-specific instructions
  (Samsung, Xiaomi, Huawei, OPPO, vivo, …)
- UI in English, 繁體中文, 简体中文, and 日本語

## Requirements

| | Minimum |
|---|---|
| Android | 8.0 (API 26) or newer |
| CPU | 64-bit ARM (**arm64-v8a only** — no x86 or 32-bit support) |
| Performance | The FARGAN neural vocoder runs on the CPU; mid-range phones from ~2018 on are fine. On very weak devices enable **Settings → Power Save Mode**. |
| Radio | Any HF SSB transceiver. For single-cable operation use a rig with a built-in USB audio codec + CAT (e.g. IC-7300, IC-705, many recent rigs); otherwise a USB audio interface / digital interface (DigiRig etc.) between phone and rig. |
| Accessories | USB-OTG adapter for USB audio / USB serial. Nothing at all for IC-705 Wi-Fi mode. |

> Receive-only works fine without any rig connection: feed audio in through the
> microphone (acoustic coupling) or a USB sound card and just listen.

## Installation

1. Download the latest APK from the
   [**Releases**](https://github.com/pepefrog1234/RADE_decode_Android/releases) page.
2. Allow "install from unknown sources" when prompted and install the APK.
3. On first launch, grant the microphone permission and follow the
   battery-optimization dialog so decoding survives in the background.

The app is not on the Play Store; updates are published as GitHub releases.

## Quick start — receiving

1. **Connect audio.** Options, best first:
   - *USB audio:* plug the rig (or audio interface) into the phone with an OTG
     adapter. The device appears under **Settings → Audio Devices → Input** —
     select it.
   - *IC-705 Wi-Fi:* see [Wi-Fi (IC-705)](#wi-fi-ic-705) below — audio arrives over
     the network, no cable.
   - *Microphone:* hold the phone near the rig's speaker. Works, but USB audio is
     far more reliable.
2. **Tune the rig** to RADE activity, e.g. around the 20 m FreeDV calling
   frequency **14.236 MHz USB**. The **Stations** tab and
   [qso.freedv.org](https://qso.freedv.org) show who is on the air right now and
   where.
3. Open the **Receiver** tab and press **START**.
4. Watch the **INPUT** meter: adjust rig output / **Settings → Input Gain** until
   peaks sit around **−15 to −5 dBFS**.
5. When a RADE signal appears, the header steps `SEARCH → SYNC`,
   speech starts playing, and the sender's callsign appears once their over ends
   (that's when the EOO frame is sent).

A session is created automatically whenever sync is acquired, logged to the
**Log** tab, and its decoded audio saved as a WAV file you can replay or share.

## Quick start — transmitting

> You need a valid amateur radio license to transmit.

1. **Settings → TX Callsign:** enter your callsign (max 8 characters) — it is
   encoded into the EOO frame on every over.
2. **Settings → Audio Devices → Output:** select the USB audio device that feeds
   the rig (skip for IC-705 Wi-Fi — TX audio goes over the network).
3. Press **START**, then tap **TX**. Speak into the phone's microphone. Tap
   **BACK TO RX** to end the over (this also transmits the EOO callsign frame).
4. **Set your levels** — the goal is *little to no ALC deflection* on the rig:
   - Phone **volume keys** and **Settings → RX Output → Volume** scale the
     overall output,
   - **Settings → TX Output → USB TX level** sets the drive into the rig's USB
     audio input,
   - also check the rig's own USB MOD level (IC-7300: `Menu → SET → Connectors →
     USB MOD Level`).
5. PTT is keyed automatically if a rig is connected on the **Rig** tab; otherwise
   use the rig's VOX or key it manually.

## Rig control (CAT)

Open the **Rig** tab and pick one of three connection modes. Once connected you
get a live frequency readout, mode, S-meter, and a frequency entry box — entering
a frequency also selects the correct sideband and data mode automatically
(LSB/PKTLSB below 10 MHz, USB/PKTUSB above; plain USB/LSB fallback for rigs
without data modes).

### Serial (local) — USB CAT cable

The app ships a real Hamlib 4.5.5 `rigctld` for arm64 and runs it on the phone,
bridged to the USB cable through a native pty driver.

1. Plug in the CAT cable (CDC-ACM, CP210x, FTDI, Prolific, and CH340/CH341
   chips are supported; composite devices like the Xiegu X6100 list each port
   separately).
2. Pick your **Manufacturer / Model** (340+ Hamlib models, searchable by name or
   Hamlib ID), set the **baud rate**, and for Icom rigs optionally the **CI-V
   address** (hex, usually auto).
3. **DTR/RTS:** DTR defaults on, RTS defaults off — *leave RTS off if your CAT
   cable wires RTS to PTT*, or the rig may key up the moment you connect.
4. Tap **CONNECT** and grant the USB permission.

Xiegu rigs (G90, X6100, X5105, X108G) get tightened CI-V timeouts automatically
so PTT stays responsive.

### TCP (rigctld) — remote server

Point the app at any `rigctld`-compatible TCP server (default port 4532): a PC
running `rigctld`, FLRig, or an SDR application. Choose the **Hermes-Lite 2**
profile to connect to the rigctl server in Thetis, Quisk, SparkSDR, or piHPSDR —
note that audio still flows through Android audio devices (no OpenHPSDR I/Q
streaming).

### Wi-Fi (IC-705)

Fully wireless operation using the IC-705's built-in network server (Icom RS-BA1
protocol, UDP ports 50001/50002/50003):

1. On the radio: `Set ▸ WLAN ▸ Network Control = ON`, and set a Network
   user/password.
2. Join the phone to the same network (or the radio's access point), enter the
   radio's IP address, username, and password, then **CONNECT**.
3. CAT control *and* 48 kHz TX/RX audio both run over Wi-Fi — the "Audio
   (Wi-Fi)" indicator turns green. No USB cable, no audio interface.

## FreeDV Reporter, Stations & Map

With **Settings → FreeDV Reporter** enabled and a callsign set, the app maintains
a live connection to qso.freedv.org:

- Everything you decode is spotted (callsign, SNR, frequency), including a live
  "receiving now" heartbeat while you are synced — other operators see your dot
  react in real time.
- Your TX status and rig frequency (when CAT-connected) are reported, plus an
  80-character free-text status message.
- Without a callsign the app connects in view-only mode — you still see everyone
  else.

The **Stations** tab lists all online stations with grid, frequency, and TX
indication, filterable by band. The **Map** tab plots them on a world map:

| Marker / line | Meaning |
|---|---|
| 🔴 red marker | station transmitting |
| 🟢 green marker | station receiving (frequency + SNR shown) |
| ⚪ gray marker | idle / monitoring |
| solid red→green arc | confirmed path — RX decoded the TX station's EOO callsign |
| blue dashed arc | inferred path — RX synced on the same frequency (±5 kHz) |
| SNR badge | reported SNR at the path midpoint, color-coded |

## Reception log

The **Log** tab keeps every reception session (sessions split automatically when
sync is lost for more than ~2 s). Tap a session for:

- Start/end time, duration, sync ratio, modem frame counts, audio device used
- **Playback and sharing of the decoded audio WAV**
- All callsigns decoded, with SNR and frame position at decode time
- SNR-over-time chart with sync-state shading, sync event timeline, and signal
  statistics (peak/average SNR, frequency offset range)

Recordings are stored in app-private storage and shared through the standard
Android share sheet.

## Settings reference

| Setting | Default | What it does |
|---|---|---|
| Audio Devices → Input / Output | first USB device | Select capture and playback devices; Refresh rescans |
| Input Gain → Digital Gain | 4.0× (12 dB) | 0.1–30× software gain for weak USB inputs; aim for −15…−5 dBFS peaks |
| RX Output → Volume | 100 % | Decoded speech level; also scales TX output |
| TX Output → USB TX level | 20 % | RADE waveform drive into the rig's USB audio input |
| TX Callsign → Callsign (EOO) | — | Up to 8 characters, sent in the EOO frame on every over |
| FreeDV Reporter → Enable Reporter | on | Live connection to qso.freedv.org |
| FreeDV Reporter → Grid Square | from GPS | 6-character Maidenhead locator; auto-filled if location permission granted |
| FreeDV Reporter → Status message | — | Free text (≤ 80 chars) shown next to your station on qso.freedv.org |
| Performance → Power Save Mode | off | Hides spectrum/waterfall **and stops FFT processing** so weak devices decode smoothly |

## Troubleshooting

- **Signal visible on the waterfall but never syncs** — check the sideband (USB
  above 10 MHz, LSB below), then the input level (−15…−5 dBFS). Too-hot or
  too-quiet audio is the most common cause.
- **Orange warning "device rejected Unprocessed preset"** — your phone insists on
  applying voice processing (AEC/AGC) to the selected input, which can break
  decoding (seen e.g. on Samsung Galaxy S24 internal mics). Use a USB audio
  input instead.
- **Audio breaks up / robotic on a budget device** — enable **Power Save Mode**;
  spectrum drawing and FFT are the main CPU load besides the vocoder.
- **Decoding stops when the screen turns off** — battery optimization is killing
  the service. Use the in-app battery dialog (shown on first launch) and the
  vendor-specific steps it lists.
- **Rig keys PTT the moment the serial cable connects** — your CAT cable wires
  RTS to PTT. Turn **RTS off** in the Rig tab.
- **No USB serial device found** — confirm the phone supports USB-OTG, try
  **Refresh**, and accept Android's USB permission dialog.
- **IC-705 Wi-Fi won't connect** — Network Control must be ON, the username and
  password must match the radio's Network User settings, and the phone must reach
  the radio's IP (same network / radio AP mode).
- **Reporter shows "Not connected"** — set a callsign in Settings and check
  **Enable Reporter**; the Stations tab shows the exact reason.

## Permissions

| Permission | Why |
|---|---|
| Microphone (`RECORD_AUDIO`) | Capture RX audio from USB/mic; TX microphone |
| Internet / network state | FreeDV Reporter (qso.freedv.org), IC-705 Wi-Fi, TCP rigctld |
| Location (fine/coarse, optional) | Auto-calculate your Maidenhead grid for the Reporter |
| Foreground service (microphone, media playback) | Keep decoding while backgrounded |
| USB host (hardware feature, optional) | USB audio interfaces and CAT cables |

No storage permission is needed — recordings live in app-private storage and are
exported via the share sheet.

## Architecture (for developers)

```
RX:  USB / mic / IC-705 Wi-Fi (48 kHz)
      → Oboe capture
      → polyphase FIR decimation (→ 8 kHz)
      → RADE V1 OFDM demodulation (+ EOO callsign decode)
      → FARGAN neural vocoder (→ 16 kHz speech)
      → Oboe playback (+ WAV session recording)

TX:  microphone (48 kHz)
      → feature extraction → RADE OFDM modulation (8 kHz)
      → polyphase interpolation (→ 48 kHz)
      → USB audio / IC-705 Wi-Fi → rig (PTT via CAT)
```

| Layer | Where | Notes |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | Receiver, Rig, Stations, Map (osmdroid), Log, Settings |
| ViewModel | `TransceiverViewModel` | Binds to the service, exposes `StateFlow<ServiceState>` |
| Service | `service/AudioService` | Foreground service (mic + media playback types); session lifecycle, snapshots, recording |
| JNI bridge | `AudioBridge`, `RADEWrapper` | Oboe engine control / RADE + FARGAN processing |
| Native | `app/src/main/cpp/` | `audio_engine.cpp` (Oboe streams, decimation, frame dispatch), `rade_jni.cpp`, `radae/` (RADE modem, unity build), `opus/` (FARGAN), `eoo/` (callsign codec) |
| Rig control | `network/`, `usb/` | Bundled Hamlib 4.5.5 `rigctld` (arm64) + native USB-serial→pty bridge; Icom RS-BA1 UDP client in Kotlin |
| Data | `data/` | Raw SQLite (no Room): sessions, signal snapshots, sync events, callsign events |
| Reporter | `network/FreeDVReporter` | Socket.IO v4 over WebSocket via OkHttp |

### Building from source

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

- Android Studio with AGP 9.1 / Kotlin 2.2.10, JDK 17+
- NDK + CMake 3.22.1 are driven automatically by the Gradle build — no separate
  native build step
- First native build downloads Opus 1.9.0 and Oboe 1.9.0 via CMake FetchContent
  (network required once)
- Output is **arm64-v8a only** — test on a real device or an arm64 emulator image

## License & acknowledgements

This application is licensed under the **GNU Lesser General Public License
v2.1** (see [LICENSE](LICENSE)).

Standing on the shoulders of:

| Project | Author / origin | License |
|---|---|---|
| [RADE modem](https://github.com/drowe67/radae) | David Rowe & the FreeDV project | BSD 2-Clause |
| Opus / FARGAN vocoder | Xiph.Org Foundation | BSD 3-Clause |
| EOO callsign codec | Codec2 / FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/) (`rigctld`) | The Hamlib Group | LGPL 2.1+ |
| [Oboe](https://github.com/google/oboe) | Google | Apache 2.0 |
| kiss_fft | Mark Borgerding | BSD 3-Clause |
| OkHttp | Square | Apache 2.0 |
| osmdroid | osmdroid contributors | Apache 2.0 |
| [FreeDV Reporter](https://qso.freedv.org) | FreeDV project | — |
| App icon design | [megabits.xyz](https://megabits.xyz) | — |

The app began as an Android port of the iOS FreeDV RADE receiver and has grown
into a full transceiver.

> **Disclaimer:** transmitting requires a valid amateur radio license in your
> jurisdiction. Receive-only operation is generally license-free, but check your
> local regulations.

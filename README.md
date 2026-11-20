# RADE_decode — FreeDV RADE Digital Voice Transceiver for Android

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
[![Release](https://img.shields.io/github/v/release/pepefrog1234/RADE_decode_Android)](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

**English** | [繁體中文](README.zh-TW.md) | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

**RADE_decode** turns an Android phone or tablet into a complete **FreeDV RADE**
digital voice station for HF amateur radio. It receives *and* transmits the RADE
(Radio Autoencoder) waveform in real time, entirely on the device, neural vocoder
included, and it works with stations that run RADE V1 in FreeDV on a PC
(freedv-gui 2.x).

Plug the phone into an SSB transceiver with a USB cable, or use no cable at all:
an Icom radio's network server over Wi-Fi, a **Hermes-Lite 2** on your LAN with
the phone as the SDR host, or Thetis on a PC through VBAN audio. Rig control,
FreeDV Reporter spotting, a live station list and map, and a reception log are
built in.

> This page describes **v1.6.37**. What each version changed is in the
> [release notes](docs/release-notes/).

---

## What is RADE?

[RADE](https://github.com/drowe67/radae) (Radio Autoencoder) is the
machine-learning digital voice mode developed by David Rowe and the
[FreeDV](https://freedv.org) project. Instead of a conventional codec + FEC + modem
chain, a neural network maps speech directly to OFDM symbols and back, and the
speech is synthesized by the **FARGAN** neural vocoder from the Opus project. The
result is speech quality far beyond legacy HF digital voice modes, and
intelligible copy at SNRs where analog SSB becomes hard work.

The app runs the whole signal chain natively on the phone, using the FreeDV
project's C implementation ([rade_c](https://github.com/freedv/rade_c)):

- **Modem:** RADE V1 OFDM, 30 carriers, processed at 8 kHz. FreeDV Reporter lists
  it as mode `RADEV1`.
- **Vocoder:** FARGAN at 16 kHz, running on the CPU. No internet connection or
  GPU is needed.
- **Callsign:** the EOO (End-Of-Over) frame carries the station's callsign at the
  end of each over.
- **RADE V2 (experimental):** the newer waveform can be switched on in Settings.
  V1 and V2 stations cannot hear each other, and V2 carries no callsign. Leave it
  off for normal operation.

## Features

**Receive**
- Real-time RADE decoding from a 48 kHz audio source: the rig's USB codec, a USB
  audio interface, one of the network modes below, or the built-in microphone
- Spectrum and waterfall with carrier markers, SNR, frequency offset, and a
  three-stage sync indicator (`SEARCH → CANDIDATE → SYNC`)
- The decoded callsign on screen and in the notification
- **Analog monitor:** hear the raw channel audio to check that the frequency is
  free before you key up
- Decoding continues in the background, with the screen off or while you use
  other apps
- Decoded speech on the phone speaker, wired or USB audio, or a Bluetooth headset
  or speaker, including **LE Audio (LC3)**; you can choose the output device and
  test it
- Every synced session is recorded to a WAV file, with playback and sharing in
  the app

**Transmit**
- Microphone → RADE waveform → rig, with your callsign in the EOO frame
- Tap **TX** to toggle, or use **Hold-to-Talk**. The TX button is also on the
  Stations and Map tabs, so you can watch who receives you while you talk
- PTT through CAT: the app reads the radio's own transmit status back and
  repeats a key-on that the radio missed. Interfaces without CAT can be keyed
  with the RTS line; otherwise use the rig's VOX
- Built-in, USB/wired, or (experimental) Bluetooth microphone, with adjustable
  gain. If the microphone is lost during an over, the app unkeys the rig and
  returns to receive
- TX level controls, with ALC guidance in the UI

**Radio connections** (Rig tab)
- **Serial (local):** a USB CAT cable and the bundled **Hamlib 4.7.2** `rigctld`:
  180+ rig models, no PC needed
- **TCP (rigctld):** a rigctld-compatible server on your network, with a
  **Hermes-Lite 2** profile for the CAT servers of Thetis, piHPSDR, SparkSDR, and
  Quisk
- **Wi-Fi (IC-705):** Icom's own network protocol. CAT and audio both run over
  Wi-Fi, and over LTE through a VPN (in use with the IC-705 and IC-7300MK2)
- **HL2 (LAN):** a direct **openHPSDR (protocol 1)** connection to a Hermes-Lite 2.
  The phone is the SDR host: PA switch, LNA gain, automatic N2ADR filter
  selection, and experimental PureSignal
- **Thetis (VBAN):** audio over Wi-Fi to and from Voicemeeter on a PC, so Thetis
  stays in charge of the radio, PureSignal included
- Live frequency, mode, PTT, and S-meter; a frequency entry that selects the
  sideband and data mode for you; and a manual frequency for FreeDV Reporter
  when no rig is connected

**FreeDV Reporter**
- Live connection to [qso.freedv.org](https://qso.freedv.org): your RX reports
  (callsign and SNR), TX status, frequency, and a free-text status message
- **Stations** tab: every station that is online, filtered by band
  (160 m – VHF+) and sorted by frequency, callsign, or latest report. Each row
  shows whether the station is transmitting or what it is receiving
- **Map** tab: an OpenStreetMap world map with TX/RX/idle markers, great-circle
  signal paths (red-to-green when the callsign was decoded, blue dashed when
  inferred from a matching frequency), and an SNR badge on each path
- Maidenhead grid square from GPS, or typed in

**Also**
- Reception log: every session with an SNR chart, a sync timeline, the decoded
  callsigns, and signal statistics
- **Power Save Mode** for low-end devices: no spectrum, waterfall, or FFT, so the
  decoder gets the CPU it needs
- Battery-optimization assistant with vendor-specific steps (Samsung, Xiaomi,
  Huawei, OPPO, vivo, ASUS, Sony)
- In-app capture of the CAT and audio logs for bug reports
- UI in English, 繁體中文, 简体中文, and 日本語

## Requirements

| | Minimum |
|---|---|
| Android | 8.0 (API 26) or newer |
| CPU | 64-bit ARM (**arm64-v8a only**; there is no x86 or 32-bit build) |
| Performance | The FARGAN neural vocoder runs on the CPU. A mid-range phone is enough; on older or low-end devices turn on **Settings → Performance → Power Save Mode**. |
| Radio | Any HF SSB transceiver. One cable is enough for a rig with a built-in USB audio codec and CAT (IC-7300, IC-705, and many recent rigs). Other rigs need a USB audio or digital interface (DigiRig and similar) between phone and rig. An Icom radio with a network server, or a **Hermes-Lite 2**, needs no audio interface at all. |
| Accessories | A USB-OTG adapter for USB audio and USB serial. Nothing for the network modes. |

> Receive-only works without any rig connection: feed audio in through the
> microphone (acoustic coupling) or a USB sound card and just listen.

## Installation

1. Download **`app-release.apk`** from the
   [latest release](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest).
2. Allow installation from this source when Android asks, then install the APK.
3. The first time you press **START**, grant the microphone permission. Android 12
   and later also asks for *Nearby devices*, which Bluetooth audio needs; it is
   optional.
4. Follow the battery-optimization dialog so that decoding keeps running in the
   background.

The app is not on Google Play; updates are published as GitHub releases.

**Updating.** Every release since v1.6.21 is signed with the same key, so a newer
APK installs over the older one and keeps your data. Two cases need an uninstall
first, and uninstalling deletes the app's recordings, log, and settings:

- moving up from v1.6.20 or earlier, which were debug builds signed with a
  different key;
- going back to an older version.

Save the recordings you want to keep before you uninstall. See the
[signing and backup guide (繁體中文)](docs/signing/README.zh-TW.md).

## Connecting a radio

Open the **Rig** tab and choose how the phone reaches the radio. This table shows
what carries the audio and what keys the transmitter in each setup.

| Your setup | Audio | Control and PTT | Mode on the Rig tab |
|---|---|---|---|
| Rig with USB audio and CAT on one cable | USB | Hamlib, over the same cable | [Serial (local)](#serial-local) |
| USB audio interface plus a CAT cable | USB | Hamlib, over the CAT cable | [Serial (local)](#serial-local) |
| Interface that keys the rig with RTS, no CAT | USB | RTS line | [Serial (local)](#serial-local) with **RTS PTT** |
| No control cable at all | USB or microphone | The rig's VOX, or by hand | — |
| PC or SDR program that serves rigctld | Android audio devices | TCP | [TCP (rigctld)](#tcp-rigctld) |
| Icom radio with a network server | Network | Network | [Wi-Fi (IC-705)](#icom-network) |
| Hermes-Lite 2 | Network (I/Q) | Network | [HL2 (LAN)](#hl2-lan) |
| Thetis and Voicemeeter on a PC | VBAN over Wi-Fi | Thetis CAT server, or VOX | [Thetis (VBAN)](#thetis-vban) |

Once a rig is connected you get a live frequency readout, the mode, an S-meter,
and a frequency box. Enter a frequency in kHz and tap **SET**: the app tunes the
rig and selects the sideband and data mode (LSB/PKTLSB below 10 MHz, USB/PKTUSB
above; plain USB/LSB for rigs without data modes). With no rig connected, **SET**
keeps the value as your manual frequency, and that is what FreeDV Reporter shows
for your station.

The CAT daemon inside the app accepts connections from the phone itself only
(127.0.0.1). Other devices on your network cannot reach it.

<a id="serial-local"></a>
### Serial (local) — USB CAT cable

The app ships a real Hamlib 4.7.2 `rigctld` for arm64 and runs it on the phone,
bridged to the USB cable through a native pty driver.

1. Plug in the CAT cable. CDC-ACM, CP210x, FTDI, Prolific, and CH340/CH341 chips
   are supported; composite devices such as the Xiegu X6100 list each port
   separately.
2. Pick your **Manufacturer** and **Model** (180+ Hamlib models, searchable by
   name or Hamlib ID), set the **Baud** rate, and for Icom rigs optionally the
   **CI-V** address (hex; leave it empty for auto).
3. **DTR/RTS:** DTR defaults to on and RTS to off. *Leave RTS off if your CAT
   cable wires RTS to PTT*, or the rig may key up the moment you connect.
4. Tap **CONNECT** and grant the USB permission.

**RTS PTT.** For an interface that keys the rig with the RTS line and has no CAT,
turn on **RTS PTT (key TX via RTS)** and leave the RTS switch off. Pressing TX
then asserts RTS, and the app releases it after the over, once the callsign has
gone out.

Xiegu rigs (G90, X6100, X6200, X5105, X108G) get shorter CI-V timeouts
automatically, so PTT stays responsive.

<a id="tcp-rigctld"></a>
### TCP (rigctld) — a server on your network

Point the app at any `rigctld`-compatible TCP server (default port 4532): a PC
running `rigctld`, or an SDR program that serves the same protocol. Audio still
goes through the Android audio devices in this mode.

The **Hermes-Lite 2** profile connects to the CAT server of the SDR program that
runs the radio: **Thetis** (its TCP/IP CAT server), **piHPSDR** (its RigCtl port,
19090 by default), or **Spark/Quisk** (SparkSDR and Quisk, which speak the
rigctld protocol). For a Hermes-Lite 2 with no PC at all, use
[HL2 (LAN)](#hl2-lan) instead.

<a id="icom-network"></a>
### Wi-Fi (IC-705) — Icom network control

Fully wireless operation through the radio's built-in network server (Icom's
RS-BA1 protocol, UDP ports 50001 to 50003). It is used with the IC-705 and the
IC-7300MK2.

1. On the radio, turn **Network Control** on in the network settings and set a
   network user name and password.
2. Put the phone on the same network (or on the radio's own access point), enter
   the radio's IP address, the user name, and the password, then tap **CONNECT**.
3. CAT control and the TX/RX audio both run over the network. The
   "Audio (Wi-Fi)" indicator shows *connected*. No USB cable, no audio
   interface.

Options on the Rig tab, applied at the next connect:

- **Audio buffer (network latency):** 150 ms is the radio's default; 200 or
  300 ms can smooth out LTE or VPN jitter.
- **TX audio 16 kHz (experimental):** sends one third of the uplink data. Worth
  trying when an LTE uplink stalls.
- **RX audio 16 kHz (experimental):** asks the radio for 16 kHz receive audio. If
  you get no audio, turn it off and reconnect.

**Away from home.** The radio can be reached over LTE through a VPN into its
network. The radio needs a default gateway, and the VPN must either give the
phone an address inside the radio's subnet or NAT it into that subnet. If the
link drops, the app reconnects by itself and returns to receive.

<a id="hl2-lan"></a>
### HL2 (LAN) — Hermes-Lite 2, no PC

A direct **openHPSDR (protocol 1)** connection to a Hermes-Lite 2 on your
network. The phone is the SDR host:

1. Put the HL2 on the same LAN as the phone (Ethernet to your router or access
   point).
2. On the **Rig** tab pick **HL2 (LAN)**. Leave **Host** empty for broadcast
   auto-discovery, or enter the radio's IP address, then tap **CONNECT**.
3. Frequency and PTT are controlled from the app. The 48 kHz I/Q stream runs both
   ways on UDP port 1024, and the app does the SSB modulation and demodulation
   itself.

Controls on the Rig tab:

- **TX drive** (0–255, default 128). At 0 the radio keys with no output.
- **RX LNA gain** (−12 to +48 dB).
- **PA (5 W output):** on by default. Off uses the low-power jack (about 10 mW)
  and leaves the antenna jack on receive, for a transverter or an external
  amplifier.
- **PureSignal (experimental):** adaptive predistortion with the WDSP engine that
  Thetis uses. Off by default, and effective with the PA on.

The filters of an N2ADR filter board are selected from the frequency
automatically.

Notes:
- The Audio Devices settings are **not used** in this mode. RX and TX audio ride
  the network.
- "Radio is busy" during connect means another host (Thetis, Quisk, SparkSDR, …)
  is already streaming from the HL2. Close it and reconnect.

<a id="thetis-vban"></a>
### Thetis (VBAN) — audio over Wi-Fi to a PC

For stations where Thetis on a PC runs the radio. The phone's RX and TX audio
travel over Wi-Fi to [Voicemeeter](https://vb-audio.com/Voicemeeter/) on the PC
with the VBAN protocol, and Voicemeeter's virtual cables are Thetis's VAC. Thetis
keeps doing what it does, PureSignal included, and no sound card or cable sits
between phone and PC.

1. In Voicemeeter, set up an **outgoing** VBAN stream (48 kHz, PCM 16-bit) to the
   phone's IP address, and select the **incoming** stream named `RADE-TX` for the
   transmit direction.
2. On the **Rig** tab pick **Thetis (VBAN)** and enter the PC's address as
   **Host**, the **VBAN port** (6980 by default), and the **CAT port** of the SDR
   program's CAT server. Leave the CAT port empty or 0 to work without CAT and
   use VOX.
3. Choose the SDR program (Thetis, piHPSDR, or Spark/Quisk) and tap **CONNECT**.

The [v1.5.62 release notes](docs/release-notes/v1.5.62.md) (日本語) walk through
the PC side step by step.

## Receiving

1. **Connect audio.** Options, best first:
   - *USB audio:* plug the rig or audio interface into the phone with an OTG
     adapter, then select it under **Settings → Audio Devices → Input Devices**.
   - *A network mode:* with Wi-Fi (IC-705), HL2 (LAN), or Thetis (VBAN) connected
     on the Rig tab, the receive audio arrives over the network.
   - *Microphone:* hold the phone near the rig's speaker. It works, but USB audio
     is far more reliable.
2. **Tune the rig** to RADE activity, for example the 20 m FreeDV calling
   frequency **14.236 MHz USB**. The **Stations** tab and
   [qso.freedv.org](https://qso.freedv.org) show who is on the air and where.
3. Open the **Receiver** tab and press **START**.
4. Watch the **INPUT** meter. Adjust the rig's output or **Settings → Input Gain →
   Digital Gain** until the peaks sit around **−15 to −5 dBFS**.
5. When a RADE signal appears, the header steps through
   `SEARCH → CANDIDATE → SYNC` and speech starts playing. The sender's callsign
   appears under *Last decoded callsign* once their over ends, because that is
   when the EOO frame is sent.

**Analog monitor.** While receiving, **ANALOG MONITOR** plays the raw channel
audio in place of the decoded speech, so you can hear whether an analog QSO is
using the frequency before you transmit. The sync indicator stays live. Tap the
button again to go back to RADE.

A session starts whenever sync is acquired. It is listed on the **Log** tab, and
its decoded audio is saved as a WAV file that you can replay or share.

## Transmitting

> You need a valid amateur radio license to transmit.

1. **Settings → TX Callsign:** enter your callsign (up to 8 characters: letters,
   digits, and `/`). It goes out in the EOO frame at the end of every over, and
   FreeDV Reporter needs it too.
2. **Settings → Audio Devices → TX Output Device:** select the USB audio device
   that feeds the rig. Skip this in the network modes, where the TX audio goes
   over the network.
3. Press **START**, then tap **TX** and speak into the microphone. Tap
   **BACK TO RX** to end the over: the callsign frame goes out, and then PTT is
   released.
4. **Set your levels.** The goal is *little or no ALC deflection* on the rig:
   - the phone's **volume keys** (the Android media volume) and **Settings → TX
     Output → USB TX level** set the drive into the rig's USB audio input;
   - **Settings → TX Mic → TX Mic Gain** changes how loud your voice is, not the
     RF drive;
   - check the rig's own USB modulation level as well (IC-7300:
     `Menu → SET → Connectors → USB MOD Level`);
   - in the network modes the drive is set on the radio, or with **TX drive** for
     the HL2.
5. **PTT.** With a rig connected on the **Rig** tab, the app keys it for you.
   Without rig control, use RTS PTT, the rig's VOX, or key it by hand.

**Hold-to-Talk.** With **Settings → PTT → Hold-to-Talk PTT** on, the TX button
works like a walkie-talkie: hold it to transmit, release it to receive.

**Transmitting from Stations and Map.** While the receiver is running, these two
tabs show the same TX button in a bar above the navigation bar. Beside it are the
sync state, the SNR, and your frequency on receive, and the time on air and the
microphone level on transmit. A held Hold-to-Talk button stays held when you
switch between the two tabs; leaving them releases PTT.

## FreeDV Reporter, Stations, and Map

With **Settings → FreeDV Reporter → Enable Reporter** on (the default) and a
callsign set, the app keeps a live connection to qso.freedv.org:

- What you decode is reported (callsign and SNR), with a "receiving now"
  heartbeat while you are synced, so other operators see your station react in
  real time.
- Your TX status and frequency are reported. The frequency comes from the
  connected rig; with no rig, it is the one you set on the Rig tab.
- Your grid square comes from GPS (tap **GPS** next to the field) or is typed in.
  If it is empty the app reports `UN00KN`, and your station is not drawn on the
  map.
- A status message of up to 80 characters is shown next to your station.
- Without a callsign the app does not connect, and the Stations tab says why.

The **Stations** tab lists every online station with its grid, frequency, and
what it is doing: `TX`, or what it receives (`RX K1ABC 12 dB`). Filter the list
by band, and sort it by frequency (the default: the stations on one frequency
are grouped under a header), callsign, or latest report. The **Map** tab plots
the stations on a world map:

| Marker / line | Meaning |
|---|---|
| 🔴 red marker | station transmitting |
| 🟢 green marker | station receiving (frequency and SNR shown) |
| ⚪ gray marker | idle / monitoring |
| red→green arc | confirmed path: the receiver decoded the transmitter's callsign |
| blue dashed arc | inferred path: the receiver is synced on the same frequency (±5 kHz) |
| SNR badge | the reported SNR at the middle of the path, color-coded |

## Reception log

The **Log** tab keeps every reception session. A session ends when sync has been
lost for about 2 seconds. Tap a session for:

- Start and end time, duration, sync ratio, modem frame counts, and the audio
  device used
- **Playback and sharing of the decoded audio**
- Every callsign decoded, with its SNR and position in the session
- An SNR-over-time chart, the sync event timeline, and signal statistics (peak
  and average SNR, frequency offset range)

Recordings are kept in app-private storage and shared through the standard
Android share sheet.

## Settings reference

In the order of the **Settings** tab. The options of each connection mode are on
the Rig tab and are described under [Connecting a radio](#connecting-a-radio).

| Setting | Default | What it does |
|---|---|---|
| Audio Devices → Input Devices | A USB input when one is connected, otherwise the built-in mic | The capture device for receive. **Refresh** rescans. |
| Audio Devices → TX Output Device | A USB output when one is connected | Where the RADE transmit waveform is played. |
| Input Gain → Digital Gain | 4.0× | 0.1–30× software gain for weak USB inputs; aim for −15 to −5 dBFS peaks. Receive only. |
| RX Output → Volume | 100% | Level of the decoded speech. |
| RX Output → RX output device | Auto | *Auto* uses wired audio when it is connected and the phone speaker otherwise. Pick a Bluetooth device to play there. *System default* leaves the routing to Android. |
| RX Output → Test selected RX output | — | Plays a 1 kHz tone and shows where Android actually routed it. |
| TX Output → USB TX level | 20% | Drive of the RADE waveform into the rig's USB audio input. |
| TX Output → Android media volume | — | USB and speaker TX also follow the media volume. The volume keys change it. |
| TX Callsign → Callsign (EOO) | — | Up to 8 characters, sent in the EOO frame of every over. FreeDV Reporter needs it. |
| PTT → Hold-to-Talk PTT | Off | Transmit only while the TX button is held. |
| TX Mic | Built-in mic | The built-in microphone, or a USB or wired one. |
| TX Mic → TX Mic Gain | 4.0× | 0.5–15× gain into the RADE encoder. The TX meter reads after this gain. |
| Bluetooth Mic (TX) | Off | Experimental: take the TX voice from a Bluetooth headset. LE Audio (LC3) is used when the headset has it, classic SCO otherwise. |
| FreeDV Reporter → Enable Reporter | On | Live connection to qso.freedv.org. |
| FreeDV Reporter → Grid Square | — | Maidenhead locator. **GPS** fills it from your position; empty is reported as `UN00KN`. |
| FreeDV Reporter → Status message | — | Free text (up to 80 characters) shown next to your station. **Update** sends it now. |
| Performance → Power Save Mode | Off | Hides the spectrum and waterfall **and stops the FFT**, so weak devices decode smoothly. |
| RADE Mode → RADE V2 waveform (experimental) | Off | Switches TX and RX to RADE V2. V1 and V2 cannot hear each other, and the callsign is V1-only. |
| Diagnostics | — | **Capture CAT log** and **Capture audio log** collect the app's recent logs so that you can copy them into a bug report. |

## Troubleshooting

- **A signal is visible on the waterfall but never syncs.** Check the sideband
  (USB above 10 MHz, LSB below), then the input level (−15 to −5 dBFS). Audio
  that is too hot or too quiet is the most common cause. Also make sure RADE V2
  is off unless the other station uses V2.
- **Orange warning "device rejected Unprocessed preset".** The phone insists on
  applying voice processing (AEC/AGC) to the selected input, which can break
  decoding (seen, for example, on the internal microphones of the Samsung Galaxy
  S24). Use a USB audio input.
- **Audio breaks up or sounds robotic on a budget device.** Turn on **Power Save
  Mode**. Drawing the spectrum and the FFT are the main CPU load besides the
  vocoder.
- **The header shows SYNC but you hear nothing.** Run **Settings → RX Output →
  Test selected RX output**. It tells a routing problem from "no decoded speech
  yet". Then pick the output device yourself.
- **Decoding stops when the screen turns off.** Battery optimization is stopping
  the service. Use the battery dialog (shown on first launch) and the
  vendor-specific steps it lists.
- **The rig keys the moment the serial cable connects.** Your CAT cable wires RTS
  to PTT. Turn **RTS** off on the Rig tab.
- **"PTT was not confirmed over CAT."** Look at the radio. If it did switch, keep
  operating; the app keeps retrying and reads the state back. If it is stuck in
  transmit, press **STOP**.
- **"The radio stayed in receive after the app keyed it."** The radio refused to
  transmit. Check TX inhibit, SWR protection, and the band and mode on the radio.
- **"Transmission stopped because microphone audio was lost."** The app unkeyed
  the rig and went back to receive. Check the microphone and its USB connection.
- **No USB serial device found.** Confirm that the phone supports USB-OTG, tap
  **Refresh**, and accept Android's USB permission dialog.
- **CAT worked in an earlier version and does not now.** v1.6.36 moved the
  bundled Hamlib from 4.5.5 to 4.7.2. Capture the CAT log and open an issue with
  your rig model.
- **Icom network will not connect.** Network Control must be on, the user name
  and password must match the radio's, and the phone must be able to reach the
  radio's IP address. Over a VPN, see the routing note under
  [Wi-Fi (IC-705)](#icom-network).
- **Icom over LTE: audio drops out.** Set the audio buffer to 200 or 300 ms and
  try **TX audio 16 kHz**.
- **HL2 (LAN): "Radio not found".** The phone must be on the same LAN and subnet
  as the Hermes-Lite 2 for broadcast discovery; otherwise enter the radio's IP
  address in **Host**. "Radio is busy" means another SDR host is connected.
- **HL2 (LAN): the relay keys but no RF comes out.** Check the **TX drive** slider
  and the **PA** switch on the Rig tab.
- **Reporter shows "Not connected".** Set a callsign in Settings and turn on
  **Enable Reporter**. The Stations tab shows the exact reason.
- **Anything hard to pin down.** **Settings → Diagnostics** captures the app's
  recent CAT and audio logs on the phone (no adb needed), ready to paste into a
  [GitHub issue](https://github.com/pepefrog1234/RADE_decode_Android/issues).

## Permissions and network use

| Permission | Why |
|---|---|
| Microphone (`RECORD_AUDIO`) | Receive audio from USB or the microphone; the TX microphone |
| Nearby devices (`BLUETOOTH_CONNECT`, Android 12+, optional) | Bluetooth headsets and LE Audio routing |
| Location (fine/coarse, optional) | Works out your Maidenhead grid for the Reporter |
| Internet / network state | FreeDV Reporter, the network rig modes, map tiles |
| Foreground service (microphone, media playback), wake lock | Keeps decoding while the app is in the background |
| USB host (hardware feature, optional) | USB audio interfaces and CAT cables |

No storage permission is needed. Recordings live in app-private storage and leave
the app through the share sheet.

The app connects to qso.freedv.org (when the Reporter is enabled and a callsign
is set), to OpenStreetMap's tile servers (when the Map tab is open), and to the
radio or PC you configure on the Rig tab. It contains no advertising or analytics
code.

## For developers

### Architecture

```
RX:  USB / mic / Icom network / VBAN (48 kHz)   HL2: I/Q over UDP (48 kHz)
      → Oboe capture or network packets           → complex decimation ÷6
      → polyphase FIR decimation (→ 8 kHz)        → analytic USB demod (→ 8 kHz)
      → RADE OFDM demodulation (+ EOO callsign decode)
      → FARGAN neural vocoder (→ 16 kHz speech)
      → Oboe playback (+ WAV session recording)

TX:  microphone (48 kHz)
      → feature extraction → RADE OFDM modulation (8 kHz)
      → polyphase interpolation (→ 48 kHz) → USB audio / Icom network / VBAN
        or analytic filter + ×6 interpolation → 16-bit I/Q → HL2 over UDP
      (PTT via CAT, RTS, or MOX)
```

| Layer | Where | Notes |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | Receiver, Rig, Stations, Map (osmdroid), Log, Settings; `PttDock` is the TX bar of the Stations and Map tabs |
| ViewModel | `TransceiverViewModel` | Binds to the service and exposes UI state as `StateFlow` |
| Service | `service/AudioService` | Foreground service (microphone + media playback types): session lifecycle, snapshots, recording |
| JNI bridge | `AudioBridge`, `RADEWrapper` | Oboe engine control; RADE + FARGAN processing |
| Native | `app/src/main/cpp/` | `audio_engine.cpp` (Oboe streams, decimation, frame dispatch), `rade_jni.cpp`, `radae/` (RADE modem, vendored unmodified from [rade_c](https://github.com/freedv/rade_c)), `opus/` (vendored Opus with FARGAN), `eoo/` (callsign codec), `wdsp_ps/` (PureSignal engine from WDSP) |
| Rig control | `network/`, `usb/` | Bundled Hamlib 4.7.2 `rigctld` (arm64) with a native USB-serial→pty bridge; Icom RS-BA1, openHPSDR protocol 1 (Hermes-Lite 2, with the SSB modem in the app), and VBAN clients in Kotlin |
| Data | `data/` | Raw SQLite (no Room): sessions, signal snapshots, sync events, callsign events |
| Reporter | `network/FreeDVReporter` | Socket.IO v4 over WebSocket with OkHttp |

### Building from source

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

- Android Studio with AGP 9.1 (Kotlin 2.2.10) and JDK 21; the JBR that ships with
  Android Studio works
- The NDK and CMake 3.22.1 are driven by the Gradle build. There is no separate
  native build step
- The first native build downloads Oboe 1.9.0 through CMake FetchContent (network
  needed once). Opus and the RADE modem are vendored in the repository
- The output is **arm64-v8a only**. Test on a real device or an arm64 emulator
  image
- `assembleRelease` needs the release signing key and fails without it. See the
  [signing guide (繁體中文)](docs/signing/README.zh-TW.md)

### Tests and maintenance

- The `Hamlib*Test` classes run the app's code against a real `rigctld`. They are
  skipped unless `RADE_TEST_RIGCTLD` points at a host build of the bundled
  Hamlib; [tools/hamlib/README.md](tools/hamlib/README.md) explains how the
  bundled binary is built and patched.
- `./gradlew connectedDebugAndroidTest` runs the instrumented tests on a
  connected device.
- After updating `radae/` or `opus/`, run the host loopback check in
  `tools/radecheck`.
- [CLAUDE.md](CLAUDE.md) holds the maintenance notes for the vendored modem and
  the bundled `rigctld`.

## License and acknowledgements

The app's own source code is licensed under the **GNU Lesser General Public
License v2.1** (see [LICENSE](LICENSE)). The APK also contains the PureSignal
engine from WDSP, which is GPL-2.0-or-later, so the combined binary is
distributed under the GPL. See the [WDSP notice](docs/licenses/WDSP-NOTICE.md).

Standing on the shoulders of:

| Project | Author / origin | License |
|---|---|---|
| [RADE modem](https://github.com/freedv/rade_c) | David Rowe and the FreeDV project | BSD 2-Clause |
| Opus / FARGAN vocoder | Xiph.Org Foundation | BSD 3-Clause |
| EOO callsign codec | Codec2 / FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/) (`rigctld`) | The Hamlib Group | LGPL 2.1+ |
| [WDSP](https://github.com/g0orx/wdsp) (PureSignal) | Warren Pratt, NR0V; Linux port by John Melton, G0ORX/N6LYT | GPL 2.0+ |
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

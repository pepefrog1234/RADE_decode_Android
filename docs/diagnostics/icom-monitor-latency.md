# Icom v1.6.21 Monitor latency and PTT follow-up

Report received 2026-09-15. The supplied excerpt covers 2026-09-14
19:59:24–20:02:33. The tester reports increasing Monitor delay, brief RX
interruptions, and PTT remaining keyed until disconnect/reconnect.

## Evidence and scope

- 19:59:24: OFF is acknowledged in 244 ms. Network RX starts at 48 kHz and the
  Android output at 16 kHz.
- The excerpt records 221 concealed audio packets by 20:02:25. Actual packet
  arrival is uneven: 500 packets are delivered between 20:00:23.396 and
  20:00:26.102, faster than their approximately five seconds of audio duration.
- The old jitter queue releases buffered packets plus concealed silence after
  waiting at a gap. The native playback ring holds 32,000 samples, almost two
  seconds at 16 kHz, with no mechanism to discard old playback. The UDP consumer
  channel was unbounded as well. Repeated stalls can therefore preserve earlier
  audio instead of returning to live playback.
- `RPRT -8` is Hamlib's protocol error, not a successful OFF confirmation.
  The excerpt also contains several caller timeouts. A complete negative ERP
  response leaves TCP healthy; existing UDP heartbeats can remain healthy too,
  so neither alone detects a broken CI-V command path.
- The last stuck-PTT event described in the prose is absent from the attached
  excerpt. The changes close identifiable recovery gaps; they do not establish
  the exact cause of that unseen operation or promise uninterrupted LTE audio.

## Reproduce and validate

Build/JVM tests:

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
```

Host native queue regression (requires clang++):

```sh
clang++ -std=c++11 -pthread -fsanitize=address,undefined -g \
  -I app/src/main/cpp app/src/main/cpp/audio_ring_buffer.cpp \
  app/src/test/cpp/audio_ring_buffer_test.cpp -o /tmp/rade-audio-ring-test
/tmp/rade-audio-ring-test
```

Expected simulation output: legacy peak 1,989 ms; bounded peak 290 ms. This
measures the queue only. Network, jitter wait, device buffering and Bluetooth
add their own latency.

The Android `AudioEngineLifecycleTest` includes a headless test that feeds two
seconds of Monitor PCM into the production native engine with playback paused,
then checks that only the newest 100 ms remains at both network sample rates.
It can be run on an attached arm64 device using `connectedDebugAndroidTest`.

Protocol references: [wfview sample rates](https://wfview.org/older-user-manual-pages/preferences-file-v1-1/)
and [wfview Icom UDP audio implementation](https://github.com/eliggett/wfview/blob/master/src/radio/icomudpaudio.cpp).
RX parsing preserves compatibility with received header identifiers and accepts
the declared packet length, rather than assuming that the radio copies the
client's TX identifier/datalen fields.

See [v1.6.23 release notes](../release-notes/v1.6.23.md) for field-test steps.

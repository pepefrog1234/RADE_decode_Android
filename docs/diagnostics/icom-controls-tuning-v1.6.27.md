# Issue #10: controls clipped and radio returning to an old frequency

## Reports and evidence

- [September 19 UI report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5740325251):
  in v1.6.26 the first decoded callsign makes TX disappear below the screen;
  map labels are too large.
- [September 19 frequency report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5740501418):
  the IC-7300MK2 returns from 7177 to 7065 kHz after several transmissions.
  This comment contains no new log. Restored Android preferences are the
  reporter's hypothesis, not an established cause. `manual_freq_hz` is used
  without CAT and is not automatically sent when Icom connects.
- Earlier v1.6.25 logs contain network reconnect writes at 18:40:38.943,
  18:40:39.124 and 18:40:40.162: CI-V `25 00` sets 7065100 Hz, `07 00`
  selects VFO A, then `25 00` restores 7065000 Hz. These are real outgoing
  writes, not just values displayed from a cache.
- The **packaged** `assets/rigctld` and `jniLibs/arm64-v8a/librigctld.so`
  identify as **Hamlib 4.5.5, SHA 6eecd3**. The untracked local Hamlib 4.7.1
  build tree is not the binary shipped in the APK. Earlier investigations
  that used that tree must not be taken as evidence of the shipped backend's
  startup behavior.
- In the [official 4.5.5 Icom source](https://github.com/Hamlib/Hamlib/blob/4.5.5/rigs/icom/icom.c),
  `icom_rig_open()` calls `icom_current_vfo()` and its active
  `icom_current_vfo_x25()` helper. They select VFOs and, when their frequency
  readings match, tune +100 Hz and restore the original value. Read/set
  failures are not consistently checked. `no_xchg=1` does not disable the
  x25 probe and can force VFO A in the other branch, so it is not a solution.

This establishes an unwanted tuning path and matches the older wire trace.
It does **not** establish which command caused the September 19 jump without
that session's log, nor rule out radio-side split/VFO settings or another CAT
client.

## Changes

1. Reserve the bottom of the receiver for TX and Start/Stop. The information,
   callsign, meters, analog monitor and hints scroll inside the remaining
   height. Callsign/warning growth no longer displaces the essential controls.
2. Use 12 sp map callsigns, 14 sp for TX, and 10 sp details, retaining bold
   callsigns and Android density/font scaling.
3. Add a guard to the **Icom network CI-V bridge only**. The profile operates
   the selected VFO; reject VFO/memory selection and frequency writes unless
   they exactly match an active, explicit Set request for the selected VFO.
   Return a CI-V NAK (with echo when observed), never a fabricated success.
   Read queries, mode/filter commands and PTT keep their existing paths.
4. The permit is created inside RigController's serialized Set operation and
   closes on completion, failure or cancellation. It expires after 10 seconds
   as an additional bound, belongs to one UDP session and is never persisted.
   Requested retransmissions undergo the same check; a completed or obsolete
   tuning command is replaced by an idle packet with the requested sequence.
5. Log `CI-V blocked automatic tuning`, `CI-V explicit frequency request`,
   and `CI-V stale tuning retransmit suppressed`, so future reports distinguish
   denied probes from actual radio writes.

The bundled Hamlib binaries are unchanged. USB CAT and external rigctld
connections do not use this bridge or its guard.

## Validation

- Final `testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline`
  run with `RADE_TEST_RIGCTLD` set: **119 tests, zero failures/errors/skips**.
  Debug APK metadata is versionCode 10627 / 1.6.27-controls-tuning-guard.
- JVM tests cover denied startup tuning/VFO probes, selected versus unselected
  VFO, exact requested frequency, permit expiry/replacement, and NAK/echo.
- Real UDP session tests cover split PTY reads, read/PTT passthrough, echo-aware
  rejection, active versus expired retransmissions, and reconnect isolation.
  RigController tests verify permit lifetime through readback and cancellation.
- Optional `HamlibIcomTuningTest` runs the **actual unmodified Hamlib 4.5.5**
  daemon against a real host PTY and the production guard. Four scenarios use
  echo on/off and equal/unequal VFO frequencies. VFO B stays selected, manual
  Set and three PTT on/off cycles succeed, and an unrequested Set is rejected.
  Build 4.5.5 from its official release source and run:

  ```sh
  RADE_TEST_RIGCTLD=/path/to/hamlib-4.5.5/tests/rigctld ./gradlew testDebugUnitTest
  ```

  The host fixture also requires `python3` for PTY allocation. Without the
  environment variable these four optional tests are skipped.
- `TransceiverLayoutTest` covers a 320 × 360 dp viewport with 2× system fonts,
  decoded callsign and warnings, plus hold-to-talk release after the TX header
  changes. Instrumentation APK compilation is checked; device execution is
  still needed because this workstation has no attached device or AVD.

## Field confirmation

Install the candidate as an update, then verify:

1. With display/font scaling as before, decode a callsign while receiving.
   TX and Stop remain visible and clickable; scroll the information area.
2. Test both tap-to-talk and hold-to-talk, including release after scrolling
   the information area. Check the reduced map label size.
3. Tune the physical radio to 7177 kHz with a different frequency stored in
   the other VFO. Connect, receive, transmit several times, disconnect and
   reconnect. The selected VFO and dial frequency should stay unchanged.
4. Press Set for a new frequency once and check physical dial/readback.
   If the jump returns, save the Audio Log covering connection and the jump,
   and record the radio's selected VFO and split setting. Do not assume a
   matching screen value alone proves the physical radio stayed tuned.

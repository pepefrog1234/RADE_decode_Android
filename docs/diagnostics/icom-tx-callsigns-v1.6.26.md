# v1.6.25 follow-up: TX gaps, callsign identity and map text

Investigation on 2026-09-18 of the September 17 follow-ups in
[issue #10](https://github.com/pepefrog1234/RADE_decode_Android/issues/10).
The candidate version is `1.6.26-tx-cadence-callsigns` (10626).

## Field evidence

The tester's [latest report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5718749132)
says frequency Set is working and two LTE/VPN QSOs, one at 16 kHz and one at
48 kHz, were OK. This is useful positive feedback for v1.6.25; it is not proof
that every intermittent problem has been eliminated.

- [First TX interruption report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5717857320):
  the radio's negotiated TX buffer is 150 ms and TX is 48 kHz. NetTxPump reports
  regular progress without recorded catch-up/drop events. Around the reported
  18:27 minute, the log also contains an intentional EOO at 18:27:00.405 and
  pump completion at 18:27:00.743. OFF initially times out, then is confirmed
  by readback at 18:27:04.112. The minute-only report cannot distinguish an
  earlier unintended gap from the end of this over.
- [LTE/VPN interruption report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5718022592):
  CAT recovery starts at 18:40:34.491 and succeeds at 18:40:42.640. During the
  reconnect, TX audio starts at 18:40:40.880, before rigctld connects at
  18:40:42.638. There is no key-ON call for this attempt: the old ViewModel
  skipped PTT because CAT was not connected, while UDP audio was already up.
  Later TX at 18:41:25.919–18:42:13.694 has regular pump progress, stable ring
  snapshots, and no recorded pacing catch-up/drop events. OFF is confirmed
  at 18:42:14.492. This excerpt does not establish the cause of the short
  interruptions heard on two WebSDRs during that later over.
- [Callsign report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5718395567):
  the tester hears one participant while the search/sync box shows the other.
  The log has received callsigns such as DG3DJ at 19:05:19.629 and
  19:11:08.674, followed by DK3UD at 19:12:17.066. It does not independently
  identify the speaker at each instant. The modem implementation supplies
  the explanation: RADE V1 decodes the callsign from the end-of-over frame,
  and the UI deliberately retained it until the next successful decode.
  Therefore the displayed value belongs to a completed over. It cannot
  reliably identify a new speaker before that speaker's end frame arrives.

Some comments reach GitHub's length limit and include concatenated or repeated
log text. These are incomplete excerpts; missing rows are not proof of an
event's absence from the complete recording.

## Changes in this candidate

### Callsign display and map readability

The search/sync box now labels its retained value **Last decoded callsign** and
explains that callsigns are decoded at the end of a transmission. This preserves
the useful history without presenting it as live speaker identification. The
explanation is localized in English, Japanese, Traditional and Simplified
Chinese. No callsign values are swapped or guessed.

Map callsigns were drawn at 24 px (28 px for TX), irrespective of screen density;
on a density-3 display at the default font scale this is only 8 sp. Callsigns
now use bold 16 sp (18 sp for TX), with 12 sp frequency/SNR details. Font scaling
follows Android's settings and line separation uses font metrics.

### Connection readiness

TX is refused, with the existing TX-start warning, while a connection or Icom
recovery is in progress, or when Icom UDP is up but CAT is disconnected. The
operator can try TX again when the complete connection is ready. This addresses
the specific unkeyed audio attempt at 18:40:40, not the later 18:42 interruption.

### TX cadence and diagnostics

- The pump sends its first frame immediately. NetTxPacer also advanced its
  initial deadline by one period, causing the next frame to be scheduled at
  40 ms instead of 20 ms. The corrected schedule is 0, 20, 40, ... ms.
- After a long stall without spare encoder frames, the old re-anchor returned
  zero sleep, immediately consuming another frame. It now waits one frame
  period to allow the encoder to refill. Bounded catch-up and stale-backlog
  dropping remain covered by the existing tests.
- NetTxPump now records `gapMax`, the largest interval between send starts in
  each report window. The earlier `late` statistic measures deadline debt and
  does not describe every real inter-frame gap.
- Native `Net TX underrun` warnings report missing modem samples and affected
  frames while TX is active. Previously, `fillNetTxFrame` zero-padded missing
  samples and returned a full frame, so regular UDP send counts alone could
  not reveal encoder starvation. Logging is rate limited; normal final-frame
  padding after TX stops is excluded.

The first-frame timing and re-anchor defects are reproducible. Neither proves
the cause of the later steady-state WebSDR interruptions. Packet sizes, sample
rates and the operator's radio buffer choice are unchanged.

## Validation

- The updated eight-case NetTxPacer suite failed against the prior scheduling
  behavior, including two new cadence/refill regressions, then passed with the
  correction.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline` passes:
  **108 JVM tests, zero failures/errors/skips**. The native arm64 library and
  both APKs compile successfully.
- APK metadata is `versionCode 10626`, `1.6.26-tx-cadence-callsigns`.
- No Android device or configured AVD was available. Native audio behavior,
  on-device UI layout and IC-7300MK2 operation still require field validation.

## Suggested field test and reply

Try repeated connection/reconnection with TX presses before CAT becomes ready;
the app should remain in RX and show the TX-start warning. Once connected, test
several TX/RX cycles, frequency changes and a longer over. For LTE/VPN, compare
the existing 150 ms buffer with 200–300 ms, and 48 kHz with 16 kHz TX, one setting
at a time. These settings take effect on reconnect. Do not use the previously
problematic 500/800 ms values.

If another interruption occurs, export the complete Audio Log immediately and
note the exact time. Compare `gapMax`, `Net TX underrun`, retransmission counts,
PTT transitions and radio/WebSDR observations. Verify map text at the phone's
usual font size and a larger accessibility font size. During an alternating
QSO, the retained callsign should be clearly understood as the last decoded
completed over.

Suggested English reply (draft, not posted):

> Thanks for confirming that frequency Set and both test QSOs worked. The
> callsign is sent at the end of each RADE V1 transmission, so the retained
> callsign belongs to an earlier completed transmission. I have labelled this
> explicitly and enlarged the map callsigns. I also fixed a TX-start timing
> error and an attempt to transmit before CAT had reconnected. The later short
> audio gaps are not fully explained yet; the candidate adds precise send-gap
> and encoder-underrun logging to help trace them. Please note the exact time
> and export the full Audio Log if another gap occurs.

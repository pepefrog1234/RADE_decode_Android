# IC-7300MK2 CAT rejection, unexpected frequency and stuck PTT

Analysis of the September 15–16 follow-ups in
[issue #10](https://github.com/pepefrog1234/RADE_decode_Android/issues/10).
The latest reports use v1.6.24 over the tester's Wi-Fi/LTE-VPN connection.
This update addresses reproducible transport and confirmation defects; physical
radio validation is still needed.

## What the supplied logs establish

- In the [frequency-jump report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5701616933),
  Set to 7,177,000 Hz at 19:13:37 succeeds and polling continues to report that
  value through 19:16:08. A timeout and `RPRT -13` (Hamlib bus error) follow.
  At 19:16:18, repeated `RPRT -9` (command rejected) begins. At 19:16:25,
  a read reports 7,065,000 Hz. No app Set to that frequency appears in this
  excerpt.
- In the [repeated-Set report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5701680297),
  four requests to restore 7,177,000 Hz at 19:20:33–19:21:11 return `-9` within
  a few milliseconds. Reads report other frequencies or fail. Disconnect at
  19:21:18 and reconnect at 19:21:32 are followed by a 7,177,000 Hz read.
- In the [later TX/RX report](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5701792562),
  RX is requested at 19:30:46. PTT OFF attempts return `-9` and `-5`, then an
  ACK arrives in about 3 ms at 19:30:51.981. The old app treats this as confirmed
  OFF, although subsequent frequency replies contain no value and PTT reads
  still return `-9`.
- The available [48 kHz TX interruption log](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5701379312)
  has regular NetTxPump progress (250 frames per five seconds), zero recorded
  late/catch-up/dropped frames, and send calls taking at most 7 ms in the
  available interval. It does not establish a phone-side pump stall. These
  counters do not measure delivery to the radio or its audio buffer.

Several comments contain repeated log blocks and reach GitHub's comment length
limit. They are excerpts, not complete sessions. The logs establish a broken
CAT session but do not prove which physical CI-V command caused the dial jump.

## Defects addressed

1. Every PTT query and write was proactively transmitted three times, at
   approximately 0, 25 and 60 ms. Assuming the radio would deduplicate these
   packets was unsafe: extra CI-V replies can be consumed by a later command.
   Each request is now sent once. Explicit retransmit requests from the current
   radio session still work.
2. The serial bridge forwarded byte-identical UDP duplicates to Hamlib. It now
   suppresses matching sequence-and-byte duplicates in a bounded, 15-second
   history. Different packets and unique out-of-order packets remain eligible.
   Packets with mismatched session IDs are rejected before any stream handling,
   including retransmit handling, so a previous login cannot request a replay
   of the current session's PTT command.
3. CAT health only counted timeout/I/O/protocol errors. Repeated command
   rejection (`-9`), bus errors (`-13`), bus collisions (`-14`), and successful
   but empty/invalid status reads now count as failures. Frequency and PTT
   reads have independent failure windows; one healthy read type cannot hide
   the other's failure. Three failures persisting for at least 15 seconds
   request the existing Icom session recovery. Unsupported meter/mode reads
   alone do not trigger this recovery.
4. The owned Icom network rigctld now starts with
   `--set-conf=poll_interval=0,cache_timeout=0`. Hamlib's background publisher
   otherwise resets cache timeout to its polling interval, even when only
   `cache_timeout=0` was requested. This avoids confirming Set/PTT against the
   immediately cached write value. RigController continues its own polling.
5. Icom network PTT OFF requires both an ACK and a valid OFF readback on the
   same connection. ACK plus ON/missing readback leaves release pending, so the
   existing background OFF retry and reconnect logic continues. Repeated
   unconfirmed OFF attempts also trigger CAT recovery. Superseded operations
   and replaced connections cannot publish a successful OFF result.

The Audio Log now includes outgoing CI-V frequency/VFO/PTT write bytes, alongside
the existing request/readback records. This allows another frequency jump to
be compared with actual commands sent toward the radio. Generic USB and
external rigctld connections retain their existing PTT confirmation behavior.

## Validation

- Five newly added regression cases failed against the old code: proactive
  PTT duplication, duplicate/session-mismatched CI-V delivery, and three CAT
  health failure patterns.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline` passes
  with **106 JVM tests, zero failures/errors/skips**, including eight new
  regression tests. Coverage also checks valid radio-requested retransmission,
  old-session retransmit rejection, and ACKs with ON or empty OFF readback.
- A local Hamlib 4.7.2 Dummy rigctld experiment reproduced the cache problem:
  `cache_timeout=0` alone yielded `get_cache = 1000 ms`; with both options it
  remained 0 ms before and after PTT ON/OFF readbacks. The bundled Hamlib 4.7.1
  source has the same startup override. This host experiment is not an Android
  or IC-7300MK2 hardware test.
- Debug and instrumentation APKs compile. No physical phone/radio validation
  has been performed for this candidate.

## Field validation

Use the same radio configuration and connection as the failing test. Compare
the physical radio display with the app while setting 7,177 / 7,065 / 7,068 kHz
and cycling TX/RX. An ACK with missing/ON readback should leave the PTT release
warning visible and retain background recovery. Record whether a broken CAT
session recovers without a manual disconnect.

Compare 48 kHz and 16 kHz receive settings under the same LTE/VPN conditions,
and note the exact time of any transmit audio interruption. The 48 kHz audio
packetization was not changed by this fix. Export the full Audio Log immediately
after a jump, stuck PTT, or interruption; include the original/new physical dial
frequency and the radio's TX/RX indication. The new wire-write records are
needed to distinguish an app command from a radio/backend state change.

Suggested English reply (draft, not posted):

> I traced the latest logs to a CAT session that kept rejecting commands while
> the network connection remained alive. I found and fixed duplicate PTT
> transmissions and duplicate/stale packet handling. The new candidate also
> disables Hamlib's status cache for the Icom network connection, verifies OFF
> by reading the radio status, and restarts the session after persistent CAT
> failures. The automated tests pass, but I still need a test on your radio to
> confirm the stuck-TX and frequency symptoms are resolved. The 48 kHz audio
> interruption is not yet explained by the available log. If it happens again,
> please export the complete Audio Log immediately and note the time and what
> the radio display showed.

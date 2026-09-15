# IC-7300MK2 frequency jump and repeated Set report

Follow-up received 2026-09-16. Manfred reports that the physical radio suddenly
changed frequency and accepted the previous frequency only after several Set
attempts. The supplied attachment repeats the earlier v1.6.21 log covering
2026-09-14 19:59:24–20:02:33.

## What the log establishes

- Frequency queries exceed the caller deadline at 19:59:53.916, 20:00:01.293
  and 20:00:11.659. At 20:00:03.377, `get_freq` returns `RPRT -8`, Hamlib's
  protocol error.
- The excerpt contains **no `set_freq` command and no `Frequency:` value**.
  Successful frequency polling was not logged by that version. Consequently,
  this excerpt cannot identify the original/jumped frequencies, the time of
  the jump, or whether a particular Set reached the physical radio.
- Serial retransmission counters increase, but their contents are absent.
  This does not establish that an old frequency command was replayed.

## Confirmed code defects addressed in v1.6.24

1. `RigScreen` replaced the editable frequency text whenever the reported
   frequency changed. A poll arriving while the operator was typing could
   therefore replace the intended value before Set was pressed. Editable text
   is now independent of polling until a submitted value is observed.
2. The old formatter used the phone locale and rounded kHz to one decimal place
   (100 Hz). The input filter retained only digits and a decimal point, deleting
   decimal commas. For example, editing `5357,5` could produce `53575`, changing
   the magnitude. Both decimal separators are now accepted, ambiguous grouping
   is rejected without deleting characters, and formatting preserves 1 Hz.
3. Frequency Set used a one-second wait, assumed a successful acknowledgement
   meant the requested dial frequency, and unconditionally proceeded to the
   band's automatic mode change. Set now waits up to 2.5 seconds and then
   separately waits up to 2.5 seconds for a matching frequency readback on the
   same ordered connection. A missing or mismatched readback displays a warning,
   preserves the requested input for retry, and skips the automatic mode change.
4. Polls started before a newer Set cannot overwrite its state. Superseded
   frequency jobs are cancelled; their already-sent replies retain their FIFO
   slots. An old operation cannot continue its readback on a replacement
   connection. Later matching polling clears a previous confirmation warning.

Frequency requests and completed replies, including replies arriving after a
caller timeout, now appear in the Audio Log through the existing RigController
capture. The completion entry includes requested Hz, set result, reported Hz,
and whether they match.

These are reproducible application defects, but they are **not proof of the
cause of the reported physical radio jump**. Hamlib may also answer `get_freq`
from its cache; matching rigctld readback is not an independent measurement of
the physical radio. The radio display remains part of field validation.

## Validation and field test

`testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline` passes
with **98 JVM tests, zero failures/errors/skips**. Eleven new tests cover input
preservation/precision, mismatched and slow replies, superseded operations,
later recovery, and replacement TCP sessions. Android instrumentation builds,
but physical Android/IC-7300MK2 testing has not been performed.

For a field test, use the same LTE/VPN connection and record the physical radio
display alongside the app. Slowly type a new frequency while polling continues,
then press Set once. Confirm that the requested digits remain intact and that
both displays agree after the reply. Try decimal kHz entries with `.` and `,`
and, where supported, 1 Hz precision. During a failed operation the input should
remain available for another Set. Export the full Audio Log immediately after
an unexpected jump and include its approximate time and original/new frequency.

Suggested reply to Manfred:

> Thanks for the additional detail. I found and fixed problems where background
> frequency updates could overwrite an entry before Set, and decimal commas
> could be removed from the input. The new build also checks the reported
> frequency after Set and records the request and reply. The earlier log has
> query errors but no frequency-setting command or frequency values, so I
> cannot yet confirm what caused the sudden change on the radio. A full Audio
> Log captured immediately after another occurrence, together with its time
> and the original/new frequencies, would help trace it.

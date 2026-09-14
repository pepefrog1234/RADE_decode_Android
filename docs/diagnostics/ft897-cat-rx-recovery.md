# FT-897 CAT framing and RX recovery

Based on the published `v1.6.21` source. The report concerns an FT-897 connected
through a sound card and USB serial adapter, without Bluetooth: the app's Rig
page does not display the mode, and the receiver returns to the Start button
when attempting to transmit.

## Changes

- FT-897 (Hamlib model 1023) and FT-897D (1043) configure the physical USB UART
  as **8N2**. The selected baud rate remains unchanged. Other models keep 8N1.
  This applies to the CDC, CP210x, FTDI and CH340 framing requests, including
  connections opened after Android grants USB permission. Failed framing
  requests now report a connection error rather than start the byte bridge.
- This is required at the USB layer: Hamlib changes the PTY's termios, but the
  native PTY bridge transfers only bytes and does not forward line settings to
  the USB adapter. The [Hamlib FT-897 backend](https://github.com/Hamlib/Hamlib/blob/4.5.5/rigs/yaesu/ft897.c)
  specifies eight data bits, no parity, and two stop bits.
- A rejected PTT or failed TX audio setup now runs the normal audio/PTT cleanup
  and restores RX. A native TX-open failure no longer stops the service or leaves
  it falsely marked as receiving. A failure message remains visible after RX
  returns, in English, Japanese and both Chinese translations.
- Stop, disconnect and service replacement invalidate pending RX restoration.
  Stop allows PTT cleanup to finish. Rapid re-key still waits for the receiver to
  return. If RX cannot restart, the app reports that failure instead of claiming
  to have restored reception.
- The EOO drain, RF tail, ordered CAT OFF and background OFF retries remain in
  place. An uncertain PTT-OFF continues to block new TX while allowing RX.

## Verification

`UsbSerialFramingTest` covers the two FT-897 model IDs, unchanged defaults, and
the actual USB setup fields and CDC payload. `RxRecoveryTest` covers cleanup
ordering, Stop/disconnect during cleanup and settling, changed service identity,
cleanup exceptions, and queued re-key after RX restoration.

Validation on 2026-09-14: **74 unit tests passed**, with zero failures, errors
or skips. Both the debug APK and Android instrumentation test APK built
successfully. The initial offline build required downloading missing Android
test dependencies; the following complete build succeeded:

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

The tests do not emulate the physical UART, radio or Android audio hardware.
No Android device was attached during implementation. The reported case still
needs an FT-897 test: connect with the matching CAT baud rate, check mode and
frequency readback, then perform repeated RX/TX cycles. Also test Stop during
RX restoration and capture the CAT log if the failure repeats; USB connection
logs now include the applied framing.

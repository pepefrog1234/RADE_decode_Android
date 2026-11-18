package yakumo2683.RADEdecode.ui

import org.junit.Assert.*
import org.junit.Test

class RadeV2StatusTest {
    private fun hint(
        mistune: Int,
        sideband: RxSideband,
        radeV2: Boolean = true,
        receiving: Boolean = true,
        synced: Boolean = false
    ) = tuneHintFor(radeV2, receiving, synced, mistune, sideband)

    /** rade_mistune(): +1 = signal too high in audio frequency. On USB audio is
     *  (signal − dial), so the dial goes up; on LSB audio is (dial − signal). */
    @Test fun arrowFollowsTheRadiosSideband() {
        assertEquals(TuneHint.TUNE_UP, hint(+1, RxSideband.UPPER))
        assertEquals(TuneHint.TUNE_DOWN, hint(-1, RxSideband.UPPER))
        assertEquals(TuneHint.TUNE_DOWN, hint(+1, RxSideband.LOWER))
        assertEquals(TuneHint.TUNE_UP, hint(-1, RxSideband.LOWER))
    }

    @Test fun unknownSidebandNeverNamesADialDirection() {
        assertEquals(TuneHint.SIGNAL_TOO_HIGH, hint(+1, RxSideband.UNKNOWN))
        assertEquals(TuneHint.SIGNAL_TOO_LOW, hint(-1, RxSideband.UNKNOWN))
    }

    @Test fun noIndicationShowsNothing() {
        for (sideband in RxSideband.entries) assertEquals(TuneHint.NONE, hint(0, sideband))
    }

    @Test fun v1NeverShowsTheHint() {
        for (sideband in RxSideband.entries) for (mistune in listOf(-1, 1)) {
            assertEquals(TuneHint.NONE, hint(mistune, sideband, radeV2 = false))
        }
    }

    /** The sync callback can overtake the polled indicator. */
    @Test fun hintIsHiddenInSyncAndWhileNotReceiving() {
        for (sideband in RxSideband.entries) for (mistune in listOf(-1, 1)) {
            assertEquals(TuneHint.NONE, hint(mistune, sideband, synced = true))
            assertEquals(TuneHint.NONE, hint(mistune, sideband, receiving = false))
        }
    }

    @Test fun uiStateShowsTheHintOnlyWhileReceivingV2OutOfSync() {
        val mistuned = TransceiverViewModel.UiState(isRunning = true, radeV2Mode = true, mistune = 1)
        assertEquals(TuneHint.TUNE_UP, mistuned.tuneHint(RxSideband.UPPER))
        assertEquals(TuneHint.TUNE_DOWN, mistuned.tuneHint(RxSideband.LOWER))
        assertEquals(TuneHint.SIGNAL_TOO_HIGH, mistuned.tuneHint(RxSideband.UNKNOWN))
        for (hidden in listOf(
            mistuned.copy(mistune = 0),            // signal gone, or tuned into range
            mistuned.copy(syncState = 2),          // in sync
            mistuned.copy(radeV2Mode = false),     // V1
            mistuned.copy(isRunning = false),      // stopped
            mistuned.copy(isTx = true),            // transmitting
            TransceiverViewModel.UiState()         // defaults
        )) assertEquals(TuneHint.NONE, hidden.tuneHint(RxSideband.UPPER))
    }

    @Test fun sidebandComesFromTheCatMode() {
        fun cat(mode: String) = rxSidebandOf(hermesDirect = false, rigConnected = true, rigMode = mode)
        for (mode in listOf("USB", "PKTUSB", "ECSSUSB", "USB-D1", "USB-D3", " usb ", "pktusb")) {
            assertEquals(mode, RxSideband.UPPER, cat(mode))
        }
        for (mode in listOf("LSB", "PKTLSB", "ECSSLSB", "LSB-D2", "lsb")) {
            assertEquals(mode, RxSideband.LOWER, cat(mode))
        }
        // No sideband, or one whose audio/dial relationship is rig specific. "" is
        // what a new CAT connection reports until its first mode read.
        for (mode in listOf("", "CW", "CWR", "RTTY", "RTTYR", "AM", "FM", "PKTFM", "DSB", "SAL", "SAH",
                "ISBUSB", "ISBLSB", "None", "USBX", "XUSB", "USB LSB")) {
            assertEquals(mode, RxSideband.UNKNOWN, cat(mode))
        }
    }

    @Test fun sidebandIsUnknownWithoutCatAndFixedForHermesLite() {
        // A mode left over from an earlier connection must not be trusted.
        assertEquals(RxSideband.UNKNOWN, rxSidebandOf(hermesDirect = false, rigConnected = false, rigMode = "USB"))
        assertEquals(RxSideband.UNKNOWN, rxSidebandOf(hermesDirect = false, rigConnected = false, rigMode = "LSB"))
        // The app demodulates the HL2's I/Q itself, always as upper sideband.
        assertEquals(RxSideband.UPPER, rxSidebandOf(hermesDirect = true, rigConnected = false, rigMode = ""))
        assertEquals(RxSideband.UPPER, rxSidebandOf(hermesDirect = true, rigConnected = true, rigMode = "LSB"))
    }

    private fun clock(ppm: Float, settled: Boolean = false, radeV2: Boolean = true, receiving: Boolean = true) =
        clockOffsetDisplayFor(radeV2, receiving, ppm, settled)

    @Test fun clockOffsetLineIsThereFromTheStartInV2Only() {
        // A dash until the modem has an estimate: the line never comes and goes.
        assertEquals(ClockOffsetDisplay.NONE, clock(0f))
        assertEquals(ClockOffsetDisplay.NONE, clock(-0f))
        assertEquals(ClockOffsetDisplay.NONE, clock(0f, settled = true))
        assertEquals(ClockOffsetDisplay.NONE, clock(Float.NaN))
        assertEquals(ClockOffsetDisplay.NONE, clock(Float.POSITIVE_INFINITY))
        for (ppm in listOf(0f, 37f, 180f)) for (settled in listOf(false, true)) {
            assertEquals(ClockOffsetDisplay.HIDDEN, clock(ppm, settled, radeV2 = false))
            assertEquals(ClockOffsetDisplay.HIDDEN, clock(ppm, settled, receiving = false))
        }
    }

    @Test fun clockOffsetIsPreliminaryUntilSettled() {
        assertEquals(ClockOffsetDisplay.PRELIMINARY, clock(37.4f))
        assertEquals(ClockOffsetDisplay.PRELIMINARY, clock(-0.2f))
        assertEquals(ClockOffsetDisplay.SETTLED, clock(37.4f, settled = true))
        assertEquals(ClockOffsetDisplay.SETTLED, clock(-125f, settled = true))
    }

    /** The estimate approaches the real offset from below, so a reading beyond
     *  the limit is flagged before it has settled as well. */
    @Test fun clockOffsetBeyond125PpmIsOutOfSpecInEitherDirection() {
        for (ppm in listOf(125.1f, -125.1f, 180f, -250f)) for (settled in listOf(false, true)) {
            assertEquals("$ppm", ClockOffsetDisplay.OUT_OF_SPEC, clock(ppm, settled))
        }
        for (ppm in listOf(124.9f, -124.9f, 125f, -125f)) {
            assertEquals("$ppm", ClockOffsetDisplay.PRELIMINARY, clock(ppm))
        }
    }

    @Test fun uiStateDrivesTheClockOffsetLine() {
        val state = TransceiverViewModel.UiState(isRunning = true, radeV2Mode = true)
        assertEquals(ClockOffsetDisplay.NONE, state.clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.PRELIMINARY, state.copy(clockOffsetPpm = 12f).clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.SETTLED,
            state.copy(clockOffsetPpm = 12f, clockOffsetSettled = true).clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.OUT_OF_SPEC, state.copy(clockOffsetPpm = -180f).clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.HIDDEN, state.copy(clockOffsetPpm = 12f, isTx = true).clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.HIDDEN, state.copy(clockOffsetPpm = 12f, radeV2Mode = false).clockOffsetDisplay)
        assertEquals(ClockOffsetDisplay.HIDDEN, TransceiverViewModel.UiState().clockOffsetDisplay)
    }

    @Test fun clockOffsetIsFormattedAsSignedWholePpm() {
        assertEquals("+37", formatClockOffsetPpm(37.4f))
        assertEquals("-180", formatClockOffsetPpm(-179.6f))
        assertEquals("+1", formatClockOffsetPpm(0.6f))
        assertEquals("-1", formatClockOffsetPpm(-0.6f))
        assertEquals("0", formatClockOffsetPpm(0.2f))
        assertEquals("0", formatClockOffsetPpm(-0.2f))
        assertEquals("0", formatClockOffsetPpm(Float.NaN))   // never throws on the UI thread
    }
}

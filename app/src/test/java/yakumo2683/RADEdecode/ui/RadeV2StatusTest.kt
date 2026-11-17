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

    /** The sync callback can overtake the polled indicator, and the modem itself
     *  still reports the old direction on the call that acquires. */
    @Test fun hintIsHiddenInSyncAndWhileNotReceiving() {
        for (sideband in RxSideband.entries) for (mistune in listOf(-1, 1)) {
            assertEquals(TuneHint.NONE, hint(mistune, sideband, synced = true))
            assertEquals(TuneHint.NONE, hint(mistune, sideband, receiving = false))
        }
    }

    @Test fun uiStateShowsTheHintOnlyWhileReceivingV2OutOfSync() {
        val mistuned = TransceiverViewModel.UiState(
            isRunning = true, radeV2Mode = true, mistune = 1, rxSideband = RxSideband.UPPER
        )
        assertEquals(TuneHint.TUNE_UP, mistuned.tuneHint)
        assertEquals(TuneHint.TUNE_DOWN, mistuned.copy(rxSideband = RxSideband.LOWER).tuneHint)
        assertEquals(TuneHint.SIGNAL_TOO_HIGH, mistuned.copy(rxSideband = RxSideband.UNKNOWN).tuneHint)
        assertEquals(TuneHint.NONE, mistuned.copy(mistune = 0).tuneHint)            // signal gone / in range
        assertEquals(TuneHint.NONE, mistuned.copy(syncState = 2).tuneHint)          // in sync
        assertEquals(TuneHint.NONE, mistuned.copy(radeV2Mode = false).tuneHint)     // V1
        assertEquals(TuneHint.NONE, mistuned.copy(isRunning = false).tuneHint)      // stopped
        assertEquals(TuneHint.NONE, mistuned.copy(isTx = true).tuneHint)            // transmitting
        assertEquals(TuneHint.NONE, TransceiverViewModel.UiState().tuneHint)        // defaults
    }

    @Test fun sidebandComesFromTheCatMode() {
        fun cat(mode: String) = rxSideband(hermesDirect = false, rigConnected = true, rigMode = mode)
        for (mode in listOf("USB", "PKTUSB", "ECSSUSB", "USB-D1", "USB-D3", " usb ", "pktusb")) {
            assertEquals(mode, RxSideband.UPPER, cat(mode))
        }
        for (mode in listOf("LSB", "PKTLSB", "ECSSLSB", "LSB-D2", "lsb")) {
            assertEquals(mode, RxSideband.LOWER, cat(mode))
        }
        // No sideband, or one whose audio/dial relationship is rig specific.
        for (mode in listOf("", "CW", "CWR", "RTTY", "RTTYR", "AM", "FM", "PKTFM", "DSB", "SAL", "SAH",
                "ISBUSB", "ISBLSB", "None", "USBX", "XUSB", "USB LSB")) {
            assertEquals(mode, RxSideband.UNKNOWN, cat(mode))
        }
    }

    @Test fun sidebandIsUnknownWithoutCatAndFixedForHermesLite() {
        // A mode left over from an earlier connection must not be trusted.
        assertEquals(RxSideband.UNKNOWN, rxSideband(hermesDirect = false, rigConnected = false, rigMode = "USB"))
        assertEquals(RxSideband.UNKNOWN, rxSideband(hermesDirect = false, rigConnected = false, rigMode = "LSB"))
        // The app demodulates the HL2's I/Q itself, always as upper sideband.
        assertEquals(RxSideband.UPPER, rxSideband(hermesDirect = true, rigConnected = false, rigMode = ""))
        assertEquals(RxSideband.UPPER, rxSideband(hermesDirect = true, rigConnected = true, rigMode = "LSB"))
    }

    @Test fun clockOffsetIsShownOnlyOnceV2HasAnEstimate() {
        assertTrue(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = 37.4f))
        assertTrue(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = -0.2f))
        assertFalse(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = 0f))
        assertFalse(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = -0f))
        assertFalse(shouldShowClockOffset(radeV2 = false, receiving = true, clockOffsetPpm = 37.4f))
        assertFalse(shouldShowClockOffset(radeV2 = true, receiving = false, clockOffsetPpm = 37.4f))
        assertFalse(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = Float.NaN))
        assertFalse(shouldShowClockOffset(radeV2 = true, receiving = true, clockOffsetPpm = Float.POSITIVE_INFINITY))

        val state = TransceiverViewModel.UiState(isRunning = true, radeV2Mode = true, clockOffsetPpm = 12f)
        assertTrue(state.showClockOffset)
        assertFalse(state.copy(isTx = true).showClockOffset)
        assertFalse(state.copy(radeV2Mode = false).showClockOffset)
        assertFalse(TransceiverViewModel.UiState().showClockOffset)
    }

    @Test fun clockOffsetBeyond125PpmIsOutOfSpecInEitherDirection() {
        for (ppm in listOf(0f, 37f, -37f, 124.9f, -124.9f, 125f, -125f)) {
            assertFalse("$ppm", clockOffsetOutOfSpec(ppm))
        }
        for (ppm in listOf(125.1f, -125.1f, 180f, -250f)) {
            assertTrue("$ppm", clockOffsetOutOfSpec(ppm))
        }
        assertFalse(clockOffsetOutOfSpec(Float.NaN))
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

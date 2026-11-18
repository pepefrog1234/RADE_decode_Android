package yakumo2683.RADEdecode.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import yakumo2683.RADEdecode.R

/** RADE V2 tuning hint and clock offset on the receiver screen. */
@RunWith(AndroidJUnit4::class)
class TransceiverTuneHintTest {
    @get:Rule val compose = createComposeRule()

    private val state = mutableStateOf(TransceiverViewModel.UiState(isRunning = true, radeV2Mode = true))
    private val sideband = mutableStateOf(RxSideband.UPPER)

    private fun text(id: Int, vararg args: Any): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }

    private fun showReceiver() = compose.setContent {
        MaterialTheme { TransceiverContent(state.value, {}, {}, {}, {}, {}, rxSideband = sideband.value) }
    }

    private fun update(change: TransceiverViewModel.UiState.() -> TransceiverViewModel.UiState) =
        compose.runOnIdle { state.value = state.value.change() }

    private fun radioOn(newSideband: RxSideband) = compose.runOnIdle { sideband.value = newSideband }

    @Test fun hintNamesTheDialDirectionForTheRadiosSideband() {
        showReceiver()
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()

        // Signal too high in audio frequency: up on USB, down on LSB.
        update { copy(mistune = 1) }
        compose.onNodeWithTag("tune_hint").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up_detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sync_search)).assertIsDisplayed()

        radioOn(RxSideband.LOWER)
        compose.onNodeWithText(text(R.string.tune_hint_down)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertDoesNotExist()

        // Signal too low: the other way round.
        update { copy(mistune = -1) }
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertIsDisplayed()
        radioOn(RxSideband.UPPER)
        compose.onNodeWithText(text(R.string.tune_hint_down)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_down_detail)).assertIsDisplayed()
    }

    @Test fun unknownSidebandIsWordedAsAudioFrequencyNotAsADialDirection() {
        sideband.value = RxSideband.UNKNOWN
        state.value = state.value.copy(mistune = 1)
        showReceiver()
        compose.onNodeWithText(text(R.string.tune_hint_signal_high)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_signal_high_detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tune_hint_down)).assertDoesNotExist()

        update { copy(mistune = -1) }
        compose.onNodeWithText(text(R.string.tune_hint_signal_low)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_signal_low_detail)).assertIsDisplayed()
    }

    @Test fun hintDisappearsInSyncWithoutSignalInV1AndWhileNotReceiving() {
        state.value = state.value.copy(mistune = 1)
        showReceiver()
        compose.onNodeWithTag("tune_hint").assertIsDisplayed()

        update { copy(syncState = 2) }                       // tuned into range: SYNC
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sync_sync)).assertIsDisplayed()

        update { copy(syncState = 0) }
        compose.onNodeWithTag("tune_hint").assertIsDisplayed()
        update { copy(mistune = 0) }                         // signal gone
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()

        update { copy(mistune = 1, radeV2Mode = false) }     // V1
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()
        update { copy(radeV2Mode = true, isRunning = false) } // stopped
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()
    }

    @Test fun clockOffsetLineShowsWhatTheEstimateIsWorth() {
        showReceiver()
        // There from the start, so the screen below it does not jump later.
        compose.onNodeWithTag("clock_offset").assertIsDisplayed()
            .assertTextEquals(text(R.string.clock_offset_none))

        update { copy(clockOffsetPpm = 37.4f) }              // early in an over: still creeping up
        compose.onNodeWithTag("clock_offset").assertTextEquals(text(R.string.clock_offset_preliminary, "+37"))

        update { copy(clockOffsetPpm = 98.2f, clockOffsetSettled = true) }
        compose.onNodeWithTag("clock_offset").assertTextEquals(text(R.string.clock_offset, "+98"))

        update { copy(clockOffsetPpm = -180.2f, clockOffsetSettled = false) }
        compose.onNodeWithTag("clock_offset").assertTextEquals(text(R.string.clock_offset_out_of_spec, "-180"))

        update { copy(radeV2Mode = false) }                  // V1 has no estimate
        compose.onNodeWithTag("clock_offset").assertDoesNotExist()
    }
}

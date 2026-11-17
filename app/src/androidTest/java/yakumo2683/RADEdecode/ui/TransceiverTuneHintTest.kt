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
    private fun text(id: Int, vararg args: Any): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }

    private fun showReceiver() = compose.setContent {
        MaterialTheme { TransceiverContent(state.value, {}, {}, {}, {}, {}) }
    }

    private fun update(change: TransceiverViewModel.UiState.() -> TransceiverViewModel.UiState) =
        compose.runOnIdle { state.value = state.value.change() }

    @Test fun hintNamesTheDialDirectionForTheRadiosSideband() {
        showReceiver()
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()

        // Signal too high in audio frequency: up on USB, down on LSB.
        update { copy(mistune = 1, rxSideband = RxSideband.UPPER) }
        compose.onNodeWithTag("tune_hint").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up_detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sync_search)).assertIsDisplayed()

        update { copy(rxSideband = RxSideband.LOWER) }
        compose.onNodeWithText(text(R.string.tune_hint_down)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertDoesNotExist()

        // Signal too low: the other way round.
        update { copy(mistune = -1) }
        compose.onNodeWithText(text(R.string.tune_hint_up)).assertIsDisplayed()
        update { copy(rxSideband = RxSideband.UPPER) }
        compose.onNodeWithText(text(R.string.tune_hint_down)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tune_hint_down_detail)).assertIsDisplayed()
    }

    @Test fun unknownSidebandIsWordedAsAudioFrequencyNotAsADialDirection() {
        state.value = state.value.copy(mistune = 1, rxSideband = RxSideband.UNKNOWN)
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
        state.value = state.value.copy(mistune = 1, rxSideband = RxSideband.UPPER)
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
        update { copy(radeV2Mode = true, isTx = true) }      // transmitting
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()
        update { copy(isTx = false, isRunning = false) }     // stopped
        compose.onNodeWithTag("tune_hint").assertDoesNotExist()
    }

    @Test fun clockOffsetIsShownOnceEstimatedAndFlaggedBeyond125Ppm() {
        showReceiver()
        compose.onNodeWithTag("clock_offset").assertDoesNotExist()

        update { copy(clockOffsetPpm = 37.4f) }
        compose.onNodeWithTag("clock_offset").assertIsDisplayed()
            .assertTextEquals(text(R.string.clock_offset, "+37"))

        update { copy(clockOffsetPpm = -180.2f) }
        compose.onNodeWithTag("clock_offset").assertIsDisplayed()
            .assertTextEquals(text(R.string.clock_offset_out_of_spec, "-180"))

        update { copy(radeV2Mode = false) }                  // V1 has no estimate
        compose.onNodeWithTag("clock_offset").assertDoesNotExist()
    }
}

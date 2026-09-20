package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransceiverLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decodedCallsignAndWarningsDoNotMoveControlsOffSmallScreen() {
        val state = mutableStateOf(TransceiverViewModel.UiState(isRunning = true))
        var txClicks = 0
        var stopClicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) {
                        TransceiverContent(state.value, {}, { txClicks++ }, {}, {}, { stopClicks++ })
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("tx_control").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            state.value = state.value.copy(lastCallsign = "DJ4MO", analogMonitor = true,
                unprocessedRejected = true, pttControlError = true, rxRestartError = true)
        }
        compose.onNodeWithTag("tx_control").assertIsDisplayed().performClick()
        compose.onNodeWithTag("start_stop_control").assertIsDisplayed().performClick()
        assertEquals(before, compose.onNodeWithTag("tx_control").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("receiver_information").performTouchInput { swipeUp() }
        compose.onNodeWithTag("tx_control").assertIsDisplayed()
        compose.onNodeWithTag("start_stop_control").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, txClicks); assertEquals(1, stopClicks) }
    }

    @Test fun holdToTalkStillReleasesAfterTxHeaderReplacesCallsign() {
        val state = mutableStateOf(TransceiverViewModel.UiState(isRunning = true,
            pttHoldMode = true, lastCallsign = "DJ4MO"))
        var releases = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 360.dp)) {
                    TransceiverContent(state.value, {}, {},
                        { state.value = state.value.copy(isTx = true) }, { releases++ }, {})
                }
            }
        }
        compose.onNodeWithTag("tx_control").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("tx_control").assertIsDisplayed().performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, releases) }
        compose.onNodeWithTag("start_stop_control").assertIsDisplayed()
    }
}

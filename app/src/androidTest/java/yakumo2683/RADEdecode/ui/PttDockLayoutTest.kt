package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PttDockLayoutTest {
    @get:Rule val compose = createComposeRule()

    /** The dock as the Stations and Map tabs hold it: against the bottom edge. */
    private fun showDock(
        state: MutableState<TransceiverViewModel.UiState>,
        onToggleTx: () -> Unit = {},
        onHoldPress: () -> Unit = {},
        onHoldRelease: () -> Unit = {}
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 360.dp), contentAlignment = Alignment.BottomCenter) {
                    PttDockContent(state.value, 14_236_000, onToggleTx, onHoldPress, onHoldRelease)
                }
            }
        }
    }

    private fun txButtonBounds() = compose.onNodeWithTag("tx_control").fetchSemanticsNode().boundsInRoot

    @Test fun txStartAndErrorMessagesDoNotMoveTheButton() {
        val state = mutableStateOf(TransceiverViewModel.UiState(isRunning = true, syncState = 2, snrDb = 12))
        var toggles = 0
        showDock(state, onToggleTx = { toggles++ })
        val before = txButtonBounds()
        compose.runOnIdle { state.value = state.value.copy(isRunning = false, isTx = true, txLevelDb = -12f) }
        assertEquals(before, txButtonBounds())
        compose.runOnIdle {
            state.value = state.value.copy(pttControlError = true, pttStillReceiving = true, txInputError = true)
        }
        assertEquals(before, txButtonBounds())
        compose.onNodeWithTag("tx_control").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun holdToTalkStaysHeldWhenTheDockTurnsToTx() {
        val state = mutableStateOf(TransceiverViewModel.UiState(isRunning = true, pttHoldMode = true))
        var releases = 0
        showDock(
            state,
            onHoldPress = { state.value = state.value.copy(isRunning = false, isTx = true) },
            onHoldRelease = { releases++ }
        )
        compose.onNodeWithTag("tx_control").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.value.isTx)
            assertEquals(0, releases)
        }
        compose.onNodeWithTag("tx_control").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, releases) }
    }
}

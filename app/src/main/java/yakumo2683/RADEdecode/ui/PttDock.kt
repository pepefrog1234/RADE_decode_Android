package yakumo2683.RADEdecode.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import yakumo2683.RADEdecode.R
import yakumo2683.RADEdecode.ui.theme.*

private val TxDockBackground = Color(0xFF2A1416)

/**
 * The TX button for the Stations and Map tabs, placed above the navigation
 * bar: transmit while watching who receives the signal. Shown under the same
 * condition as the Receiver tab's TX button, i.e. while the engine is active.
 */
@Composable
fun PttDock(viewModel: TransceiverViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val frequencyHz by viewModel.reportFreqHz.collectAsState()

    // Hold-to-talk keys the rig only while a finger is on the button. When the
    // dock leaves the screen (a tab without it, rotation, Back) that finger has
    // nothing left to let go of, so let go for it. Only while this app is
    // transmitting: after Stop there is nothing to release, and a release
    // would bring RX back.
    DisposableEffect(viewModel) {
        onDispose {
            val last = viewModel.uiState.value
            if (last.pttHoldMode && (last.isTx || last.txSwitching)) viewModel.pttHoldRelease()
        }
    }

    if (state.isRunning || state.isTx || state.txSwitching) {
        PttDockContent(
            state = state,
            frequencyHz = frequencyHz,
            onToggleTx = { if (state.isTx) viewModel.switchToRx() else viewModel.switchToTx() },
            onHoldPress = viewModel::switchToTx,
            onHoldRelease = viewModel::pttHoldRelease,
            modifier = modifier
        )
    }
}

@Composable
internal fun PttDockContent(
    state: TransceiverViewModel.UiState,
    frequencyHz: Long,
    onToggleTx: () -> Unit,
    onHoldPress: () -> Unit,
    onHoldRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(
        targetValue = if (state.isTx) TxDockBackground else MaterialTheme.colorScheme.surface,
        animationSpec = tween(300), label = "pttDock"
    )
    val edge by animateColorAsState(
        targetValue = if (state.isTx) Red400 else MaterialTheme.colorScheme.outline,
        animationSpec = tween(300), label = "pttDockEdge"
    )
    Column(
        modifier = modifier
            .testTag("ptt_dock")
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    ) {
        HorizontalDivider(color = edge)
        // Above the button, and the dock is anchored at its bottom edge: a
        // message that appears while the button is held does not move it.
        val errors = txErrorMessages(state)
        if (errors.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp)
            ) {
                for (message in errors) {
                    Text(stringResource(message), color = Red400, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockStatus(state, frequencyHz, Modifier.weight(0.4f))
            // One call site for RX and TX: a button that is being held must
            // stay the same button, in the same place, when TX starts.
            TxButton(
                isTx = state.isTx,
                holdMode = state.pttHoldMode,
                onClick = onToggleTx,
                onHoldPress = onHoldPress,
                onHoldRelease = onHoldRelease,
                modifier = Modifier.weight(0.6f),
                contentPadding = PaddingValues(horizontal = 12.dp)
            )
        }
    }
}

/** RX: sync state, SNR and dial frequency. TX: time on air and microphone level. */
@Composable
private fun DockStatus(
    state: TransceiverViewModel.UiState,
    frequencyHz: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.isTx) {
            StatusLine(Red400, stringResource(R.string.ptt_dock_on_air), txElapsed(state.txStartedAtMs))
            LevelBar(state.txLevelDb, Modifier.fillMaxWidth())
        } else {
            StatusLine(
                color = syncStateColor(state.syncState),
                label = stringResource(syncStateLabelRes(state.syncState)),
                value = if (state.isSynced) "${state.snrDb} ${stringResource(R.string.unit_db)}" else null
            )
            if (frequencyHz > 0) {
                Text(
                    text = "%.3f MHz".format(frequencyHz / 1_000_000.0),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceDim,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StatusLine(color: Color, label: String, value: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (value != null) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** "m:ss" since [startedAtMs] on the [SystemClock.elapsedRealtime] clock; null when that is not known. */
@Composable
private fun txElapsed(startedAtMs: Long): String? {
    if (startedAtMs <= 0) return null
    fun elapsedMs() = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0)
    // Starts from the real value: the dock can come up in the middle of an over.
    val seconds by produceState(elapsedMs() / 1000, startedAtMs) {
        while (true) {
            val elapsed = elapsedMs()
            value = elapsed / 1000
            delay(1000 - elapsed % 1000)
        }
    }
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

package yakumo2683.RADEdecode.ui

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import yakumo2683.RADEdecode.data.AppDatabase
import yakumo2683.RADEdecode.data.ReceptionSession
import yakumo2683.RADEdecode.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val _sessions = MutableStateFlow<List<ReceptionSession>>(emptyList())
    val sessions: StateFlow<List<ReceptionSession>> = _sessions

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _sessions.value = db.getAllSessions() }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            db.deleteSession(sessionId)
            refresh()
        }
    }

    fun deleteAllSessions() {
        viewModelScope.launch {
            db.deleteAllSessions()
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(),
    onSessionClick: (Long) -> Unit = {}
) {
    val sessions by viewModel.sessions.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            containerColor = SurfaceCard,
            title = { Text("Delete All Sessions") },
            text = { Text("This will permanently delete all reception logs. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllSessions()
                        showDeleteAllDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Red400)
                ) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header with clear all button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECEPTION LOG",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Cyan400
            )
            if (sessions.isNotEmpty()) {
                TextButton(
                    onClick = { showDeleteAllDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Red400),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear All", fontSize = 12.sp)
                }
            }
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(48.dp), tint = OnSurfaceDim)
                    Spacer(Modifier.height(12.dp))
                    Text("No reception sessions yet", fontSize = 16.sp, color = OnSurfaceDim)
                    Spacer(Modifier.height(4.dp))
                    Text("Start receiving to create a session", fontSize = 13.sp, color = OnSurfaceDim.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteSession(session.id)
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            // Red delete background revealed on swipe
                            val color by animateColorAsState(
                                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                    Red400 else SurfaceCard,
                                label = "swipe"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(color)
                                    .padding(end = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(Icons.Default.Delete, "Delete", tint = androidx.compose.ui.graphics.Color.White)
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true
                    ) {
                        SessionCard(session, dateFormat) { onSessionClick(session.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ReceptionSession,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val duration = session.endTime?.let { (it - session.startTime) / 1000 } ?: 0
    val syncRatio = if (session.totalModemFrames > 0)
        session.syncedFrames.toFloat() / session.totalModemFrames * 100f
    else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceCard
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(session.startTime)),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatDuration(duration),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Cyan400
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SignalCellularAlt, null,
                        modifier = Modifier.size(14.dp),
                        tint = if (syncRatio > 50f) GreenBright else OnSurfaceDim
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Sync %.0f%%".format(syncRatio), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = OnSurfaceDim)
                }
                Text("${session.totalModemFrames} frames", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = OnSurfaceDim)
                if (session.audioDevice.isNotEmpty()) {
                    Text(session.audioDevice, fontSize = 12.sp, color = OnSurfaceDim)
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

package yakumo2683.RADEdecode.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import yakumo2683.RADEdecode.data.*
import yakumo2683.RADEdecode.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val app = application

    private val _session = MutableStateFlow<ReceptionSession?>(null)
    val session: StateFlow<ReceptionSession?> = _session

    private val _snapshots = MutableStateFlow<List<SignalSnapshot>>(emptyList())
    val snapshots: StateFlow<List<SignalSnapshot>> = _snapshots

    private val _syncEvents = MutableStateFlow<List<SyncEvent>>(emptyList())
    val syncEvents: StateFlow<List<SyncEvent>> = _syncEvents

    private val _callsignEvents = MutableStateFlow<List<CallsignEvent>>(emptyList())
    val callsignEvents: StateFlow<List<CallsignEvent>> = _callsignEvents

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playProgress = MutableStateFlow(0f)
    val playProgress: StateFlow<Float> = _playProgress

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    fun load(sessionId: Long) {
        viewModelScope.launch {
            _session.value = db.getSession(sessionId)
            _snapshots.value = db.getSnapshots(sessionId)
            _syncEvents.value = db.getSyncEvents(sessionId)
            _callsignEvents.value = db.getCallsignEvents(sessionId)
        }
    }

    fun getWavFile(): java.io.File? {
        val filename = _session.value?.audioFilename ?: return null
        val file = java.io.File(app.filesDir, "recordings/$filename")
        return if (file.exists() && file.length() > 44) file else null
    }

    fun togglePlayback() {
        if (_isPlaying.value) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val file = getWavFile() ?: return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _playProgress.value = 0f
                    progressJob?.cancel()
                }
            }
            _isPlaying.value = true
            progressJob = viewModelScope.launch {
                while (_isPlaying.value) {
                    val mp = mediaPlayer
                    if (mp != null && mp.isPlaying) {
                        _playProgress.value = mp.currentPosition.toFloat() / mp.duration.toFloat()
                    }
                    kotlinx.coroutines.delay(200)
                }
            }
        } catch (e: Exception) {
            _isPlaying.value = false
        }
    }

    fun stopPlayback() {
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _playProgress.value = 0f
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = viewModel()
) {
    val session by viewModel.session.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()
    val syncEvents by viewModel.syncEvents.collectAsState()
    val callsignEvents by viewModel.callsignEvents.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    DisposableEffect(Unit) { onDispose { viewModel.stopPlayback() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Back + title
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Cyan400)
            }
            Text(
                "SESSION DETAIL",
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, color = Cyan400
            )
        }

        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan400)
            }
            return
        }

        // ── Overview card ──
        DetailCard {
            val duration = s.endTime?.let { (it - s.startTime) / 1000 } ?: 0
            val syncRatio = if (s.totalModemFrames > 0)
                s.syncedFrames.toFloat() / s.totalModemFrames * 100f else 0f

            DetailRow(Icons.Default.Schedule, "Start", dateFormat.format(Date(s.startTime)))
            s.endTime?.let {
                DetailRow(Icons.Default.Schedule, "End", dateFormat.format(Date(it)))
            }
            DetailRow(Icons.Default.Timer, "Duration", formatDetailDuration(duration))
            DetailRow(Icons.Default.SignalCellularAlt, "Sync Ratio", "%.1f%%".format(syncRatio))
            DetailRow(Icons.Default.Memory, "Modem Frames", "${s.totalModemFrames} (${s.syncedFrames} synced)")
            DetailRow(Icons.Default.SettingsInputAntenna, "Device", s.audioDevice)
        }

        // ── Audio playback ──
        val wavFile = viewModel.getWavFile()
        if (wavFile != null) {
            val isPlaying by viewModel.isPlaying.collectAsState()
            val progress by viewModel.playProgress.collectAsState()

            SectionLabel("DECODED AUDIO")
            DetailCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play/Stop button
                    FilledIconButton(
                        onClick = { viewModel.togglePlayback() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isPlaying) Red400 else Cyan400
                        )
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play"
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        // Progress bar
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Cyan400,
                            trackColor = Surface2
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (isPlaying) "Playing..." else wavFile.name,
                                fontSize = 11.sp,
                                color = OnSurfaceDim
                            )
                            Text(
                                "%.0f KB".format(wavFile.length() / 1024.0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        }

        // ── Callsigns decoded ──
        if (callsignEvents.isNotEmpty()) {
            SectionLabel("CALLSIGNS DECODED")
            DetailCard {
                callsignEvents.forEach { ev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ev.callsign,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = GreenBright
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "SNR ${ev.snrAtDecode} dB",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = Cyan400
                            )
                            Text(
                                "+${ev.offsetMs / 1000}s | frame ${ev.modemFrame}",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = OnSurfaceDim
                            )
                        }
                    }
                    if (ev != callsignEvents.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        // ── SNR chart ──
        if (snapshots.isNotEmpty()) {
            SectionLabel("SNR OVER TIME")
            SnrChart(snapshots, Modifier.fillMaxWidth().height(140.dp))
        }

        // ── Sync timeline ──
        if (syncEvents.isNotEmpty()) {
            SectionLabel("SYNC EVENTS")
            DetailCard {
                syncEvents.forEach { ev ->
                    val stateNames = arrayOf("SEARCH", "CANDIDATE", "SYNC")
                    val from = stateNames.getOrElse(ev.fromState) { "?" }
                    val to = stateNames.getOrElse(ev.toState) { "?" }
                    val toColor = when (ev.toState) {
                        2 -> GreenBright; 1 -> Amber400; else -> OnSurfaceDim
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "+${ev.offsetMs / 1000}s",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = OnSurfaceDim,
                                modifier = Modifier.width(50.dp)
                            )
                            Text("$from → ", fontSize = 12.sp, color = OnSurfaceDim)
                            Text(to, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = toColor)
                        }
                        Text(
                            "SNR ${ev.snrAtEvent}dB",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }

        // ── Signal snapshots summary ──
        if (snapshots.isNotEmpty()) {
            SectionLabel("SIGNAL SUMMARY")
            DetailCard {
                val snrValues = snapshots.map { it.snr }
                val peakSnr = snrValues.max()
                val avgSnr = snrValues.average()
                val freqValues = snapshots.map { it.freqOffset }

                DetailRow(Icons.Default.TrendingUp, "Peak SNR", "%.1f dB".format(peakSnr))
                DetailRow(Icons.Default.BarChart, "Avg SNR", "%.1f dB".format(avgSnr))
                DetailRow(Icons.Default.Tune, "Freq Offset Range",
                    "%.1f ~ %.1f Hz".format(freqValues.min(), freqValues.max()))
                DetailRow(Icons.Default.DataUsage, "Snapshots", "${snapshots.size}")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/* ── SNR Chart ───────────────────────────────────────────────── */

@Composable
private fun SnrChart(snapshots: List<SignalSnapshot>, modifier: Modifier) {
    val gridColor = Color(0xFF1E2530)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface0)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        val w = size.width
        val h = size.height
        if (snapshots.isEmpty()) return@Canvas

        val snrMin = -5f
        val snrMax = (snapshots.maxOf { it.snr } + 5f).coerceAtLeast(20f)
        val totalMs = snapshots.last().offsetMs - snapshots.first().offsetMs
        if (totalMs <= 0) return@Canvas

        // Grid
        for (db in generateSequence(0f) { it + 5f }.takeWhile { it <= snrMax }) {
            val y = h * (1f - (db - snrMin) / (snrMax - snrMin))
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
        }

        // SNR line
        val path = Path()
        snapshots.forEachIndexed { i, snap ->
            val x = w * (snap.offsetMs - snapshots.first().offsetMs).toFloat() / totalMs
            val y = h * (1f - (snap.snr - snrMin) / (snrMax - snrMin))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Cyan400, style = Stroke(width = 1.5f))

        // Sync colored background bands
        snapshots.forEachIndexed { i, snap ->
            if (i == 0) return@forEachIndexed
            val prev = snapshots[i - 1]
            val x0 = w * (prev.offsetMs - snapshots.first().offsetMs).toFloat() / totalMs
            val x1 = w * (snap.offsetMs - snapshots.first().offsetMs).toFloat() / totalMs
            val color = when (snap.syncState) {
                2 -> GreenBright.copy(alpha = 0.06f)
                1 -> Amber400.copy(alpha = 0.06f)
                else -> Color.Transparent
            }
            if (color != Color.Transparent) {
                drawRect(color, Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, h))
            }
        }
    }
}

/* ── Helpers ──────────────────────────────────────────────────── */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, color = Cyan400,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceCard,
        content = {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    )
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = OnSurfaceDim)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = OnSurfaceDim)
        }
        Text(
            value, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatDetailDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}

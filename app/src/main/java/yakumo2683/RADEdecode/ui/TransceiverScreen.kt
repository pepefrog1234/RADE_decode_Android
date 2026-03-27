package yakumo2683.RADEdecode.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import yakumo2683.RADEdecode.ui.theme.*

@Composable
fun TransceiverScreen(viewModel: TransceiverViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.startReceiving()
    }

    val isActive = state.isRunning || state.isTx  // engine is doing something

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Status header — shows TX state when transmitting, RX state otherwise ──
        if (state.isTx) {
            TxHeader()
        } else {
            SyncHeader(state)
        }

        // ── Signal info cards (RX) ──
        if (!state.isTx) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoCard("SNR", "${state.snrDb}", "dB", Modifier.weight(1f))
                InfoCard("FREQ", String.format("%.1f", state.freqOffsetHz), "Hz", Modifier.weight(1f))
            }
        }

        // ── Spectrum (RX) ──
        if (!state.isTx) {
            SpectrumChart(
                spectrum = state.spectrum,
                isSynced = state.isSynced,
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )

            WaterfallView(
                spectrum = state.spectrum,
                modifier = Modifier.fillMaxWidth().height(90.dp)
            )
        }

        // ── Level meters ──
        if (state.isTx) {
            LevelMeter("MIC INPUT", state.txLevelDb, Modifier.fillMaxWidth())
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LevelMeter("INPUT", state.inputLevelDb, Modifier.weight(1f))
                LevelMeter("OUTPUT", state.outputLevelDb, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.weight(1f))

        // ── TX button — only visible when engine is active ──
        if (isActive) {
            TxButton(
                isTx = state.isTx,
                onClick = {
                    if (state.isTx) {
                        viewModel.switchToRx()
                    } else {
                        viewModel.switchToTx()
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
        }

        // ── Start / Stop button ──
        StartStopButton(
            isRunning = isActive,
            onClick = {
                if (isActive) {
                    viewModel.stopAll()
                } else {
                    if (hasAudioPermission) viewModel.startReceiving()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        )
    }
}

/* ── Sync Header (RX) ────────────────────────────────────────── */

@Composable
private fun SyncHeader(state: TransceiverViewModel.UiState) {
    val syncColor by animateColorAsState(
        targetValue = when (state.syncState) {
            2 -> GreenBright
            1 -> Amber400
            else -> OnSurfaceDim
        },
        animationSpec = tween(300), label = "sync"
    )

    val bgBrush = when (state.syncState) {
        2 -> Brush.horizontalGradient(listOf(Color(0xFF003D00), Color(0xFF1B5E20), Color(0xFF003D00)))
        1 -> Brush.horizontalGradient(listOf(Color(0xFF3E2723), Color(0xFF4E342E), Color(0xFF3E2723)))
        else -> Brush.horizontalGradient(listOf(Surface2, Surface3, Surface2))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush)
            .border(1.dp, syncColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = syncColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = state.syncText,
                color = syncColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )
        }

        if (state.lastCallsign.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.lastCallsign,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/* ── TX Header ───────────────────────────────────────────────── */

@Composable
private fun TxHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(
                listOf(Color(0xFF3D0000), Color(0xFF5E1B1B), Color(0xFF3D0000))
            ))
            .border(1.dp, Red400.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = Red400,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "TRANSMITTING",
                color = Red400,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )
        }
    }
}

/* ── Info Card ────────────────────────────────────────────────── */

@Composable
private fun InfoCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = SurfaceCard,
        border = ButtonDefaults.outlinedButtonBorder(enabled = false),
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Cyan400
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    fontSize = 13.sp,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

/* ── Spectrum Chart ───────────────────────────────────────────── */

@Composable
fun SpectrumChart(spectrum: FloatArray, isSynced: Boolean, modifier: Modifier = Modifier) {
    val lineColor = if (isSynced) GreenBright else Cyan400
    val fillAlpha = if (isSynced) 0.15f else 0.08f
    val gridColor = Color(0xFF1E2530)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface0)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        val w = size.width
        val h = size.height
        val bins = spectrum.size
        if (bins == 0) return@Canvas

        val dbMin = -80f
        val dbMax = 0f

        // Grid
        for (db in listOf(-60f, -40f, -20f)) {
            val y = h * (1f - (db - dbMin) / (dbMax - dbMin))
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
        }
        for (i in 1..3) {
            val x = w * i / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 0.5f)
        }

        // Build path
        val path = Path()
        val fillPath = Path()
        var started = false

        for (i in 0 until bins) {
            val x = w * i.toFloat() / bins
            val db = spectrum[i].coerceIn(dbMin, dbMax)
            val y = h * (1f - (db - dbMin) / (dbMax - dbMin))
            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(0f, h)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Fill under curve
        fillPath.lineTo(w, h)
        fillPath.close()
        drawPath(fillPath, Brush.verticalGradient(
            listOf(lineColor.copy(alpha = fillAlpha), Color.Transparent)
        ))

        // Spectrum line
        drawPath(path, lineColor, style = Stroke(width = 1.5f))
    }
}

/* ── Waterfall View ───────────────────────────────────────────── */

@Composable
fun WaterfallView(spectrum: FloatArray, modifier: Modifier = Modifier) {
    val rows = remember { mutableStateListOf<FloatArray>() }
    val maxRows = 50

    LaunchedEffect(spectrum) {
        if (spectrum.any { it > -99f }) {
            rows.add(0, spectrum.copyOf())
            while (rows.size > maxRows) rows.removeAt(rows.size - 1)
        }
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF050810))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        val w = size.width
        val h = size.height
        if (rows.isEmpty()) return@Canvas

        val rowHeight = h / maxRows
        val dbMin = -80f
        val dbMax = -10f

        for (r in rows.indices) {
            val row = rows[r]
            val y = r * rowHeight
            val binWidth = w / row.size

            for (i in row.indices) {
                val db = row[i].coerceIn(dbMin, dbMax)
                val norm = (db - dbMin) / (dbMax - dbMin)
                drawRect(
                    color = waterfallColor(norm),
                    topLeft = Offset(i * binWidth, y),
                    size = Size(binWidth + 0.5f, rowHeight + 0.5f)
                )
            }
        }
    }
}

private fun waterfallColor(v: Float): Color {
    return when {
        v < 0.15f -> Color(0f, 0f, v / 0.15f * 0.4f)
        v < 0.35f -> {
            val t = (v - 0.15f) / 0.2f
            Color(0f, t * 0.6f, 0.4f + t * 0.4f)
        }
        v < 0.55f -> {
            val t = (v - 0.35f) / 0.2f
            Color(t * 0.3f, 0.6f + t * 0.4f, 0.8f - t * 0.6f)
        }
        v < 0.75f -> {
            val t = (v - 0.55f) / 0.2f
            Color(0.3f + t * 0.7f, 1f - t * 0.2f, 0.2f - t * 0.2f)
        }
        else -> {
            val t = (v - 0.75f) / 0.25f
            Color(1f, 0.8f - t * 0.8f, 0f)
        }
    }
}

/* ── Level Meter ──────────────────────────────────────────────── */

@Composable
fun LevelMeter(label: String, levelDb: Float, modifier: Modifier = Modifier) {
    val segments = 24
    val dbMin = -60f
    val dbMax = 0f
    val norm = ((levelDb - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
    val active = (norm * segments).toInt()

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Cyan400)
            Text(
                String.format("%.0f dB", levelDb),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = OnSurfaceDim
            )
        }
        Spacer(Modifier.height(3.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Surface0)
        ) {
            val segW = size.width / segments
            val gap = 1.5f
            for (i in 0 until segments) {
                val isActive = i < active
                val segColor = when {
                    i >= segments - 2 -> if (isActive) Color(0xFFFF1744) else Color(0xFF2A0A0A)
                    i >= segments - 5 -> if (isActive) Color(0xFFFFD600) else Color(0xFF2A2A0A)
                    else -> if (isActive) Color(0xFF00E676) else Color(0xFF0A2A0A)
                }
                drawRoundRect(
                    color = segColor,
                    topLeft = Offset(i * segW + gap, 1f),
                    size = Size(segW - gap * 2, size.height - 2f),
                    cornerRadius = CornerRadius(2f)
                )
            }
        }
    }
}

/* ── TX Button ───────────────────────────────────────────────── */

@Composable
private fun TxButton(isTx: Boolean, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (isTx) Color(0xFF880000) else Red400,
        animationSpec = tween(300), label = "txbtn"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor)
    ) {
        Icon(
            imageVector = if (isTx) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isTx) "BACK TO RX" else "TX",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
    }
}

/* ── Start / Stop Button ──────────────────────────────────────── */

@Composable
private fun StartStopButton(isRunning: Boolean, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (isRunning) Red400 else Cyan600,
        animationSpec = tween(300), label = "btn"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isRunning) "STOP" else "START",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
    }
}

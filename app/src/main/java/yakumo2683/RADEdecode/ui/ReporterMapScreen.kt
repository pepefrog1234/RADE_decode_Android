package yakumo2683.RADEdecode.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import yakumo2683.RADEdecode.location.LocationTracker
import yakumo2683.RADEdecode.network.FreeDVReporter
import yakumo2683.RADEdecode.ui.theme.*
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class MapStation(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val transmitting: Boolean,
    val receiving: Boolean,
    val receivedCallsign: String,
    val snr: Int,
    val frequency: Long
)

@Composable
fun ReporterMapScreen(reporter: FreeDVReporter?) {
    val stations by reporter?.stations?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<String, FreeDVReporter.ReporterStation>()) }

    val mapStations = remember(stations) {
        val raw = stations.values
            .filter { it.callsign.isNotEmpty() && it.gridSquare.length >= 2 }
            .mapNotNull { st ->
                LocationTracker.fromMaidenhead(st.gridSquare)?.let { (lat, lon) ->
                    MapStation(st.callsign, lat, lon, st.transmitting, st.receiving, st.receivedCallsign, st.snr, st.frequency)
                }
            }
        // Infer TX status: if anyone is receiving callsign X, X is transmitting
        val inferredTx = raw
            .filter { it.receivedCallsign.isNotEmpty() }
            .map { it.receivedCallsign.uppercase() }
            .toSet()
        raw.map { st ->
            if (!st.transmitting && st.callsign.uppercase() in inferredTx) {
                st.copy(transmitting = true)
            } else st
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewRef?.onResume()
                    reporter?.forceFlush()
                    mapViewRef?.invalidate()
                }
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onDetach()
        }
    }

    val overlay = remember { StationOverlay() }
    val txCount = mapStations.count { it.transmitting }
    val rxCount = mapStations.count { (it.receiving || it.receivedCallsign.isNotEmpty()) && !it.transmitting }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val config = Configuration.getInstance()
                config.userAgentValue = "RADEdecode/1.0"
                config.osmdroidBasePath = ctx.filesDir
                config.osmdroidTileCache = java.io.File(ctx.cacheDir, "osmdroid")

                MapView(ctx).also { mapViewRef = it }.apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    minZoomLevel = 2.0
                    maxZoomLevel = 18.0
                    controller.setZoom(3.0)
                    controller.setCenter(GeoPoint(35.0, 135.0))
                    overlayManager.tilesOverlay.setColorFilter(
                        android.graphics.ColorMatrixColorFilter(
                            floatArrayOf(
                                0.25f, 0f, 0f, 0f, 0f,
                                0f, 0.25f, 0f, 0f, 0f,
                                0f, 0f, 0.35f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                    overlays.add(overlay)
                }
            },
            update = { mapView ->
                overlay.stations = mapStations
                if (!overlay.fitted && mapStations.isNotEmpty() && mapView.width > 0) {
                    overlay.fitted = true
                    val lats = mapStations.map { it.lat }
                    val lons = mapStations.map { it.lon }
                    val box = BoundingBox(
                        lats.max() + 3.0, lons.max() + 5.0,
                        lats.min() - 3.0, lons.min() - 5.0
                    )
                    mapView.post { mapView.zoomToBoundingBox(box, false, 50) }
                }
                mapView.invalidate()
            }
        )

        // Legend panel (bottom-left)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(Color(0xFFFF4444), "TX", txCount)
            LegendDot(Color(0xFF44EE44), "RX", rxCount)
            LegendDot(Color(0xFF777777), "Idle", mapStations.size - txCount - rxCount)
        }

        // Info button (top-right)
        var showHelp by remember { mutableStateOf(false) }
        IconButton(
            onClick = { showHelp = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        }

        if (showHelp) {
            MapHelpDialog(onDismiss = { showHelp = false })
        }
    }
}

@Composable
private fun MapHelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1A2E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("\u5730\u5716\u8aaa\u660e", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            HelpSection("\u96fb\u53f0\u72c0\u614b") {
                HelpItem(Color(0xFFFF4444), "\u7d05\u8272 \u2014 \u6b63\u5728\u767c\u5c04 (TX)")
                HelpItem(Color(0xFF44EE44), "\u7da0\u8272 \u2014 \u6b63\u5728\u63a5\u6536\u8a0a\u865f (RX)")
                HelpItem(Color(0xFF777777), "\u7070\u8272 \u2014 \u9592\u7f6e / \u5f85\u6a5f")
            }

            HelpSection("\u4fe1\u865f\u8def\u5f91") {
                HelpItem(Color(0xFFFF8866), "\u7d05\u2192\u7da0\u6f38\u5c64 \u2014 \u5df2\u78ba\u8a8d\u8def\u5f91\uff08\u900f\u904e EOO \u89e3\u78bc\u51fa\u547c\u865f\uff09")
                HelpItem(Color(0xFFAAAAFF), "\u85cd\u8272\u865b\u7dda \u2014 \u63a8\u6e2c\u8def\u5f91\uff08\u540c\u983b\u7387\u00b15kHz\uff0c\u5c1a\u672a\u89e3\u78bc\u547c\u865f\uff09")
            }

            HelpSection("\u6a19\u7c64\u8aaa\u660e") {
                Text("\u7b2c\u4e00\u884c\uff1a\u547c\u865f\uff08\u5927\u5beb\uff09", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Text("\u7b2c\u4e8c\u884c\uff1a\u983b\u7387 (MHz) + \u8a0a\u566a\u6bd4 (dB)", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }

            HelpSection("\u8a0a\u566a\u6bd4\u6a19\u7c64") {
                HelpItem(Color(0xFF22AA22), "\u226510 dB \u2014 \u4fe1\u865f\u826f\u597d")
                HelpItem(Color(0xFFCC8800), "\u22653 dB \u2014 \u4fe1\u865f\u666e\u901a")
                HelpItem(Color(0xFFCC3333), "<3 dB \u2014 \u4fe1\u865f\u5fae\u5f31")
            }

            HelpSection("\u624b\u52e2\u64cd\u4f5c") {
                Text("\u96d9\u6307\u7e2e\u653e\uff0c\u55ae\u6307\u62d6\u66f3\u5e73\u79fb", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }

            Text(
                "\u8cc7\u6599\u4f86\u6e90\uff1aqso.freedv.org",
                color = Cyan400,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Cyan400, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun HelpItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color) }
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

// ─── Overlay ────────────────────────────────────────────────────────

private class StationOverlay : Overlay() {
    var stations: List<MapStation> = emptyList()
    var fitted = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 1f, 1f, 0xAA000000.toInt())
    }
    private val snrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val byCallsign = stations.associateBy { it.callsign.uppercase() }

        // ── Build all signal paths (confirmed + inferred) ──
        data class SignalPath(
            val tx: MapStation, val rx: MapStation,
            val snr: Int, val inferred: Boolean
        )

        val paths = mutableListOf<SignalPath>()

        // 1) Confirmed: RX decoded the TX callsign
        for (rx in stations) {
            if (rx.receivedCallsign.isEmpty()) continue
            val tx = byCallsign[rx.receivedCallsign.uppercase()] ?: continue
            paths.add(SignalPath(tx, rx, rx.snr, inferred = false))
        }

        // 2) Inferred: RX is receiving (no callsign) on same freq as a TX station
        val txStations = stations.filter { it.transmitting && it.frequency > 0 }
        for (rx in stations) {
            if (rx.transmitting || !rx.receiving) continue
            if (rx.receivedCallsign.isNotEmpty()) continue // already confirmed
            if (rx.frequency <= 0) continue
            // Find TX on same frequency (±5 kHz tolerance)
            val tx = txStations.firstOrNull { kotlin.math.abs(it.frequency - rx.frequency) < 5000 }
            if (tx != null && tx.callsign.uppercase() != rx.callsign.uppercase()) {
                paths.add(SignalPath(tx, rx, rx.snr, inferred = true))
            }
        }

        // ── Draw signal paths (great circle arcs) ──
        for (path in paths) {
            val tx = path.tx; val rx = path.rx

            // Interpolate great circle points
            val gcPoints = greatCirclePoints(tx.lat, tx.lon, rx.lat, rx.lon, 32)
            val screenPts = gcPoints.map { (lat, lon) ->
                val pt = proj.toPixels(GeoPoint(lat, lon), null)
                pt.x.toFloat() to pt.y.toFloat()
            }
            if (screenPts.size < 2) continue

            val (x1, y1) = screenPts.first()
            val (x2, y2) = screenPts.last()
            val dx = x2 - x1; val dy = y2 - y1
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 20f) continue

            // Build path from great circle points
            val arcPath = Path().apply {
                moveTo(screenPts[0].first, screenPts[0].second)
                for (i in 1 until screenPts.size) {
                    lineTo(screenPts[i].first, screenPts[i].second)
                }
            }

            // Line style
            if (path.inferred) {
                linePaint.shader = null
                linePaint.color = 0x66AAAAFF.toInt()
                linePaint.strokeWidth = 1.5f
            } else {
                linePaint.shader = LinearGradient(
                    x1, y1, x2, y2,
                    0xCCFF5555.toInt(), 0xCC44EE44.toInt(),
                    Shader.TileMode.CLAMP
                )
                linePaint.strokeWidth = 2.5f
            }
            canvas.drawPath(arcPath, linePaint)
            linePaint.shader = null
            linePaint.strokeWidth = 2.5f

            // Arrow (use direction from first two screen points)
            val angle = atan2(dy, dx)
            if (dist > 60f && screenPts.size >= 2) {
                val (ax0, ay0) = screenPts[0]
                val (ax1, ay1) = screenPts[1]
                val aAngle = atan2(ay1 - ay0, ax1 - ax0)
                val arrowSize = 12f
                val ax = ax0 + cos(aAngle) * 22f
                val ay = ay0 + sin(aAngle) * 22f
                val arrowPath = Path().apply {
                    moveTo(ax, ay)
                    lineTo(
                        ax - cos(aAngle - 0.5f) * arrowSize,
                        ay - sin(aAngle - 0.5f) * arrowSize
                    )
                    lineTo(
                        ax - cos(aAngle + 0.5f) * arrowSize,
                        ay - sin(aAngle + 0.5f) * arrowSize
                    )
                    close()
                }
                arrowPaint.color = if (path.inferred) 0x88AAAAFF.toInt() else 0xDDFF6666.toInt()
                canvas.drawPath(arrowPath, arrowPaint)
            }

            // SNR badge at arc midpoint
            val mid = screenPts[screenPts.size / 2]
            val mx = mid.first; val my = mid.second
            if (path.snr != 0) {
                val snrText = "${path.snr} dB"
                val tw = snrPaint.measureText(snrText)
                val bw = tw / 2 + 10; val bh = 16f
                val badgeColor = when {
                    path.inferred -> 0xAA555588.toInt()
                    path.snr >= 10 -> 0xDD22AA22.toInt()
                    path.snr >= 3 -> 0xDDCC8800.toInt()
                    else -> 0xDDCC3333.toInt()
                }
                badgePaint.color = badgeColor
                canvas.drawRoundRect(mx - bw, my - bh, mx + bw, my + bh, 8f, 8f, badgePaint)
                badgeStrokePaint.color = if (path.inferred) 0x33FFFFFF else 0x44FFFFFF
                canvas.drawRoundRect(mx - bw, my - bh, mx + bw, my + bh, 8f, 8f, badgeStrokePaint)
                snrPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawText(snrText, mx, my + 7f, snrPaint)
            }
        }

        // ── Station markers ──
        for (st in stations) {
            val pt = proj.toPixels(GeoPoint(st.lat, st.lon), null)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()

            val isTx = st.transmitting
            val isRx = (st.receiving || st.receivedCallsign.isNotEmpty()) && !isTx
            val color = when {
                isTx -> 0xFFFF4444.toInt()
                isRx -> 0xFF44EE44.toInt()
                else -> 0xFF777777.toInt()
            }

            // Glow
            if (isTx) {
                glowPaint.color = 0x25FF4444
                canvas.drawCircle(x, y, 36f, glowPaint)
                glowPaint.color = 0x18FF4444
                canvas.drawCircle(x, y, 52f, glowPaint)
            } else if (isRx) {
                glowPaint.color = 0x2044EE44
                canvas.drawCircle(x, y, 28f, glowPaint)
            }

            // Outer ring
            dotPaint.style = Paint.Style.STROKE
            dotPaint.strokeWidth = 2.5f
            dotPaint.color = color
            val r = if (isTx) 11f else if (isRx) 8f else 6f
            canvas.drawCircle(x, y, r, dotPaint)

            // Inner fill
            dotPaint.style = Paint.Style.FILL
            dotPaint.color = (color and 0x00FFFFFF) or 0xBB000000.toInt()
            canvas.drawCircle(x, y, r - 1.5f, dotPaint)

            // Label (callsign always uppercase + info line)
            labelPaint.color = color
            labelPaint.textSize = if (isTx) 28f else 24f
            canvas.drawText(st.callsign.uppercase(), x + r + 6f, y - 8f, labelPaint)

            // Info line: frequency + SNR
            val infoParts = mutableListOf<String>()
            if (st.frequency > 0) infoParts.add("%.3f".format(st.frequency / 1_000_000.0))
            if (isRx && st.snr != 0) infoParts.add("${st.snr}dB")
            if (infoParts.isNotEmpty()) {
                labelPaint.textSize = 18f
                labelPaint.color = (color and 0x00FFFFFF) or 0x99000000.toInt()
                canvas.drawText(infoParts.joinToString(" "), x + r + 6f, y + 12f, labelPaint)
            }
        }
    }
}

// ─── Legend ──────────────────────────────────────────────────────────

@Composable
private fun LegendDot(color: Color, label: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color, radius = size.minDimension / 2)
        }
        Text(
            "$label $count",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

// ─── Great circle interpolation ─────────────────────────────────────

/**
 * Compute intermediate points along the great circle arc between two coordinates.
 * Uses spherical interpolation (slerp) for accurate geodesic paths.
 */
private fun greatCirclePoints(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double,
    segments: Int
): List<Pair<Double, Double>> {
    val r1 = Math.toRadians(lat1); val r2 = Math.toRadians(lat2)
    val l1 = Math.toRadians(lon1); val l2 = Math.toRadians(lon2)

    // Central angle (haversine)
    val dlat = r2 - r1; val dlon = l2 - l1
    val a = sin(dlat / 2) * sin(dlat / 2) +
            cos(r1) * cos(r2) * sin(dlon / 2) * sin(dlon / 2)
    val d = 2 * asin(sqrt(a).coerceAtMost(1.0))

    if (d < 1e-10) return listOf(lat1 to lon1, lat2 to lon2)

    val points = mutableListOf<Pair<Double, Double>>()
    for (i in 0..segments) {
        val f = i.toDouble() / segments
        val A = sin((1 - f) * d) / sin(d)
        val B = sin(f * d) / sin(d)
        val x = A * cos(r1) * cos(l1) + B * cos(r2) * cos(l2)
        val y = A * cos(r1) * sin(l1) + B * cos(r2) * sin(l2)
        val z = A * sin(r1) + B * sin(r2)
        val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        val lon = Math.toDegrees(atan2(y, x))
        points.add(lat to lon)
    }
    return points
}

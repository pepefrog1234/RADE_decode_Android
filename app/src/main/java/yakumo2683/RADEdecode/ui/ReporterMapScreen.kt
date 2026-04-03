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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val snr: Int
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
                    MapStation(st.callsign, lat, lon, st.transmitting, st.receiving, st.receivedCallsign, st.snr)
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
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
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

        // Legend panel (bottom-left, semi-transparent)
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

        // ── Signal paths ──
        for (rx in stations) {
            if (rx.receivedCallsign.isEmpty()) continue
            val tx = byCallsign[rx.receivedCallsign.uppercase()] ?: continue
            val txPt = proj.toPixels(GeoPoint(tx.lat, tx.lon), null)
            val rxPt = proj.toPixels(GeoPoint(rx.lat, rx.lon), null)

            val x1 = txPt.x.toFloat(); val y1 = txPt.y.toFloat()
            val x2 = rxPt.x.toFloat(); val y2 = rxPt.y.toFloat()
            val dx = x2 - x1; val dy = y2 - y1
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 20f) continue

            // Gradient line: red(TX) → green(RX)
            linePaint.shader = LinearGradient(
                x1, y1, x2, y2,
                0xCCFF5555.toInt(), 0xCC44EE44.toInt(),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(x1, y1, x2, y2, linePaint)
            linePaint.shader = null

            // Arrow at TX end pointing AWAY from TX (signal direction: TX → RX)
            val angle = atan2(dy, dx)
            if (dist > 60f) {
                val arrowSize = 12f
                val ax = x1 + cos(angle) * 22f
                val ay = y1 + sin(angle) * 22f
                val path = Path().apply {
                    moveTo(ax, ay)
                    lineTo(
                        ax - cos(angle - 0.5f) * arrowSize,
                        ay - sin(angle - 0.5f) * arrowSize
                    )
                    lineTo(
                        ax - cos(angle + 0.5f) * arrowSize,
                        ay - sin(angle + 0.5f) * arrowSize
                    )
                    close()
                }
                arrowPaint.color = 0xDDFF6666.toInt()
                canvas.drawPath(path, arrowPaint)
            }

            // SNR badge at midpoint
            val mx = (x1 + x2) / 2f; val my = (y1 + y2) / 2f
            val snrText = "${rx.snr} dB"
            val tw = snrPaint.measureText(snrText)
            val bw = tw / 2 + 10; val bh = 16f

            // Badge color based on SNR
            val badgeColor = when {
                rx.snr >= 10 -> 0xDD22AA22.toInt()  // green
                rx.snr >= 3 -> 0xDDCC8800.toInt()    // yellow
                else -> 0xDDCC3333.toInt()             // red
            }
            badgePaint.color = badgeColor
            canvas.drawRoundRect(mx - bw, my - bh, mx + bw, my + bh, 8f, 8f, badgePaint)
            badgeStrokePaint.color = 0x44FFFFFF
            canvas.drawRoundRect(mx - bw, my - bh, mx + bw, my + bh, 8f, 8f, badgeStrokePaint)
            snrPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(snrText, mx, my + 7f, snrPaint)
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

            // Label
            labelPaint.color = color
            labelPaint.textSize = if (isTx) 28f else 24f
            canvas.drawText(st.callsign, x + r + 6f, y - 6f, labelPaint)
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

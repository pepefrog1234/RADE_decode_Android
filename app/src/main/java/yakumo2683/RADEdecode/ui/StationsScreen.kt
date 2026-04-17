package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yakumo2683.RADEdecode.R
import yakumo2683.RADEdecode.network.FreeDVReporter
import yakumo2683.RADEdecode.ui.theme.*

private data class BandFilter(val label: String, val minHz: Long, val maxHz: Long)

private val bandFilters = listOf(
    BandFilter("All", 0, 0),
    BandFilter("160m", 1_800_000, 2_000_000),
    BandFilter("80m", 3_500_000, 4_000_000),
    BandFilter("40m", 7_000_000, 7_300_000),
    BandFilter("20m", 14_000_000, 14_350_000),
    BandFilter("15m", 21_000_000, 21_450_000),
    BandFilter("10m", 28_000_000, 29_700_000),
    BandFilter("VHF+", 50_000_000, Long.MAX_VALUE)
)

@Composable
fun StationsScreen(reporter: FreeDVReporter? = null) {
    val stations by reporter?.stations?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<String, FreeDVReporter.ReporterStation>()) }
    val isConnected by reporter?.connected?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isConnecting by reporter?.connecting?.collectAsState()
        ?: remember { mutableStateOf(false) }
    var selectedBand by remember { mutableStateOf(0) }

    val namedStations = stations.values.filter { it.callsign.isNotEmpty() }
    val filteredStations = run {
        val band = bandFilters[selectedBand]
        if (band.minHz == 0L) namedStations
        else namedStations.filter { it.frequency in band.minHz..band.maxHz }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.header_freedv_reporter),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = Cyan400,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        // Connection status — three states: connected / connecting / disconnected
        val statusColor = when {
            isConnected -> GreenBright
            isConnecting -> Cyan400
            else -> OnSurfaceDim
        }
        val statusText = when {
            isConnected -> stringResource(R.string.stations_connected, namedStations.size)
            isConnecting -> stringResource(R.string.stations_connecting)
            else -> stringResource(R.string.stations_not_connected)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isConnected || isConnecting) statusColor.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(10.dp)
                ),
            shape = RoundedCornerShape(10.dp),
            color = if (isConnected || isConnecting) statusColor.copy(alpha = 0.08f) else SurfaceCard
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnecting && !isConnected) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor
                    )
                } else {
                    Icon(
                        Icons.Default.CellTower, null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = statusColor
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Band filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bandFilters.forEachIndexed { index, band ->
                val selected = selectedBand == index
                FilterChip(
                    selected = selected,
                    onClick = { selectedBand = index },
                    label = { Text(band.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan400.copy(alpha = 0.2f),
                        selectedLabelColor = Cyan400
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filteredStations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.WifiOff, null,
                        modifier = Modifier.size(48.dp),
                        tint = OnSurfaceDim
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (isConnected) stringResource(R.string.stations_no_stations)
                               else stringResource(R.string.stations_enable_reporter),
                        fontSize = 16.sp,
                        color = OnSurfaceDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.stations_connect_hint),
                        fontSize = 13.sp,
                        color = OnSurfaceDim.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val sorted = filteredStations.sortedByDescending { it.lastUpdate }
                items(sorted, key = { it.connectionId }) { station ->
                    StationCard(station)
                }
            }
        }
    }
}

@Composable
private fun StationCard(station: FreeDVReporter.ReporterStation) {
    val isTx = station.transmitting
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isTx) Red400.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp)
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (isTx) Red400.copy(alpha = 0.15f) else SurfaceCard
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = station.callsign.ifEmpty { stringResource(R.string.stations_anonymous) },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (station.rxOnly) {
                        Icon(
                            Icons.Default.Headset, stringResource(R.string.stations_rx_only),
                            modifier = Modifier.size(14.dp),
                            tint = Cyan400
                        )
                    }
                }
                Text(
                    text = "${station.gridSquare} | ${station.mode}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (station.frequency > 0) {
                    Text(
                        text = "%.3f MHz".format(station.frequency / 1_000_000.0),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Cyan400
                    )
                }
                Text(
                    text = station.version,
                    fontSize = 10.sp,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

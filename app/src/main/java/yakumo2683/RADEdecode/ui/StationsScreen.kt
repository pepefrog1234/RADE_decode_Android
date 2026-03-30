package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
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

@Composable
fun StationsScreen(reporter: FreeDVReporter? = null) {
    val stations by reporter?.stations?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<String, FreeDVReporter.ReporterStation>()) }
    val isConnected by reporter?.connected?.collectAsState()
        ?: remember { mutableStateOf(false) }

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

        // Connection status
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isConnected) GreenBright.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(10.dp)
                ),
            shape = RoundedCornerShape(10.dp),
            color = if (isConnected) GreenBright.copy(alpha = 0.08f) else SurfaceCard
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CellTower, null,
                    tint = if (isConnected) GreenBright else OnSurfaceDim,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isConnected) stringResource(R.string.stations_connected, stations.size)
                           else stringResource(R.string.stations_not_connected),
                    fontSize = 13.sp,
                    color = if (isConnected) GreenBright else OnSurfaceDim
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (stations.isEmpty()) {
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
                val sorted = stations.values.sortedByDescending { it.lastUpdate }
                items(sorted, key = { it.connectionId }) { station ->
                    StationCard(station)
                }
            }
        }
    }
}

@Composable
private fun StationCard(station: FreeDVReporter.ReporterStation) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = SurfaceCard
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

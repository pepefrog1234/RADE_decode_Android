package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    // 60m: widest range that covers US channelised + UK/EU band segments
    BandFilter("60m", 5_250_000, 5_450_000),
    BandFilter("40m", 7_000_000, 7_300_000),
    BandFilter("30m", 10_100_000, 10_150_000),
    BandFilter("20m", 14_000_000, 14_350_000),
    BandFilter("17m", 18_068_000, 18_168_000),
    BandFilter("15m", 21_000_000, 21_450_000),
    BandFilter("12m", 24_890_000, 24_990_000),
    BandFilter("10m", 28_000_000, 29_700_000),
    BandFilter("VHF+", 50_000_000, Long.MAX_VALUE)
)

/** The band chip a frequency falls under, null between the bands. */
private fun bandLabel(hz: Long): String? =
    bandFilters.firstOrNull { it.minHz > 0 && hz in it.minHz..it.maxHz }?.label

/** Name of an order on the selector itself. */
private val StationSort.labelRes: Int
    get() = when (this) {
        StationSort.FREQUENCY -> R.string.stations_sort_frequency
        StationSort.CALLSIGN -> R.string.stations_sort_callsign
        StationSort.RECENT -> R.string.stations_sort_recent
    }

/** The same order spelled out in the selector's menu. */
private val StationSort.menuLabelRes: Int
    get() = when (this) {
        StationSort.FREQUENCY -> R.string.stations_sort_frequency_menu
        StationSort.CALLSIGN -> R.string.stations_sort_callsign_menu
        StationSort.RECENT -> R.string.stations_sort_recent_menu
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationsScreen(
    reporter: FreeDVReporter? = null,
    txCallsign: String = "",
    reporterEnabled: Boolean = true,
    sort: StationSort = StationSort.FREQUENCY,
    onSortChange: (StationSort) -> Unit = {}
) {
    val stations by reporter?.stations?.collectAsState()
        ?: remember { mutableStateOf(emptyMap<String, FreeDVReporter.ReporterStation>()) }
    val isConnected by reporter?.connected?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isConnecting by reporter?.connecting?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val lastError by reporter?.lastError?.collectAsState()
        ?: remember { mutableStateOf("") }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.header_freedv_reporter),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Cyan400
            )
            SortSelector(sort, onSortChange)
        }

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

        // Diagnostic hint — visible only when disconnected & not actively connecting
        if (!isConnected && !isConnecting) {
            val hint = when {
                !reporterEnabled -> stringResource(R.string.stations_hint_enable_in_settings)
                txCallsign.isEmpty() -> stringResource(R.string.stations_hint_set_callsign)
                lastError.isNotEmpty() -> stringResource(R.string.stations_hint_error, lastError)
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = OnSurfaceDim,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
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
            val listState = rememberLazyListState()
            // A newly chosen order is shown from its top. Left alone, the list
            // would follow the row that was first to wherever it went.
            var shownSort by remember { mutableStateOf(sort) }
            LaunchedEffect(sort) {
                if (shownSort != sort) {
                    shownSort = sort
                    listState.scrollToItem(0)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag("station_list"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (sort == StationSort.FREQUENCY) {
                    for (group in groupByFrequency(filteredStations)) {
                        stickyHeader(key = "frequency-${group.frequencyKhz}", contentType = "frequency") {
                            FrequencyHeader(group)
                        }
                        // The header carries the frequency of these rows.
                        items(group.stations, key = { it.connectionId }, contentType = { "station" }) { station ->
                            StationCard(station, showFrequency = false)
                        }
                    }
                } else {
                    items(
                        sortStations(filteredStations, sort),
                        key = { it.connectionId },
                        contentType = { "station" }
                    ) { station ->
                        StationCard(station, showFrequency = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SortSelector(sort: StationSort, onSortChange: (StationSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .testTag("station_sort")
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable(
                    onClickLabel = stringResource(R.string.stations_sort),
                    role = Role.DropdownList
                ) { expanded = true }
                .padding(start = 8.dp, top = 5.dp, end = 2.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.SwapVert, stringResource(R.string.stations_sort),
                modifier = Modifier.size(16.dp),
                tint = Cyan400
            )
            Text(
                text = stringResource(sort.labelRes),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Cyan400
            )
            Icon(
                Icons.Default.ArrowDropDown, null,
                modifier = Modifier.size(18.dp),
                tint = Cyan400
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StationSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.menuLabelRes)) },
                    onClick = {
                        expanded = false
                        onSortChange(option)
                    },
                    leadingIcon = {
                        if (option == sort) Icon(Icons.Default.Check, null, tint = Cyan400)
                        else Spacer(Modifier.size(24.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun FrequencyHeader(group: FrequencyGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque: the rows scroll underneath while the header is pinned.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (group.frequencyKhz > 0) "%.3f MHz".format(group.frequencyKhz / 1000.0)
                       else stringResource(R.string.stations_frequency_unknown),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Cyan400
            )
            bandLabel(group.frequencyKhz * 1000)?.let { band ->
                Text(band, fontSize = 11.sp, color = OnSurfaceDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.stations_group_count, group.stations.size, group.stations.size
                ),
                fontSize = 11.sp,
                color = OnSurfaceDim
            )
            if (group.transmitting > 0) {
                Text(
                    text = stringResource(R.string.stations_group_transmitting, group.transmitting),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Red400
                )
            }
        }
    }
}

@Composable
private fun StationCard(station: FreeDVReporter.ReporterStation, showFrequency: Boolean) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = station.callsign.ifEmpty { stringResource(R.string.stations_anonymous) },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (station.rxOnly) {
                        Icon(
                            Icons.Default.Headset, stringResource(R.string.stations_rx_only),
                            modifier = Modifier.size(14.dp),
                            tint = Cyan400
                        )
                    }
                    // Last in the row and the only part that gives way: the
                    // callsign stays whole when the two do not fit.
                    ActivityTag(station, Modifier.weight(1f, fill = false))
                }
                Text(
                    text = "${station.gridSquare} | ${station.mode}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (showFrequency && station.frequency > 0) {
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
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
        }
    }
}

/** "TX", or what the station is receiving. Sits on the callsign line, so a
 *  station that starts or stops receiving does not change the height of its row. */
@Composable
private fun ActivityTag(station: FreeDVReporter.ReporterStation, modifier: Modifier = Modifier) {
    val receiving = receivingLabel(station, stringResource(R.string.unit_db))
    val (text, color) = when {
        station.transmitting -> "TX" to Red400
        receiving != null -> receiving to GreenBright
        else -> return
    }
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

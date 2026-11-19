package yakumo2683.RADEdecode.ui

import yakumo2683.RADEdecode.network.FreeDVReporter.ReporterStation

/** Order of the Stations list. [prefValue] is what is saved: do not rename it. */
enum class StationSort(val prefValue: String) {
    /** Dial frequency, then callsign. A row moves only when its station changes
     *  frequency, and the stations of one QSO stay together. */
    FREQUENCY("frequency"),
    CALLSIGN("callsign"),
    /** Latest report first: every report from any station reorders the list. */
    RECENT("recent");

    companion object {
        fun fromPref(value: String?): StationSort =
            entries.firstOrNull { it.prefValue == value } ?: FREQUENCY
    }
}

/** Stations on one dial frequency, to the kHz that the list shows. */
internal data class FrequencyGroup(
    /** 0 when these stations did not report a frequency. */
    val frequencyKhz: Long,
    val stations: List<ReporterStation>
) {
    val transmitting: Int get() = stations.count { it.transmitting }
}

private fun ReporterStation.frequencyKhz(): Long =
    if (frequency <= 0) 0 else (frequency + 500) / 1000

// One callsign can be online from several devices: the last two keys keep
// those rows in a fixed order too.
private val byCallsign: Comparator<ReporterStation> =
    compareBy<ReporterStation, String>(String.CASE_INSENSITIVE_ORDER) { it.callsign }
        .thenBy { it.frequency }
        .thenBy { it.connectionId }

/** Ascending frequency with the stations that report none last; callsign order inside a group. */
internal fun groupByFrequency(stations: Collection<ReporterStation>): List<FrequencyGroup> =
    stations.groupBy { it.frequencyKhz() }
        .map { (khz, members) -> FrequencyGroup(khz, members.sortedWith(byCallsign)) }
        .sortedWith(compareBy<FrequencyGroup> { it.frequencyKhz == 0L }.thenBy { it.frequencyKhz })

internal fun sortStations(stations: Collection<ReporterStation>, sort: StationSort): List<ReporterStation> =
    when (sort) {
        StationSort.FREQUENCY -> groupByFrequency(stations).flatMap { it.stations }
        StationSort.CALLSIGN -> stations.sortedWith(byCallsign)
        StationSort.RECENT ->
            stations.sortedWith(compareByDescending<ReporterStation> { it.lastUpdate }.then(byCallsign))
    }

/**
 * What a station is receiving, as the Map tab shows it: "RX K1ABC 12 dB". The
 * callsign is there once the station has decoded one and the SNR once it has
 * reported one. Null when the station is transmitting or not receiving.
 */
internal fun receivingLabel(station: ReporterStation, dbUnit: String): String? {
    if (station.transmitting) return null
    if (!station.receiving && station.receivedCallsign.isEmpty()) return null
    return buildString {
        append("RX")
        if (station.receivedCallsign.isNotEmpty()) append(' ').append(station.receivedCallsign.uppercase())
        if (station.snr != 0) append(' ').append(station.snr).append(' ').append(dbUnit)
    }
}

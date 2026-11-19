package yakumo2683.RADEdecode.ui

import org.junit.Assert.*
import org.junit.Test
import yakumo2683.RADEdecode.network.FreeDVReporter.ReporterStation

class StationListTest {
    private fun station(
        id: String, callsign: String, hz: Long, lastUpdate: Long = 0,
        transmitting: Boolean = false, receiving: Boolean = false,
        receivedCallsign: String = "", snr: Int = 0
    ) = ReporterStation(
        connectionId = id, callsign = callsign, gridSquare = "PM95", frequency = hz,
        mode = "RADEV1", version = "", rxOnly = false, transmitting = transmitting,
        receiving = receiving, receivedCallsign = receivedCallsign, snr = snr,
        lastUpdate = lastUpdate
    )

    private fun List<ReporterStation>.callsigns() = map { it.callsign }

    private val online = listOf(
        station("a", "VK2DEF", 14_236_000, lastUpdate = 50),
        station("b", "JH4JKL", 7_177_000, lastUpdate = 40),
        station("c", "K1ABC", 14_236_000, lastUpdate = 30, transmitting = true),
        station("d", "DL1GHI", 14_240_000, lastUpdate = 20),
        station("e", "BX2ABC", 14_236_000, lastUpdate = 10)
    )

    @Test fun frequencyOrderKeepsOneQsoTogetherInCallsignOrder() {
        val groups = groupByFrequency(online)
        assertEquals(listOf(7_177L, 14_236L, 14_240L), groups.map { it.frequencyKhz })
        assertEquals(listOf("BX2ABC", "K1ABC", "VK2DEF"), groups[1].stations.callsigns())
        assertEquals(listOf(0, 1, 0), groups.map { it.transmitting })
        assertEquals(listOf("JH4JKL", "BX2ABC", "K1ABC", "VK2DEF", "DL1GHI"),
            sortStations(online, StationSort.FREQUENCY).callsigns())
    }

    @Test fun aNewReportMovesARowOnlyInRecentOrder() {
        val afterReport = online.map { if (it.callsign == "BX2ABC") it.copy(lastUpdate = 99, snr = 7) else it }
        for (sort in listOf(StationSort.FREQUENCY, StationSort.CALLSIGN))
            assertEquals("$sort", sortStations(online, sort).callsigns(), sortStations(afterReport, sort).callsigns())
        assertEquals("BX2ABC", sortStations(online, StationSort.RECENT).last().callsign)
        assertEquals("BX2ABC", sortStations(afterReport, StationSort.RECENT).first().callsign)
    }

    @Test fun orderDoesNotDependOnTheOrderStationsArriveIn() {
        for (sort in StationSort.entries)
            assertEquals("$sort", sortStations(online, sort), sortStations(online.reversed(), sort))
    }

    @Test fun frequenciesGroupToTheKilohertzThatIsShown() {
        val groups = groupByFrequency(listOf(
            station("a", "AA1A", 14_236_000), station("b", "BB1B", 14_236_499),
            station("c", "CC1C", 14_235_500), station("d", "DD1D", 14_236_500)
        ))
        assertEquals(listOf(14_236L, 14_237L), groups.map { it.frequencyKhz })
        assertEquals(listOf("AA1A", "BB1B", "CC1C"), groups[0].stations.callsigns())
    }

    @Test fun stationsWithoutAFrequencyComeLast() {
        val groups = groupByFrequency(listOf(
            station("a", "AA1A", 0), station("b", "BB1B", 28_330_000), station("c", "CC1C", -1)
        ))
        assertEquals(listOf(28_330L, 0L), groups.map { it.frequencyKhz })
        assertEquals(listOf("AA1A", "CC1C"), groups[1].stations.callsigns())
    }

    @Test fun oneCallsignOnSeveralDevicesKeepsAFixedOrder() {
        val devices = listOf(
            station("z", "w1aw", 14_236_000), station("m", "W1AW", 7_177_000), station("b", "W1AW", 14_236_000)
        )
        val byCallsign = sortStations(devices, StationSort.CALLSIGN)
        assertEquals(listOf("m", "b", "z"), byCallsign.map { it.connectionId })
        assertEquals(byCallsign, sortStations(devices.reversed(), StationSort.CALLSIGN))
        assertEquals(listOf("b", "z"), groupByFrequency(devices)[1].stations.map { it.connectionId })
    }

    @Test fun savedOrderFallsBackToFrequency() {
        for (sort in StationSort.entries) assertEquals(sort, StationSort.fromPref(sort.prefValue))
        assertEquals(StationSort.FREQUENCY, StationSort.fromPref(null))
        assertEquals(StationSort.FREQUENCY, StationSort.fromPref("lastUpdate"))
    }

    @Test fun receivingLabelShowsWhatAStationHears() {
        assertEquals("RX K1ABC 12 dB",
            receivingLabel(station("a", "BX2ABC", 0, receiving = true, receivedCallsign = "k1abc", snr = 12), "dB"))
        // During a RADE V1 over the callsign is not decoded yet.
        assertEquals("RX -3 dB", receivingLabel(station("a", "BX2ABC", 0, receiving = true, snr = -3), "dB"))
        assertEquals("RX", receivingLabel(station("a", "BX2ABC", 0, receiving = true), "dB"))
        assertEquals("RX K1ABC", receivingLabel(station("a", "BX2ABC", 0, receivedCallsign = "K1ABC"), "dB"))
        // The SNR of an earlier over stays in the station record.
        assertNull(receivingLabel(station("a", "BX2ABC", 0, snr = 12), "dB"))
        assertNull(receivingLabel(station("a", "BX2ABC", 0, transmitting = true, receiving = true, snr = 12), "dB"))
    }
}

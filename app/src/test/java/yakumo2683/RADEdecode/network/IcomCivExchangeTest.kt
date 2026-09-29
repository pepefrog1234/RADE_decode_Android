package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test
import yakumo2683.RADEdecode.network.IcomTuningGuardTest.Companion.bytes
import yakumo2683.RADEdecode.network.IcomTuningGuardTest.Companion.frame

class IcomCivExchangeTest {
    private fun reply(body: String) = bytes("FEFEE0A4${body}FD")
    private val frequency = reply("25000050060700")

    @Test fun fragmentedAndCoalescedEchoAndReplyYieldOnlyOneAnswer() {
        val exchange = IcomCivExchange()
        val query = frame("2500")
        exchange.begin(query)
        assertTrue(exchange.receive(query.copyOfRange(0, 3)).isEmpty())
        val received = exchange.receive(query.copyOfRange(3, query.size) + frequency + frequency)
        assertEquals(1, received.size)
        assertArrayEquals(frequency, received.single())
        assertEquals(1L, exchange.discardedEchoes)
        assertEquals(1L, exchange.discardedReplies)
        assertNull(exchange.pendingRequest())
    }

    @Test fun wrongOpcodeVfoAddressAndWriteAckCannotSatisfyFrequencyRead() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("2500"))
        for (invalid in listOf(reply("2600010100"), reply("25010050060700"),
                bytes("FEFEE0A225000050060700FD"), reply("FB"), reply("2500005A060700"))) {
            assertTrue(exchange.receive(invalid).isEmpty())
            assertNotNull(exchange.pendingRequest())
        }
        assertArrayEquals(frequency, exchange.receive(frequency).single())
    }

    @Test fun lateReadWithSameOpcodeCannotAcknowledgeNewFrequencyWrite() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("25000042070700"))
        assertTrue(exchange.receive(frequency).isEmpty())
        assertNotNull(exchange.pendingRequest())
        assertArrayEquals(reply("FB"), exchange.receive(reply("FB")).single())
    }

    @Test fun stalePttWriteAckCannotCompleteReadback() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("1C00"))
        assertTrue(exchange.receive(reply("FB")).isEmpty())
        assertTrue(exchange.receive(reply("1C0100")).isEmpty())
        assertArrayEquals(reply("1C0000"), exchange.receive(reply("1C0000")).single())
    }

    @Test fun negativeAcknowledgementIsPassedOnceForReadsAndWrites() {
        val exchange = IcomCivExchange()
        for (command in listOf("2500", "1C0000")) {
            exchange.begin(frame(command))
            assertArrayEquals(reply("FA"), exchange.receive(reply("FA")).single())
            assertTrue(exchange.receive(reply("FA")).isEmpty())
        }
    }

    @Test fun powerStatusReadPassesButPowerWriteRequiresAcknowledgement() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("18"))
        assertArrayEquals(reply("1801"), exchange.receive(reply("1801")).single())
        for (command in listOf("1800", "1801")) {
            exchange.begin(frame(command))
            assertTrue(exchange.receive(reply("1801")).isEmpty())
            assertArrayEquals(reply("FB"), exchange.receive(reply("FB")).single())
        }
        for (command in listOf("11", "12")) {
            exchange.begin(frame(command))
            assertArrayEquals(reply(command + "01"), exchange.receive(reply(command + "01")).single())
        }
    }

    @Test fun universalAddressFilterQueryAcceptsActualRadioSource() {
        val exchange = IcomCivExchange()
        exchange.begin(bytes("FEFE00E01A06FD"))
        assertTrue(exchange.receive(reply("1A05000001")).isEmpty())
        assertArrayEquals(reply("1A060102"), exchange.receive(reply("1A060102")).single())
        exchange.begin(bytes("FEFE00E01A060102FD"))
        assertTrue(exchange.receive(reply("1A060102")).isEmpty())
        assertArrayEquals(reply("FB"), exchange.receive(reply("FB")).single())
    }

    @Test fun clearingSessionDiscardsPartialReplyAndOldRequest() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("2500"))
        assertTrue(exchange.receive(frequency.copyOfRange(0, 5)).isEmpty())
        exchange.clear()
        assertNull(exchange.pendingRequest())
        exchange.begin(frame("1C00"))
        assertTrue(exchange.receive(frequency.copyOfRange(5, frequency.size)).isEmpty())
        assertArrayEquals(reply("1C0000"), exchange.receive(reply("1C0000")).single())
    }

    @Test fun broadcastDoesNotConsumePendingCommand() {
        val exchange = IcomCivExchange()
        exchange.begin(frame("2500"))
        val broadcast = bytes("FEFE00A4000050060700FD")
        assertArrayEquals(broadcast, exchange.receive(broadcast).single())
        assertNotNull(exchange.pendingRequest())
        assertArrayEquals(frequency, exchange.receive(frequency).single())
    }
}

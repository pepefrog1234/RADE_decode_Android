package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class IcomAudioJitterTest {
    private fun pkt(seq: Int, payload: Int) = ByteArray(24 + payload).also { it[0] = seq.toByte() }

    @Test fun reordersContiguousPackets() {
        val out = mutableListOf<ByteArray>()
        val j = IcomAudioJitter(maxPackets = 24) { out.add(it) }
        j.add(1, pkt(1, 1364))
        j.add(3, pkt(3, 1364))
        assertEquals(1, out.size)
        j.add(2, pkt(2, 556))
        assertEquals(listOf(1, 2, 3), out.map { it[0].toInt() })
        assertEquals(3L, j.delivered)
        assertEquals(0L, j.concealed)
    }

    @Test fun lostPacketIsConcealedWithSilenceOfTheAlternatingSize() {
        val out = mutableListOf<ByteArray>()
        val j = IcomAudioJitter(maxPackets = 4) { out.add(it) }
        j.add(10, pkt(10, 1364))
        j.add(11, pkt(11, 556))
        // 12 (a 1364-byte part) is lost; 13..17 arrive.
        for (s in 13..17) j.add(s, pkt(s, if (s % 2 == 0) 1364 else 556))
        // Waited for maxPackets newer packets, then concealed 12 and moved on.
        assertEquals(1L, j.concealed)
        val concealedPkt = out[2]
        assertEquals(24 + 1364, concealedPkt.size)
        assertTrue(concealedPkt.all { it == 0.toByte() })
        assertEquals(listOf(10, 11, 0, 13, 14, 15, 16, 17), out.map { it[0].toInt() })
        // The late original is now stale and must not be inserted out of order.
        j.add(12, pkt(12, 1364))
        assertEquals(8, out.size)
        assertEquals(1L, j.lateDropped)
    }

    @Test fun twoConsecutiveLossesConcealOneWholeFrame() {
        val out = mutableListOf<ByteArray>()
        val j = IcomAudioJitter(maxPackets = 2) { out.add(it) }
        j.add(0, pkt(0, 556))
        // 1 (1364) and 2 (556) lost
        j.add(3, pkt(3, 1364)); j.add(4, pkt(4, 556)); j.add(5, pkt(5, 1364))
        assertEquals(2L, j.concealed)
        assertEquals(24 + 1364, out[1].size)
        assertEquals(24 + 556, out[2].size)
        assertEquals(listOf(0, 0, 0, 3, 4, 5), out.map { it[0].toInt() })
    }

    @Test fun sequenceWrapIsHandled() {
        val out = mutableListOf<Int>()
        val j = IcomAudioJitter(maxPackets = 8) { out.add(it[0].toInt() and 0xFF) }
        j.add(0xFFFE, pkt(0xFE, 1364))
        j.add(0x0000, pkt(0x00, 1364))
        assertEquals(listOf(0xFE), out)
        j.add(0xFFFF, pkt(0xFF, 556))
        assertEquals(listOf(0xFE, 0xFF, 0x00), out)
    }
}

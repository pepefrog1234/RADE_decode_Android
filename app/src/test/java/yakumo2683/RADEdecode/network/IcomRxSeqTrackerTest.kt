package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class IcomRxSeqTrackerTest {
    @Test fun contiguousPacketsNeedNoRequests() {
        val t = IcomRxSeqTracker()
        for (s in 10..20) assertTrue(t.onPacket(s, 1000L + s))
        assertTrue(t.dueRequests(2000).isEmpty())
        assertEquals(0L, t.stats.missing)
        assertEquals(0, t.openHoles)
    }

    @Test fun singleLossIsRequestedOnceThenHealedByRetransmit() {
        val t = IcomRxSeqTracker(reRequestMs = 120)
        assertTrue(t.onPacket(100, 0))
        assertTrue(t.onPacket(102, 10))          // 101 missing
        val req = t.dueRequests(10)
        assertEquals(listOf(IcomRxSeqTracker.SeqRange(101, 101)), req)
        assertTrue("not re-requested before reRequestMs", t.dueRequests(50).isEmpty())
        assertTrue("the retransmit fills the hole and is delivered", t.onPacket(101, 60))
        assertEquals(1L, t.stats.healed)
        assertFalse("a second copy is a duplicate", t.onPacket(101, 70))
        assertEquals(1L, t.stats.duplicates)
        assertTrue(t.dueRequests(500).isEmpty())
    }

    @Test fun burstLossIsRequestedAsRangesOldestFirstAndCapped() {
        val t = IcomRxSeqTracker(maxRequestSpan = 10, maxRequestPerPoll = 30)
        t.onPacket(0, 0)
        t.onPacket(45, 10)                        // 1..44 missing (44 packets)
        val req = t.dueRequests(10)
        assertEquals(listOf(
            IcomRxSeqTracker.SeqRange(1, 10),
            IcomRxSeqTracker.SeqRange(11, 20),
            IcomRxSeqTracker.SeqRange(21, 30)
        ), req)
        assertEquals(3L, t.stats.requests)
        // The rest is asked for on the next poll; the first 30 wait for reRequestMs.
        val next = t.dueRequests(20)
        assertEquals(listOf(
            IcomRxSeqTracker.SeqRange(31, 40),
            IcomRxSeqTracker.SeqRange(41, 44)
        ), next)
    }

    @Test fun holesAreAbandonedAfterGiveUp() {
        val t = IcomRxSeqTracker(giveUpAfterMs = 600, reRequestMs = 100)
        t.onPacket(5, 0)
        t.onPacket(7, 0)                          // 6 missing
        assertEquals(1, t.dueRequests(0).size)
        assertEquals(1, t.dueRequests(150).size)  // re-request
        assertTrue(t.dueRequests(700).isEmpty())  // given up
        assertEquals(1L, t.stats.lost)
        assertEquals(0, t.openHoles)
        assertFalse("a copy arriving after give-up is treated as stale", t.onPacket(6, 800))
    }

    @Test fun sequenceWrapsAt16Bits() {
        val t = IcomRxSeqTracker()
        t.onPacket(0xFFFE, 0)
        t.onPacket(0x0001, 0)                     // 0xFFFF and 0x0000 missing
        val req = t.dueRequests(0)
        assertEquals(listOf(IcomRxSeqTracker.SeqRange(0xFFFF, 0x0000)), req)
        assertEquals(2, req[0].size)
        assertTrue(t.onPacket(0xFFFF, 5))
        assertTrue(t.onPacket(0x0000, 5))
        assertEquals(0, t.openHoles)
        assertTrue(t.onPacket(0x0002, 6))
        assertFalse(t.onPacket(0xFFFE, 7))        // old duplicate
    }

    @Test fun hugeJumpResyncsInsteadOfRequestingEverything() {
        val t = IcomRxSeqTracker(resyncSpan = 512)
        t.onPacket(10, 0)
        assertTrue(t.onPacket(5000, 0))
        assertEquals(1L, t.stats.resyncs)
        assertTrue(t.dueRequests(0).isEmpty())
        assertTrue("a far-behind packet also resyncs", t.onPacket(100, 0))
        assertEquals(2L, t.stats.resyncs)
    }
}

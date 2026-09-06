package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class IcomRxSeqTrackerPingTest {
    @Test fun aPingCarryingAMissingSeqFillsTheHoleAndIsCounted() {
        val t = IcomRxSeqTracker(giveUpAfterMs = 1000)
        t.onPacket(10, 0)
        t.onPacket(12, 10)                     // 11 missing
        assertEquals(1, t.openHoles)
        t.onPing(11)
        assertEquals(0, t.openHoles)
        assertEquals(1L, t.stats.healedByPing)
        assertEquals(1L, t.stats.pings)
        assertEquals(0L, t.stats.lost)
    }

    @Test fun expireHolesCountsLostWithoutRequesting() {
        val t = IcomRxSeqTracker(giveUpAfterMs = 500)
        t.onPacket(0, 0)
        t.onPacket(3, 0)                       // 1, 2 missing
        t.expireHoles(400)
        assertEquals(2, t.openHoles)
        t.expireHoles(600)
        assertEquals(0, t.openHoles)
        assertEquals(2L, t.stats.lost)
        assertEquals(0L, t.stats.requests)
    }
}

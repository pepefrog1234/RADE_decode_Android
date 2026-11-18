package yakumo2683.RADEdecode.service

import org.junit.Assert.*
import org.junit.Test

class ClockOffsetSettlingTest {
    private val settling = ClockOffsetSettling()
    private fun poll(atMs: Long, synced: Boolean, acquired: Boolean = false) =
        settling.poll(synced, acquired, atMs)

    @Test fun estimateSettlesAfter45SecondsOfSync() {
        assertFalse(poll(0, synced = false))
        assertFalse(poll(10_000, synced = true, acquired = true))
        assertFalse(poll(40_000, synced = true))
        assertFalse(poll(54_900, synced = true))
        assertTrue(poll(55_000, synced = true))
        assertTrue(poll(90_000, synced = true))
    }

    /** The modem holds its estimate after the over, so a settled one stays settled. */
    @Test fun settledEstimateIsKeptAfterTheOver() {
        poll(0, synced = true, acquired = true)
        assertTrue(poll(45_000, synced = true))
        assertTrue(poll(46_000, synced = false))
        assertTrue(poll(300_000, synced = false))
    }

    @Test fun shortOverNeverSettles() {
        poll(0, synced = true, acquired = true)
        assertFalse(poll(20_000, synced = true))
        assertFalse(poll(21_000, synced = false))
        assertFalse(poll(120_000, synced = false))   // time out of sync does not count
    }

    /** Every acquisition restarts the modem's estimate from zero. */
    @Test fun nextOverStartsUnsettledAgain() {
        poll(0, synced = true, acquired = true)
        assertTrue(poll(50_000, synced = true))
        assertTrue(poll(60_000, synced = false))
        assertFalse(poll(70_000, synced = true, acquired = true))
        assertFalse(poll(110_000, synced = true))
        assertTrue(poll(115_000, synced = true))
    }

    /** A drop and re-acquisition between two polls shows up only as the flag
     *  from the sync callback: both polls see "in sync". */
    @Test fun reacquisitionBetweenPollsRestartsTheWait() {
        poll(0, synced = true, acquired = true)
        assertTrue(poll(50_000, synced = true))
        assertFalse(poll(50_080, synced = true, acquired = true))
        assertFalse(poll(90_000, synced = true))
        assertTrue(poll(95_080, synced = true))
    }

    /** Without the callback flag (first poll after start-up already in sync),
     *  the rising edge is enough. */
    @Test fun risingEdgeAloneStartsTheWait() {
        assertFalse(poll(5_000, synced = true))
        assertFalse(poll(49_900, synced = true))
        assertTrue(poll(50_000, synced = true))
    }
}

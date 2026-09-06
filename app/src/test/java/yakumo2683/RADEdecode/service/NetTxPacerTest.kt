package yakumo2683.RADEdecode.service

import org.junit.Assert.*
import org.junit.Test

class NetTxPacerTest {
    private val period = 20_000_000L  // 20 ms
    private val ms = 1_000_000L
    private val radioBufferFrames = 7  // ~150 ms

    @Test fun onTimeTicksSleepUntilTheNextDeadline() {
        val p = NetTxPacer(period, maxCatchupPerTick = 1, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        val plan = p.afterSend(1 * ms, spareFrames = 10)          // 1 ms of work
        assertEquals(NetTxPacer.Plan(0, 0, 2 * period - 1 * ms), plan)
        assertEquals(0L, p.lateTicks)
    }

    @Test fun subFrameLatenessJustSkipsTheSleep() {
        val p = NetTxPacer(period, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        val plan = p.afterSend(2 * period + 5 * ms, spareFrames = 10)  // 5 ms late
        assertEquals(NetTxPacer.Plan(0, 0, 0), plan)
        assertEquals(1L, p.lateTicks)
        assertEquals(0L, p.catchupFrames)
        assertEquals(5 * ms, p.maxBehindNs)
    }

    @Test fun aShortStallIsCaughtUpAtTwiceRealTimeAndFullyAccounted() {
        val p = NetTxPacer(period, maxCatchupPerTick = 1, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        // 100 ms stall (< radio buffer): the frame due at 20 ms goes out at 120 ms.
        var t = 120 * ms
        var sent = 1
        var plan = p.afterSend(t, spareFrames = 10)
        sent += plan.extraFrames
        assertEquals(1, plan.extraFrames)
        assertEquals(0, plan.dropFrames)
        assertEquals(period, plan.sleepNs)                          // hold the 2x pace
        assertEquals(60 * ms, p.nextDeadlineNs)
        // Simulate the pump: sleep, ~1 ms of work, send the regular frame, plan again.
        repeat(12) {
            t += plan.sleepNs + 1 * ms
            sent++
            plan = p.afterSend(t, spareFrames = 10)
            sent += plan.extraFrames
            assertTrue("at most one extra frame per tick", plan.extraFrames <= 1)
            assertEquals(0, plan.dropFrames)
            if (plan.extraFrames == 1 && p.nextDeadlineNs < t) {
                assertEquals("still behind: hold the 2x pace", period, plan.sleepNs)
            }
        }
        assertEquals(4L, p.catchupFrames)                           // 5 owed frames: 4 extra + zero-sleep ticks
        assertEquals(0L, p.droppedFrames)
        assertTrue("schedule caught up with wall clock", p.nextDeadlineNs > t)
        // Every deadline from 20 ms up to now has exactly one frame.
        assertEquals((p.nextDeadlineNs / period).toInt() - 1, sent)
    }

    @Test fun catchUpNeverReadsBeyondTheRingReserve() {
        val p = NetTxPacer(period, maxCatchupPerTick = 3, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        val plan = p.afterSend(200 * ms, spareFrames = 0)          // ring has nothing spare
        assertEquals(NetTxPacer.Plan(0, 0, 0), plan)
        assertEquals(0L, p.catchupFrames)
        assertEquals(1L, p.reanchors)
        assertEquals(200 * ms, p.nextDeadlineNs)                    // re-anchored to now
    }

    @Test fun aLongStallDropsAllButOneRadioBufferOfBacklog() {
        val p = NetTxPacer(period, maxCatchupPerTick = 1, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        // 900 ms stall: the frame due at 20 ms goes out at 920 ms → 44 frames owed.
        val plan = p.afterSend(920 * ms, spareFrames = 200)
        assertEquals(44 - radioBufferFrames, plan.dropFrames)        // 37 stale frames discarded
        assertEquals(1, plan.extraFrames)                            // the buffer's worth is caught up at 2x
        assertEquals(37L, p.droppedFrames)
        assertEquals(1L, p.backlogDrops)
        assertEquals(1L, p.catchupFrames)
        // Owed after this tick: 7 − 1 = 6 frames → still behind, hold the 2x pace.
        assertEquals(period, plan.sleepNs)
        assertEquals(920 * ms - 6 * period, p.nextDeadlineNs)
    }

    @Test fun backlogDropIsBoundedByTheRing() {
        val p = NetTxPacer(period, maxCatchupPerTick = 1, maxCatchupFrames = radioBufferFrames)
        p.start(0)
        val plan = p.afterSend(920 * ms, spareFrames = 30)
        assertEquals(30, plan.dropFrames)                           // ring can give up 30
        assertEquals(0, plan.extraFrames)                           // nothing spare left to send extra
        // Still behind by the rest; the next tick with an empty ring re-anchors.
        val next = p.afterSend(921 * ms, spareFrames = 0)
        assertEquals(NetTxPacer.Plan(0, 0, 0), next)
        assertEquals(1L, p.reanchors)
    }
}

package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class MeterPollBackoffTest {
    @Test fun oneFailurePausesPolling() {
        val backoff = MeterPollBackoff()
        assertTrue(backoff.due(0))
        assertEquals(0, backoff.record(succeeded = true, nowMs = 1_000))
        assertEquals(30_000, backoff.record(succeeded = false, nowMs = 2_000))
        assertFalse(backoff.due(2_000))
        assertFalse(backoff.due(31_999))
        assertTrue(backoff.due(32_000))
    }

    @Test fun eachFurtherFailureDoublesThePauseUpToTheLongest() {
        val backoff = MeterPollBackoff()
        var now = 0L
        val pauses = (1..7).map {
            now += 1_000_000 // Long after any pause: only the count matters.
            backoff.record(succeeded = false, nowMs = now)
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), pauses)
        assertFalse(backoff.due(now + 299_999))
        assertTrue(backoff.due(now + 300_000))
    }

    /** An answer Hamlib accepts only for some values fails now and then; a
     *  success in between must not put it back on every poll cycle. */
    @Test fun meterThatFailsNowAndThenKeepsItsLongPause() {
        val backoff = MeterPollBackoff()
        assertEquals(30_000, backoff.record(succeeded = false, nowMs = 0))
        repeat(4) { assertEquals(0, backoff.record(succeeded = true, nowMs = 31_000)) }
        assertTrue("Polled again while it works", backoff.due(31_000))
        assertEquals(60_000, backoff.record(succeeded = false, nowMs = 40_000))
        assertEquals(0, backoff.record(succeeded = true, nowMs = 101_000))
        assertEquals(120_000, backoff.record(succeeded = false, nowMs = 102_000))
    }

    @Test fun fiveReadsInARowForgetTheFailures() {
        val backoff = MeterPollBackoff()
        repeat(3) { backoff.record(succeeded = false, nowMs = 0) }
        repeat(5) { assertEquals(0, backoff.record(succeeded = true, nowMs = 200_000)) }
        assertEquals(30_000, backoff.record(succeeded = false, nowMs = 300_000))
    }

    @Test fun resetAllowsPollingAtOnce() {
        val backoff = MeterPollBackoff()
        repeat(5) { backoff.record(succeeded = false, nowMs = 0) }
        assertFalse(backoff.due(1))
        backoff.reset()
        assertTrue(backoff.due(1))
        assertEquals(30_000, backoff.record(succeeded = false, nowMs = 2))
    }

    /** System.nanoTime() has an arbitrary origin and may be negative. */
    @Test fun clockMayStartBelowZero() {
        val backoff = MeterPollBackoff()
        assertTrue(backoff.due(-5_000_000))
        backoff.record(succeeded = false, nowMs = -5_000_000)
        assertFalse(backoff.due(-4_980_000))
        assertTrue(backoff.due(-4_970_000))
    }
}

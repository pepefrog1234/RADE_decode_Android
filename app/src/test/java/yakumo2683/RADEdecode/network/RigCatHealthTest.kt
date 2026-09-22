package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class RigCatHealthTest {
    private val timeout = RigctldResponse(emptyList(), -5)
    private val protocolError = RigctldResponse(emptyList(), -8)
    private val ok = RigctldResponse(listOf("Frequency: 7177000"), 0)

    @Test fun acknowledgedOffWithoutMatchingReadbackStillTriggersRecovery() {
        val health = RigCatHealth()
        for (now in listOf(0L, 7_000L, 15_000L)) {
            assertEquals(now == 15_000L, health.record("set_ptt", "0",
                RigctldResponse(emptyList(), 0), now, confirmOffByReadback = true))
            if (now < 15_000L) assertFalse(health.record("get_ptt", "",
                RigctldResponse(listOf("PTT: 1"), 0), now, confirmOffByReadback = true))
        }
    }

    @Test fun timedOutPttReadsRecoverEvenWhenFrequencyPollingStillWorks() {
        val health = RigCatHealth()
        for (now in listOf(0L, 7_000L, 15_000L)) {
            assertFalse(health.record("get_freq", "", ok, now))
            assertEquals(now == 15_000L, health.record("get_ptt", "", timeout, now))
        }
    }

    @Test fun rejectedOrProtocolRepliesAreAnswersFromALiveRadioNotLinkFailures() {
        // v1.6.27 field log: Hamlib's set_vfo-before-read fallback made every
        // poll "RPRT -9" while PTT and audio worked; that must not recover.
        val health = RigCatHealth()
        for (i in 0 until 12) {
            val now = i * 5_000L
            assertFalse(health.record("get_ptt", "", RigctldResponse(emptyList(), -9), now))
            assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), -9), now))
            assertFalse(health.record("get_freq", "", protocolError, now))
            assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), -13), now))
        }
    }

    @Test fun emptySuccessfulFrequencyRepliesAreNotHealthy() {
        val health = RigCatHealth()
        assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), 0), 0))
        assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), 0), 7_000))
        assertTrue(health.record("get_freq", "", RigctldResponse(emptyList(), 0), 15_000))
    }

    @Test fun timedOutFrequencyReadsRecoverWithoutTreatingUnsupportedMetersAsFailures() {
        val health = RigCatHealth()
        repeat(3) { i ->
            val now = i * 8_000L
            assertFalse(health.record("get_level", "STRENGTH", RigctldResponse(emptyList(), -9), now))
            assertFalse(health.record("get_ptt", "", RigctldResponse(listOf("PTT: 0"), 0), now))
            assertEquals(i == 2, health.record("get_freq", "", timeout, now))
        }
    }

    @Test fun repeatedTimeoutsTriggerOnceAfterTheWindow() {
        val health = RigCatHealth()
        assertFalse(health.record("get_freq", "", timeout, 0))
        assertFalse(health.record("get_ptt", "", timeout, 7_000))
        assertTrue(health.record("get_freq", "", timeout, 15_000))
        assertFalse(health.record("get_ptt", "", timeout, 20_000))
        health.reset()
        assertFalse(health.record("get_freq", "", timeout, 30_000))
    }

    @Test fun unsupportedCommandsAndShortLossDoNotRestartTheRadio() {
        val health = RigCatHealth()
        repeat(10) {
            assertFalse(health.record("get_level", "STRENGTH", protocolError, it * 5_000L))
            assertFalse(health.record("get_mode", "", RigctldResponse(emptyList(), -11), it * 5_000L))
        }
        assertFalse(health.record("get_freq", "", timeout, 50_000))
        assertFalse(health.record("get_ptt", "", timeout, 52_000))
        assertFalse(health.record("get_freq", "", ok, 53_000))
        assertFalse(health.record("get_freq", "", timeout, 70_000))
    }

    @Test fun healthyFrequencyRepliesDoNotHideRepeatedOffFailure() {
        val health = RigCatHealth()
        assertFalse(health.record("set_ptt", "0", timeout, 0))
        assertFalse(health.record("get_freq", "", ok, 1_000))
        assertFalse(health.record("set_ptt", "0", timeout, 7_000))
        assertFalse(health.record("get_ptt", "", RigctldResponse(listOf("PTT: 1"), 0), 10_000))
        assertTrue(health.record("set_ptt", "0", timeout, 15_000))
    }

    @Test fun confirmedOffReadbackClearsTheOffFailureWindow() {
        val health = RigCatHealth()
        health.record("set_ptt", "0", timeout, 0)
        health.record("set_ptt", "0", timeout, 7_000)
        assertFalse(health.record("get_ptt", "", RigctldResponse(listOf("PTT: 0"), 0), 14_000))
        assertFalse(health.record("set_ptt", "0", timeout, 30_000))
    }
}

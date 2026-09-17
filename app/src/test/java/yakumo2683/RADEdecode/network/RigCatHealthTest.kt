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

    @Test fun rejectedPttReadsRecoverEvenWhenFrequencyPollingStillWorks() {
        val health = RigCatHealth()
        for (now in listOf(0L, 7_000L, 15_000L)) {
            assertFalse(health.record("get_freq", "", ok, now))
            assertEquals(now == 15_000L,
                health.record("get_ptt", "", RigctldResponse(emptyList(), -9), now))
        }
    }

    @Test fun busErrorsAndEmptySuccessfulFrequencyRepliesAreNotHealthy() {
        val health = RigCatHealth()
        assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), -13), 0))
        assertFalse(health.record("get_freq", "", RigctldResponse(emptyList(), 0), 7_000))
        assertTrue(health.record("get_freq", "", RigctldResponse(emptyList(), 0), 15_000))
    }

    @Test fun rejectedFrequencyReadsRecoverWithoutTreatingUnsupportedMetersAsFailures() {
        val health = RigCatHealth()
        repeat(3) { i ->
            val now = i * 8_000L
            assertFalse(health.record("get_level", "STRENGTH", RigctldResponse(emptyList(), -9), now))
            assertFalse(health.record("get_ptt", "", RigctldResponse(listOf("PTT: 0"), 0), now))
            assertEquals(i == 2, health.record("get_freq", "", RigctldResponse(emptyList(), -9), now))
        }
    }

    @Test fun repeatedRadioProtocolErrorsTriggerOnceAfterTheWindow() {
        val health = RigCatHealth()
        assertFalse(health.record("get_freq", "", protocolError, 0))
        assertFalse(health.record("get_ptt", "", protocolError, 7_000))
        assertTrue(health.record("get_freq", "", protocolError, 15_000))
        assertFalse(health.record("get_ptt", "", protocolError, 20_000))
        health.reset()
        assertFalse(health.record("get_freq", "", protocolError, 30_000))
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

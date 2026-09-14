package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class RigCatHealthTest {
    private val timeout = RigctldResponse(emptyList(), -5)
    private val protocolError = RigctldResponse(emptyList(), -8)
    private val ok = RigctldResponse(emptyList(), 0)

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

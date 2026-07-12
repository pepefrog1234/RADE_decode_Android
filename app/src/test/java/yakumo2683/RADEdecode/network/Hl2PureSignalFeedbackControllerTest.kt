package yakumo2683.RADEdecode.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Hl2PureSignalFeedbackControllerTest {

    @Test
    fun `encodes extended feedback gain and clamps to HL2 range`() {
        assertEquals(0xC0, encodeHl2TxFeedbackGain(-99))
        assertEquals(0xC0, encodeHl2TxFeedbackGain(-12))
        assertEquals(0xCC, encodeHl2TxFeedbackGain(0))
        assertEquals(0xE0, encodeHl2TxFeedbackGain(20))
        assertEquals(0xFC, encodeHl2TxFeedbackGain(48))
        assertEquals(0xFC, encodeHl2TxFeedbackGain(99))
    }

    @Test
    fun `snapshot names all controller and solver diagnostics`() {
        val raw = intArrayOf(
            0, 0, 0, 0, 152, 7, 0, 0,
            4, 3, PsInfoSnapshot.OUTCOME_ACCEPTED, 0, 0, 2, 1, 8
        )

        val info = PsInfoSnapshot.from(raw)

        assertEquals(152, info.feedbackLevel)
        assertEquals(7, info.attemptCounter)
        assertEquals(4, info.acceptedCounter)
        assertEquals(3, info.rejectedCounter)
        assertEquals(PsInfoSnapshot.OUTCOME_ACCEPTED, info.lastOutcome)
        assertEquals(2, info.dogCounter)
        assertTrue(info.correctionApplied)
        assertEquals(8, info.state)
        assertTrue(info.fitIsSane)
        assertTrue(info.solutionOk)
    }

    @Test
    fun `snapshot rejects truncated vectors and reports failed sanity`() {
        assertThrows(IllegalArgumentException::class.java) {
            PsInfoSnapshot.from(IntArray(15))
        }

        val raw = acceptedInfo(attempt = 1, level = 152)
        raw[6] = 0x04
        val info = PsInfoSnapshot.from(raw)

        assertFalse(info.fitIsSane)
        assertFalse(info.solutionOk)

        raw[6] = 0
        raw[14] = 0
        assertFalse(PsInfoSnapshot.from(raw).solutionOk)
    }

    @Test
    fun `starts at safe minimum and waits one clean interval before one shot`() {
        val controller = Hl2PureSignalFeedbackController()

        assertEquals(-12, controller.gainDb)
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Wait,
            controller.onPttStarted(nowMs = 0)
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(
                snapshot(attempt = 12), -9.0, overflowSeen = false, nowMs = 1_000
            )
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Wait,
            controller.onFeedbackInterval(
                snapshot(attempt = 12), -9.0, overflowSeen = false, nowMs = 1_500
            )
        )
    }

    @Test
    fun `preflight centers RX feedback ratio with a capped gain change`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 0)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 3,
                ceilingDb = 48,
                reason = Hl2PureSignalFeedbackController.GainReason.PREFLIGHT_RATIO
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 0),
                feedbackRatioDb = -40.0,
                overflowSeen = false,
                nowMs = 1_000
            )
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(
                snapshot(attempt = 0),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
    }

    @Test
    fun `preflight accepts full coarse ratio band`() {
        listOf(-12.0, -9.0, -6.0).forEach { ratioDb ->
            val controller = Hl2PureSignalFeedbackController()
            controller.onPttStarted(nowMs = 0)
            assertEquals(
                Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
                controller.onFeedbackInterval(
                    snapshot(attempt = 0), ratioDb, overflowSeen = false, nowMs = 1_000
                )
            )
        }
    }

    @Test
    fun `invalid preflight samples wait without calibrating until timeout`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 0)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Wait,
            controller.onFeedbackInterval(
                snapshot(attempt = 0), Double.NaN, overflowSeen = false, nowMs = 1_000
            )
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StopRetry(
                Hl2PureSignalFeedbackController.StopReason.TIMEOUT
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 0), Double.NaN, overflowSeen = false, nowMs = 8_000
            )
        )
    }

    @Test
    fun `accepted target result completes the PTT calibration`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 10)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(
            snapshot(attempt = 4), -9.0, overflowSeen = false, nowMs = 1_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Complete(
                Hl2PureSignalFeedbackController.CompleteReason.TARGET_REACHED
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 5, level = 152, accepted = true),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
    }

    @Test
    fun `rejected target result is retried as another bounded one shot`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 10)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(
            snapshot(attempt = 0), -9.0, overflowSeen = false, nowMs = 1_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Wait,
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 152, accepted = false),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 152, accepted = false),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 3_000
            )
        )
    }

    @Test
    fun `overflow takes priority backs off six dB and lowers persistent ceiling`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 20)
        controller.onPttStarted(nowMs = 0)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 14,
                ceilingDb = 18,
                reason = Hl2PureSignalFeedbackController.GainReason.ADC_OVERFLOW
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 99, level = 152, accepted = true),
                feedbackRatioDb = -9.0,
                overflowSeen = true,
                nowMs = 1_000
            )
        )

        controller.onPttStopped()
        controller.onPttStarted(nowMs = 10_000)
        assertEquals(18, controller.gainCeilingDb)
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(
                snapshot(attempt = 0), -9.0, overflowSeen = false, nowMs = 11_000
            )
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 18,
                ceilingDb = 18,
                reason = Hl2PureSignalFeedbackController.GainReason.FEEDBACK_LEVEL
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 100, accepted = true),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 12_000
            )
        )
    }

    @Test
    fun `overflow after lock invalidates the accepted operating point`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 20)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(snapshot(0), -9.0, false, nowMs = 1_000)
        controller.onFeedbackInterval(
            snapshot(1, level = 152, accepted = true), -9.0, false, nowMs = 2_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 14,
                ceilingDb = 18,
                reason = Hl2PureSignalFeedbackController.GainReason.ADC_OVERFLOW
            ),
            controller.onFeedbackInterval(
                snapshot(1, level = 152, accepted = true), -9.0, true, nowMs = 3_000
            )
        )
    }

    @Test
    fun `feedback adjustment uses target center and caps each change at fifteen dB`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(
            snapshot(attempt = 0), -9.0, overflowSeen = false, nowMs = 1_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 3,
                ceilingDb = 48,
                reason = Hl2PureSignalFeedbackController.GainReason.FEEDBACK_LEVEL
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 1, accepted = false),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
    }

    @Test
    fun `accepts minimum usable feedback at the clipping ceiling`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 48)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(
            snapshot(attempt = 0), -9.0, overflowSeen = false, nowMs = 1_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Complete(
                Hl2PureSignalFeedbackController.CompleteReason.MINIMUM_USABLE_AT_CEILING
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 90, accepted = true),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
    }

    @Test
    fun `coarse preflight still tries once when clipping ceiling is below target`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 20)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(snapshot(0), -9.0, true, nowMs = 1_000)
        // Backoff is 14 dB and the persistent ceiling is 18 dB.
        controller.onFeedbackInterval(snapshot(0), -20.0, false, nowMs = 2_000)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(snapshot(0), -13.0, false, nowMs = 3_000)
        )
    }

    @Test
    fun `overflow still backs off after calibration timeout`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 20)
        controller.onPttStarted(nowMs = 0)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.ApplyGain(
                gainDb = 14,
                ceilingDb = 18,
                reason = Hl2PureSignalFeedbackController.GainReason.ADC_OVERFLOW
            ),
            controller.onFeedbackInterval(snapshot(0), -9.0, true, nowMs = 8_000)
        )
    }

    @Test
    fun `persistent overflow at minimum reports failure only once`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 0)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StopRetry(
                Hl2PureSignalFeedbackController.StopReason.OVERFLOW_AT_MIN_GAIN
            ),
            controller.onFeedbackInterval(snapshot(0), -9.0, true, nowMs = 1_000)
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.Wait,
            controller.onFeedbackInterval(snapshot(0), -9.0, true, nowMs = 2_000)
        )
    }

    @Test
    fun `stops when feedback remains unusable at the ceiling`() {
        val controller = Hl2PureSignalFeedbackController(initialGainDb = 48)
        controller.onPttStarted(nowMs = 0)
        controller.onFeedbackInterval(
            snapshot(attempt = 0), -9.0, overflowSeen = false, nowMs = 1_000
        )

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StopRetry(
                Hl2PureSignalFeedbackController.StopReason.FEEDBACK_TOO_WEAK_AT_CEILING
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = 1, level = 89, accepted = true),
                feedbackRatioDb = -9.0,
                overflowSeen = false,
                nowMs = 2_000
            )
        )
    }

    @Test
    fun `limits a PTT period to four gain changes`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 0)
        var attempt = 0
        var nowMs = 500L

        repeat(4) {
            assertEquals(
                Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
                controller.onFeedbackInterval(snapshot(attempt = attempt), -9.0, false, nowMs)
            )
            nowMs += 500
            attempt++
            assertTrue(
                controller.onFeedbackInterval(
                    snapshot(attempt = attempt, level = 1),
                    -9.0,
                    false,
                    nowMs
                ) is Hl2PureSignalFeedbackController.Decision.ApplyGain
            )
            nowMs += 500
        }

        assertEquals(4, controller.gainChangesThisPtt)
        assertEquals(48, controller.gainDb)
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration,
            controller.onFeedbackInterval(snapshot(attempt = attempt), -9.0, false, nowMs)
        )
        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StopRetry(
                Hl2PureSignalFeedbackController.StopReason.GAIN_CHANGE_LIMIT
            ),
            controller.onFeedbackInterval(
                snapshot(attempt = attempt + 1, level = 300),
                -9.0,
                false,
                nowMs = nowMs + 500
            )
        )
    }

    @Test
    fun `stops retrying after eight seconds`() {
        val controller = Hl2PureSignalFeedbackController()
        controller.onPttStarted(nowMs = 10_000)
        controller.onFeedbackInterval(snapshot(attempt = 0), -9.0, false, nowMs = 11_000)

        assertEquals(
            Hl2PureSignalFeedbackController.Decision.StopRetry(
                Hl2PureSignalFeedbackController.StopReason.TIMEOUT
            ),
            controller.onFeedbackInterval(snapshot(attempt = 0), -9.0, false, nowMs = 18_000)
        )
    }

    private fun snapshot(
        attempt: Int,
        level: Int = 0,
        accepted: Boolean = false
    ): PsInfoSnapshot {
        val raw = if (accepted) {
            acceptedInfo(attempt, level)
        } else {
            IntArray(16).also {
                it[4] = level
                it[5] = attempt
                it[9] = attempt
                it[10] = if (attempt > 0) PsInfoSnapshot.OUTCOME_REJECTED else 0
            }
        }
        return PsInfoSnapshot.from(raw)
    }

    companion object {
        private fun acceptedInfo(attempt: Int, level: Int): IntArray = IntArray(16).also {
            it[4] = level
            it[5] = attempt
            it[8] = attempt
            it[10] = PsInfoSnapshot.OUTCOME_ACCEPTED
            it[14] = 1
        }
    }
}

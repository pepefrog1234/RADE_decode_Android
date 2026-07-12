package yakumo2683.RADEdecode.network

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Encodes the Hermes-Lite 2 extended TX-feedback LNA gain field.
 *
 * Bit 7 enables the TX feedback path and bit 6 selects the extended six-bit
 * gain code.  In extended mode code zero is -12 dB and code 60 is +48 dB.
 */
internal fun encodeHl2TxFeedbackGain(gainDb: Int): Int {
    val clampedGain = gainDb.coerceIn(
        Hl2PureSignalFeedbackController.MIN_GAIN_DB,
        Hl2PureSignalFeedbackController.MAX_GAIN_DB
    )
    return 0xC0 or (clampedGain - Hl2PureSignalFeedbackController.MIN_GAIN_DB)
}

/** Immutable, named view of WDSP's 16-element PureSignal info vector. */
internal data class PsInfoSnapshot(
    val feedbackScaleFit: Int,
    val magnitudeFit: Int,
    val cosineFit: Int,
    val sineFit: Int,
    val feedbackLevel: Int,
    val attemptCounter: Int,
    val solutionSanity: Int,
    val feedbackFitSanity: Int,
    val acceptedCounter: Int,
    val rejectedCounter: Int,
    val lastOutcome: Int,
    val dogCounter: Int,
    val correctionApplied: Boolean,
    val state: Int
) {
    val fitIsSane: Boolean
        get() = feedbackScaleFit == 0 && magnitudeFit == 0 &&
            cosineFit == 0 && sineFit == 0 &&
            solutionSanity == 0 && feedbackFitSanity == 0

    /** True when the accepted, sane result is actively driving the corrector. */
    val solutionOk: Boolean
        get() = lastOutcome == OUTCOME_ACCEPTED && fitIsSane && correctionApplied

    companion object {
        const val OUTCOME_NONE = 0
        const val OUTCOME_ACCEPTED = 1
        const val OUTCOME_REJECTED = 2

        fun from(info: IntArray): PsInfoSnapshot {
            require(info.size >= INFO_SIZE) {
                "PureSignal info must contain at least $INFO_SIZE integers"
            }
            return PsInfoSnapshot(
                feedbackScaleFit = info[0],
                magnitudeFit = info[1],
                cosineFit = info[2],
                sineFit = info[3],
                feedbackLevel = info[4],
                attemptCounter = info[5],
                solutionSanity = info[6],
                feedbackFitSanity = info[7],
                acceptedCounter = info[8],
                rejectedCounter = info[9],
                lastOutcome = info[10],
                dogCounter = info[13],
                correctionApplied = info[14] != 0,
                state = info[15]
            )
        }

        private const val INFO_SIZE = 16
    }
}

/**
 * Bounded gain/preflight policy for one-shot HL2 PureSignal calibration.
 *
 * The learned gain and clipping ceiling persist between PTT periods.  Retry
 * counts and the timeout are per PTT period, preventing a bad feedback path
 * from making the application calibrate indefinitely.
 */
internal class Hl2PureSignalFeedbackController(
    initialGainDb: Int = MIN_GAIN_DB
) {
    init {
        require(initialGainDb in MIN_GAIN_DB..MAX_GAIN_DB) {
            "initialGainDb must be in $MIN_GAIN_DB..$MAX_GAIN_DB"
        }
    }

    var gainDb: Int = initialGainDb
        private set

    var gainCeilingDb: Int = MAX_GAIN_DB
        private set

    var gainChangesThisPtt: Int = 0
        private set

    private var phase = Phase.IDLE
    private var pttStartedAtMs = 0L
    private var attemptBaseline = 0

    /** Begin a PTT period. The first clean interval is always a preflight. */
    fun onPttStarted(nowMs: Long): Decision {
        pttStartedAtMs = nowMs
        gainChangesThisPtt = 0
        phase = Phase.PREFLIGHT
        return Decision.Wait
    }

    fun onPttStopped() {
        phase = Phase.IDLE
    }

    /**
     * Evaluate one feedback-health interval.
     *
     * [overflowSeen] has priority over solver results because a clipped ADC
     * cannot produce trustworthy calibration coefficients.
     */
    fun onFeedbackInterval(
        info: PsInfoSnapshot,
        feedbackRatioDb: Double,
        overflowSeen: Boolean,
        nowMs: Long
    ): Decision {
        if (phase == Phase.IDLE) {
            return Decision.Wait
        }

        if (overflowSeen) {
            if (phase == Phase.STOPPED && gainDb == MIN_GAIN_DB) {
                return Decision.Wait
            }
            return handleOverflow()
        }

        if (phase == Phase.COMPLETE || phase == Phase.STOPPED) {
            return Decision.Wait
        }

        if (timedOut(nowMs)) {
            return stop(StopReason.TIMEOUT)
        }

        return when (phase) {
            Phase.PREFLIGHT -> {
                evaluatePreflight(info, feedbackRatioDb, nowMs)
            }

            Phase.AWAITING_RESULT -> {
                if (info.attemptCounter <= attemptBaseline) {
                    Decision.Wait
                } else {
                    attemptBaseline = info.attemptCounter
                    evaluateResult(info, nowMs)
                }
            }

            else -> Decision.Wait
        }
    }

    private fun evaluatePreflight(
        info: PsInfoSnapshot,
        feedbackRatioDb: Double,
        nowMs: Long
    ): Decision {
        if (!feedbackRatioDb.isFinite()) return Decision.Wait

        if (feedbackRatioDb !in PREFLIGHT_RATIO_MIN_DB..PREFLIGHT_RATIO_MAX_DB) {
            val deltaDb = (PREFLIGHT_RATIO_TARGET_DB - feedbackRatioDb)
                .roundToInt()
                .coerceIn(-MAX_ADJUSTMENT_DB, MAX_ADJUSTMENT_DB)
            val requestedGain = (gainDb + deltaDb).coerceIn(MIN_GAIN_DB, gainCeilingDb)
            if (requestedGain == gainDb) {
                if (feedbackRatioDb < PREFLIGHT_RATIO_MIN_DB && gainDb >= gainCeilingDb) {
                    // A prior clip may impose a ceiling below the coarse ratio
                    // target. Let one solver attempt measure fblvl; a sane
                    // result >=90 is still usable and may be accepted safely.
                    attemptBaseline = info.attemptCounter
                    phase = Phase.AWAITING_RESULT
                    return Decision.StartSingleCalibration
                }
                return stop(StopReason.FEEDBACK_TOO_STRONG_AT_MIN_GAIN)
            }
            if (timedOut(nowMs)) return stop(StopReason.TIMEOUT)
            if (gainChangesThisPtt >= MAX_GAIN_CHANGES_PER_PTT) {
                return stop(StopReason.GAIN_CHANGE_LIMIT)
            }

            gainDb = requestedGain
            gainChangesThisPtt++
            // Remain in preflight: the new gain must produce a complete,
            // clean interval before the solver is allowed to run.
            return Decision.ApplyGain(gainDb, gainCeilingDb, GainReason.PREFLIGHT_RATIO)
        }

        // This call closes one complete, overflow-free interval at a usable
        // RX1-feedback/RX2-reference ratio.
        attemptBaseline = info.attemptCounter
        phase = Phase.AWAITING_RESULT
        return Decision.StartSingleCalibration
    }

    private fun handleOverflow(): Decision {
        // Clipping protection is intentionally exempt from retry/time limits:
        // even after calibration gives up, the physical ADC must be backed off.
        val clippedGain = gainDb
        gainCeilingDb = minOf(
            gainCeilingDb,
            (clippedGain - CLIP_CEILING_MARGIN_DB).coerceAtLeast(MIN_GAIN_DB)
        )
        val backedOffGain = (clippedGain - OVERFLOW_BACKOFF_DB).coerceAtLeast(MIN_GAIN_DB)
        if (backedOffGain == gainDb) {
            return stop(StopReason.OVERFLOW_AT_MIN_GAIN)
        }

        gainDb = backedOffGain
        gainChangesThisPtt++
        phase = Phase.PREFLIGHT
        return Decision.ApplyGain(gainDb, gainCeilingDb, GainReason.ADC_OVERFLOW)
    }

    private fun evaluateResult(info: PsInfoSnapshot, nowMs: Long): Decision {
        val level = info.feedbackLevel

        if (level in TARGET_MIN..TARGET_MAX) {
            return if (info.solutionOk) {
                complete(CompleteReason.TARGET_REACHED)
            } else {
                // A rejected one-shot may be tried again, but only after
                // another clean interval and within the per-PTT timeout.
                phase = Phase.PREFLIGHT
                Decision.Wait
            }
        }

        if (level < TARGET_MIN && gainDb >= gainCeilingDb) {
            return if (level >= MIN_USABLE_AT_CEILING && info.solutionOk) {
                complete(CompleteReason.MINIMUM_USABLE_AT_CEILING)
            } else if (level < MIN_USABLE_AT_CEILING) {
                stop(StopReason.FEEDBACK_TOO_WEAK_AT_CEILING)
            } else {
                phase = Phase.PREFLIGHT
                Decision.Wait
            }
        }

        val deltaDb = feedbackGainAdjustment(level)
        val requestedGain = (gainDb + deltaDb).coerceIn(MIN_GAIN_DB, gainCeilingDb)
        if (requestedGain == gainDb) {
            return stop(
                if (level < TARGET_MIN) StopReason.FEEDBACK_TOO_WEAK_AT_CEILING
                else StopReason.FEEDBACK_TOO_STRONG_AT_MIN_GAIN
            )
        }

        if (timedOut(nowMs)) return stop(StopReason.TIMEOUT)
        if (gainChangesThisPtt >= MAX_GAIN_CHANGES_PER_PTT) {
            return stop(StopReason.GAIN_CHANGE_LIMIT)
        }

        gainDb = requestedGain
        gainChangesThisPtt++
        phase = Phase.PREFLIGHT
        return Decision.ApplyGain(gainDb, gainCeilingDb, GainReason.FEEDBACK_LEVEL)
    }

    private fun feedbackGainAdjustment(feedbackLevel: Int): Int {
        if (feedbackLevel <= 0) return MAX_ADJUSTMENT_DB
        return (20.0 * log10(TARGET_CENTER / feedbackLevel.toDouble()))
            .roundToInt()
            .coerceIn(-MAX_ADJUSTMENT_DB, MAX_ADJUSTMENT_DB)
    }

    private fun timedOut(nowMs: Long): Boolean =
        nowMs - pttStartedAtMs >= RETRY_TIMEOUT_MS

    private fun complete(reason: CompleteReason): Decision.Complete {
        phase = Phase.COMPLETE
        return Decision.Complete(reason)
    }

    private fun stop(reason: StopReason): Decision.StopRetry {
        phase = Phase.STOPPED
        return Decision.StopRetry(reason)
    }

    private enum class Phase {
        IDLE,
        PREFLIGHT,
        AWAITING_RESULT,
        COMPLETE,
        STOPPED
    }

    sealed interface Decision {
        data object Wait : Decision
        data object StartSingleCalibration : Decision

        data class ApplyGain(
            val gainDb: Int,
            val ceilingDb: Int,
            val reason: GainReason
        ) : Decision

        data class Complete(val reason: CompleteReason) : Decision
        data class StopRetry(val reason: StopReason) : Decision
    }

    enum class GainReason {
        ADC_OVERFLOW,
        PREFLIGHT_RATIO,
        FEEDBACK_LEVEL
    }

    enum class CompleteReason {
        TARGET_REACHED,
        MINIMUM_USABLE_AT_CEILING
    }

    enum class StopReason {
        TIMEOUT,
        GAIN_CHANGE_LIMIT,
        OVERFLOW_AT_MIN_GAIN,
        FEEDBACK_TOO_WEAK_AT_CEILING,
        FEEDBACK_TOO_STRONG_AT_MIN_GAIN
    }

    companion object {
        const val MIN_GAIN_DB = -12
        const val MAX_GAIN_DB = 48
        const val TARGET_MIN = 140
        const val TARGET_MAX = 165
        const val MIN_USABLE_AT_CEILING = 90
        const val MAX_GAIN_CHANGES_PER_PTT = 4
        const val RETRY_TIMEOUT_MS = 8_000L

        const val PREFLIGHT_RATIO_MIN_DB = -12.0
        const val PREFLIGHT_RATIO_MAX_DB = -6.0
        const val PREFLIGHT_RATIO_TARGET_DB = -9.0

        private const val TARGET_CENTER = 152.293
        private const val MAX_ADJUSTMENT_DB = 15
        private const val OVERFLOW_BACKOFF_DB = 6
        private const val CLIP_CEILING_MARGIN_DB = 2
    }
}

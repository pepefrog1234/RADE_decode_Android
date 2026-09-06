package yakumo2683.RADEdecode.service

/**
 * Deadline pacing for the network TX audio pump (Icom RS-BA1 / IC-705 Wi-Fi),
 * with bounded catch-up.
 *
 * The radio plays TX audio from a small jitter buffer (~150–300 ms on the
 * IC-7300MK2) that we keep topped up at exactly 50 frames/s. Whenever the pump
 * falls behind — a late wakeup, or `send()` blocked because the VPN/LTE uplink
 * stalled — the radio keeps draining its buffer while nothing arrives. The
 * pre-v1.6.15 policy re-anchored the schedule and never sent the frames the
 * radio had consumed, so every stall permanently lowered its buffer until it
 * starved.
 *
 * Policy now: after a stall of S with a radio buffer of B,
 *  - the radio played B of buffered audio and was silent for S − B: that hole
 *    is on the air already and cannot be undone;
 *  - we send back at most B worth of owed frames ([maxCatchupFrames]) at a
 *    gentle 2x (one regular + [maxCatchupPerTick] extra per period), which
 *    refills the radio's buffer to exactly its nominal depth;
 *  - the remaining S − B owed frames are DROPPED from the encoder ring. Sending
 *    them would push the radio's buffer past B — beyond what the IC-7300MK2 can
 *    hold (v1.6.16 field test: 500 ms → long dropouts, 800 ms → no modulation
 *    at all) — and add the stall as permanent latency.
 *  When the ring holds no backlog (the encoder itself stalled) there is
 *  nothing to catch up with and the schedule is simply re-anchored.
 *
 * Pure scheduling arithmetic (unit-tested); the pump owns the I/O.
 */
internal class NetTxPacer(
    private val periodNs: Long,
    private val maxCatchupPerTick: Int = 1,
    /** Owed frames beyond this are dropped instead of sent (≈ radio buffer / period). */
    private val maxCatchupFrames: Int
) {
    /** Deadline of the NEXT regular frame. */
    var nextDeadlineNs: Long = 0L
        private set

    // Diagnostics (read by the pump's periodic health log).
    var lateTicks = 0L; private set
    var maxBehindNs = 0L; private set
    var catchupFrames = 0L; private set
    var droppedFrames = 0L; private set
    var backlogDrops = 0L; private set
    var reanchors = 0L; private set

    /** What the pump should do after sending the regular frame. */
    data class Plan(
        /** Extra frames to read from the ring and SEND now (catch-up). */
        val extraFrames: Int,
        /** Owed frames to read from the ring and DISCARD (stale backlog). */
        val dropFrames: Int,
        /** Time to sleep before the next regular frame. */
        val sleepNs: Long
    )

    fun start(nowNs: Long) {
        nextDeadlineNs = nowNs + periodNs
    }

    /**
     * Call right after the regular frame for the current deadline was sent.
     * @param spareFrames whole frames the encoder ring can give up beyond its
     *   safety reserve — extra sends and drops are both bounded by it so a
     *   catch-up can never read silence out of an empty ring.
     */
    fun afterSend(nowNs: Long, spareFrames: Int): Plan {
        nextDeadlineNs += periodNs
        val behind = nowNs - nextDeadlineNs
        if (behind <= 0) return Plan(0, 0, -behind)

        lateTicks++
        if (behind > maxBehindNs) maxBehindNs = behind
        val owed = (behind / periodNs).toInt()           // whole frames late
        if (owed <= 0) return Plan(0, 0, 0)               // sub-frame late: just don't sleep

        val spare = spareFrames.coerceAtLeast(0)
        if (spare <= 0) {
            // Late, but the ring holds no backlog: the encoder stalled with us.
            // Nothing real to catch up with — re-anchor rather than pad silence.
            reanchors++
            nextDeadlineNs = nowNs
            return Plan(0, 0, 0)
        }

        // Anything beyond one radio buffer of backlog is already a hole on the
        // air: drop it (oldest first) so the catch-up refills the radio to its
        // nominal depth and no further.
        var drop = 0
        if (owed > maxCatchupFrames) {
            drop = minOf(owed - maxCatchupFrames, spare)
            if (drop > 0) {
                backlogDrops++
                droppedFrames += drop
                nextDeadlineNs += drop * periodNs
            }
        }
        val stillOwed = owed - drop
        val extra = minOf(stillOwed, maxCatchupPerTick, spare - drop).coerceAtLeast(0)
        catchupFrames += extra
        nextDeadlineNs += extra * periodNs
        // Still behind after the extra frame(s): hold the 2x pace (one full
        // period) instead of spinning; otherwise sleep to the real deadline.
        val sleep = if (nextDeadlineNs >= nowNs) nextDeadlineNs - nowNs else periodNs
        return Plan(extra, drop, sleep)
    }

    fun resetStats() {
        lateTicks = 0; maxBehindNs = 0; catchupFrames = 0; droppedFrames = 0
        backlogDrops = 0; reanchors = 0
    }
}

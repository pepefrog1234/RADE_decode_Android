package yakumo2683.RADEdecode.service

/**
 * Deadline pacing for the network TX audio pump (Icom RS-BA1 / IC-705 Wi-Fi),
 * with bounded catch-up.
 *
 * The radio plays TX audio from a jitter buffer that we keep topped up at
 * exactly 50 frames/s. Whenever the pump wakes late (scheduler/GC hiccup, a
 * VPN or LTE modem stalling the socket) the radio keeps draining its buffer
 * while we send nothing. The old policy simply re-anchored the deadline after
 * such a stall, so the frames the radio consumed meanwhile were never sent:
 * every stall permanently lowered the radio's buffer occupancy until it
 * eventually starved — the reported brief interruptions of the transmitted
 * RADE signal over LTE/VPN — while the missed audio piled up in the encoder
 * ring as latency (and, past the ring's 4 s, was dropped).
 *
 * Now, when we fall behind by whole frames we send the owed frames at a
 * gentle 2x (one regular + [maxCatchupPerTick] extra per period), refilling
 * the radio's buffer to its nominal depth without ever exceeding it — we only
 * send what the radio has already consumed. A backlog larger than
 * [dropBacklogNs] means the radio has been silent for far longer than its
 * buffer, so the far end has lost sync anyway: that stale audio is dropped
 * instead, restoring normal latency for the rest of the over. When the ring
 * has nothing spare (the encoder itself stalled) there is nothing to catch up
 * with, and the schedule is simply re-anchored.
 *
 * Pure scheduling arithmetic (unit-tested); the pump owns the I/O.
 */
internal class NetTxPacer(
    private val periodNs: Long,
    private val maxCatchupPerTick: Int = 1,
    private val dropBacklogNs: Long
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
        /** Owed frames to read from the ring and DISCARD (hopeless backlog). */
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
        if (behind > dropBacklogNs) {
            val drop = minOf(owed, spare)
            backlogDrops++
            droppedFrames += drop
            nextDeadlineNs += drop * periodNs
            return Plan(0, drop, (nextDeadlineNs - nowNs).coerceAtLeast(0))
        }
        val extra = minOf(owed, maxCatchupPerTick, spare)
        catchupFrames += extra
        nextDeadlineNs += extra * periodNs
        // Still behind after the extra frame(s): hold the 2x pace (one full
        // period) instead of spinning; otherwise sleep to the real deadline.
        val sleep = if (nextDeadlineNs >= nowNs) nextDeadlineNs - nowNs else periodNs
        return Plan(extra, 0, sleep)
    }

    fun resetStats() {
        lateTicks = 0; maxBehindNs = 0; catchupFrames = 0; droppedFrames = 0
        backlogDrops = 0; reanchors = 0
    }
}

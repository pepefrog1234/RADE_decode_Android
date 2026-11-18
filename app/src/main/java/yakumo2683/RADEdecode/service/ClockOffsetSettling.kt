package yakumo2683.RADEdecode.service

/**
 * Tells whether the RADE V2 clock offset estimate read now has settled.
 *
 * The modem restarts the estimate from 0 at every acquisition and it creeps
 * up from there: about three quarters of the real offset after 30 s of sync,
 * within a few percent after 45 s. Before that it is not a measurement yet.
 * Not thread safe: polled from the service's status loop.
 */
internal class ClockOffsetSettling(private val settleMs: Long = SETTLE_MS) {
    private var wasSynced = false
    private var syncedSinceMs = 0L
    private var settled = false

    /**
     * @param synced the receiver is in sync on this poll
     * @param acquired the receiver (re)acquired since the last poll, even if
     *   it was also in sync then: the modem restarted its estimate
     * @param nowMs a monotonic clock
     * @return whether the estimate has had [settleMs] of uninterrupted sync.
     *   Stays true after the over ends, because the modem holds the estimate.
     */
    fun poll(synced: Boolean, acquired: Boolean, nowMs: Long): Boolean {
        if (acquired || (synced && !wasSynced)) {
            syncedSinceMs = nowMs
            settled = false
        }
        wasSynced = synced
        if (synced && nowMs - syncedSinceMs >= settleMs) settled = true
        return settled
    }

    companion object {
        const val SETTLE_MS = 45_000L
    }
}

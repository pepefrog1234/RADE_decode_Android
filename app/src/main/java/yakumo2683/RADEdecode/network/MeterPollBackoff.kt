package yakumo2683.RADEdecode.network

/**
 * When to stop asking a rig for a meter it fails to read.
 *
 * A failed read is not free. Hamlib retries it, and when the answer is one it
 * cannot parse, rigctld closes the rig, waits a second and reopens it. Every
 * CAT command sent meanwhile, a key-on included, waits for that. Measured with
 * the real daemon (Hamlib 4.7.2) and a QMX-style rig whose "SM;" answer has a
 * length Hamlib does not expect: polled every cycle, key-ons reached the rig
 * up to 1.3 s late; a rig that does not answer "SM;" at all holds CAT for 2 to
 * 3.4 s per read.
 *
 * So one failure is enough to pause, and each further one doubles the pause.
 * Failures are forgotten only after several reads in a row have worked: a
 * meter that fails now and then (an answer whose length depends on the value)
 * would otherwise be polled, and stall, as often as before.
 *
 * Not thread-safe: the caller serialises access.
 */
internal class MeterPollBackoff(
    private val firstPauseMs: Long = 30_000,
    private val longestPauseMs: Long = 300_000,
    private val successesToForget: Int = 5
) {
    private var failures = 0
    private var successes = 0
    private var retryAtMs: Long? = null

    fun reset() {
        failures = 0
        successes = 0
        retryAtMs = null
    }

    /**
     * Record a completed read.
     * @return the pause a failure starts, in ms; 0 after a success.
     */
    fun record(succeeded: Boolean, nowMs: Long): Long {
        if (succeeded) {
            if (++successes >= successesToForget) failures = 0
            return 0
        }
        successes = 0
        val pause = minOf(firstPauseMs shl minOf(failures, 20), longestPauseMs)
        failures++
        retryAtMs = nowMs + pause
        return pause
    }

    fun due(nowMs: Long): Boolean = retryAtMs?.let { nowMs >= it } ?: true
}

package yakumo2683.RADEdecode.network

/** Radio replies, not localhost TCP liveness. Called only for completed ERP
 * transactions, including replies delivered after their caller timed out. */
internal class RigCatHealth(private val failureWindowMs: Long = 15_000) {
    private var failedSince: Long? = null
    private var failures = 0
    private var offFailedSince: Long? = null
    private var offFailures = 0
    private var reported = false

    fun reset() {
        failedSince = null; failures = 0
        offFailedSince = null; offFailures = 0
        reported = false
    }

    fun record(name: String, args: String, response: RigctldResponse, nowMs: Long): Boolean {
        val off = name == "set_ptt" && args == "0"
        val offConfirmed = (off && response.result == 0) ||
            (name == "get_ptt" && response.value("PTT") == "0")
        if (offConfirmed) { offFailedSince = null; offFailures = 0 }
        if (off && response.result in setOf(-5, -6, -8)) {
            if (offFailedSince == null) offFailedSince = nowMs
            offFailures++
        }
        // An unsupported meter/mode command is not evidence of a dead tunnel.
        if (name in setOf("get_freq", "get_ptt", "set_ptt")) {
            if (response.result == 0) {
                failedSince = null; failures = 0
            } else if (response.result in setOf(-5, -6, -8)) {
                if (failedSince == null) failedSince = nowMs
                failures++
            }
        }
        val failed = failures >= 3 && failedSince?.let { nowMs - it >= failureWindowMs } == true
        val offFailed = offFailures >= 3 && offFailedSince?.let { nowMs - it >= failureWindowMs } == true
        if (reported || (!failed && !offFailed)) return false
        reported = true
        return true
    }
}

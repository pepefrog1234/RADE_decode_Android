package yakumo2683.RADEdecode.network

/** Radio replies, not localhost TCP liveness. Called only for completed ERP
 * transactions, including replies delivered after their caller timed out. */
internal class RigCatHealth(private val failureWindowMs: Long = 15_000) {
    private var failedSince: Long? = null
    private var failures = 0
    private var offFailedSince: Long? = null
    private var offFailures = 0
    private var reported = false
    private class Failures(var since: Long, var count: Int = 0)
    private val readFailures = mutableMapOf<String, Failures>()

    fun reset() {
        failedSince = null; failures = 0
        offFailedSince = null; offFailures = 0
        readFailures.clear()
        reported = false
    }

    fun record(name: String, args: String, response: RigctldResponse, nowMs: Long,
               confirmOffByReadback: Boolean = false): Boolean {
        val off = name == "set_ptt" && args == "0"
        val offConfirmed = (off && response.result == 0 && !confirmOffByReadback) ||
            (name == "get_ptt" && response.value("PTT") == "0")
        if (offConfirmed) { offFailedSince = null; offFailures = 0 }
        // A NAK (-9), bus error (-13), or collision (-14) can leave the
        // session unusable even though localhost TCP and UDP pings are healthy.
        val linkFailure = response.result in setOf(-5, -6, -8, -9, -13, -14)
        val validRead = when (name) {
            "get_freq" -> response.value("Frequency")?.toLongOrNull()?.let { it in 10_000L..1_300_000_000L } == true
            "get_ptt" -> rigctldPtt(response.value("PTT")) != null
            else -> response.result == 0
        }
        val failedRead = linkFailure || (response.result == 0 && !validRead)
        if (off && (linkFailure || (confirmOffByReadback && response.result == 0))) {
            if (offFailedSince == null) offFailedSince = nowMs
            offFailures++
        }
        if (name == "get_freq" || name == "get_ptt") {
            if (validRead) readFailures.remove(name)
            else if (failedRead) readFailures.getOrPut(name) { Failures(nowMs) }.count++
        }
        // An unsupported meter/mode command is not evidence of a dead tunnel.
        if (name in setOf("get_freq", "get_ptt", "set_ptt")) {
            if (validRead) {
                failedSince = null; failures = 0
            } else if (failedRead) {
                if (failedSince == null) failedSince = nowMs
                failures++
            }
        }
        val failed = failures >= 3 && failedSince?.let { nowMs - it >= failureWindowMs } == true
        val offFailed = offFailures >= 3 && offFailedSince?.let { nowMs - it >= failureWindowMs } == true
        // A cached/healthy frequency must not conceal repeatedly rejected PTT
        // reads (and vice versa), as in the v1.6.24 IC-7300MK2 field logs.
        val readFailed = readFailures.values.any { it.count >= 3 && nowMs - it.since >= failureWindowMs }
        if (reported || (!failed && !offFailed && !readFailed)) return false
        reported = true
        return true
    }
}

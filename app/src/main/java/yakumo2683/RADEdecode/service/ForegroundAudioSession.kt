package yakumo2683.RADEdecode.service

/** Tracks an already granted foreground session across bounded audio recovery.
 * Callers serialize access with AudioService's monitor. A recovery ticket belongs
 * to this service instance and is revoked by Stop or any new user start. */
internal class ForegroundAudioSession {
    var isActive = false
        private set
    private var generation = 0L
    private var recoveryTicket: Long? = null

    fun start(promote: () -> Unit) {
        recoveryTicket = null
        if (isActive) return
        // Android may reject a start after the activity went into the background.
        // Never claim foreground ownership when promotion did not succeed.
        promote()
        isActive = true
    }

    fun stop(demote: () -> Unit) {
        recoveryTicket = null
        isActive = false
        demote()
    }

    fun holdForReconnect(): Long? {
        if (!isActive) return null
        return (++generation).also { recoveryTicket = it }
    }

    fun canResume(ticket: Long): Boolean = isActive && recoveryTicket == ticket

    fun resume(ticket: Long, restartAudio: () -> Boolean): Boolean? {
        if (!canResume(ticket)) return null
        recoveryTicket = null
        return restartAudio()
    }
}

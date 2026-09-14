package yakumo2683.RADEdecode.ui

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/** A Stop/disconnect invalidates RX restoration without cancelling PTT cleanup.
 * This is separate from the TX request id: a rapid re-key still needs RX restored
 * before its queued TX can start. */
internal class RxRecovery {
    private val generation = AtomicLong()

    fun snapshot(): Long = generation.get()

    fun invalidate() { generation.incrementAndGet() }

    fun isCurrent(session: Long): Boolean = session == generation.get()

    suspend fun restore(
        session: Long,
        stopTxAndUnkey: suspend () -> Unit,
        canResume: () -> Boolean,
        resumeRx: () -> Unit
    ): Boolean {
        // Always finish cleanup, even if Stop invalidated this session meanwhile.
        stopTxAndUnkey()
        delay(20) // Let the audio route settle after closing the TX streams.
        if (!isCurrent(session) || !canResume()) return false
        resumeRx()
        return true
    }
}

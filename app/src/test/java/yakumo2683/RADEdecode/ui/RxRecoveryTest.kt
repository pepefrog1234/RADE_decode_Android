package yakumo2683.RADEdecode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production recovery lifecycle without Android audio or a radio. */
class RxRecoveryTest {
    @Test
    fun receiveResumesOnlyAfterAudioDrainAndPttRelease() = runBlocking {
        val recovery = RxRecovery()
        val events = mutableListOf<String>()
        val drainFinished = CompletableDeferred<Unit>()
        val releaseStarted = CompletableDeferred<Unit>()
        val releaseFinished = CompletableDeferred<Unit>()
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.restore(
                session = recovery.snapshot(),
                stopTxAndUnkey = {
                    events += "drain"
                    drainFinished.await()
                    events += "unkey"
                    releaseStarted.complete(Unit)
                    releaseFinished.await()
                    events += "released"
                },
                canResume = { true },
                resumeRx = { events += "rx" }
            )
        }
        assertEquals(listOf("drain"), events)
        drainFinished.complete(Unit)
        withTimeout(2_000) { releaseStarted.await() }
        assertEquals(listOf("drain", "unkey"), events)
        assertFalse(transition.isCompleted)
        releaseFinished.complete(Unit)
        assertTrue(withTimeout(2_000) { transition.await() })
        assertEquals(listOf("drain", "unkey", "released", "rx"), events)
    }

    @Test
    fun stopOrDisconnectDuringCleanupStillUnkeysAndSuppressesReceive() = runBlocking {
        // Stop and disconnect deliberately use the same production invalidation.
        assertInvalidationDuringCleanup()
    }

    @Test
    fun stopOrDisconnectDuringRouteSettleSuppressesReceive() = runBlocking {
        assertInvalidationDuringSettle()
    }

    @Test
    fun replacingTheServiceWhileCleaningUpPreventsRestartingEitherService() = runBlocking {
        val recovery = RxRecovery()
        val originalService = Any()
        var currentService = originalService
        val cleanupFinished = CompletableDeferred<Unit>()
        var resumed = false
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.restore(
                session = recovery.snapshot(),
                stopTxAndUnkey = { cleanupFinished.await() },
                canResume = { currentService === originalService },
                resumeRx = { resumed = true }
            )
        }
        currentService = Any()
        cleanupFinished.complete(Unit)
        assertFalse(withTimeout(2_000) { transition.await() })
        assertFalse(resumed)
    }

    @Test
    fun cleanupFailureIsPropagatedWithoutResumingReceive() = runBlocking {
        val recovery = RxRecovery()
        val failure = IllegalStateException("audio teardown failed")
        var resumeChecked = false
        var resumed = false
        val caught = try {
            recovery.restore(
                session = recovery.snapshot(),
                stopTxAndUnkey = { throw failure },
                canResume = { resumeChecked = true; true },
                resumeRx = { resumed = true }
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertSame(failure, caught)
        assertFalse(resumeChecked)
        assertFalse(resumed)
    }

    @Test
    fun rapidRekeyKeepsTheSessionAndWaitsForReceiveRestoration() = runBlocking {
        val recovery = RxRecovery()
        val session = recovery.snapshot()
        val events = mutableListOf<String>()
        val cleanupFinished = CompletableDeferred<Unit>()
        var receiverRunning = false
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.restore(
                session = session,
                stopTxAndUnkey = {
                    events += "cleanup"
                    cleanupFinished.await()
                    events += "unkeyed"
                },
                canResume = { true },
                resumeRx = { receiverRunning = true; events += "rx" }
            )
        }
        // A new PTT request queues behind cleanup; it does not invalidate the
        // RX session as Stop or disconnect would.
        val queuedTx = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(transition.await())
            assertTrue(receiverRunning)
            events += "key"
        }
        assertTrue(recovery.isCurrent(session))
        assertEquals(listOf("cleanup"), events)
        cleanupFinished.complete(Unit)
        withTimeout(2_000) { queuedTx.join() }
        assertEquals(listOf("cleanup", "unkeyed", "rx", "key"), events)
    }

    private suspend fun assertInvalidationDuringCleanup() = coroutineScope {
        val recovery = RxRecovery()
        val session = recovery.snapshot()
        val cleanupFinished = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.restore(
                session = session,
                stopTxAndUnkey = {
                    events += "drain"
                    cleanupFinished.await()
                    events += "unkey"
                },
                canResume = { true },
                resumeRx = { events += "rx" }
            )
        }
        recovery.invalidate()
        assertFalse(recovery.isCurrent(session))
        assertFalse(transition.isCompleted)
        cleanupFinished.complete(Unit)
        assertFalse(withTimeout(2_000) { transition.await() })
        assertEquals(listOf("drain", "unkey"), events)
    }

    private suspend fun assertInvalidationDuringSettle() = coroutineScope {
        val recovery = RxRecovery()
        val session = recovery.snapshot()
        var cleanupFinished = false
        var resumed = false
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            recovery.restore(
                session = session,
                stopTxAndUnkey = { cleanupFinished = true },
                canResume = { true },
                resumeRx = { resumed = true }
            )
        }
        // UNDISPATCHED runs cleanup immediately and returns at restore's first
        // suspension (its route-settle delay). This event-loop thread cannot
        // resume the delay until the following invalidation has executed.
        assertTrue(cleanupFinished)
        assertFalse(transition.isCompleted)
        recovery.invalidate()
        assertFalse(withTimeout(2_000) { transition.await() })
        assertFalse(resumed)
    }
}

package yakumo2683.RADEdecode.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RigctldTransportTest {
    @Test fun freshFrequencyProbeNeverUsesCachedOrMalformedReplies() = runBlocking {
        val replies = listOf(
            "Frequency: 7105000\nRPRT 0",
            "Frequency: 7177000\nRPRT -9", // Failed commands cannot confirm even a labelled value.
            "RPRT 0",                     // Empty success is not a frequency.
            "Frequency: 0\nRPRT 0",      // Invalid range.
            "Frequency: invalid\nRPRT 0",
            "RPRT -9"
        )
        FakeRig { input, output ->
            for (reply in replies) {
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\n$reply\n"); output.flush()
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertEquals(7_105_000L, controller.readFrequency())
                repeat(4) {
                    assertNull("Recovery needs a fresh valid response", controller.readFrequency())
                    assertEquals(7_105_000L, controller.state.value.freqHz)
                }
                assertEquals("UI polling retains its last good dial value", 7_105_000L, controller.getFreq())
                controller.disconnect()
                assertNull("A disconnected session cannot provide fresh frequency", controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun freshFrequencyProbeCannotFollowAReplacementConnection() = runBlocking {
        val oldSeen = CompletableDeferred<Unit>()
        FakeRig { input, _ ->
            assertEquals("+\\get_freq", input.readLine())
            oldSeen.complete(Unit)
            assertNull(input.readLine())
        }.use { oldRig ->
            FakeRig { input, output ->
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\nFrequency: 14236000\nRPRT 0\n"); output.flush()
            }.use { newRig ->
                val controller = RigController(pollingEnabled = false)
                try {
                    controller.connect("127.0.0.1", oldRig.port)
                    val old = async { controller.readFrequency() }
                    withTimeout(1000) { oldSeen.await() }
                    controller.connect("127.0.0.1", newRig.port)
                    assertNull(old.await())
                    assertEquals(14_236_000L, controller.readFrequency())
                    oldRig.checkFinished()
                    newRig.checkFinished()
                } finally { controller.destroy() }
            }
        }
    }

    @Test fun networkOffAckDoesNotConfirmRxWhileRadioStillReportsTx() = runBlocking {
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 1", input.readLine())
            output.print("set_ptt: 1\nRPRT 0\n"); output.flush()
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 0\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_ptt", input.readLine())
            output.print("get_ptt:\nPTT: 1\nRPRT 0\n"); output.flush()
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 0\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_ptt", input.readLine())
            output.print("get_ptt:\nPTT: 0\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port, verifyPttOff = true)
                assertTrue(controller.setPtt(true))
                assertFalse(controller.setPtt(false))
                assertTrue(controller.state.value.ptt)
                assertTrue(controller.state.value.error.contains("not confirmed"))
                assertTrue(controller.setPtt(false))
                assertFalse(controller.state.value.ptt)
                assertEquals("", controller.state.value.error)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun emptyPttReadbackCannotConfirmNetworkOff() = runBlocking {
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 0\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_ptt", input.readLine())
            output.print("get_ptt:\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port, verifyPttOff = true)
                assertFalse(controller.setPtt(false))
                assertTrue(controller.state.value.error.contains("unknown"))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun laterMatchingPollClearsAnUnconfirmedFrequencyWarning() = runBlocking {
        FakeRig { input, output ->
            assertEquals("+\\set_freq 14236000", input.readLine())
            output.print("set_freq: 14236000\nRPRT -8\n"); output.flush()
            assertEquals("+\\get_freq", input.readLine())
            output.print("get_freq:\nRPRT -8\n"); output.flush()
            for (hz in listOf(7_100_000, 14_236_000)) {
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\nFrequency: $hz\nRPRT 0\n"); output.flush()
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertFalse(controller.setFreq(14_236_000))
                assertTrue(controller.state.value.frequencyError.isNotEmpty())
                assertEquals(7_100_000L, controller.getFreq())
                assertTrue(controller.state.value.frequencyError.isNotEmpty())
                assertEquals(14_236_000L, controller.getFreq())
                assertEquals("", controller.state.value.frequencyError)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun oldFrequencyOperationCannotSendReadbackToAReplacementConnection() = runBlocking {
        val oldSeen = CompletableDeferred<Unit>()
        FakeRig { input, _ ->
            assertEquals("+\\set_freq 7100000", input.readLine())
            oldSeen.complete(Unit)
            assertNull(input.readLine()) // Reconnecting closes this session.
        }.use { oldRig ->
            FakeRig { input, output ->
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\nFrequency: 14236000\nRPRT 0\n"); output.flush()
            }.use { newRig ->
                val controller = RigController(pollingEnabled = false)
                try {
                    controller.connect("127.0.0.1", oldRig.port)
                    val old = async { controller.setFreq(7_100_000) }
                    withTimeout(1000) { oldSeen.await() }
                    controller.connect("127.0.0.1", newRig.port)
                    assertFalse(old.await())
                    assertEquals("", controller.state.value.frequencyError)
                    assertEquals(0L, controller.state.value.freqHz)
                    assertEquals(14_236_000L, controller.getFreq())
                    oldRig.checkFinished()
                    newRig.checkFinished()
                } finally { controller.destroy() }
            }
        }
    }

    @Test fun setFrequencyRequiresMatchingReadbackRatherThanOnlyAnAck() = runBlocking {
        FakeRig { input, output ->
            assertEquals("+\\set_freq 14236000", input.readLine())
            output.print("set_freq: 14236000\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_freq", input.readLine())
            output.print("get_freq:\nFrequency: 7100000\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertFalse(controller.setFreq(14_236_000))
                assertEquals(7_100_000L, controller.state.value.freqHz)
                assertTrue(controller.state.value.frequencyError.contains("not confirmed"))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun slowFrequencySetIsReadBackAfterItsReply() = runBlocking {
        val permitted = AtomicReference<Long?>()
        FakeRig { input, output ->
            assertEquals("+\\set_freq 5357500", input.readLine())
            assertEquals(5_357_500L, permitted.get())
            Thread.sleep(1200) // Exceeded the previous 1 s set deadline.
            output.print("set_freq: 5357500\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_freq", input.readLine())
            assertEquals(5_357_500L, permitted.get())
            output.print("get_freq:\nFrequency: 5357500\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            controller.authorizeFrequencyChange = { hz ->
                permitted.set(hz)
                AutoCloseable { permitted.set(null) }
            }
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setFreq(5_357_500))
                assertNull(permitted.get())
                assertEquals(5_357_500L, controller.state.value.freqHz)
                assertEquals("", controller.state.value.frequencyError)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun pollStartedBeforeSetCannotOverwriteTheNewFrequencyOperation() = runBlocking {
        val pollSeen = CompletableDeferred<Unit>()
        val releaseSet = CountDownLatch(1)
        FakeRig { input, output ->
            assertEquals("+\\get_freq", input.readLine())
            pollSeen.complete(Unit)
            assertEquals("+\\set_freq 14236000", input.readLine())
            output.print("get_freq:\nFrequency: 7100000\nRPRT 0\n"); output.flush()
            assertTrue(releaseSet.await(2, TimeUnit.SECONDS))
            output.print("set_freq: 14236000\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_freq", input.readLine())
            output.print("get_freq:\nFrequency: 14236000\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                val poll = async { controller.getFreq() }
                withTimeout(1000) { pollSeen.await() }
                val set = async { controller.setFreq(14_236_000) }
                assertEquals(0L, withTimeout(1500) { poll.await() })
                assertEquals(0L, controller.state.value.freqHz)
                releaseSet.countDown()
                assertTrue(set.await())
                assertEquals(14_236_000L, controller.state.value.freqHz)
                rig.checkFinished()
            } finally { releaseSet.countDown(); controller.destroy() }
        }
    }

    @Test fun cancelledFrequencyRequestCannotConsumeOrOverwriteItsReplacement() = runBlocking {
        val oldSeen = CompletableDeferred<Unit>()
        val permitted = AtomicReference<Long?>()
        FakeRig { input, output ->
            assertEquals("+\\set_freq 7100000", input.readLine())
            assertEquals(7_100_000L, permitted.get())
            oldSeen.complete(Unit)
            assertEquals("+\\set_freq 14236000", input.readLine())
            assertEquals(14_236_000L, permitted.get())
            output.print("set_freq: 7100000\nRPRT 0\nset_freq: 14236000\nRPRT 0\n"); output.flush()
            assertEquals("+\\get_freq", input.readLine())
            output.print("get_freq:\nFrequency: 14236000\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            controller.authorizeFrequencyChange = { hz ->
                permitted.set(hz)
                AutoCloseable { permitted.set(null) }
            }
            try {
                controller.connect("127.0.0.1", rig.port)
                val old = async { controller.setFreq(7_100_000) }
                withTimeout(1000) { oldSeen.await() }
                old.cancelAndJoin()
                assertNull(permitted.get())
                assertTrue(controller.setFreq(14_236_000))
                assertNull(permitted.get())
                assertEquals(14_236_000L, controller.state.value.freqHz)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun repeatedTimeoutsReachTheNetworkRecoveryHook() = runBlocking {
        val recovery = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            repeat(3) {
                assertEquals("+\\set_ptt 0", input.readLine())
                output.print("set_ptt: 0\nRPRT -5\n"); output.flush()
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false, catHealth = RigCatHealth(failureWindowMs = 0))
            controller.onCatUnresponsive = { recovery.complete(Unit) }
            try {
                controller.connect("127.0.0.1", rig.port)
                repeat(3) { assertFalse(controller.setPtt(false)) }
                withTimeout(1000) { recovery.await() }
                assertTrue("Local TCP should still be alive when radio-level recovery fires", controller.isConnected)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun repeatedPttRejectionsRecoverAfterAudioTxEndsWithoutDependingOnPolling() = runBlocking {
        val recovery = CompletableDeferred<Unit>()
        val audioTx = AtomicBoolean(true)
        FakeRig { input, output ->
            repeat(3) {
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\nFrequency: 7105000\nRPRT 0\n"); output.flush()
                assertEquals("+\\get_ptt", input.readLine())
                output.print("get_ptt:\nRPRT -9\n"); output.flush()
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false, catHealth = RigCatHealth(failureWindowMs = 0))
            controller.txInProgress = { audioTx.get() }
            controller.onCatUnresponsive = { recovery.complete(Unit) }
            try {
                controller.connect("127.0.0.1", rig.port, verifyPttOff = true)
                repeat(3) {
                    assertEquals(7_105_000L, controller.getFreq())
                    assertNull(controller.readPtt())
                }
                delay(150)
                assertFalse("Recovery must not cut active audio TX", recovery.isCompleted)
                audioTx.set(false) // OFF is still unknown, but RX has resumed.
                withTimeout(1000) { recovery.await() }
                assertTrue(controller.isConnected)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun disconnectCancelsARecoveryDeferredDuringTx() = runBlocking {
        val recovered = AtomicBoolean(false)
        val audioTx = AtomicBoolean(true)
        FakeRig { input, output ->
            repeat(3) {
                assertEquals("+\\get_ptt", input.readLine())
                output.print("get_ptt:\nRPRT -9\n"); output.flush()
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false, catHealth = RigCatHealth(failureWindowMs = 0))
            controller.txInProgress = { audioTx.get() }
            controller.onCatUnresponsive = { recovered.set(true) }
            try {
                controller.connect("127.0.0.1", rig.port)
                repeat(3) { assertNull(controller.readPtt()) }
                delay(150)
                controller.disconnect()
                audioTx.set(false)
                delay(200)
                assertFalse("User disconnect must invalidate the queued recovery", recovered.get())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun replacementConnectionCannotInheritTheOldSessionsDeferredRecovery() = runBlocking {
        val recovered = AtomicBoolean(false)
        val audioTx = AtomicBoolean(true)
        FakeRig { input, output ->
            repeat(3) {
                assertEquals("+\\get_ptt", input.readLine())
                output.print("get_ptt:\nRPRT -9\n"); output.flush()
            }
        }.use { oldRig ->
            FakeRig { input, output ->
                assertEquals("+\\get_freq", input.readLine())
                output.print("get_freq:\nFrequency: 7105000\nRPRT 0\n"); output.flush()
            }.use { newRig ->
                val controller = RigController(pollingEnabled = false, catHealth = RigCatHealth(failureWindowMs = 0))
                controller.txInProgress = { audioTx.get() }
                controller.onCatUnresponsive = { recovered.set(true) }
                try {
                    controller.connect("127.0.0.1", oldRig.port)
                    repeat(3) { assertNull(controller.readPtt()) }
                    controller.connect("127.0.0.1", newRig.port)
                    audioTx.set(false)
                    assertEquals(7_105_000L, controller.getFreq())
                    delay(200)
                    assertFalse("The failed predecessor cannot recover the healthy replacement", recovered.get())
                    assertTrue(controller.isConnected)
                    oldRig.checkFinished()
                    newRig.checkFinished()
                } finally { controller.destroy() }
            }
        }
    }

    @Test fun lateCompletedRepliesStillReachHealthTracking() = runBlocking {
        val observed = CompletableDeferred<RigctldResponse>()
        FakeRig { input, output ->
            assertEquals("+\\get_freq", input.readLine())
            Thread.sleep(100)
            output.print("get_freq:\nFrequency: 7100000\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport(onResponse = { _, name, _, response ->
                assertEquals("get_freq", name)
                observed.complete(response)
            }).use { transport ->
                assertNull(transport.command("get_freq", timeoutMs = 20))
                assertEquals("7100000", withTimeout(1000) { observed.await() }.value("Frequency"))
                assertFalse(transport.hasPending)
                rig.checkFinished()
            }
        }
    }

    @Test fun lateMeterZeroCannotConfirmPttOff() = runBlocking {
        FakeRig { input, output ->
            assertEquals("+\\get_level STRENGTH", input.readLine())
            Thread.sleep(1200) // The production controller's meter deadline is 1 s.
            output.print("get_level: STRENGTH\r\n0\r\nRPRT 0\r\n"); output.flush()
            assertEquals("+\\get_ptt", input.readLine())
            output.print("get_ptt:\nPTT: 1\nRPRT 0\n"); output.flush()
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                controller.getSmeter()
                assertNull("An outstanding meter reply is not a PTT readback", controller.readPtt(false))
                val ptt = withTimeout(3000) {
                    var result: Boolean? = null
                    while (result == null) { delay(20); result = controller.readPtt(false) }
                    result
                }
                assertEquals(true, ptt)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun cancelledOnDoesNotBlockOffOrLoseReplyOrdering() = runBlocking {
        val onSeen = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 1", input.readLine())
            onSeen.complete(Unit)
            // OFF must reach the same TCP stream BEFORE the old ON reply arrives.
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 1\nRPRT -9\nset_ptt: 0\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport().use { transport ->
                val on = async { transport.command("set_ptt", "1", 2500, true) }
                withTimeout(1000) { onSeen.await() }
                val start = System.nanoTime()
                on.cancelAndJoin()
                assertTrue("Cancellation waited on socket I/O", (System.nanoTime() - start) / 1_000_000 < 500)
                assertEquals(0, transport.command("set_ptt", "0", 2000, true)?.result)
                rig.checkFinished()
            }
        }
    }

    @Test fun oldOffReplyCannotAcknowledgeOffAfterAnInterveningOn() = runBlocking {
        val onSeen = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 0", input.readLine())
            assertEquals("+\\set_ptt 1", input.readLine())
            onSeen.complete(Unit)
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 0\nRPRT 0\nset_ptt: 1\nRPRT 0\nset_ptt: 0\nRPRT -5\n")
            output.flush()
        }.use { rig ->
            rig.transport().use { transport ->
                assertNull(transport.command("set_ptt", "0", 50, true))
                val on = async { transport.command("set_ptt", "1", 2000, true) }
                withTimeout(1000) { onSeen.await() }
                assertEquals(-5, transport.command("set_ptt", "0", 2000, true)?.result)
                assertEquals(0, on.await()?.result)
                rig.checkFinished()
            }
        }
    }

    @Test fun timedOutTransactionSuppressesPollsAndCoalescesRepeatedOff() = runBlocking {
        val releaseReply = CountDownLatch(1)
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 0", input.readLine())
            assertTrue(releaseReply.await(2, TimeUnit.SECONDS))
            output.print("set_ptt: 0\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport().use { transport ->
                assertNull(transport.command("set_ptt", "0", 50, true))
                assertNull(transport.command("get_freq", timeoutMs = 50))
                val retry = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.command("set_ptt", "0", 2000, true)
                }
                releaseReply.countDown()
                assertEquals(0, retry.await()?.result)
                rig.checkFinished()
            }
        }
    }

    @Test fun fragmentedMultilineReplyIsDrainedAfterCallerTimeout() = runBlocking {
        val releaseMode = CountDownLatch(1)
        FakeRig { input, output ->
            assertEquals("+\\get_mode", input.readLine())
            output.print("get_mo"); output.flush()
            Thread.sleep(300) // Cross the reader's 250 ms socket wakeup mid-line.
            assertTrue(releaseMode.await(2, TimeUnit.SECONDS))
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("de:\nMode: USB\nPassband: 2400\nRPRT 0\nset_ptt: 0\nRPRT 0\n")
            output.flush()
        }.use { rig ->
            rig.transport().use { transport ->
                assertNull(transport.command("get_mode", timeoutMs = 50))
                val off = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.command("set_ptt", "0", 2000, true)
                }
                releaseMode.countDown()
                assertEquals(0, off.await()?.result)
                rig.checkFinished()
            }
        }
    }

    @Test fun wrongHeaderFailsClosedInsteadOfConfirmingPtt() = runBlocking {
        val failed = CompletableDeferred<Exception>()
        FakeRig { input, output ->
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("set_ptt: 1\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport { _, error -> failed.complete(error) }.use { transport ->
                assertNull(transport.command("set_ptt", "0", 1000, true))
                assertTrue(withTimeout(1000) { failed.await() }.message!!.contains("Expected"))
                rig.checkFinished()
            }
        }
    }

    @Test fun longIdleDoesNotSpendTheNextCommandsDeadline() = runBlocking {
        val failed = CompletableDeferred<Exception>()
        FakeRig { input, output ->
            assertEquals("+\\get_ptt", input.readLine())
            Thread.sleep(100)
            output.print("get_ptt:\nPTT: 0\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport(stalledReplyMs = 350, onFailure = { _, e -> failed.complete(e) }).use { transport ->
                delay(600) // Reader has been idle longer than the command deadline.
                assertEquals("0", transport.command("get_ptt", timeoutMs = 1000)?.value("PTT"))
                assertFalse(failed.isCompleted)
                rig.checkFinished()
            }
        }
    }

    @Test fun permanentlyStalledReplyFailsSessionAndCompletesWaiters() = runBlocking {
        val failed = CompletableDeferred<Exception>()
        FakeRig { input, _ ->
            assertEquals("+\\get_ptt", input.readLine())
            assertNull(input.readLine()) // Client closes the stalled session.
        }.use { rig ->
            rig.transport(stalledReplyMs = 200, onFailure = { _, e -> failed.complete(e) }).use { transport ->
                assertNull(transport.command("get_ptt", timeoutMs = 1000))
                assertTrue(withTimeout(1000) { failed.await() }.message!!.contains("stalled"))
                rig.checkFinished()
            }
        }
    }

    @Test fun saturatedUserQueueStillHasRoomForOff() = runBlocking {
        FakeRig { input, output ->
            repeat(7) { assertEquals("+\\set_freq ${7_000_000 + it}", input.readLine()) }
            assertEquals("+\\set_ptt 0", input.readLine())
            repeat(7) { output.print("set_freq: ${7_000_000 + it}\nRPRT 0\n") }
            output.print("set_ptt: 0\nRPRT 0\n"); output.flush()
        }.use { rig ->
            rig.transport().use { transport ->
                repeat(7) { assertNull(transport.command("set_freq", "${7_000_000 + it}", 10, true)) }
                assertNull(transport.command("set_freq", "8000000", 10, true))
                assertEquals(0, transport.command("set_ptt", "0", 2000, true)?.result)
                rig.checkFinished()
            }
        }
    }

    /* ── Key-on confirmed by the rig's own status (RigController.confirmKeyed) ──
     * Every script ends on a command the test itself sends, so anything the
     * controller wrote unasked would be read in its place and fail the script. */

    @Test fun acknowledgedKeyOnIsRepeatedWhileTheRigReportsReceive() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true) // Acknowledged, yet the rig did not key.
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = true)
            input.expectKey(output, on = false)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertEquals(true, controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.state.value.ptt)
                assertTrue(controller.setPtt(false))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun rigKnownToReportTransmitThatStaysInReceiveIsReportedAndLeftAlone() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = true) // This rig's status does show transmit.
            input.expectKey(output, on = false)
            input.expectKey(output, on = true)
            repeat(2) {
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = true)
            }
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false) // Unkeyed by the caller, not by the check.
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertEquals(true, controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.setPtt(false))
                assertTrue(controller.setPtt(true))
                assertEquals(false, controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.setPtt(false))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    /** uSDX firmware answers IF; with a constant 0 where the TS-480 format has
     *  the transmit flag, so Hamlib reports receive throughout an over. */
    @Test fun statusThatNeverShowsTransmitIsNotHeldAgainstTheRig() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            repeat(2) {
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = true)
            }
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false)
            // Later overs: the status is still read, but no longer acted on.
            repeat(2) {
                input.expectKey(output, on = true)
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = false)
            }
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                repeat(3) {
                    assertTrue(controller.setPtt(true))
                    assertNull("No verdict on a rig never seen transmitting", controller.confirmKeyed(settleMs = 10))
                    assertTrue(controller.setPtt(false))
                }
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun statusIsActedOnAgainOnceTheRigHasReportedTransmit() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true) // First over stays in receive: status in doubt.
            repeat(2) {
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = true)
            }
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false)
            input.expectKey(output, on = true) // Second over keys: the status can be believed.
            input.expectStatus(output, transmitting = true)
            input.expectKey(output, on = false)
            input.expectKey(output, on = true) // Third over: a missed key-on is repeated again.
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = true)
            input.expectKey(output, on = false)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                for (expected in listOf(null, true, true)) {
                    assertTrue(controller.setPtt(true))
                    assertEquals(expected, controller.confirmKeyed(settleMs = 10))
                    assertTrue(controller.setPtt(false))
                }
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun unreadableStatusAfterKeyOnIsNotActedOn() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            assertEquals("+\\get_ptt", input.readLine())
            output.print("get_ptt:\nRPRT -11\n"); output.flush()
            input.expectKey(output, on = false)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertNull(controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.setPtt(false))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun keyOnIsNotRepeatedOnceAReleaseHasBegun() = runBlocking {
        val statusAsked = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            assertEquals("+\\get_ptt", input.readLine())
            statusAsked.complete(Unit)
            // The release is written behind the status request still in flight.
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("get_ptt:\nPTT: 0\nRPRT 0\nset_ptt: 0\nRPRT 0\n"); output.flush()
            input.expectFrequency(output)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                val check = async { controller.confirmKeyed(settleMs = 10) }
                withTimeout(2000) { statusAsked.await() }
                assertTrue(controller.setPtt(false))
                assertNull("Receive is what a released rig should report", check.await())
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    /** The rig was still transmitting when it was asked, but by the time the
     *  answer arrives the release is the newest command: it says nothing about
     *  a key-on that no longer stands. */
    @Test fun statusReadBeforeAReleaseIsDiscardedOnceTheReleaseHasBegun() = runBlocking {
        val statusAsked = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            assertEquals("+\\get_ptt", input.readLine())
            statusAsked.complete(Unit)
            assertEquals("+\\set_ptt 0", input.readLine())
            output.print("get_ptt:\nPTT: 1\nRPRT 0\nset_ptt: 0\nRPRT 0\n"); output.flush()
            input.expectFrequency(output)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                val check = async { controller.confirmKeyed(settleMs = 10) }
                withTimeout(2000) { statusAsked.await() }
                assertTrue(controller.setPtt(false))
                assertNull(check.await())
                assertFalse("A stale transmit reading must not undo the release", controller.state.value.ptt)
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun keyOnCheckSendsNothingAfterAReleaseOrWhenCancelled() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectKey(output, on = false)
            input.expectKey(output, on = true)
            input.expectFrequency(output)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertTrue(controller.setPtt(false))
                assertNull(controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.setPtt(true))
                val check = async { controller.confirmKeyed(settleMs = 10_000) }
                delay(100)
                check.cancelAndJoin()
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    /** The status check passes, and the release begins only then: the repeat's
     *  own claim, made as it is written, is all that is left to stop it. */
    @Test fun releaseJustBeforeTheRepeatIsWrittenStillPreventsIt() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false)
            input.expectFrequency(output)
        }.use { rig ->
            lateinit var controller: RigController
            controller = RigController(pollingEnabled = false,
                beforeRepeatedKeyOn = { assertTrue(controller.setPtt(false)) })
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertNull(controller.confirmKeyed(settleMs = 10))
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun cancellingTheCheckWhileItsStatusRequestIsInFlightSendsNothingMore() = runBlocking {
        val statusAsked = CompletableDeferred<Unit>()
        val answerStatus = CountDownLatch(1)
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            assertEquals("+\\get_ptt", input.readLine())
            statusAsked.complete(Unit)
            assertTrue(answerStatus.await(2, TimeUnit.SECONDS))
            output.print("get_ptt:\nPTT: 0\nRPRT 0\n"); output.flush()
            input.expectFrequency(output)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                val check = async { controller.confirmKeyed(settleMs = 10) }
                withTimeout(2000) { statusAsked.await() }
                check.cancelAndJoin()
                answerStatus.countDown()
                val frequency = withTimeout(2000) {
                    var read: Long? = null
                    while (read == null) { delay(20); read = controller.readFrequency() }
                    read
                }
                assertEquals(7_100_000L, frequency)
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun keyOnCheckWithoutAKeyOnSendsNothing() = runBlocking {
        FakeRig { input, output -> input.expectFrequency(output) }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertNull(controller.confirmKeyed(settleMs = 10))
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun oneKeyOnIsCheckedAndRepeatedByOneCallOnly() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = true)
            input.expectKey(output, on = false)
            input.expectKey(output, on = true)
            repeat(2) {
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = true)
            }
            input.expectStatus(output, transmitting = false)
            input.expectFrequency(output)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                assertEquals(true, controller.confirmKeyed(settleMs = 10))
                assertNull(controller.confirmKeyed(settleMs = 10))
                assertTrue(controller.setPtt(false))
                assertTrue(controller.setPtt(true))
                assertEquals(false, controller.confirmKeyed(settleMs = 10))
                assertNull("Its two repeats are spent", controller.confirmKeyed(settleMs = 10))
                assertEquals(7_100_000L, controller.readFrequency())
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun reconnectingToTheSameRigctldKeepsWhatWasLearnedAboutItsRig() = runBlocking {
        val repeatedOver = { input: BufferedReader, output: PrintWriter ->
            input.expectKey(output, on = true)
            repeat(2) {
                input.expectStatus(output, transmitting = false)
                input.expectKey(output, on = true)
            }
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false)
        }
        val readOnlyOver = { input: BufferedReader, output: PrintWriter ->
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = false)
            input.expectKey(output, on = false)
        }
        suspend fun over(controller: RigController) {
            assertTrue(controller.setPtt(true))
            assertNull(controller.confirmKeyed(settleMs = 10))
            assertTrue(controller.setPtt(false))
        }
        FakeRig(repeatedOver).use { first -> FakeRig(readOnlyOver).use { same -> FakeRig(repeatedOver).use { other ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", first.port)
                over(controller) // Its status never shows transmit.
                controller.connect("127.0.0.1", same.port, verifyPttOff = false, sameRig = true)
                over(controller)
                controller.connect("127.0.0.1", other.port) // Possibly another rig.
                over(controller)
                first.checkFinished()
                same.checkFinished()
                other.checkFinished()
            } finally { controller.destroy() }
        } } }
    }

    @Test fun unansweredRepeatEndsTheCheckWithoutAVerdict() = runBlocking {
        val answerRepeat = CountDownLatch(1)
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectStatus(output, transmitting = false)
            assertEquals("+\\set_ptt 1", input.readLine())
            assertTrue(answerRepeat.await(6, TimeUnit.SECONDS))
            output.print("set_ptt: 1\nRPRT 0\n"); output.flush()
            input.expectKey(output, on = false)
        }.use { rig ->
            val controller = RigController(pollingEnabled = false)
            try {
                controller.connect("127.0.0.1", rig.port)
                assertTrue(controller.setPtt(true))
                // Waits out the production PTT deadline (2.5 s) on the repeat.
                assertNull(controller.confirmKeyed(settleMs = 10))
                answerRepeat.countDown()
                assertTrue(controller.setPtt(false))
                rig.checkFinished()
            } finally { controller.destroy() }
        }
    }

    @Test fun guardDecidesWhetherACommandIsWritten() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = false) // The refused key-on never arrives.
            input.expectKey(output, on = true)
        }.use { rig ->
            rig.transport().use { transport ->
                assertNull(transport.command("set_ptt", "1", 2000, true) { false })
                assertFalse(transport.hasPending)
                assertEquals(0, transport.command("set_ptt", "0", 2000, true)?.result)
                assertEquals(0, transport.command("set_ptt", "1", 2000, true) { true }?.result)
                rig.checkFinished()
            }
        }
    }

    /** A release asked for while the guard is deciding cannot be written until
     *  the guarded command has been: the claim and the write are one step. */
    @Test fun guardRunsWhileNoOtherCommandCanBeWritten() = runBlocking {
        FakeRig { input, output ->
            input.expectKey(output, on = true)
            input.expectKey(output, on = false)
        }.use { rig ->
            rig.transport().use { transport ->
                var off: Deferred<RigctldResponse?>? = null
                val on = transport.command("set_ptt", "1", 2000, true) {
                    off = async(Dispatchers.IO) { transport.command("set_ptt", "0", 2000, true) }
                    Thread.sleep(200) // Time for the OFF to overtake, if it could.
                    true
                }
                assertEquals(0, on?.result)
                assertEquals(0, off!!.await()?.result)
                rig.checkFinished()
            }
        }
    }

    @Test fun guardIsNotAskedAboutACommandTheQueueCannotTake() = runBlocking {
        val refused = CountDownLatch(1)
        FakeRig { input, output ->
            repeat(7) { assertEquals("+\\set_freq ${7_000_000 + it}", input.readLine()) }
            assertTrue(refused.await(2, TimeUnit.SECONDS))
            repeat(7) { output.print("set_freq: ${7_000_000 + it}\nRPRT 0\n") }
            output.flush()
            input.expectFrequency(output)
        }.use { rig ->
            rig.transport().use { transport ->
                repeat(7) { assertNull(transport.command("set_freq", "${7_000_000 + it}", 10, true)) }
                var asked = false
                assertNull(transport.command("set_ptt", "1", 10, true) { asked = true; true })
                assertFalse("A guard may claim state only for a command that is then sent", asked)
                refused.countDown()
                withTimeout(2000) { while (transport.hasPending) delay(10) }
                assertEquals(0, transport.command("get_freq", timeoutMs = 2000)?.result)
                rig.checkFinished()
            }
        }
    }

    /** Each failed S-meter read makes rigctld hold up the commands behind it
     *  (see MeterPollBackoff), so the poller must not repeat it every cycle. */
    @Test fun pollerStopsAskingForAnSMeterTheRigFailsToGive() = runBlocking {
        val meterReads = AtomicInteger()
        val frequencyReads = AtomicInteger()
        FakeRig { input, output ->
            while (true) {
                val line = input.readLine() ?: break // The controller hung up.
                when (line) {
                    "+\\get_level STRENGTH" -> {
                        meterReads.incrementAndGet()
                        output.print("get_level: STRENGTH\nRPRT -8\n")
                    }
                    "+\\get_freq" -> {
                        frequencyReads.incrementAndGet()
                        output.print("get_freq:\nFrequency: 7100000\nRPRT 0\n")
                    }
                    "+\\get_ptt" -> output.print("get_ptt:\nPTT: 0\nRPRT 0\n")
                    "+\\get_mode" -> output.print("get_mode:\nMode: USB\nPassband: 2400\nRPRT 0\n")
                    else -> fail("The poller sent $line")
                }
                output.flush()
            }
        }.use { rig ->
            val controller = RigController() // Polling on.
            try {
                controller.connect("127.0.0.1", rig.port)
                // Four poll cycles: the meter would be due in each of the first three.
                withTimeout(15_000) { while (frequencyReads.get() < 4) delay(50) }
                assertEquals(1, meterReads.get())
            } finally { controller.destroy() }
            rig.checkFinished()
        }
    }

    // rigctld's side of an exchange, for FakeRig scripts.
    private fun BufferedReader.expectKey(output: PrintWriter, on: Boolean) {
        val value = if (on) 1 else 0
        assertEquals("+\\set_ptt $value", readLine())
        output.print("set_ptt: $value\nRPRT 0\n"); output.flush()
    }

    private fun BufferedReader.expectStatus(output: PrintWriter, transmitting: Boolean) {
        assertEquals("+\\get_ptt", readLine())
        output.print("get_ptt:\nPTT: ${if (transmitting) 1 else 0}\nRPRT 0\n"); output.flush()
    }

    private fun BufferedReader.expectFrequency(output: PrintWriter) {
        assertEquals("+\\get_freq", readLine())
        output.print("get_freq:\nFrequency: 7100000\nRPRT 0\n"); output.flush()
    }

    private class FakeRig(script: (BufferedReader, PrintWriter) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val failure = AtomicReference<Throwable?>()
        private val done = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val worker = thread(name = "fake-rigctld") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    script(socket.getInputStream().bufferedReader(), PrintWriter(socket.getOutputStream(), true))
                    done.countDown()
                    release.await(4, TimeUnit.SECONDS) // Keep healthy sessions open for assertions.
                }
            } catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }

        fun transport(
            stalledReplyMs: Int = 15_000,
            onResponse: (RigctldTransport, String, String, RigctldResponse) -> Unit = { _, _, _, _ -> },
            onFailure: (RigctldTransport, Exception) -> Unit = { _, _ -> }
        ): RigctldTransport =
            RigctldTransport(Socket("127.0.0.1", port), onFailure, stalledReplyMs, onResponse).also { it.start() }

        fun checkFinished() {
            assertTrue("Fake daemon did not complete", done.await(4, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Fake daemon failed", it) }
        }

        override fun close() {
            release.countDown()
            server.close()
            worker.join(4000)
        }
    }
}

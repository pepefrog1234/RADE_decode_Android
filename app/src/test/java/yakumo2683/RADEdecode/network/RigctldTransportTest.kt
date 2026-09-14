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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RigctldTransportTest {
    @Test fun repeatedProtocolErrorsReachTheNetworkRecoveryHook() = runBlocking {
        val recovery = CompletableDeferred<Unit>()
        FakeRig { input, output ->
            repeat(3) {
                assertEquals("+\\set_ptt 0", input.readLine())
                output.print("set_ptt: 0\nRPRT -8\n"); output.flush()
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

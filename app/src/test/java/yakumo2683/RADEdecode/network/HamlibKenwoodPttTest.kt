package yakumo2683.RADEdecode.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Optional host integration: RADE_TEST_RIGCTLD=/path/to/rigctld of Hamlib 4.5.5,
 * the version bundled in the APK (as for [HamlibIcomTuningTest]).
 *
 * Runs the app's own [RigController] against the real daemon and a QMX-style
 * radio (Kenwood TS-480 CAT, model 2052) behind a real PTY.
 */
class HamlibKenwoodPttTest {
    @Test fun keyOnThatReachesTheRadioIsConfirmedWithoutARepeat() = exercise { radio, controller ->
        assertTrue(controller.setPtt(true))
        assertTrue(radio.transmitting)
        assertEquals(true, controller.confirmKeyed())
        assertEquals(1, radio.keyOns.get())
        assertTrue(controller.setPtt(false))
        assertFalse(radio.transmitting)
    }

    /** Hamlib sends "TX;" and then "ID;", and is satisfied by the answer to ID. */
    @Test fun keyOnLostOnItsWayIsAcknowledgedByHamlibAndRepeatedByTheCheck() = exercise { radio, controller ->
        radio.keyOnsToLose = 1
        assertTrue("Hamlib acknowledged a key-on the radio never received", controller.setPtt(true))
        assertFalse(radio.transmitting)
        assertEquals(true, controller.confirmKeyed())
        assertTrue(radio.transmitting)
        assertEquals(2, radio.keyOns.get())
        assertTrue(controller.setPtt(false))
        assertFalse(radio.transmitting)
    }

    /** The radio's "?;" is read, and the ID answer after it still counts as success. */
    @Test fun keyOnRefusedByTheRadioIsAcknowledgedByHamlibAndRepeatedByTheCheck() = exercise { radio, controller ->
        radio.keyOnsToRefuse = 1
        assertTrue("Hamlib acknowledged a key-on the radio refused", controller.setPtt(true))
        assertFalse(radio.transmitting)
        assertEquals(true, controller.confirmKeyed())
        assertTrue(radio.transmitting)
        assertTrue(controller.setPtt(false))
    }

    /**
     * An S-meter answer Hamlib cannot parse makes rigctld close and reopen the
     * rig, about a second during which a key-on just waits. Polling it on every
     * cycle kept doing that; the poller has to leave the meter alone instead.
     */
    @Test fun keyOnIsNotHeldUpByAnSMeterAnswerHamlibRejects() =
        exercise(sMeterAnswers = listOf("SM-86;"), polling = true) { radio, controller ->
            assertKeyOnsArePrompt(radio, controller)
        }

    /** "SM" and four characters is what Hamlib takes; a signed value in dB has
     *  that length for some readings only, so every other poll fails here. */
    @Test fun keyOnIsNotHeldUpByAnSMeterAnswerHamlibRejectsNowAndThen() =
        exercise(sMeterAnswers = listOf("SM-86;", "SM-103;"), polling = true) { radio, controller ->
            assertKeyOnsArePrompt(radio, controller)
        }

    private suspend fun assertKeyOnsArePrompt(radio: Radio, controller: RigController) {
        // Give the poller time to meet the failing meter and leave it alone.
        var reads = -1
        var quietSince = System.nanoTime()
        val giveUp = System.nanoTime() + 25_000_000_000L
        while (System.nanoTime() < giveUp && System.nanoTime() - quietSince < 4_000_000_000L) {
            delay(100)
            val now = radio.sMeterReads.get()
            if (now != reads) { reads = now; quietSince = System.nanoTime() }
        }
        assertTrue("The poller never asked for the S-meter", reads > 0)
        val waits = (1..8).map { press ->
            delay(250L + 170L * press) // Spread the presses over the poll cycle.
            val pressed = System.nanoTime()
            assertTrue(controller.setPtt(true))
            val waitedMs = (radio.keyedAtNs - pressed) / 1_000_000
            assertTrue(controller.setPtt(false))
            waitedMs
        }
        println("Key-on reached the radio after $waits ms")
        assertTrue("Key-on reached the radio after $waits ms", waits.max() < 400)
    }

    private fun exercise(
        sMeterAnswers: List<String> = listOf("SM0005;"),
        polling: Boolean = false,
        test: suspend (Radio, RigController) -> Unit
    ) {
        val binary = System.getenv("RADE_TEST_RIGCTLD")
        assumeTrue("Set RADE_TEST_RIGCTLD to run the real Hamlib integration", binary != null)
        check(File(binary!!).canExecute())
        Radio(sMeterAnswers).use { radio ->
            val port = ServerSocket(0).use { it.localPort }
            val log = File.createTempFile("rade-hamlib-kenwood-", ".log")
            val process = ProcessBuilder(binary, "-m", "2052", "-r", radio.path, "-s", "115200", "-t", "$port", "-vvvv")
                .redirectErrorStream(true).redirectOutput(log).start()
            val controller = if (polling) RigController() else RigController(pollingEnabled = false)
            var passed = false
            try {
                runBlocking {
                    val deadline = System.nanoTime() + 15_000_000_000L
                    while (!controller.isConnected && System.nanoTime() < deadline && process.isAlive) {
                        controller.connect("127.0.0.1", port)
                        if (!controller.isConnected) delay(50)
                    }
                    assertTrue("Hamlib startup failed", controller.isConnected)
                    test(radio, controller)
                }
                radio.failure.get()?.let { throw AssertionError(it) }
                passed = true
            } catch (t: Throwable) {
                throw AssertionError("Hamlib log: $log\n${log.readText().takeLast(12000)}\nRadio: ${radio.failure.get()}", t)
            } finally {
                controller.destroy()
                process.destroyForcibly().waitFor()
                if (passed) log.delete()
            }
        }
    }

    /** CAT as the QMX manual (firmware 1_04_003) describes it: "TX;" and "RX;"
     *  are not answered, "IF;" carries the transmit flag, errors are "?;". */
    private class Radio(private val sMeterAnswers: List<String>) : AutoCloseable {
        // A real PTY, as on Android; see HamlibIcomTuningTest.
        private val bridge = ProcessBuilder("python3", "-u", "-c", """
            import os, pty, sys, threading, tty
            master, slave = pty.openpty()
            tty.setraw(slave)
            print(os.ttyname(slave), flush=True)
            def reply():
                while True:
                    data = os.read(0, 4096)
                    if not data: return
                    os.write(master, data)
            threading.Thread(target=reply, daemon=True).start()
            while True:
                os.write(1, os.read(master, 4096))
        """.trimIndent()).start()
        private val input = bridge.inputStream.buffered()
        val path = buildString { while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; append(b.toChar()) } }
        @Volatile var transmitting = false
        @Volatile var keyOnsToLose = 0
        @Volatile var keyOnsToRefuse = 0
        @Volatile var keyedAtNs = 0L
        val keyOns = AtomicInteger()
        val sMeterReads = AtomicInteger()
        private var sMeterPolls = 0
        private var lastSMeterReadNs = 0L
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "hamlib-kenwood-radio") {
            try {
                bridge.outputStream.use { output ->
                    val command = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        if (b != ';'.code) { command.append(b.toChar()); continue }
                        val reply = answer(command.toString())
                        command.setLength(0)
                        if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                    }
                }
            } catch (_: java.io.IOException) { /* teardown */ }
            catch (t: Throwable) { failure.set(t) }
        }

        private fun answer(command: String): String = when (command) {
            "TX", "TQ1" -> {
                keyOns.incrementAndGet()
                when {
                    keyOnsToLose > 0 -> { keyOnsToLose--; "" }
                    keyOnsToRefuse > 0 -> { keyOnsToRefuse--; "?;" }
                    else -> { transmitting = true; keyedAtNs = System.nanoTime(); "" }
                }
            }
            "RX", "TQ0" -> { transmitting = false; "" }
            "ID" -> "ID020;"
            // Frequency, 5 spaces, RIT, RIT/XIT/bank, channel, transmit flag,
            // mode, VFO/scan/split/tone, tone number, space.
            "IF" -> "IF00007074000     +000000000${if (transmitting) 1 else 0}2000000 ;"
            "FA" -> "FA00007074000;"
            "FB" -> "FB00007074000;"
            "MD" -> "MD2;"
            "PS" -> "PS1;"
            "AI" -> "AI0;"
            "SM" -> {
                sMeterReads.incrementAndGet()
                // The reading stays the same through Hamlib's own retries of
                // one poll, which follow each other within 50 ms.
                val now = System.nanoTime()
                if (now - lastSMeterReadNs > 400_000_000L) sMeterPolls++
                lastSMeterReadNs = now
                sMeterAnswers[(sMeterPolls - 1) % sMeterAnswers.size]
            }
            "AI0" -> ""
            else -> "?;"
        }

        override fun close() { bridge.destroyForcibly().waitFor(); worker.join(1000) }
    }
}

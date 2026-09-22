package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Optional host integration: RADE_TEST_RIGCTLD=/path/to/Hamlib-4.5.5/tests/rigctld. */
class HamlibIcomTuningTest {
    @Test fun selectedBWithEcho() = exercise(echo = true, equal = false)
    @Test fun selectedBWithoutEcho() = exercise(echo = false, equal = false)
    @Test fun equalFrequenciesWithEcho() = exercise(echo = true, equal = true)
    @Test fun equalFrequenciesWithoutEcho() = exercise(echo = false, equal = true)

    private fun exercise(echo: Boolean, equal: Boolean) {
        val binary = System.getenv("RADE_TEST_RIGCTLD")
        assumeTrue("Set RADE_TEST_RIGCTLD to run the real Hamlib integration", binary != null)
        check(File(binary!!).canExecute())
        Radio(echo, equal).use { radio ->
            val port = ServerSocket(0).use { it.localPort }
            val log = File.createTempFile("rade-hamlib-tuning-", ".log")
            val process = ProcessBuilder(binary, "-m", "3085", "-r", radio.path,
                "-t", "$port", "-vvvv", "--set-conf=poll_interval=0,cache_timeout=0")
                .redirectErrorStream(true).redirectOutput(log).start()
            var passed = false
            try {
                val deadline = System.nanoTime() + 15_000_000_000L
                var connection: Socket? = null
                while (connection == null && System.nanoTime() < deadline && process.isAlive) {
                    try { connection = Socket("127.0.0.1", port) }
                    catch (_: java.io.IOException) { Thread.sleep(50) }
                }
                assertNotNull("Hamlib startup failed: ${log.readText()}", connection)
                connection!!.use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    fun command(text: String): String {
                        writer.write("$text\n"); writer.flush()
                        return checkNotNull(reader.readLine()) { log.readText() }
                    }
                    assertEquals("7177000", command("f"))
                    assertTrue("Expected actual startup VFO probes", radio.blocked.isNotEmpty())
                    assertEquals(1, radio.selected)
                    assertEquals(7_177_000L, radio.frequencies[1])
                    val token = synchronized(radio.guard) { radio.guard.begin(7_142_000) }
                    try {
                        assertEquals("RPRT 0", command("F 7142000"))
                        assertEquals("7142000", command("f"))
                    } finally { synchronized(radio.guard) { radio.guard.end(token) } }
                    repeat(3) {
                        assertEquals("RPRT 0", command("T 1"))
                        assertEquals("1", command("t"))
                        assertEquals("RPRT 0", command("T 0"))
                        assertEquals("0", command("t"))
                        assertEquals("7142000", command("f"))
                    }
                    assertEquals("Unrequested frequency write was not rejected", "RPRT -9", command("F 7065000"))
                    assertEquals("7142000", command("f"))
                    assertEquals(1, radio.selected)
                    assertEquals(if (equal) 7_177_000L else 7_065_000L, radio.frequencies[0])
                    assertEquals(7_142_000L, radio.frequencies[1])
                }
                radio.failure.get()?.let { throw AssertionError(it) }
                passed = true
            } catch (t: Throwable) {
                throw AssertionError("Hamlib log: $log\n${log.readText().takeLast(12000)}\nRadio: ${radio.failure.get()}", t)
            } finally {
                process.destroyForcibly().waitFor()
                if (passed) log.delete()
            }
        }
    }

    private class Radio(val echo: Boolean, equal: Boolean) : AutoCloseable {
        // A real PTY matches Android's transport. Hamlib's Icom host:port mode
        // switches to raw UDP, so a TCP serial fake would exercise a different path.
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
        val guard = IcomTuningGuard()
        val frequencies = longArrayOf(if (equal) 7_177_000 else 7_065_000, 7_177_000)
        @Volatile var selected = 1
        private var ptt = 0
        val blocked = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "hamlib-civ-radio") {
            try {
                bridge.outputStream.use { output ->
                    val buffer = ArrayList<Byte>()
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        buffer.add(b.toByte())
                        if (b != 0xfd) continue
                        val frame = buffer.toByteArray(); buffer.clear()
                        val response = synchronized(guard) {
                            if (!guard.allows(frame)) {
                                blocked.add(frame.joinToString("") { "%02X".format(it) })
                                IcomTuningGuard.acknowledgement(frame, echo)
                            } else {
                                val body = frame.copyOfRange(4, frame.size - 1)
                                val reply = byteArrayOf(-2, -2, frame[3], frame[2]) + answer(body) + byteArrayOf(-3)
                                if (echo) frame + reply else reply
                            }
                        }
                        output.write(response); output.flush()
                    }
                }
            } catch (_: SocketException) { /* teardown */ }
            catch (t: Throwable) { failure.set(t) }
        }

        private fun answer(b: ByteArray): ByteArray {
            val ack = byteArrayOf(0xfb.toByte())
            return when (b[0].toInt() and 255) {
                0x03 -> byteArrayOf(3) + bcd(frequencies[selected])
                0x25 -> {
                    val target = if (b[1].toInt() == 0) selected else 1 - selected
                    if (b.size == 2) b + bcd(frequencies[target])
                    else { frequencies[target] = decode(b.copyOfRange(2, 7)); ack }
                }
                0x05 -> { frequencies[selected] = decode(b.copyOfRange(1, 6)); ack }
                0x04 -> byteArrayOf(4, 1, 1)
                0x26 -> byteArrayOf(0x26, b[1], 1, 1, 1)
                0x0f -> byteArrayOf(0x0f, 0)
                0x1c -> if (b.size == 2) byteArrayOf(0x1c, 0, ptt.toByte())
                    else { ptt = b[2].toInt(); ack }
                0x19 -> byteArrayOf(0x19, 0, 0xa4.toByte())
                else -> byteArrayOf(0xfa.toByte())
            }
        }

        override fun close() { bridge.destroyForcibly().waitFor(); worker.join(1000) }

        private fun bcd(hz: Long): ByteArray {
            var rest = hz
            return ByteArray(5) { val n = (rest % 100).toInt(); rest /= 100; ((n / 10 shl 4) or (n % 10)).toByte() }
        }
        private fun decode(b: ByteArray): Long {
            var value = 0L
            b.reversed().forEach { value = value * 100 + ((it.toInt() and 255) ushr 4) * 10 + (it.toInt() and 15) }
            return value
        }
    }
}

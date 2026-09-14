package yakumo2683.RADEdecode.network

import kotlinx.coroutines.*
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque

internal data class RigctldResponse(val lines: List<String>, val result: Int) {
    fun value(label: String): String? = if (result == 0) {
        lines.firstOrNull { it.startsWith("$label:") }?.substringAfter(':')?.trim()
    } else null

    // Hamlib get_level uses an unlabelled value even in extended response mode.
    fun level(): String? = if (result == 0) {
        value("Level Value") ?: lines.singleOrNull()?.trim()
    } else null
}

/**
 * One TCP session, with a single reader and ordered ERP transactions. A caller
 * timeout/cancellation stops only its wait: the reader still consumes that
 * transaction through RPRT before delivering the next reply. In particular an
 * old numeric meter value can never acknowledge PTT, nor can a late T 0 reply
 * acknowledge a later T 0 across an intervening T 1.
 *
 * OFF can be written behind an in-flight ON/readback on the SAME socket. This
 * preserves radio command ordering without waiting for a cancelled coroutine's
 * blocking read, or moving OFF to another connection ahead of a queued ON.
 */
internal class RigctldTransport(
    private val socket: Socket,
    private val onFailure: (RigctldTransport, Exception) -> Unit,
    private val stalledReplyMs: Int = 15_000,
    private val onResponse: (RigctldTransport, String, String, RigctldResponse) -> Unit = { _, _, _, _ -> }
) : Closeable {
    private class Pending(val name: String, val args: String) {
        val sentNs = System.nanoTime()
        val reply = CompletableDeferred<RigctldResponse?>()
        val header = "$name:${if (args.isEmpty()) "" else " $args"}"
    }

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val input = socket.getInputStream().buffered()
    private val writer = socket.getOutputStream().bufferedWriter()
    @Volatile private var closed = false
    val hasPending: Boolean get() = synchronized(lock) { pending.isNotEmpty() }

    fun start() {
        socket.soTimeout = minOf(250, stalledReplyMs)
        scope.launch {
            try {
                while (isActive && !closed) {
                    val header = readProtocolLine() ?: throw EOFException("rigctld closed connection")
                    val request = synchronized(lock) { pending.peekFirst() }
                        ?: throw IOException("Unsolicited rigctld reply: $header")
                    if (header.trim() != request.header) {
                        throw IOException("Expected '${request.header}', received '$header'")
                    }
                    val lines = mutableListOf<String>()
                    var size = 0
                    var result: Int
                    while (true) {
                        val line = readProtocolLine() ?: throw EOFException("Incomplete rigctld reply")
                        if (line.startsWith("RPRT ")) {
                            result = line.substringAfter(' ').trim().toIntOrNull()
                                ?: throw IOException("Invalid rigctld result: $line")
                            break
                        }
                        size += line.length
                        if (lines.size >= 64 || size > 16_384) throw IOException("Oversized rigctld reply")
                        lines.add(line)
                    }
                    val response = RigctldResponse(lines, result)
                    synchronized(lock) {
                        if (!closed) {
                            check(pending.removeFirst() === request)
                            request.reply.complete(response)
                        }
                    }
                    // Outside the transport lock: connection teardown takes
                    // the controller lock before closing its transport.
                    if (!closed) onResponse(this@RigctldTransport, request.name, request.args, response)
                }
            } catch (e: Exception) {
                if (!closed) {
                    close()
                    onFailure(this@RigctldTransport, e)
                }
            }
        }
    }

    /** Keep partial lines across socket wakeups. BufferedReader.readLine loses
     * its partial line on SocketTimeoutException. The hard deadline starts at
     * the oldest command's write, not at an idle socket read before it. */
    private fun readProtocolLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (!closed) {
            val oldest = synchronized(lock) { pending.peekFirst() }
            if (oldest != null && System.nanoTime() - oldest.sentNs >= stalledReplyMs * 1_000_000L) {
                throw SocketTimeoutException("rigctld transaction '${oldest.name}' stalled for ${stalledReplyMs}ms")
            }
            val byte = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (byte == -1) {
                if (bytes.size() != 0) throw EOFException("Incomplete rigctld line")
                return null
            }
            if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
            if (bytes.size() >= 16_384) throw IOException("Oversized rigctld line")
            bytes.write(byte)
        }
        return null
    }

    suspend fun command(
        name: String,
        args: String = "",
        timeoutMs: Long,
        allowQueue: Boolean = false
    ): RigctldResponse? {
        require(name.matches(Regex("[a-z_]+")) && '\n' !in args && '\r' !in args)
        val context = currentCoroutineContext()
        val request = try {
            synchronized(lock) {
                context.ensureActive() // Never send a cancelled, queued key-ON.
                if (closed || (!allowQueue && pending.isNotEmpty())) return null
                // Repeated OFF while its previous response is still outstanding
                // waits for the same transaction instead of flooding rigctld.
                val tail = pending.peekLast()
                if (tail?.name == name && tail.args == args) tail else {
                    // Reserve the last slot for OFF, even during a burst of
                    // user frequency/mode changes against a slow daemon.
                    val limit = if (name == "set_ptt" && args == "0") 8 else 7
                    if (pending.size >= limit) return null
                    Pending(name, args).also {
                        pending.addLast(it)
                        writer.write("+\\$name${if (args.isEmpty()) "" else " $args"}\n")
                        writer.flush()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            close()
            onFailure(this, e)
            return null
        }
        // Deferred has no caller parent: cancellation must not erase the FIFO slot.
        return withTimeoutOrNull(timeoutMs) { request.reply.await() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Close socket before reader/writer: it wakes the blocking reader.
            try { socket.close() } catch (_: IOException) {}
            while (pending.isNotEmpty()) pending.removeFirst().reply.complete(null)
        }
        scope.cancel()
    }
}

package yakumo2683.RADEdecode.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * rigctld TCP protocol client for controlling amateur radios.
 *
 * Connects to a hamlib rigctld daemon and sends/receives text commands.
 * All I/O runs on Dispatchers.IO. Thread-safe via synchronized socket access.
 *
 * Protocol reference: https://hamlib.sourceforge.net/manuals/hamlib.html#rigctld-protocol
 */
class RigController {

    companion object {
        private const val TAG = "RigController"
        private const val CONNECT_TIMEOUT_MS = 3000
        // Per-command socket read timeout. Bounds how long a single (possibly
        // unanswered) CAT command can hold the serial lock. Kept well above real
        // CAT response times (tens of ms) but far below "several seconds" — the
        // old 3000 ms let one slow Xiegu G90 status read block a PTT for 3 s.
        private const val READ_TIMEOUT_MS = 1000
        // PTT set and its readback get a longer deadline: rigctld may spend its
        // own backend timeout+retry on a slow or lossy CAT link (Xiegu, USB
        // glitches, Wi-Fi/LTE CI-V) before answering, and a late
        // "set_ptt: 1;RPRT 0" is still the real answer. 1000 ms cut those off
        // and made every PTT look unacknowledged.
        private const val PTT_TIMEOUT_MS = 2500
        private const val DEFAULT_PORT = 4532
    }

    data class RigState(
        val connected: Boolean = false,
        val host: String = "",
        val port: Int = DEFAULT_PORT,
        val freqHz: Long = 0,
        val mode: String = "",
        val bandwidth: Int = 0,
        val ptt: Boolean = false,
        val sMeter: Int = 0,       // dB relative to S9 (e.g. -54 = S0, 0 = S9, +20 = S9+20)
        val rfPower: Float = 0f,   // watts
        val error: String = ""
    )

    private val _state = MutableStateFlow(RigState())
    val state: StateFlow<RigState> = _state.asStateFlow()

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Object()

    /** Last CAT command written to the socket — reported in the "connection lost"
     *  error so we can see which command was in flight when rigctld/the link died. */
    @Volatile private var lastCommand: String = ""

    /** Optional probe of the local rigctld's liveness/exit cause, supplied by the
     *  ViewModel ([RigctldProcess.exitDiagnostics]). Used to enrich the disconnect
     *  reason: rigctld crash vs. USB-serial bridge/socket failure. */
    var diagnosticsProvider: (() -> String?)? = null

    /** When the socket dropped but the local rigctld is still alive, we reconnect to
     *  it. These bound rapid flapping: count rapid reconnects, reset once a
     *  connection has been stable for a while. */
    @Volatile private var lastConnectMs = 0L
    @Volatile private var autoReconnects = 0
    @Volatile private var userDisconnected = false
    private val maxRapidReconnects = 6
    private val stableConnectionMs = 5000L

    /**
     * Number of user-initiated commands (PTT, set freq, etc.) currently waiting
     * for or holding the serial lock. The background status poller yields to
     * these: JVM `synchronized` is not fair, so without this a chatty poller can
     * repeatedly re-acquire the lock ahead of a waiting PTT and starve it for
     * seconds — the cause of the multi-second PTT delay on slow-CAT rigs like the
     * Xiegu G90.
     */
    private val userCmdPending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Run a latency-critical user command with priority over the poller. */
    private inline fun <T> priority(block: () -> T): T {
        userCmdPending.incrementAndGet()
        try { return block() } finally { userCmdPending.decrementAndGet() }
    }

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isConnected: Boolean get() = _state.value.connected

    /* ── Connection ─────────────────────────────────────────── */

    suspend fun connect(host: String, port: Int = DEFAULT_PORT) {
        disconnect()
        userDisconnected = false   // a (re)connect intent supersedes a prior disconnect
        withContext(Dispatchers.IO) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS

                synchronized(lock) {
                    socket = s
                    writer = PrintWriter(s.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(s.getInputStream()))
                }

                _state.value = _state.value.copy(
                    connected = true, host = host, port = port, error = ""
                )
                lastConnectMs = System.currentTimeMillis()
                Log.i(TAG, "Connected to rigctld at $host:$port")

                startPolling()
            } catch (e: java.net.ConnectException) {
                // ECONNREFUSED during retry — don't show as error
                _state.value = _state.value.copy(connected = false, error = "")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _state.value = _state.value.copy(
                    connected = false, error = e.message ?: "Connection failed"
                )
            }
        }
    }

    fun disconnect() {
        userDisconnected = true   // stops any pending auto-reconnect
        pollingJob?.cancel()
        pollingJob = null
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            writer = null
            reader = null
            socket = null
        }
        _state.value = _state.value.copy(connected = false)
        Log.i(TAG, "Disconnected")
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /* ── Command transport ──────────────────────────────────── */

    private fun sendCommand(
        cmd: String,
        timeoutMs: Int = READ_TIMEOUT_MS,
        acceptsReply: (String) -> Boolean = { true }
    ): String? {
        synchronized(lock) {
            val w = writer ?: return null
            val r = reader ?: return null
            val s = socket ?: return null
            return try {
                lastCommand = cmd
                w.println(cmd)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                var resp: String
                do {
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0) throw java.net.SocketTimeoutException("CAT reply deadline")
                    s.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                    resp = r.readLine() ?: throw java.io.EOFException("rigctld closed connection")
                    if (acceptsReply(resp)) break
                    Log.w(TAG, "Skipping stale reply while waiting for '$cmd': '$resp'")
                } while (true)
                if (cmd.isNotEmpty() && !cmd.startsWith("f") && !cmd.startsWith("t") && !cmd.startsWith("l")) {
                    Log.i(TAG, "CMD '$cmd' → '$resp'")
                }
                resp
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout is non-fatal — rigctld is just slow (CI-V retries)
                Log.w(TAG, "Command '$cmd' timed out (non-fatal)")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Command '$cmd' failed: ${e.message}")
                handleDisconnect(e)
                null
            } finally {
                try { s.soTimeout = READ_TIMEOUT_MS } catch (_: Exception) {}
            }
        }
    }

    /** Send command, read multi-line response until RPRT line. */
    private fun sendCommandMulti(cmd: String): List<String> {
        synchronized(lock) {
            val w = writer ?: return emptyList()
            val r = reader ?: return emptyList()
            return try {
                lastCommand = cmd
                w.println(cmd)
                val lines = mutableListOf<String>()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("RPRT")) break
                    lines.add(line)
                }
                lines
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Multi-command '$cmd' timed out (non-fatal)")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Multi-command '$cmd' failed: ${e.message}")
                handleDisconnect(e)
                emptyList()
            }
        }
    }

    private fun handleDisconnect(cause: Throwable? = null) {
        pollingJob?.cancel()

        // Enrich the reason so the cause is visible on-screen (no adb needed):
        // which CAT command was in flight, the actual socket exception, and whether
        // the local rigctld is still alive (crash vs. socket/bridge drop).
        val rigctld = try { diagnosticsProvider?.invoke() } catch (_: Throwable) { null }
        val exMsg = cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
        val detail = buildString {
            append("Connection lost")
            if (lastCommand.isNotEmpty()) append(" (last cmd: '$lastCommand')")
            if (exMsg != null) append(" {$exMsg}")
            if (!rigctld.isNullOrEmpty()) append(" [rigctld: $rigctld]")
        }
        Log.e(TAG, detail)

        // A connection that was stable for a while and then dropped gets a fresh
        // batch of retries; rapid re-drops are bounded to avoid flapping.
        if (System.currentTimeMillis() - lastConnectMs > stableConnectionMs) autoReconnects = 0

        // If the local rigctld is still alive, the TCP client just got dropped —
        // reconnect to it rather than leaving the user disconnected. (Only the
        // confirmed-alive local case: "running" comes from RigctldProcess.)
        if (rigctld == "running" && autoReconnects < maxRapidReconnects) {
            autoReconnects++
            val host = _state.value.host
            val port = _state.value.port
            _state.value = _state.value.copy(
                connected = false,
                error = "$detail — reconnecting ${autoReconnects}/$maxRapidReconnects…"
            )
            scope.launch {
                delay(700)
                if (!userDisconnected && !_state.value.connected) connect(host, port)
            }
        } else {
            _state.value = _state.value.copy(connected = false, error = detail)
        }
    }

    /* ── Frequency ──────────────────────────────────────────── */

    suspend fun setFreq(hz: Long) = withContext(Dispatchers.IO) {
        val resp = priority { sendCommand("F $hz") }
        // "RPRT 0" = accepted; "RPRT -n" = the rig/rigctld rejected the set.
        // Recording a rejected frequency here made the display show the new
        // value for ~1s until the poller snapped it back — looking like the
        // set "not working" with no clue why. Keep the last real frequency
        // and log the reason instead.
        if (resp != null && !resp.trim().startsWith("RPRT -")) {
            _state.value = _state.value.copy(freqHz = hz)
        } else if (resp != null) {
            Log.w(TAG, "set_freq $hz rejected: ${resp.trim()}")
        }
    }

    suspend fun getFreq(): Long = withContext(Dispatchers.IO) {
        val resp = sendCommand("f")
        val freq = resp?.trim()?.toLongOrNull()
        // Only accept a plausible HF/VHF/UHF frequency. rigctld get_freq can return
        // "RPRT -n" (error), an empty/partial line, or — if the text response
        // stream ever desyncs — a value belonging to another field (passband,
        // s-meter, ptt). The old code wrote whatever it got (0 on any failure)
        // straight into freqHz, so a momentarily-slow rig flashed "00000"/garbage
        // to the display and made the set-frequency field jump so it couldn't be
        // set — the reported fault after long receive, made frequent by v1.5.5's
        // retry=0. Reject implausible reads and keep the last good frequency.
        if (freq != null && freq in 10_000L..1_300_000_000L) {
            _state.value = _state.value.copy(freqHz = freq)
        }
        _state.value.freqHz
    }

    /* ── Mode ───────────────────────────────────────────────── */

    /**
     * Set mode. Passband defaults to -1 (RIG_PASSBAND_NOCHANGE) so hamlib preserves
     * the rig's current filter.
     *
     * If hamlib rejects PKTUSB/PKTLSB (common in 4.5.5), we:
     *   1. Query the rig's current data-mode + filter via CI-V before we change anything.
     *   2. Switch to the base mode (USB/LSB) with passband=NOCHANGE.
     *   3. Re-enable data mode via CI-V using the *preserved* filter byte, so the user's
     *      filter slot (FIL1/2/3) is restored rather than forced to FIL1.
     * If the CI-V query fails (non-Icom rig, CI-V off, timeout), we stop at the base
     * mode rather than risk clobbering the filter — data mode must then be toggled
     * manually on the rig.
     */
    suspend fun setMode(mode: String, bandwidth: Int = -1) = withContext(Dispatchers.IO) {
        var actualMode = mode
        val resp = sendCommand("M $mode $bandwidth")
        if (resp != null && resp.contains("-9")) {
            val baseMode = when (mode) {
                "PKTUSB" -> "USB"
                "PKTLSB" -> "LSB"
                else -> null
            }
            if (baseMode != null) {
                val preservedFilter = queryIcomDataFilter()
                Log.i(TAG, "setMode($mode) rejected; preserved filter byte=$preservedFilter")
                val baseResp = sendCommand("M $baseMode $bandwidth")
                if (baseResp == null || !baseResp.contains("-")) {
                    if (preservedFilter != null) {
                        sendCommand(
                            "w \\0xFE\\0xFE\\0x00\\0xE0\\0x1A\\0x06\\0x01\\0x$preservedFilter\\0xFD"
                        )
                        actualMode = mode
                    } else {
                        actualMode = baseMode
                    }
                } else {
                    actualMode = baseMode
                }
            }
        }
        _state.value = _state.value.copy(mode = actualMode, bandwidth = bandwidth)
    }

    /**
     * Query the current Icom data-mode + filter setting via raw CI-V.
     * Sends `FE FE 00 E0 1A 06 FD` (read form of the data-mode+filter command) and
     * parses the rig's reply `... 1A 06 <data_mode> <filter> FD`.
     * Returns the filter byte as a two-char hex string (e.g. "01", "02", "03"),
     * or null if no valid response was parsed.
     */
    private fun queryIcomDataFilter(): String? {
        val lines = sendCommandMulti("w \\0xFE\\0xFE\\0x00\\0xE0\\0x1A\\0x06\\0xFD")
        val bytePat = Regex("""\\0x([0-9A-Fa-f]{2})""")
        for (line in lines) {
            val bytes = bytePat.findAll(line)
                .map { it.groupValues[1].uppercase() }
                .toList()
            for (i in 0..bytes.size - 5) {
                if (bytes[i] == "1A" && bytes[i + 1] == "06" && bytes[i + 4] == "FD") {
                    return bytes[i + 3]
                }
            }
        }
        return null
    }

    suspend fun getMode(): Pair<String, Int> = withContext(Dispatchers.IO) {
        // Simple protocol: "m" returns mode on line 1, passband on line 2
        var mode = ""
        var bw = 0
        synchronized(lock) {
            val w = writer ?: return@withContext Pair("", 0)
            val r = reader ?: return@withContext Pair("", 0)
            try {
                w.println("m")
                // get_mode succeeds with TWO lines (mode, then passband); on error
                // rigctld sends a SINGLE "RPRT -n". Reading a 2nd line unconditionally
                // meant that on error we either stalled the full read timeout or, if
                // the response arrived late, consumed the NEXT command's reply —
                // desyncing the stream so every later "f" read returned the wrong
                // field (garbage frequency). Only consume the passband line when the
                // first line is a real mode token (starts with a letter, not RPRT).
                val line1 = r.readLine()?.trim() ?: ""
                if (line1.isNotEmpty() && !line1.startsWith("RPRT") &&
                    line1.first().isLetter()) {
                    mode = line1
                    bw = r.readLine()?.trim()?.toIntOrNull() ?: 0
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Non-fatal
            } catch (_: Exception) {}
        }
        if (mode.isNotEmpty()) {
            _state.value = _state.value.copy(mode = mode, bandwidth = bw)
        }
        Pair(mode, bw)
    }

    /* ── PTT ────────────────────────────────────────────────── */

    /** @return true when rigctld acknowledged the command; false when the
     *  command could not be delivered (socket down/timeout) — the caller must
     *  NOT assume the rig state changed in that case. */
    suspend fun setPtt(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val startedNs = System.nanoTime()
        Log.i(TAG, "setPtt($on) sending...")
        // Priority over the poller: keying/unkeying must not wait behind a slow
        // background status read (the Xiegu G90 multi-second PTT delay).
        // A prior status read may have timed out with its reply still in flight.
        // ERP echoes the command/value and result on one line, so that stale
        // reply cannot acknowledge this key/unkey. Other commands retain their
        // existing protocol; the read remains bounded by the usual deadline.
        val resp = priority {
            sendCommand(";T ${if (on) 1 else 0}", timeoutMs = PTT_TIMEOUT_MS) { rigctldPttResult(it, on) != null }
        }
        val acknowledged = rigctldPttResult(resp, on) == 0
        Log.i(TAG, "setPtt($on) response: $resp acknowledged=$acknowledged elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000}")
        if (acknowledged) {
            val current = _state.value
            _state.value = current.copy(ptt = on, error = if (current.error.startsWith("PTT ")) "" else current.error)
        } else if (isConnected) {
            _state.value = _state.value.copy(error = "PTT ${if (on) "ON" else "OFF"} not acknowledged: ${resp ?: "timeout"}")
        }
        acknowledged
    }

    suspend fun getPtt(): Boolean = withContext(Dispatchers.IO) {
        val ptt = rigctldPtt(sendCommand("t"))
        if (ptt != null) _state.value = _state.value.copy(ptt = ptt)
        _state.value.ptt
    }

    /**
     * Fresh PTT readback ("t") to confirm a key/unkey whose acknowledgement
     * never arrived. A slow or lossy CAT link loses the reply, or rigctld
     * answers "RPRT -5" after its own timeout, although the rig DID switch —
     * treating that as failure cut overs short after ~5 s and left a false
     * "PTT not confirmed" pending (reported on v1.6.15).
     * @param expected when the readback matches it, a pending "PTT …" error is
     *   cleared (the operation is confirmed after all).
     * @return the rig's PTT state, or null when it could not be read.
     */
    suspend fun readPtt(expected: Boolean? = null): Boolean? = withContext(Dispatchers.IO) {
        val ptt = priority { rigctldPtt(sendCommand("t", timeoutMs = PTT_TIMEOUT_MS)) }
        Log.i(TAG, "readPtt → $ptt (expected=$expected)")
        if (ptt != null) {
            val cur = _state.value
            val clearError = ptt == expected && cur.error.startsWith("PTT ")
            _state.value = cur.copy(ptt = ptt, error = if (clearError) "" else cur.error)
        }
        ptt
    }

    /* ── Levels (S-meter, RF power, SWR) ────────────────────── */

    suspend fun getSmeter(): Int = withContext(Dispatchers.IO) {
        val resp = sendCommand("l STRENGTH")
        val db = resp?.trim()?.toIntOrNull()
        // Hamlib STRENGTH is dB relative to S9. If the rigctld text stream ever
        // desyncs, this read can accidentally consume a frequency line such as
        // "14115000", which previously rendered as "S9+14115000dB". Reject
        // implausible readings and keep the last valid meter value.
        if (db != null && db in -80..80) {
            _state.value = _state.value.copy(sMeter = db)
        }
        _state.value.sMeter
    }

    suspend fun getRfPower(): Float = withContext(Dispatchers.IO) {
        val resp = sendCommand("l RFPOWER") ?: return@withContext 0f
        val power = resp.trim().toFloatOrNull() ?: 0f
        _state.value = _state.value.copy(rfPower = power)
        power
    }

    /* ── Power control ──────────────────────────────────────── */

    suspend fun setPowerstat(on: Boolean) = withContext(Dispatchers.IO) {
        priority { sendCommand("\\set_powerstat ${if (on) 1 else 0}") }
    }

    /* ── VFO ────────────────────────────────────────────────── */

    suspend fun setVfo(vfo: String) = withContext(Dispatchers.IO) {
        priority { sendCommand("V $vfo") }
    }

    /* ── Polling loop ───────────────────────────────────────── */

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var cycle = 0
            // Before each status read, stand down if a user command (PTT, freq…)
            // is pending or in flight, and don't issue a new one until it clears.
            // This is what keeps PTT responsive on slow-CAT rigs (Xiegu G90):
            // the poller voluntarily yields the unfair `synchronized` lock.
            suspend fun gate() { while (userCmdPending.get() > 0 && isActive) delay(15) }

            // Query mode on the very first cycle so the UI shows USB/LSB/etc.
            // as soon as the rig connects (otherwise the mode label stays blank
            // until the user triggers a manual setMode — Xiegu G90 etc.).
            try { gate(); getMode() } catch (_: Exception) {}
            while (isActive && _state.value.connected) {
                try {
                    // Each command releases the lock between calls; gate() ahead of
                    // each one yields priority to user-initiated commands.
                    gate(); getFreq()
                    delay(100)
                    gate(); getPtt()
                    delay(100)
                    // Avoid slow/unsupported RX meter reads holding the CAT
                    // lock when the operator releases PTT during TX.
                    gate(); if (!_state.value.ptt) getSmeter()
                    // Re-read mode every ~5s — mode changes rarely but the user
                    // may flip USB/LSB on the rig face, and we want the UI to
                    // reflect that without forcing them to reconnect.
                    if (cycle % 5 == 4) {
                        delay(100)
                        gate(); getMode()
                    }
                } catch (_: Exception) {}
                cycle++
                // Idle ~1s between cycles, sliced so a PTT pressed mid-wait is
                // serviced immediately (loop back → gate yields) instead of after
                // the full second.
                var slept = 0
                while (slept < 1000 && isActive && _state.value.connected &&
                       userCmdPending.get() == 0) {
                    delay(50); slept += 50
                }
            }
        }
    }
}

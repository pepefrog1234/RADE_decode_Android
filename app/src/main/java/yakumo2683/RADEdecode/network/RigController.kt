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

    private fun sendCommand(cmd: String): String? {
        synchronized(lock) {
            val w = writer ?: return null
            val r = reader ?: return null
            return try {
                w.println(cmd)
                val resp = r.readLine()
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
                handleDisconnect()
                null
            }
        }
    }

    /** Send command, read multi-line response until RPRT line. */
    private fun sendCommandMulti(cmd: String): List<String> {
        synchronized(lock) {
            val w = writer ?: return emptyList()
            val r = reader ?: return emptyList()
            return try {
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
                handleDisconnect()
                emptyList()
            }
        }
    }

    private fun handleDisconnect() {
        _state.value = _state.value.copy(connected = false, error = "Connection lost")
        pollingJob?.cancel()
    }

    /* ── Frequency ──────────────────────────────────────────── */

    suspend fun setFreq(hz: Long) = withContext(Dispatchers.IO) {
        val resp = priority { sendCommand("F $hz") }
        if (resp != null) {
            _state.value = _state.value.copy(freqHz = hz)
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

    suspend fun setPtt(on: Boolean) = withContext(Dispatchers.IO) {
        Log.i(TAG, "setPtt($on) sending...")
        // Priority over the poller: keying/unkeying must not wait behind a slow
        // background status read (the Xiegu G90 multi-second PTT delay).
        val resp = priority { sendCommand("T ${if (on) 1 else 0}") }
        Log.i(TAG, "setPtt($on) response: $resp")
        if (resp != null) {
            _state.value = _state.value.copy(ptt = on)
        }
    }

    suspend fun getPtt(): Boolean = withContext(Dispatchers.IO) {
        val resp = sendCommand("t") ?: return@withContext false
        val ptt = resp.trim() != "0"
        _state.value = _state.value.copy(ptt = ptt)
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
                    gate(); getSmeter()
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

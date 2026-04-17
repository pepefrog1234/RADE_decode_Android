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
        private const val READ_TIMEOUT_MS = 3000
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
        val resp = sendCommand("F $hz")
        if (resp != null) {
            _state.value = _state.value.copy(freqHz = hz)
        }
    }

    suspend fun getFreq(): Long = withContext(Dispatchers.IO) {
        val resp = sendCommand("f") ?: return@withContext 0L
        val freq = resp.trim().toLongOrNull() ?: 0L
        _state.value = _state.value.copy(freqHz = freq)
        freq
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
                mode = r.readLine()?.trim() ?: ""
                bw = r.readLine()?.trim()?.toIntOrNull() ?: 0
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
        val resp = sendCommand("T ${if (on) 1 else 0}")
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
        val resp = sendCommand("l STRENGTH") ?: return@withContext -54
        val db = resp.trim().toIntOrNull() ?: -54
        _state.value = _state.value.copy(sMeter = db)
        db
    }

    suspend fun getRfPower(): Float = withContext(Dispatchers.IO) {
        val resp = sendCommand("l RFPOWER") ?: return@withContext 0f
        val power = resp.trim().toFloatOrNull() ?: 0f
        _state.value = _state.value.copy(rfPower = power)
        power
    }

    /* ── Power control ──────────────────────────────────────── */

    suspend fun setPowerstat(on: Boolean) = withContext(Dispatchers.IO) {
        sendCommand("\\set_powerstat ${if (on) 1 else 0}")
    }

    /* ── VFO ────────────────────────────────────────────────── */

    suspend fun setVfo(vfo: String) = withContext(Dispatchers.IO) {
        sendCommand("V $vfo")
    }

    /* ── Polling loop ───────────────────────────────────────── */

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _state.value.connected) {
                try {
                    // Each command releases the lock between calls,
                    // giving user-initiated commands a chance to execute
                    getFreq()
                    delay(100)
                    getPtt()
                    delay(100)
                    getSmeter()
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }
}

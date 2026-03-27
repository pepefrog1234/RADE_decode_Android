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
        private const val CONNECT_TIMEOUT_MS = 5000
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
                r.readLine()
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

    suspend fun setMode(mode: String, bandwidth: Int = 0) = withContext(Dispatchers.IO) {
        sendCommand("M $mode $bandwidth")
        _state.value = _state.value.copy(mode = mode, bandwidth = bandwidth)
    }

    suspend fun getMode(): Pair<String, Int> = withContext(Dispatchers.IO) {
        // rigctld "m" returns two lines: mode, then passband
        val lines = sendCommandMulti("+m")
        if (lines.size >= 2) {
            val mode = lines[0].removePrefix("Mode: ").trim()
            val bw = lines[1].removePrefix("Passband: ").trim().toIntOrNull() ?: 0
            _state.value = _state.value.copy(mode = mode, bandwidth = bw)
            Pair(mode, bw)
        } else {
            // Fallback: simple protocol
            val resp = sendCommand("m")
            val mode = resp?.trim() ?: ""
            val bwResp = sendCommand("")  // second line
            val bw = bwResp?.trim()?.toIntOrNull() ?: 0
            _state.value = _state.value.copy(mode = mode, bandwidth = bw)
            Pair(mode, bw)
        }
    }

    /* ── PTT ────────────────────────────────────────────────── */

    suspend fun setPtt(on: Boolean) = withContext(Dispatchers.IO) {
        val resp = sendCommand("T ${if (on) 1 else 0}")
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
                    getFreq()
                    getPtt()
                    getSmeter()
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }
}

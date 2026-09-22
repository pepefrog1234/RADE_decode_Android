package yakumo2683.RADEdecode.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.net.Socket

/**
 * rigctld TCP protocol client for controlling amateur radios.
 *
 * Connects to a hamlib rigctld daemon and sends/receives text commands.
 * All I/O runs on Dispatchers.IO. RigctldTransport owns ordered ERP replies.
 *
 * Protocol reference: https://hamlib.sourceforge.net/manuals/hamlib.html#rigctld-protocol
 */
class RigController internal constructor(
    private val pollingEnabled: Boolean,
    private val catHealth: RigCatHealth = RigCatHealth()
) {

    constructor() : this(pollingEnabled = true)

    companion object {
        private const val TAG = "RigController"
        private const val CONNECT_TIMEOUT_MS = 3000
        // Caller deadlines do not abandon the reader's FIFO transaction slot.
        // A late response is still drained, while polls stand down and OFF can
        // be sent behind an in-flight command without blocking cancellation.
        private const val READ_TIMEOUT_MS = 1000L
        private const val FREQUENCY_TIMEOUT_MS = 2500L
        // PTT set and its readback get a longer deadline: rigctld may spend its
        // own backend timeout+retry on a slow or lossy CAT link (Xiegu, USB
        // glitches, Wi-Fi/LTE CI-V) before answering, and a late
        // "set_ptt: 1;RPRT 0" is still the real answer. 1000 ms cut those off
        // and made every PTT look unacknowledged.
        private const val PTT_TIMEOUT_MS = 2500L
        private const val DEFAULT_PORT = 4532
    }

    data class RigState(
        val connected: Boolean = false,
        val host: String = "",
        val port: Int = DEFAULT_PORT,
        val freqHz: Long = 0,
        val frequencyError: String = "",
        val mode: String = "",
        val bandwidth: Int = 0,
        val ptt: Boolean = false,
        val sMeter: Int = 0,       // dB relative to S9 (e.g. -54 = S0, 0 = S9, +20 = S9+20)
        val rfPower: Float = 0f,   // watts
        val error: String = ""
    )

    private val _state = MutableStateFlow(RigState())
    val state: StateFlow<RigState> = _state.asStateFlow()

    @Volatile private var connection: RigctldTransport? = null
    private val lock = Any()
    private var connectionGeneration = 0L
    private var requirePttOffReadback = false
    private var frequencyOperation = 0L
    private var unconfirmedFrequencyHz: Long? = null
    private val frequencyMutex = kotlinx.coroutines.sync.Mutex()
    private val pttOperation = java.util.concurrent.atomic.AtomicLong()
    /** Installed only for the Icom network tunnel. */
    var onCatUnresponsive: (() -> Unit)? = null
    /** True while an over is in progress (keyed, transmitting, or switching
     *  back). CAT-health recovery is deferred until it ends: tearing the
     *  session down mid-over cuts the transmission (field log v1.6.27), and
     *  the poll results that trigger it are unreliable during TX anyway. */
    var txInProgress: () -> Boolean = { false }
    private var deferredRecovery: (() -> Unit)? = null
    /** Network CI-V permits exist only while this explicit Set is in flight. */
    var authorizeFrequencyChange: ((Long) -> AutoCloseable?)? = null

    /** Last CAT command written to the socket — reported in the "connection lost"
     *  error so we can see which command was in flight when rigctld/the link died. */
    @Volatile private var lastCommand: String = ""

    /** Caller deadlines exceeded without a successful CAT response. */
    @Volatile private var consecutiveTimeouts = 0

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
     * User operations currently waiting for their replies. Polls yield while
     * these are active, and the transport also suppresses polls until all old
     * transactions have been drained after a timeout/cancellation.
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

    suspend fun connect(host: String, port: Int = DEFAULT_PORT, verifyPttOff: Boolean = false) {
        disconnect()
        val generation = synchronized(lock) {
            userDisconnected = false
            connectionGeneration
        }
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                ensureActive()
                synchronized(lock) {
                    if (userDisconnected || generation != connectionGeneration) {
                        socket.close()
                        return@withContext
                    }
                    val transport = RigctldTransport(socket, ::transportFailed, onResponse = ::recordCatResponse)
                    connection = transport
                    requirePttOffReadback = verifyPttOff
                    catHealth.reset()
                    deferredRecovery = null
                    consecutiveTimeouts = 0
                    _state.update { it.copy(connected = true, host = host, port = port, error = "") }
                    lastConnectMs = System.currentTimeMillis()
                    Log.i(TAG, "Connected to rigctld at $host:$port (ordered ERP)")
                    transport.start()
                    if (pollingEnabled) startPolling()
                }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                if (e is CancellationException) throw e
                synchronized(lock) {
                    if (generation == connectionGeneration && !userDisconnected) {
                        val error = if (e is java.net.ConnectException) "" else e.message ?: "Connection failed"
                        _state.update { it.copy(connected = false, error = error) }
                    }
                }
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            userDisconnected = true
            connectionGeneration++
            pollingJob?.cancel()
            pollingJob = null
            connection?.close()
            connection = null
            unconfirmedFrequencyHz = null
            deferredRecovery = null
            _state.update { it.copy(connected = false, frequencyError = "") }
        }
        Log.i(TAG, "Disconnected")
    }

    private fun transportFailed(transport: RigctldTransport, cause: Exception) {
        var recover: (() -> Unit)? = null
        synchronized(lock) {
            if (connection !== transport || userDisconnected) return
            if (cause is java.net.SocketTimeoutException) recover = onCatUnresponsive
            connection = null
            handleDisconnect(cause)
        }
        recover?.let { requestRecovery(it, "rigctld transport timed out") }
    }

    /** Run [recover] now, or after the current over if one is in progress. */
    private fun requestRecovery(recover: () -> Unit, why: String) {
        if (txInProgress()) {
            Log.w(TAG, "$why — Icom session recovery deferred until the over ends")
            synchronized(lock) { deferredRecovery = recover }
        } else {
            Log.w(TAG, "$why — recovering Icom session")
            recover.invoke()
        }
    }

    /** Called from the poller: fire a recovery that was deferred during TX. */
    private fun runDeferredRecovery() {
        if (txInProgress()) return
        val r = synchronized(lock) { deferredRecovery?.also { deferredRecovery = null } } ?: return
        Log.w(TAG, "Over ended — running the deferred Icom session recovery")
        r.invoke()
    }

    private fun recordCatResponse(transport: RigctldTransport, name: String, args: String, response: RigctldResponse) {
        val recover = synchronized(lock) {
            if (connection !== transport || userDisconnected) return
            if (name == "get_freq" || name == "set_freq") {
                Log.i(TAG, "Frequency reply: '$name $args' RPRT=${response.result} values=${response.lines}")
            }
            if (catHealth.record(name, args, response, System.nanoTime() / 1_000_000,
                    confirmOffByReadback = requirePttOffReadback)) onCatUnresponsive else null
        }
        if (recover != null) requestRecovery(recover, "Radio CAT has repeatedly failed for 15 s")
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /* ── Command transport ──────────────────────────────────── */

    private suspend fun sendCommand(
        name: String,
        args: String = "",
        timeoutMs: Long = READ_TIMEOUT_MS,
        allowQueue: Boolean = false,
        expectedConnection: RigctldTransport? = null
    ): RigctldResponse? {
        val transport = connection ?: return null
        if (expectedConnection != null && transport !== expectedConnection) return null
        // Polling must not add work behind a timed-out transaction. Its reader
        // continues draining independently, and PTT can still queue an OFF.
        if (!allowQueue && (transport.hasPending || userCmdPending.get() > 0)) return null
        lastCommand = "$name $args".trim()
        val response = transport.command(name, args, timeoutMs, allowQueue)
        if (connection !== transport) return null
        if (response == null) {
            consecutiveTimeouts++
            Log.w(TAG, "CAT '$name $args' reply pending/timed out; old reply remains assigned to its transaction")
        } else if (response.result == 0) {
            consecutiveTimeouts = 0
        }
        if (name.startsWith("set_") || response?.result?.let { it != 0 } == true) {
            Log.i(TAG, "CMD '$name $args' → RPRT ${response?.result} ${response?.lines}")
        }
        return response
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
            val verifyOff = requirePttOffReadback
            _state.value = _state.value.copy(
                connected = false,
                error = "$detail — reconnecting ${autoReconnects}/$maxRapidReconnects…"
            )
            scope.launch {
                delay(700)
                if (!userDisconnected && !_state.value.connected) connect(host, port, verifyOff)
            }
        } else {
            _state.value = _state.value.copy(connected = false, error = detail)
        }
    }

    /* ── Frequency ──────────────────────────────────────────── */

    suspend fun setFreq(hz: Long): Boolean {
        if (hz !in 10_000L..1_300_000_000L) return false
        val (operation, session) = synchronized(lock) {
            val active = connection ?: return false
            unconfirmedFrequencyHz = hz
            _state.update { it.copy(frequencyError = "") }
            ++frequencyOperation to active
        }
        return withContext(Dispatchers.IO) {
            priority {
                frequencyMutex.lock()
                var tuningPermit: AutoCloseable? = null
                try {
                    if (!isCurrentFrequencyOperation(operation, session)) return@priority false
                    tuningPermit = authorizeFrequencyChange?.invoke(hz)
                    Log.i(TAG, "setFreq request=$operation requestedHz=$hz")
                    val response = sendCommand("set_freq", "$hz", FREQUENCY_TIMEOUT_MS,
                        allowQueue = true, expectedConnection = session)
                    ensureActive()
                    if (!isCurrentFrequencyOperation(operation, session)) return@priority false
                    // A set acknowledgement alone does not tell us what the rig
                    // reports. Keep this readback on the same ordered session,
                    // even when a slow set reply has outlived its caller wait.
                    val observed = sendCommand("get_freq", timeoutMs = FREQUENCY_TIMEOUT_MS,
                        allowQueue = true, expectedConnection = session)
                        ?.value("Frequency")?.toLongOrNull()?.takeIf { it in 10_000L..1_300_000_000L }
                    ensureActive()
                    synchronized(lock) {
                        if (!isCurrentFrequencyOperation(operation, session)) return@synchronized false
                        val confirmed = observed == hz
                        unconfirmedFrequencyHz = if (confirmed) null else hz
                        _state.update { it.copy(
                            freqHz = observed ?: it.freqHz,
                            frequencyError = if (confirmed) "" else
                                "Frequency change not confirmed. Requested $hz Hz; reported ${observed?.let { "$it Hz" } ?: "unknown"}."
                        ) }
                        Log.i(TAG, "setFreq request=$operation requestedHz=$hz setResult=${response?.result} reportedHz=$observed confirmed=$confirmed")
                        confirmed
                    }
                } finally {
                    try { tuningPermit?.close() } finally { frequencyMutex.unlock() }
                }
            }
        }
    }

    private fun isCurrentFrequencyOperation(operation: Long, session: RigctldTransport): Boolean =
        synchronized(lock) { operation == frequencyOperation && connection === session }

    suspend fun getFreq(): Long {
        val (operation, session) = synchronized(lock) { frequencyOperation to connection }
        if (session == null) return _state.value.freqHz
        return withContext(Dispatchers.IO) {
            val freq = sendCommand("get_freq", expectedConnection = session)?.value("Frequency")?.toLongOrNull()
            synchronized(lock) {
                if (isCurrentFrequencyOperation(operation, session) && freq != null && freq in 10_000L..1_300_000_000L) {
                    val confirmed = freq == unconfirmedFrequencyHz
                    if (confirmed) {
                        unconfirmedFrequencyHz = null
                        Log.i(TAG, "setFreq request=$operation confirmed by later poll reportedHz=$freq")
                    }
                    _state.update { it.copy(freqHz = freq, frequencyError = if (confirmed) "" else it.frequencyError) }
                }
                _state.value.freqHz
            }
        }
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
        priority {
            val resp = sendCommand("set_mode", "$mode $bandwidth", allowQueue = true)
            if (resp?.result == 0) {
                _state.update { it.copy(mode = mode, bandwidth = bandwidth) }
            } else if (resp?.result == -9) {
                val baseMode = when (mode) {
                    "PKTUSB" -> "USB"
                    "PKTLSB" -> "LSB"
                    else -> null
                } ?: return@priority
                val filter = queryIcomDataFilter()
                if (sendCommand("set_mode", "$baseMode $bandwidth", allowQueue = true)?.result != 0) return@priority
                var actualMode = baseMode
                if (filter != null && sendCommand(
                        "send_cmd", "\\0xFE\\0xFE\\0x00\\0xE0\\0x1A\\0x06\\0x01\\0x$filter\\0xFD",
                        allowQueue = true
                    )?.result == 0) actualMode = mode
                _state.update { it.copy(mode = actualMode, bandwidth = bandwidth) }
            }
        }
    }

    /**
     * Query the current Icom data-mode + filter setting via raw CI-V.
     * Sends `FE FE 00 E0 1A 06 FD` (read form of the data-mode+filter command) and
     * parses the rig's reply `... 1A 06 <data_mode> <filter> FD`.
     * Returns the filter byte as a two-char hex string (e.g. "01", "02", "03"),
     * or null if no valid response was parsed.
     */
    private suspend fun queryIcomDataFilter(): String? {
        val lines = sendCommand("send_cmd", "\\0xFE\\0xFE\\0x00\\0xE0\\0x1A\\0x06\\0xFD", allowQueue = true)
            ?.takeIf { it.result == 0 }?.lines ?: return null
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
        val resp = sendCommand("get_mode")
        val mode = resp?.value("Mode")
        val bandwidth = resp?.value("Passband")?.toIntOrNull()
        if (mode != null && bandwidth != null) _state.update { it.copy(mode = mode, bandwidth = bandwidth) }
        Pair(_state.value.mode, _state.value.bandwidth)
    }

    /* ── PTT ────────────────────────────────────────────────── */

    suspend fun setPtt(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val (operation, session, verifyOff) = synchronized(lock) {
            val active = connection ?: return@withContext false
            Triple(pttOperation.incrementAndGet(), active, requirePttOffReadback)
        }
        val startedNs = System.nanoTime()
        Log.i(TAG, "setPtt($on) sending...")
        var result: Int? = null
        var readback: Boolean? = null
        val acknowledged = priority {
            val resp = sendCommand("set_ptt", if (on) "1" else "0", PTT_TIMEOUT_MS,
                allowQueue = true, expectedConnection = session)
            result = resp?.result
            ensureActive()
            if (operation != pttOperation.get() || connection !== session) return@priority false
            if (!on && verifyOff && result == 0) {
                // The bundled network daemon disables its cache. A generic
                // ACK alone is not evidence of OFF after duplicated CI-V replies.
                readback = rigctldPtt(sendCommand("get_ptt", timeoutMs = PTT_TIMEOUT_MS,
                    allowQueue = true, expectedConnection = session)?.value("PTT"))
                readback == false
            } else result == 0
        }
        ensureActive()
        Log.i(TAG, "setPtt($on) result=$result readback=$readback acknowledged=$acknowledged " +
            "elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000}")
        synchronized(lock) {
            if (connection !== session || operation != pttOperation.get()) return@withContext false
            if (acknowledged) {
                _state.update { it.copy(ptt = on, error = if (it.error.startsWith("PTT ")) "" else it.error) }
            } else if (isConnected) {
                _state.update { it.copy(error = "PTT ${if (on) "ON" else "OFF"} not confirmed: ${result ?: "pending"}" +
                    if (!on && verifyOff && result == 0) " (reported=${readback ?: "unknown"})" else "") }
            }
        }
        acknowledged
    }

    suspend fun getPtt(): Boolean = withContext(Dispatchers.IO) {
        val operation = pttOperation.get()
        val ptt = rigctldPtt(sendCommand("get_ptt")?.value("PTT"))
        if (ptt != null && operation == pttOperation.get()) _state.update { it.copy(ptt = ptt) }
        _state.value.ptt
    }

    /** Only a complete, correctly assigned get_ptt reply may confirm RF state.
     * Do not queue more readbacks while a slow set/status command is unresolved. */
    suspend fun readPtt(expected: Boolean? = null): Boolean? = withContext(Dispatchers.IO) {
        if (connection?.hasPending != false) return@withContext null
        val operation = pttOperation.get()
        val ptt = priority {
            rigctldPtt(sendCommand("get_ptt", timeoutMs = PTT_TIMEOUT_MS, allowQueue = true)?.value("PTT"))
        }
        if (operation != pttOperation.get()) return@withContext null
        Log.i(TAG, "readPtt → $ptt (expected=$expected)")
        if (ptt != null) {
            _state.update { it.copy(ptt = ptt, error = if (ptt == expected && it.error.startsWith("PTT ")) "" else it.error) }
        }
        ptt
    }

    /* ── Levels (S-meter, RF power, SWR) ────────────────────── */

    suspend fun getSmeter(): Int = withContext(Dispatchers.IO) {
        val db = sendCommand("get_level", "STRENGTH")?.level()?.toIntOrNull()
        if (db != null && db in -80..80) _state.update { it.copy(sMeter = db) }
        _state.value.sMeter
    }

    suspend fun getRfPower(): Float = withContext(Dispatchers.IO) {
        val power = sendCommand("get_level", "RFPOWER")?.level()?.toFloatOrNull()
        if (power != null) _state.update { it.copy(rfPower = power) }
        _state.value.rfPower
    }

    suspend fun setPowerstat(on: Boolean) = withContext(Dispatchers.IO) {
        priority { sendCommand("set_powerstat", if (on) "1" else "0", allowQueue = true) }
        Unit
    }

    suspend fun setVfo(vfo: String) = withContext(Dispatchers.IO) {
        priority { sendCommand("set_vfo", vfo, allowQueue = true) }
        Unit
    }

    /* ── Polling loop ───────────────────────────────────────── */

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var cycle = 0
            // User commands take priority over background status traffic.
            suspend fun gate() { while (userCmdPending.get() > 0 && isActive) delay(15) }

            // Query mode on the very first cycle so the UI shows USB/LSB/etc.
            // as soon as the rig connects (otherwise the mode label stays blank
            // until the user triggers a manual setMode — Xiegu G90 etc.).
            try { gate(); getMode() } catch (_: Exception) {}
            while (isActive && _state.value.connected) {
                try {
                    runDeferredRecovery()
                    // Gate each read, not just the start of a polling cycle. No
                    // frequency read while transmitting: Hamlib answers it with
                    // an empty reply during PTT, which only feeds the health
                    // tracker false failures (the dial cannot change mid-over).
                    gate(); if (!_state.value.ptt && !txInProgress()) getFreq()
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

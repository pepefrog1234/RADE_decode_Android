package yakumo2683.RADEdecode.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FreeDV Reporter client — connects to qso.freedv.org via Socket.IO v4.
 *
 * Self-contained Engine.IO + Socket.IO implementation using OkHttp WebSocket.
 * No external Socket.IO library needed. Mirrors iOS FreeDVReporter.swift.
 *
 * Protocol:
 *   Engine.IO: OPEN(0), CLOSE(1), PING(2), PONG(3), MESSAGE(4)
 *   Socket.IO: CONNECT(0), EVENT(2) wrapped inside Engine.IO MESSAGE
 */
class FreeDVReporter(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "FreeDVReporter"
        private const val BASE_URL = "wss://qso.freedv.org"
        private const val EIO_PATH = "/socket.io/?EIO=4&transport=websocket"

        // Engine.IO packet types
        private const val EIO_OPEN = '0'
        private const val EIO_CLOSE = '1'
        private const val EIO_PING = '2'
        private const val EIO_PONG = '3'
        private const val EIO_MESSAGE = '4'

        // Socket.IO packet types (inside EIO MESSAGE)
        private const val SIO_CONNECT = '0'
        private const val SIO_DISCONNECT = '1'
        private const val SIO_EVENT = '2'
    }

    data class ReporterStation(
        val connectionId: String,
        val callsign: String,
        val gridSquare: String,
        val frequency: Long,
        val mode: String,
        val version: String,
        val rxOnly: Boolean,
        val transmitting: Boolean = false,
        val transmittingAt: Long = 0,
        val receiving: Boolean = false,     // currently receiving signal (from rx_report)
        val receivingAt: Long = 0,          // when last rx_report came
        val receivedCallsign: String = "",  // decoded callsign of TX station
        val snr: Int = 0,
        val receivedAt: Long = 0,
        val lastUpdate: Long = System.currentTimeMillis()
    )

    data class ReporterConfig(
        val callsign: String = "",
        val gridSquare: String = "",
        val enabled: Boolean = false
    )

    private val stationLock = Any()
    private val stationMap = mutableMapOf<String, ReporterStation>()
    @Volatile private var stationsDirty = false
    private val _stations = MutableStateFlow<Map<String, ReporterStation>>(emptyMap())
    val stations: StateFlow<Map<String, ReporterStation>> = _stations.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    var config = ReporterConfig()
        private set

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pingIntervalMs: Long = 25000
    private var connectionId: String? = null

    @Synchronized
    fun configure(callsign: String, gridSquare: String, enabled: Boolean) {
        val changed = config.callsign != callsign || config.gridSquare != gridSquare
        config = ReporterConfig(callsign, gridSquare, enabled)
        if (!enabled) {
            disconnect()
        } else if (webSocket == null) {
            connect()
        } else if (changed && _connected.value) {
            disconnect()
            connect()
        }
    }

    @Synchronized
    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder()
            .url("$BASE_URL$EIO_PATH")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
                flushStations()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connected.value = false
                webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $reason")
                _connected.value = false
                webSocket = null
            }
        })
    }

    @Synchronized
    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        cleanupJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connected.value = false
        connectionId = null
        synchronized(stationLock) {
            stationMap.clear()
        }
        _stations.value = emptyMap()
    }

    /** Report a decoded callsign + SNR to the reporter network. */
    fun reportRx(callsign: String, snr: Int, frequency: Long = 14236000) {
        if (!_connected.value || config.callsign.isEmpty()) return
        sendEvent("rx_report", JSONObject().apply {
            put("callsign", callsign)
            put("snr", snr)
            put("mode", "RADEV1")
            put("frequency", frequency)
        })
    }

    /** Report frequency change. */
    fun reportFreqChange(frequency: Long) {
        if (!_connected.value) return
        sendEvent("freq_change", JSONObject().apply {
            put("freq", frequency)
        })
    }

    /* ── Protocol handling ───────────────────────────────────── */

    private fun handleMessage(raw: String) {
        if (raw.isEmpty()) return

        when (raw[0]) {
            EIO_OPEN -> handleEioOpen(raw.substring(1))
            EIO_PING -> {
                webSocket?.send("$EIO_PONG")
            }
            EIO_PONG -> { /* server pong, ignore */ }
            EIO_MESSAGE -> handleSioPacket(raw.substring(1))
            EIO_CLOSE -> {
                Log.i(TAG, "Server closed connection")
                disconnect()
            }
        }
    }

    private fun handleEioOpen(json: String) {
        try {
            val obj = JSONObject(json)
            val sid = obj.optString("sid", "")
            pingIntervalMs = obj.optLong("pingInterval", 25000)
            Log.i(TAG, "Engine.IO OPEN sid=$sid pingInterval=$pingIntervalMs")

            // Send Socket.IO CONNECT with auth
            val auth = JSONObject().apply {
                put("protocol_version", 2)
                if (config.callsign.isNotEmpty()) {
                    put("role", "report")
                    put("callsign", config.callsign)
                    put("grid_square", config.gridSquare)
                    put("version", "RADE_Android/1.0")
                    put("os", "Android")
                    put("rx_only", true)
                } else {
                    put("role", "view")
                }
            }
            val packet = "${EIO_MESSAGE}${SIO_CONNECT}${auth}"
            Log.i(TAG, "Sending SIO CONNECT: $packet")
            webSocket?.send(packet)

            startPingLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse EIO OPEN: ${e.message}")
        }
    }

    private fun handleSioPacket(data: String) {
        if (data.isEmpty()) return

        when (data[0]) {
            SIO_CONNECT -> {
                // Connection successful
                try {
                    val obj = JSONObject(data.substring(1))
                    connectionId = obj.optString("sid", null)
                    _connected.value = true
                    resetReconnectDelay()
                    Log.i(TAG, "Socket.IO connected, sid=$connectionId")
                } catch (_: Exception) {
                    _connected.value = true
                    resetReconnectDelay()
                }
            }
            SIO_DISCONNECT -> {
                Log.w(TAG, "Server disconnected us: ${data.substring(1)}")
                _connected.value = false
                webSocket?.close(1000, "Server disconnect")
                webSocket = null
                connectionId = null
            }
            SIO_EVENT -> handleSioEvent(data.substring(1))
        }
    }

    private fun handleSioEvent(json: String) {
        try {
            val arr = JSONArray(json)
            val eventName = arr.getString(0)
            val payload = if (arr.length() > 1) arr.get(1) else null

            when (eventName) {
                "new_connection" -> handleNewConnection(payload as? JSONObject)
                "remove_connection" -> handleRemoveConnection(payload as? JSONObject)
                "bulk_update" -> handleBulkUpdate(payload as? JSONArray)
                "rx_report" -> handleRxReport(payload as? JSONObject)
                "tx_report" -> handleTxReport(payload as? JSONObject)
                "freq_change" -> handleFreqChange(payload as? JSONObject)
                "connection_successful" -> Log.i(TAG, "Reporter: connection successful")
                else -> Log.d(TAG, "Unhandled event: $eventName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SIO event: ${e.message}")
        }
    }

    private fun handleNewConnection(data: JSONObject?) {
        data ?: return
        val id = data.optString("sid", data.optString("connection_id", ""))
        if (id.isEmpty()) return
        putStation(id, parseStation(id, data))
    }

    private fun handleRemoveConnection(data: JSONObject?) {
        val id = data?.optString("sid", data.optString("connection_id", "")) ?: return
        removeStation(id)
    }

    private fun handleBulkUpdate(data: JSONArray?) {
        data ?: return
        // Replay each entry as an individual event (matches C++ fireEvent behavior)
        for (i in 0 until data.length()) {
            val entry = data.optJSONArray(i) ?: continue
            if (entry.length() < 2) continue
            val eventType = entry.optString(0, "")
            val payload = entry.opt(1) ?: continue

            when (eventType) {
                "new_connection" -> handleNewConnection(payload as? JSONObject)
                "remove_connection" -> handleRemoveConnection(payload as? JSONObject)
                "rx_report" -> handleRxReport(payload as? JSONObject)
                "tx_report" -> handleTxReport(payload as? JSONObject)
                "freq_change" -> handleFreqChange(payload as? JSONObject)
                else -> {
                    val obj = payload as? JSONObject ?: continue
                    val id = obj.optString("sid", "")
                    if (id.isNotEmpty()) putStation(id, parseStation(id, obj))
                }
            }
        }
    }

    private fun handleRxReport(data: JSONObject?) {
        data ?: return
        val id = data.optString("sid", data.optString("connection_id", ""))
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        putStation(id, parseStation(id, data).copy(
            transmitting = false,
            receiving = true,
            receivingAt = now
        ))
    }

    private fun handleTxReport(data: JSONObject?) {
        data ?: return
        val id = data.optString("sid", data.optString("connection_id", ""))
        if (id.isEmpty()) return
        val isTx = data.optBoolean("transmitting", true)
        val now = System.currentTimeMillis()
        val station = parseStation(id, data).copy(
            transmitting = isTx,
            transmittingAt = now  // always mark as tx_report event
        )
        // For tx_report, callsign is at top level (not receiver_callsign)
        // parseStation might read it correctly, but force it from the data
        val cs = data.optString("callsign", "")
        val gs = data.optString("grid_square", "")
        val final = if (cs.isNotEmpty()) station.copy(callsign = cs) else station
        val finalWithGrid = if (gs.isNotEmpty()) final.copy(gridSquare = gs) else final
        putStation(id, finalWithGrid)
    }

    private fun handleFreqChange(data: JSONObject?) {
        data ?: return
        val id = data.optString("sid", data.optString("connection_id", ""))
        if (id.isEmpty()) return
        val freq = data.optLong("freq", data.optLong("frequency", 0))
        synchronized(stationLock) {
            val existing = stationMap[id]
            if (existing != null) {
                stationMap[id] = existing.copy(
                    frequency = if (freq > 0) freq else existing.frequency,
                    lastUpdate = System.currentTimeMillis()
                )
            } else {
                stationMap[id] = parseStation(id, data).copy(frequency = freq)
            }
            stationsDirty = true
        }
    }

    private fun flushStations() {
        val now = System.currentTimeMillis()
        synchronized(stationLock) {
            for ((id, st) in stationMap) {
                var changed = false
                var updated = st
                // Clear stale receivedCallsign (>5s since last non-empty rx_report)
                if (updated.receivedCallsign.isNotEmpty() && now - updated.receivedAt > 5000) {
                    updated = updated.copy(receivedCallsign = "", receivedAt = 0)
                    changed = true
                }
                // Clear stale receiving (>5s since last rx_report)
                if (updated.receiving && now - updated.receivingAt > 5000) {
                    updated = updated.copy(receiving = false, receivingAt = 0)
                    changed = true
                }
                if (changed) {
                    stationMap[id] = updated
                    stationsDirty = true
                }
            }
            if (stationsDirty) {
                stationsDirty = false
                _stations.value = stationMap.toMap()
            }
        }
    }

    private fun putStation(id: String, station: ReporterStation) {
        synchronized(stationLock) {
            val existing = stationMap[id]
            stationMap[id] = if (existing != null) mergeStation(existing, station) else station
            stationsDirty = true
        }
    }

    private fun removeStation(id: String) {
        synchronized(stationLock) {
            stationMap.remove(id)
            stationsDirty = true
        }
    }

    private fun parseStation(id: String, obj: JSONObject): ReporterStation {
        // rx_report: receiver_callsign = the station, callsign = what it receives
        val hasReceiver = obj.has("receiver_callsign")
        return ReporterStation(
            connectionId = id,
            callsign = if (hasReceiver) obj.optString("receiver_callsign", "")
                        else obj.optString("callsign", ""),
            gridSquare = obj.optString("receiver_grid_square",
                          obj.optString("grid_square", "")),
            frequency = obj.optLong("freq", obj.optLong("frequency", 0)),
            mode = obj.optString("mode", ""),
            version = obj.optString("version", ""),
            rxOnly = obj.optBoolean("rx_only", false),
            receivedCallsign = if (hasReceiver) obj.optString("callsign", "") else "",
            snr = obj.optInt("snr", 0)
        )
    }

    private fun mergeStation(existing: ReporterStation, update: ReporterStation): ReporterStation {
        val now = System.currentTimeMillis()

        // tx_report sets transmittingAt > 0; rx_report leaves it at 0
        val newTx = if (update.transmittingAt > 0) {
            update.transmitting  // explicit tx_report: use its value
        } else {
            existing.transmitting  // rx_report: don't change TX state
        }
        val newTxAt = if (update.transmittingAt > 0) update.transmittingAt else existing.transmittingAt

        // receivedCallsign: non-empty wins (sticky), cleared only by timeout
        val newReceived = if (update.receivedCallsign.isNotEmpty()) update.receivedCallsign
                          else existing.receivedCallsign
        val newReceivedAt = if (update.receivedCallsign.isNotEmpty()) now
                            else existing.receivedAt
        val newSnr = if (update.receivedCallsign.isNotEmpty()) update.snr else existing.snr

        // receiving: true from rx_report (has receivingAt), preserved otherwise
        val newReceiving = if (update.receivingAt > 0) update.receiving else existing.receiving
        val newReceivingAt = if (update.receivingAt > 0) update.receivingAt else existing.receivingAt

        return existing.copy(
            callsign = update.callsign.ifEmpty { existing.callsign },
            gridSquare = update.gridSquare.ifEmpty { existing.gridSquare },
            frequency = if (update.frequency > 0) update.frequency else existing.frequency,
            mode = update.mode.ifEmpty { existing.mode },
            version = update.version.ifEmpty { existing.version },
            transmitting = newTx,
            transmittingAt = newTxAt,
            receiving = newReceiving,
            receivingAt = newReceivingAt,
            receivedCallsign = newReceived,
            snr = newSnr,
            receivedAt = newReceivedAt,
            lastUpdate = now
        )
    }

    /* ── Send helpers ────────────────────────────────────────── */

    private fun sendEvent(name: String, data: JSONObject) {
        val arr = JSONArray().put(name).put(data)
        webSocket?.send("${EIO_MESSAGE}${SIO_EVENT}$arr")
    }

    private var cleanupJob: Job? = null

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                webSocket?.send("$EIO_PING")
            }
        }
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(1000)
                flushStations()
            }
        }
    }

    private var reconnectDelay = 5000L

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(300_000) // max 5 min
            if (config.enabled) connect()
        }
    }

    private fun resetReconnectDelay() {
        reconnectDelay = 5000L
    }
}

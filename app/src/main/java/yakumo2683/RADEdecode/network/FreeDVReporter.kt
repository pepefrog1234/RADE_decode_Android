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
        val lastUpdate: Long = System.currentTimeMillis()
    )

    data class ReporterConfig(
        val callsign: String = "",
        val gridSquare: String = "",
        val enabled: Boolean = false
    )

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

    fun configure(callsign: String, gridSquare: String, enabled: Boolean) {
        config = ReporterConfig(callsign, gridSquare, enabled)
        if (enabled) connect() else disconnect()
    }

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

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connected.value = false
        connectionId = null
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
            put("frequency", frequency)
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
                if (config.callsign.isNotEmpty()) {
                    put("callsign", config.callsign)
                    put("grid_square", config.gridSquare)
                    put("version", "RADE_Android/1.0")
                    put("os", "Android")
                    put("rx_only", true)
                }
            }
            webSocket?.send("${EIO_MESSAGE}${SIO_CONNECT}${auth}")

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
                    Log.i(TAG, "Socket.IO connected, sid=$connectionId")
                } catch (_: Exception) {
                    _connected.value = true
                }
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
        val id = data.optString("connection_id", "")
        if (id.isEmpty()) return

        val station = parseStation(id, data)
        val current = _stations.value.toMutableMap()
        current[id] = station
        _stations.value = current
    }

    private fun handleRemoveConnection(data: JSONObject?) {
        val id = data?.optString("connection_id", "") ?: return
        val current = _stations.value.toMutableMap()
        current.remove(id)
        _stations.value = current
    }

    private fun handleBulkUpdate(data: JSONArray?) {
        data ?: return
        val map = mutableMapOf<String, ReporterStation>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("connection_id", "")
            if (id.isNotEmpty()) {
                map[id] = parseStation(id, obj)
            }
        }
        _stations.value = map
    }

    private fun handleRxReport(data: JSONObject?) {
        // Update station's last activity
        data ?: return
        val id = data.optString("connection_id", "")
        val current = _stations.value.toMutableMap()
        current[id]?.let {
            current[id] = it.copy(lastUpdate = System.currentTimeMillis())
        }
        _stations.value = current
    }

    private fun handleFreqChange(data: JSONObject?) {
        data ?: return
        val id = data.optString("connection_id", "")
        val freq = data.optLong("frequency", 0)
        val current = _stations.value.toMutableMap()
        current[id]?.let {
            current[id] = it.copy(frequency = freq, lastUpdate = System.currentTimeMillis())
        }
        _stations.value = current
    }

    private fun parseStation(id: String, obj: JSONObject): ReporterStation {
        return ReporterStation(
            connectionId = id,
            callsign = obj.optString("callsign", ""),
            gridSquare = obj.optString("grid_square", ""),
            frequency = obj.optLong("frequency", 0),
            mode = obj.optString("mode", ""),
            version = obj.optString("version", ""),
            rxOnly = obj.optBoolean("rx_only", false)
        )
    }

    /* ── Send helpers ────────────────────────────────────────── */

    private fun sendEvent(name: String, data: JSONObject) {
        val arr = JSONArray().put(name).put(data)
        webSocket?.send("${EIO_MESSAGE}${SIO_EVENT}$arr")
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                webSocket?.send("$EIO_PING")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            if (config.enabled) connect()
        }
    }
}

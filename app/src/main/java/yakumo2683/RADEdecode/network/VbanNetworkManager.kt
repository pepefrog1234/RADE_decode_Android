package yakumo2683.RADEdecode.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * VBAN (VB-Audio Network) PCM audio transport — the app's RX/TX audio rides
 * Wi-Fi to a PC running Voicemeeter, whose virtual cables feed an SDR host
 * (Thetis etc.) as its VAC. This replaces the physical USB-sound-card +
 * cable link between phone and PC, and keeps PC-side features like
 * PureSignal fully working; CAT/PTT runs separately over the existing TCP
 * (rigctld) bridge to the SDR's CAT server.
 *
 * Protocol per the public VBAN spec (28-byte header + PCM payload, UDP):
 *   'V','B','A','N' | SR index + sub-protocol | nbs-1 | nbc-1 | bit-format |
 *   stream name[16] | frame counter (LE)
 *
 * We send mono PCM16 @48 kHz as stream [TX_STREAM_NAME] (select it as a
 * Voicemeeter *incoming* stream), and accept any incoming mono/stereo PCM16
 * @48 kHz audio stream from the configured host (configure a Voicemeeter
 * *outgoing* stream to this phone's IP on [port]).
 */
class VbanNetworkManager(private val appContext: Context? = null) : NetworkAudioRig {

    companion object {
        private const val TAG = "VbanNet"
        const val DEFAULT_PORT = 6980

        const val AUDIO_RATE = 48000
        const val TX_FRAME_SAMPLES = 960          // one 20 ms pump tick

        const val TX_STREAM_NAME = "RADE-TX"

        private const val HEADER_SIZE = 28
        private const val SR_INDEX_48K = 3        // VBAN sample-rate table
        private const val BITFMT_INT16 = 0x01
        private const val PROTOCOL_MASK = 0xE0
        private const val PROTOCOL_AUDIO = 0x00
        private const val CODEC_MASK = 0xF0
        private const val TX_CHUNK_SAMPLES = 240  // 4 packets per pump tick, 508 B each

        private val VBAN_SR = intArrayOf(
            6000, 12000, 24000, 48000, 96000, 192000, 384000,
            8000, 16000, 32000, 64000, 128000, 256000, 512000,
            11025, 22050, 44100, 88200, 176400, 352800, 705600
        )
    }

    data class State(
        val connecting: Boolean = false,
        val connected: Boolean = false,
        /** Incoming VBAN audio observed recently. */
        val streaming: Boolean = false,
        /** Name of the incoming stream being received. */
        val deviceName: String = "",
        val error: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    override val isConnected: Boolean get() = _state.value.connected
    override val audioLinkUp: Boolean get() = _state.value.streaming
    override val audioRate: Int get() = AUDIO_RATE
    override val txFrameSamples: Int get() = TX_FRAME_SAMPLES
    @Volatile override var onAudioPcm: ((ShortArray) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var target: InetSocketAddress? = null
    private var connScope: CoroutineScope? = null
    private val sendLock = Any()
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var lastRxNanos = 0L
    /* Health counters (reset each stats interval) */
    @Volatile private var txPktInterval = 0
    @Volatile private var rxPktInterval = 0
    @Volatile private var rxGapInterval = 0
    private var rxLastFrame = -1L
    @Volatile private var srWarned = false

    private val txPacket = ByteArray(HEADER_SIZE + TX_CHUNK_SAMPLES * 2)
    private var txFrameCounter = 0L

    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.connected || _state.value.connecting) return@withContext false
        if (host.isBlank()) {
            _state.value = State(error = "PC host address is required")
            return@withContext false
        }
        _state.value = State(connecting = true)
        try {
            val addr = InetAddress.getByName(host.trim())
            val sock = DatagramSocket(port).apply {
                soTimeout = 400
                try { trafficClass = 0xB8 } catch (_: Exception) {}
            }
            socket = sock
            target = InetSocketAddress(addr, port)
            acquireWifiLock()
            srWarned = false
            rxLastFrame = -1L
            txFrameCounter = 0L
            writeTxHeader()

            _state.value = State(connected = true)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            connScope = scope
            scope.launch { readerLoop(sock, addr) }
            scope.launch { healthLoop() }
            Log.i(TAG, "VBAN up: peer=$host:$port txStream=$TX_STREAM_NAME rate=$AUDIO_RATE")
            true
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            releaseWifiLock()
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            target = null
            _state.value = State(error = "VBAN failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        connScope?.cancel()
        connScope = null
        releaseWifiLock()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        target = null
        onAudioPcm = null
        _state.value = State()
        Log.i(TAG, "Disconnected")
    }

    /* ── RX: Voicemeeter outgoing stream → modem ─────────────── */

    private suspend fun readerLoop(sock: DatagramSocket, peer: InetAddress) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var streamLogged = false
        var peerWarned = false
        while (connScope?.isActive == true) {
            try {
                pkt.setData(buf, 0, buf.size)
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                if (_state.value.streaming &&
                    System.nanoTime() - lastRxNanos > 2_000_000_000L
                ) {
                    _state.value = _state.value.copy(streaming = false)
                    streamLogged = false
                    Log.w(TAG, "Incoming VBAN stream stalled")
                }
                continue
            } catch (_: Exception) {
                break
            }
            val n = pkt.length
            if (n < HEADER_SIZE || buf[0] != 'V'.code.toByte() || buf[1] != 'B'.code.toByte() ||
                buf[2] != 'A'.code.toByte() || buf[3] != 'N'.code.toByte()
            ) continue
            if (pkt.address != peer) {
                if (!peerWarned) {
                    peerWarned = true
                    Log.w(TAG, "Ignoring VBAN from unexpected host ${pkt.address.hostAddress}")
                }
                continue
            }
            val sr = buf[4].toInt() and 0xFF
            if ((sr and PROTOCOL_MASK) != PROTOCOL_AUDIO) continue   // text/service/etc.
            val bit = buf[7].toInt() and 0xFF
            if ((bit and CODEC_MASK) != 0 || (bit and 0x07) != BITFMT_INT16) continue
            val rate = VBAN_SR.getOrNull(sr and 0x1F) ?: 0
            if (rate != AUDIO_RATE) {
                if (!srWarned) {
                    srWarned = true
                    Log.e(TAG, "Incoming VBAN stream is $rate Hz — set Voicemeeter's outgoing stream to 48000 Hz")
                    _state.value = _state.value.copy(
                        error = "Incoming stream is $rate Hz (need 48000)"
                    )
                }
                continue
            }
            val samples = (buf[5].toInt() and 0xFF) + 1
            val channels = (buf[6].toInt() and 0xFF) + 1
            if (channels !in 1..2) continue
            val need = HEADER_SIZE + samples * 2 * channels
            if (n < need) continue

            val frame = ((buf[24].toLong() and 0xFF)) or
                ((buf[25].toLong() and 0xFF) shl 8) or
                ((buf[26].toLong() and 0xFF) shl 16) or
                ((buf[27].toLong() and 0xFF) shl 24)
            if (rxLastFrame >= 0 && frame > rxLastFrame + 1) {
                rxGapInterval += (frame - rxLastFrame - 1).toInt()
            }
            rxLastFrame = frame
            rxPktInterval++
            lastRxNanos = System.nanoTime()
            if (!streamLogged) {
                streamLogged = true
                val name = String(buf, 8, 16).substringBefore('\u0000').trim()
                _state.value = _state.value.copy(streaming = true, deviceName = name, error = "")
                Log.i(TAG, "Incoming VBAN stream up: \"$name\" ${rate}Hz ch=$channels")
            }

            val sink = onAudioPcm ?: continue
            val out = ShortArray(samples)
            var p = HEADER_SIZE
            if (channels == 1) {
                for (i in 0 until samples) {
                    out[i] = (((buf[p].toInt() and 0xFF)) or (buf[p + 1].toInt() shl 8)).toShort()
                    p += 2
                }
            } else {
                // Stereo from Voicemeeter: take the left channel.
                for (i in 0 until samples) {
                    out[i] = (((buf[p].toInt() and 0xFF)) or (buf[p + 1].toInt() shl 8)).toShort()
                    p += 4
                }
            }
            sink(out)
        }
    }

    /* ── TX: modem → Voicemeeter incoming stream ─────────────── */

    private fun writeTxHeader() {
        txPacket[0] = 'V'.code.toByte(); txPacket[1] = 'B'.code.toByte()
        txPacket[2] = 'A'.code.toByte(); txPacket[3] = 'N'.code.toByte()
        txPacket[4] = SR_INDEX_48K.toByte()                 // audio sub-protocol + 48 kHz
        txPacket[5] = (TX_CHUNK_SAMPLES - 1).toByte()
        txPacket[6] = 0                                     // mono
        txPacket[7] = BITFMT_INT16.toByte()                 // PCM 16-bit
        for (i in 0 until 16) {
            txPacket[8 + i] = if (i < TX_STREAM_NAME.length) TX_STREAM_NAME[i].code.toByte() else 0
        }
    }

    override fun sendAudioFrame(pcm: ShortArray) {
        val sock = socket ?: return
        val dst = target ?: return
        if (!isConnected) return
        synchronized(sendLock) {
            var off = 0
            while (off + TX_CHUNK_SAMPLES <= pcm.size) {
                txPacket[24] = txFrameCounter.toByte()
                txPacket[25] = (txFrameCounter ushr 8).toByte()
                txPacket[26] = (txFrameCounter ushr 16).toByte()
                txPacket[27] = (txFrameCounter ushr 24).toByte()
                txFrameCounter++
                var p = HEADER_SIZE
                for (i in 0 until TX_CHUNK_SAMPLES) {
                    val s = pcm[off + i].toInt()
                    txPacket[p] = s.toByte()
                    txPacket[p + 1] = (s shr 8).toByte()
                    p += 2
                }
                try {
                    sock.send(DatagramPacket(txPacket, txPacket.size, dst))
                    txPktInterval++
                } catch (e: Exception) {
                    Log.w(TAG, "VBAN send failed", e)
                    return
                }
                off += TX_CHUNK_SAMPLES
            }
        }
    }

    /* ── Diagnostics ─────────────────────────────────────────── */

    private suspend fun healthLoop() {
        while (connScope?.isActive == true) {
            delay(5000)
            if (!_state.value.connected) continue
            Log.i(
                TAG,
                "VBAN health: tx=${txPktInterval / 5}/s rx=${rxPktInterval / 5}/s " +
                    "gaps=$rxGapInterval stream=\"${_state.value.deviceName}\" " +
                    "streaming=${_state.value.streaming}"
            )
            txPktInterval = 0
            rxPktInterval = 0
            rxGapInterval = 0
        }
    }

    private fun acquireWifiLock() {
        val ctx = appContext ?: return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RADE:VBAN")
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RADE:VBAN")
            }
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            Log.i(TAG, "Wi-Fi performance lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock unavailable", e)
        }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }
}

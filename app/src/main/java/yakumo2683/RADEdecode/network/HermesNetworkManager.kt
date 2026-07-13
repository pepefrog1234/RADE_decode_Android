package yakumo2683.RADEdecode.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import yakumo2683.RADEdecode.PureSignalBridge
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct network control of a Hermes-Lite 2 over openHPSDR Protocol 1
 * (Metis): discovery, start/stop, C&C (frequency / PTT / TX drive / LNA
 * gain) and the bidirectional 48 kHz I/Q stream — all on UDP port 1024,
 * no PC host in between.
 *
 * The RADE modem side of the app exchanges plain 8 kHz PCM audio, so this
 * manager also does the SSB translation in software:
 *
 *   RX: 24-bit I/Q @48k → complex decimate ÷6 → analytic USB bandpass
 *       (300–2700 Hz, carrier at the NCO) → Re{·} → AGC → int16 @8k
 *       → [onAudioPcm] → native feedNetRx (factor-1 passthrough).
 *   TX: native fillNetTxFrame @8k (RADE waveform) → analytic bandpass
 *       (Hilbert) → complex interpolate ×6 → 16-bit I/Q @48k → EP2 frames.
 *
 * Pacing: while idle, one host EP2 packet is sent per received EP6 packet
 * (radio-clocked, 126 samples each way at 48 kHz); while keyed, EP2 is
 * self-clocked at 48 kHz so TX never depends on the RX stream.
 *
 * [appContext] enables a Wi-Fi performance lock for the duration of the
 * connection: phone Wi-Fi power save throttles the sustained ~3 Mbit/s UDP
 * *upstream* the TX I/Q needs, which starves the radio's TX FIFO — the rig
 * keys (MOX arrives) but transmits no RF, while the downstream RX direction
 * keeps working ("one-way" symptom).
 */
class HermesNetworkManager(private val appContext: Context? = null) : NetworkAudioRig {

    companion object {
        private const val TAG = "HermesNet"
        const val HPSDR_PORT = 1024

        /** PCM rate exchanged with the native modem (factor-1 passthrough). */
        const val AUDIO_RATE = 8000
        /** 20 ms of 8 kHz PCM per TX pump tick. */
        const val TX_FRAME_SAMPLES = 160

        private const val IQ_RATE = 48000
        private const val INTERP = IQ_RATE / AUDIO_RATE            // 6

        /** Receivers: RX1 = receive/physical ADC/PA feedback; RX2 becomes
         *  the internal TX-DAC reference during MOX when the PureSignal bit
         *  (addr 0x0A bit 22) is set. */
        private const val NUM_RX = 2
        /** EP6 sample group: 6 bytes I/Q per receiver + 2 bytes mic. */
        private const val RX_GROUP_BYTES = 6 * NUM_RX + 2
        private const val RX_SAMPLES_PER_FRAME = 504 / RX_GROUP_BYTES   // 36 with 2 RX

        /** EP2 (host→radio) frames always carry 63 TX samples regardless of NRX. */
        private const val SAMPLES_PER_FRAME = 63
        private const val SAMPLES_PER_PACKET = 2 * SAMPLES_PER_FRAME
        private const val PACKET_NS = SAMPLES_PER_PACKET * 1_000_000_000L / IQ_RATE  // 2.625 ms

        // SSB passband (Hz above the suppressed carrier / NCO)
        private const val SSB_CENTER = 1500.0
        private const val SSB_HALF_BW = 1200.0                      // 300..2700 Hz

        // HL2 addr 0x17: radio-side TX FIFO priming depth + PTT hang. The
        // gateware default latency is only ~20 ms — any phone-side scheduling
        // hiccup longer than that underruns the radio's FIFO mid-over
        // (choppy/unstable RF). 40 ms matches common PC hosts (Quisk).
        // Hang stays at the gateware default (12 ms).
        private const val TX_BUFFER_LATENCY_MS = 40
        private const val PTT_HANG_MS = 12

        private const val DECIM_TAPS = 144                          // 24 per phase × 6
        private const val ANALYTIC_TAPS = 129
        private const val INTERP_TAPS_PER_PHASE = 24

        // RX AGC: I/Q off-air levels are tiny and LNA-dependent; normalize
        // toward a healthy modem drive. Native then applies inputGain_ × 0.15.
        private const val AGC_TARGET_RMS = 0.22f
        private const val AGC_MAX_GAIN = 2000f                      // +66 dB
        private const val AGC_SMOOTH = 0.1f

        // Analytic TX of a real signal halves the waveform amplitude, but the
        // complex ENVELOPE of the clamped RADE waveform peaks ~25% above the
        // waveform itself: on hardware the ×2.0 "restore" made the int16
        // stage rail at ±32767 (HL2's RX2 DAC-loopback reference sat pinned
        // at hwPeak in every interval). ×1.6 maps the measured ≥0.625
        // analytic peak to full scale with the ×0.8 headroom actually intact.
        private const val TX_IQ_SCALE = 1.6f * 0.8f * 32767.0f

        /* ── PureSignal phase 2b (WDSP calcc/iqc via PureSignalBridge) ──
         *
         * The engine works in a normalized domain where the TX envelope
         * peaks at exactly 1.0: IQC bins its input envelope over [0,1] in 16
         * intervals (a cubic per bin — envelopes past 1.0 EXTRAPOLATE the top
         * spline), and CALCC both discards collection samples whose scaled
         * reference envelope exceeds 1.0 and needs the top bin populated to
         * finish a collection at all. Our analytic floats (oi,oq) measured
         * ≥0.625 peak on hardware — not the naive 0.5 — so the previous
         * ×2.0 norm overflowed that domain to ~1.25 AND railed the int16
         * wire stage; the corrector then expanded peaks further into the
         * clip each calibration, which is why correction made IMD worse.
         * PS_TX_NORM = 1.6 maps the measured peak to ~1.0 and a radial
         * (phase-preserving) limiter in sendAudioFrame pins the residual
         * overshoot to exactly 1.0, which also guarantees top-bin samples.
         * int16 output uses TX_IQ_SCALE / PS_TX_NORM = 0.8 full scale at
         * envelope 1.0, leaving ~2 dB for the corrector's peak expansion.
         * RX2 is multiplied by the inverse of that final 0.8 protocol scale
         * before CALCC, matching piHPSDR's HL2 drive-inverse treatment. */
        private const val PS_TX_NORM = 1.6f
        private const val PS_POST_SCALE = TX_IQ_SCALE / PS_TX_NORM
        /** Undo the fixed post-IQC protocol scale on RX2 before CALCC. */
        private const val PS_TX_REFERENCE_SCALE = 32767.0f / PS_POST_SCALE

        /** Complex samples per psFeed block (matches the engine blockSize). */
        private const val PS_BLOCK_SAMPLES = 1024

        /**
         * Protocol-1 HL2 TX-DAC reference peak. piHPSDR measured 0.2386 for
         * HL2 and uses a padded 0.2400. WDSP uses this to normalize the
         * hardware reference back into the [0,1] correction-envelope domain.
         */
        private const val PS_HL2_TX_REF_PEAK = 0.24f
    }

    data class State(
        val connecting: Boolean = false,
        val connected: Boolean = false,
        /** EP6 I/Q stream observed recently. */
        val streaming: Boolean = false,
        val deviceName: String = "",
        val freqHz: Long = 14_236_000L,
        val ptt: Boolean = false,
        val error: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    override val isConnected: Boolean get() = _state.value.connected
    override val audioLinkUp: Boolean get() = _state.value.streaming
    override val audioRate: Int get() = AUDIO_RATE
    override val txFrameSamples: Int get() = TX_FRAME_SAMPLES
    @Volatile override var onAudioPcm: ((ShortArray) -> Unit)? = null

    /* ── Connection ─────────────────────────────────────────── */

    private var socket: DatagramSocket? = null
    private var radioAddr: InetSocketAddress? = null
    private var connScope: CoroutineScope? = null
    @Volatile private var connectionClosing = false
    private val sendLock = Any()
    @Volatile private var lastEp6Nanos = 0L
    @Volatile private var lastEp2Nanos = 0L
    private var ep6Toggle = false

    /* TX health counters (reset each 1 s stats interval by the pacer) */
    @Volatile private var ep2SentInterval = 0
    @Volatile private var txIqUnderrunInterval = 0
    @Volatile private var ep6RecvInterval = 0
    @Volatile private var ep6GapInterval = 0
    private var ep6LastSeq = -1L

    /* Radio-reported status parsed from EP6 C&C (raw ADC units) */
    @Volatile private var radioFwdPower = 0
    @Volatile private var radioRevPower = 0
    @Volatile private var radioPaCurrent = 0
    @Volatile private var radioTemp = 0
    /** OR-latch every clip report until the one-second PS controller tick. */
    private val psAdcOverflowInterval = AtomicBoolean(false)

    /* PureSignal phase 1: per-receiver level accumulators during MOX, to
     * identify and calibrate the TX feedback path. Reader thread writes,
     * pacer stats block reads+resets. */
    private val fbStatsLock = Any()
    private var fbSumSq1 = 0.0
    private var fbSumSq2 = 0.0
    private var fbPeak1 = 0f
    private var fbPeak2 = 0f
    private var fbCount = 0

    /* ── PureSignal phase 2b: live predistortion state ────────
     *
     * HL2's gateware supplies the correctly paired PureSignal streams:
     * RX1 remains on the physical ADC (PA output feedback), while addr 0x0A
     * bit 22 switches RX2 to the internal TX DAC reference during MOX. Both
     * pass through matching mixer/receiver chains and arrive in the same EP6
     * sample group. Feed inverse-drive-scaled RX2 first and RX1 second to
     * WDSP pscc; adding the phone TX-ring/FIFO delay here would incorrectly
     * de-align that pair. */
    @Volatile private var psEnabledFlag = false
    /** Engine exists (psCreate succeeded); cleared by disable/disconnect. */
    @Volatile private var psEngineUp = false
    private val psEngineLifecycleLock = Any()
    private val psRestartInProgress = AtomicBoolean(false)
    private val psControlLock = Any()
    private var psFeedbackController = Hl2PureSignalFeedbackController()
    @Volatile private var psFeedbackGainDb = psFeedbackController.gainDb
    @Volatile private var psControlStatus = "IDLE"

    private val psBlockAssembler = Hl2PureSignalBlockAssembler(
        blockSamples = PS_BLOCK_SAMPLES,
        txReferenceScale = PS_TX_REFERENCE_SCALE
    ) {
        txReferenceIq, paFeedbackIq, sampleCount ->
        // PTT/disable can race the reader at a block boundary. Recheck the
        // active state before entering native calibration.
        if (mox && psEnabledFlag && psEngineUp) {
            PureSignalBridge.psFeed(txReferenceIq, paFeedbackIq, sampleCount)
        }
    }

    /* Pacer-thread scratch for the 1 s diagnostics line. */
    private val psInfoScratch = IntArray(16)

    /* PS envelope diagnostics: highest pre-limiter envelope and number of
     * radially limited samples since the last stats line. Written only by
     * the TX pump thread; read-then-reset by the pacer (losing one racy
     * update is acceptable for a diagnostic). */
    @Volatile private var psEnvPeakInterval = 0f
    @Volatile private var psEnvLimitedInterval = 0

    private fun resetFeedbackStats() {
        synchronized(fbStatsLock) {
            fbSumSq1 = 0.0
            fbSumSq2 = 0.0
            fbPeak1 = 0f
            fbPeak2 = 0f
            fbCount = 0
        }
    }

    /** PureSignal enabled (UI switch state as applied to this manager). */
    val psEnabled: Boolean get() = psEnabledFlag

    /** Snapshot of the WDSP calibration state vector (see puresignal.h). */
    fun psInfo(): IntArray {
        val out = IntArray(16)
        PureSignalBridge.psGetInfo(out)   // zeroed when the engine is down
        return out
    }

    /**
     * Enable/disable PureSignal adaptive predistortion (experimental).
     * While connected the engine is created/destroyed on the spot; the
     * calibration itself only runs while keyed (see [setPtt]).
     */
    fun setPsEnabled(v: Boolean) {
        if (psEnabledFlag == v) return
        psEnabledFlag = v
        Log.i(TAG, "PureSignal ${if (v) "ENABLED" else "disabled"}")
        if (v) {
            synchronized(psControlLock) {
                psFeedbackController = Hl2PureSignalFeedbackController()
                psFeedbackGainDb = psFeedbackController.gainDb
                if (mox) {
                    psFeedbackController.onPttStarted(SystemClock.elapsedRealtime())
                    psControlStatus = "PREFLIGHT"
                }
            }
            if (_state.value.connected) psEngineCreate()
        } else {
            synchronized(psControlLock) {
                psFeedbackController.onPttStopped()
                psControlStatus = "IDLE"
            }
            psEngineDestroy()
        }
    }

    private fun psEngineCreate() {
        synchronized(psEngineLifecycleLock) {
            if (psEngineUp || !psEnabledFlag || connectionClosing ||
                !_state.value.connected) return
            psBlockAssembler.reset()
            psEngineUp = PureSignalBridge.psCreate(
                IQ_RATE,
                PS_BLOCK_SAMPLES,
                PS_HL2_TX_REF_PEAK
            )
            Log.i(TAG, "PureSignal engine create: ${if (psEngineUp) "OK" else "FAILED"} " +
                "(rate=$IQ_RATE block=$PS_BLOCK_SAMPLES hwPeak=$PS_HL2_TX_REF_PEAK)")
        }
    }

    private fun psEngineDestroy() {
        synchronized(psEngineLifecycleLock) {
            psEngineUp = false
            psBlockAssembler.reset()
            PureSignalBridge.psDestroy()   // force-clears any in-flight calibration
            Log.i(TAG, "PureSignal engine destroyed")
        }
    }

    /** A gain change invalidates samples already captured by CALCC. Destroying
     * on a worker also prevents an in-flight detached solver from swapping a
     * stale/clipped candidate after the controller has backed the ADC off. */
    private fun restartPsEngine(reason: String) {
        if (!psRestartInProgress.compareAndSet(false, true)) return
        psEngineUp = false
        psBlockAssembler.reset()
        resetFeedbackStats()
        kotlin.concurrent.thread(name = "hl2-ps-restart") {
            try {
                synchronized(psEngineLifecycleLock) {
                    PureSignalBridge.psDestroy()
                    if (psEnabledFlag && !connectionClosing && _state.value.connected) {
                        psEngineUp = PureSignalBridge.psCreate(
                            IQ_RATE,
                            PS_BLOCK_SAMPLES,
                            PS_HL2_TX_REF_PEAK
                        )
                    }
                }
                Log.i(TAG, "PureSignal engine restarted ($reason): " +
                    if (psEngineUp) "OK" else "left down")
            } finally {
                psRestartInProgress.set(false)
            }
        }
    }

    private fun invalidatePsOperatingPoint(reason: String) {
        synchronized(psControlLock) {
            psFeedbackController = Hl2PureSignalFeedbackController()
            psFeedbackGainDb = psFeedbackController.gainDb
            if (mox) {
                psFeedbackController.onPttStarted(SystemClock.elapsedRealtime())
                psControlStatus = "PREFLIGHT"
            } else {
                psControlStatus = "IDLE"
            }
        }
        psAdcOverflowInterval.set(false)
        resetFeedbackStats()
        if (psEnabledFlag && _state.value.connected) restartPsEngine(reason)
    }

    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireWifiLock() {
        val ctx = appContext ?: return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RADE:HL2")
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RADE:HL2")
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

    /* ── Radio control state (C&C) ──────────────────────────── */

    @Volatile private var mox = false
    @Volatile private var freqHz = 14_236_000L
    @Volatile private var txDrive = 128                             // 0..255
    @Volatile private var lnaDb = 19                                // -12..+48
    @Volatile private var paEnabled = true                          // 5W PA vs low-power out
    private var ccRotation = 0
    private var ep2Seq = 0L

    /**
     * Discover the radio (broadcast when [host] is blank), start the I/Q
     * stream and the pacing loops. Returns true when connected; on failure
     * [state].error carries the reason.
     */
    suspend fun connect(host: String): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.connected || _state.value.connecting) return@withContext false
        connectionClosing = false
        _state.value = State(connecting = true, freqHz = freqHz)
        try {
            val sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 700
                // DSCP EF: ask the Wi-Fi driver/AP to treat the I/Q stream as
                // voice-class traffic (WMM AC_VO) — best-effort.
                try { trafficClass = 0xB8 } catch (_: Exception) {}
            }
            socket = sock
            acquireWifiLock()

            val target = if (host.isBlank()) InetAddress.getByName("255.255.255.255")
                         else InetAddress.getByName(host.trim())

            // Discovery: 0xEFFE 0x02 + 60 zero bytes. Reply: 0xEFFE, 0x02
            // (idle) / 0x03 (already streaming to another host), MAC[6],
            // gateware version, board id.
            val discovery = ByteArray(63).also {
                it[0] = 0xEF.toByte(); it[1] = 0xFE.toByte(); it[2] = 0x02
            }
            var reply: DatagramPacket? = null
            for (attempt in 1..4) {
                try {
                    sock.send(DatagramPacket(discovery, discovery.size, target, HPSDR_PORT))
                    val buf = ByteArray(128)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val d = pkt.data
                    if (pkt.length >= 11 && d[0] == 0xEF.toByte() && d[1] == 0xFE.toByte() &&
                        (d[2] == 0x02.toByte() || d[2] == 0x03.toByte())
                    ) { reply = pkt; break }
                } catch (_: SocketTimeoutException) {
                    Log.i(TAG, "Discovery attempt $attempt: no reply")
                }
            }
            if (reply == null) return@withContext fail("Radio not found (discovery timed out)")
            val d = reply.data
            if (d[2] == 0x03.toByte()) {
                return@withContext fail("Radio is busy (already streaming to another host)")
            }
            val mac = (3..8).joinToString(":") { String.format("%02X", d[it]) }
            val gateware = d[9].toInt() and 0xFF
            val boardId = d[10].toInt() and 0xFF
            radioAddr = InetSocketAddress(reply.address, HPSDR_PORT)
            val name = "Hermes-Lite 2 (gw $gateware, $mac)"
            Log.i(TAG, "Discovered board=$boardId $name at ${reply.address.hostAddress}")

            sock.soTimeout = 400

            // Prime every C&C address (speed/duplex/NRX, freqs, drive, LNA,
            // PS + TX-feedback gain, FIFO config) before starting the stream.
            repeat(4) { sendEp2() }
            sendStartStop(start = true)

            _state.value = State(
                connected = true, deviceName = name, freqHz = freqHz, error = ""
            )

            if (psEnabledFlag) psEngineCreate()

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            connScope = scope
            scope.launch { readerLoop(sock) }
            startPacer()
            Log.i(TAG, "TX FIFO config: latency=${TX_BUFFER_LATENCY_MS}ms hang=${PTT_HANG_MS}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            fail("Connect failed: ${e.message}")
        }
    }

    private fun fail(msg: String): Boolean {
        connectionClosing = true
        releaseWifiLock()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        radioAddr = null
        _state.value = State(error = msg, freqHz = freqHz)
        return false
    }

    fun disconnect() {
        connectionClosing = true
        mox = false
        // The stop command must not run on the caller's thread — the
        // Disconnect button invokes this on MAIN, where a UDP send throws
        // NetworkOnMainThreadException and the radio then keeps streaming
        // until its own watchdog fires (seen in a tester's log).
        if (socket != null && radioAddr != null) {
            val t = kotlin.concurrent.thread(name = "hl2-stop") {
                try {
                    sendStartStop(start = false)
                    sendStartStop(start = false)
                } catch (_: Exception) {}
            }
            try { t.join(300) } catch (_: InterruptedException) {}
        }
        stopPacer()
        connScope?.cancel()
        connScope = null
        psEngineDestroy()
        synchronized(psControlLock) {
            psFeedbackController = Hl2PureSignalFeedbackController()
            psFeedbackGainDb = psFeedbackController.gainDb
            psControlStatus = "IDLE"
        }
        releaseWifiLock()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        radioAddr = null
        onAudioPcm = null
        synchronized(txIqLock) { txIqCount = 0; txIqRead = 0; txIqWrite = 0 }
        ep6LastSeq = -1L
        _state.value = State(freqHz = freqHz)
        Log.i(TAG, "Disconnected")
    }

    /* ── Control API ────────────────────────────────────────── */

    fun setFrequency(hz: Long) {
        val next = hz.coerceIn(10_000L, 60_000_000L)
        if (next == freqHz) return
        freqHz = next
        invalidatePsOperatingPoint("frequency changed")
        _state.value = _state.value.copy(freqHz = freqHz)
        Log.i(TAG, "Frequency set: $freqHz Hz")
    }

    /** Key/unkey. The MOX bit rides every EP2 frame; I/Q is zero when idle. */
    fun setPtt(on: Boolean) {
        mox = on
        // Drop any stale TX waveform so the next over starts clean.
        synchronized(txIqLock) { txIqCount = 0; txIqRead = 0; txIqWrite = 0 }
        if (psEnabledFlag) {
            if (on) {
                // Drop a partial EP6 pair block from the previous over.
                psBlockAssembler.reset()
                resetFeedbackStats()
                psAdcOverflowInterval.set(false)
                synchronized(psControlLock) {
                    psFeedbackController.onPttStarted(SystemClock.elapsedRealtime())
                    psControlStatus = "PREFLIGHT"
                }
                // Hold native collection off for a full clean feedback
                // interval. The controller starts one manual calibration.
                if (psEngineUp) PureSignalBridge.psSetMox(false)
            } else {
                // Keep an accepted correction across overs, as upstream WDSP
                // clients do. Only MOX/calibration collection stops here.
                // Resetting correction requires paired psFeed+psApply traffic;
                // the former apply-only drain could never initiate ramp-out.
                if (psEngineUp) PureSignalBridge.psSetMox(false)
                psBlockAssembler.reset()
                resetFeedbackStats()
                synchronized(psControlLock) {
                    psFeedbackController.onPttStopped()
                    psControlStatus = "IDLE"
                }
            }
        }
        _state.value = _state.value.copy(ptt = on)
        Log.i(TAG, "PTT ${if (on) "ON" else "OFF"}")
    }

    /** Hardware TX drive level 0..255 (addr 0x09). Zero = keyed but no RF. */
    fun setDrive(v: Int) {
        val next = v.coerceIn(0, 255)
        if (next == txDrive) return
        txDrive = next
        invalidatePsOperatingPoint("drive changed")
        Log.i(TAG, "TX drive set: $txDrive/255")
    }

    /** RX LNA gain in dB, -12..+48 (HL2 addr 0x0A). */
    fun setLnaDb(v: Int) {
        lnaDb = v.coerceIn(-12, 48)
        Log.i(TAG, "RX LNA gain set: $lnaDb dB")
    }

    /**
     * Select the 5 W power-amplifier output (addr 0x09 bit 19). The HL2 has
     * TWO RF connectors: with the PA off (gateware default) TX leaves the
     * low-power (~10 mW) jack — "keys but no power on the antenna". Off keeps
     * the low-power path and additionally parks the T/R relay on RX (bit 18)
     * so the main antenna keeps receiving.
     */
    fun setPaEnabled(v: Boolean) {
        if (paEnabled == v) return
        paEnabled = v
        invalidatePsOperatingPoint("PA route changed")
        Log.i(TAG, "PA ${if (v) "ENABLED (5W output)" else "disabled (low-power output)"}")
    }

    /* ── Protocol: control packets ──────────────────────────── */

    private fun sendStartStop(start: Boolean) {
        val sock = socket ?: return
        val addr = radioAddr ?: return
        val cmd = ByteArray(64).also {
            it[0] = 0xEF.toByte(); it[1] = 0xFE.toByte(); it[2] = 0x04
            it[3] = if (start) 0x01 else 0x00
        }
        synchronized(sendLock) {
            try { sock.send(DatagramPacket(cmd, cmd.size, addr)) } catch (e: Exception) {
                Log.w(TAG, "start/stop send failed", e)
            }
        }
    }

    /* ── Protocol: EP6 receive (I/Q from radio) ─────────────── */

    private suspend fun readerLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var streamingLogged = false
        while (currentScope()?.isActive == true) {
            try {
                pkt.setData(buf, 0, buf.size)
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                if (_state.value.streaming &&
                    System.nanoTime() - lastEp6Nanos > 2_000_000_000L
                ) {
                    _state.value = _state.value.copy(streaming = false)
                    streamingLogged = false
                    Log.w(TAG, "EP6 stream stalled")
                }
                continue
            } catch (_: Exception) {
                break  // socket closed
            }
            val n = pkt.length
            if (n >= 1032 && buf[0] == 0xEF.toByte() && buf[1] == 0xFE.toByte() &&
                buf[2] == 0x01.toByte() && buf[3] == 0x06.toByte()
            ) {
                lastEp6Nanos = System.nanoTime()
                if (!streamingLogged) {
                    streamingLogged = true
                    _state.value = _state.value.copy(streaming = true)
                    Log.i(TAG, "EP6 I/Q stream up")
                }
                ep6RecvInterval++
                val seq = ((buf[4].toLong() and 0xFF) shl 24) or
                    ((buf[5].toLong() and 0xFF) shl 16) or
                    ((buf[6].toLong() and 0xFF) shl 8) or
                    (buf[7].toLong() and 0xFF)
                if (ep6LastSeq >= 0 && seq > ep6LastSeq + 1) {
                    ep6GapInterval += (seq - ep6LastSeq - 1).toInt()
                }
                ep6LastSeq = seq
                parseUsbFrame(buf, 8)
                parseUsbFrame(buf, 520)
                // Radio-clocked pacing while idle. With 2 receivers EP6 runs
                // at ~667 pkt/s — answering every second packet keeps the
                // idle upstream at the 1-RX level while still refreshing C&C
                // hundreds of times per second. During MOX the pacer loop
                // self-clocks EP2 instead.
                if (!mox) {
                    ep6Toggle = !ep6Toggle
                    if (ep6Toggle) sendEp2()
                }
            }
        }
    }

    private fun currentScope(): CoroutineScope? = connScope

    private fun s24(buf: ByteArray, p: Int): Float {
        val v = ((buf[p].toInt() and 0xFF) shl 16) or
            ((buf[p + 1].toInt() and 0xFF) shl 8) or
            (buf[p + 2].toInt() and 0xFF)
        return (if (v >= 0x800000) v - 0x1000000 else v) / 8388608.0f
    }

    private fun parseUsbFrame(buf: ByteArray, off: Int) {
        if (buf[off] != 0x7F.toByte() || buf[off + 1] != 0x7F.toByte() ||
            buf[off + 2] != 0x7F.toByte()
        ) return
        parseRadioCc(buf, off + 3)
        val keyed = mox
        val psActive = keyed && psEnabledFlag && psEngineUp
        var p = off + 8
        for (s in 0 until RX_SAMPLES_PER_FRAME) {
            val i1 = s24(buf, p)
            val q1 = s24(buf, p + 3)
            val i2 = s24(buf, p + 6)
            val q2 = s24(buf, p + 9)
            if (keyed) {
                // PureSignal phase 1: while transmitting, measure BOTH
                // receivers to identify which one the gateware feeds with the
                // TX sample (the demod chain is idle — RX is torn down in TX).
                synchronized(fbStatsLock) {
                    fbSumSq1 += (i1 * i1 + q1 * q1).toDouble()
                    fbSumSq2 += (i2 * i2 + q2 * q2).toDouble()
                    val m1 = maxOf(abs(i1), abs(q1))
                    val m2 = maxOf(abs(i2), abs(q2))
                    if (m1 > fbPeak1) fbPeak1 = m1
                    if (m2 > fbPeak2) fbPeak2 = m2
                    fbCount++
                }
                // PureSignal phase 2b: HL2 RX2 is the internal TX/DAC
                // reference; RX1 is the physical ADC/PA feedback.
                if (psActive) {
                    psBlockAssembler.push(
                        rx1FeedbackI = i1,
                        rx1FeedbackQ = q1,
                        rx2ReferenceI = i2,
                        rx2ReferenceQ = q2
                    )
                }
            } else {
                demodPush(i1, q1)
            }
            p += RX_GROUP_BYTES
        }
    }

    /**
     * From-radio C&C (addr = C0[7:3]). Kept as raw ADC units — enough to tell
     * "the radio reports forward power" from "the radio emits nothing", which
     * is the decisive TX diagnostic.
     */
    private fun parseRadioCc(buf: ByteArray, off: Int) {
        val c0 = buf[off].toInt() and 0xFF
        val c1 = buf[off + 1].toInt() and 0xFF
        val c2 = buf[off + 2].toInt() and 0xFF
        val c3 = buf[off + 3].toInt() and 0xFF
        val c4 = buf[off + 4].toInt() and 0xFF
        when (c0 shr 3) {
            0 -> {
                val clipped = (c1 and 0x01) != 0
                if (clipped && mox && psEnabledFlag) {
                    psAdcOverflowInterval.set(true)
                }
            }
            1 -> {
                radioTemp = (c1 shl 8) or c2
                radioFwdPower = (c3 shl 8) or c4
            }
            2 -> {
                radioRevPower = (c1 shl 8) or c2
                radioPaCurrent = (c3 shl 8) or c4
            }
        }
    }

    /* ── Protocol: EP2 send (C&C + TX I/Q to radio) ─────────── */

    private val ep2Buf = ByteArray(1032)

    private fun sendEp2() {
        val sock = socket ?: return
        val addr = radioAddr ?: return
        synchronized(sendLock) {
            ep2Buf[0] = 0xEF.toByte(); ep2Buf[1] = 0xFE.toByte()
            ep2Buf[2] = 0x01; ep2Buf[3] = 0x02
            ep2Buf[4] = (ep2Seq ushr 24).toByte()
            ep2Buf[5] = (ep2Seq ushr 16).toByte()
            ep2Buf[6] = (ep2Seq ushr 8).toByte()
            ep2Buf[7] = ep2Seq.toByte()
            ep2Seq++
            fillUsbFrame(ep2Buf, 8)
            fillUsbFrame(ep2Buf, 520)
            try {
                sock.send(DatagramPacket(ep2Buf, ep2Buf.size, addr))
                lastEp2Nanos = System.nanoTime()
                ep2SentInterval++
            } catch (e: Exception) {
                Log.w(TAG, "EP2 send failed", e)
            }
        }
    }

    private fun fillUsbFrame(buf: ByteArray, off: Int) {
        buf[off] = 0x7F; buf[off + 1] = 0x7F; buf[off + 2] = 0x7F
        writeCc(buf, off + 3)
        var p = off + 8
        val keyed = mox
        for (s in 0 until SAMPLES_PER_FRAME) {
            // L/R "radio audio" — HL2 has no speaker; always zero.
            buf[p] = 0; buf[p + 1] = 0; buf[p + 2] = 0; buf[p + 3] = 0
            var i16 = 0
            var q16 = 0
            if (keyed) {
                var underrun = false
                synchronized(txIqLock) {
                    if (txIqCount >= 2) {
                        i16 = txIqRing[txIqRead].toInt()
                        q16 = txIqRing[txIqRead + 1].toInt()
                        txIqRead = (txIqRead + 2) % txIqRing.size
                        txIqCount -= 2
                    } else {
                        underrun = true
                    }
                }
                if (underrun) txIqUnderrunInterval++
            }
            buf[p + 4] = (i16 shr 8).toByte(); buf[p + 5] = i16.toByte()
            buf[p + 6] = (q16 shr 8).toByte(); buf[p + 7] = q16.toByte()
            p += 8
        }
    }

    /**
     * One C&C group per USB frame, rotating so every register refreshes
     * ~every 6.6 ms. C0 = (addr << 1) | MOX.
     */
    private fun writeCc(buf: ByteArray, off: Int) {
        val moxBit = if (mox) 1 else 0
        buf[off + 1] = 0; buf[off + 2] = 0; buf[off + 3] = 0; buf[off + 4] = 0
        when (ccRotation) {
            0 -> {  // addr 0x00: speed 48 kHz, filter-board OC bits, duplex, NRX
                buf[off] = moxBit.toByte()
                buf[off + 1] = 0x00                     // 48 kHz
                // OC outputs C2[7:1]: the gateware forwards these to the
                // N2ADR filter board over I2C (bits 6:0 of the data byte).
                // Without them no low-pass filter is selected and the TX
                // path through the board is open — keys but no RF.
                buf[off + 2] = (filterOcBits(freqHz) shl 1).toByte()
                // bit2 duplex + bits[6:3] = NRX-1 (RX2 = PS TX-DAC reference)
                buf[off + 4] = (0x04 or ((NUM_RX - 1) shl 3)).toByte()
            }
            1 -> {  // addr 0x01: TX NCO frequency
                buf[off] = ((0x01 shl 1) or moxBit).toByte()
                putFreq(buf, off + 1, freqHz)
            }
            2 -> {  // addr 0x02: RX1 NCO frequency
                buf[off] = ((0x02 shl 1) or moxBit).toByte()
                putFreq(buf, off + 1, freqHz)
            }
            3 -> {  // addr 0x03: RX2 NCO frequency — the PureSignal TX-DAC
                    // reference receiver tracks the TX frequency
                buf[off] = ((0x03 shl 1) or moxBit).toByte()
                putFreq(buf, off + 1, freqHz)
            }
            4 -> {  // addr 0x09: TX drive level + PA routing
                buf[off] = ((0x09 shl 1) or moxBit).toByte()
                buf[off + 1] = txDrive.toByte()
                // bit19 (C2[3]): PA on → TX leaves the 5 W antenna jack.
                // With the PA off, bit18 (C2[2]) parks the T/R relay on RX so
                // the main antenna keeps receiving while the low-power jack
                // carries TX.
                buf[off + 2] = if (paEnabled) 0x08 else 0x04
            }
            5 -> {  // addr 0x0A: PS enable (bit22 = C2[6]) + HL2 LNA gain
                buf[off] = ((0x0A shl 1) or moxBit).toByte()
                buf[off + 2] = 0x40                     // PureSignal feedback routing on
                buf[off + 4] = (0x40 or ((lnaDb + 12) and 0x3F)).toByte()
            }
            6 -> {  // addr 0x0E: LNA gain during TX (feedback level), bit15 enables
                buf[off] = ((0x0E shl 1) or moxBit).toByte()
                // C3 bit7 enables TX feedback gain and bit6 selects the HL2
                // extended -12..+48 dB code. Without bit6, code zero is the
                // legacy gain code 32 (about +20 dB), not -12 dB.
                buf[off + 3] = encodeHl2TxFeedbackGain(psFeedbackGainDb).toByte()
            }
            else -> {  // addr 0x17: HL2 PTT hang (C3) + TX buffer latency (C4)
                buf[off] = ((0x17 shl 1) or moxBit).toByte()
                buf[off + 3] = PTT_HANG_MS.toByte()
                buf[off + 4] = TX_BUFFER_LATENCY_MS.toByte()
            }
        }
        ccRotation = (ccRotation + 1) % 8
    }

    /**
     * N2ADR filter-board byte (one-hot, per the board's MCP23008 mapping):
     * bits 0..5 = 160 / 80 / 60-40 / 30-20 / 17-15 / 12-10 m low-pass
     * filters, bit 6 = 3 MHz receive high-pass (used on every band except
     * 160 m; the board hardware switches it out on TX automatically).
     */
    private fun filterOcBits(hz: Long): Int {
        val lpf = when {
            hz <= 2_300_000L -> 0x01
            hz <= 4_700_000L -> 0x02
            hz <= 7_800_000L -> 0x04
            hz <= 15_000_000L -> 0x08
            hz <= 22_000_000L -> 0x10
            else -> 0x20
        }
        return if (lpf == 0x01) lpf else lpf or 0x40
    }

    private fun putFreq(buf: ByteArray, off: Int, hz: Long) {
        buf[off] = (hz ushr 24).toByte()
        buf[off + 1] = (hz ushr 16).toByte()
        buf[off + 2] = (hz ushr 8).toByte()
        buf[off + 3] = hz.toByte()
    }

    /* ── EP2 pacer thread ───────────────────────────────────── */

    private var pacerThread: Thread? = null
    @Volatile private var pacerRun = false

    private fun startPacer() {
        pacerRun = true
        pacerThread = kotlin.concurrent.thread(name = "hl2-pacer") {
            // Coroutine delay() on Dispatchers.IO jitters by many ms under
            // load; a dedicated audio-priority thread with parkNanos keeps the
            // 2.625 ms packet cadence tight enough for the radio's TX FIFO.
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )
            } catch (_: Exception) {}
            pacerLoop()
        }
    }

    private fun stopPacer() {
        pacerRun = false
        pacerThread?.interrupt()
        try { pacerThread?.join(300) } catch (_: InterruptedException) {}
        pacerThread = null
    }

    /**
     * While keyed (MOX) this self-clocks EP2 at exactly 48 kHz with absolute
     * deadlines — TX must never depend on the radio's RX stream, which some
     * gateware stops while transmitting. On a stall it CATCHES UP (refilling
     * exactly what the radio's FIFO drained — safe now that addr 0x17 primes
     * a 40 ms radio-side buffer) and only resyncs beyond 100 ms, where the
     * over is audibly broken anyway. While idle it is only a safety net: the
     * reader loop paces EP2 1:1 off EP6, and this thread just keeps C&C alive
     * when the stream is not flowing (pre-stream, stall).
     *
     * Once per second while keyed it logs TX health — app side (packets sent,
     * I/Q ring level, underruns, drive, frequency) and radio side (EP6 rate,
     * sequence gaps, forward/reverse power, PA current, ADC overflow) — so a
     * single in-app log capture pinpoints where a TX failure lives.
     */
    private fun updatePureSignalController(
        info: PsInfoSnapshot,
        feedbackRatioDb: Double,
        overflowSeen: Boolean
    ) {
        val decision = synchronized(psControlLock) {
            psFeedbackController.onFeedbackInterval(
                info = info,
                feedbackRatioDb = feedbackRatioDb,
                overflowSeen = overflowSeen,
                nowMs = SystemClock.elapsedRealtime()
            ).also {
                psFeedbackGainDb = psFeedbackController.gainDb
            }
        }

        when (decision) {
            Hl2PureSignalFeedbackController.Decision.Wait -> Unit
            Hl2PureSignalFeedbackController.Decision.StartSingleCalibration -> {
                if (psEngineUp) {
                    PureSignalBridge.psStartSingleCalibration()
                    psControlStatus = "CALIBRATING"
                    Log.i(TAG, "PureSignal controller: one-shot calibration started " +
                        "gain=${psFeedbackGainDb}dB")
                } else {
                    // Engine went down between the gate check and here; the
                    // solver never saw the request, so don't sit waiting on
                    // an attempt counter that cannot advance.
                    synchronized(psControlLock) {
                        psFeedbackController.onCalibrationLaunchFailed()
                    }
                }
            }
            is Hl2PureSignalFeedbackController.Decision.ApplyGain -> {
                psControlStatus = "PREFLIGHT"
                Log.w(TAG, "PureSignal controller: ${decision.reason} -> " +
                    "gain=${decision.gainDb}dB ceiling=${decision.ceilingDb}dB")
                restartPsEngine("${decision.reason}, gain=${decision.gainDb}dB")
            }
            is Hl2PureSignalFeedbackController.Decision.Complete -> {
                psControlStatus = "LOCKED"
                if (psEngineUp) PureSignalBridge.psSetMox(false)
                Log.i(TAG, "PureSignal controller: LOCKED (${decision.reason}) " +
                    "gain=${psFeedbackGainDb}dB")
                logPsModelSummary()
            }
            is Hl2PureSignalFeedbackController.Decision.StopRetry -> {
                psControlStatus = "FAILED:${decision.reason}"
                if (psEngineUp) PureSignalBridge.psSetMox(false)
                // A timeout or unusable accepted candidate must not be able
                // to remain active or swap in after the app stops retrying.
                restartPsEngine("controller stopped: ${decision.reason}")
                Log.w(TAG, "PureSignal controller stopped for this PTT: ${decision.reason}")
            }
        }
    }

    /**
     * One-shot dump of the accepted correction model. A single line answers
     * the decisive hardware question: did the solver see a real PA? A
     * compressing PA reads as ym rising toward the top bins (AM/AM > 0 dB)
     * and a bent phase curve (AM/PM span). A near-flat model despite known
     * PA compression means the feedback tap does not contain the PA's
     * nonlinearity — a topology problem no software can correct.
     */
    private fun logPsModelSummary() {
        if (!psEngineUp) return
        val m = FloatArray(32)
        PureSignalBridge.psGetModel(m)
        var lo = -1
        var hi = -1
        for (k in 0 until 16) {
            if (m[k] > 0f) {
                if (lo < 0) lo = k
                hi = k
            }
        }
        if (lo < 0 || hi <= lo) {
            Log.i(TAG, "PS model: no fitted bins")
            return
        }
        var phMin = Float.MAX_VALUE
        var phMax = -Float.MAX_VALUE
        for (k in lo..hi) {
            val ph = m[16 + k]
            if (ph < phMin) phMin = ph
            if (ph > phMax) phMax = ph
        }
        val amAmDb = 20.0 * log10(m[hi].toDouble() / m[lo].toDouble())
        Log.i(
            TAG,
            "PS model: bins $lo..$hi ym %.3f->%.3f (AM/AM %.2f dB) AM/PM span %.1f deg"
                .format(m[lo], m[hi], amAmDb, phMax - phMin)
        )
        Log.i(TAG, "PS model ym: " +
            (0 until 16).joinToString(",") { "%.3f".format(m[it]) })
        Log.i(TAG, "PS model ph: " +
            (0 until 16).joinToString(",") { "%.1f".format(m[16 + it]) })
    }

    private fun pacerLoop() {
        var next = System.nanoTime()
        var wasMox = false
        var statNs = System.nanoTime()
        while (pacerRun) {
            if (mox) {
                if (!wasMox) {
                    wasMox = true
                    next = System.nanoTime()
                    statNs = next
                    ep2SentInterval = 0
                    txIqUnderrunInterval = 0
                    ep6RecvInterval = 0
                    ep6GapInterval = 0
                }
                val now = System.nanoTime()
                if (next < now - 100_000_000L) {
                    Log.w(TAG, "TX pacer resync (fell ${(now - next) / 1_000_000} ms behind)")
                    next = now
                }
                while (next <= System.nanoTime()) {
                    sendEp2()
                    next += PACKET_NS
                }
                if (System.nanoTime() - statNs >= 1_000_000_000L) {
                    statNs = System.nanoTime()
                    val ringLevel = synchronized(txIqLock) { txIqCount }
                    // PureSignal levels (dBFS): RX1 is physical PA feedback;
                    // RX2 is the gateware's internal TX/DAC reference.
                    var fb1 = -120.0
                    var fb2 = -120.0
                    var fbPk1 = -120.0
                    var fbPk2 = -120.0
                    var hadFbSamples = false
                    synchronized(fbStatsLock) {
                        if (fbCount > 0) {
                            hadFbSamples = true
                            fb1 = 10.0 * log10(fbSumSq1 / fbCount + 1e-12)
                            fb2 = 10.0 * log10(fbSumSq2 / fbCount + 1e-12)
                            fbPk1 = 20.0 * log10(fbPeak1 + 1e-6)
                            fbPk2 = 20.0 * log10(fbPeak2 + 1e-6)
                        }
                        fbSumSq1 = 0.0; fbSumSq2 = 0.0
                        fbPeak1 = 0f; fbPeak2 = 0f
                        fbCount = 0
                    }
                    var overflowSeen = psAdcOverflowInterval.get()
                    // WDSP calibration health while PureSignal is enabled.
                    val psDiag = if (psEnabledFlag) {
                        PureSignalBridge.psGetInfo(psInfoScratch)
                        val snapshot = PsInfoSnapshot.from(psInfoScratch)
                        if (!psRestartInProgress.get() && psEngineUp) {
                            // Consume clips only when the controller can act;
                            // keep them latched throughout native restarts.
                            overflowSeen = psAdcOverflowInterval.getAndSet(false)
                            updatePureSignalController(
                                info = snapshot,
                                feedbackRatioDb = if (hadFbSamples) fb1 - fb2 else Double.NaN,
                                overflowSeen = overflowSeen
                            )
                        }
                        val ceiling = synchronized(psControlLock) {
                            psFeedbackController.gainCeilingDb
                        }
                        // Envelope domain health: peak pre-limiter envelope
                        // (should sit just at/below 1.00) and how many
                        // samples the radial limiter touched this interval.
                        val envPeak = psEnvPeakInterval
                        val envLimited = psEnvLimitedInterval
                        psEnvPeakInterval = 0f
                        psEnvLimitedInterval = 0
                        " ps=[try=${snapshot.attemptCounter} ok=${snapshot.acceptedCounter} " +
                            "rej=${snapshot.rejectedCounter} result=${snapshot.lastOutcome} " +
                            "fit=${snapshot.feedbackScaleFit}/${snapshot.magnitudeFit}/" +
                            "${snapshot.cosineFit}/${snapshot.sineFit} " +
                            "sane=0x${snapshot.solutionSanity.toString(16)} " +
                            "rxsane=0x${snapshot.feedbackFitSanity.toString(16)} " +
                            "dog=${snapshot.dogCounter} " +
                            "corr=${if (snapshot.correctionApplied) 1 else 0} " +
                            "state=${snapshot.state} fblvl=${snapshot.feedbackLevel} " +
                            "env=%.2f/%d ".format(envPeak, envLimited) +
                            "ctl=$psControlStatus ceil=${ceiling}dB]"
                    } else ""
                    Log.i(
                        TAG,
                        "TX health: ep2=${ep2SentInterval}/s iqRing=$ringLevel " +
                            "underrun=${txIqUnderrunInterval} drive=$txDrive freq=$freqHz " +
                            "pa=${if (paEnabled) 1 else 0} " +
                            "oc=0x%02X ".format(filterOcBits(freqHz)) +
                            "| radio: ep6=${ep6RecvInterval}/s gaps=${ep6GapInterval} " +
                            "fwd=$radioFwdPower rev=$radioRevPower paI=$radioPaCurrent " +
                            "temp=$radioTemp ovf=${if (overflowSeen) 1 else 0} " +
                            "| PS: fbLna=${psFeedbackGainDb}dB " +
                            "rx1Fb=%.1f/%.1f rx2Ref=%.1f/%.1f dBFS".format(
                                fb1, fbPk1, fb2, fbPk2
                            ) +
                            psDiag
                    )
                    ep2SentInterval = 0
                    txIqUnderrunInterval = 0
                    ep6RecvInterval = 0
                    ep6GapInterval = 0
                }
                val sleepNs = next - System.nanoTime()
                if (sleepNs > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(sleepNs)
                }
            } else {
                wasMox = false
                if (System.nanoTime() - lastEp6Nanos > 100_000_000L &&
                    System.nanoTime() - lastEp2Nanos > 200_000_000L
                ) {
                    sendEp2()
                }
                java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L)
            }
        }
    }

    /* ══ SSB DSP ══════════════════════════════════════════════
     * All filters are designed once at init (Blackman-Harris windowed sinc).
     */

    private fun windowBH(n: Int, taps: Int): Double {
        val x = 2.0 * PI * n / (taps - 1)
        return 0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
    }

    /** Unity-DC-gain lowpass prototype. */
    private fun designLowpass(taps: Int, cutoffHz: Double, fs: Double): FloatArray {
        val h = DoubleArray(taps)
        val fc = cutoffHz / fs
        val m = (taps - 1) / 2.0
        for (n in 0 until taps) {
            val k = n - m
            h[n] = (if (abs(k) < 1e-9) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)) *
                windowBH(n, taps)
        }
        val sum = h.sum()
        return FloatArray(taps) { (h[it] / sum).toFloat() }
    }

    // RX/TX shared: analytic USB bandpass at 8 kHz — lowpass ±1200 Hz
    // modulated up to +1500 Hz passes only +300..+2700 Hz.
    private val anRe = FloatArray(ANALYTIC_TAPS)
    private val anIm = FloatArray(ANALYTIC_TAPS)

    // RX: 48k→8k complex decimator
    private val decimLpf = designLowpass(DECIM_TAPS, 3400.0, IQ_RATE.toDouble())
    private val decimHistI = FloatArray(DECIM_TAPS)
    private val decimHistQ = FloatArray(DECIM_TAPS)
    private var decimPos = 0
    private var decimPhase = 0

    // RX: analytic filter delay lines (complex input)
    private val rxAnHistI = FloatArray(ANALYTIC_TAPS)
    private val rxAnHistQ = FloatArray(ANALYTIC_TAPS)
    private var rxAnPos = 0

    // RX: block assembly + AGC
    private val rxBlock = FloatArray(TX_FRAME_SAMPLES)
    private var rxBlockPos = 0
    private var agcGain = 200f

    // TX: analytic filter delay line (real input) + ×6 interpolator
    private val txAnHist = FloatArray(ANALYTIC_TAPS)
    private var txAnPos = 0
    private val interpPhases = Array(INTERP) { FloatArray(INTERP_TAPS_PER_PHASE) }
    private val txInterpHistI = FloatArray(INTERP_TAPS_PER_PHASE)
    private val txInterpHistQ = FloatArray(INTERP_TAPS_PER_PHASE)
    private var txInterpPos = 0

    // TX: reusable interpolated-output block, interleaved I,Q floats in the
    // analytic domain (one 20 ms frame = 960 complex samples = 1920 floats).
    // PureSignal's psApply runs on this block before int16 conversion.
    private var txBlock = FloatArray(TX_FRAME_SAMPLES * INTERP * 2)

    // TX I/Q ring: 1 s of 48 kHz interleaved I,Q written by sendAudioFrame,
    // drained by the EP2 builder; zeros on underrun.
    private val txIqRing = ShortArray(IQ_RATE * 2)
    private val txIqLock = Any()
    private var txIqRead = 0
    private var txIqWrite = 0
    private var txIqCount = 0

    init {
        val lp = designLowpass(ANALYTIC_TAPS, SSB_HALF_BW, AUDIO_RATE.toDouble())
        val m = (ANALYTIC_TAPS - 1) / 2.0
        for (n in 0 until ANALYTIC_TAPS) {
            val ph = 2.0 * PI * SSB_CENTER * (n - m) / AUDIO_RATE
            anRe[n] = (lp[n] * cos(ph)).toFloat()
            anIm[n] = (lp[n] * sin(ph)).toFloat()
        }
        // Interpolator: same prototype family at the output rate; each phase
        // normalized to unity sum so the interpolated amplitude is flat.
        val proto = designLowpass(INTERP * INTERP_TAPS_PER_PHASE, 3400.0, IQ_RATE.toDouble())
        for (p in 0 until INTERP) {
            var sum = 0.0
            for (k in 0 until INTERP_TAPS_PER_PHASE) sum += proto[p + k * INTERP].toDouble()
            val norm = if (abs(sum) > 1e-9) 1.0 / sum else 1.0
            for (k in 0 until INTERP_TAPS_PER_PHASE) {
                interpPhases[p][k] = (proto[p + k * INTERP] * norm).toFloat()
            }
        }
    }

    /** One 48 kHz complex sample in → maybe one 8 kHz audio sample out. */
    private fun demodPush(fi: Float, fq: Float) {
        decimHistI[decimPos] = fi
        decimHistQ[decimPos] = fq
        decimPos = (decimPos + 1) % DECIM_TAPS
        if (++decimPhase < INTERP) return
        decimPhase = 0

        var di = 0f
        var dq = 0f
        var idx = decimPos
        for (k in 0 until DECIM_TAPS) {
            idx--
            if (idx < 0) idx = DECIM_TAPS - 1
            di += decimHistI[idx] * decimLpf[k]
            dq += decimHistQ[idx] * decimLpf[k]
        }

        rxAnHistI[rxAnPos] = di
        rxAnHistQ[rxAnPos] = dq
        rxAnPos = (rxAnPos + 1) % ANALYTIC_TAPS
        var audio = 0f
        idx = rxAnPos
        for (k in 0 until ANALYTIC_TAPS) {
            idx--
            if (idx < 0) idx = ANALYTIC_TAPS - 1
            // Re{ h * z }: only positive frequencies (the upper sideband) survive.
            audio += anRe[k] * rxAnHistI[idx] - anIm[k] * rxAnHistQ[idx]
        }

        rxBlock[rxBlockPos++] = audio
        if (rxBlockPos < TX_FRAME_SAMPLES) return
        rxBlockPos = 0
        emitRxBlock()
    }

    private fun emitRxBlock() {
        val sink = onAudioPcm ?: return
        var sumSq = 0f
        var peak = 0f
        for (x in rxBlock) {
            sumSq += x * x
            val a = abs(x)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / TX_FRAME_SAMPLES)
        if (rms > 1e-9f) {
            val desired = (AGC_TARGET_RMS / rms).coerceAtMost(AGC_MAX_GAIN)
            agcGain += (desired - agcGain) * AGC_SMOOTH
        }
        if (peak * agcGain > 0.95f) agcGain = 0.95f / peak   // fast attack, no clip
        val out = ShortArray(TX_FRAME_SAMPLES)
        for (n in 0 until TX_FRAME_SAMPLES) {
            val v = (rxBlock[n] * agcGain).coerceIn(-0.98f, 0.98f)
            out[n] = (v * 32767f).toInt().toShort()
        }
        sink(out)
    }

    /**
     * TX pump entry: 8 kHz RADE waveform → analytic → ×6 → [txBlock] →
     * (PureSignal predistortion when enabled) → int16 I/Q ring.
     *
     * PureSignal tap placement, replicated from Thetis/WDSP: in WDSP's TXA
     * chain xiqc (the corrector) runs in place on midbuff (TXA.c xtxa,
     * "PureSignal correction"), and the calibration reference handed to
     * pscc is the hardware-bound signal AFTER that correction — TXA.c
     * creates calcc with the inverse hardware-reference peak, and calcc's
     * math only measures the PA's inverse if the reference is the actual
     * corrected drive. HL2 returns that post-DAC reference as RX2, paired
     * in hardware with RX1 feedback, so no app-side reference history or
     * TX FIFO delay estimate belongs here.
     */
    override fun sendAudioFrame(pcm: ShortArray) {
        if (!isConnected) return
        val outSamples = pcm.size * INTERP
        if (txBlock.size < outSamples * 2) txBlock = FloatArray(outSamples * 2)
        val blk = txBlock
        var w = 0
        for (s in pcm) {
            val x = s / 32768f
            txAnHist[txAnPos] = x
            txAnPos = (txAnPos + 1) % ANALYTIC_TAPS
            var yi = 0f
            var yq = 0f
            var idx = txAnPos
            for (k in 0 until ANALYTIC_TAPS) {
                idx--
                if (idx < 0) idx = ANALYTIC_TAPS - 1
                yi += anRe[k] * txAnHist[idx]
                yq += anIm[k] * txAnHist[idx]
            }

            txInterpHistI[txInterpPos] = yi
            txInterpHistQ[txInterpPos] = yq
            txInterpPos = (txInterpPos + 1) % INTERP_TAPS_PER_PHASE
            for (p in 0 until INTERP) {
                var oi = 0f
                var oq = 0f
                var id2 = txInterpPos
                val ph = interpPhases[p]
                for (k in 0 until INTERP_TAPS_PER_PHASE) {
                    id2--
                    if (id2 < 0) id2 = INTERP_TAPS_PER_PHASE - 1
                    oi += txInterpHistI[id2] * ph[k]
                    oq += txInterpHistQ[id2] * ph[k]
                }
                blk[w++] = oi
                blk[w++] = oq
            }
        }

        if (psEnabledFlag && psEngineUp && mox) {
            // Normalize into WDSP's [0,1] envelope domain (see PS_TX_NORM)
            // and radially pin any residual envelope overshoot to exactly
            // 1.0 — scaling I and Q together preserves phase, unlike the
            // final per-component int16 clamp, and keeps the corrector and
            // CALCC inside their fitted domain. Then predistort and convert
            // with the compensated int16 scale; the radio returns the actual
            // post-DAC reference synchronously on RX2.
            var envPeak = psEnvPeakInterval
            var limited = psEnvLimitedInterval
            for (n in 0 until outSamples) {
                var i = blk[2 * n] * PS_TX_NORM
                var q = blk[2 * n + 1] * PS_TX_NORM
                val env = sqrt(i * i + q * q)
                if (env > envPeak) envPeak = env
                if (env > 1.0f) {
                    limited++
                    i /= env
                    q /= env
                }
                blk[2 * n] = i
                blk[2 * n + 1] = q
            }
            psEnvPeakInterval = envPeak
            psEnvLimitedInterval = limited
            PureSignalBridge.psApply(blk, outSamples)
            writeTxRing(blk, outSamples, PS_POST_SCALE)
        } else {
            // Numerically identical to the pre-PureSignal path: the same
            // oi,oq floats through the same single TX_IQ_SCALE multiply.
            writeTxRing(blk, outSamples, TX_IQ_SCALE)
        }
    }

    /** Scale [nSamples] complex float samples to int16 and push them into
     *  the EP2 TX ring (drop-on-full per pair, as before). */
    private fun writeTxRing(blk: FloatArray, nSamples: Int, scale: Float) {
        synchronized(txIqLock) {
            for (n in 0 until nSamples) {
                val i16 = (blk[2 * n] * scale).toInt().coerceIn(-32767, 32767).toShort()
                val q16 = (blk[2 * n + 1] * scale).toInt().coerceIn(-32767, 32767).toShort()
                if (txIqCount <= txIqRing.size - 2) {
                    txIqRing[txIqWrite] = i16
                    txIqRing[txIqWrite + 1] = q16
                    txIqWrite = (txIqWrite + 2) % txIqRing.size
                    txIqCount += 2
                }
            }
        }
    }
}

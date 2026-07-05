package yakumo2683.RADEdecode.network

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
 * Pacing: protocol 1 is radio-clocked — one host EP2 packet is sent per
 * received EP6 packet (126 samples each way at 48 kHz). A fallback pacer
 * covers the pre-stream window and any RX stall so C&C (and TX I/Q, if
 * keyed) keep flowing.
 */
class HermesNetworkManager : NetworkAudioRig {

    companion object {
        private const val TAG = "HermesNet"
        const val HPSDR_PORT = 1024

        /** PCM rate exchanged with the native modem (factor-1 passthrough). */
        const val AUDIO_RATE = 8000
        /** 20 ms of 8 kHz PCM per TX pump tick. */
        const val TX_FRAME_SAMPLES = 160

        private const val IQ_RATE = 48000
        private const val INTERP = IQ_RATE / AUDIO_RATE            // 6
        private const val SAMPLES_PER_FRAME = 63                    // per 512-byte USB frame
        private const val SAMPLES_PER_PACKET = 2 * SAMPLES_PER_FRAME
        private const val PACKET_NS = SAMPLES_PER_PACKET * 1_000_000_000L / IQ_RATE  // 2.625 ms

        // SSB passband (Hz above the suppressed carrier / NCO)
        private const val SSB_CENTER = 1500.0
        private const val SSB_HALF_BW = 1200.0                      // 300..2700 Hz

        private const val DECIM_TAPS = 144                          // 24 per phase × 6
        private const val ANALYTIC_TAPS = 129
        private const val INTERP_TAPS_PER_PHASE = 24

        // RX AGC: I/Q off-air levels are tiny and LNA-dependent; normalize
        // toward a healthy modem drive. Native then applies inputGain_ × 0.15.
        private const val AGC_TARGET_RMS = 0.22f
        private const val AGC_MAX_GAIN = 2000f                      // +66 dB
        private const val AGC_SMOOTH = 0.1f

        // Analytic TX of a real signal halves the amplitude; ×2 restores it,
        // ×0.8 leaves envelope headroom over the clamped RADE waveform.
        private const val TX_IQ_SCALE = 2.0f * 0.8f * 32767.0f
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
    private val sendLock = Any()
    @Volatile private var lastEp6Nanos = 0L
    @Volatile private var lastEp2Nanos = 0L

    /* TX health counters (reset each 1 s stats interval by the pacer) */
    @Volatile private var ep2SentInterval = 0
    @Volatile private var txIqUnderrunInterval = 0

    /* ── Radio control state (C&C) ──────────────────────────── */

    @Volatile private var mox = false
    @Volatile private var freqHz = 14_236_000L
    @Volatile private var txDrive = 128                             // 0..255
    @Volatile private var lnaDb = 19                                // -12..+48
    private var ccRotation = 0
    private var ep2Seq = 0L

    /**
     * Discover the radio (broadcast when [host] is blank), start the I/Q
     * stream and the pacing loops. Returns true when connected; on failure
     * [state].error carries the reason.
     */
    suspend fun connect(host: String): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.connected || _state.value.connecting) return@withContext false
        _state.value = State(connecting = true, freqHz = freqHz)
        try {
            val sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 700
            }
            socket = sock

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

            // Prime every C&C address (speed/duplex, freqs, drive, LNA)
            // before starting the stream, then start EP6.
            repeat(3) { sendEp2() }
            sendStartStop(start = true)

            _state.value = State(
                connected = true, deviceName = name, freqHz = freqHz, error = ""
            )

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            connScope = scope
            scope.launch { readerLoop(sock) }
            scope.launch { fallbackPacer() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            fail("Connect failed: ${e.message}")
        }
    }

    private fun fail(msg: String): Boolean {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        radioAddr = null
        _state.value = State(error = msg, freqHz = freqHz)
        return false
    }

    fun disconnect() {
        mox = false
        try { sendStartStop(start = false); sendStartStop(start = false) } catch (_: Exception) {}
        connScope?.cancel()
        connScope = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        radioAddr = null
        onAudioPcm = null
        synchronized(txIqLock) { txIqCount = 0; txIqRead = 0; txIqWrite = 0 }
        _state.value = State(freqHz = freqHz)
        Log.i(TAG, "Disconnected")
    }

    /* ── Control API ────────────────────────────────────────── */

    fun setFrequency(hz: Long) {
        freqHz = hz.coerceIn(10_000L, 60_000_000L)
        _state.value = _state.value.copy(freqHz = freqHz)
        Log.i(TAG, "Frequency set: $freqHz Hz")
    }

    /** Key/unkey. The MOX bit rides every EP2 frame; I/Q is zero when idle. */
    fun setPtt(on: Boolean) {
        mox = on
        // Drop any stale TX waveform so the next over starts clean.
        synchronized(txIqLock) { txIqCount = 0; txIqRead = 0; txIqWrite = 0 }
        _state.value = _state.value.copy(ptt = on)
        Log.i(TAG, "PTT ${if (on) "ON" else "OFF"}")
    }

    /** Hardware TX drive level 0..255 (addr 0x09). Zero = keyed but no RF. */
    fun setDrive(v: Int) {
        txDrive = v.coerceIn(0, 255)
        Log.i(TAG, "TX drive set: $txDrive/255")
    }

    /** RX LNA gain in dB, -12..+48 (HL2 addr 0x0A). */
    fun setLnaDb(v: Int) {
        lnaDb = v.coerceIn(-12, 48)
        Log.i(TAG, "RX LNA gain set: $lnaDb dB")
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
                parseUsbFrame(buf, 8)
                parseUsbFrame(buf, 520)
                // Radio-clocked 1:1 pacing while idle: one EP2 per EP6. During
                // MOX the pacer loop self-clocks EP2 instead — some gateware
                // stops the RX stream while transmitting, and TX I/Q must not
                // die with it.
                if (!mox) sendEp2()
            }
        }
    }

    private fun currentScope(): CoroutineScope? = connScope

    private fun parseUsbFrame(buf: ByteArray, off: Int) {
        if (buf[off] != 0x7F.toByte() || buf[off + 1] != 0x7F.toByte() ||
            buf[off + 2] != 0x7F.toByte()
        ) return
        var p = off + 8
        for (s in 0 until SAMPLES_PER_FRAME) {
            val i24 = ((buf[p].toInt() and 0xFF) shl 16) or
                ((buf[p + 1].toInt() and 0xFF) shl 8) or
                (buf[p + 2].toInt() and 0xFF)
            val q24 = ((buf[p + 3].toInt() and 0xFF) shl 16) or
                ((buf[p + 4].toInt() and 0xFF) shl 8) or
                (buf[p + 5].toInt() and 0xFF)
            val fi = (if (i24 >= 0x800000) i24 - 0x1000000 else i24) / 8388608.0f
            val fq = (if (q24 >= 0x800000) q24 - 0x1000000 else q24) / 8388608.0f
            demodPush(fi, fq)
            p += 8
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
            0 -> {  // addr 0x00: speed 48 kHz, 1 receiver, duplex on
                buf[off] = moxBit.toByte()
                buf[off + 1] = 0x00                     // 48 kHz
                buf[off + 4] = 0x04                     // duplex — RX runs through TX
            }
            1 -> {  // addr 0x01: TX NCO frequency
                buf[off] = ((0x01 shl 1) or moxBit).toByte()
                putFreq(buf, off + 1, freqHz)
            }
            2 -> {  // addr 0x02: RX1 NCO frequency
                buf[off] = ((0x02 shl 1) or moxBit).toByte()
                putFreq(buf, off + 1, freqHz)
            }
            3 -> {  // addr 0x09: TX drive level
                buf[off] = ((0x09 shl 1) or moxBit).toByte()
                buf[off + 1] = txDrive.toByte()
            }
            else -> {  // addr 0x0A: HL2 LNA gain (bit6 = manual, value = dB + 12)
                buf[off] = ((0x0A shl 1) or moxBit).toByte()
                buf[off + 4] = (0x40 or ((lnaDb + 12) and 0x3F)).toByte()
            }
        }
        ccRotation = (ccRotation + 1) % 5
    }

    private fun putFreq(buf: ByteArray, off: Int, hz: Long) {
        buf[off] = (hz ushr 24).toByte()
        buf[off + 1] = (hz ushr 16).toByte()
        buf[off + 2] = (hz ushr 8).toByte()
        buf[off + 3] = hz.toByte()
    }

    /**
     * EP2 pacer. While keyed (MOX) it self-clocks EP2 at exactly 48 kHz with
     * absolute deadlines — TX must never depend on the radio's RX stream,
     * which some gateware stops while transmitting; a >40 ms slip (coarse
     * phone timers, GC) resyncs instead of firing a catch-up burst that would
     * overflow the radio's small TX FIFO. While idle it is only a safety net:
     * the reader loop paces EP2 1:1 off EP6, and this loop just keeps C&C
     * alive when the stream is not flowing (pre-stream, stall).
     *
     * Once per second while keyed it logs TX health — packets sent, I/Q ring
     * level, underrun samples (keyed but ring empty → zeros sent), drive and
     * frequency — so a single in-app log capture pinpoints whether missing RF
     * is "no I/Q produced", "drive at zero", or "packets not reaching the
     * radio".
     */
    private suspend fun fallbackPacer() {
        var next = System.nanoTime()
        var wasMox = false
        var statNs = System.nanoTime()
        while (currentScope()?.isActive == true) {
            if (mox) {
                if (!wasMox) {
                    wasMox = true
                    next = System.nanoTime()
                    statNs = next
                    ep2SentInterval = 0
                    txIqUnderrunInterval = 0
                }
                val now = System.nanoTime()
                if (next < now - 40_000_000L) {
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
                    Log.i(
                        TAG,
                        "TX health: ep2=${ep2SentInterval}/s iqRing=$ringLevel " +
                            "underrun=${txIqUnderrunInterval} drive=$txDrive freq=$freqHz"
                    )
                    ep2SentInterval = 0
                    txIqUnderrunInterval = 0
                }
                delay(2)
            } else {
                wasMox = false
                if (System.nanoTime() - lastEp6Nanos > 100_000_000L &&
                    System.nanoTime() - lastEp2Nanos > 200_000_000L
                ) {
                    sendEp2()
                }
                delay(20)
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

    /** TX pump entry: 8 kHz RADE waveform → analytic → ×6 → I/Q ring. */
    override fun sendAudioFrame(pcm: ShortArray) {
        if (!isConnected) return
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
                val i16 = (oi * TX_IQ_SCALE).toInt().coerceIn(-32767, 32767).toShort()
                val q16 = (oq * TX_IQ_SCALE).toInt().coerceIn(-32767, 32767).toShort()
                synchronized(txIqLock) {
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
}

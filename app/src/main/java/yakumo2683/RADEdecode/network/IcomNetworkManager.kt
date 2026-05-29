package yakumo2683.RADEdecode.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Icom RS-BA1 (LAN/WLAN) network rig control for radios with a built-in server
 * such as the IC-705, IC-9700, IC-7610.
 *
 * The radio does NOT speak the Hamlib rigctld protocol — it speaks Icom's own
 * UDP protocol on three ports: control (50001), CI-V serial (50002) and audio
 * (50003).  This class implements the control + CI-V serial streams, decapsulates
 * the CI-V byte stream, and feeds it through a local pty (icom_pty.c).  The
 * bundled rigctld is then pointed at that pty with `-m 3085`, so the entire
 * existing [RigController] / [RigctldProcess] stack works unchanged — only the
 * transport differs from the USB path.
 *
 * Phase 1: control only.  Audio (50003) still flows over USB.
 *
 * Protocol reference: github.com/nonoo/kappanhang, cross-checked against wfview.
 * The radio must have "Network control" enabled and a Network User name/password
 * configured in its menu (Set > Network).
 */
class IcomNetworkManager {

    companion object {
        private const val TAG = "IcomNetwork"

        const val DEFAULT_CONTROL_PORT = 50001
        private const val SERIAL_PORT = 50002
        private const val AUDIO_PORT = 50003

        /** Network audio sample rate (s16le mono). 48 kHz is the IC-705's native
         *  rate and what kappanhang/RS-BA1 use; the engine resamples 48↔8 kHz. */
        const val NET_AUDIO_RATE = 48000
        /** 20 ms TX frame: 48000 × 0.02 = 960 samples = 1920 bytes. */
        const val NET_AUDIO_FRAME_SAMPLES = 960
        private const val TX_BUF_MS = 150

        init { System.loadLibrary("rade_jni") }

        @JvmStatic external fun nativeIcomPtyOpen(): String?
        @JvmStatic external fun nativeIcomPtyWrite(data: ByteArray, len: Int): Int
        @JvmStatic external fun nativeIcomPtyRead(timeoutMs: Int): ByteArray?
        @JvmStatic external fun nativeIcomPtyClose()

        /** Icom username/password obfuscation ("passcode"), ported from kappanhang. */
        private val PASSCODE = IntArray(160).also { s ->
            val tbl = intArrayOf(
                0x47, 0x5d, 0x4c, 0x42, 0x66, 0x20, 0x23, 0x46, 0x4e, 0x57, 0x45, 0x3d,
                0x67, 0x76, 0x60, 0x41, 0x62, 0x39, 0x59, 0x2d, 0x68, 0x7e, 0x7c, 0x65,
                0x7d, 0x49, 0x29, 0x72, 0x73, 0x78, 0x21, 0x6e, 0x5a, 0x5e, 0x4a, 0x3e,
                0x71, 0x2c, 0x2a, 0x54, 0x3c, 0x3a, 0x63, 0x4f, 0x43, 0x75, 0x27, 0x79,
                0x5b, 0x35, 0x70, 0x48, 0x6b, 0x56, 0x6f, 0x34, 0x32, 0x6c, 0x30, 0x61,
                0x6d, 0x7b, 0x2f, 0x4b, 0x64, 0x38, 0x2b, 0x2e, 0x50, 0x40, 0x3f, 0x55,
                0x33, 0x37, 0x25, 0x77, 0x24, 0x26, 0x74, 0x6a, 0x28, 0x53, 0x4d, 0x69,
                0x22, 0x5c, 0x44, 0x31, 0x36, 0x58, 0x3b, 0x7a, 0x51, 0x5f, 0x52
            )
            // tbl[0] corresponds to char code 32, up to code 126.
            for (i in tbl.indices) s[32 + i] = tbl[i]
        }

        private fun passcode(str: String): ByteArray {
            val res = ByteArray(16)
            val n = minOf(str.length, 16)
            for (i in 0 until n) {
                var p = str[i].code + i
                if (p > 126) p = 32 + p % 127
                res[i] = (if (p in 0..159) PASSCODE[p] else 0).toByte()
            }
            return res
        }

        private fun u16le(r: ByteArray, off: Int) =
            (r[off].toInt() and 0xFF) or ((r[off + 1].toInt() and 0xFF) shl 8)

        private fun u32be(r: ByteArray, off: Int) =
            ((r[off].toInt() and 0xFF) shl 24) or ((r[off + 1].toInt() and 0xFF) shl 16) or
            ((r[off + 2].toInt() and 0xFF) shl 8) or (r[off + 3].toInt() and 0xFF)

        private fun ByteArray.putSid(off: Int, sid: Int) {
            this[off] = (sid ushr 24).toByte()
            this[off + 1] = (sid ushr 16).toByte()
            this[off + 2] = (sid ushr 8).toByte()
            this[off + 3] = sid.toByte()
        }

        private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

        private fun ByteArray.startsWith(p: ByteArray): Boolean {
            if (size < p.size) return false
            for (i in p.indices) if (this[i] != p[i]) return false
            return true
        }

        private fun hex(v: Int) = "0x%08X".format(v)
    }

    data class State(
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val audioConnected: Boolean = false,  // UDP 50003 audio stream up (full wireless)
        val deviceName: String = "",
        val error: String = ""
    )

    /** Callback for received, in-order audio PCM (int16 mono at [NET_AUDIO_RATE]).
     *  Set by AudioService; invoked on the audio stream's consume coroutine. */
    @Volatile var onAudioPcm: ((ShortArray) -> Unit)? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val isConnected: Boolean get() = _state.value.connected

    private var connScope: CoroutineScope? = null

    private var control: IcomStream? = null
    private var serial: IcomStream? = null
    private var audio: IcomStream? = null
    private var audioSendSeq = 0

    // Protocol state (control stream)
    private var username = ""
    private var password = ""
    private var authInnerSeq = 0
    private var authID = ByteArray(6)
    private var a8ReplyID = ByteArray(16)
    private var gotA8 = false
    private var authOk = false
    private var serialOpened = false
    private var serialInnerSeq = 0

    /* ───────────────────────── public API ───────────────────────── */

    /**
     * Connect to the radio and bring up the CI-V tunnel.
     * @return the pty slave path (e.g. "/dev/pts/3") to hand to rigctld, or "" on failure.
     */
    suspend fun connect(host: String, controlPort: Int, user: String, pass: String): String {
        disconnect()  // clean any previous session

        username = user
        password = pass
        authInnerSeq = 0
        authID = ByteArray(6)
        a8ReplyID = ByteArray(16)
        gotA8 = false
        authOk = false
        serialOpened = false
        serialInnerSeq = 0
        audioSendSeq = 0

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        connScope = scope
        _state.value = State(connecting = true)

        val result = CompletableDeferred<String>()

        try {
            val ctrl = IcomStream("control", host, controlPort, scope)
            control = ctrl
            if (!ctrl.open()) {
                fail("Control handshake failed — check the IP and that the radio is reachable")
                disconnect(); return ""
            }

            ctrl.sendTracked(buildLogin(ctrl))                       // authInnerSeq 0 → login
            val r60 = ctrl.expect(96, byteArrayOf(0x60, 0, 0, 0, 0, 0, 0x01, 0))
            if (r60 == null) {
                fail("No login reply — is 'Network control' ON and the control port correct?")
                disconnect(); return ""
            }
            if (r60.size >= 52 && r60.u(48) == 0xFF && r60.u(49) == 0xFF && r60.u(50) == 0xFF && r60.u(51) == 0xFE) {
                fail("Invalid username / password")
                disconnect(); return ""
            }
            System.arraycopy(r60, 26, authID, 0, 6)

            ctrl.startPing(firstSeq = 2)
            ctrl.sendTracked(buildAuth(ctrl, magic = 0x02))          // first auth
            ctrl.startIdle()
            ctrl.sendTracked(buildAuth(ctrl, magic = 0x05))          // second auth

            // Steady-state control consumer: brings up the serial stream and
            // completes `result` with the pty path (or "" on failure).
            scope.launch { controlLoop(ctrl, host, result) }
            scope.launch { reauthLoop(ctrl) }

            val pty = withTimeoutOrNull(9000) { result.await() } ?: ""
            if (pty.isEmpty()) {
                if (_state.value.error.isEmpty()) fail("Timed out waiting for the serial/audio grant")
                disconnect(); return ""
            }
            _state.value = State(connected = true, deviceName = _state.value.deviceName)
            Log.i(TAG, "Icom network connected, pty=$pty device=${_state.value.deviceName}")
            return pty
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.message ?: "Connection failed")
            disconnect(); return ""
        }
    }

    fun disconnect() {
        val scope = connScope ?: return
        onAudioPcm = null
        // Best-effort polite shutdown.
        try {
            if (serialOpened) serial?.sendTracked(buildSerialOpenClose(close = true))
            control?.let { it.sendRaw(buildAuth(it, magic = 0x01)) }  // deauth
        } catch (_: Exception) {}
        try { audio?.close() } catch (_: Exception) {}
        try { serial?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
        audio = null
        serial = null
        control = null
        try { nativeIcomPtyClose() } catch (_: Exception) {}
        scope.cancel()
        connScope = null
        serialOpened = false
        if (_state.value.connected || _state.value.connecting) {
            _state.value = State()
        }
        Log.i(TAG, "Icom network disconnected")
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        _state.value = State(error = msg)
    }

    /* ───────────────────── control-stream protocol ───────────────── */

    private suspend fun controlLoop(ctrl: IcomStream, host: String, result: CompletableDeferred<String>) {
        try {
            ctrl.incoming.consumeEach { r ->
                when {
                    r.size == 168 && r.u(0) == 0xa8 -> {
                        System.arraycopy(r, 66, a8ReplyID, 0, 16)
                        gotA8 = true
                        maybeRequestSerial(ctrl)
                    }
                    r.size == 64 && r.u(0) == 0x40 -> {
                        if (r.u(21) == 0x05) { authOk = true; maybeRequestSerial(ctrl) }
                    }
                    r.size == 80 && r.u(0) == 0x50 -> {
                        if (r.u(48) == 0xFF && r.u(49) == 0xFF && r.u(50) == 0xFF) {
                            if (!serialOpened) {
                                fail("Auth rejected — try rebooting the radio")
                                if (!result.isCompleted) result.complete("")
                            }
                        } else if (r.u(48) == 0 && r.u(49) == 0 && r.u(50) == 0 && r.u(64) == 0x01) {
                            fail("Radio reported disconnect")
                            if (!serialOpened && !result.isCompleted) result.complete("")
                        }
                    }
                    r.size == 144 && r.u(0) == 0x90 && r.u(96) == 0x01 -> {
                        if (!serialOpened) openSerial(ctrl, host, r, result)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "controlLoop ended: ${e.message}")
        }
    }

    private fun maybeRequestSerial(ctrl: IcomStream) {
        if (!serialOpened && authOk && gotA8) {
            Log.i(TAG, "requesting serial+audio stream")
            ctrl.sendTracked(buildConnInfo(ctrl))
        }
    }

    private fun openSerial(ctrl: IcomStream, host: String, r: ByteArray, result: CompletableDeferred<String>) {
        val scope = connScope ?: return
        // The grant packet can carry refreshed IDs.
        ctrl.remoteSID = u32be(r, 8)
        ctrl.localSID = u32be(r, 12)
        System.arraycopy(r, 26, authID, 0, 6)
        val devName = parseCString(r, 64)
        _state.value = _state.value.copy(deviceName = devName)
        Log.i(TAG, "serial/audio granted, device='$devName' — opening CI-V stream")

        scope.launch {
            val ser = IcomStream("serial", host, SERIAL_PORT, scope)
            serial = ser
            if (!ser.open()) {
                fail("CI-V stream handshake failed")
                if (!result.isCompleted) result.complete("")
                return@launch
            }
            ser.startPing(firstSeq = 1)
            ser.startIdle()
            serialInnerSeq = 0
            ser.sendTracked(buildSerialOpenClose(close = false))  // open CI-V

            val path = nativeIcomPtyOpen()
            if (path == null) {
                fail("Could not create local pty")
                if (!result.isCompleted) result.complete("")
                return@launch
            }
            serialOpened = true

            // radio CI-V → pty (rigctld reads it)
            scope.launch { serialRxLoop(ser) }
            // pty (rigctld writes) → radio CI-V
            scope.launch { ptyTxLoop(ser) }

            // Phase 2 "full wireless": also bring up the audio stream (UDP 50003).
            // Control still succeeds even if audio fails — the user can fall back
            // to USB audio.
            openAudio(host, scope)

            if (!result.isCompleted) result.complete(path)
        }
    }

    private suspend fun openAudio(host: String, scope: CoroutineScope) {
        val aud = IcomStream("audio", host, AUDIO_PORT, scope)
        audio = aud
        if (!aud.open()) {
            Log.w(TAG, "audio stream handshake failed — control works, audio stays on USB")
            audio = null
            return
        }
        aud.startPing(firstSeq = 1)
        // NOTE: the audio stream sends NO periodic idle pkt0 (only the audio data
        // packets are tracked) — matching the reference implementation.
        audioSendSeq = 0
        scope.launch { audioRxLoop(aud) }
        _state.value = _state.value.copy(audioConnected = true)
        Log.i(TAG, "audio stream up (UDP $AUDIO_PORT, ${NET_AUDIO_RATE}Hz) — full wireless")
    }

    /* ───────────────────── audio (UDP 50003) bridge ──────────────── */

    /**
     * Consume incoming audio packets, reorder them by sequence in a small jitter
     * buffer, and hand the in-order PCM payloads to [onAudioPcm].
     *
     * Audio data packet: byte0..1 = length LE (0x056c=1388 → 1364B payload, or
     * 0x0244=580 → 556B payload), seq (LE) at [6:8] (set as a tracked pkt0 seq),
     * 0x80 marker at [16], s16le PCM payload at [24:]. The 1364/556 split is just
     * MTU fragmentation of one continuous 48 kHz mono stream, so feeding payloads
     * strictly in sequence order reconstructs the stream for the decimator.
     */
    private suspend fun audioRxLoop(aud: IcomStream) {
        val jitter = AudioJitter { pkt ->
            val payloadLen = pkt.size - 24
            if (payloadLen <= 0) return@AudioJitter
            val n = payloadLen / 2
            val shorts = ShortArray(n)
            var bi = 24
            for (i in 0 until n) {
                shorts[i] = ((pkt[bi].toInt() and 0xFF) or (pkt[bi + 1].toInt() shl 8)).toShort()
                bi += 2
            }
            onAudioPcm?.invoke(shorts)
        }
        try {
            aud.incoming.consumeEach { r ->
                if (r.size >= 580 &&
                    ((r.u(0) == 0x6c && r.u(1) == 0x05) || (r.u(0) == 0x44 && r.u(1) == 0x02))) {
                    jitter.add(u16le(r, 6), r)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "audioRxLoop ended: ${e.message}")
        }
    }

    /**
     * Send one 20 ms TX audio frame (960 int16 samples @ 48 kHz) to the radio,
     * fragmented into the two packet sizes the radio expects (1364 B + 556 B).
     */
    fun sendAudioFrame(pcm: ShortArray) {
        val aud = audio ?: return
        val bytes = ByteArray(pcm.size * 2)
        var bi = 0
        for (s in pcm) {
            bytes[bi++] = s.toByte()
            bytes[bi++] = (s.toInt() shr 8).toByte()
        }
        val p1 = minOf(1364, bytes.size)
        aud.sendTracked(buildAudioPart(aud, bytes, 0, p1))
        audioSendSeq = (audioSendSeq + 1) and 0xFFFF
        if (bytes.size > p1) {
            aud.sendTracked(buildAudioPart(aud, bytes, p1, bytes.size - p1))
            audioSendSeq = (audioSendSeq + 1) and 0xFFFF
        }
    }

    private fun buildAudioPart(s: IcomStream, data: ByteArray, off: Int, len: Int): ByteArray {
        val total = 24 + len
        val p = ByteArray(total)
        p[0] = total.toByte(); p[1] = (total ushr 8).toByte()   // length u32 LE (fits in 2 bytes)
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[16] = 0x80.toByte()
        p[18] = (audioSendSeq ushr 8).toByte(); p[19] = audioSendSeq.toByte()  // audio seq (BE)
        p[22] = (len ushr 8).toByte(); p[23] = len.toByte()      // payload length (BE)
        System.arraycopy(data, off, p, 24, len)
        // p[6:7] (tracking seq) is filled by sendTracked.
        return p
    }

    /**
     * Minimal in-order jitter buffer with 16-bit modular sequence numbers.
     * Releases contiguous packets immediately; if the next expected packet is
     * missing and the buffer grows past [MAX], it skips forward to the oldest
     * buffered packet (accepting the gap) so audio never stalls permanently.
     * Single-threaded: only touched from one consume coroutine.
     */
    private class AudioJitter(private val onPcm: (ByteArray) -> Unit) {
        private val buf = HashMap<Int, ByteArray>()
        private var expected = -1
        companion object { private const val MAX = 24 }  // ~24 packets ≈ 240 ms

        fun add(seq: Int, pkt: ByteArray) {
            if (expected < 0) expected = seq
            if (seqLess(seq, expected)) return       // already played / too old
            buf[seq] = pkt
            drain()
            if (buf.size > MAX) {
                // Stuck on a lost packet — resync to the oldest buffered seq.
                var oldest = expected
                var first = true
                for (k in buf.keys) {
                    if (first || seqLess(k, oldest)) { oldest = k; first = false }
                }
                expected = oldest
                drain()
            }
        }

        private fun drain() {
            while (true) {
                val p = buf.remove(expected) ?: break
                onPcm(p)
                expected = (expected + 1) and 0xFFFF
            }
        }

        /** True if a is strictly "before" b in 16-bit modular sequence space. */
        private fun seqLess(a: Int, b: Int): Boolean {
            val d = (b - a) and 0xFFFF
            return d != 0 && d < 0x8000
        }
    }

    private suspend fun reauthLoop(ctrl: IcomStream) {
        try {
            while (true) {
                delay(60_000)
                ctrl.sendTracked(buildAuth(ctrl, magic = 0x05))
            }
        } catch (_: CancellationException) {}
    }

    /* ───────────────────── serial (CI-V) bridge ──────────────────── */

    private suspend fun serialRxLoop(ser: IcomStream) {
        try {
            ser.incoming.consumeEach { r ->
                // CI-V data packet: r[0]=0x15+len, r[16]=0xc1, r[17]=len, payload at 21.
                if (r.size >= 22 && r.u(16) == 0xc1 && (r.u(0) - 0x15) == r.u(17)) {
                    val len = r.u(17)
                    if (21 + len <= r.size && len > 0) {
                        val civ = r.copyOfRange(21, 21 + len)
                        nativeIcomPtyWrite(civ, civ.size)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "serialRxLoop ended: ${e.message}")
        }
    }

    private suspend fun ptyTxLoop(ser: IcomStream) {
        val frame = ArrayList<Byte>(96)
        try {
            while (true) {
                yield()                                   // cancellation point
                val d = nativeIcomPtyRead(100) ?: break   // null = pty closed
                if (d.isEmpty()) continue
                for (b in d) {
                    frame.add(b)
                    val v = b.toInt() and 0xFF
                    // CI-V frames end in 0xFD (0xFC for some); also cap at 80 bytes.
                    if (v == 0xFD || v == 0xFC || frame.size >= 80) {
                        ser.sendTracked(buildSerialData(ser, frame.toByteArray()))
                        serialInnerSeq = (serialInnerSeq + 1) and 0xFFFF
                        frame.clear()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ptyTxLoop ended: ${e.message}")
        }
    }

    /* ───────────────────────── packet builders ───────────────────── */

    private fun buildLogin(s: IcomStream): ByteArray {
        val p = ByteArray(128)
        p[0] = 0x80.toByte()
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[19] = 0x70; p[20] = 0x01
        p[23] = authInnerSeq.toByte(); p[24] = (authInnerSeq ushr 8).toByte()
        // p[26],p[27] = a random "auth start" id (any value works; the reply echoes it)
        p[26] = (authInnerSeq and 0xFF).toByte(); p[27] = 0x42
        System.arraycopy(passcode(username), 0, p, 64, 16)
        System.arraycopy(passcode(password), 0, p, 80, 16)
        val app = "icom-pc".toByteArray(Charsets.US_ASCII)
        System.arraycopy(app, 0, p, 96, app.size)
        authInnerSeq = (authInnerSeq + 1) and 0xFFFF
        return p
    }

    private fun buildAuth(s: IcomStream, magic: Int): ByteArray {
        val p = ByteArray(64)
        p[0] = 0x40
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[19] = 0x30; p[20] = 0x01; p[21] = magic.toByte()
        p[23] = authInnerSeq.toByte(); p[24] = (authInnerSeq ushr 8).toByte()
        System.arraycopy(authID, 0, p, 26, 6)
        authInnerSeq = (authInnerSeq + 1) and 0xFFFF
        return p
    }

    private fun buildConnInfo(s: IcomStream): ByteArray {
        val p = ByteArray(144)
        p[0] = 0x90.toByte()
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[19] = 0x80.toByte(); p[20] = 0x01; p[21] = 0x03
        p[23] = authInnerSeq.toByte(); p[24] = (authInnerSeq ushr 8).toByte()
        System.arraycopy(authID, 0, p, 26, 6)
        System.arraycopy(a8ReplyID, 0, p, 32, 16)
        val name = "IC-705".toByteArray(Charsets.US_ASCII)
        System.arraycopy(name, 0, p, 64, name.size)
        System.arraycopy(passcode(username), 0, p, 96, 16)
        p[112] = 0x01; p[113] = 0x01; p[114] = 0x04; p[115] = 0x04
        p[118] = (NET_AUDIO_RATE ushr 8).toByte(); p[119] = NET_AUDIO_RATE.toByte()  // rx
        p[122] = (NET_AUDIO_RATE ushr 8).toByte(); p[123] = NET_AUDIO_RATE.toByte()  // tx
        p[126] = (SERIAL_PORT ushr 8).toByte(); p[127] = SERIAL_PORT.toByte()
        p[130] = (AUDIO_PORT ushr 8).toByte(); p[131] = AUDIO_PORT.toByte()
        p[134] = (TX_BUF_MS ushr 8).toByte(); p[135] = TX_BUF_MS.toByte()
        p[136] = 0x01
        authInnerSeq = (authInnerSeq + 1) and 0xFFFF
        return p
    }

    private fun buildSerialOpenClose(close: Boolean): ByteArray {
        val s = serial ?: return ByteArray(0)
        val p = ByteArray(22)
        p[0] = 0x16
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[16] = 0xc0.toByte(); p[17] = 0x01
        p[19] = (serialInnerSeq ushr 8).toByte(); p[20] = serialInnerSeq.toByte()
        p[21] = if (close) 0x00 else 0x05
        serialInnerSeq = (serialInnerSeq + 1) and 0xFFFF
        return p
    }

    private fun buildSerialData(s: IcomStream, civ: ByteArray): ByteArray {
        val l = civ.size
        val p = ByteArray(21 + l)
        p[0] = (0x15 + l).toByte()
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[16] = 0xc1.toByte(); p[17] = l.toByte()
        p[19] = (serialInnerSeq ushr 8).toByte(); p[20] = serialInnerSeq.toByte()
        System.arraycopy(civ, 0, p, 21, l)
        return p
    }

    private fun parseCString(r: ByteArray, off: Int): String {
        var end = off
        while (end < r.size && r[end].toInt() != 0) end++
        return String(r, off, end - off, Charsets.US_ASCII)
    }

    /* ═══════════════════════════════════════════════════════════════
     *  IcomStream — one UDP stream (control or serial).
     *  Handles the pkt3/4/6 handshake, idle (pkt0) + ping (pkt7) keep-alive,
     *  tracked sends with retransmit, and exposes non-keepalive packets via
     *  the `incoming` channel.
     * ═══════════════════════════════════════════════════════════════ */
    private inner class IcomStream(
        val name: String,
        val host: String,
        val port: Int,
        val scope: CoroutineScope
    ) {
        var localSID = 0
        var remoteSID = 0

        private var socket: DatagramSocket? = null
        @Volatile private var running = false
        private val sendLock = Any()

        private var trackSeq = 1                       // pkt0 tracking seq (bytes 6-7)
        private val txBuf = LinkedHashMap<Int, ByteArray>()

        private var pingSeq = 1
        private var pingInner = 0x8304
        @Volatile private var keepaliveStarted = false

        val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        suspend fun open(): Boolean {
            return try {
                val sock = DatagramSocket()
                sock.connect(InetSocketAddress(InetAddress.getByName(host), port))
                sock.soTimeout = 400
                socket = sock
                computeLocalSid(sock)
                running = true
                scope.launch(Dispatchers.IO) { readerLoop() }

                sendRaw(plain(0x03, withRemote = false)); sendRaw(plain(0x03, withRemote = false))
                val r4 = expect(16, byteArrayOf(0x10, 0, 0, 0, 0x04, 0, 0, 0)) ?: return false
                remoteSID = u32be(r4, 8)
                sendRaw(pkt6()); sendRaw(pkt6())
                expect(16, byteArrayOf(0x10, 0, 0, 0, 0x06, 0, 0x01, 0)) ?: return false
                trackSeq = 1
                Log.i(TAG, "$name stream up: localSID=${hex(localSID)} remoteSID=${hex(remoteSID)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "$name open failed: ${e.message}")
                false
            }
        }

        private fun computeLocalSid(sock: DatagramSocket) {
            val addr = sock.localAddress?.address
            val ip = if (addr != null && addr.size >= 4) {
                val o = addr.size - 4
                ((addr[o].toInt() and 0xFF) shl 24) or ((addr[o + 1].toInt() and 0xFF) shl 16) or
                ((addr[o + 2].toInt() and 0xFF) shl 8) or (addr[o + 3].toInt() and 0xFF)
            } else 0
            localSID = (ip shl 16) or (sock.localPort and 0xFFFF)
        }

        fun startPing(firstSeq: Int) {
            pingSeq = firstSeq
            pingInner = 0x8304
            keepaliveStarted = true
            scope.launch {
                try {
                    while (isActive && running) {
                        delay(3000)
                        sendPingRequest()
                    }
                } catch (_: CancellationException) {}
            }
        }

        fun startIdle() {
            scope.launch {
                try {
                    while (isActive && running) {
                        delay(100)
                        sendTracked(idlePacket())
                    }
                } catch (_: CancellationException) {}
            }
        }

        suspend fun expect(len: Int, prefix: ByteArray, timeoutMs: Long = 3000): ByteArray? =
            withTimeoutOrNull(timeoutMs) {
                while (isActive) {
                    val r = incoming.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                    if (r.size == len && r.startsWith(prefix)) return@withTimeoutOrNull r
                }
                null
            }

        fun sendRaw(p: ByteArray) {
            synchronized(sendLock) {
                try { socket?.send(DatagramPacket(p, p.size)) }
                catch (e: Exception) { if (running) Log.w(TAG, "$name send failed: ${e.message}") }
            }
        }

        /** Assign the next tracking seq, store for retransmit, then send. */
        fun sendTracked(p: ByteArray) {
            synchronized(sendLock) {
                p[6] = trackSeq.toByte(); p[7] = (trackSeq ushr 8).toByte()
                txBuf[trackSeq and 0xFFFF] = p.copyOf()
                if (txBuf.size > 512) {
                    val it = txBuf.keys.iterator(); it.next(); it.remove()
                }
                try { socket?.send(DatagramPacket(p, p.size)) }
                catch (e: Exception) { if (running) Log.w(TAG, "$name send failed: ${e.message}") }
                trackSeq = (trackSeq + 1) and 0xFFFF
            }
        }

        fun close() {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            incoming.close()
        }

        private suspend fun readerLoop() {
            val buf = ByteArray(1500)
            val sock = socket ?: return
            while (running) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    val r = buf.copyOf(dp.length)
                    when {
                        isPkt7(r) -> handlePkt7(r)
                        isPkt0(r) -> handlePkt0(r)
                        else -> incoming.trySend(r)
                    }
                } catch (_: SocketTimeoutException) {
                    // loop to re-check `running`
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "$name reader: ${e.message}")
                    break
                }
            }
        }

        private fun isPkt7(r: ByteArray) =
            r.size == 21 && r.u(1) == 0 && r.u(2) == 0 && r.u(3) == 0 && r.u(4) == 0x07 && r.u(5) == 0

        private fun isPkt0(r: ByteArray) = r.size >= 16 && r.u(1) == 0 && r.u(2) == 0 && r.u(3) == 0 &&
            ((r.u(0) == 0x10 && (r.u(4) == 0x00 || r.u(4) == 0x01) && r.u(5) == 0) ||
             (r.u(0) == 0x18 && r.u(4) == 0x01 && r.u(5) == 0))

        private fun handlePkt7(r: ByteArray) {
            if (r.u(16) == 0x00 && keepaliveStarted) {
                // Radio ping request → reply, echoing its id and seq.
                val seq = u16le(r, 6)
                val p = ByteArray(21)
                p[0] = 0x15; p[4] = 0x07
                p[6] = seq.toByte(); p[7] = (seq ushr 8).toByte()
                p.putSid(8, localSID); p.putSid(12, remoteSID)
                p[16] = 0x01
                System.arraycopy(r, 17, p, 17, 4)
                sendRaw(p)
            }
            // else: reply to our own ping — nothing to do.
        }

        private fun handlePkt0(r: ByteArray) {
            if (r.u(0) == 0x10 && r.u(4) == 0x01) {
                resend(u16le(r, 6))
            } else if (r.u(0) == 0x18 && r.u(4) == 0x01) {
                var i = 16
                while (i + 4 <= r.size) {
                    val start = u16le(r, i); val end = u16le(r, i + 2)
                    var s = start
                    while (true) { resend(s); if (s == end) break; s = (s + 1) and 0xFFFF }
                    i += 4
                }
            }
            // else: idle — ignore.
        }

        private fun resend(seq: Int) {
            val d = txBuf[seq and 0xFFFF]
            if (d != null) { sendRaw(d); sendRaw(d) }
            else { val idle = idlePacketWithSeq(seq); sendRaw(idle); sendRaw(idle) }
        }

        private fun sendPingRequest() {
            val p = ByteArray(21)
            p[0] = 0x15; p[4] = 0x07
            p[6] = pingSeq.toByte(); p[7] = (pingSeq ushr 8).toByte()
            p.putSid(8, localSID); p.putSid(12, remoteSID)
            p[16] = 0x00
            p[17] = ((pingInner * 7 + 0x33) and 0xFF).toByte()   // arbitrary id byte
            p[18] = pingInner.toByte(); p[19] = (pingInner ushr 8).toByte(); p[20] = 0x06
            sendRaw(p)
            pingInner = (pingInner + 1) and 0xFFFF
            pingSeq = (pingSeq + 1) and 0xFFFF
        }

        private fun plain(type: Int, withRemote: Boolean): ByteArray {
            val p = ByteArray(16)
            p[0] = 0x10; p[4] = type.toByte()
            p.putSid(8, localSID); p.putSid(12, if (withRemote) remoteSID else 0)
            return p
        }

        private fun pkt6(): ByteArray {
            val p = ByteArray(16)
            p[0] = 0x10; p[4] = 0x06; p[6] = 0x01
            p.putSid(8, localSID); p.putSid(12, remoteSID)
            return p
        }

        private fun idlePacket(): ByteArray {
            val p = ByteArray(16)
            p[0] = 0x10
            p.putSid(8, localSID); p.putSid(12, remoteSID)
            return p
        }

        private fun idlePacketWithSeq(seq: Int): ByteArray {
            val p = idlePacket()
            p[6] = seq.toByte(); p[7] = (seq ushr 8).toByte()
            return p
        }
    }
}

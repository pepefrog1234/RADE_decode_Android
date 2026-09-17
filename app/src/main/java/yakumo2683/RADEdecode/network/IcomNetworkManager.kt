package yakumo2683.RADEdecode.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

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
class IcomNetworkManager internal constructor(private val pty: IcomPty) : NetworkAudioRig {

    constructor() : this(NativeIcomPty)

    companion object {
        private const val TAG = "IcomNetwork"

        const val DEFAULT_CONTROL_PORT = 50001
        private const val SERIAL_PORT = 50002
        private const val AUDIO_PORT = 50003

        /** Default network audio sample rate (s16le mono). RX may independently
         * request 16 kHz; the engine decimates the selected rate to 8 kHz. */
        const val NET_AUDIO_RATE = 48000
        /** 20 ms TX frame: 48000 × 0.02 = 960 samples = 1920 bytes. */
        const val NET_AUDIO_FRAME_SAMPLES = 960

        /** Radio-side TX audio buffer (ms), requested in the conninfo packet: the
         *  jitter buffer the radio fills before it plays our TX audio (wfview's
         *  "TX latency", kappanhang's txSeqBufLength; RS-BA1's default is 150).
         *  Also sizes our own RX jitter depth. 150 ms suits Wi-Fi/LAN. Over LTE
         *  or a VPN both the arrival jitter and the retransmit round trip exceed
         *  it, so the radio's buffer ran dry every few seconds — the reported
         *  short interruptions of the transmitted RADE signal. */
        const val DEFAULT_AUDIO_BUFFER_MS = 150
        const val MIN_AUDIO_BUFFER_MS = 100
        /** Field-tested on an IC-7300MK2 (v1.6.16): 500 ms produced long TX
         *  dropouts and 800 ms keyed the rig with NO modulation at all — the
         *  radio's TX buffer cannot hold that much. 300 ms is the usable ceiling. */
        const val MAX_AUDIO_BUFFER_MS = 300

        /** TX audio sample rate offered to the radio (conninfo txsample). 48 kHz
         *  LPCM is ~860 kbps on the uplink; RS-BA1 also supports 16 kHz, which
         *  cuts that to ~290 kbps for a weak LTE uplink (experimental — the radio
         *  does the 16→48 kHz conversion internally). */
        const val TX_RATE_FULL = 48000
        const val TX_RATE_LOW = 16000

        /** How many times the whole connect handshake is attempted before giving
         *  up, and the pause after each failed attempt. A radio that has just
         *  seen its previous session torn down keeps that session (token + the
         *  serial/audio ports) allocated for up to ~1–2 minutes; a reconnect that
         *  lands inside that window gets "Auth rejected" or "CI-V stream handshake
         *  failed" and, before this, forced the operator to keep pressing Connect,
         *  kill the app, or wait out the radio's timeout by hand. Retrying the
         *  full handshake — fresh sockets, fresh session id, re-login — across
         *  that window recovers automatically. Backoff grows so the total span
         *  (~1+4+8+12+16 s of pauses over 6 tries) brackets the radio's cleanup. */
        private const val CONNECT_ATTEMPTS = 6
        private val CONNECT_BACKOFF_MS = longArrayOf(1000, 4000, 8000, 12000, 16000)

        /** Control-stream liveness: if no datagram at all arrives from the radio
         *  for this long while we believe we are connected, the transport is dead
         *  (weak LTE/Wi-Fi drop, radio powered off, VPN tunnel gone). On a healthy
         *  link the radio answers our 3 s pings and sends its own idle/ping
         *  traffic, so ~5 missed cycles is a real death, not jitter. Without this
         *  the manager stayed "connected" forever against a silent radio, the rig
         *  screen kept showing a green "Connected" that was a lie, and TX hung. */
        private const val LINK_TIMEOUT_MS = 15_000L

        /** Session-level liveness. The radio keeps answering and sending pkt7
         *  pings on a session it has silently dropped: field log (IC-7300MK2,
         *  v1.6.18) — CI-V idles, CI-V replies and RX audio all stopped at once
         *  while pings kept flowing for 2.5 minutes, so the ping-fed control
         *  watchdog never fired, every CAT command timed out, TX could not key
         *  the rig and RX stayed silent with no error shown. While the radio
         *  considers us logged in it sends a CI-V idle every ~30 ms (and kept
         *  doing so during TX and during a 7 s CI-V reply delay), so this much
         *  silence of TRACKED packets on the serial stream means the session is
         *  gone: tear down and let the ViewModel reconnect. */
        private const val DATA_SILENCE_MS = 6_000L
        private const val LOGOUT_GRACE_MS = 500L

        /** Marker prefix on the one connect error that must NOT be retried: wrong
         *  Network User name/password. Rets would just fail identically for ~1 min. */
        const val ERR_BAD_CREDENTIALS = "Invalid username / password"

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

    /** Callback for received, in-order audio PCM (int16 mono at [audioRate]).
     *  Set by AudioService; invoked on the audio stream's consume coroutine. */
    @Volatile override var onAudioPcm: ((ShortArray) -> Unit)? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    override val isConnected: Boolean get() = _state.value.connected
    override val audioLinkUp: Boolean get() = _state.value.audioConnected
    override val audioRate: Int get() = rxAudioRate
    /** Negotiated independently of TX; change only before connecting. */
    @Volatile var rxAudioRate: Int = NET_AUDIO_RATE
        set(value) { field = if (value == 16000) 16000 else NET_AUDIO_RATE }
    /** 20 ms of TX audio at [txAudioRate] (960 @ 48 kHz, 320 @ 16 kHz). */
    override val txFrameSamples: Int get() = txAudioRate / 50

    /** See [DEFAULT_AUDIO_BUFFER_MS]. Set before connect(); it is sent to the
     *  radio in the conninfo packet, so a change takes effect on the next connect. */
    @Volatile var audioBufferMs: Int = DEFAULT_AUDIO_BUFFER_MS
        set(value) { field = value.coerceIn(MIN_AUDIO_BUFFER_MS, MAX_AUDIO_BUFFER_MS) }
    override val txBufferMs: Int get() = audioBufferMs
    /** PTT must stay keyed this long after the last TX frame left the phone:
     *  the radio still holds up to [audioBufferMs] of it, plus network + DSP
     *  latency. Cutting PTT earlier truncates the EOO callsign on the air. */
    override val txTailMs: Int get() = audioBufferMs + 100

    /** See [TX_RATE_FULL]/[TX_RATE_LOW]. Set before connect() (conninfo txsample).
     *  Overrides the interface's read-only rate so the TX pump and native encoder
     *  pick the same value the radio was told. */
    @Volatile override var txAudioRate: Int = TX_RATE_FULL
        set(value) { field = if (value == TX_RATE_LOW) TX_RATE_LOW else TX_RATE_FULL }

    @Volatile private var connScope: CoroutineScope? = null
    @Volatile private var disconnecting = false
    private val connectMutex = Mutex()

    /** Optional persistence hooks (wired by the ViewModel to SharedPreferences).
     *  The last session's login token is stored so that a token left behind by
     *  an unclean exit or unacknowledged logout can be removed at the start of
     *  the next connect. Without this the
     *  radio holds the stale token and ignores the login until it expires. */
    @Volatile var saveStaleToken: ((String?) -> Unit)? = null
    @Volatile var loadStaleToken: (() -> String?)? = null

    private var control: IcomStream? = null
    private var serial: IcomStream? = null
    private var audio: IcomStream? = null
    private var audioSendSeq = 0

    /** Every stream created in this session, so disconnect() can close ALL of
     *  them — not just the ones the control/serial/audio fields point at. A
     *  stream replaced before it was closed kept its UDP port bound (and its
     *  reader thread alive) for the life of the process; the next connect then
     *  got EADDRINUSE on 50002/50003 and had to fall back to ephemeral ports. */
    private val allStreams = ArrayList<IcomStream>()

    /** Set by [abortConnect]: makes a running connect() stop retrying after the
     *  current attempt instead of grinding through all CONNECT_ATTEMPTS. */
    @Volatile private var abortRequested = false

    /** Cancel an in-flight connect() retry loop (operator pressed the button
     *  while "CONNECTING…", e.g. to stop an automatic reconnect to a radio that
     *  is really switched off). Safe to call when nothing is connecting. */
    fun abortConnect() { abortRequested = true }

    // Protocol state (control stream)
    private var username = ""
    private var password = ""
    private var authInnerSeq = 0
    private var loginRequestId = -1
    private var authID = ByteArray(6)
    private var a8ReplyID = ByteArray(16)
    private var gotA8 = false
    private var authOk = false
    private var serialOpened = false
    /** Set SYNCHRONOUSLY when the serial/audio open starts. serialOpened is only
     *  set once the async CI-V handshake succeeds (~200 ms later), and the
     *  IC-7300MK2 sends its 0x90 "connected" status twice within a few ms — the
     *  second copy used to open a SECOND serial stream (EADDRINUSE → ephemeral
     *  port → "no pkt6 reply" → "CI-V stream handshake failed" 5 s after a good
     *  connect, which the link-loss handler then treated as a dead radio), and
     *  it overwrote `serial`, so the CI-V open packet went out with the wrong
     *  SIDs and every CAT command timed out. */
    @Volatile private var serialOpening = false
    /** We only treat a 0x90 "connected" status as OUR grant after we asked for
     *  the streams; the radio also emits it for a previous, still-alive session. */
    @Volatile private var connInfoRequested = false
    private var serialInnerSeq = 0

    /* ───────────────────────── public API ───────────────────────── */

    /**
     * Connect to the radio and bring up the CI-V tunnel.
     * @return the pty slave path (e.g. "/dev/pts/3") to hand to rigctld, or "" on failure.
     *
     * Retries the WHOLE handshake, not just the login. A reconnect that lands
     * while the radio is still holding the previous session (its login token and
     * the serial/audio ports) fails at whichever stage the radio hasn't freed
     * yet — "Auth rejected", "CI-V stream handshake failed" or "No login reply".
     * Before this, the only recovery was to press Connect again, kill the app, or
     * wait out the radio's ~1–2 min session timeout by hand (all reported on the
     * IC-7300MK2). Re-running the full handshake with backoff rides over that
     * window automatically; only a real credential rejection stops early.
     */
    suspend fun connect(host: String, controlPort: Int, user: String, pass: String): String =
        connectMutex.withLock { connectLocked(host, controlPort, user, pass) }

    private suspend fun connectLocked(host: String, controlPort: Int, user: String, pass: String): String {
        disconnect()  // clean any previous session

        username = user
        password = pass
        abortRequested = false

        for (attempt in 1..CONNECT_ATTEMPTS) {
            val pty = attemptConnect(host, controlPort)
            if (pty.isNotEmpty()) return pty

            val err = _state.value.error
            // A genuine name/password rejection fails identically on every retry —
            // stop now and let the operator fix it instead of burning ~1 min.
            if (err.startsWith(ERR_BAD_CREDENTIALS)) return ""
            if (abortRequested) {
                Log.i(TAG, "connect aborted by operator after attempt $attempt")
                _state.value = State()
                return ""
            }
            if (attempt >= CONNECT_ATTEMPTS) break

            val backoff = CONNECT_BACKOFF_MS[(attempt - 1).coerceAtMost(CONNECT_BACKOFF_MS.size - 1)]
            Log.i(TAG, "connect attempt $attempt/$CONNECT_ATTEMPTS failed ($err); retrying in ${backoff}ms")
            // Keep the spinner up and say we are still trying, so the automatic
            // recovery doesn't look like a hang. Preserve the underlying reason.
            _state.value = State(
                connecting = true,
                error = "$err — retrying (${attempt + 1}/$CONNECT_ATTEMPTS)…"
            )
            delay(backoff)
            if (abortRequested) {
                Log.i(TAG, "connect aborted by operator during backoff")
                _state.value = State()
                return ""
            }
        }
        return ""
    }

    /** One full handshake attempt (fresh sockets, fresh session id, re-login).
     *  Returns the pty path on success, or "" with [State.error] set on failure. */
    private suspend fun attemptConnect(host: String, controlPort: Int): String {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        synchronized(this) {
            // Share the teardown lock: a new session cannot replace the old one's
            // fields while its logout is still waiting for retransmission.
            disconnecting = false
            authInnerSeq = 0
            var nextId: Int
            do { nextId = kotlin.random.Random.nextInt(0x10000) } while (nextId == loginRequestId)
            loginRequestId = nextId
            authID = ByteArray(6)
            a8ReplyID = ByteArray(16)
            gotA8 = false
            authOk = false
            serialOpened = false
            serialOpening = false
            connInfoRequested = false
            serialInnerSeq = 0
            audioSendSeq = 0

            connScope = scope
            _state.value = State(connecting = true)
        }

        val result = CompletableDeferred<String>(scope.coroutineContext[Job])

        try {
            val ctrl = synchronized(this) {
                if (!isCurrentSession(scope)) return ""
                IcomStream("control", host, controlPort, scope).also { control = it }
            }
            val controlOpened = ctrl.open()
            if (!isCurrentSession(scope)) return ""
            if (!controlOpened) {
                fail("Control handshake failed — no reply from $host:$controlPort. " +
                     "Check the IP and that the radio is reachable. Over a VPN the radio " +
                     "also needs a default gateway (or the VPN must NAT clients into the " +
                     "radio's subnet), or it cannot send replies back.")
                disconnect(); return ""
            }

            // Pre-login cleanup: remove the PREVIOUS session's token, but only
            // when one was left by an unclean exit or an unacknowledged logout.
            // Clear this one-shot copy after sending the removal; radios
            // differ in how they treat a removal for an unknown token: the
            // IC-705 (fw 1.41) silently ignores it, but a radio that ANSWERS
            // 0x40 packets (IC-7300MK2) consumes its tracked sequence numbers
            // before the login reply, and an unconditional removal on every
            // connect then wedged every login after the first.
            loadStaleToken?.invoke()?.let { hexTok ->
                parseTokenHex(hexTok)?.let { oldTok ->
                    Log.i(TAG, "removing stale token left by an unclean exit")
                    val remove = buildTokenRemove(ctrl, oldTok)
                    ctrl.sendTracked(remove)
                    repeat(2) { delay(80); ctrl.sendRaw(remove) }
                    delay(150)
                }
                saveStaleToken?.invoke(null)             // one-shot: never resend it
            }

            // Login reply = the 96-byte tracked data packet (type 0x60). Bytes
            // 6-7 carry the RADIO's running tracking seq — never match on them:
            // they are 0x0001 only when the reply happens to be the radio's
            // very first tracked packet. A reply the radio sends to the
            // pre-login token removal, or a login retry after a lost first
            // reply, shifts that seq, and a matcher pinned to 0x0001 then
            // drops every real login reply ("No login reply" although the
            // radio answered).
            val loginReply = byteArrayOf(0x60, 0, 0, 0, 0, 0)
            // Anything already queued arrived BEFORE the login left (e.g. a
            // radio's ack to the token removal, whatever its shape) — drop it
            // so it cannot be mistaken for the login reply. The real reply
            // cannot arrive before the login is sent.
            ctrl.drainPending()
            ctrl.sendTracked(buildLogin(ctrl))                       // authInnerSeq 0 → login
            // 5 s window per attempt, up to 3 attempts: a lost login packet on a
            // cold path, or a radio still flushing a just-expired previous
            // session, both recover on a resend instead of failing the connect.
            val expectedRequestId = loginRequestId
            val acceptLogin: (ByteArray) -> Boolean = { reply ->
                val matches = u16le(reply, 26) == expectedRequestId
                if (!matches) Log.w(TAG, "ignoring stale login reply: request=${u16le(reply, 26)} expected=$expectedRequestId")
                matches
            }
            var r60 = ctrl.expect(96, loginReply, timeoutMs = 5000, accept = acceptLogin)
            var loginAttempt = 1
            while (r60 == null && loginAttempt < 3) {
                if (!isCurrentSession(scope)) return ""
                loginAttempt++
                Log.i(TAG, "no login reply; resending login (attempt $loginAttempt/3)")
                ctrl.sendTracked(buildLogin(ctrl))
                r60 = ctrl.expect(96, loginReply, timeoutMs = 5000, accept = acceptLogin)
            }
            if (!isCurrentSession(scope)) return ""
            if (r60 == null) {
                fail("No login reply — is 'Network control' ON and the control port correct?")
                disconnect(); return ""
            }
            if (r60.size >= 52 && r60.u(48) == 0xFF && r60.u(49) == 0xFF && r60.u(50) == 0xFF && r60.u(51) == 0xFE) {
                fail(ERR_BAD_CREDENTIALS)
                disconnect(); return ""
            }
            synchronized(this) {
                if (!isCurrentSession(scope)) return ""
                System.arraycopy(r60, 26, authID, 0, 6)
                // Persist the token now, while we hold it: if this session later
                // ends without a clean disconnect, the NEXT connect removes it
                // before logging in (see the pre-login cleanup above).
                saveStaleToken?.invoke(authID.joinToString("") { "%02x".format(it) })

                ctrl.startPing(firstSeq = 2)
                ctrl.sendTracked(buildAuth(ctrl, magic = 0x02))          // first auth
                ctrl.startIdle()
                ctrl.sendTracked(buildAuth(ctrl, magic = 0x05))          // second auth

                // Steady-state control consumer: brings up the serial stream and
                // completes `result` with the pty path (or "" on failure).
                scope.launch { controlLoop(ctrl, host, result) }
                scope.launch { reauthLoop(ctrl) }
            }

            val pty = withTimeoutOrNull(9000) { result.await() } ?: ""
            synchronized(this) {
                if (!isCurrentSession(scope)) return ""
                if (pty.isEmpty()) {
                    if (_state.value.error.isEmpty()) fail("Timed out waiting for the serial/audio grant")
                    disconnect(); return ""
                }
                // Preserve audioConnected/deviceName — openAudio runs in the background
                // and may have already (or will soon) set audioConnected. Using copy()
                // here instead of a fresh State() avoids clobbering it back to false.
                _state.value = _state.value.copy(connecting = false, connected = true, error = "")
                // Watch the control stream for sustained silence: a weak-LTE/Wi-Fi drop
                // (or the radio being powered off) otherwise left us "connected" against
                // a dead transport — a green "Connected" that lied and a hung TX.
                ctrl.startWatchdog(LINK_TIMEOUT_MS) { onControlLinkLost(scope) }
                Log.i(TAG, "Icom network connected, pty=$pty device=${_state.value.deviceName}")
                return pty
            }
        } catch (e: CancellationException) {
            disconnectIfCurrent(scope)
            // A radio-triggered teardown cancels this attempt's result, not the
            // operator's connect request. Retry it; propagate real caller cancellation.
            currentCoroutineContext().ensureActive()
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.message ?: "Connection failed")
            disconnect(); return ""
        }
    }

    /** Fired by the control stream's watchdog when the radio has gone silent for
     *  [LINK_TIMEOUT_MS]. Tear the session down with a clear reason so the UI
     *  stops showing a false "connected" and the operator can reconnect (the
     *  next connect() will retry across the radio's cleanup window). */
    private fun onControlLinkLost(scope: CoroutineScope) {
        if (!isCurrentSession(scope)) return
        Log.w(TAG, "control link silent > ${LINK_TIMEOUT_MS}ms — tearing down")
        disconnectIfCurrent(scope, "Radio stopped responding — Wi-Fi/LTE link lost. Press Connect to reconnect.")
    }

    private fun isCurrentSession(scope: CoroutineScope) = connScope === scope && !disconnecting && scope.isActive

    /** CAT can fail while UDP pings/audio continue. Recover the whole radio
     * session, not just the localhost socket to the same broken CI-V tunnel. */
    fun recoverCatSession() {
        val session = connScope ?: return
        if (!isConnected || !isCurrentSession(session)) return
        Thread {
            disconnectIfCurrent(session, "Radio CAT stopped responding — reconnecting…")
        }.start()
    }

    @Synchronized
    private fun disconnectIfCurrent(scope: CoroutineScope, reason: String? = null) {
        // A watchdog thread can be queued before a manual disconnect/reconnect.
        // It must not later close that new session or overwrite its status.
        if (connScope === scope) disconnect(reason)
    }

    /** @param reason when non-null, the teardown leaves this error on [state]
     *  (e.g. a watchdog link-loss note) instead of a blank disconnected state. */
    @Synchronized
    fun disconnect(reason: String? = null) {
        val scope = connScope ?: run {
            // Already torn down; still honor a link-loss reason so the UI shows it.
            if (reason != null) _state.value = State(error = reason)
            return
        }
        disconnecting = true
        // NOTE: onAudioPcm is deliberately NOT cleared here. AudioService owns
        // that hook and only (re)installs it from startNetworkDecoding(), so
        // clearing it on a manual Disconnect left a still-running RX engine
        // silent after the next Connect until the operator cycled TX→RX
        // (reported, IC-7300MK2 over LTE). Nothing arrives on a closed session
        // anyway, and the hook is a no-op once the engine is stopped.
        //
        // Freeze application traffic, including queued PTT/audio resends, but
        // keep readers, ping replies and idles alive for the logout grace period.
        synchronized(allStreams) { allStreams.forEach { it.beginShutdown() } }
        try {
            val haveToken = authID.any { it != 0.toByte() }
            val serialClose = if (serialOpened) buildSerialOpenClose(close = true) else null
            if (serialClose != null) serial?.sendTracked(serialClose, duringShutdown = true)
            val ctrl = control
            if (haveToken && ctrl != null) {
                // Build ONCE and assign a real tracking sequence. The old raw
                // sends all had outer seq 0 and could not be requested back.
                val remove = buildAuth(ctrl, magic = 0x01)
                ctrl.sendDeauth(remove)
                Log.i(TAG, "logout sent: request=${u16le(remove, 26)} inner=${u16le(remove, 23)} " +
                    "seq=${u16le(remove, 6)} localSID=${hex(ctrl.localSID)} remoteSID=${hex(ctrl.remoteSID)}")
                repeat(2) {
                    Thread.sleep(80)
                    ctrl.sendRaw(remove, duringShutdown = true)
                    if (serialClose != null) serial?.sendRaw(serialClose, duringShutdown = true)
                }
                // Do NOT send stream goodbye before this wait: the radio needs
                // this transport to request a lost deauth (kappanhang: 500 ms).
                Thread.sleep(LOGOUT_GRACE_MS - 160)
                Log.i(TAG, "logout grace ended: acknowledged=${ctrl.deauthAcknowledged}")
                if (ctrl.deauthAcknowledged) saveStaleToken?.invoke(null)
                // With no matching acknowledgement, preserve the token for the
                // existing one-shot pre-login cleanup instead of assuming success.
            } else if (serialClose != null) {
                Thread.sleep(80)
            }
        } catch (_: Exception) {}
        try { audio?.close() } catch (_: Exception) {}
        try { serial?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
        // …and anything created but replaced along the way (see allStreams).
        synchronized(allStreams) {
            for (s in allStreams) try { s.close() } catch (_: Exception) {}
            allStreams.clear()
        }
        audio = null
        serial = null
        control = null
        try { pty.close() } catch (_: Exception) {}
        scope.cancel()
        connScope = null
        serialOpened = false
        serialOpening = false
        connInfoRequested = false
        if (reason != null) {
            _state.value = State(error = reason)
        } else if (_state.value.connected || _state.value.connecting) {
            _state.value = State()
        }
        Log.i(TAG, "Icom network disconnected${if (reason != null) " ($reason)" else ""}")
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        val cur = _state.value
        if (cur.connected) {
            // A late failure on a secondary stream must not masquerade as a lost
            // link: the control/CI-V session is still up and rigctld still works.
            // Show the message, keep the connection (the link watchdog decides
            // when the radio is really gone).
            _state.value = cur.copy(error = msg)
        } else {
            _state.value = State(error = msg)
        }
    }

    /* ───────────────────── control-stream protocol ───────────────── */

    private suspend fun controlLoop(ctrl: IcomStream, host: String, result: CompletableDeferred<String>) {
        try {
            ctrl.incoming.consumeEach { r ->
                synchronized(this) {
                    if (!isCurrentSession(ctrl.scope)) return@consumeEach
                    when {
                        r.size == 168 && r.u(0) == 0xa8 -> {
                            System.arraycopy(r, 66, a8ReplyID, 0, 16)
                            gotA8 = true
                            maybeRequestSerial(ctrl)
                        }
                        r.size == 64 && r.u(0) == 0x40 -> {
                            // Auth / re-auth reply. Logged so a field capture shows what
                            // the radio said around a dropped session (re-auth runs every 60 s).
                            Log.i(TAG, "control 0x40 auth reply: magic=0x${"%02x".format(r.u(21))} " +
                                "response=${hex(u32be(r, 48))} request=${u16le(r, 26)} inner=${u16le(r, 23)} " +
                                "localSID=${hex(u32be(r, 12))} remoteSID=${hex(u32be(r, 8))} opened=$serialOpened")
                            if (r.u(21) == 0x05) { authOk = true; maybeRequestSerial(ctrl) }
                        }
                        r.size == 80 && r.u(0) == 0x50 -> {
                            val rejected = r.u(48) == 0xFF && r.u(49) == 0xFF && r.u(50) == 0xFF
                            val radioDisconnect = r.u(48) == 0 && r.u(49) == 0 && r.u(50) == 0 && r.u(64) == 0x01
                            Log.i(TAG, "control 0x50 status: b48-50=%02x%02x%02x b64=%02x rejected=%b disconnect=%b opened=%b"
                                .format(r.u(48), r.u(49), r.u(50), r.u(64), rejected, radioDisconnect, serialOpened))
                            if (rejected) {
                                // After the streams are open the radio also emits this
                                // pattern; kappanhang ignores it there too.
                                if (!serialOpened) {
                                    fail("Auth rejected — try rebooting the radio")
                                    if (!result.isCompleted) result.complete("")
                                }
                            } else if (radioDisconnect) {
                                if (serialOpened) {
                                    // The radio ended the session. fail() would only show
                                    // a message and leave a zombie: tear down properly so
                                    // the ViewModel's link-loss handler reconnects.
                                    Log.w(TAG, "radio reported disconnect mid-session — tearing down for reconnect")
                                    Thread { disconnectIfCurrent(ctrl.scope, "Radio ended the session — reconnecting…") }.start()
                                } else {
                                    fail("Radio reported disconnect")
                                    if (!result.isCompleted) result.complete("")
                                }
                            }
                        }
                        r.size == 144 && r.u(0) == 0x90 && r.u(96) == 0x01 -> {
                            // The radio's "connected" status. Exactly one open per
                            // session, and only once WE have asked for the streams
                            // (kappanhang gates on the same synchronous flag).
                            if (connInfoRequested && !serialOpened && !serialOpening) {
                                openSerial(ctrl, host, r, result)
                            } else {
                                Log.i(TAG, "0x90 connected-status ignored (requested=$connInfoRequested " +
                                    "opening=$serialOpening opened=$serialOpened)")
                            }
                        }
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
        if (!serialOpened && !serialOpening && authOk && gotA8) {
            Log.i(TAG, "requesting serial+audio stream")
            connInfoRequested = true
            ctrl.sendTracked(buildConnInfo(ctrl))
        }
    }

    private fun openSerial(ctrl: IcomStream, host: String, r: ByteArray, result: CompletableDeferred<String>) {
        val scope = connScope ?: return
        serialOpening = true            // before anything async — see the field doc
        // The grant packet can carry refreshed IDs.
        ctrl.remoteSID = u32be(r, 8)
        ctrl.localSID = u32be(r, 12)
        System.arraycopy(r, 26, authID, 0, 6)
        // The grant may refresh the token — keep the persisted copy current so
        // an unclean exit removes the token the radio actually holds.
        saveStaleToken?.invoke(authID.joinToString("") { "%02x".format(it) })
        val devName = parseCString(r, 64)
        _state.value = _state.value.copy(deviceName = devName)
        Log.i(TAG, "serial/audio granted, device='$devName' — opening CI-V stream")

        scope.launch {
            // CI-V replies are few and each one matters (a lost PTT ack costs a
            // full rigctld timeout+retry round): track the radio's numbering and
            // ask for anything missing right away.
            val ser = synchronized(this) {
                if (!isCurrentSession(scope)) return@launch
                IcomStream(
                    "serial", host, SERIAL_PORT, scope,
                    rxTracker = IcomRxSeqTracker(giveUpAfterMs = 1000, reRequestMs = 150)
                ).also { serial = it }
            }
            val serialReady = ser.open()
            val path = synchronized(this) {
                if (!isCurrentSession(scope)) return@launch
                if (!serialReady) {
                    serialOpening = false
                    fail("CI-V stream handshake failed")
                    if (!result.isCompleted) result.complete("")
                    return@launch
                }
                ser.startPing(firstSeq = 1)
                ser.startIdle()
                serialInnerSeq = 0
                ser.sendTracked(buildSerialOpenClose(close = false))  // open CI-V

                val path = pty.open()
                if (path == null) {
                    serialOpening = false
                    fail("Could not create local pty")
                    if (!result.isCompleted) result.complete("")
                    return@launch
                }
                serialOpened = true
                serialOpening = false

                // Session-level liveness (see DATA_SILENCE_MS).
                scope.launch { serialDataWatchdog(ser) }

                // radio CI-V → pty (rigctld reads it)
                scope.launch { serialRxLoop(ser) }
                // pty (rigctld writes) → radio CI-V
                scope.launch { ptyTxLoop(ser) }
                ser.startStatsLog()
                path
            }

            // Phase 2 "full wireless": also bring up the audio stream (UDP 50003).
            // Control still succeeds even if audio fails — the user can fall back
            // to USB audio.
            openAudio(host, scope)

            if (isCurrentSession(scope) && !result.isCompleted) result.complete(path)
        }
    }

    private suspend fun openAudio(host: String, scope: CoroutineScope) {
        // Sequence statistics are observe-only; the jitter buffer gives
        // reordered audio audioBufferMs to arrive before concealing a gap.
        val aud = synchronized(this) {
            if (!isCurrentSession(scope)) return
            IcomStream(
                "audio", host, AUDIO_PORT, scope,
                rxTracker = IcomRxSeqTracker(giveUpAfterMs = audioBufferMs.toLong(), reRequestMs = 100)
            ).also { audio = it }
        }
        val audioReady = aud.open()
        synchronized(this) {
            if (!isCurrentSession(scope)) return
            if (!audioReady) {
                Log.w(TAG, "audio stream handshake failed — control works, audio stays on USB")
                audio = null
                return
            }
            aud.startPing(firstSeq = 1)
            // NOTE: the audio stream sends NO periodic idle pkt0 (only the audio data
            // packets are tracked) — matching the reference implementation.
            audioSendSeq = 0
            scope.launch { audioRxLoop(aud) }
            aud.startStatsLog()
            _state.value = _state.value.copy(audioConnected = true)
            Log.i(TAG, "audio stream up (UDP $AUDIO_PORT, rx ${audioRate}Hz tx ${txAudioRate}Hz, " +
            "radio TX buffer ${audioBufferMs}ms) — full wireless")
        }
    }

    /* ───────────────────── audio (UDP 50003) bridge ──────────────── */

    /**
     * Consume incoming audio packets, reorder them by sequence in a small jitter
     * buffer, and hand the in-order PCM payloads to [onAudioPcm].
     *
     * Audio data packet: byte0..1 = length LE (0x056c=1388 → 1364B payload, or
     * 0x0244=580 → 556B payload), seq (LE) at [6:8] (set as a tracked pkt0 seq),
     * 0x80 marker at [16], s16le PCM payload at [24:]. The 1364/556 split is just
     * MTU fragmentation of one continuous 48 kHz mono stream. At 16 kHz, a
     * 20 ms frame fits in one 640-byte payload. Feed both formats in order.
     */
    private suspend fun audioRxLoop(aud: IcomStream) {
        var pcmPackets = 0L
        var fedSamples = 0L
        var sawConsumer = false
        // 48 kHz uses two fragments per 20 ms frame; 16 kHz uses one.
        // Reordering waits scale with that cadence, not a fixed packet count.
        val packetMs = if (audioRate == 16000) 20 else 10
        val jitter = IcomAudioJitter(
            maxPackets = (audioBufferMs / packetMs).coerceAtLeast(2), sampleRate = audioRate
        ) { pkt ->
            val shorts = IcomAudioPacket.pcm(pkt)
            val cb = onAudioPcm
            if (cb != null) {
                if (!sawConsumer) { sawConsumer = true; Log.i(TAG, "audio RX: first PCM delivered to decoder") }
                cb(shorts)
                fedSamples += shorts.size
            }
        }
        var queueDrops = aud.incomingDrops.get()
        try {
            aud.incoming.consumeEach { r ->
                if (!isCurrentSession(aud.scope)) return@consumeEach
                val dropped = aud.incomingDrops.get()
                if (dropped != queueDrops) {
                    jitter.reset()
                    queueDrops = dropped
                }
                if (IcomAudioPacket.isAudio(r)) {
                    if (pcmPackets == 0L) Log.i(TAG, "audio RX: first audio packet from radio (size=${r.size})")
                    pcmPackets++
                    if (pcmPackets % 500L == 0L)
                        Log.i(TAG, "audio RX: $pcmPackets pkts, fed=$fedSamples samples, " +
                            "concealed=${jitter.concealed} late=${jitter.lateDropped} " +
                            "queueDropped=$queueDrops resync=${jitter.resyncs} rate=$audioRate consumer=${onAudioPcm != null}")
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
    private var audioTxNoStreamLoggedMs = 0L

    override fun sendAudioFrame(pcm: ShortArray) {
        val aud = audio
        if (aud == null) {
            // No audio stream (its handshake failed, or the session is gone):
            // the rig would sit keyed with no modulation. Say so, once a second.
            val now = System.currentTimeMillis()
            if (now - audioTxNoStreamLoggedMs > 1000) {
                audioTxNoStreamLoggedMs = now
                Log.w(TAG, "audio TX: no audio stream — frame dropped (rig keyed, no modulation)")
            }
            return
        }
        if (audioSendSeq == 0) {
            Log.i(TAG, "audio TX: first frame ${pcm.size} samples @ ${txAudioRate} Hz (${pcm.size * 2} B) " +
                "on ${aud.name} localSID=${hex(aud.localSID)} remoteSID=${hex(aud.remoteSID)}")
        }
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

    private suspend fun reauthLoop(ctrl: IcomStream) {
        try {
            while (true) {
                delay(60_000)
                synchronized(this) {
                    if (!isCurrentSession(ctrl.scope)) return
                    Log.i(TAG, "reauth sent (60 s token refresh)")
                    ctrl.sendTracked(buildAuth(ctrl, magic = 0x05))
                }
            }
        } catch (_: CancellationException) {}
    }

    /**
     * Detect a session the radio has silently dropped (see DATA_SILENCE_MS):
     * no tracked packet (idle or data) on the CI-V stream for a while although
     * the stream itself is still up. The teardown runs on its own thread
     * because disconnect() cancels the scope this coroutine lives in.
     */
    private suspend fun serialDataWatchdog(ser: IcomStream) {
        try {
            while (true) {
                delay(2000)
                if (!serialOpened || !isCurrentSession(ser.scope)) return
                val last = ser.lastDataRxMs
                if (last == 0L) continue                   // never saw one yet: nothing to judge
                val silent = System.currentTimeMillis() - last
                if (silent > DATA_SILENCE_MS) {
                    Log.w(TAG, "serial stream: no idle/data from the radio for ${silent}ms while pings continue " +
                        "— radio dropped the session; tearing down for reconnect")
                    Thread { disconnectIfCurrent(ser.scope, "Radio dropped the session (CI-V silent) — reconnecting…") }.start()
                    return
                }
            }
        } catch (_: CancellationException) {}
    }

    /* ───────────────────── serial (CI-V) bridge ──────────────────── */

    private suspend fun serialRxLoop(ser: IcomStream) {
        val recent = LinkedHashMap<Int, Pair<Long, ByteArray>>()
        try {
            ser.incoming.consumeEach { r ->
                if (!isCurrentSession(ser.scope)) return@consumeEach
                // A delayed UDP packet from the previous login must not enter
                // this rigctld byte stream. Its socket address can be identical.
                if (r.size < 16 || u32be(r, 8) != ser.remoteSID || u32be(r, 12) != ser.localSID)
                    return@consumeEach
                // CI-V data packet: r[0]=0x15+len, r[16]=0xc1, r[17]=len, payload at 21.
                if (r.size >= 22 && r.u(16) == 0xc1 && (r.u(0) - 0x15) == r.u(17)) {
                    val len = r.u(17)
                    if (21 + len <= r.size && len > 0) {
                        val now = System.nanoTime()
                        val seq = u16le(r, 6)
                        val previous = recent[seq]
                        if (previous != null && now - previous.first < 15_000_000_000L &&
                            previous.second.contentEquals(r)) {
                            Log.w(TAG, "serial RX: duplicate CI-V packet ignored seq=$seq")
                            return@consumeEach
                        }
                        recent.remove(seq)
                        recent[seq] = now to r
                        if (recent.size > 256) recent.remove(recent.keys.first())
                        val civ = r.copyOfRange(21, 21 + len)
                        pty.write(civ, civ.size)
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
                val d = pty.read(100) ?: break   // null = pty closed
                if (d.isEmpty()) continue
                for (b in d) {
                    frame.add(b)
                    val v = b.toInt() and 0xFF
                    // CI-V frames end in 0xFD (0xFC for some); also cap at 80 bytes.
                    if (v == 0xFD || v == 0xFC || frame.size >= 80) {
                        val civ = frame.toByteArray()
                        val pkt = buildSerialData(ser, civ)
                        ser.sendTracked(pkt)   // fills the tracking seq into pkt
                        serialInnerSeq = (serialInnerSeq + 1) and 0xFFFF
                        // Let Hamlib and explicit radio retransmit requests
                        // retry loss. Unsolicited copies of a PTT query/set can
                        // produce extra replies that acknowledge a later command.
                        if (isCivControlWrite(civ)) Log.i(TAG,
                            "CI-V TX seq=${u16le(pkt, 6)} bytes=${civ.joinToString("") { "%02X".format(it) }}")
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
        // The radio echoes this per-attempt nonce in the login reply. Keep it
        // stable for retries, but never reuse the previous attempt's identity.
        p[26] = loginRequestId.toByte(); p[27] = (loginRequestId ushr 8).toByte()
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

    /** Token-removal (deauth) packet for an EXPLICIT token — used by the
     *  pre-login cleanup to delete the previous session's token. Identical to
     *  buildAuth(magic = 0x01) except the token bytes come from the argument
     *  instead of the live authID, and the auth inner seq is left at 0 and NOT
     *  advanced: the login that follows must still go out as authInnerSeq 0,
     *  exactly as it does when there is no stale token — advancing it here made
     *  the login leave as seq 3 and radios stricter than the IC-705 (the
     *  IC-7300MK2) refused it. */
    private fun buildTokenRemove(s: IcomStream, token: ByteArray): ByteArray {
        val p = ByteArray(64)
        p[0] = 0x40
        p.putSid(8, s.localSID); p.putSid(12, s.remoteSID)
        p[19] = 0x30; p[20] = 0x01; p[21] = 0x01
        System.arraycopy(token, 0, p, 26, minOf(token.size, 6))
        return p
    }

    /** Parse a 12-hex-char token back to its 6 bytes, or null if malformed. */
    private fun parseTokenHex(hex: String): ByteArray? {
        if (hex.length != 12) return null
        return try {
            ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
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
        p[118] = (audioRate ushr 8).toByte(); p[119] = audioRate.toByte()  // rx sample rate (u32 BE @116)
        val txRate = txAudioRate                                                      // tx sample rate (u32 BE @120)
        p[122] = (txRate ushr 8).toByte(); p[123] = txRate.toByte()
        p[126] = (SERIAL_PORT ushr 8).toByte(); p[127] = SERIAL_PORT.toByte()
        p[130] = (AUDIO_PORT ushr 8).toByte(); p[131] = AUDIO_PORT.toByte()
        val bufMs = audioBufferMs                                  // radio TX buffer, u32 BE @132
        p[132] = (bufMs ushr 24).toByte(); p[133] = (bufMs ushr 16).toByte()
        p[134] = (bufMs ushr 8).toByte(); p[135] = bufMs.toByte()
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

    /** Log writes that could change the dial/VFO/PTT, without logging every poll. */
    private fun isCivControlWrite(civ: ByteArray): Boolean =
        civ.size >= 7 &&
        civ[0] == 0xFE.toByte() && civ[1] == 0xFE.toByte() &&
        (civ.u(4) in setOf(0x05, 0x07) ||
            (civ.u(4) == 0x25 && civ.size > 7) ||
            (civ.u(4) == 0x1C && civ.u(5) == 0 && civ.size > 7))

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
        val scope: CoroutineScope,
        /** When set, the radio's tracked packets on this stream are deduplicated
         *  and gaps are requested back (kappanhang/wfview behaviour). */
        private val rxTracker: IcomRxSeqTracker? = null
    ) {
        var localSID = 0
        @Volatile var remoteSID = 0

        private var socket: DatagramSocket? = null
        @Volatile private var running = false
        @Volatile private var shuttingDown = false
        private val sendLock = Any()
        @Volatile private var deauthRequest: ByteArray? = null
        @Volatile var deauthAcknowledged = false
            private set

        private var trackSeq = 1                       // pkt0 tracking seq (bytes 6-7)
        private val txBuf = LinkedHashMap<Int, ByteArray>()
        private val isAudio = name == "audio"

        init { synchronized(allStreams) { allStreams.add(this) } }

        // Link diagnostics (logged by startStatsLog while they change).
        private var txTracked = 0L          // tracked packets we sent
        private var rxPackets = 0L          // datagrams from the radio
        private var radioReqSingle = 0L     // radio asked for one missing packet
        private var radioReqRange = 0L      // radio asked for a range
        private var radioReqPackets = 0L    // packets covered by those requests
        private var resentPackets = 0L      // resent from txBuf
        private var resentAsIdle = 0L       // requested but no longer buffered

        private var pingSeq = 1
        private var pingInner = 0x8304
        @Volatile private var keepaliveStarted = false

        /** Wall-clock of the last datagram received from the radio on this
         *  stream, stamped for every inbound packet (pings and idles included).
         *  The control-stream watchdog reads it to detect a dead transport. */
        @Volatile var lastRxMs = System.currentTimeMillis()

        /** Wall-clock of the last TRACKED packet (idle pkt0 or data) from the
         *  radio — pings excluded, since the radio keeps pinging on a session it
         *  has dropped. The session-level liveness signal; 0 = none seen yet. */
        @Volatile var lastDataRxMs = 0L

        val incomingDrops = AtomicLong()
        // Never queue an unbounded recording behind a slow decoder. Audio only:
        // control and CI-V messages must retain their original ordered delivery.
        val incoming = if (isAudio) Channel<ByteArray>(
            capacity = (300 / if (audioRate == 16000) 20 else 10),
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { incomingDrops.incrementAndGet() }
        ) else Channel<ByteArray>(Channel.UNLIMITED)

        suspend fun open(): Boolean {
            return try {
                synchronized(this@IcomNetworkManager) {
                    if (!isCurrentSession(scope) || shuttingDown) return false
                    // Bind the LOCAL udp port to the same number as the radio's port
                    // (control 50001 / serial 50002 / audio 50003). The IC-705 streams
                    // data — especially audio on 50003 — back to that specific local
                    // port, so an ephemeral source port can leave audio never arriving
                    // even though the handshake succeeds. kappanhang binds local==remote
                    // for all three streams. Fall back to ephemeral if the port is busy.
                    val sock = try {
                        DatagramSocket(port)
                    } catch (e: Exception) {
                        Log.w(TAG, "$name: bind local port $port failed (${e.message}); using ephemeral")
                        DatagramSocket()
                    }
                    sock.connect(InetSocketAddress(InetAddress.getByName(host), port))
                    sock.soTimeout = 400
                    socket = sock
                    computeLocalSid(sock)
                    running = true
                    scope.launch(Dispatchers.IO) { readerLoop() }
                }

                // Handshake with retries. Over LTE/VPN the first packets are
                // routinely lost while the path warms up (LTE idle→active, VPN
                // tunnel state, ARP on the far LAN) — the previous two
                // fire-and-forget SYNs + single 3 s wait failed with "Control
                // handshake failed" on a path where another Icom client works.
                // kappanhang/wfview resend the SYN until the radio answers; do
                // the same for all three streams (control/serial/audio share
                // this open()).
                var r4: ByteArray? = null
                for (attempt in 1..8) {
                    sendRaw(plain(0x03, withRemote = false))
                    r4 = expect(16, byteArrayOf(0x10, 0, 0, 0, 0x04, 0, 0, 0), timeoutMs = 1000)
                    if (r4 != null) break
                    Log.i(TAG, "$name: no SYN reply (attempt $attempt/8)")
                }
                if (r4 == null) { close(); return false }   // free the port + reader thread
                remoteSID = u32be(r4, 8)
                var pkt6Ok = false
                for (attempt in 1..5) {
                    sendRaw(pkt6())
                    if (expect(16, byteArrayOf(0x10, 0, 0, 0, 0x06, 0, 0x01, 0), timeoutMs = 1000) != null) {
                        pkt6Ok = true
                        break
                    }
                    Log.i(TAG, "$name: no pkt6 reply (attempt $attempt/5)")
                }
                if (!pkt6Ok) { close(); return false }      // goodbye to the half-open stream, free the port
                trackSeq = 1
                Log.i(TAG, "$name stream up: localSID=${hex(localSID)} remoteSID=${hex(remoteSID)}")
                true
            } catch (e: CancellationException) {
                close()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$name open failed: ${e.message}")
                try { close() } catch (_: Exception) {}
                false
            }
        }

        /** One stream-level goodbye (type 0x05); close() repeats it. */
        fun sendGoodbye() {
            if (remoteSID != 0 && running) sendRaw(plain(0x05, withRemote = true), duringShutdown = true)
        }

        fun beginShutdown() {
            synchronized(sendLock) {
                shuttingDown = true
                // A late retransmit request must never replay an old PTT-on or
                // re-auth after logout. Only teardown packets/idles remain.
                txBuf.clear()
            }
        }

        fun sendDeauth(packet: ByteArray) {
            deauthRequest = packet.copyOf()
            sendTracked(packet, duringShutdown = true)
        }

        private fun computeLocalSid(sock: DatagramSocket) {
            val addr = sock.localAddress?.address
            val ip = if (addr != null && addr.size >= 4) {
                val o = addr.size - 4
                ((addr[o].toInt() and 0xFF) shl 24) or ((addr[o + 1].toInt() and 0xFF) shl 16) or
                ((addr[o + 2].toInt() and 0xFF) shl 8) or (addr[o + 3].toInt() and 0xFF)
            } else 0
            // Salt the low 16 bits per session instead of using the local port:
            // local ip:port are IDENTICAL on every connection here (the local
            // port is bound to match the remote's), so a port-based SID collides
            // with the radio's record of a not-yet-expired previous session and
            // the login is silently ignored. The SID is just our session
            // identifier echoed back by the radio — any value works, so make it
            // unique per connect.
            localSID = (ip shl 16) or kotlin.random.Random.nextInt(0x10000)
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
                        // A later seq lets the radio notice a missing deauth
                        // and request it during the logout grace period.
                        sendTracked(idlePacket(), duringShutdown = true)
                    }
                } catch (_: CancellationException) {}
            }
        }

        /** Fire [onLost] once if no datagram arrives from the radio for
         *  [timeoutMs]. Started only after the stream is fully up, so the
         *  handshake's own retries aren't cut short. */
        fun startWatchdog(timeoutMs: Long, onLost: () -> Unit) {
            lastRxMs = System.currentTimeMillis()
            scope.launch {
                try {
                    while (isActive && running) {
                        delay(2000)
                        if (System.currentTimeMillis() - lastRxMs > timeoutMs) {
                            onLost()
                            break
                        }
                    }
                } catch (_: CancellationException) {}
            }
        }

        /**
         * Periodic one-line link report (only while the counters change): how
         * much the radio had to ask us to resend (uplink loss) and how much we
         * had to ask the radio (downlink loss). Lets a field log tell packet
         * loss from a stalled pump or a starved radio buffer without a sniffer.
         */
        fun startStatsLog(intervalMs: Long = 5000) {
            scope.launch {
                var last = ""
                try {
                    while (isActive && running) {
                        delay(intervalMs)
                        val rx = rxTracker?.stats?.toString() ?: "pkts=$rxPackets"
                        val line = "$name link: tx=$txTracked radioReq=${radioReqSingle}s/${radioReqRange}r " +
                            "(${radioReqPackets} pkts) resent=$resentPackets idleFill=$resentAsIdle | rx: $rx"
                        if (line != last) { Log.i(TAG, line); last = line }
                    }
                } catch (_: CancellationException) {}
            }
        }

        /** Discard every packet already queued on [incoming] (nothing blocks). */
        fun drainPending() {
            while (incoming.tryReceive().getOrNull() != null) { /* drop */ }
        }

        suspend fun expect(
            len: Int, prefix: ByteArray, timeoutMs: Long = 3000,
            accept: (ByteArray) -> Boolean = { true }
        ): ByteArray? =
            withTimeoutOrNull(timeoutMs) {
                while (isActive) {
                    val r = incoming.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                    if (r.size == len && r.startsWith(prefix) && accept(r)) return@withTimeoutOrNull r
                }
                null
            }

        fun sendRaw(p: ByteArray, duringShutdown: Boolean = false) {
            synchronized(sendLock) {
                if (!running || (shuttingDown && !duringShutdown)) return
                try { socket?.send(DatagramPacket(p, p.size)) }
                catch (e: Exception) { if (running) Log.w(TAG, "$name send failed: ${e.message}") }
            }
        }

        /** Assign the next tracking seq, store for retransmit, then send. */
        fun sendTracked(p: ByteArray, duringShutdown: Boolean = false) {
            synchronized(sendLock) {
                if (!running || (shuttingDown && !duringShutdown)) return
                txTracked++
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
            synchronized(sendLock) {
                if (!running && socket == null) return
                // Polite stream-level disconnect (kappanhang/wfview send type 0x05
                // before closing). Without it the radio keeps this session alive
                // until its own timeout and silently ignores the NEXT login —
                // the reported "first connect works, later connects get 'No login
                // reply'" pattern. Sent twice: a single unacked UDP packet on a
                // link we are about to drop.
                if (remoteSID != 0) {
                    try {
                        sendGoodbye()
                        sendGoodbye()
                    } catch (_: Exception) {}
                }
                running = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                incoming.close()
            }
        }

        private suspend fun readerLoop() {
            val buf = ByteArray(1500)
            val sock = socket ?: return
            // Also stop when the session scope is cancelled: a stream that was
            // never closed explicitly must not keep its port and thread forever.
            while (running && scope.isActive) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    val r = buf.copyOf(dp.length)
                    // Socket addresses are reused on reconnect. Reject the old
                    // session before processing even pings/retransmit requests:
                    // an old request must not replay a current control packet.
                    if (r.size < 16 || u32be(r, 12) != localSID ||
                        (remoteSID != 0 && u32be(r, 8) != remoteSID)) continue
                    lastRxMs = System.currentTimeMillis()   // any traffic = radio alive
                    rxPackets++
                    // Read the logout acknowledgement here, not in controlLoop:
                    // its state mutations are deliberately blocked by teardown.
                    val deauth = deauthRequest
                    if (deauth != null && matchesDeauthReply(r, deauth)) deauthAcknowledged = true
                    when {
                        isPkt7(r) -> { rxTracker?.onPing(u16le(r, 6)); handlePkt7(r) }
                        isRetransmitRequest(r) -> handleRetransmitRequest(r)
                        // Everything else the radio sends is one of its TRACKED
                        // packets — an idle pkt0 or data — numbered at bytes 6-7.
                        // Observed (stats only, see admitTracked); idles carry
                        // nothing further.
                        else -> {
                            if (isTracked(r)) lastDataRxMs = System.currentTimeMillis()
                            if (admitTracked(r) && !isIdle(r)) incoming.trySend(r)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // loop to re-check `running` / scope
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "$name reader: ${e.message}")
                    break
                }
            }
            if (running) {
                // Left because the scope died, not via close(): release the port.
                running = false
                try { sock.close() } catch (_: Exception) {}
                incoming.close()
            }
        }

        private fun matchesDeauthReply(r: ByteArray, request: ByteArray): Boolean =
            r.size == 64 && r.u(0) == 0x40 && r.u(4) == 0 && r.u(5) == 0 &&
                r.u(20) == 0x02 && r.u(21) == 0x01 && u32be(r, 48) == 0 &&
                u16le(r, 23) == u16le(request, 23) &&
                u32be(r, 8) == u32be(request, 12) && u32be(r, 12) == u32be(request, 8) &&
                (26..31).all { r[it] == request[it] }

        private fun isPkt7(r: ByteArray) =
            r.size == 21 && r.u(1) == 0 && r.u(2) == 0 && r.u(3) == 0 && r.u(4) == 0x07 && r.u(5) == 0

        /** Radio asks us to resend: single seq (0x10, type 1) or ranges (0x18, type 1). */
        private fun isRetransmitRequest(r: ByteArray) = r.size >= 16 &&
            r.u(1) == 0 && r.u(2) == 0 && r.u(3) == 0 && r.u(4) == 0x01 && r.u(5) == 0 &&
            (r.u(0) == 0x10 || r.u(0) == 0x18)

        /** Idle pkt0 (16 bytes, type 0): keep-alive that still consumes a seq. */
        private fun isIdle(r: ByteArray) = r.size == 16 && r.u(0) == 0x10 &&
            r.u(1) == 0 && r.u(2) == 0 && r.u(3) == 0 && r.u(4) == 0 && r.u(5) == 0

        /** Any type-0 packet of 16+ bytes is tracked (radio's seq at bytes 6-7).
         *  Bytes 0-3 are the u32 LE length — NOT zero for packets over 255 bytes
         *  (every audio packet), which is why the audio stream tracked nothing
         *  in v1.6.15/16 while the serial stream did. */
        private fun isTracked(r: ByteArray) = r.size >= 16 && r.u(4) == 0 && r.u(5) == 0

        /**
         * Sequence bookkeeping for the radio's tracked packets — OBSERVE ONLY.
         *
         * v1.6.15 also sent retransmit requests for every hole and dropped
         * "duplicates". Field stats (IC-7300MK2): the serial stream showed a
         * steady 10 missing/s — exactly the radio's ping rate — with nothing ever
         * healed, i.e. the radio's pkt7 pings share its tracking counter, and
         * every request we sent asked for a ping. Until the model is confirmed
         * from these counters (see Stats.healedByPing), nothing is requested and
         * nothing is dropped; the stats still tell downlink loss from ping gaps.
         */
        private fun admitTracked(r: ByteArray): Boolean {
            val tracker = rxTracker ?: return true
            if (!isTracked(r)) return true
            tracker.onPacket(u16le(r, 6), System.currentTimeMillis())
            tracker.expireHoles(System.currentTimeMillis())
            return true
        }

        /** Ask the radio to resend [range] — same wire format it uses toward us
         *  (kappanhang sendRetransmitRequest/…ForRanges), sent twice like it does. */
        private fun sendRetransmitRequest(range: IcomRxSeqTracker.SeqRange) {
            val p: ByteArray
            if (range.first == range.last) {
                p = ByteArray(16)
                p[0] = 0x10; p[4] = 0x01
                p[6] = range.first.toByte(); p[7] = (range.first ushr 8).toByte()
                p.putSid(8, localSID); p.putSid(12, remoteSID)
            } else {
                p = ByteArray(20)
                p[0] = 0x18; p[4] = 0x01
                p.putSid(8, localSID); p.putSid(12, remoteSID)
                p[16] = range.first.toByte(); p[17] = (range.first ushr 8).toByte()
                p[18] = range.last.toByte(); p[19] = (range.last ushr 8).toByte()
            }
            sendRaw(p); sendRaw(p)
        }

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
                sendRaw(p, duringShutdown = true)
            }
            // else: reply to our own ping — nothing to do.
        }

        private fun handleRetransmitRequest(r: ByteArray) {
            if (r.u(0) == 0x10) {
                radioReqSingle++; radioReqPackets++
                resend(u16le(r, 6), burst = false)
            } else {
                radioReqRange++
                var i = 16
                while (i + 4 <= r.size) {
                    val start = u16le(r, i); val end = u16le(r, i + 2)
                    var s = start
                    var n = 0
                    while (n < 512) {   // bound a malformed range
                        resend(s, burst = true); n++
                        if (s == end) break
                        s = (s + 1) and 0xFFFF
                    }
                    radioReqPackets += n
                    i += 4
                }
            }
        }

        /**
         * Resend a tracked packet the radio missed. Singles get the protocol's
         * customary double send. A RANGE request means a burst was lost; on the
         * audio stream each packet of it is resent once, so a weak LTE uplink
         * is not hit with twice the burst it just failed to carry (a lost resend
         * is simply requested again).
         */
        private fun resend(seq: Int, burst: Boolean) {
            synchronized(sendLock) {
                val d = txBuf[seq and 0xFFFF]
                if (d != null) {
                    resentPackets++
                    sendRaw(d, duringShutdown = true)
                    if (!(burst && isAudio)) sendRaw(d, duringShutdown = true)
                } else {
                    resentAsIdle++
                    val idle = idlePacketWithSeq(seq)
                    sendRaw(idle, duringShutdown = true); sendRaw(idle, duringShutdown = true)
                }
            }
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

package yakumo2683.RADEdecode.ui

import android.app.Application
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceInfo
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import yakumo2683.RADEdecode.AudioBridge
import yakumo2683.RADEdecode.RxOutputTester
import yakumo2683.RADEdecode.location.LocationTracker
import yakumo2683.RADEdecode.network.FreeDVReporter
import yakumo2683.RADEdecode.network.HermesNetworkManager
import yakumo2683.RADEdecode.network.IcomNetworkManager
import yakumo2683.RADEdecode.network.RigController
import yakumo2683.RADEdecode.network.RigctldProcess
import yakumo2683.RADEdecode.network.VbanNetworkManager
import yakumo2683.RADEdecode.service.AudioService
import yakumo2683.RADEdecode.usb.UsbSerialManager

/** RF tail between EOO playout and PTT release (ms): lets the rig flush its own
 *  USB-audio/TX chain so the end of the callsign isn't clipped. Other SDR apps
 *  insert a similar ~200ms TX delay. */
private const val TX_PTT_TAIL_MS = 200L

class TransceiverViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Map full-width characters (Japanese IME input) onto their ASCII
         *  equivalents; other characters pass through unchanged. */
        private fun toAscii(c: Char): Char = when (c) {
            in '！'..'～' -> c - 0xFEE0   // full-width ASCII block
            '　' -> ' '                        // ideographic space
            else -> c
        }

        /**
         * Normalize a callsign for EOO TX and FreeDV Reporter: full-width →
         * ASCII, uppercase, and only characters a callsign can contain
         * (A–Z, 0–9, '/'). qso.freedv.org refuses the whole connection when
         * the callsign contains a space or full-width letter, and the station
         * then never appears on the site.
         */
        fun sanitizeCallsign(raw: String): String =
            raw.map { toAscii(it) }
                .joinToString("")
                .uppercase()
                .filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' }
                .take(8)

        /** Normalize a Maidenhead grid square: full-width → ASCII, uppercase,
         *  letters/digits only (e.g. "PM95UR"). */
        fun sanitizeGridSquare(raw: String): String =
            raw.map { toAscii(it) }
                .joinToString("")
                .uppercase()
                .filter { it in 'A'..'Z' || it in '0'..'9' }
                .take(6)
    }

    /* ── FreeDV Reporter ────────────────────────────────────── */
    val reporter = FreeDVReporter(viewModelScope)
    val locationTracker = LocationTracker(application)

    /* ── Rig controller (rigctld TCP) ──────────────────────── */
    private val rigController = RigController()
    private val rigctldProcess = RigctldProcess(application)
    val rigState: StateFlow<RigController.RigState> = rigController.state

    /* ── USB serial (for local rigctld via USB Host API) ─── */
    val usbSerialManager = UsbSerialManager(application)
    val usbSerialState: StateFlow<UsbSerialManager.UsbSerialState> = usbSerialManager.state

    /* ── Icom network rig control (RS-BA1 WLAN, e.g. IC-705) ─── */
    private val icomNetwork = IcomNetworkManager()
    val icomNetworkState: StateFlow<IcomNetworkManager.State> = icomNetwork.state

    /* ── Hermes-Lite 2 direct network control (openHPSDR protocol 1) ─── */
    private val hermesNetwork = HermesNetworkManager(application)
    val hermesState: StateFlow<HermesNetworkManager.State> = hermesNetwork.state

    /* ── VBAN network audio (Voicemeeter → Thetis VAC, keeps PureSignal) ─── */
    private val vbanNetwork = VbanNetworkManager(application)
    val vbanState: StateFlow<VbanNetworkManager.State> = vbanNetwork.state

    data class UiState(
        val isRunning: Boolean = false,    // RX is active
        val leCommActive: Boolean = false, // LE Audio (LC3) communication session active
        val isTx: Boolean = false,         // TX is active
        /** TX→RX hand-over in progress (EOO drain, RF tail, PTT release): the
         *  engine is momentarily neither RX nor TX, but the UI must not fall back
         *  to the Start screen for that second. */
        val txSwitching: Boolean = false,
        val pttControlError: Boolean = false,
        val syncState: Int = 0,
        val snrDb: Int = 0,
        val freqOffsetHz: Float = 0f,
        val inputLevelDb: Float = -100f,
        val outputLevelDb: Float = -100f,
        val txLevelDb: Float = -100f,
        val lastCallsign: String = "",
        val txCallsign: String = "",
        val spectrum: FloatArray = FloatArray(AudioBridge.SPECTRUM_BINS) { -100f },
        val unprocessedRejected: Boolean = false,
        val devices: List<AudioBridge.AudioDevice> = emptyList(),
        val outputDevices: List<AudioBridge.AudioDevice> = emptyList(),
        val selectedDeviceId: Int = -1,
        val selectedRxOutputDeviceId: Int = AudioService.RX_OUTPUT_AUTO,
        val selectedOutputDeviceId: Int = -1,
        val builtInMicId: Int = -1,
        val serviceBound: Boolean = false,
        val powerSaveMode: Boolean = false,  // 效能節約模式: hide spectrum/waterfall + skip FFT on weak devices
        val pttHoldMode: Boolean = false,    // hold-to-talk: TX is keyed only while the TX button is held
        val bluetoothMicTx: Boolean = false, // experimental: capture TX voice from the Bluetooth headset mic (SCO)
        val txMicDeviceId: Int = -1,         // selected TX mic input device id (-1 = built-in mic)
        val radeV2Mode: Boolean = false,     // experimental: RADE V2 waveform (not interoperable with V1)
        val analogMonitor: Boolean = false   // RX analog SSB monitor: raw channel audio to speaker (freedv-gui "Analog")
    ) {
        val syncText: String get() = when (syncState) {
            0 -> "SEARCH"
            1 -> "CANDIDATE"
            2 -> "SYNC"
            else -> "UNKNOWN"
        }

        val isSynced: Boolean get() = syncState == 2
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var audioService: AudioService? = null
    private var serviceCollectJob: Job? = null

    /** In-flight TX→RX transition (EOO drain → PTT tail → unkey → resume RX). */
    private var txStopJob: Job? = null
    @Volatile private var txRequestId = 0L

    /** True while the app itself has keyed the rig's PTT (doSwitchToTx). The
     *  unkey path checks THIS instead of rigController.isConnected so a
     *  transient Wi-Fi/rigctld drop can't skip the unkey and leave the rig
     *  stuck in TX. */
    @Volatile private var pttKeyedByApp = false
    /** Submit CAT immediately, even while the main thread is opening audio.
     *  Join before unkeying so a slow T 1 cannot arrive after T 0. */
    private var pttKeyJob: Deferred<PttKeyOutcome>? = null

    /** Result of the CAT key-on request. Only REFUSED (the rig itself reports
     *  PTT still off) aborts the over: a missing or late acknowledgement is no
     *  reason to cut a QSO the operator can see is on the air (v1.6.14 did,
     *  ~5 s into every over on rigs with slow CAT replies). */
    private enum class PttKeyOutcome { ACKED, CONFIRMED, UNKNOWN, REFUSED }
    /** Background unkey retry, started when the quick attempts in
     *  stopTxAndUnkeyPtt all failed. Keeps trying — riding through an Icom
     *  auto-reconnect — until rigctld acknowledges "T 0" or the operator
     *  disconnects. A lossy LTE/VPN link must never leave the rig in TX. */
    private var pttUnkeyJob: Job? = null

    /** True while the app has keyed the rig by asserting the serial RTS line
     *  (CAT-less interfaces, Rig tab "RTS PTT" option). */
    private var rtsPttKeyed = false

    /** True once the operator has pressed Disconnect on an Icom Wi-Fi session,
     *  so the link-loss observer doesn't treat that intentional teardown as an
     *  unexpected drop. Reset when a fresh Icom connect starts. */
    @Volatile private var icomUserDisconnect = false

    /** Guards the link-loss teardown so overlapping state emissions can't run it
     *  twice concurrently. */
    @Volatile private var icomLinkLostHandling = false

    /** Last Icom connect parameters — the link-loss handler reconnects with them. */
    private var icomLastHost = ""
    private var icomLastPort = IcomNetworkManager.DEFAULT_CONTROL_PORT
    private var icomLastUser = ""
    private var icomLastPass = ""
    private var icomAutoReconnectJob: Job? = null
    /** The engine was running (RX or TX) when the Icom link dropped — resume RX
     *  decoding after the automatic reconnect. */
    @Volatile private var icomResumeRxAfterReconnect = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AudioService.LocalBinder).service
            audioService = service
            service.reporter = reporter
            // Enables full-wireless audio when a network rig is up (IC-705
            // Wi-Fi or a Hermes-Lite 2 on the LAN).
            service.networkRig = when {
                hermesNetwork.isConnected -> hermesNetwork
                vbanNetwork.isConnected -> vbanNetwork
                else -> icomNetwork
            }
            // Restore persisted audio settings
            service.setInputGain(prefs.getFloat("input_gain", 4.0f))
            service.setTxMicGain(prefs.getFloat("tx_mic_gain", 4.0f))
            service.setOutputVolume(prefs.getFloat("output_volume", 1.0f))
            service.setTxVolume(prefs.getFloat("tx_volume", 0.2f))
            service.powerSaveMode = _uiState.value.powerSaveMode
            service.radeV2Mode = _uiState.value.radeV2Mode
            _uiState.value = _uiState.value.copy(serviceBound = true)
            startCollectingServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            serviceCollectJob?.cancel()
            _uiState.value = _uiState.value.copy(serviceBound = false, isRunning = false, isTx = false)
        }
    }

    private val prefs = application.getSharedPreferences("rade_prefs", Context.MODE_PRIVATE)
    // Rig-tab prefs (DTR/RTS lines, RTS-PTT) — written by RigScreen.
    private val rigPrefs = application.getSharedPreferences("rig_prefs", Context.MODE_PRIVATE)
    private val savedRxInputDeviceId: Int
        get() = prefs.getInt("rx_input_device", -1)

    /** User's Reporter toggle preference, independent of whether reporter is actually connected. */
    private val _reporterEnabledPref = MutableStateFlow(false)
    val reporterEnabledPref: StateFlow<Boolean> = _reporterEnabledPref.asStateFlow()

    /**
     * Manually entered dial frequency (Hz), used when no rig is connected —
     * shown on the Rig tab and reported to FreeDV Reporter, mirroring
     * freedv-gui's frequency box which works without any CAT control.
     * A connected rig (rigctld / HL2) always takes priority over this.
     */
    private val _manualFreqHz = MutableStateFlow(0L)
    val manualFreqHz: StateFlow<Long> = _manualFreqHz.asStateFlow()

    /** Result of the last RX-output route test (1 kHz tone), shown in Settings. */
    private val _rxRouteTest = MutableStateFlow<String?>(null)
    val rxRouteTest: StateFlow<String?> = _rxRouteTest.asStateFlow()
    private val _rxRouteTesting = MutableStateFlow(false)
    val rxRouteTesting: StateFlow<Boolean> = _rxRouteTesting.asStateFlow()

    init {
        // On a rig "connection lost", let RigController report the local rigctld's
        // liveness/exit cause (crash vs. USB-bridge failure) in the on-screen error.
        rigController.diagnosticsProvider = { rigctldProcess.exitDiagnostics() }

        // Persist the Icom login token across app runs, so a token left on the
        // radio by an unclean exit (app killed while connected) is removed at
        // the start of the next connect instead of blocking the login.
        icomNetwork.saveStaleToken = { v ->
            prefs.edit().putString("icom_stale_token", v ?: "").apply()
        }
        icomNetwork.loadStaleToken = {
            prefs.getString("icom_stale_token", "")?.takeIf { it.isNotEmpty() }
        }

        // Restore persisted callsign. Sanitize on load too: a full-width or
        // space-padded callsign saved by an older build would silently keep
        // the station off qso.freedv.org (server-side regex refusal).
        val savedCallsign = sanitizeCallsign(prefs.getString("tx_callsign", "") ?: "")
        if (savedCallsign.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(txCallsign = savedCallsign)
        }

        // Start GPS grid updates when location permission is already granted
        // (no-op otherwise). Settings offers a button to request permission.
        locationTracker.startTracking()

        // Reporter toggle defaults to ON so the app auto-connects to qso.freedv.org
        // on launch; users can still opt out in Settings.
        _reporterEnabledPref.value = prefs.getBoolean("reporter_enabled", true)

        // Power-save mode (效能節約模式) — off by default; restored across launches.
        _uiState.value = _uiState.value.copy(
            powerSaveMode = prefs.getBoolean("power_save_mode", false),
            pttHoldMode = prefs.getBoolean("ptt_hold_mode", false),
            bluetoothMicTx = prefs.getBoolean("bluetooth_mic_tx", false),
            radeV2Mode = prefs.getBoolean("rade_v2_mode", false),
            txMicDeviceId = prefs.getInt("tx_mic_device", -1),
            selectedDeviceId = savedRxInputDeviceId,
            selectedRxOutputDeviceId = prefs.getInt(
                "rx_output_device",
                AudioService.RX_OUTPUT_AUTO
            )
        )
        // Restore the manual dial frequency (used when no rig is connected).
        _manualFreqHz.value = prefs.getLong("manual_freq_hz", 0L)

        // Load the persistent status message; reporter holds it and re-emits
        // on every (re)connect, so we just need to give it the saved value.
        reporter.updateMessage(prefs.getString("reporter_message", "") ?: "")
        reporter.modeString = if (_uiState.value.radeV2Mode) "RADEV2" else "RADEV1"
        syncReporterState()

        // Location → grid update (only triggers reconnect if grid actually changes)
        viewModelScope.launch {
            locationTracker.state.collect { loc ->
                if (loc.gridSquare.isNotEmpty()) {
                    syncReporterState()
                }
            }
        }

        // Rig state → forward frequency changes as live events (no reconnect)
        viewModelScope.launch {
            var lastConnected = false
            var lastFreq = 0L
            rigController.state.collect { rs ->
                _uiState.value = _uiState.value.copy(
                    pttControlError = rs.error.startsWith("PTT ") || (pttKeyedByApp && !rs.connected)
                )
                if (rs.connected != lastConnected) {
                    lastConnected = rs.connected
                    lastFreq = 0L  // re-push freq once connected again
                }
                val engineActive = _uiState.value.isRunning || _uiState.value.isTx
                if (rs.connected && engineActive && rs.freqHz > 0 && rs.freqHz != lastFreq) {
                    lastFreq = rs.freqHz
                    if (reporter.connected.value) {
                        reporter.reportFreqChange(rs.freqHz)
                    }
                }
            }
        }

        // Hermes-Lite 2 state → forward frequency changes like the rigctld path.
        viewModelScope.launch {
            var lastFreq = 0L
            hermesNetwork.state.collect { hs ->
                if (!hs.connected) { lastFreq = 0L; return@collect }
                val engineActive = _uiState.value.isRunning || _uiState.value.isTx
                if (engineActive && hs.freqHz > 0 && hs.freqHz != lastFreq &&
                    reporter.connected.value
                ) {
                    lastFreq = hs.freqHz
                    reporter.reportFreqChange(hs.freqHz)
                }
            }
        }

        // Icom network transport → detect an UNEXPECTED link loss. The bundled
        // rigctld keeps its local TCP link to us alive against a now-dead pty, so
        // RigController would otherwise stay "connected to 127.0.0.1:4532" forever
        // — the reported green "Connected" that lied, with a stuck TX indicator,
        // after a weak-LTE drop. When the Icom manager's watchdog reports the
        // transport dead (connected → false with an error, not an operator
        // disconnect), tear the local rig stack down so the status is honest and
        // the operator can reconnect (connect() now retries across the radio's
        // cleanup window).
        viewModelScope.launch {
            var wasConnected = false
            icomNetwork.state.collect { st ->
                if (st.connected) {
                    wasConnected = true
                    return@collect
                }
                val unexpectedLoss = wasConnected && !icomUserDisconnect &&
                    st.error.isNotEmpty() && !_rigConnecting.value
                wasConnected = false
                if (unexpectedLoss) handleIcomLinkLost()
            }
        }

        // Engine start → push current freq immediately so web UI pins our station.
        // Engine stop → just stop sending rx_report; we rely on each viewer's own
        // client-side timeout to clear our "receiving" indicator. Reconnecting to
        // force an immediate clear would drop the stations list, which hurts UX
        // more than the brief stale display.
        viewModelScope.launch {
            _uiState
                .map { it.isRunning || it.isTx }
                .distinctUntilChanged()
                .collect { active ->
                    if (active && reporter.connected.value) {
                        val freq = currentReportFreqHz()
                        if (freq > 0) reporter.reportFreqChange(freq)
                    }
                }
        }

        // When the reporter transitions to connected, push current freq + TX state.
        // The frequency is dial state, not activity — push it whenever we know
        // it (rig or manual entry) so the Frequency column on qso.freedv.org
        // isn't blank until the first RX/TX session.
        viewModelScope.launch {
            reporter.connected.collect { connected ->
                if (connected) {
                    val freq = currentReportFreqHz()
                    if (freq > 0) {
                        reporter.reportFreqChange(freq)
                    }
                    if (_uiState.value.isTx) reporter.reportTx(true)
                }
            }
        }

        // Forward TX on/off transitions to the reporter
        viewModelScope.launch {
            _uiState
                .map { it.isTx }
                .distinctUntilChanged()
                .collect { isTx ->
                    if (reporter.connected.value) {
                        reporter.reportTx(isTx)
                    }
                }
        }

        bindToService()
        refreshDevices()
        usbSerialManager.register()
    }

    /** GPS grid takes priority, fall back to manual pref. */
    private fun currentGrid(): String {
        val locGrid = locationTracker.state.value.gridSquare
        if (locGrid.isNotEmpty()) return sanitizeGridSquare(locGrid)
        return sanitizeGridSquare(prefs.getString("reporter_grid", "") ?: "")
    }

    /** Dial frequency to report: a connected rig wins, else the manual entry. */
    private fun currentReportFreqHz(): Long = when {
        rigController.isConnected -> rigController.state.value.freqHz
        hermesNetwork.isConnected -> hermesNetwork.state.value.freqHz
        else -> _manualFreqHz.value
    }

    /**
     * Drive reporter connection from toggle + callsign. The connection is stable once
     * established — rig/engine state changes are communicated via live events
     * (freq_change, tx_report) rather than by tearing down the websocket.
     */
    private fun syncReporterState() {
        val callsign = _uiState.value.txCallsign
        val shouldConnect = _reporterEnabledPref.value && callsign.isNotEmpty()
        reporter.configure(callsign, currentGrid(), shouldConnect)
    }

    /* ── RX ─────────────────────────────────────────────────── */

    /** True when rig control is running over the IC-705's Wi-Fi (control stream up).
     *  In that mode there is no USB sound card, so RX/TX audio must use the network
     *  path regardless of whether the audio sub-stream has finished its handshake —
     *  routing on the deterministic control-connected flag (not the racy
     *  audioConnected flag) is what makes Start actually pick the wireless path.
     *  The audioConnected flag is surfaced separately in the UI for status. */
    private fun useNetworkAudio(): Boolean =
        icomNetwork.isConnected || hermesNetwork.isConnected || vbanNetwork.isConnected

    fun startReceiving() {
        val app = getApplication<Application>()

        val intent = Intent(app, AudioService::class.java)
        app.startForegroundService(intent)
        bindToService()

        viewModelScope.launch {
            var attempts = 0
            while (audioService == null && attempts < 20) {
                delay(100)
                attempts++
            }
            if (useNetworkAudio()) {
                audioService?.startNetworkDecoding(
                    outputDeviceId = _uiState.value.selectedRxOutputDeviceId
                )
            } else {
                audioService?.startDecoding(
                    inputDeviceId = _uiState.value.selectedDeviceId,
                    outputDeviceId = _uiState.value.selectedRxOutputDeviceId,
                    recordWav = false,
                    useLeAudioCommunication = shouldUseLeAudioCommunicationSession()
                )
            }
        }
    }

    private fun stopReceiving() {
        audioService?.stopDecoding()
    }

    /* ── TX ─────────────────────────────────────────────────── */

    fun setTxCallsign(callsign: String) {
        val sanitized = sanitizeCallsign(callsign)
        _uiState.value = _uiState.value.copy(txCallsign = sanitized)
        prefs.edit().putString("tx_callsign", sanitized).apply()
        syncReporterState()
    }

    /* ── Reporter ──────────────────────────────────────────── */

    fun setReporterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("reporter_enabled", enabled).apply()
        _reporterEnabledPref.value = enabled
        syncReporterState()
    }

    /* ── Performance (效能節約模式) ─────────────────────────── */

    /** Toggle power-save mode: hides the spectrum/waterfall in the UI and stops
     *  the native FFT in the service so low-end devices can keep decoding. */
    fun setPowerSaveMode(enabled: Boolean) {
        prefs.edit().putBoolean("power_save_mode", enabled).apply()
        _uiState.value = _uiState.value.copy(powerSaveMode = enabled)
        audioService?.powerSaveMode = enabled
    }

    /**
     * Experimental: switch the modem to the RADE V2 waveform (recently merged
     * upstream, still under test). V1 and V2 are NOT interoperable on the air
     * — both stations must select the same version. On V2 the end-of-over
     * frame carries no callsign payload, so EOO callsign RX/TX is V1-only.
     * Applies immediately: a running RX restarts on the new waveform.
     */
    fun setRadeV2Mode(enabled: Boolean) {
        prefs.edit().putBoolean("rade_v2_mode", enabled).apply()
        _uiState.value = _uiState.value.copy(radeV2Mode = enabled)
        audioService?.radeV2Mode = enabled
        // qso.freedv.org's Mode column should reflect the selected waveform.
        reporter.modeString = if (enabled) "RADEV2" else "RADEV1"
        if (_uiState.value.isRunning && !_uiState.value.isTx) {
            stopReceiving()
            startReceiving()
        }
    }

    /** Hold-to-talk: when enabled the TX button keys TX only while held. */
    fun setPttHoldMode(enabled: Boolean) {
        prefs.edit().putBoolean("ptt_hold_mode", enabled).apply()
        _uiState.value = _uiState.value.copy(pttHoldMode = enabled)
    }

    /**
     * Experimental: capture TX voice from the Bluetooth headset mic (HFP/SCO) instead
     * of the phone mic. Off by default. Only affects the TX path — RX and Bluetooth
     * A2DP playback are untouched.
     */
    fun setBluetoothMicTx(enabled: Boolean) {
        prefs.edit().putBoolean("bluetooth_mic_tx", enabled).apply()
        _uiState.value = _uiState.value.copy(bluetoothMicTx = enabled)
    }

    /**
     * Choose the TX microphone input device (e.g. a USB-hub external mic) instead of
     * the phone's built-in mic. -1 = built-in. Avoids the Bluetooth SCO mic's delay
     * and quality loss. The Bluetooth-mic toggle, when on, still takes priority.
     */
    fun selectTxMicDevice(deviceId: Int) {
        prefs.edit().putInt("tx_mic_device", deviceId).apply()
        _uiState.value = _uiState.value.copy(txMicDeviceId = deviceId)
    }

    /** Resolve the TX mic device id, falling back to the built-in mic when the
     *  selected device is gone or none was chosen. */
    private fun resolveTxMicId(): Int {
        val sel = _uiState.value.txMicDeviceId
        return if (sel > 0 && _uiState.value.devices.any { it.id == sel }) sel
               else _uiState.value.builtInMicId
    }

    /** Release also cancels a re-key queued behind the previous EOO drain. */
    fun pttHoldRelease() {
        switchToRx()
    }

    fun setReporterGrid(grid: String) {
        prefs.edit().putString("reporter_grid", sanitizeGridSquare(grid)).apply()
        syncReporterState()
    }

    /** Save the status-message draft locally; does not push to the server. */
    fun setReporterMessageDraft(message: String) {
        prefs.edit().putString("reporter_message", message).apply()
    }

    fun getSavedReporterMessage(): String = prefs.getString("reporter_message", "") ?: ""

    /**
     * Push the given status message to the FreeDV Reporter server now.
     * The reporter also remembers it and re-emits on every reconnect.
     */
    fun updateReporterMessage(message: String) {
        prefs.edit().putString("reporter_message", message).apply()
        reporter.updateMessage(message)
    }

    private fun startTransmitting() {
        viewModelScope.launch {
            var attempts = 0
            while (audioService == null && attempts < 20) {
                delay(100)
                attempts++
            }
            audioService?.startTransmitting(
                inputDeviceId = resolveTxMicId(),  // built-in mic or a chosen USB mic
                outputDeviceId = _uiState.value.selectedOutputDeviceId,
                callsign = _uiState.value.txCallsign,
                useBluetoothMic = _uiState.value.bluetoothMicTx,
                keepRxAlive = shouldKeepRxAliveAcrossTx(),
                preferLeAudioCommunication = shouldUseLeAudioCommunicationSession()
            )
        }
    }

    /* ── Mode switching (while engine is active) ────────────── */

    /** Switch from RX → TX (stops RX, starts TX, keys PTT) */
    fun switchToTx() {
        val requestId = ++txRequestId
        if (txStopJob?.isActive == true) {
            // The previous over is still draining its EOO — queue this key-press
            // behind it instead of dropping it (quick re-key in hold-to-talk).
            viewModelScope.launch {
                txStopJob?.join()
                var waitedMs = 0
                while (!_uiState.value.isRunning && waitedMs < 1000) {
                    delay(50)
                    waitedMs += 50
                }
                if (requestId == txRequestId) doSwitchToTx()
            }
            return
        }
        doSwitchToTx()
    }

    /** RX analog SSB monitor (freedv-gui's "Analog"): hear the raw channel
     *  audio instead of decoded speech, to check whether the frequency is
     *  occupied by an analog QSO before keying up — essential when operating
     *  the rig remotely. RADE decode keeps running (sync indicator stays
     *  live); switching to TX or pressing Stop turns the monitor off. */
    fun toggleAnalogMonitor() {
        val next = !_uiState.value.analogMonitor
        _uiState.value = _uiState.value.copy(analogMonitor = next)
        audioService?.setAnalogMonitor(next)
    }

    private fun clearAnalogMonitor() {
        if (_uiState.value.analogMonitor) {
            _uiState.value = _uiState.value.copy(analogMonitor = false)
        }
        audioService?.setAnalogMonitor(false)
    }

    private fun doSwitchToTx() {
        if (pttKeyedByApp && !_uiState.value.isTx) {
            Log.w("TransceiverVM", "TX refused: the previous PTT release is still unconfirmed")
        }
        if (!_uiState.value.isRunning || _uiState.value.isTx || pttKeyedByApp) return
        val requestedAt = System.nanoTime()
        val requestId = txRequestId
        clearAnalogMonitor()
        // Auto-PTT via rigctld (both paths)
        if (rigController.isConnected) {
            pttKeyedByApp = true
            // Preserve the LTE/VPN key-on retries added in v1.6.12. A release
            // cancels this request, and OFF joins the job before unkeying.
            pttKeyJob?.cancel()
            pttKeyJob = viewModelScope.async(Dispatchers.IO) {
                var lastReadback: Boolean? = null
                for (attempt in 1..3) {
                    ensureActive()
                    if (!pttKeyedByApp || requestId != txRequestId) return@async PttKeyOutcome.REFUSED
                    try {
                        if (rigController.setPtt(true)) return@async PttKeyOutcome.ACKED
                        // No usable acknowledgement (timeout, "RPRT -5" from a
                        // slow CAT link, a lost reply). Ask the rig what it did
                        // before repeating the command: a slow rig has usually
                        // keyed by now.
                        lastReadback = rigController.readPtt(expected = true)
                        if (lastReadback == true) {
                            Log.i("TransceiverVM", "PTT ON confirmed by readback after attempt $attempt")
                            return@async PttKeyOutcome.CONFIRMED
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("TransceiverVM", "PTT key attempt $attempt failed", e)
                    }
                    if (attempt < 3) delay(250L * attempt)
                }
                if (lastReadback == false) {
                    Log.e("TransceiverVM", "PTT key REFUSED: rig reports PTT off after retries")
                    PttKeyOutcome.REFUSED
                } else {
                    Log.e("TransceiverVM", "PTT key unconfirmed after retries (no CAT reply) — keeping TX; operator can see the rig")
                    PttKeyOutcome.UNKNOWN
                }
            }
        } else if (hermesNetwork.isConnected) {
            // HL2 direct: the MOX bit rides the protocol-1 C&C stream.
            pttKeyedByApp = true
            hermesNetwork.setPtt(true)
        }
        // RTS-as-PTT (Rig tab option): CAT-less interfaces key the rig with the
        // serial RTS line (classic 4-jack digimode interfaces, Digirig wiring).
        // Assert RTS for the over; released in stopTxAndUnkeyPtt after the
        // audio has drained.
        if (rigPrefs.getBoolean("rts_ptt_enabled", false) &&
            usbSerialManager.state.value.connectedDevice != null
        ) {
            rtsPttKeyed = true
            usbSerialManager.setModemLines(
                dtr = rigPrefs.getBoolean("dtr_enabled", true),
                rts = true
            )
            Log.i("TransceiverVM", "RTS PTT: asserted RTS for TX")
        }
        try {
            if (useNetworkAudio()) {
                // Full wireless: mic → encoder → network (Icom UDP / HL2 I/Q).
                // The TX MIC picker applies here too — "no USB devices" only ever
                // held for the Icom full-wireless case, and a USB headset mic
                // alongside an HL2 (LAN) session is a normal setup (reported:
                // picker showed the USB headset but the built-in mic captured).
                refreshDevices()
                val micId = resolveTxMicId()
                Log.i("TransceiverVM", "switchToTx (network): micId=$micId btMic=${_uiState.value.bluetoothMicTx}")
                audioService?.startNetworkTransmitting(
                    inputDeviceId = micId,
                    callsign = _uiState.value.txCallsign,
                    useBluetoothMic = _uiState.value.bluetoothMicTx
                )
                return
            }
            // Refresh devices to pick up USB audio output if available
            refreshDevices()
            val outId = _uiState.value.selectedOutputDeviceId
            val micId = _uiState.value.builtInMicId
            Log.i("TransceiverVM", "switchToTx: micId=$micId outDevId=$outId rigConnected=${rigController.isConnected}")
            // startTransmitting: use built-in mic for TX input (not USB audio input
            // which is the radio's audio output used for RX decoding)
            audioService?.startTransmitting(
                inputDeviceId = resolveTxMicId(),
                outputDeviceId = outId,
                callsign = _uiState.value.txCallsign,
                useBluetoothMic = _uiState.value.bluetoothMicTx,
                keepRxAlive = shouldKeepRxAliveAcrossTx(),
                preferLeAudioCommunication = shouldUseLeAudioCommunicationSession()
            )
        } finally {
            Log.i("TransceiverVM", "TX audio setup elapsedMs=${(System.nanoTime() - requestedAt) / 1_000_000}")
            val keyJob = pttKeyJob
            // A failed mic/output open or rejected CAT command must not leave
            // the radio keyed. This runs after synchronous audio setup returns.
            viewModelScope.launch {
                val outcome = keyJob?.await() ?: PttKeyOutcome.ACKED
                val audioUp = audioService?.state?.value?.isTx == true
                if (pttKeyJob === keyJob && txStopJob?.isActive != true &&
                    (outcome == PttKeyOutcome.REFUSED || !audioUp)) {
                    Log.e("TransceiverVM", "TX start failed: ptt=$outcome audioTx=$audioUp; stopping and unkeying")
                    txStopJob = viewModelScope.launch { stopTxAndUnkeyPtt(fullTeardown = true) }
                } else if (outcome == PttKeyOutcome.UNKNOWN) {
                    Log.w("TransceiverVM", "TX continues without a CAT PTT confirmation (banner shown)")
                }
            }
        }
    }

    private fun shouldUseLeAudioCommunicationSession(): Boolean {
        val s = _uiState.value
        if (!s.bluetoothMicTx || useNetworkAudio()) return false
        val hasDeviceSnapshot = s.devices.isNotEmpty() || s.outputDevices.isNotEmpty()
        return !hasDeviceSnapshot ||
            s.devices.any { it.isBleAudio } ||
            s.outputDevices.any { it.isBleAudio }
    }

    /**
     * Whether to keep the RX output stream (and its Bluetooth LE Audio / LC3 media
     * route) open across TX instead of tearing the engine down. Only for local
     * monitoring on a Bluetooth output with no separate transmit path — opening/
     * closing the media output on TX is what makes a dual-mode LC3 headset fall
     * back to A2DP/silence, and no app API can switch it back. The USB/rig and
     * network paths keep the original full teardown (unchanged).
     */
    private fun shouldKeepRxAliveAcrossTx(): Boolean {
        val s = _uiState.value
        val rxOut = s.outputDevices.firstOrNull { it.id == s.selectedRxOutputDeviceId }
        val rxIsBluetooth = rxOut?.isBluetooth == true || rxOut?.isBleAudio == true
        return rxIsBluetooth &&
            !useNetworkAudio() &&
            s.selectedOutputDeviceId <= 0 &&
            !s.bluetoothMicTx
    }

    /** Switch from TX → RX: stop TX (drains the EOO callsign frame), hold the
     *  rig keyed for a short RF tail, then unkey PTT and resume RX. */
    fun switchToRx() {
        ++txRequestId
        Log.i("TransceiverVM", "switchToRx: isTx=${_uiState.value.isTx}, rigConnected=${rigController.isConnected}")
        // Service state is authoritative before the UI flow collector catches
        // up, particularly on a very short press during synchronous TX setup.
        if (audioService?.state?.value?.isTx != true && !pttKeyedByApp && !rtsPttKeyed) return
        if (txStopJob?.isActive == true) return
        txStopJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(txSwitching = true)
            try {
                stopTxAndUnkeyPtt()
                // Resume RX — brief settle for the audio route to quiesce.
                delay(20)
                if (useNetworkAudio()) {
                    audioService?.startNetworkDecoding(
                        outputDeviceId = _uiState.value.selectedRxOutputDeviceId
                    )
                } else {
                    audioService?.startDecoding(
                        inputDeviceId = _uiState.value.selectedDeviceId,
                        outputDeviceId = _uiState.value.selectedRxOutputDeviceId,
                        recordWav = false,
                        useLeAudioCommunication = shouldUseLeAudioCommunicationSession()
                    )
                }
            } finally {
                _uiState.value = _uiState.value.copy(txSwitching = false)
            }
        }
    }

    /**
     * Stop TX and unkey the rig in the order the air interface needs:
     * stopTransmitting() blocks until the EOO (callsign) frame has fully
     * played out of the audio path, a short tail lets the rig flush its own
     * TX audio chain, and only then is PTT dropped. Unkeying first (the old
     * behaviour) cut the callsign off mid-air.
     */
    private suspend fun stopTxAndUnkeyPtt(fullTeardown: Boolean = false) {
        // Only drain the EOO callsign and hold the RF tail when there is a real
        // transmit path: a CAT-keyed rig, network audio, or a USB audio output.
        // With none of those (pure local LC3 monitoring) tear down immediately —
        // this removes several seconds of dead time from the TX->RX switch.
        val onAir = rigController.isConnected ||
            useNetworkAudio() ||
            _uiState.value.selectedOutputDeviceId > 0 ||
            rtsPttKeyed
        // fullTeardown (Stop pressed): release the engine instead of resuming RX,
        // so a keep-alive (local LC3) TX doesn't leave RX running after Stop.
        withContext(Dispatchers.IO) {
            audioService?.stopTransmitting(drainEoo = onAir, forceFullTeardown = fullTeardown)
        }
        // RTS-as-PTT: drop RTS back to its static Rig-tab setting only after the
        // audio has drained (mirrors the CAT PTT tail below).
        if (rtsPttKeyed) {
            delay(TX_PTT_TAIL_MS)
            usbSerialManager.setModemLines(
                dtr = rigPrefs.getBoolean("dtr_enabled", true),
                rts = rigPrefs.getBoolean("rts_enabled", false)
            )
            rtsPttKeyed = false
            Log.i("TransceiverVM", "RTS PTT: released RTS after TX")
        }
        // Unkey whenever WE keyed the rig — not gated on isConnected: over Wi-Fi
        // the rigctld link can be transiently down right here (auto-reconnect in
        // flight), and skipping the unkey leaves the radio stuck in TX (reported
        // on an IC-7300mk2 over WLAN). A single "T 0" can also be lost to a
        // socket timeout, so retry with backoff until rigctld acknowledges.
        if (pttKeyedByApp) {
            // Stop any in-flight key-ON retry FIRST (cancel + join): a retry
            // that fired after the unkey would re-key the rig and leave it
            // stuck in TX.
            pttKeyJob?.let { it.cancel(); it.join() }
            pttKeyJob = null
            // An unkey retry still running from an earlier over yields to this one.
            pttUnkeyJob?.cancel()
            pttUnkeyJob = null
            delay(pttTailMs())
            if (hermesNetwork.isConnected && !rigController.isConnected) {
                // HL2 direct: MOX=0 rides every subsequent C&C frame — no ack
                // round-trip to lose, so no retry loop is needed.
                hermesNetwork.setPtt(false)
                pttKeyedByApp = false
                return
            }
            var unkeyed = false
            for (attempt in 1..3) {
                try {
                    if (rigController.setPtt(false)) { unkeyed = true; break }
                    // No usable acknowledgement — the rig may well have unkeyed
                    // (slow CAT, lost reply, "RPRT -5"): read its PTT state back
                    // before assuming it is stuck in TX.
                    if (rigController.readPtt(expected = false) == false) {
                        Log.i("TransceiverVM", "PTT OFF confirmed by readback after attempt $attempt")
                        unkeyed = true; break
                    }
                } catch (e: Exception) {
                    Log.w("TransceiverVM", "PTT unkey attempt $attempt failed", e)
                }
                Log.w("TransceiverVM", "PTT unkey not acknowledged (attempt $attempt); retrying")
                if (attempt < 3) delay(300L * attempt)
            }
            pttKeyedByApp = !unkeyed
            if (!unkeyed) {
                Log.e("TransceiverVM", "PTT unkey not acknowledged after quick retries — keep trying in the background")
                startPersistentUnkey()
            }
        }
    }

    /** RF tail between the last TX audio leaving the phone and the PTT release.
     *  On the Icom network path the radio still holds its whole TX buffer of our
     *  audio when the pump has drained, so the tail follows that buffer depth
     *  (150 ms default → 300 ms; 800 ms for LTE → 950 ms). */
    private fun pttTailMs(): Long =
        if (icomNetwork.isConnected) icomNetwork.txTailMs.toLong().coerceAtLeast(TX_PTT_TAIL_MS)
        else TX_PTT_TAIL_MS

    /**
     * Keep releasing PTT until rigctld acknowledges it. The quick retry loop in
     * stopTxAndUnkeyPtt spans only ~8 s; an LTE stall or a VPN re-key easily
     * outlasts that, and giving up then left the IC-7300MK2 transmitting until
     * its own TX time-out (reported). While the Icom transport is down this waits
     * for the automatic reconnect (handleIcomLinkLost) to bring rigctld back, then
     * unkeys. Ends when acknowledged or when the operator disconnects the rig.
     */
    private fun startPersistentUnkey() {
        if (pttUnkeyJob?.isActive == true) return
        pttUnkeyJob = viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && pttKeyedByApp) {
                attempt++
                if (rigController.isConnected) {
                    val ok = try {
                        rigController.setPtt(false) ||
                            rigController.readPtt(expected = false) == false   // unkeyed after all
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        pttKeyedByApp = false
                        Log.i("TransceiverVM", "PTT unkey acknowledged on background attempt $attempt")
                        break
                    }
                    Log.w("TransceiverVM", "PTT unkey background attempt $attempt not acknowledged")
                } else if (attempt % 5 == 1) {
                    Log.w("TransceiverVM", "PTT unkey pending: rig link down (attempt $attempt) — waiting for reconnect")
                }
                delay(1000)
            }
        }
    }

    /** Stop everything and tear down the service */
    fun stopAll() {
        ++txRequestId
        val app = getApplication<Application>()
        clearAnalogMonitor()
        if (_uiState.value.isTx || txStopJob?.isActive == true || pttKeyedByApp || rtsPttKeyed) {
            // Let the EOO finish over the air and unkey before the service dies.
            viewModelScope.launch {
                txStopJob?.join()
                if (audioService?.state?.value?.isTx == true || pttKeyedByApp || rtsPttKeyed) {
                    if (audioService?.state?.value?.isRunning == true) stopReceiving()
                    stopTxAndUnkeyPtt(fullTeardown = true)
                }
                app.stopService(Intent(app, AudioService::class.java))
            }
        } else {
            stopReceiving()
            app.stopService(Intent(app, AudioService::class.java))
        }
        _uiState.value = _uiState.value.copy(
            isRunning = false,
            isTx = false,
            syncState = 0,
            snrDb = 0,
            freqOffsetHz = 0f,
            inputLevelDb = -100f,
            outputLevelDb = -100f,
            txLevelDb = -100f,
            analogMonitor = false
        )
    }

    /* ── Device / settings ─────────────────────────────────── */

    fun selectDevice(deviceId: Int) {
        prefs.edit().putInt("rx_input_device", deviceId).apply()
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId)
    }

    fun selectRxOutputDevice(deviceId: Int) {
        val current = _uiState.value
        val pairedInput = findInputForRxOutput(deviceId, current.devices, current.outputDevices)
        val stableAutoInput = findStableInputForAuto(deviceId, current.selectedDeviceId, current.devices)
        val nextInput = pairedInput ?: stableAutoInput
        nextInput?.let {
            prefs.edit().putInt("rx_input_device", it.id).apply()
        }
        prefs.edit().putInt("rx_output_device", deviceId).apply()
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = nextInput?.id ?: current.selectedDeviceId,
            selectedRxOutputDeviceId = deviceId
        )
        audioService?.setRxAudioDevices(nextInput?.id, deviceId)
    }

    fun selectOutputDevice(deviceId: Int) {
        _uiState.value = _uiState.value.copy(selectedOutputDeviceId = deviceId)
    }

    /**
     * Play a 1 kHz tone to the currently selected RX output and report where
     * Android actually routed it — a route check independent of RADE decode.
     * For Auto, tests the device RX would actually pick; for System default,
     * leaves routing to Android.
     */
    fun testRxOutput() {
        if (_rxRouteTesting.value) return
        val selection = _uiState.value.selectedRxOutputDeviceId
        viewModelScope.launch {
            _rxRouteTesting.value = true
            _rxRouteTest.value = "Testing… (1 kHz tone, ~2s)"
            val result = withContext(Dispatchers.IO) {
                val deviceId = resolveTestDeviceId(selection)
                RxOutputTester.run(getApplication(), deviceId)
            }
            _rxRouteTest.value = result.summary
            _rxRouteTesting.value = false
        }
    }

    /** Resolve an RX-output selection (Auto / System default / explicit) to a
     *  concrete AudioDeviceInfo id for the route test, or -1 for system default. */
    private fun resolveTestDeviceId(selection: Int): Int = when {
        selection > 0 -> selection
        selection == AudioService.RX_OUTPUT_SYSTEM_DEFAULT -> -1
        else -> {
            val bridge = AudioBridge.forDeviceDiscovery(getApplication())
            val id = bridge.findPreferredRxOutputDevice()?.id ?: -1
            bridge.release()
            id
        }
    }

    fun setInputGain(gain: Float) {
        prefs.edit().putFloat("input_gain", gain).apply()
        audioService?.setInputGain(gain)
    }

    fun getSavedInputGain(): Float = prefs.getFloat("input_gain", 4.0f)

    /** TX mic gain: boosts the mic into the RADE encoder so the far-end decoded
     *  speech isn't under-modulated (the RX side compensates the same quiet
     *  Android mic with input_gain; TX had no equivalent until v1.5.53). */
    fun setTxMicGain(gain: Float) {
        prefs.edit().putFloat("tx_mic_gain", gain).apply()
        audioService?.setTxMicGain(gain)
    }

    fun getSavedTxMicGain(): Float = prefs.getFloat("tx_mic_gain", 4.0f)

    fun setVolume(volume: Float) {
        prefs.edit().putFloat("output_volume", volume).apply()
        audioService?.setOutputVolume(volume)
    }

    fun getSavedVolume(): Float = prefs.getFloat("output_volume", 1.0f)

    fun setTxVolume(volume: Float) {
        prefs.edit().putFloat("tx_volume", volume).apply()
        audioService?.setTxVolume(volume)
    }

    fun getSavedTxVolume(): Float = prefs.getFloat("tx_volume", 0.2f)

    /* ── Diagnostics: in-app CAT/rig log capture (no adb needed) ─── */

    /**
     * Dump the app's own recent rig/CAT-related logcat: [RigController] (CAT
     * commands, connection-lost detail), [RigctldProcess], the native USB-serial
     * bridge + piped rigctld stderr ("UsbPtyBridge"), and "UsbSerialManager".
     * An app may read its own log buffer without permissions. Blocking — call off
     * the main thread.
     */
    fun captureCatLog(): String = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf(
                "logcat", "-d", "-v", "time",
                "RigController:V", "RigctldProcess:V",
                "UsbPtyBridge:V", "UsbSerialManager:V", "HermesNet:V",
                "IcomNetwork:V", "NetTxPump:V", "TransceiverVM:V", "*:S"
            )
        )
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        out.ifBlank { "(no CAT log captured — connect the rig and reproduce, then capture again)" }
    } catch (e: Exception) {
        "Failed to read logcat: ${e.message}"
    }

    /** Clear the log buffer so a fresh reproduction can be captured cleanly. */
    fun clearCatLog() {
        try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() } catch (_: Exception) {}
    }

    /**
     * Dump the app's own recent audio/routing logcat: [AudioService] (RX/TX
     * lifecycle, Bluetooth/LE Audio routing, keep-alive), [AudioBridge], and the
     * native "AudioEngine" / "RADE_JNI" / "PureSignalPS" tags. Fatal Java
     * and native crash tags plus the dedicated crash buffer are included on a
     * best-effort basis; logcat permission/errors are reported in the capture
     * instead of silently becoming empty output. Blocking — call off the main
     * thread.
     */
    fun captureAudioLog(): String = try {
        fun runLogcat(label: String, command: Array<String>): String {
            return try {
                val proc = ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                if (exit == 0) text else {
                    "($label unavailable: logcat exit=$exit" +
                        if (text.isBlank()) ")" else ": ${text.trim()})"
                }
            } catch (e: Exception) {
                "($label unavailable: ${e.message ?: e.javaClass.simpleName})"
            }
        }

        val mainLog = runLogcat(
            "audio log",
            arrayOf(
                "logcat", "-d", "-v", "time",
                "AudioService:V", "AudioBridge:V", "AudioEngine:V", "RADE_JNI:V",
                "HermesNet:V", "IcomNetwork:V", "NetTxPump:V", "PureSignalPS:V", "TransceiverVM:V", "RigController:V",
                "AndroidRuntime:V", "libc:V", "DEBUG:V", "crash_dump64:V",
                "tombstoned:V", "*:S"
            )
        )
        val crashLog = runLogcat(
            "crash buffer",
            arrayOf("logcat", "-b", "crash", "-d", "-v", "time")
        )

        buildString {
            if (mainLog.isNotBlank()) append(mainLog.trimEnd())
            if (crashLog.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("--------- crash buffer\n")
                append(crashLog.trimEnd())
            }
        }.ifBlank {
            "(no audio/crash log — start RX, reproduce once, then capture)"
        }
    } catch (e: Exception) {
        "Failed to read logcat: ${e.message}"
    }

    fun refreshDevices() {
        val bridge = AudioBridge.forDeviceDiscovery(getApplication())
        val devices = bridge.getInputDevices()
        val outputDevices = bridge.getOutputDevices()
        val usbInput = bridge.findUsbInputDevice()
        val usbOutput = outputDevices.firstOrNull { it.isUsb }
        val builtInMic = bridge.findBuiltInMic()
        bridge.release()
        val current = _uiState.value
        val outputIds = outputDevices.map { it.id }.toSet()
        val selectedTxOutputId = when {
            current.selectedOutputDeviceId > 0 && current.selectedOutputDeviceId in outputIds -> current.selectedOutputDeviceId
            else -> usbOutput?.id ?: current.selectedOutputDeviceId
        }
        // Bluetooth SCO is no longer offered as an RX output (it is the call-audio
        // profile and plays media silently). Migrate any persisted SCO selection —
        // e.g. left over from v1.5.26 — back to Auto so it can't sit there unheard.
        val scoOutputIds = outputDevices.filter { it.isBluetoothSco }.map { it.id }.toSet()
        val selectedRxOutputId = when {
            current.selectedRxOutputDeviceId > 0 &&
                (current.selectedRxOutputDeviceId !in outputIds ||
                    current.selectedRxOutputDeviceId in scoOutputIds) ->
                AudioService.RX_OUTPUT_AUTO
            else -> current.selectedRxOutputDeviceId
        }
        val pairedInput = findInputForRxOutput(selectedRxOutputId, devices, outputDevices)
        val currentInput = devices.firstOrNull { it.id == current.selectedDeviceId }
        val savedInput = devices.firstOrNull { it.id == savedRxInputDeviceId }
        val stableAutoInput = when {
            currentInput?.isBluetooth == true -> findStableInputForAuto(selectedRxOutputId, currentInput.id, devices)
            savedInput?.isBluetooth == true -> findStableInputForAuto(selectedRxOutputId, savedInput.id, devices)
            else -> null
        }
        val selectedInputId = when {
            stableAutoInput != null -> stableAutoInput.id
            currentInput != null -> currentInput.id
            savedInput != null -> savedInput.id
            pairedInput != null -> pairedInput.id
            else -> usbInput?.id ?: builtInMic?.id ?: current.selectedDeviceId
        }

        _uiState.value = _uiState.value.copy(
            devices = devices,
            outputDevices = outputDevices,
            builtInMicId = builtInMic?.id ?: current.builtInMicId,
            selectedDeviceId = selectedInputId,
            selectedRxOutputDeviceId = selectedRxOutputId,
            selectedOutputDeviceId = selectedTxOutputId
        )
    }

    private fun findInputForRxOutput(
        outputSelection: Int,
        inputDevices: List<AudioBridge.AudioDevice>,
        outputDevices: List<AudioBridge.AudioDevice>
    ): AudioBridge.AudioDevice? {
        if (outputSelection <= 0) return null
        val output = resolveRxOutputForPairing(outputSelection, outputDevices) ?: return null
        if (!output.isUsb && !output.isWired) return null

        fun sameFamily(input: AudioBridge.AudioDevice): Boolean = when {
            output.isUsb -> input.isUsb
            output.isWired -> input.isWired
            else -> false
        }

        return inputDevices.firstOrNull { sameFamily(it) && it.name == output.name }
            ?: inputDevices.firstOrNull { sameFamily(it) }
    }

    private fun resolveRxOutputForPairing(
        outputSelection: Int,
        outputDevices: List<AudioBridge.AudioDevice>
    ): AudioBridge.AudioDevice? {
        if (outputSelection > 0) return outputDevices.firstOrNull { it.id == outputSelection }
        return null
    }

    private fun findStableInputForAuto(
        outputSelection: Int,
        currentInputId: Int,
        inputDevices: List<AudioBridge.AudioDevice>
    ): AudioBridge.AudioDevice? {
        if (outputSelection != AudioService.RX_OUTPUT_AUTO) return null
        val currentInput = inputDevices.firstOrNull { it.id == currentInputId } ?: return null
        if (!currentInput.isBluetooth) return null
        return inputDevices.firstOrNull { it.isUsb }
            ?: inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /* ── Rig control (rigctld) ─────────────────────────────── */

    /** Rig manufacturer of the currently selected model, used to pick USB/PKTUSB */
    var rigMfg: String = ""

    fun rigConnect(host: String, port: Int) {
        viewModelScope.launch { rigController.connect(host, port) }
    }

    fun rigDisconnect() {
        icomUserDisconnect = true   // this teardown is intentional — not a link loss
        icomAutoReconnectJob?.cancel()
        icomAutoReconnectJob = null
        icomResumeRxAfterReconnect = false
        // A pending unkey can't be delivered once the operator tears the link
        // down; the radio's own TX time-out is the fallback. Don't leave TX blocked.
        pttUnkeyJob?.cancel()
        pttUnkeyJob = null
        pttKeyedByApp = false
        icomNetwork.abortConnect()   // also stops a connect() retry loop in flight
        rigController.disconnect()
        rigctldProcess.stop()
        usbSerialManager.close()
        icomNetwork.disconnect()
        hermesNetwork.disconnect()
        vbanNetwork.disconnect()
    }

    /**
     * The Icom Wi-Fi transport died unexpectedly (weak LTE/Wi-Fi drop, radio
     * powered off) and the manager's watchdog tore its own session down. Stop the
     * local rig stack that was riding on it — the bundled rigctld and its TCP
     * client stay "connected" to 127.0.0.1 against a dead pty otherwise — so the
     * Rig screen stops showing a false "Connected" with a stuck TX, and the
     * operator gets an accurate state to reconnect from. The Icom manager's error
     * ("Radio stopped responding…") stays on screen for them to see.
     */
    private fun handleIcomLinkLost() {
        if (icomLinkLostHandling) return
        icomLinkLostHandling = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.w("TransceiverVM", "Icom transport lost — tearing down local rig stack")
                val wasTx = audioService?.state?.value?.isTx == true
                val wasRx = audioService?.state?.value?.isRunning == true
                // Nothing reaches the radio right now: drop the engine's TX/RX state
                // so it isn't left pretending to transmit into the void.
                if (wasTx) {
                    audioService?.stopTransmitting(drainEoo = false, forceFullTeardown = true)
                } else if (wasRx) {
                    audioService?.stopDecoding()
                }
                // Cancel any in-flight key-on retry so it can't keep trying to
                // key a radio that is gone.
                pttKeyJob?.cancel()
                pttKeyJob = null
                rtsPttKeyed = false
                rigController.disconnect()
                rigctldProcess.stop()
                // The rig may well still be keyed (we were in TX, or an unkey was
                // pending). Keep pttKeyedByApp so the background unkey fires the
                // moment the link is back, and so the operator sees an honest
                // "PTT not confirmed" instead of a clean RX display meanwhile.
                if (wasTx) pttKeyedByApp = true
                if (pttKeyedByApp) startPersistentUnkey()
                icomResumeRxAfterReconnect = wasRx || wasTx
                // Weak-LTE drops are transient: reconnect by ourselves (connect()
                // already retries across the radio's session-cleanup window).
                if (!icomUserDisconnect && icomLastHost.isNotEmpty()) {
                    scheduleIcomAutoReconnect()
                } else if (pttKeyedByApp) {
                    Log.w("TransceiverVM", "PTT release pending but no auto-reconnect (operator disconnect)")
                }
            } catch (e: Exception) {
                Log.w("TransceiverVM", "handleIcomLinkLost", e)
            } finally {
                icomLinkLostHandling = false
            }
        }
    }

    /**
     * Automatic reconnect after an unexpected Icom link loss, then restore what
     * was running: the pending unkey goes out as soon as rigctld is back (the
     * persistent unkey job) and RX decoding resumes if it was on. Each round is
     * a full connect() with its own ~1.5 min of handshake retries; after two
     * rounds the manager's error stays on screen for a manual Connect — by then
     * the radio's own TX time-out has long unkeyed it. Pressing the Rig button
     * while it shows CONNECTING… cancels (rigDisconnect → abortConnect).
     */
    private fun scheduleIcomAutoReconnect() {
        if (icomAutoReconnectJob?.isActive == true) return
        icomAutoReconnectJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1500)   // let the radio notice the dead session first
            for (round in 1..2) {
                if (icomUserDisconnect) return@launch
                Log.i("TransceiverVM", "Icom auto-reconnect round $round/2 → $icomLastHost:$icomLastPort")
                val ok = connectIcomNetwork(icomLastHost, icomLastPort, icomLastUser, icomLastPass)
                if (icomUserDisconnect) return@launch
                if (ok) {
                    Log.i("TransceiverVM", "Icom auto-reconnect succeeded " +
                        "(pttPending=$pttKeyedByApp resumeRx=$icomResumeRxAfterReconnect)")
                    if (pttKeyedByApp) startPersistentUnkey()
                    if (icomResumeRxAfterReconnect) {
                        icomResumeRxAfterReconnect = false
                        val st = audioService?.state?.value
                        if (st?.isRunning != true && st?.isTx != true) startReceiving()
                    }
                    return@launch
                }
                delay(5000)
            }
            Log.e("TransceiverVM", "Icom auto-reconnect gave up — press Connect to retry")
            // Nothing more we can do for a pending unkey: the radio's TX time-out
            // takes over. Clear the flag so TX isn't blocked once the operator
            // reconnects by hand.
            pttKeyedByApp = false
            pttUnkeyJob?.cancel()
            pttUnkeyJob = null
            icomResumeRxAfterReconnect = false
        }
    }

    /**
     * VBAN mode: the phone's RX/TX audio rides Wi-Fi to Voicemeeter on a PC,
     * whose virtual cables are the SDR host's (Thetis) VAC — PC-side features
     * like PureSignal keep working, with no sound card or cables between the
     * phone and PC. CAT/PTT optionally bridges to the SDR's CAT server through
     * the bundled rigctld ([catModel] as in the TCP Hermes-Lite 2 profile;
     * [catPort] 0 = no CAT).
     */
    fun rigStartThetisVban(host: String, vbanPort: Int, catModel: Int, catPort: Int) {
        if (_rigConnecting.value || rigController.isConnected || vbanNetwork.isConnected) return
        _rigConnecting.value = true
        rigMfg = "OpenHPSDR"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                rigctldProcess.stop()
                if (!vbanNetwork.connect(host, vbanPort)) return@launch  // vbanState carries the error
                audioService?.networkRig = vbanNetwork

                if (catPort > 0) {
                    val ok = rigctldProcess.start(model = catModel, device = "$host:$catPort", speed = 0)
                    if (ok) {
                        var connected = false
                        for (attempt in 1..10) {
                            delay(1000)
                            rigController.connect("127.0.0.1", 4532)
                            if (rigController.isConnected) { connected = true; break }
                        }
                        // CAT failure keeps the audio link up — the user can
                        // still operate with VOX / PC-side PTT.
                        if (!connected) rigctldProcess.stop()
                    }
                }
            } finally {
                _rigConnecting.value = false
            }
        }
    }

    /**
     * Connect straight to a Hermes-Lite 2 over openHPSDR protocol 1 (UDP 1024):
     * discovery (broadcast when [host] is blank), C&C for frequency/PTT/drive,
     * and the 48 kHz I/Q stream that the manager translates to/from the 8 kHz
     * modem audio. No rigctld and no PC host involved.
     */
    fun rigStartHermesNetwork(host: String) {
        if (_rigConnecting.value || rigController.isConnected || hermesNetwork.isConnected) return
        _rigConnecting.value = true
        rigMfg = "OpenHPSDR"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                rigctldProcess.stop()
                // Apply persisted controls before connect so the first C&C
                // frames already carry them.
                hermesNetwork.setFrequency(prefs.getLong("hl2_freq", 14_236_000L))
                hermesNetwork.setDrive(prefs.getInt("hl2_drive", 128))
                hermesNetwork.setLnaDb(prefs.getInt("hl2_lna", 19))
                hermesNetwork.setPaEnabled(prefs.getBoolean("hl2_pa", true))
                hermesNetwork.setPsEnabled(prefs.getBoolean("hl2_ps", false))
                if (hermesNetwork.connect(host)) {
                    audioService?.networkRig = hermesNetwork
                }
            } finally {
                _rigConnecting.value = false
            }
        }
    }

    fun hl2SetDrive(v: Int) {
        prefs.edit().putInt("hl2_drive", v).apply()
        hermesNetwork.setDrive(v)
    }

    fun hl2SetLnaDb(v: Int) {
        prefs.edit().putInt("hl2_lna", v).apply()
        hermesNetwork.setLnaDb(v)
    }

    fun hl2SetPaEnabled(v: Boolean) {
        prefs.edit().putBoolean("hl2_pa", v).apply()
        hermesNetwork.setPaEnabled(v)
    }

    /** PureSignal adaptive TX predistortion (experimental, WDSP engine). */
    fun hl2SetPsEnabled(v: Boolean) {
        prefs.edit().putBoolean("hl2_ps", v).apply()
        hermesNetwork.setPsEnabled(v)
    }

    fun getSavedHl2Drive(): Int = prefs.getInt("hl2_drive", 128)
    fun getSavedHl2LnaDb(): Int = prefs.getInt("hl2_lna", 19)
    fun getSavedHl2PaEnabled(): Boolean = prefs.getBoolean("hl2_pa", true)
    fun getSavedHl2PsEnabled(): Boolean = prefs.getBoolean("hl2_ps", false)

    /**
     * Start rig control over the radio's own Wi-Fi (Icom RS-BA1 network protocol,
     * e.g. IC-705).  Brings up the control + CI-V tunnel, exposes it as a local pty,
     * then runs the bundled rigctld (model 3085 = IC-705) against that pty so the
     * normal [RigController] path works.  Audio still flows over USB in this phase.
     */
    fun rigStartIcomNetwork(
        host: String,
        controlPort: Int,
        username: String,
        password: String,
        audioBufferMs: Int = IcomNetworkManager.DEFAULT_AUDIO_BUFFER_MS,
        txAudioRate: Int = IcomNetworkManager.TX_RATE_FULL
    ) {
        if (_rigConnecting.value || rigController.isConnected) return
        icomAutoReconnectJob?.cancel()
        icomAutoReconnectJob = null
        // Remembered for the automatic reconnect after a link loss.
        icomLastHost = host
        icomLastPort = controlPort
        icomLastUser = username
        icomLastPass = password
        // Radio-side TX audio buffer and TX sample rate (Rig tab): both ride the
        // conninfo packet, so they must be set before the handshake.
        icomNetwork.audioBufferMs = audioBufferMs
        icomNetwork.txAudioRate = txAudioRate
        viewModelScope.launch(Dispatchers.IO) {
            connectIcomNetwork(host, controlPort, username, password)
        }
    }

    /**
     * One full Icom connect: UDP handshake → pty → bundled rigctld → RigController.
     * Shared by the Connect button and the automatic reconnect after a link loss.
     * @return true when RigController ended up connected.
     */
    private suspend fun connectIcomNetwork(
        host: String, controlPort: Int, username: String, password: String
    ): Boolean {
        if (_rigConnecting.value || rigController.isConnected) return rigController.isConnected
        _rigConnecting.value = true
        rigMfg = "Icom"
        icomUserDisconnect = false   // a fresh connect intent supersedes a prior disconnect
        try {
            rigctldProcess.stop()
            val ptyPath = icomNetwork.connect(host, controlPort, username, password)
            if (ptyPath.isEmpty()) return false   // icomNetwork.state carries the error
            audioService?.networkRig = icomNetwork

            val ok = rigctldProcess.startWithPty(
                model = 3085,          // IC-705
                ptyPath = ptyPath,
                speed = 115200,        // baud is irrelevant for a pty
                civAddr = ""
            )
            if (!ok) {
                icomNetwork.disconnect()
                return false
            }

            var connected = false
            for (attempt in 1..10) {
                delay(1000)
                rigController.connect("127.0.0.1", 4532)
                if (rigController.isConnected) { connected = true; break }
            }
            if (!connected) {
                rigctldProcess.stop()
                icomNetwork.disconnect()
            }
            return connected
        } finally {
            _rigConnecting.value = false
        }
    }

    /** Start local rigctld process (serial mode, device path) then connect to it */
    fun rigStartLocal(model: Int, device: String, speed: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            rigctldProcess.stop()
            val ok = rigctldProcess.start(model = model, device = device, speed = speed)
            if (ok) {
                delay(800) // wait for rigctld to be ready
                rigController.connect("127.0.0.1", 4532)
            }
        }
    }

    /**
     * TCP CAT bridge: run the bundled rigctld with a network rig device —
     * hamlib treats a "host:port" pathname as a TCP connection in place of a
     * serial port. Needed for SDR apps whose CAT servers speak Kenwood
     * dialects rather than the rigctld protocol (Thetis = model 2048,
     * piHPSDR = model 2040); NET rigctl (model 2) covers SparkSDR/Quisk with
     * hamlib's proper \chk_vfo / \dump_state handshake.
     */
    fun rigStartTcpBridge(model: Int, host: String, port: Int) {
        if (_rigConnecting.value || rigController.isConnected) return
        _rigConnecting.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                rigctldProcess.stop()
                val ok = rigctldProcess.start(model = model, device = "$host:$port", speed = 0)
                if (!ok) return@launch

                var connected = false
                for (attempt in 1..10) {
                    delay(1000)
                    rigController.connect("127.0.0.1", 4532)
                    if (rigController.isConnected) { connected = true; break }
                }
                if (!connected) rigctldProcess.stop()
            } finally {
                _rigConnecting.value = false
            }
        }
    }

    private val _rigConnecting = MutableStateFlow(false)
    val rigConnecting: StateFlow<Boolean> = _rigConnecting.asStateFlow()

    /** Update DTR / RTS on the currently open USB serial device. No-op when disconnected. */
    fun setSerialModemLines(dtr: Boolean, rts: Boolean) {
        usbSerialManager.setModemLines(dtr, rts)
    }

    /** Start local rigctld via USB Host API + pty bridge (no root required) */
    fun rigStartLocalUsb(
        model: Int,
        usbDevice: UsbSerialManager.UsbSerialDevice,
        speed: Int,
        civAddr: String = "",
        dtr: Boolean = true,
        rts: Boolean = false
    ) {
        if (_rigConnecting.value || rigController.isConnected) return
        _rigConnecting.value = true

        usbSerialManager.openDevice(usbDevice, speed, dtr, rts) { ptyPath ->
            if (ptyPath.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rigctldProcess.stop()
                        val ok = rigctldProcess.startWithPty(
                            model = model, ptyPath = ptyPath, speed = speed, civAddr = civAddr
                        )
                        if (ok) {
                            // Poll for rigctld TCP readiness
                            var connected = false
                            for (attempt in 1..10) {
                                delay(1000)
                                rigController.connect("127.0.0.1", 4532)
                                if (rigController.isConnected) {
                                    connected = true
                                    // Auto-select USB audio output for TX
                                    refreshDevices()
                                    break
                                }
                            }
                            if (!connected) {
                                rigctldProcess.stop()
                                usbSerialManager.close()
                            }
                        } else {
                            rigController.disconnect()
                            usbSerialManager.close()
                        }
                    } finally {
                        _rigConnecting.value = false
                    }
                }
            } else {
                _rigConnecting.value = false
            }
        }
    }

    /** Manufacturers whose rigs do NOT support PKTUSB/PKTLSB data modes */
    private val noDataModeMfgs = setOf("Xiegu", "Alinco", "Drake", "AOR", "JRC")

    fun rigSetFreq(hz: Long) {
        if (hermesNetwork.isConnected) {
            // HL2 direct: NCO frequency rides the C&C stream; SSB "mode" is
            // implicit in the app's own demodulator (USB passband).
            hermesNetwork.setFrequency(hz)
            prefs.edit().putLong("hl2_freq", hz).apply()
            return
        }
        if (!rigController.isConnected) {
            // No CAT at all: keep the value as the manual dial frequency and
            // report it to FreeDV Reporter, like freedv-gui's frequency box.
            prefs.edit().putLong("manual_freq_hz", hz).apply()
            _manualFreqHz.value = hz
            if (reporter.connected.value) reporter.reportFreqChange(hz)
            return
        }
        viewModelScope.launch {
            rigController.setFreq(hz)
            // Auto-pick data mode for the band. RigController.setMode preserves the
            // rig's current filter (queries filter byte via CI-V before switching),
            // so the user's FIL1/FIL2/FIL3 selection stays intact.
            val useDataMode = rigMfg !in noDataModeMfgs
            val autoMode = if (hz < 10_000_000L) {
                if (useDataMode) "PKTLSB" else "LSB"
            } else {
                if (useDataMode) "PKTUSB" else "USB"
            }
            rigController.setMode(autoMode)
        }
    }

    /** Direct rig PTT control — works regardless of audio engine state */
    fun rigSetPtt(on: Boolean) {
        viewModelScope.launch { rigController.setPtt(on) }
    }

    /** Switch to TX — keys rig PTT even if audio engine is not running */
    fun rigPttOn() {
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(true) }
        }
    }

    fun rigPttOff() {
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(false) }
        }
    }

    /* ── Service binding ───────────────────────────────────── */

    private fun bindToService() {
        val app = getApplication<Application>()
        val intent = Intent(app, AudioService::class.java)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startCollectingServiceState() {
        serviceCollectJob = viewModelScope.launch {
            audioService?.state?.collect { svcState ->
                _uiState.value = _uiState.value.copy(
                    isRunning = svcState.isRunning,
                    leCommActive = svcState.leCommActive,
                    isTx = svcState.isTx,
                    syncState = svcState.syncState,
                    snrDb = svcState.snrDb,
                    freqOffsetHz = svcState.freqOffsetHz,
                    inputLevelDb = svcState.inputLevelDb,
                    outputLevelDb = svcState.outputLevelDb,
                    txLevelDb = svcState.txLevelDb,
                    lastCallsign = svcState.lastCallsign,
                    spectrum = svcState.spectrum,
                    unprocessedRejected = svcState.unprocessedRejected
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceCollectJob?.cancel()
        reporter.disconnect()
        locationTracker.stopTracking()
        rigController.destroy()
        rigctldProcess.destroy()
        usbSerialManager.destroy()
        icomNetwork.disconnect()
        hermesNetwork.disconnect()
        vbanNetwork.disconnect()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}

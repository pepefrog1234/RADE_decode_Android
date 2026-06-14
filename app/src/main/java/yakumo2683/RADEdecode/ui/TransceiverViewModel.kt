package yakumo2683.RADEdecode.ui

import android.app.Application
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import yakumo2683.RADEdecode.location.LocationTracker
import yakumo2683.RADEdecode.network.FreeDVReporter
import yakumo2683.RADEdecode.network.IcomNetworkManager
import yakumo2683.RADEdecode.network.RigController
import yakumo2683.RADEdecode.network.RigctldProcess
import yakumo2683.RADEdecode.service.AudioService
import yakumo2683.RADEdecode.usb.UsbSerialManager

/** RF tail between EOO playout and PTT release (ms): lets the rig flush its own
 *  USB-audio/TX chain so the end of the callsign isn't clipped. Other SDR apps
 *  insert a similar ~200ms TX delay. */
private const val TX_PTT_TAIL_MS = 200L

class TransceiverViewModel(application: Application) : AndroidViewModel(application) {

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

    data class UiState(
        val isRunning: Boolean = false,    // RX is active
        val isTx: Boolean = false,         // TX is active
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
        val pttHoldMode: Boolean = false     // hold-to-talk: TX is keyed only while the TX button is held
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AudioService.LocalBinder).service
            audioService = service
            service.reporter = reporter
            service.icomNetwork = icomNetwork   // enables full-wireless audio when IC-705 Wi-Fi is up
            // Restore persisted audio settings
            service.setInputGain(prefs.getFloat("input_gain", 4.0f))
            service.setOutputVolume(prefs.getFloat("output_volume", 1.0f))
            service.setTxVolume(prefs.getFloat("tx_volume", 0.2f))
            service.powerSaveMode = _uiState.value.powerSaveMode
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

    /** User's Reporter toggle preference, independent of whether reporter is actually connected. */
    private val _reporterEnabledPref = MutableStateFlow(false)
    val reporterEnabledPref: StateFlow<Boolean> = _reporterEnabledPref.asStateFlow()

    init {
        // Restore persisted callsign
        val savedCallsign = prefs.getString("tx_callsign", "") ?: ""
        if (savedCallsign.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(txCallsign = savedCallsign)
        }

        // Reporter toggle defaults to ON so the app auto-connects to qso.freedv.org
        // on launch; users can still opt out in Settings.
        _reporterEnabledPref.value = prefs.getBoolean("reporter_enabled", true)

        // Power-save mode (效能節約模式) — off by default; restored across launches.
        _uiState.value = _uiState.value.copy(
            powerSaveMode = prefs.getBoolean("power_save_mode", false),
            pttHoldMode = prefs.getBoolean("ptt_hold_mode", false),
            selectedRxOutputDeviceId = prefs.getInt(
                "rx_output_device",
                AudioService.RX_OUTPUT_AUTO
            )
        )
        // Load the persistent status message; reporter holds it and re-emits
        // on every (re)connect, so we just need to give it the saved value.
        reporter.updateMessage(prefs.getString("reporter_message", "") ?: "")
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
                    if (active && rigController.isConnected && reporter.connected.value) {
                        val freq = rigController.state.value.freqHz
                        if (freq > 0) reporter.reportFreqChange(freq)
                    }
                }
        }

        // When the reporter transitions to connected, push current freq + TX state
        viewModelScope.launch {
            reporter.connected.collect { connected ->
                if (connected) {
                    val freq = rigController.state.value.freqHz
                    val engineActive = _uiState.value.isRunning || _uiState.value.isTx
                    if (rigController.isConnected && engineActive && freq > 0) {
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
        if (locGrid.isNotEmpty()) return locGrid
        return prefs.getString("reporter_grid", "") ?: ""
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
    private fun useNetworkAudio(): Boolean = icomNetwork.isConnected

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
                    recordWav = false
                )
            }
        }
    }

    private fun stopReceiving() {
        audioService?.stopDecoding()
    }

    /* ── TX ─────────────────────────────────────────────────── */

    fun setTxCallsign(callsign: String) {
        _uiState.value = _uiState.value.copy(txCallsign = callsign)
        prefs.edit().putString("tx_callsign", callsign).apply()
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

    /** Hold-to-talk: when enabled the TX button keys TX only while held. */
    fun setPttHoldMode(enabled: Boolean) {
        prefs.edit().putBoolean("ptt_hold_mode", enabled).apply()
        _uiState.value = _uiState.value.copy(pttHoldMode = enabled)
    }

    /**
     * Release of the held TX button. A very short press can be released
     * before TX has finished engaging (isTx still false), and switchToRx()
     * would no-op then — leaving the rig keyed. Wait briefly for TX to
     * engage before switching back.
     */
    fun pttHoldRelease() {
        viewModelScope.launch {
            var waitedMs = 0
            while (!_uiState.value.isTx && waitedMs < 3000) {
                delay(50)
                waitedMs += 50
            }
            if (_uiState.value.isTx) switchToRx()
        }
    }

    fun setReporterGrid(grid: String) {
        prefs.edit().putString("reporter_grid", grid).apply()
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
                inputDeviceId = _uiState.value.builtInMicId,  // built-in mic, not USB audio
                outputDeviceId = _uiState.value.selectedOutputDeviceId,
                callsign = _uiState.value.txCallsign
            )
        }
    }

    /* ── Mode switching (while engine is active) ────────────── */

    /** Switch from RX → TX (stops RX, starts TX, keys PTT) */
    fun switchToTx() {
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
                doSwitchToTx()
            }
            return
        }
        doSwitchToTx()
    }

    private fun doSwitchToTx() {
        if (!_uiState.value.isRunning || _uiState.value.isTx) return
        // Auto-PTT via rigctld (both paths)
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(true) }
        }
        if (useNetworkAudio()) {
            // Full wireless: mic → encoder → UDP 50003. No USB audio devices.
            Log.i("TransceiverVM", "switchToTx (network): micId=${_uiState.value.builtInMicId}")
            audioService?.startNetworkTransmitting(
                inputDeviceId = _uiState.value.builtInMicId,
                callsign = _uiState.value.txCallsign
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
            inputDeviceId = _uiState.value.builtInMicId,
            outputDeviceId = outId,
            callsign = _uiState.value.txCallsign
        )
    }

    /** Switch from TX → RX: stop TX (drains the EOO callsign frame), hold the
     *  rig keyed for a short RF tail, then unkey PTT and resume RX. */
    fun switchToRx() {
        Log.i("TransceiverVM", "switchToRx: isTx=${_uiState.value.isTx}, rigConnected=${rigController.isConnected}")
        if (!_uiState.value.isTx) return
        if (txStopJob?.isActive == true) return
        txStopJob = viewModelScope.launch {
            stopTxAndUnkeyPtt()
            // Resume RX
            delay(100) // brief pause for clean transition
            if (useNetworkAudio()) {
                audioService?.startNetworkDecoding(
                    outputDeviceId = _uiState.value.selectedRxOutputDeviceId
                )
            } else {
                audioService?.startDecoding(
                    inputDeviceId = _uiState.value.selectedDeviceId,
                    outputDeviceId = _uiState.value.selectedRxOutputDeviceId,
                    recordWav = false
                )
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
    private suspend fun stopTxAndUnkeyPtt() {
        withContext(Dispatchers.IO) { audioService?.stopTransmitting() }
        delay(TX_PTT_TAIL_MS)
        if (rigController.isConnected) rigController.setPtt(false)
    }

    /** Stop everything and tear down the service */
    fun stopAll() {
        val app = getApplication<Application>()
        if (_uiState.value.isTx || txStopJob?.isActive == true) {
            // Let the EOO finish over the air and unkey before the service dies.
            viewModelScope.launch {
                txStopJob?.join()
                if (audioService?.state?.value?.isTx == true) stopTxAndUnkeyPtt()
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
            txLevelDb = -100f
        )
    }

    /* ── Device / settings ─────────────────────────────────── */

    fun selectDevice(deviceId: Int) {
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId)
    }

    fun selectRxOutputDevice(deviceId: Int) {
        prefs.edit().putInt("rx_output_device", deviceId).apply()
        _uiState.value = _uiState.value.copy(selectedRxOutputDeviceId = deviceId)
        audioService?.setRxOutputDevice(deviceId)
    }

    fun selectOutputDevice(deviceId: Int) {
        _uiState.value = _uiState.value.copy(selectedOutputDeviceId = deviceId)
    }

    fun setInputGain(gain: Float) {
        prefs.edit().putFloat("input_gain", gain).apply()
        audioService?.setInputGain(gain)
    }

    fun getSavedInputGain(): Float = prefs.getFloat("input_gain", 4.0f)

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

    fun refreshDevices() {
        val bridge = AudioBridge(getApplication())
        val devices = bridge.getInputDevices()
        val outputDevices = bridge.getOutputDevices()
        val usbInput = bridge.findUsbInputDevice()
        val usbOutput = outputDevices.firstOrNull { it.isUsb }
        val builtInMic = bridge.findBuiltInMic()
        bridge.release()
        val current = _uiState.value
        val inputIds = devices.map { it.id }.toSet()
        val outputIds = outputDevices.map { it.id }.toSet()
        val selectedInputId = when {
            current.selectedDeviceId > 0 && current.selectedDeviceId in inputIds -> current.selectedDeviceId
            else -> usbInput?.id ?: current.selectedDeviceId
        }
        val selectedTxOutputId = when {
            current.selectedOutputDeviceId > 0 && current.selectedOutputDeviceId in outputIds -> current.selectedOutputDeviceId
            else -> usbOutput?.id ?: current.selectedOutputDeviceId
        }
        val selectedRxOutputId = when {
            current.selectedRxOutputDeviceId > 0 && current.selectedRxOutputDeviceId !in outputIds ->
                AudioService.RX_OUTPUT_AUTO
            else -> current.selectedRxOutputDeviceId
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

    /* ── Rig control (rigctld) ─────────────────────────────── */

    /** Rig manufacturer of the currently selected model, used to pick USB/PKTUSB */
    var rigMfg: String = ""

    fun rigConnect(host: String, port: Int) {
        viewModelScope.launch { rigController.connect(host, port) }
    }

    fun rigDisconnect() {
        rigController.disconnect()
        rigctldProcess.stop()
        usbSerialManager.close()
        icomNetwork.disconnect()
    }

    /**
     * Start rig control over the radio's own Wi-Fi (Icom RS-BA1 network protocol,
     * e.g. IC-705).  Brings up the control + CI-V tunnel, exposes it as a local pty,
     * then runs the bundled rigctld (model 3085 = IC-705) against that pty so the
     * normal [RigController] path works.  Audio still flows over USB in this phase.
     */
    fun rigStartIcomNetwork(host: String, controlPort: Int, username: String, password: String) {
        if (_rigConnecting.value || rigController.isConnected) return
        _rigConnecting.value = true
        rigMfg = "Icom"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                rigctldProcess.stop()
                val ptyPath = icomNetwork.connect(host, controlPort, username, password)
                if (ptyPath.isEmpty()) return@launch   // icomNetwork.state carries the error

                val ok = rigctldProcess.startWithPty(
                    model = 3085,          // IC-705
                    ptyPath = ptyPath,
                    speed = 115200,        // baud is irrelevant for a pty
                    civAddr = ""
                )
                if (!ok) {
                    icomNetwork.disconnect()
                    return@launch
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
            } finally {
                _rigConnecting.value = false
            }
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
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}

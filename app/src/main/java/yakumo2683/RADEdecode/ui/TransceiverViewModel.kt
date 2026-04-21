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
import yakumo2683.RADEdecode.network.RigController
import yakumo2683.RADEdecode.network.RigctldProcess
import yakumo2683.RADEdecode.service.AudioService
import yakumo2683.RADEdecode.usb.UsbSerialManager

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
        val selectedOutputDeviceId: Int = -1,
        val builtInMicId: Int = -1,
        val serviceBound: Boolean = false
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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AudioService.LocalBinder).service
            audioService = service
            service.reporter = reporter
            // Restore persisted audio settings
            service.setInputGain(prefs.getFloat("input_gain", 4.0f))
            service.setOutputVolume(prefs.getFloat("output_volume", 1.0f))
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
            audioService?.startDecoding(
                inputDeviceId = _uiState.value.selectedDeviceId,
                recordWav = false
            )
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

    fun setReporterGrid(grid: String) {
        prefs.edit().putString("reporter_grid", grid).apply()
        syncReporterState()
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

    private fun stopTransmitting() {
        audioService?.stopTransmitting()
    }

    /* ── Mode switching (while engine is active) ────────────── */

    /** Switch from RX → TX (stops RX, starts TX, keys PTT) */
    fun switchToTx() {
        if (!_uiState.value.isRunning) return
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
        // Auto-PTT via rigctld
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(true) }
        }
    }

    /** Switch from TX → RX (unkeys PTT, stops TX, resumes RX) */
    fun switchToRx() {
        Log.i("TransceiverVM", "switchToRx: isTx=${_uiState.value.isTx}, rigConnected=${rigController.isConnected}")
        if (!_uiState.value.isTx) return
        // Auto-PTT off
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(false) }
        }
        stopTransmitting()
        // Resume RX
        viewModelScope.launch {
            delay(100) // brief pause for clean transition
            audioService?.startDecoding(
                inputDeviceId = _uiState.value.selectedDeviceId,
                recordWav = false
            )
        }
    }

    /** Stop everything and tear down the service */
    fun stopAll() {
        if (_uiState.value.isTx) stopTransmitting()
        else stopReceiving()

        val app = getApplication<Application>()
        app.stopService(Intent(app, AudioService::class.java))
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

    fun refreshDevices() {
        val bridge = AudioBridge(getApplication())
        val devices = bridge.getInputDevices()
        val outputDevices = bridge.getOutputDevices()
        val usbInput = bridge.findUsbInputDevice()
        val usbOutput = outputDevices.firstOrNull { it.isUsb }
        val builtInMic = bridge.findBuiltInMic()
        bridge.release()

        _uiState.value = _uiState.value.copy(
            devices = devices,
            outputDevices = outputDevices,
            builtInMicId = builtInMic?.id ?: _uiState.value.builtInMicId,
            selectedDeviceId = usbInput?.id ?: _uiState.value.selectedDeviceId,
            selectedOutputDeviceId = usbOutput?.id ?: _uiState.value.selectedOutputDeviceId
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
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}

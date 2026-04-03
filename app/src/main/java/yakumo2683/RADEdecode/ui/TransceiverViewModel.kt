package yakumo2683.RADEdecode.ui

import android.app.Application
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
import yakumo2683.RADEdecode.AudioBridge
import yakumo2683.RADEdecode.location.LocationTracker
import yakumo2683.RADEdecode.network.FreeDVReporter
import yakumo2683.RADEdecode.network.RigController
import yakumo2683.RADEdecode.network.RigctldProcess
import yakumo2683.RADEdecode.service.AudioService

class TransceiverViewModel(application: Application) : AndroidViewModel(application) {

    /* ── FreeDV Reporter ────────────────────────────────────── */
    val reporter = FreeDVReporter(viewModelScope)
    val locationTracker = LocationTracker(application)

    /* ── Rig controller (rigctld TCP) ──────────────────────── */
    private val rigController = RigController()
    private val rigctldProcess = RigctldProcess(application)
    val rigState: StateFlow<RigController.RigState> = rigController.state

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
        val devices: List<AudioBridge.AudioDevice> = emptyList(),
        val outputDevices: List<AudioBridge.AudioDevice> = emptyList(),
        val selectedDeviceId: Int = -1,
        val selectedOutputDeviceId: Int = -1,
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

    init {
        // Restore persisted callsign
        val savedCallsign = prefs.getString("tx_callsign", "") ?: ""
        if (savedCallsign.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(txCallsign = savedCallsign)
        }

        // Restore reporter config
        val reporterEnabled = prefs.getBoolean("reporter_enabled", false)
        val reporterGrid = prefs.getString("reporter_grid", "") ?: ""
        if (reporterEnabled && savedCallsign.isNotEmpty()) {
            reporter.configure(savedCallsign, reporterGrid, true)
        }

        // Update reporter grid square when location changes
        viewModelScope.launch {
            locationTracker.state.collect { loc ->
                if (loc.gridSquare.isNotEmpty() && reporter.config.enabled) {
                    reporter.configure(
                        reporter.config.callsign,
                        loc.gridSquare,
                        true
                    )
                }
            }
        }

        bindToService()
        refreshDevices()
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
        // Update reporter callsign if enabled
        if (reporter.config.enabled) {
            reporter.configure(callsign, reporter.config.gridSquare, true)
        }
    }

    /* ── Reporter ──────────────────────────────────────────── */

    fun setReporterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("reporter_enabled", enabled).apply()
        val callsign = _uiState.value.txCallsign
        val grid = locationTracker.state.value.gridSquare.ifEmpty { reporter.config.gridSquare }
        reporter.configure(callsign, grid, enabled)
    }

    fun setReporterGrid(grid: String) {
        prefs.edit().putString("reporter_grid", grid).apply()
        if (reporter.config.enabled) {
            reporter.configure(reporter.config.callsign, grid, true)
        }
    }

    private fun startTransmitting() {
        viewModelScope.launch {
            var attempts = 0
            while (audioService == null && attempts < 20) {
                delay(100)
                attempts++
            }
            audioService?.startTransmitting(
                inputDeviceId = _uiState.value.selectedDeviceId,
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
        // startTransmitting handles stopping RX internally (atomic transition)
        audioService?.startTransmitting(
            inputDeviceId = _uiState.value.selectedDeviceId,
            outputDeviceId = _uiState.value.selectedOutputDeviceId,
            callsign = _uiState.value.txCallsign
        )
        // Auto-PTT via rigctld
        if (rigController.isConnected) {
            viewModelScope.launch { rigController.setPtt(true) }
        }
    }

    /** Switch from TX → RX (unkeys PTT, stops TX, resumes RX) */
    fun switchToRx() {
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
        audioService?.setInputGain(gain)
    }

    fun setVolume(volume: Float) {
        audioService?.setOutputVolume(volume)
    }

    fun refreshDevices() {
        val bridge = AudioBridge(getApplication())
        val devices = bridge.getInputDevices()
        val outputDevices = bridge.getOutputDevices()
        val usbDevice = bridge.findUsbInputDevice()
        bridge.release()

        _uiState.value = _uiState.value.copy(
            devices = devices,
            outputDevices = outputDevices,
            selectedDeviceId = usbDevice?.id ?: _uiState.value.selectedDeviceId
        )
    }

    /* ── Rig control (rigctld) ─────────────────────────────── */

    fun rigConnect(host: String, port: Int) {
        viewModelScope.launch { rigController.connect(host, port) }
    }

    fun rigDisconnect() {
        rigController.disconnect()
        rigctldProcess.stop()
    }

    /** Start local rigctld process (serial mode) then connect to it */
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

    fun rigSetFreq(hz: Long) {
        viewModelScope.launch {
            rigController.setFreq(hz)
            // Auto-mode: PKTLSB below 10 MHz, PKTUSB at 10 MHz and above
            val autoMode = if (hz < 10_000_000L) "PKTLSB" else "PKTUSB"
            rigController.setMode(autoMode)
        }
    }

    fun rigSetMode(mode: String) {
        viewModelScope.launch { rigController.setMode(mode) }
    }

    fun rigSetPtt(on: Boolean) {
        viewModelScope.launch { rigController.setPtt(on) }
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
                    spectrum = svcState.spectrum
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
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}

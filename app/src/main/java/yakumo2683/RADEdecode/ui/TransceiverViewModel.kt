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
import yakumo2683.RADEdecode.service.AudioService

class TransceiverViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isRunning: Boolean = false,
        val syncState: Int = 0,
        val snrDb: Int = 0,
        val freqOffsetHz: Float = 0f,
        val inputLevelDb: Float = -100f,
        val outputLevelDb: Float = -100f,
        val lastCallsign: String = "",
        val spectrum: FloatArray = FloatArray(AudioBridge.SPECTRUM_BINS) { -100f },
        val devices: List<AudioBridge.AudioDevice> = emptyList(),
        val selectedDeviceId: Int = -1,
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
            _uiState.value = _uiState.value.copy(serviceBound = true)
            startCollectingServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            serviceCollectJob?.cancel()
            _uiState.value = _uiState.value.copy(serviceBound = false, isRunning = false)
        }
    }

    init {
        // Bind to service if already running
        bindToService()
        refreshDevices()
    }

    fun startReceiving() {
        val app = getApplication<Application>()

        // Start the foreground service
        val intent = Intent(app, AudioService::class.java)
        app.startForegroundService(intent)

        // Bind to it
        bindToService()

        // Wait for bind, then start decoding
        viewModelScope.launch {
            // Wait until bound
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

    fun stopReceiving() {
        audioService?.stopDecoding()
        val app = getApplication<Application>()
        app.stopService(Intent(app, AudioService::class.java))
        _uiState.value = _uiState.value.copy(
            isRunning = false,
            syncState = 0,
            snrDb = 0,
            freqOffsetHz = 0f,
            inputLevelDb = -100f,
            outputLevelDb = -100f
        )
    }

    fun selectDevice(deviceId: Int) {
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId)
    }

    fun setInputGain(gain: Float) {
        audioService?.setInputGain(gain)
    }

    fun setVolume(volume: Float) {
        audioService?.setOutputVolume(volume)
    }

    fun refreshDevices() {
        // Use a temporary AudioBridge just for device enumeration
        val bridge = AudioBridge(getApplication())
        val devices = bridge.getInputDevices()
        val usbDevice = bridge.findUsbInputDevice()
        bridge.release()

        _uiState.value = _uiState.value.copy(
            devices = devices,
            selectedDeviceId = usbDevice?.id ?: _uiState.value.selectedDeviceId
        )
    }

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
                    syncState = svcState.syncState,
                    snrDb = svcState.snrDb,
                    freqOffsetHz = svcState.freqOffsetHz,
                    inputLevelDb = svcState.inputLevelDb,
                    outputLevelDb = svcState.outputLevelDb,
                    lastCallsign = svcState.lastCallsign,
                    spectrum = svcState.spectrum
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceCollectJob?.cancel()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) { }
    }
}

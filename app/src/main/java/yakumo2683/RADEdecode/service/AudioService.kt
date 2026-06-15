package yakumo2683.RADEdecode.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import yakumo2683.RADEdecode.AudioBridge
import yakumo2683.RADEdecode.MainActivity
import yakumo2683.RADEdecode.R
import yakumo2683.RADEdecode.data.*

/**
 * Foreground Service that keeps the RADE audio engine running in the background.
 *
 * Unlike iOS which must defer decoding until the app returns to foreground,
 * Android's foreground service allows continuous real-time decoding even when
 * the app is backgrounded. The notification shows live sync state and SNR.
 */
class AudioService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: AudioService get() = this@AudioService
    }

    private val binder = LocalBinder()
    var reporter: yakumo2683.RADEdecode.network.FreeDVReporter? = null
    /** Set by the ViewModel when rig control runs over the IC-705's Wi-Fi.
     *  When non-null and its audio stream is up, RX/TX audio rides UDP 50003
     *  instead of a USB sound card ("full wireless"). */
    var icomNetwork: yakumo2683.RADEdecode.network.IcomNetworkManager? = null
    private var audioBridge: AudioBridge? = null
    private var pollingJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var db: AppDatabase? = null
    private var wavRecorder: WavRecorder? = null
    private var bluetoothCommunicationRouteActive = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    @Volatile private var rxAudioTrack: AudioTrack? = null
    private var rxPumpJob: Job? = null
    @Volatile private var rxOutputVolume: Float = 1.0f
    @Volatile private var rxJavaOutputLevelDb: Float = -100f

    // Current session tracking
    private var currentWavPath: String? = null
    private var currentSession: ReceptionSession? = null
    private var sessionStartTime: Long = 0
    private var lastSyncState: Int = 0
    private var totalModemFrames: Int = 0
    private var syncedFrames: Int = 0
    private var currentInputDeviceId: Int = -1

    // Session splitting: finalize session when sync lost > 2 seconds
    private var lastSyncedTime: Long = 0   // last time syncState was 2 (0 = never synced this session)
    private var lastRxReportMs: Long = 0   // last time we emitted an rx_report to the reporter

    /** Power-save (低效能裝置) mode: skip the native FFT and stop publishing
     *  spectrum updates so weak devices can keep decoding smoothly. Set by the
     *  ViewModel; read on the polling loop, hence @Volatile. */
    @Volatile
    var powerSaveMode: Boolean = false

    companion object {
        const val CHANNEL_ID = "rade_decode_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "yakumo2683.RADEdecode.STOP"
        const val RX_OUTPUT_AUTO = -1
        const val RX_OUTPUT_SYSTEM_DEFAULT = 0
        private const val SYNC_LOST_TIMEOUT_MS = 2000L
        /** Shared flat spectrum published while in power-save mode (never mutated). */
        private val FLAT_SPECTRUM = FloatArray(AudioBridge.SPECTRUM_BINS) { -100f }
    }

    data class ServiceState(
        val isRunning: Boolean = false,
        val isTx: Boolean = false,
        val syncState: Int = 0,
        val snrDb: Int = 0,
        val freqOffsetHz: Float = 0f,
        val inputLevelDb: Float = -100f,
        val outputLevelDb: Float = -100f,
        val txLevelDb: Float = -100f,
        val lastCallsign: String = "",
        val spectrum: FloatArray = FloatArray(AudioBridge.SPECTRUM_BINS) { -100f },
        val unprocessedRejected: Boolean = false
    )

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val spectrumBuffer = FloatArray(AudioBridge.SPECTRUM_BINS)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        db = AppDatabase.getInstance(applicationContext)
        // Close any sessions left open from a previous crash/kill
        lifecycleScope.launch(Dispatchers.IO) {
            db?.closeOrphanedSessions()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            if (_state.value.isTx) stopTransmitting()
            else stopDecoding()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    fun startDecoding(
        inputDeviceId: Int = -1,
        outputDeviceId: Int = RX_OUTPUT_AUTO,
        recordWav: Boolean = false
    ) {
        if (_state.value.isRunning) return

        val bridge = AudioBridge(applicationContext)
        audioBridge = bridge

        bridge.callback = object : AudioBridge.Callback {
            override fun onSyncStateChanged(state: Int) {
                handleSyncChange(state)
            }

            override fun onCallsignDecoded(callsign: String) {
                handleCallsignDecoded(callsign)
            }
        }

        // Start as foreground service
        val notification = buildNotification(getString(R.string.notification_starting), 0, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val rxOutputDeviceId = resolveRxOutputDeviceId(bridge, outputDeviceId)
        val bluetoothRouteActive = prepareBluetoothCommunicationRoute(inputDeviceId)
        val effectiveInputDeviceId = effectiveBluetoothInputDeviceId(inputDeviceId, bluetoothRouteActive)
        val effectiveOutputDeviceId = effectiveBluetoothOutputDeviceId(rxOutputDeviceId, bluetoothRouteActive)
        val useJavaRxOutput = shouldUseJavaRxOutput(rxOutputDeviceId)
        bridge.setRxJavaOutputEnabled(useJavaRxOutput)
        val nativeOutputDeviceId = if (useJavaRxOutput) -1 else effectiveOutputDeviceId
        Log.i(
            "AudioService",
            "startDecoding: input=$inputDeviceId effectiveInput=$effectiveInputDeviceId " +
                "rxOutputSelection=$outputDeviceId resolved=$rxOutputDeviceId " +
                "effectiveOutput=$effectiveOutputDeviceId nativeOutput=$nativeOutputDeviceId " +
                "bluetoothRoute=$bluetoothRouteActive javaOutput=$useJavaRxOutput"
        )
        if (!bridge.start(effectiveInputDeviceId, nativeOutputDeviceId)) {
            stopRxAudioTrackPump()
            clearBluetoothCommunicationRouteIfNeeded()
            stopSelf()
            return
        }
        if (useJavaRxOutput) startRxAudioTrackPump(bridge, rxOutputDeviceId)

        // Disable platform-applied audio effects (AGC, NS, AEC) that would
        // corrupt the RADE signal on OEMs that ignore the Unprocessed preset
        // (e.g. Samsung Galaxy S24). Held effects live until bridge.stop().
        bridge.disableInputEffects()

        // Session will be created on first sync (in polling loop)
        currentInputDeviceId = effectiveInputDeviceId

        _state.value = _state.value.copy(isRunning = true)
        startPolling()
        startNotificationUpdates()
    }

    fun setInputGain(gain: Float) {
        audioBridge?.setInputGain(gain)
    }

    fun stopDecoding() {
        stopPolling()
        stopNotificationUpdates()

        // Detach the network-audio feed before tearing down the engine.
        icomNetwork?.onAudioPcm = null
        networkAudioMode = false

        // Stop native WAV recording
        audioBridge?.stopRecording()

        stopRxAudioTrackPump()
        audioBridge?.stop()
        audioBridge?.release()
        audioBridge = null
        clearBluetoothCommunicationRouteIfNeeded()

        // Finalize current session to DB
        finalizeCurrentSession()

        _state.value = ServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun setOutputVolume(volume: Float) {
        rxOutputVolume = volume.coerceIn(0f, 1f)
        rxAudioTrack?.setVolume(rxOutputVolume)
        audioBridge?.setOutputVolume(volume)
    }

    fun setRxOutputDevice(deviceId: Int) {
        val bridge = audioBridge ?: return
        if (!_state.value.isRunning || _state.value.isTx) return
        val resolved = resolveRxOutputDeviceId(bridge, deviceId)
        val bluetoothRouteActive = prepareBluetoothCommunicationRoute(null)
        val effectiveOutput = effectiveBluetoothOutputDeviceId(resolved, bluetoothRouteActive)
        val useJavaRxOutput = shouldUseJavaRxOutput(resolved)
        Log.i(
            "AudioService",
            "setRxOutputDevice: selection=$deviceId resolved=$resolved " +
                "effectiveOutput=$effectiveOutput bluetoothRoute=$bluetoothRouteActive " +
                "javaOutput=$useJavaRxOutput"
        )
        stopRxAudioTrackPump()
        bridge.setRxJavaOutputEnabled(useJavaRxOutput)
        bridge.setOutputDevice(if (useJavaRxOutput) -1 else effectiveOutput)
        if (useJavaRxOutput) startRxAudioTrackPump(bridge, resolved)
    }

    fun setRxAudioDevices(inputDeviceId: Int?, outputDeviceId: Int) {
        val bridge = audioBridge ?: return
        if (!_state.value.isRunning || _state.value.isTx) return

        val resolvedOutput = resolveRxOutputDeviceId(bridge, outputDeviceId)
        val bluetoothRouteActive = prepareBluetoothCommunicationRoute(inputDeviceId)
        val effectiveOutput = effectiveBluetoothOutputDeviceId(resolvedOutput, bluetoothRouteActive)
        val useJavaRxOutput = shouldUseJavaRxOutput(resolvedOutput)
        val nativeOutput = if (useJavaRxOutput) -1 else effectiveOutput
        stopRxAudioTrackPump()
        bridge.setRxJavaOutputEnabled(useJavaRxOutput)

        if (networkAudioMode || (inputDeviceId == null && !bluetoothRouteActive)) {
            Log.i(
                "AudioService",
                "setRxAudioDevices: outputOnly selection=$outputDeviceId resolved=$resolvedOutput " +
                    "effectiveOutput=$effectiveOutput nativeOutput=$nativeOutput " +
                    "bluetoothRoute=$bluetoothRouteActive javaOutput=$useJavaRxOutput"
            )
            bridge.setOutputDevice(nativeOutput)
            if (useJavaRxOutput) startRxAudioTrackPump(bridge, resolvedOutput)
            return
        }

        val resolvedInput = inputDeviceId?.takeIf { it > 0 } ?: -1
        val effectiveInput = effectiveBluetoothInputDeviceId(resolvedInput, bluetoothRouteActive)
        Log.i(
            "AudioService",
            "setRxAudioDevices: input=$resolvedInput effectiveInput=$effectiveInput " +
                "outputSelection=$outputDeviceId resolvedOutput=$resolvedOutput " +
                "effectiveOutput=$effectiveOutput nativeOutput=$nativeOutput " +
                "bluetoothRoute=$bluetoothRouteActive javaOutput=$useJavaRxOutput"
        )
        bridge.setDevices(effectiveInput, nativeOutput)
        bridge.disableInputEffects()
        currentInputDeviceId = effectiveInput
        if (useJavaRxOutput) startRxAudioTrackPump(bridge, resolvedOutput)
    }

    /**
     * TX USB audio output level (0..1). Lowered from the previous hard-coded 5%
     * because that was too quiet for some rigs (e.g. IC-7300) — ALC barely moved
     * and the user couldn't reach rated power. The user now drives this from
     * Settings; too hot a value will slam ALC, so document the rig's USB MOD level.
     */
    @Volatile private var txVolume: Float = 0.2f

    fun setTxVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        txVolume = v
        txAudioTrack?.setVolume(v)
    }

    fun getInputDevices(): List<AudioBridge.AudioDevice> {
        return audioBridge?.getInputDevices() ?: emptyList()
    }

    fun getOutputDevices(): List<AudioBridge.AudioDevice> {
        return audioBridge?.getOutputDevices() ?: emptyList()
    }

    private fun resolveRxOutputDeviceId(bridge: AudioBridge, selection: Int): Int {
        return when {
            selection > 0 -> selection
            selection == RX_OUTPUT_SYSTEM_DEFAULT -> -1
            else -> bridge.findPreferredRxOutputDevice()?.id ?: -1
        }
    }

    /**
     * Whether decoded RX speech plays through a Java [AudioTrack] instead of the
     * native Oboe output stream.
     *
     * Always false. ALL RX output — including Bluetooth A2DP — goes through native
     * Oboe, which opens the stream directly on the chosen device id
     * (`AAudioStreamBuilder_setDeviceId`, a hard device request). This is the
     * v1.5.22 path the user confirmed worked for Bluetooth playback.
     *
     * The v1.5.26 Java path used [AudioTrack.setPreferredDevice], which is only a
     * soft hint the platform audio policy can override — e.g. bouncing media back
     * to the speaker/USB while a USB audio device is attached. That, layered on
     * the v1.5.24 mic/SCO changes, is the regression the user bisected ("v1.5.22
     * までは Bluetooth 再生は正常"). The Java pump below is left dormant rather than
     * deleted so it can be revisited if a device ever needs it.
     */
    private fun shouldUseJavaRxOutput(@Suppress("UNUSED_PARAMETER") outputDeviceId: Int): Boolean = false

    private fun prepareBluetoothCommunicationRoute(inputDeviceId: Int?): Boolean {
        if (!usesBluetoothRxRoute(inputDeviceId)) {
            clearBluetoothCommunicationRouteIfNeeded()
            return false
        }

        if (!hasBluetoothConnectPermission()) {
            Log.w("AudioService", "Bluetooth route requested without BLUETOOTH_CONNECT permission")
            return false
        }

        val am = audioManager()
        val wasActive = bluetoothCommunicationRouteActive
        if (!wasActive) previousAudioMode = am.mode

        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scoDevice = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (scoDevice == null) {
                    Log.w("AudioService", "No Bluetooth SCO communication device is available")
                    if (wasActive) clearBluetoothCommunicationRouteIfNeeded()
                    else am.mode = previousAudioMode
                    false
                } else {
                    val routed = am.setCommunicationDevice(scoDevice)
                    bluetoothCommunicationRouteActive = routed
                    Log.i(
                        "AudioService",
                        "Bluetooth communication route: routed=$routed " +
                            "device=${scoDevice.productName} id=${scoDevice.id}"
                    )
                    if (!routed) {
                        if (wasActive) clearBluetoothCommunicationRouteIfNeeded()
                        else am.mode = previousAudioMode
                    }
                    routed
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                bluetoothCommunicationRouteActive = true
                Log.i("AudioService", "Bluetooth SCO route requested through legacy API")
                true
            }
        } catch (e: SecurityException) {
            if (wasActive) clearBluetoothCommunicationRouteIfNeeded()
            else am.mode = previousAudioMode
            Log.w("AudioService", "Bluetooth communication route denied", e)
            false
        } catch (e: RuntimeException) {
            if (wasActive) clearBluetoothCommunicationRouteIfNeeded()
            else am.mode = previousAudioMode
            Log.w("AudioService", "Bluetooth communication route failed", e)
            false
        }
    }

    private fun clearBluetoothCommunicationRouteIfNeeded() {
        if (!bluetoothCommunicationRouteActive) return

        val am = audioManager()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                run {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
            }
            am.mode = previousAudioMode
            Log.i("AudioService", "Bluetooth communication route cleared")
        } catch (e: RuntimeException) {
            Log.w("AudioService", "Failed to clear Bluetooth communication route", e)
        } finally {
            bluetoothCommunicationRouteActive = false
        }
    }

    private fun usesBluetoothRxRoute(inputDeviceId: Int?): Boolean {
        return inputDeviceId != null &&
            inputDeviceId > 0 &&
            isBluetoothDevice(inputDeviceId, AudioManager.GET_DEVICES_INPUTS)
    }

    private fun effectiveBluetoothInputDeviceId(requestedInputDeviceId: Int, routeActive: Boolean): Int {
        if (!routeActive) return requestedInputDeviceId
        if (requestedInputDeviceId > 0 &&
            isBluetoothScoDevice(requestedInputDeviceId, AudioManager.GET_DEVICES_INPUTS)
        ) {
            return requestedInputDeviceId
        }
        return findBluetoothScoDeviceId(AudioManager.GET_DEVICES_INPUTS) ?: -1
    }

    private fun effectiveBluetoothOutputDeviceId(requestedOutputDeviceId: Int, routeActive: Boolean): Int {
        if (!routeActive) return requestedOutputDeviceId
        if (requestedOutputDeviceId > 0 &&
            isBluetoothScoDevice(requestedOutputDeviceId, AudioManager.GET_DEVICES_OUTPUTS)
        ) {
            return requestedOutputDeviceId
        }
        return findBluetoothScoDeviceId(AudioManager.GET_DEVICES_OUTPUTS) ?: -1
    }

    private fun findBluetoothScoDeviceId(flags: Int): Int? =
        getAudioDevices(flags).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.id

    private fun isBluetoothDevice(deviceId: Int, flags: Int): Boolean {
        val type = getAudioDevices(flags).firstOrNull { it.id == deviceId }?.type ?: return false
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun isBluetoothScoDevice(deviceId: Int, flags: Int): Boolean =
        getAudioDevices(flags).firstOrNull { it.id == deviceId }?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun getAudioDevices(flags: Int): Array<AudioDeviceInfo> {
        return try {
            audioManager().getDevices(flags)
        } catch (e: SecurityException) {
            Log.w("AudioService", "Audio device listing requires Bluetooth permission", e)
            emptyArray()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun audioManager(): AudioManager =
        getSystemService(AUDIO_SERVICE) as AudioManager

    /* ── Network audio (IC-705 Wi-Fi, full wireless) ─────────── */

    private var networkAudioMode = false
    private var netTxPumpJob: Job? = null
    /** Counted down when the net TX pump has drained the EOO out of the ring. */
    private var netTxPumpDone: java.util.concurrent.CountDownLatch? = null

    /**
     * RX over Wi-Fi: decode audio arriving on UDP 50003 (via [icomNetwork]) and
     * play the recovered speech on the selected RX output. No USB sound card involved.
     */
    fun startNetworkDecoding(outputDeviceId: Int = RX_OUTPUT_AUTO) {
        if (_state.value.isRunning || _state.value.isTx) return
        val net = icomNetwork ?: run {
            Log.e("AudioService", "startNetworkDecoding: icomNetwork not set")
            return
        }

        val bridge = AudioBridge(applicationContext)
        audioBridge = bridge
        bridge.callback = object : AudioBridge.Callback {
            override fun onSyncStateChanged(state: Int) = handleSyncChange(state)
            override fun onCallsignDecoded(callsign: String) = handleCallsignDecoded(callsign)
        }

        val notification = buildNotification(getString(R.string.notification_starting), 0, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val audioUp = net.state.value.audioConnected
        Log.i("AudioService", "startNetworkDecoding: audioStreamConnected=$audioUp " +
            "(if false, no RX audio will arrive over Wi-Fi — check radio audio settings)")

        val rxOutputDeviceId = resolveRxOutputDeviceId(bridge, outputDeviceId)
        val useJavaRxOutput = shouldUseJavaRxOutput(rxOutputDeviceId)
        bridge.setRxJavaOutputEnabled(useJavaRxOutput)
        Log.i(
            "AudioService",
            "startNetworkDecoding: rxOutputSelection=$outputDeviceId resolved=$rxOutputDeviceId " +
                "javaOutput=$useJavaRxOutput"
        )
        val nativeOutputDeviceId = if (useJavaRxOutput) -1 else rxOutputDeviceId
        if (!bridge.startNetRx(nativeOutputDeviceId, yakumo2683.RADEdecode.network.IcomNetworkManager.NET_AUDIO_RATE)) {
            Log.e("AudioService", "startNetRx failed")
            stopRxAudioTrackPump()
            stopSelf()
            return
        }
        if (useJavaRxOutput) startRxAudioTrackPump(bridge, rxOutputDeviceId)

        // Received UDP PCM → modem. The callback fires on the audio stream's
        // consume coroutine; feedNetRx is a no-op once the engine is stopped.
        net.onAudioPcm = { pcm -> audioBridge?.feedNetRx(pcm, pcm.size) }

        networkAudioMode = true
        currentInputDeviceId = -1
        _state.value = _state.value.copy(isRunning = true)
        startPolling()
        startNotificationUpdates()
    }

    /**
     * TX over Wi-Fi: mic → RADE encoder → UDP 50003 audio frames to the radio.
     * @param inputDeviceId built-in mic device id (USB audio is the rig's RX feed).
     */
    fun startNetworkTransmitting(inputDeviceId: Int, callsign: String) {
        if (_state.value.isTx) return
        val net = icomNetwork ?: run {
            Log.e("AudioService", "startNetworkTransmitting: icomNetwork not set")
            return
        }

        // Stop network RX (mute + tear down the RX engine), keep foreground.
        if (_state.value.isRunning) {
            stopPolling()
            stopNotificationUpdates()
            net.onAudioPcm = null
            audioBridge?.setOutputVolume(0f)
            stopRxAudioTrackPump()
            audioBridge?.stop()
            audioBridge?.release()
            audioBridge = null
            clearBluetoothCommunicationRouteIfNeeded()
        }

        val bridge = AudioBridge(applicationContext)
        audioBridge = bridge

        val notification = buildNotification(getString(R.string.btn_tx), 0, callsign)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (callsign.isNotEmpty()) bridge.setTxCallsign(callsign)

        Log.i("AudioService", "startNetworkTransmitting: audioStreamConnected=${net.state.value.audioConnected} " +
            "mic=$inputDeviceId (if audio stream down, TX modulation won't reach the radio)")

        if (!bridge.startNetTx(inputDeviceId, yakumo2683.RADEdecode.network.IcomNetworkManager.NET_AUDIO_RATE)) {
            Log.e("AudioService", "startNetTx failed")
            bridge.release()
            audioBridge = null
            stopSelf()
            return
        }

        networkAudioMode = true
        _state.value = _state.value.copy(isTx = true, isRunning = false)
        startNetTxPump(bridge, net)
        startTxPolling()
        startNotificationUpdates()
    }

    /** Pull encoded modem audio every 20 ms and ship it to the radio over UDP. */
    private fun startNetTxPump(
        bridge: AudioBridge,
        net: yakumo2683.RADEdecode.network.IcomNetworkManager
    ) {
        val n = yakumo2683.RADEdecode.network.IcomNetworkManager.NET_AUDIO_FRAME_SAMPLES
        val done = java.util.concurrent.CountDownLatch(1)
        netTxPumpDone = done
        netTxPumpJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val frame = ShortArray(n)
                // Deadline-based pacing. The radio expects a steady 50 frames/sec
                // (one 20 ms frame). A plain delay(20) sleeps 20 ms PLUS the JNI+UDP
                // work each loop, so it under-delivers (~45 fps): the radio's audio
                // buffer starves every ~1-2 s (the reported dropouts) and the modem
                // sample stream is broken so the far end can't decode. Anchoring each
                // send to an absolute deadline keeps the long-term rate at exactly
                // 50 fps regardless of per-loop overhead.
                val periodNs = 20_000_000L  // 20 ms
                var nextDeadline = System.nanoTime() + periodNs
                // Keep pumping after TX stops until the ring is empty — the tail
                // is the EOO (callsign) frame queued by stopTx().
                while (isActive && (bridge.isTxRunning || bridge.nativeTxRingAvailable() > 0)) {
                    val got = bridge.fillNetTxFrame(frame, n)
                    if (got > 0) net.sendAudioFrame(frame)
                    val sleepNs = nextDeadline - System.nanoTime()
                    if (sleepNs > 0) {
                        delay(sleepNs / 1_000_000L)
                    } else {
                        // Fell behind (GC/scheduler hiccup) — resync so we don't fire
                        // a burst of catch-up frames that would overrun the radio.
                        nextDeadline = System.nanoTime()
                    }
                    nextDeadline += periodNs
                }
            } finally {
                done.countDown()
            }
        }
    }

    private fun stopNetTxPump() {
        netTxPumpJob?.cancel()
        netTxPumpJob = null
    }

    /* ── RX AudioTrack pump for explicit Bluetooth playback ─────── */

    private fun startRxAudioTrackPump(bridge: AudioBridge, outputDeviceId: Int) {
        stopRxAudioTrackPump()
        val sampleRate = 16000
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val outputDevice = getAudioDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == outputDeviceId }
        if (outputDevice != null) {
            val preferred = track.setPreferredDevice(outputDevice)
            Log.i(
                "AudioService",
                "RX AudioTrack: routing to ${outputDevice.productName} " +
                    "type=${outputDevice.type} id=$outputDeviceId preferred=$preferred"
            )
        } else {
            Log.w("AudioService", "RX AudioTrack: output device $outputDeviceId not found")
        }

        track.setVolume(rxOutputVolume)
        track.play()
        rxAudioTrack = track
        rxJavaOutputLevelDb = -100f

        // Route verification. setPreferredDevice() is a *request*, not a guarantee:
        // some OEMs refuse to move media to A2DP while a USB audio device is
        // attached (the user's YouTube symptom). Log what Android actually routed
        // to so we can tell a denied route from "no decoded speech yet".
        lifecycleScope.launch(Dispatchers.IO) {
            delay(300)
            logRxRoutedDevice(track, outputDeviceId, "300ms")
            delay(1200)
            logRxRoutedDevice(track, outputDeviceId, "1500ms")
        }

        rxPumpJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ShortArray(1600)
            try {
                while (isActive && bridge.isRunning && rxAudioTrack === track) {
                    val got = bridge.nativeReadRxRing(buf, buf.size)
                    if (got > 0) {
                        var rms = 0.0
                        for (i in 0 until got) {
                            val sample = buf[i].toFloat() / 32768.0f * rxOutputVolume
                            rms += sample * sample
                        }
                        rxJavaOutputLevelDb = (10.0 * kotlin.math.log10(rms / got + 1e-10)).toFloat()
                        track.write(buf, 0, got)
                    } else {
                        delay(10)
                    }
                }
            } catch (_: IllegalStateException) {
                // AudioTrack was released during stop/switch.
            }
        }
    }

    private fun stopRxAudioTrackPump() {
        rxPumpJob?.cancel()
        rxPumpJob = null
        try { rxAudioTrack?.stop() } catch (_: Exception) {}
        try { rxAudioTrack?.release() } catch (_: Exception) {}
        rxAudioTrack = null
        rxJavaOutputLevelDb = -100f
    }

    /** Log where Android actually routed the RX AudioTrack vs. what we requested. */
    private fun logRxRoutedDevice(track: AudioTrack, requestedId: Int, whenTag: String) {
        if (rxAudioTrack !== track) return
        val routed = try { track.routedDevice } catch (_: Exception) { null }
        val matched = routed != null && routed.id == requestedId
        Log.i(
            "AudioService",
            "RX route@$whenTag: requested id=$requestedId routed id=${routed?.id} " +
                "type=${routed?.type} name=${routed?.productName} matched=$matched"
        )
    }

    /* ── TX (Transmit) ──────────────────────────────────────── */

    fun startTransmitting(
        inputDeviceId: Int = -1,
        outputDeviceId: Int = -1,
        callsign: String = ""
    ) {
        if (_state.value.isTx) return

        // Stop RX immediately: mute output, stop bridge, finalize session
        // — but do NOT call stopForeground to keep foreground status during transition
        if (_state.value.isRunning) {
            stopPolling()
            stopNotificationUpdates()

            audioBridge?.setOutputVolume(0f)  // mute immediately to prevent feedback
            audioBridge?.stopRecording()
            stopRxAudioTrackPump()
            audioBridge?.stop()
            audioBridge?.release()
            audioBridge = null
            clearBluetoothCommunicationRouteIfNeeded()

            finalizeCurrentSession()
        }

        val bridge = AudioBridge(applicationContext)
        audioBridge = bridge

        // Start/update foreground service (no gap in foreground status)
        val notification = buildNotification(getString(R.string.btn_tx), 0, callsign)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (callsign.isNotEmpty()) {
            bridge.setTxCallsign(callsign)
        }

        Log.i("AudioService", "startTx: inputDeviceId=$inputDeviceId outputDeviceId=$outputDeviceId")
        if (!bridge.startTx(inputDeviceId, outputDeviceId)) {
            bridge.release()
            audioBridge = null
            stopSelf()
            return
        }

        _state.value = _state.value.copy(isTx = true, isRunning = false)

        // If native chose Java output (USB audio), start AudioTrack pump
        if (bridge.nativeIsTxUsingJavaOutput()) {
            startTxAudioTrackPump(bridge, outputDeviceId)
        }

        startTxPolling()
        startNotificationUpdates()
    }

    fun stopTransmitting() {
        stopTxPolling()
        stopNotificationUpdates()

        // Stop the mic/encoder and queue the EOO (callsign) frame into the TX
        // ring. On the Oboe output path this blocks until the ring has played
        // out; on the AudioTrack/network paths the pumps below drain it.
        audioBridge?.stopTx()

        // Wait for the pump to finish playing the EOO before tearing anything
        // down, so the callsign makes it over the air before PTT drops.
        awaitTxPumpDrained(txPumpDone)
        awaitTxPumpDrained(netTxPumpDone)
        txPumpDone = null
        netTxPumpDone = null

        stopTxAudioTrackPump()
        stopNetTxPump()
        networkAudioMode = false

        audioBridge?.release()
        audioBridge = null

        _state.value = ServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Block (bounded) until a TX pump has drained the ring and played it out. */
    private fun awaitTxPumpDrained(latch: java.util.concurrent.CountDownLatch?) {
        if (latch == null) return
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    /* ── TX AudioTrack pump for USB audio output ────────────── */

    private var txAudioTrack: AudioTrack? = null
    private var txPumpJob: Job? = null
    /** Counted down when the TX pump has drained + played out the EOO. */
    private var txPumpDone: java.util.concurrent.CountDownLatch? = null

    private fun startTxAudioTrackPump(bridge: yakumo2683.RADEdecode.AudioBridge, outputDeviceId: Int) {
        val sampleRate = 8000  // modem rate
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1600)  // at least 100ms

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Route to the USB audio device
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val usbDev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == outputDeviceId }
        if (usbDev != null) {
            track.setPreferredDevice(usbDev)
            Log.i("AudioService", "TX AudioTrack: routing to ${usbDev.productName} (id=$outputDeviceId)")
        } else {
            Log.w("AudioService", "TX AudioTrack: USB device $outputDeviceId not found, using default")
        }

        // User-adjustable TX level. Default 20% — enough for rigs with typical
        // USB MOD gain (e.g. IC-7300 at default ~50%) to push ALC into the
        // target region; hotter rigs (IC-9700) may need lower, quieter cables
        // may need higher. See SettingsScreen "TX Output" slider.
        track.setVolume(txVolume)
        track.play()
        txAudioTrack = track

        // Pump: read from native ring buffer → write to AudioTrack
        val done = java.util.concurrent.CountDownLatch(1)
        txPumpDone = done
        txPumpJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ShortArray(800)  // 100ms at 8kHz
            var framesWritten = 0L
            try {
                while (isActive && bridge.isTxRunning) {
                    val got = bridge.nativeReadTxRing(buf, buf.size)
                    if (got > 0) {
                        track.write(buf, 0, got)
                        framesWritten += got
                    } else {
                        delay(10)
                    }
                }
                // TX stopped: what's left in the ring is the EOO (callsign)
                // frame queued by stopTx(). Drain it, then wait until the
                // track has actually played it out before signalling done —
                // stopTransmitting() unkeys nothing until then.
                var remaining = bridge.nativeReadTxRing(buf, buf.size)
                while (isActive && remaining > 0) {
                    track.write(buf, 0, remaining)
                    framesWritten += remaining
                    remaining = bridge.nativeReadTxRing(buf, buf.size)
                }
                var waitedMs = 0
                while (isActive && waitedMs < 1500 &&
                    track.playbackHeadPosition.toLong() < framesWritten
                ) {
                    delay(20)
                    waitedMs += 20
                }
            } catch (_: IllegalStateException) {
                // AudioTrack was released — normal during stop
            } finally {
                done.countDown()
            }
        }
    }

    private fun stopTxAudioTrackPump() {
        txPumpJob?.cancel()
        txPumpJob = null
        try { txAudioTrack?.stop() } catch (_: Exception) {}
        try { txAudioTrack?.release() } catch (_: Exception) {}
        txAudioTrack = null
    }

    private var txPollingJob: Job? = null

    private fun startTxPolling() {
        txPollingJob = lifecycleScope.launch {
            while (isActive) {
                val bridge = audioBridge ?: break
                _state.value = _state.value.copy(
                    txLevelDb = bridge.txLevel
                )
                delay(100)
            }
        }
    }

    private fun stopTxPolling() {
        txPollingJob?.cancel()
        txPollingJob = null
    }

    /* ── Session management ──────────────────────────────────── */

    private fun startNewSession(deviceId: Int) {
        // Finalize previous session if exists
        finalizeCurrentSession()

        sessionStartTime = System.currentTimeMillis()
        totalModemFrames = 0
        syncedFrames = 0
        lastSyncState = 0
        lastSyncedTime = 0

        // Start new WAV recording
        audioBridge?.let { bridge ->
            bridge.stopRecording()
            val dir = java.io.File(applicationContext.filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            val wavPath = java.io.File(dir, "session_${System.currentTimeMillis()}.wav").absolutePath
            bridge.startRecording(wavPath)
            currentWavPath = wavPath
        }

        val session = ReceptionSession(
            startTime = sessionStartTime,
            audioDevice = "device_$deviceId",
            sampleRateHz = 8000
        )

        // Insert synchronously on a background thread and wait for the ID
        val latch = java.util.concurrent.CountDownLatch(1)
        lifecycleScope.launch(Dispatchers.IO) {
            val id = db?.insertSession(session) ?: 0
            currentSession = session.copy(id = id)
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Finalize the current session to DB (endTime + frame counts). */
    private fun finalizeCurrentSession() {
        val session = currentSession ?: return
        val endTime = System.currentTimeMillis()
        val finalTotalFrames = totalModemFrames
        val finalSyncedFrames = syncedFrames
        val wavPath = currentWavPath

        val latch = java.util.concurrent.CountDownLatch(1)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (wavPath != null) {
                    val wavFile = java.io.File(wavPath)
                    if (wavFile.exists() && wavFile.length() > 44) {
                        db?.updateSessionAudio(session.id, wavFile.name, wavFile.length())
                    } else if (wavFile.exists()) {
                        wavFile.delete()
                    }
                }
                db?.updateSessionEnd(
                    sessionId = session.id,
                    endTime = endTime,
                    totalFrames = finalTotalFrames,
                    syncedFrames = finalSyncedFrames
                )
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        currentSession = null
        currentWavPath = null
    }

    private fun handleSyncChange(newState: Int) {
        // Always update UI state regardless of session (atomic update)
        _state.update { it.copy(syncState = newState) }

        // Log to DB only if we have an active session
        val session = currentSession ?: return
        if (newState != lastSyncState) {
            val offsetMs = System.currentTimeMillis() - sessionStartTime
            val currentState = _state.value
            val event = SyncEvent(
                sessionId = session.id,
                offsetMs = offsetMs,
                fromState = lastSyncState,
                toState = newState,
                snrAtEvent = currentState.snrDb,
                freqOffsetAtEvent = currentState.freqOffsetHz
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db?.insertSyncEvent(event)
            }
            lastSyncState = newState
        }
    }

    private fun handleCallsignDecoded(callsign: String) {
        val session = currentSession ?: return
        val offsetMs = System.currentTimeMillis() - sessionStartTime

        val event = CallsignEvent(
            sessionId = session.id,
            offsetMs = offsetMs,
            callsign = callsign,
            snrAtDecode = _state.value.snrDb,
            modemFrame = totalModemFrames
        )

        lifecycleScope.launch(Dispatchers.IO) {
            db?.insertCallsignEvent(event)
        }

        _state.update { it.copy(lastCallsign = callsign) }
        updateNotification()

        // Report to FreeDV Reporter network
        reporter?.reportRx(callsign, _state.value.snrDb)
    }

    /* ── Polling ─────────────────────────────────────────────── */

    private fun startPolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val bridge = audioBridge ?: break
                // Power-save mode: skip the native FFT entirely — it's the most
                // expensive per-poll work and the UI hides the spectrum anyway.
                val saving = powerSaveMode
                if (!saving) {
                    bridge.getSpectrum(spectrumBuffer)
                }

                val now = System.currentTimeMillis()
                val isSynced = _state.value.syncState == 2

                totalModemFrames++
                if (isSynced) {
                    syncedFrames++
                    lastSyncedTime = now
                }

                // Session splitting: finalize if sync lost > 2 seconds
                if (currentSession != null && lastSyncedTime > 0 && !isSynced &&
                    now - lastSyncedTime > SYNC_LOST_TIMEOUT_MS) {
                    finalizeCurrentSession()
                    lastSyncedTime = 0
                }

                // Start new session when sync regained after a split
                if (currentSession == null && isSynced) {
                    startNewSession(currentInputDeviceId)
                }

                // Log signal snapshot every ~1 second (every 7th poll at 150ms)
                if (totalModemFrames % 7 == 0 && currentSession != null) {
                    logSignalSnapshot()
                }

                // Atomic update — syncState is set by callback only;
                // polling updates other fields without clobbering it
                val snr = bridge.snrEstimate
                val freq = bridge.freqOffset
                val inLvl = bridge.inputLevel
                val outLvl = if (rxAudioTrack != null) rxJavaOutputLevelDb else bridge.outputLevel
                val cs = bridge.lastCallsign
                val spec = if (saving) FLAT_SPECTRUM else spectrumBuffer.copyOf()
                val rejected = bridge.isUnprocessedRejected
                _state.update { it.copy(
                    snrDb = snr,
                    freqOffsetHz = freq,
                    inputLevelDb = inLvl,
                    outputLevelDb = outLvl,
                    lastCallsign = cs,
                    spectrum = spec,
                    unprocessedRejected = rejected
                ) }

                // Heartbeat rx_report while we're in sync. qso.freedv.org's
                // "currently receiving / blue dot" indicator is driven purely
                // by the recency of rx_report emits — without periodic empty-
                // callsign reports we'd only show as "receiving" for a moment
                // when an EOO callsign is actually decoded (= once per over).
                if (isSynced && now - lastRxReportMs >= 2000) {
                    reporter?.reportRx("", snr)
                    lastRxReportMs = now
                }

                // Periodically update DB with current WAV size (~every 3 seconds)
                if (totalModemFrames % 20 == 0) {
                    val path = currentWavPath
                    val session = currentSession
                    if (path != null && session != null) {
                        val wavFile = java.io.File(path)
                        if (wavFile.exists() && wavFile.length() > 44) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                db?.updateSessionAudio(session.id, wavFile.name, wavFile.length())
                            }
                        }
                    }
                }

                delay(80)  // ~12 FPS for smoother waterfall
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun logSignalSnapshot() {
        val session = currentSession ?: return
        val bridge = audioBridge ?: return
        val offsetMs = System.currentTimeMillis() - sessionStartTime

        val snapshot = SignalSnapshot(
            sessionId = session.id,
            offsetMs = offsetMs,
            snr = bridge.snrEstimate.toFloat(),
            freqOffset = bridge.freqOffset,
            syncState = bridge.syncState,
            inputLevelDb = bridge.inputLevel,
            outputLevelDb = if (rxAudioTrack != null) rxJavaOutputLevelDb else bridge.outputLevel
        )

        lifecycleScope.launch(Dispatchers.IO) {
            db?.insertSnapshot(snapshot)
        }
    }

    /* ── Notification ────────────────────────────────────────── */

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(syncText: String, snr: Int, callsign: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (callsign.isNotEmpty()) getString(R.string.notification_title_with_callsign, callsign)
                    else getString(R.string.notification_title_default)
        val text = getString(R.string.notification_text, syncText, snr)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val s = _state.value
        val syncText = if (s.isTx) {
            getString(R.string.transmitting)
        } else {
            when (s.syncState) {
                2 -> getString(R.string.sync_sync)
                1 -> getString(R.string.sync_candidate)
                else -> getString(R.string.sync_search)
            }
        }
        val notification = buildNotification(syncText, s.snrDb, s.lastCallsign)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateNotification()
                delay(1000) // Update notification every 1 second
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    override fun onDestroy() {
        if (_state.value.isTx) stopTransmitting()
        else stopDecoding()
        super.onDestroy()
    }
}

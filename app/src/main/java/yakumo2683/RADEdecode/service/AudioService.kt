package yakumo2683.RADEdecode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private var audioBridge: AudioBridge? = null
    private var pollingJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var db: AppDatabase? = null
    private var wavRecorder: WavRecorder? = null

    // Current session tracking
    private var currentWavPath: String? = null
    private var currentSession: ReceptionSession? = null
    private var sessionStartTime: Long = 0
    private var lastSyncState: Int = 0
    private var totalModemFrames: Int = 0
    private var syncedFrames: Int = 0
    private var currentInputDeviceId: Int = -1

    // Session splitting: finalize session when sync lost > 2 seconds
    private var syncLostTime: Long = 0          // timestamp when sync was lost (0 = not lost)
    private var sessionSplitJob: Job? = null
    companion object {
        const val CHANNEL_ID = "rade_decode_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "yakumo2683.RADEdecode.STOP"
        private const val SYNC_LOST_TIMEOUT_MS = 2000L
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
        val spectrum: FloatArray = FloatArray(AudioBridge.SPECTRUM_BINS) { -100f }
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

    fun startDecoding(inputDeviceId: Int = -1, recordWav: Boolean = false) {
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

        if (!bridge.start(inputDeviceId)) {
            stopSelf()
            return
        }

        // Start a new reception session (also starts WAV recording)
        currentInputDeviceId = inputDeviceId
        startNewSession(inputDeviceId)

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
        sessionSplitJob?.cancel()
        sessionSplitJob = null

        // Stop native WAV recording
        audioBridge?.stopRecording()

        audioBridge?.stop()
        audioBridge?.release()
        audioBridge = null

        // Finalize current session to DB
        finalizeCurrentSession()

        _state.value = ServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun setOutputVolume(volume: Float) {
        audioBridge?.setOutputVolume(volume)
    }

    fun getInputDevices(): List<AudioBridge.AudioDevice> {
        return audioBridge?.getInputDevices() ?: emptyList()
    }

    fun getOutputDevices(): List<AudioBridge.AudioDevice> {
        return audioBridge?.getOutputDevices() ?: emptyList()
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
            sessionSplitJob?.cancel()
            sessionSplitJob = null

            audioBridge?.setOutputVolume(0f)  // mute immediately to prevent feedback
            audioBridge?.stopRecording()
            audioBridge?.stop()
            audioBridge?.release()
            audioBridge = null

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

        if (!bridge.startTx(inputDeviceId, outputDeviceId)) {
            bridge.release()
            audioBridge = null
            stopSelf()
            return
        }

        _state.value = _state.value.copy(isTx = true, isRunning = false)
        startTxPolling()
        startNotificationUpdates()
    }

    fun stopTransmitting() {
        stopTxPolling()
        stopNotificationUpdates()

        audioBridge?.stopTx()
        audioBridge?.release()
        audioBridge = null

        _state.value = ServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        syncLostTime = 0

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

        // Session splitting: track sync loss duration
        if (newState == 2) {
            // Sync regained — cancel any pending split
            syncLostTime = 0
            sessionSplitJob?.cancel()
            sessionSplitJob = null

            // If no active session, start a new one (sync regained after split)
            if (currentSession == null && _state.value.isRunning) {
                startNewSession(currentInputDeviceId)
            }
        } else if (newState == 0 && syncLostTime == 0L && currentSession != null) {
            // Sync just lost — start timeout
            syncLostTime = System.currentTimeMillis()
            sessionSplitJob = lifecycleScope.launch {
                delay(SYNC_LOST_TIMEOUT_MS)
                // Still no sync after timeout → finalize session
                if (syncLostTime > 0 && currentSession != null) {
                    finalizeCurrentSession()
                    syncLostTime = 0
                }
            }
        }

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
    }

    /* ── Polling ─────────────────────────────────────────────── */

    private fun startPolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val bridge = audioBridge ?: break
                bridge.getSpectrum(spectrumBuffer)

                totalModemFrames++
                if (_state.value.syncState == 2) syncedFrames++

                // Log signal snapshot every ~1 second (every 7th poll at 150ms)
                if (totalModemFrames % 7 == 0) {
                    logSignalSnapshot()
                }

                // Atomic update — syncState is set by callback only;
                // polling updates other fields without clobbering it
                val snr = bridge.snrEstimate
                val freq = bridge.freqOffset
                val inLvl = bridge.inputLevel
                val outLvl = bridge.outputLevel
                val cs = bridge.lastCallsign
                val spec = spectrumBuffer.copyOf()
                _state.update { it.copy(
                    snrDb = snr,
                    freqOffsetHz = freq,
                    inputLevelDb = inLvl,
                    outputLevelDb = outLvl,
                    lastCallsign = cs,
                    spectrum = spec
                ) }

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

                delay(150)
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
            outputLevelDb = bridge.outputLevel
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

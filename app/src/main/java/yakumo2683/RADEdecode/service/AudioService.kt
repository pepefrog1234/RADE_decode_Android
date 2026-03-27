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

    companion object {
        const val CHANNEL_ID = "rade_decode_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "yakumo2683.RADEdecode.STOP"
    }

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

    data class ServiceState(
        val isRunning: Boolean = false,
        val syncState: Int = 0,
        val snrDb: Int = 0,
        val freqOffsetHz: Float = 0f,
        val inputLevelDb: Float = -100f,
        val outputLevelDb: Float = -100f,
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
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopDecoding()
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
        val notification = buildNotification("Starting...", 0, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!bridge.start(inputDeviceId)) {
            stopSelf()
            return
        }

        // Start a new reception session
        startNewSession(inputDeviceId)

        // Always record decoded audio to WAV (native C++ recorder)
        val dir = java.io.File(applicationContext.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        val wavPath = java.io.File(dir, "session_${System.currentTimeMillis()}.wav").absolutePath
        bridge.startRecording(wavPath)
        currentWavPath = wavPath

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

        // Stop native WAV recording
        audioBridge?.stopRecording()

        audioBridge?.stop()
        audioBridge?.release()
        audioBridge = null

        // Update session with WAV file info + finalize (synchronous)
        val wavPath = currentWavPath
        val session = currentSession
        val latch = java.util.concurrent.CountDownLatch(1)
        lifecycleScope.launch(Dispatchers.IO) {
            if (session != null) {
                // Update WAV info
                if (wavPath != null) {
                    val wavFile = java.io.File(wavPath)
                    if (wavFile.exists() && wavFile.length() > 44) {
                        db?.updateSessionAudio(session.id, wavFile.name, wavFile.length())
                    } else if (wavFile.exists()) {
                        wavFile.delete()
                    }
                }
                // Finalize session end time
                db?.updateSessionEnd(
                    sessionId = session.id,
                    endTime = System.currentTimeMillis(),
                    totalFrames = totalModemFrames,
                    syncedFrames = syncedFrames
                )
            }
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)

        currentWavPath = null
        currentSession = null

        _state.value = ServiceState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun setOutputVolume(volume: Float) {
        audioBridge?.setOutputVolume(volume)
    }

    fun getInputDevices(): List<AudioBridge.AudioDevice> {
        return audioBridge?.getInputDevices() ?: emptyList()
    }

    /* ── Session management ──────────────────────────────────── */

    private fun startNewSession(deviceId: Int) {
        sessionStartTime = System.currentTimeMillis()
        totalModemFrames = 0
        syncedFrames = 0
        lastSyncState = 0

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

    private fun handleSyncChange(newState: Int) {
        val session = currentSession ?: return
        val offsetMs = System.currentTimeMillis() - sessionStartTime

        if (newState != lastSyncState) {
            val event = SyncEvent(
                sessionId = session.id,
                offsetMs = offsetMs,
                fromState = lastSyncState,
                toState = newState,
                snrAtEvent = _state.value.snrDb,
                freqOffsetAtEvent = _state.value.freqOffsetHz
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db?.insertSyncEvent(event)
            }
            lastSyncState = newState
        }

        _state.value = _state.value.copy(syncState = newState)
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

        _state.value = _state.value.copy(lastCallsign = callsign)
        updateNotification()
    }

    /* ── Polling ─────────────────────────────────────────────── */

    private fun startPolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val bridge = audioBridge ?: break
                bridge.getSpectrum(spectrumBuffer)

                val syncState = bridge.syncState
                totalModemFrames++
                if (syncState == 2) syncedFrames++

                // Log signal snapshot every ~1 second (every 7th poll at 150ms)
                if (totalModemFrames % 7 == 0) {
                    logSignalSnapshot()
                }

                _state.value = _state.value.copy(
                    syncState = syncState,
                    snrDb = bridge.snrEstimate,
                    freqOffsetHz = bridge.freqOffset,
                    inputLevelDb = bridge.inputLevel,
                    outputLevelDb = bridge.outputLevel,
                    lastCallsign = bridge.lastCallsign,
                    spectrum = spectrumBuffer.copyOf()
                )

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
            "RADE Decode",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background audio decoding status"
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

        val title = if (callsign.isNotEmpty()) "RADE: $callsign" else "RADE Decode"
        val text = "$syncText | SNR: ${snr}dB"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val s = _state.value
        val syncText = when (s.syncState) {
            2 -> "SYNC"
            1 -> "CANDIDATE"
            else -> "SEARCH"
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
        stopDecoding()
        super.onDestroy()
    }
}

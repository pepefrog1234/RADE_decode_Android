package yakumo2683.RADEdecode

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Kotlin bridge to the native AudioEngine (C++ / Oboe).
 *
 * Manages the full audio pipeline:
 *   USB input → 48kHz→8kHz → RADE modem → FARGAN vocoder → speaker output
 *
 * Mirrors iOS AudioManager.swift functionality.
 */
class AudioBridge(private val context: Context) {

    /** Callback interface for audio engine events. */
    interface Callback {
        fun onSyncStateChanged(state: Int)
        fun onCallsignDecoded(callsign: String)
    }

    var callback: Callback? = null

    /** Inner callback object passed to JNI. Must match JNI method signatures. */
    private val jniCallback = object {
        @Suppress("unused") // Called from JNI
        fun onSyncStateChanged(state: Int) {
            callback?.onSyncStateChanged(state)
        }

        @Suppress("unused") // Called from JNI
        fun onCallsignDecoded(callsign: String) {
            callback?.onCallsignDecoded(callsign)
        }
    }

    init {
        nativeCreate()
        nativeSetCallback(jniCallback)
    }

    /** Start the audio engine with the specified input device.
     *  @param inputDeviceId Android AudioDeviceInfo ID, or -1 for default */
    fun start(inputDeviceId: Int = -1): Boolean = nativeStart(inputDeviceId)

    /** Stop the audio engine. */
    fun stop() = nativeStop()

    /** Check if audio is currently running. */
    val isRunning: Boolean get() = nativeIsRunning()

    /** Switch to a different input device. */
    fun setInputDevice(deviceId: Int) = nativeSetInputDevice(deviceId)

    /** Set output volume (0.0 to 1.0). */
    fun setOutputVolume(volume: Float) = nativeSetOutputVolume(volume)

    /** Set digital input gain (1.0 = unity, higher = boost weak signals). */
    fun setInputGain(gain: Float) = nativeSetInputGain(gain)

    /** Get current input gain. */
    val inputGain: Float get() = nativeGetInputGain()

    /** Current sync state: 0=SEARCH, 1=CANDIDATE, 2=SYNC. */
    val syncState: Int get() = nativeGetSyncState()

    /** Estimated SNR in dB (3kHz bandwidth). */
    val snrEstimate: Int get() = nativeGetSnrEstimate()

    /** Current frequency offset in Hz. */
    val freqOffset: Float get() = nativeGetFreqOffset()

    /** Input audio level in dB (RMS). */
    val inputLevel: Float get() = nativeGetInputLevel()

    /** Output audio level in dB (RMS). */
    val outputLevel: Float get() = nativeGetOutputLevel()

    /** Last decoded callsign, or empty string. */
    val lastCallsign: String get() = nativeGetLastCallsign()

    /** Copy current FFT spectrum (512 bins, dB scale, 0-4kHz). */
    fun getSpectrum(out: FloatArray) = nativeGetSpectrum(out)

    /** Start recording decoded speech to WAV file. */
    fun startRecording(path: String): Boolean = nativeStartRecording(path)

    /** Stop recording. */
    fun stopRecording() = nativeStopRecording()

    /** Release native resources. Call when done. */
    fun release() {
        stop()
        nativeDestroy()
    }

    /* ── USB Audio Device Discovery ──────────────────────────── */

    data class AudioDevice(
        val id: Int,
        val name: String,
        val type: Int,
        val isUsb: Boolean
    )

    /** List available audio input devices. */
    fun getInputDevices(): List<AudioDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { info ->
            AudioDevice(
                id = info.id,
                name = info.productName?.toString() ?: "Unknown",
                type = info.type,
                isUsb = info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                        info.type == AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
    }

    /** Find the first USB audio input device, or null. */
    fun findUsbInputDevice(): AudioDevice? {
        return getInputDevices().firstOrNull { it.isUsb }
    }

    /* ── Native methods ──────────────────────────────────────── */

    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetInputGain(gain: Float)
    private external fun nativeGetInputGain(): Float
    private external fun nativeStartRecording(path: String): Boolean
    private external fun nativeStopRecording()
    private external fun nativeSetCallback(callback: Any)
    private external fun nativeStart(inputDeviceId: Int): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetInputDevice(deviceId: Int)
    private external fun nativeSetOutputVolume(volume: Float)
    private external fun nativeGetSyncState(): Int
    private external fun nativeGetSnrEstimate(): Int
    private external fun nativeGetFreqOffset(): Float
    private external fun nativeGetInputLevel(): Float
    private external fun nativeGetOutputLevel(): Float
    private external fun nativeGetSpectrum(out: FloatArray)
    private external fun nativeGetLastCallsign(): String

    companion object {
        init {
            System.loadLibrary("rade_jni")
        }

        /** Spectrum bin count (FFT_SIZE / 2). */
        const val SPECTRUM_BINS = 512

        /** Sync states. */
        const val SYNC_SEARCH = 0
        const val SYNC_CANDIDATE = 1
        const val SYNC_SYNCED = 2
    }
}

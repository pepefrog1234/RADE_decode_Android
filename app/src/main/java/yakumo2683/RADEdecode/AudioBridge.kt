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

    /** True if the device did not honor the Unprocessed input preset. */
    val isUnprocessedRejected: Boolean get() = nativeIsUnprocessedRejected()

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
        stopTx()
        nativeDestroy()
    }

    /* ── TX (Transmit) ───────────────────────────────────────── */

    /** Start transmitting: mic → RADE encoder → output device. */
    fun startTx(inputDeviceId: Int = -1, outputDeviceId: Int = -1): Boolean =
        nativeStartTx(inputDeviceId, outputDeviceId)

    /** Stop transmitting (sends EOO frame, then stops). */
    fun stopTx() = nativeStopTx()

    /** Check if TX is currently running. */
    val isTxRunning: Boolean get() = nativeIsTxRunning()

    /** Set the callsign to embed in the EOO frame. */
    fun setTxCallsign(callsign: String) = nativeSetTxCallsign(callsign)

    /** TX microphone input level in dB (RMS). */
    val txLevel: Float get() = nativeGetTxLevel()

    /** Set the output device for TX. */
    fun setTxOutputDevice(deviceId: Int) = nativeSetTxOutputDevice(deviceId)

    /* ── USB Audio Device Discovery ──────────────────────────── */

    data class AudioDevice(
        val id: Int,
        val name: String,
        val type: Int,
        val typeName: String,
        val isUsb: Boolean
    )

    /** List available audio input devices, deduplicated by type. */
    fun getInputDevices(): List<AudioDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .groupBy { it.type }
            .map { (_, devices) -> devices.first() }  // one per type
            .map { info ->
                AudioDevice(
                    id = info.id,
                    name = info.productName?.toString() ?: "Unknown",
                    type = info.type,
                    typeName = deviceTypeName(info.type),
                    isUsb = info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                            info.type == AudioDeviceInfo.TYPE_USB_HEADSET
                )
            }
            .sortedByDescending { it.isUsb }  // USB devices first
    }

    /** Find the first USB audio input device, or null. */
    fun findUsbInputDevice(): AudioDevice? {
        return getInputDevices().firstOrNull { it.isUsb }
    }

    /** List available audio output devices, deduplicated by type. */
    fun getOutputDevices(): List<AudioDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .groupBy { it.type }
            .map { (_, devices) -> devices.first() }
            .map { info ->
                AudioDevice(
                    id = info.id,
                    name = info.productName?.toString() ?: "Unknown",
                    type = info.type,
                    typeName = deviceTypeName(info.type),
                    isUsb = info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                            info.type == AudioDeviceInfo.TYPE_USB_HEADSET
                )
            }
            .sortedByDescending { it.isUsb }
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
    private external fun nativeIsUnprocessedRejected(): Boolean
    private external fun nativeGetInputLevel(): Float
    private external fun nativeGetOutputLevel(): Float
    private external fun nativeGetSpectrum(out: FloatArray)
    private external fun nativeGetLastCallsign(): String

    /* TX native methods */
    private external fun nativeStartTx(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun nativeStopTx()
    private external fun nativeIsTxRunning(): Boolean
    private external fun nativeSetTxCallsign(callsign: String)
    private external fun nativeGetTxLevel(): Float
    private external fun nativeSetTxOutputDevice(deviceId: Int)

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        else -> "Type $type"
    }

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

package yakumo2683.RADEdecode

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

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

    /**
     * Start the audio engine with the specified devices.
     * @param inputDeviceId  Android AudioDeviceInfo ID for capture, or -1 for default.
     * @param outputDeviceId Android AudioDeviceInfo ID for playback, or -1 for default.
     *   Setting this is important when a USB audio device is connected — by default
     *   Android routes Media output to USB, which sends decoded speech back into the
     *   rig instead of the phone's speaker. Callers should typically pass the
     *   built-in speaker here (see [findBuiltInSpeaker]); wired headsets and BT
     *   still override via Android's routing policy.
     */
    fun start(inputDeviceId: Int = -1, outputDeviceId: Int = -1): Boolean =
        nativeStart(inputDeviceId, outputDeviceId)

    /** Stop the audio engine. */
    fun stop() {
        releaseInputEffects()
        nativeStop()
    }

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

    /** Audio session id of the active input stream, or -1 when not running. */
    val inputSessionId: Int get() = nativeGetInputSessionId()

    /* Effects attached to the input session id — held so they stay active for
     * the stream's lifetime. Released by [stop] or [release]. */
    private val heldInputEffects = mutableListOf<AudioEffect>()

    data class InputEffectsReport(
        val sessionId: Int,
        val aecDisabled: Boolean,
        val nsDisabled: Boolean,
        val agcDisabled: Boolean
    )

    /**
     * Attach and disable all platform audio effects on the active input session.
     *
     * Motivation: some OEMs (confirmed on Samsung Galaxy S24 with One UI 6) apply
     * AGC / noise suppression / voice isolation at the HAL layer even when the
     * app requests `InputPreset::Unprocessed`. The only reliable way to stop
     * this from app code is to create the effect control objects on the stream's
     * session id and explicitly disable them — which is what this does.
     *
     * Must be called AFTER [start] returns true. The effect objects are held
     * alive until [stop] / [release] so the "disabled" state stays latched.
     */
    fun disableInputEffects(): InputEffectsReport {
        releaseInputEffects()
        val sid = inputSessionId
        if (sid <= 0) {
            Log.w("AudioBridge", "disableInputEffects: no session id ($sid)")
            return InputEffectsReport(sid, false, false, false)
        }
        val aecOff = tryDisable("AEC", AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sid)
        }
        val nsOff = tryDisable("NS", NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sid)
        }
        val agcOff = tryDisable("AGC", AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sid)
        }
        Log.i("AudioBridge", "disableInputEffects: session=$sid AEC=$aecOff NS=$nsOff AGC=$agcOff")
        return InputEffectsReport(sid, aecOff, nsOff, agcOff)
    }

    private fun tryDisable(tag: String, available: Boolean, factory: () -> AudioEffect?): Boolean {
        if (!available) {
            Log.d("AudioBridge", "$tag not available on this device")
            return false
        }
        return try {
            val fx = factory() ?: return false.also {
                Log.w("AudioBridge", "$tag.create returned null")
            }
            fx.enabled = false
            heldInputEffects.add(fx)
            !fx.enabled
        } catch (t: Throwable) {
            Log.w("AudioBridge", "$tag disable failed: ${t.message}")
            false
        }
    }

    private fun releaseInputEffects() {
        heldInputEffects.forEach { fx ->
            try { fx.release() } catch (_: Throwable) {}
        }
        heldInputEffects.clear()
    }

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

    /** Find the built-in microphone device, or null. */
    fun findBuiltInMic(): AudioDevice? {
        return getInputDevices().firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
    }

    /**
     * Find the built-in loudspeaker, or null.
     * Passed to [start] as the RX output device to keep decoded speech out of
     * any connected USB audio device (the rig's audio input).
     */
    fun findBuiltInSpeaker(): AudioDevice? {
        return getOutputDevices().firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
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
    private external fun nativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetInputDevice(deviceId: Int)
    private external fun nativeSetOutputVolume(volume: Float)
    private external fun nativeGetSyncState(): Int
    private external fun nativeGetSnrEstimate(): Int
    private external fun nativeGetFreqOffset(): Float
    private external fun nativeIsUnprocessedRejected(): Boolean
    private external fun nativeGetInputSessionId(): Int
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
    external fun nativeReadTxRing(outBuf: ShortArray, maxSamples: Int): Int
    external fun nativeIsTxUsingJavaOutput(): Boolean

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

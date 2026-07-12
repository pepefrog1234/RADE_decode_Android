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
     */
    fun start(
        inputDeviceId: Int = -1,
        outputDeviceId: Int = -1,
        voiceCommunicationOutput: Boolean = false,
        bleAudioOutput: Boolean = false
    ): Boolean =
        nativeStart(inputDeviceId, outputDeviceId, voiceCommunicationOutput, bleAudioOutput)

    /** Stop the audio engine. */
    fun stop() {
        releaseInputEffects()
        nativeStop()
    }

    /** Check if audio is currently running. */
    val isRunning: Boolean get() = nativeIsRunning()

    /** Switch to a different input device. */
    fun setInputDevice(deviceId: Int) = nativeSetInputDevice(deviceId)

    /** Switch decoded RX playback to a different output device. */
    fun setOutputDevice(deviceId: Int) = nativeSetOutputDevice(deviceId)

    /** Use Kotlin/AudioTrack for RX playback instead of native Oboe output. */
    fun setRxJavaOutputEnabled(enabled: Boolean) = nativeSetRxJavaOutputEnabled(enabled)

    /** Route native RX playback as call audio instead of media audio. */
    fun setRxVoiceCommunicationOutputEnabled(enabled: Boolean) =
        nativeSetRxVoiceCommunicationOutputEnabled(enabled)

    /** Switch RX input and decoded-speech output together. */
    fun setDevices(inputDeviceId: Int, outputDeviceId: Int) =
        nativeSetDevices(inputDeviceId, outputDeviceId)

    /** Set output volume (0.0 to 1.0). */
    fun setOutputVolume(volume: Float) = nativeSetOutputVolume(volume)

    /** Set digital input gain (1.0 = unity, higher = boost weak signals). */
    fun setInputGain(gain: Float) = nativeSetInputGain(gain)

    /** TX mic gain: boosts the mic samples before RADE feature extraction so the
     *  far-end decoded speech isn't under-modulated (Android mics run quiet). */
    fun setTxMicGain(gain: Float) = nativeSetTxMicGain(gain)

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
        stopTx(drainEoo = false)
        nativeDestroy()
    }

    /* ── TX (Transmit) ───────────────────────────────────────── */

    /** Start transmitting: mic → RADE encoder → output device. When [keepRxAlive]
     *  is true (local LC3 monitoring, no rig) the RX output stream is left open and
     *  TX captures the mic for the level meter only — no modem, no output. When
     *  [voiceCommunicationInput] is true the mic is captured through the active
     *  Bluetooth communication route (LE Audio / LC3 headset) with the
     *  VOICE_COMMUNICATION preset instead of a pinned device id. When
     *  [voiceRecognitionInput] is true (an LC3 headset is connected but is NOT
     *  the TX mic) the mic uses the VOICE_RECOGNITION preset so the mic-open
     *  doesn't disturb the headset's LC3 group (multi-second stall / silence). */
    fun startTx(
        inputDeviceId: Int = -1,
        outputDeviceId: Int = -1,
        keepRxAlive: Boolean = false,
        voiceCommunicationInput: Boolean = false,
        voiceRecognitionInput: Boolean = false
    ): Boolean =
        nativeStartTx(
            inputDeviceId, outputDeviceId, keepRxAlive,
            voiceCommunicationInput, voiceRecognitionInput
        )

    /** Pause RX decoding (close the mic) but keep the RX output stream — and its
     *  Bluetooth LE Audio / LC3 media route — open. Pair with [resumeRxInput]. */
    fun pauseRxInput(): Boolean = nativePauseRxInput()

    /** Resume RX decoding after [pauseRxInput] (reopens the mic input stream). */
    fun resumeRxInput(): Boolean = nativeResumeRxInput()

    /** Stop transmitting (sends EOO frame, then stops). When [drainEoo] is false
     *  (no rig / local monitoring) the EOO drain + output tail wait are skipped. */
    fun stopTx(drainEoo: Boolean = true) = nativeStopTx(drainEoo)

    /** Check if TX is currently running. */
    val isTxRunning: Boolean get() = nativeIsTxRunning()

    /** Set the callsign to embed in the EOO frame. */
    fun setTxCallsign(callsign: String) = nativeSetTxCallsign(callsign)

    /** TX microphone input level in dB (RMS). */
    val txLevel: Float get() = nativeGetTxLevel()

    /** Set the output device for TX. */
    fun setTxOutputDevice(deviceId: Int) = nativeSetTxOutputDevice(deviceId)

    /* ── Network audio (Icom RS-BA1 / IC-705 Wi-Fi) ──────────────
     * Same DSP as the USB path; the rig-facing audio rides UDP 50003 via
     * IcomNetworkManager rather than an Oboe USB stream. RX: feed received
     * PCM with [feedNetRx]; decoded speech still plays on the phone speaker
     * via the normal Oboe output. TX: read encoded modem frames with
     * [fillNetTxFrame] and UDP-send them. */

    /** Start network RX: decode UDP audio (netRate int16 mono) → phone speaker. */
    fun startNetRx(outputDeviceId: Int = -1, netRate: Int = 48000): Boolean =
        nativeStartNetRx(outputDeviceId, netRate)

    /** Push a received network audio chunk into the RX modem pipeline. */
    fun feedNetRx(pcm: ShortArray, count: Int) = nativeFeedNetRx(pcm, count)

    /** Start network TX: mic → RADE encoder → modem PCM for UDP send.
     *  [voiceCommunicationInput] uses the VoiceCommunication capture preset —
     *  required for a Bluetooth LE Audio (LC3) comm-route mic. */
    fun startNetTx(
        inputDeviceId: Int = -1,
        netRate: Int = 48000,
        voiceCommunicationInput: Boolean = false
    ): Boolean = nativeStartNetTx(inputDeviceId, netRate, voiceCommunicationInput)

    /** Fill [outBuf] with one TX frame at netRate (returns samples written). */
    fun fillNetTxFrame(outBuf: ShortArray, numSamples: Int): Int =
        nativeFillNetTxFrame(outBuf, numSamples)

    /* ── PureSignal (WDSP adaptive TX predistortion) ─────────────
     * Dormant engine (nothing calls these yet). Wraps WDSP's calcc
     * (calibration computer) + iqc (TX I/Q corrector) — GPL, Warren
     * Pratt NR0V. See cpp/wdsp_ps/puresignal.h for the full contract. */

    /** Create the PureSignal engine for interleaved I/Q at [rate] Hz,
     *  nominal [blockSize] complex samples per feed/apply call. */
    fun psCreate(rate: Int = 48000, blockSize: Int = 1024): Boolean =
        nativePsCreate(rate, blockSize)

    /** Shut down and free the PureSignal engine. */
    fun psDestroy() = nativePsDestroy()

    /** Feed simultaneous TX-reference and RX-feedback blocks (interleaved
     *  I/Q floats, [n] complex samples each) to the calibration computer. */
    fun psFeed(txRef: FloatArray, feedback: FloatArray, n: Int) =
        nativePsFeed(txRef, feedback, n)

    /** Apply the current predistortion to TX I/Q in place ([n] complex samples). */
    fun psApply(txIq: FloatArray, n: Int) = nativePsApply(txIq, n)

    /** Copy the 16-int calibration state vector ([14]=correcting, [15]=state). */
    fun psGetInfo(out16: IntArray) = nativePsGetInfo(out16)

    /** Enable/disable automatic calibration (correction ramps in/out). */
    fun psSetRun(run: Boolean) = nativePsSetRun(run)

    /* ── USB Audio Device Discovery ──────────────────────────── */

    data class AudioDevice(
        val id: Int,
        val name: String,
        val type: Int,
        val typeName: String,
        val isUsb: Boolean,
        val isBluetooth: Boolean = false,
        /** A2DP media-playback profile — the correct target for decoded RX speech. */
        val isBluetoothA2dp: Boolean = false,
        /** HFP/SCO call-audio profile — microphone-capable, but silent for media
         *  playback unless the communication route is active. Not a normal RX output. */
        val isBluetoothSco: Boolean = false,
        /** Bluetooth LE Audio (LC3) — high-quality bidirectional audio. The mic can be
         *  captured directly (up to 32 kHz) with no SCO call-audio route. */
        val isBleAudio: Boolean = false,
        val isWired: Boolean = false
    )

    /** List available audio input devices. */
    fun getInputDevices(): List<AudioDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return getAudioDevices(am, AudioManager.GET_DEVICES_INPUTS)
            .distinctBy { it.id }
            .map { toAudioDevice(it) }
            .sortedWith(audioDeviceComparator)
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

    /** Find the built-in loudspeaker, or null. */
    fun findBuiltInSpeaker(): AudioDevice? {
        return getOutputDevices().firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    /**
     * Best automatic RX playback target: stable local playback. Bluetooth is
     * intentionally not auto-picked because turning Bluetooth on can expose a
     * headset mic and steal the RX input route; users can still select Bluetooth
     * explicitly from settings.
     */
    fun findPreferredRxOutputDevice(): AudioDevice? {
        val outputs = getOutputDevices()
        return outputs.firstOrNull { it.isWired }
            // Prefer a Bluetooth LE Audio (LC3) headset/speaker when present. RX is
            // opened on it by hard device id via Oboe (not the A2DP/SCO soft-hint
            // path that regressed before), so playback goes to the LC3 earphone
            // instead of the phone speaker — and AUTO recovers to it after a TX
            // cycle. Classic A2DP/SCO are still not auto-preferred.
            ?: outputs.firstOrNull { it.isBleAudio }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull { !it.isBluetooth && !it.isUsb }
    }

    /** List available audio output devices. */
    fun getOutputDevices(): List<AudioDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return getAudioDevices(am, AudioManager.GET_DEVICES_OUTPUTS)
            .distinctBy { it.id }
            .map { toAudioDevice(it) }
            .sortedWith(audioDeviceComparator)
    }

    /* ── Native methods ──────────────────────────────────────── */

    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativeSetInputGain(gain: Float)
    private external fun nativeGetInputGain(): Float
    private external fun nativeSetTxMicGain(gain: Float)
    private external fun nativeStartRecording(path: String): Boolean
    private external fun nativeStopRecording()
    private external fun nativeSetCallback(callback: Any)
    private external fun nativeStart(
        inputDeviceId: Int,
        outputDeviceId: Int,
        voiceCommunicationOutput: Boolean,
        bleAudioOutput: Boolean
    ): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetInputDevice(deviceId: Int)
    private external fun nativeSetOutputDevice(deviceId: Int)
    private external fun nativeSetRxJavaOutputEnabled(enabled: Boolean)
    private external fun nativeSetRxVoiceCommunicationOutputEnabled(enabled: Boolean)
    external fun nativeIsRxUsingJavaOutput(): Boolean
    private external fun nativeSetDevices(inputDeviceId: Int, outputDeviceId: Int)
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
    private external fun nativeStartTx(
        inputDeviceId: Int,
        outputDeviceId: Int,
        keepRxAlive: Boolean,
        voiceCommunicationInput: Boolean,
        voiceRecognitionInput: Boolean
    ): Boolean
    private external fun nativePauseRxInput(): Boolean
    private external fun nativeResumeRxInput(): Boolean
    private external fun nativeStopTx(drainEoo: Boolean)
    private external fun nativeIsTxRunning(): Boolean
    private external fun nativeSetTxCallsign(callsign: String)
    private external fun nativeGetTxLevel(): Float
    private external fun nativeSetTxOutputDevice(deviceId: Int)
    external fun nativeReadRxRing(outBuf: ShortArray, maxSamples: Int): Int
    external fun nativeReadTxRing(outBuf: ShortArray, maxSamples: Int): Int
    external fun nativeTxRingAvailable(): Int
    external fun nativeIsTxUsingJavaOutput(): Boolean

    /* Network audio (Icom RS-BA1 / IC-705 Wi-Fi) */
    external fun nativeStartNetRx(outputDeviceId: Int, netRate: Int): Boolean
    external fun nativeFeedNetRx(pcm: ShortArray, count: Int)
    external fun nativeStartNetTx(inputDeviceId: Int, netRate: Int, voiceCommunicationInput: Boolean): Boolean
    external fun nativeFillNetTxFrame(outBuf: ShortArray, numSamples: Int): Int

    /* PureSignal (WDSP calcc/iqc) */
    private external fun nativePsCreate(rate: Int, blockSize: Int): Boolean
    private external fun nativePsDestroy()
    private external fun nativePsFeed(txRef: FloatArray, feedback: FloatArray, n: Int)
    private external fun nativePsApply(txIq: FloatArray, n: Int)
    private external fun nativePsGetInfo(out16: IntArray)
    private external fun nativePsSetRun(run: Boolean)

    private val audioDeviceComparator = compareByDescending<AudioDevice> { it.isUsb }
        .thenByDescending { it.isBluetooth }
        .thenByDescending { it.isWired }
        .thenBy { it.typeName }
        .thenBy { it.name }
        .thenBy { it.id }

    private fun toAudioDevice(info: AudioDeviceInfo): AudioDevice {
        return AudioDevice(
            id = info.id,
            name = info.productName?.toString() ?: "Unknown",
            type = info.type,
            typeName = deviceTypeName(info.type),
            isUsb = isUsbType(info.type),
            isBluetooth = isBluetoothType(info.type),
            isBluetoothA2dp = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            isBluetoothSco = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            isBleAudio = isBleAudioType(info.type),
            isWired = isWiredType(info.type)
        )
    }

    private fun isUsbType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun isBluetoothType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun isBleAudioType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST

    private fun isWiredType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    private fun getAudioDevices(am: AudioManager, flags: Int): Array<AudioDeviceInfo> {
        return try {
            am.getDevices(flags)
        } catch (e: SecurityException) {
            Log.w("AudioBridge", "Audio device listing requires Bluetooth permission", e)
            emptyArray()
        }
    }

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
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE (LC3)"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Broadcast"
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

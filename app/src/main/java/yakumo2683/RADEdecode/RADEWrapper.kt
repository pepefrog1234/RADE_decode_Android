package yakumo2683.RADEdecode

/**
 * JNI wrapper for the RADE modem, FARGAN vocoder, and EOO callsign codec.
 *
 * Mirrors the iOS RADEWrapper (RADETypes.swift) providing a Kotlin-friendly
 * API over the native C libraries.
 *
 * Usage:
 * 1. Call [open] to initialise the RADE modem
 * 2. Call [farganInit] to initialise the FARGAN vocoder
 * 3. Feed audio samples via [rx] in a loop
 * 4. When features are available, synthesise speech via [farganSynthesize]
 * 5. Call [close] when done
 */
class RADEWrapper {

    companion object {
        init {
            System.loadLibrary("rade_jni")
        }

        /** RADE modem sample rate (input) */
        const val MODEM_SAMPLE_RATE = 8000

        /** FARGAN speech output sample rate */
        const val SPEECH_SAMPLE_RATE = 16000

        /** FARGAN frame size in samples */
        const val FARGAN_FRAME_SIZE = 160

        /** FARGAN warmup/continuation sample count */
        const val FARGAN_CONT_SAMPLES = 320

        /** rade_open flag: use C encoder */
        const val FLAG_USE_C_ENCODER = 0x1

        /** rade_open flag: use C decoder */
        const val FLAG_USE_C_DECODER = 0x2
    }

    /* ── RADE modem ─────────────────────────────────────────── */

    /**
     * Open the RADE modem context.
     * @param flags combination of FLAG_USE_C_ENCODER / FLAG_USE_C_DECODER
     * @return true on success
     */
    fun open(flags: Int = FLAG_USE_C_DECODER): Boolean = nativeOpen(flags)

    /** Close and release the RADE modem context. */
    fun close() = nativeClose()

    /** Number of input samples needed for the next [rx] call. */
    fun nin(): Int = nativeNin()

    /** Maximum value nin() can ever return (for buffer pre-allocation). */
    fun ninMax(): Int = nativeNinMax()

    /** Number of feature floats produced per modem frame. */
    fun numFeatures(): Int = nativeGetNumFeatures()

    /** Number of EOO bits per frame. */
    fun numEooBits(): Int = nativeGetNumEooBits()

    /**
     * Result from a single [rx] call.
     *
     * @property hasFeatures true if valid decoded features are available
     * @property hasEoo      true if End-of-Over was detected
     * @property features    decoded feature vector (only valid when hasFeatures)
     * @property eooBits     EOO soft-decision bits (only valid when hasEoo)
     */
    data class RxResult(
        val hasFeatures: Boolean,
        val hasEoo: Boolean,
        val features: FloatArray,
        val eooBits: FloatArray
    )

    /**
     * Feed modem samples to the receiver.
     *
     * @param samples Int16 audio samples at 8 kHz (length must == [nin])
     * @return [RxResult] with decoded features / EOO state
     */
    fun rx(samples: ShortArray): RxResult {
        val nFeat = numFeatures()
        val nEoo = numEooBits()
        val features = FloatArray(nFeat)
        val eoo = FloatArray(nEoo)

        val ret = nativeRx(samples, samples.size, features, eoo)

        return RxResult(
            hasFeatures = (ret and 1) != 0,
            hasEoo = (ret and 2) != 0,
            features = features,
            eooBits = eoo
        )
    }

    /** Current sync state (0 = SEARCH, 1 = CANDIDATE, 2 = SYNC). */
    fun sync(): Int = nativeSync()

    /** Estimated frequency offset in Hz. */
    fun freqOffset(): Float = nativeFreqOffset()

    /** Estimated SNR in dB (3 kHz bandwidth). */
    fun snrEstimate(): Int = nativeSnrEstimate()

    /** RADE library version number. */
    fun version(): Int = nativeVersion()

    /* ── FARGAN vocoder ─────────────────────────────────────── */

    /** Initialise the FARGAN vocoder state. */
    fun farganInit(): Boolean = nativeFarganInit()

    /** Release FARGAN vocoder state. */
    fun farganClose() = nativeFarganClose()

    /**
     * Warmup / continuation: feed initial PCM and features to prime FARGAN.
     *
     * @param pcm      Float PCM samples (length = [FARGAN_CONT_SAMPLES])
     * @param features feature vector from [rx]
     */
    fun farganCont(pcm: FloatArray, features: FloatArray) =
        nativeFarganCont(pcm, features)

    /**
     * Synthesise one frame of 16 kHz Int16 speech from features.
     *
     * @param features feature vector from [rx]
     * @return 160 Int16 PCM samples at 16 kHz
     */
    fun farganSynthesize(features: FloatArray): ShortArray {
        val pcm = ShortArray(FARGAN_FRAME_SIZE)
        nativeFarganSynthesize(features, pcm)
        return pcm
    }

    /* ── EOO callsign codec ─────────────────────────────────── */

    /**
     * Decode EOO soft-decision bits into a callsign string.
     *
     * @param syms EOO symbol soft-decisions from [rx]
     * @return decoded callsign, or null if decode failed (BER too high / CRC fail)
     */
    fun eooCallsignDecode(syms: FloatArray): String? =
        nativeEooCallsignDecode(syms)

    /* ── Native method declarations ─────────────────────────── */

    private external fun nativeOpen(flags: Int): Boolean
    private external fun nativeClose()
    private external fun nativeNin(): Int
    private external fun nativeNinMax(): Int
    private external fun nativeGetNumFeatures(): Int
    private external fun nativeGetNumEooBits(): Int
    private external fun nativeRx(
        samplesIn: ShortArray, numSamples: Int,
        featuresOut: FloatArray, eooOut: FloatArray
    ): Int
    private external fun nativeSync(): Int
    private external fun nativeFreqOffset(): Float
    private external fun nativeSnrEstimate(): Int
    private external fun nativeVersion(): Int

    private external fun nativeFarganInit(): Boolean
    private external fun nativeFarganClose()
    private external fun nativeFarganCont(pcm: FloatArray, features: FloatArray)
    private external fun nativeFarganSynthesize(features: FloatArray, pcmOut: ShortArray)

    private external fun nativeEooCallsignDecode(syms: FloatArray): String?
}

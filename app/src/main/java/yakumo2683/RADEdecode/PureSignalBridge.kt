package yakumo2683.RADEdecode

/**
 * Kotlin bridge to the WDSP PureSignal engine (cpp/wdsp_ps/) — adaptive TX
 * predistortion, the same calcc (calibration computer) + iqc (TX I/Q
 * corrector) pair Thetis uses. GPL, Warren Pratt NR0V; see
 * cpp/wdsp_ps/puresignal.h for the full threading/lifecycle contract.
 *
 * Deliberately an `object`, NOT part of [AudioBridge]: AudioBridge's
 * constructor creates a whole native AudioEngine, which callers that only
 * need PureSignal (HermesNetworkManager) must not instantiate. The JNI
 * entry points are registered by class+name, so rade_jni.cpp exports
 * parallel Java_yakumo2683_RADEdecode_PureSignalBridge_nativePs* symbols
 * delegating to the same native facade (a single engine instance —
 * AudioBridge's psXxx wrappers and these drive the same state).
 */
object PureSignalBridge {

    init {
        System.loadLibrary("rade_jni")
    }

    /** Create the PureSignal engine for interleaved I/Q at [rate] Hz,
     *  nominal [blockSize] complex samples per feed/apply call. [hardwarePeak]
     *  is the nominal peak of the radio-provided TX/DAC reference. */
    fun psCreate(
        rate: Int = 48000,
        blockSize: Int = 1024,
        hardwarePeak: Float = 1.0f
    ): Boolean = nativePsCreate(rate, blockSize, hardwarePeak)

    /** Shut down and free the PureSignal engine (safe when not created). */
    fun psDestroy() = nativePsDestroy()

    /** Feed simultaneous TX-reference and RX-feedback blocks (interleaved
     *  I/Q floats, [n] complex samples each) to the calibration computer. */
    fun psFeed(txRef: FloatArray, feedback: FloatArray, n: Int) =
        nativePsFeed(txRef, feedback, n)

    /** Apply the current predistortion to TX I/Q in place ([n] complex samples). */
    fun psApply(txIq: FloatArray, n: Int) = nativePsApply(txIq, n)

    /** Copy the 16-int calibration state vector ([5]=attempts, [8]=accepted,
     *  [9]=rejected, [10]=last outcome, [13]=watchdog, [14]=correcting,
     *  [15]=state machine state, [4]=feedback level). */
    fun psGetInfo(out16: IntArray) = nativePsGetInfo(out16)

    /** Start one calibration, or reset and ramp correction out. */
    fun psSetRun(run: Boolean) = nativePsSetRun(run)

    /** Start one manual calibration; accepted correction remains active. */
    fun psStartSingleCalibration() = nativePsStartSingleCalibration()

    /** Change only MOX state while preserving accepted correction coefficients. */
    fun psSetMox(mox: Boolean) = nativePsSetMox(mox)

    private external fun nativePsCreate(rate: Int, blockSize: Int, hardwarePeak: Float): Boolean
    private external fun nativePsDestroy()
    private external fun nativePsFeed(txRef: FloatArray, feedback: FloatArray, n: Int)
    private external fun nativePsApply(txIq: FloatArray, n: Int)
    private external fun nativePsGetInfo(out16: IntArray)
    private external fun nativePsSetRun(run: Boolean)
    private external fun nativePsStartSingleCalibration()
    private external fun nativePsSetMox(mox: Boolean)
}

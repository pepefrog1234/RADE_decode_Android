package yakumo2683.RADEdecode.network

/**
 * Assembles the two synchronous Hermes-Lite 2 PureSignal receiver streams.
 *
 * The HL2 gateware keeps RX1 on the physical ADC (the PA output feedback),
 * while enabling its PureSignal route replaces RX2's ADC input with the
 * internal TX DAC sample. WDSP's pscc() contract is TX reference first and
 * PA feedback second, so the mapping here is deliberately:
 *
 *   RX2 -> TX/DAC reference
 *   RX1 -> PA/ADC feedback
 *
 * [txReferenceScale] reverses any application-side digital drive scaling
 * that happens after WDSP's corrector. Without that inverse, CALCC would fit
 * coefficients against the scaled radio output while IQC indexes them with
 * the unscaled corrector-input envelope.
 *
 * The receiver pair has already passed through matching gateware mixer and
 * decimator paths. It must therefore be fed as a same-sample pair; an app-side
 * TX FIFO/backlog delay would de-align the streams.
 */
internal class Hl2PureSignalBlockAssembler(
    blockSamples: Int,
    private val txReferenceScale: Float,
    private val onBlock: (
        txReferenceIq: FloatArray,
        paFeedbackIq: FloatArray,
        sampleCount: Int
    ) -> Unit
) {
    private val sampleCount = blockSamples.also {
        require(it > 0) { "blockSamples must be positive" }
    }

    init {
        require(txReferenceScale.isFinite() && txReferenceScale > 0f) {
            "txReferenceScale must be finite and positive"
        }
    }

    private val txReferenceIq = FloatArray(sampleCount * 2)
    private val paFeedbackIq = FloatArray(sampleCount * 2)

    /** Reader-owned except for rare PTT/control-thread resets. */
    private var fill = 0

    @Synchronized
    fun reset() {
        fill = 0
    }

    @Synchronized
    fun push(
        rx1FeedbackI: Float,
        rx1FeedbackQ: Float,
        rx2ReferenceI: Float,
        rx2ReferenceQ: Float
    ) {
        val index = fill
        txReferenceIq[2 * index] = rx2ReferenceI * txReferenceScale
        txReferenceIq[2 * index + 1] = rx2ReferenceQ * txReferenceScale
        paFeedbackIq[2 * index] = rx1FeedbackI
        paFeedbackIq[2 * index + 1] = rx1FeedbackQ

        if (index + 1 < sampleCount) {
            fill = index + 1
            return
        }

        fill = 0
        onBlock(txReferenceIq, paFeedbackIq, sampleCount)
    }
}

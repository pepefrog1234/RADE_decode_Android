package yakumo2683.RADEdecode.network

/**
 * A rig whose RX/TX audio rides the network instead of a USB sound card.
 * AudioService's network decode/transmit paths are written against this
 * surface so the Icom RS-BA1 (IC-705 Wi-Fi) and openHPSDR (Hermes-Lite 2)
 * transports are interchangeable.
 *
 * PCM exchanged through this interface is int16 mono at [audioRate], which
 * must be an integer multiple of the 8 kHz modem rate (the native decimator/
 * interpolator only handles integer factors).
 */
interface NetworkAudioRig {
    /** Control link is up — routes the app onto the network audio path. */
    val isConnected: Boolean

    /** Audio/IQ sub-stream actually flowing (status display / diagnostics). */
    val audioLinkUp: Boolean

    /** PCM sample rate exchanged with the native modem (multiple of 8000). */
    val audioRate: Int

    /** Samples per TX pump tick (20 ms at [audioRate]). */
    val txFrameSamples: Int

    /** Depth (ms) of the buffer the RADIO plays our TX frames from, when the
     *  transport has one (Icom RS-BA1 conninfo "txbuffer"); 0 = none/unknown.
     *  The TX pump uses it to decide when a stalled backlog is hopeless. */
    val txBufferMs: Int get() = 0

    /** How long PTT must stay keyed after the last TX frame has been handed to
     *  the transport, so audio still buffered downstream reaches the air. */
    val txTailMs: Int get() = 200

    /** RX delivery hook: int16 mono at [audioRate]. Invoked on the manager's
     *  network thread; detach by setting null. */
    var onAudioPcm: ((ShortArray) -> Unit)?

    /** Queue one TX frame ([txFrameSamples] samples) toward the radio. */
    fun sendAudioFrame(pcm: ShortArray)
}

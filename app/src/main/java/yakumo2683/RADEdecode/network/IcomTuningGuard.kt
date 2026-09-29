package yakumo2683.RADEdecode.network

/**
 * The network profile controls the radio's selected VFO only. Hamlib 4.5.5
 * probes its identity by switching VFOs and sometimes tuning +100 Hz and back.
 * Those writes must not reach a radio that the operator has already tuned.
 * Access is serialized by IcomStream.sendLock, including retransmissions.
 *
 * Only virtual VFO A/B selection is acknowledged locally. Denied frequency,
 * memory and VFO-copy writes must not be reported as successful.
 * v1.6.27 answered NAK; field log (IC-7300MK2, LTE): after one lost 0x25
 * reply Hamlib sets `x25cmdfails` and from then on issues `set_vfo` (07 00)
 * before EVERY read, so the NAK turned every get_freq/get_ptt/get_level into
 * "RPRT -9" — the CAT-health tracker then tore the session down mid-over.
 * Treating A/B selection as a virtual operation lets Hamlib's fallback use
 * selected-VFO commands without moving the physical radio's VFO.
 */
internal class IcomTuningGuard(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var generation = 0L
    private var requestedHz: Long? = null
    private var expiresAt = 0L

    fun begin(hz: Long): Long {
        requestedHz = hz
        expiresAt = nowMs() + 10_000
        return ++generation
    }

    fun end(token: Long) {
        if (generation == token) requestedHz = null
    }

    fun clear() { requestedHz = null; generation++ }

    fun allows(frame: ByteArray): Boolean {
        val address = addressOffset(frame) ?: return true
        val command = frame[address + 2].toInt() and 255
        val payload = address + 3
        return when (command) {
            0x07, 0x08 -> false // VFO select/swap/copy and memory selection
            0x00, 0x05 -> matchesFrequency(frame, payload)
            0x25 -> {
                // 25 00/01 without a frequency is a read-only query.
                if (frame.size == payload + 2) true
                else frame[payload].toInt() == 0 && matchesFrequency(frame, payload + 1)
            }
            else -> true
        }
    }

    private fun matchesFrequency(frame: ByteArray, offset: Int): Boolean {
        val wanted = requestedHz ?: return false
        if (nowMs() >= expiresAt || frame.size != offset + 6) return false
        var hz = 0L
        var place = 1L
        for (i in offset until offset + 5) {
            val value = frame[i].toInt() and 255
            if ((value and 15) > 9 || (value ushr 4) > 9) return false
            hz += ((value and 15) + 10 * (value ushr 4)) * place
            place *= 100
        }
        return hz == wanted
    }

    companion object {
        fun blockedReply(frame: ByteArray, echo: Boolean): ByteArray {
            val address = requireNotNull(addressOffset(frame))
            val isVfoSelection = frame.size == address + 5 && frame[address + 2] == 7.toByte() &&
                frame[address + 3].toInt() in 0..1
            return if (isVfoSelection) acknowledgement(frame, echo) else rejection(frame, echo)
        }

        private fun addressOffset(frame: ByteArray): Int? {
            var i = 0
            while (i < frame.size && frame[i] == 0xfe.toByte()) i++
            return i.takeIf { it >= 2 && frame.size >= it + 4 && frame.last() == 0xfd.toByte() }
        }

        fun rejection(frame: ByteArray, echo: Boolean): ByteArray {
            val address = requireNotNull(addressOffset(frame))
            val nak = byteArrayOf(-2, -2, frame[address + 1], frame[address], 0xfa.toByte(), -3)
            return if (echo) frame + nak else nak
        }

        /** CI-V OK (0xFB) for a virtual VFO selection. */
        fun acknowledgement(frame: ByteArray, echo: Boolean): ByteArray {
            val address = requireNotNull(addressOffset(frame))
            val ack = byteArrayOf(-2, -2, frame[address + 1], frame[address], 0xfb.toByte(), -3)
            return if (echo) frame + ack else ack
        }

        fun isControllerEcho(frame: ByteArray): Boolean {
            val address = addressOffset(frame) ?: return false
            return frame[address + 1] == 0xe0.toByte()
        }
    }
}

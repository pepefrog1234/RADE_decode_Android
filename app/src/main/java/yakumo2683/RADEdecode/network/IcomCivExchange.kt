package yakumo2683.RADEdecode.network

/** CI-V frames can be split or coalesced by the serial tunnel. */
internal class IcomCivFramer {
    private val pending = ArrayList<Byte>()

    fun feed(bytes: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (b in bytes) {
            if (pending.isEmpty() && b != 0xfe.toByte()) continue
            pending.add(b)
            if (b == 0xfd.toByte() || b == 0xfc.toByte()) {
                val frame = pending.toByteArray()
                if (addressOffset(frame) != null) frames.add(frame)
                pending.clear()
            } else if (pending.size > 512) {
                pending.clear()
            }
        }
        return frames
    }

    fun clear() { pending.clear() }

    companion object {
        fun addressOffset(frame: ByteArray): Int? {
            var start = 0
            while (start < frame.size && frame[start] == 0xfe.toByte()) start++
            return start.takeIf { it >= 2 && frame.size >= it + 4 &&
                (frame.last() == 0xfd.toByte() || frame.last() == 0xfc.toByte()) }
        }

        fun isControllerEcho(frame: ByteArray): Boolean =
            addressOffset(frame)?.let { frame[it + 1] == 0xe0.toByte() } == true
    }
}

/**
 * Hamlib 4.5.5 does not validate the reply opcode/address, and determines its
 * echo mode only at startup. A lost/reordered radio echo can therefore become
 * another command's answer (even a plausible but wrong BCD frequency).
 * The bridge supplies one local echo for every request and only passes a
 * matching radio response. Access is serialized by IcomStream.sendLock.
 */
internal class IcomCivExchange {
    private val framer = IcomCivFramer()
    private var request: ByteArray? = null
    var discardedReplies = 0L
        private set
    var discardedEchoes = 0L
        private set

    fun begin(frame: ByteArray) { framer.clear(); request = frame.copyOf() }
    fun clear() { request = null; framer.clear() }
    fun pendingRequest(): ByteArray? = request?.copyOf()

    fun receive(bytes: ByteArray): List<ByteArray> = buildList {
        for (frame in framer.feed(bytes)) {
            val offset = IcomCivFramer.addressOffset(frame) ?: continue
            if (IcomCivFramer.isControllerEcho(frame)) {
                discardedEchoes++
                continue
            }
            if (frame[offset] == 0.toByte()) {
                // Radio broadcasts (e.g. a manual dial change) are asynchronous.
                add(frame)
                continue
            }
            val active = request
            if (active != null && matches(active, frame, offset)) {
                request = null
                add(frame)
            } else {
                discardedReplies++
            }
        }
    }

    private fun matches(sent: ByteArray, reply: ByteArray, replyOffset: Int): Boolean {
        val sentOffset = IcomCivFramer.addressOffset(sent) ?: return false
        // Raw data/filter queries use CI-V's universal destination 00; the
        // radio replies from its actual address, not from 00.
        if (reply[replyOffset] != sent[sentOffset + 1] ||
            (sent[sentOffset] != 0.toByte() && reply[replyOffset + 1] != sent[sentOffset]) ||
            reply.last() != 0xfd.toByte()) return false
        val requestBody = sent.copyOfRange(sentOffset + 2, sent.size - 1)
        val body = reply.copyOfRange(replyOffset + 2, reply.size - 1)
        val command = requestBody[0].toInt() and 255
        // A negative acknowledgement is a real failure, never fabricated success.
        if (body.size == 1 && body[0] == 0xfa.toByte()) return true
        if (body.size == 1 && body[0] == 0xfb.toByte()) return !isRead(requestBody)
        // A delayed read with the same opcode is not a write acknowledgement.
        if (!isRead(requestBody) || body[0] != requestBody[0]) return false
        val prefix = when (command) {
            0x14, 0x15, 0x16, 0x19, 0x1c, 0x25, 0x26 -> 2
            0x1a -> if (requestBody.getOrNull(1) == 5.toByte()) 4 else 2
            else -> 1
        }
        if (requestBody.size < prefix || body.size <= prefix) return false
        if (!(0 until prefix).all { requestBody[it] == body[it] }) return false
        if (command == 0x03 || command == 0x25) {
            if (body.size != prefix + 5) return false
            if (body.drop(prefix).any { (it.toInt() and 15) > 9 || ((it.toInt() and 255) ushr 4) > 9 }) return false
        }
        return true
    }

    private fun isRead(body: ByteArray): Boolean = when (body[0].toInt() and 255) {
        0x03, 0x04, 0x15, 0x19 -> true
        // rigctld checks power status when a TCP client connects. Its 18 read
        // must pass through even though 18 00/01 are power writes.
        0x0f, 0x11, 0x12, 0x18 -> body.size == 1
        0x14, 0x16, 0x1c, 0x25, 0x26 -> body.size == 2
        0x1a -> body.size == if (body.getOrNull(1) == 5.toByte()) 4 else 2
        else -> false
    }
}

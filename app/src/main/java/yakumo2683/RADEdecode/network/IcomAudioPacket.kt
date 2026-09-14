package yakumo2683.RADEdecode.network

/** RS-BA1 mono S16LE audio. 48 kHz frames are split at 1364 bytes; a
 * 16 kHz/20 ms frame fits in one 640-byte payload (664 including its header). */
internal object IcomAudioPacket {
    fun isAudio(packet: ByteArray): Boolean {
        if (packet.size < 26 || packet.size > 1388) return false
        fun u(i: Int) = packet[i].toInt() and 0xff
        val length = u(0) or (u(1) shl 8)
        // RX header identifiers vary across radios/codecs. Like wfview, use
        // the audio port, type and packet length; do not require the TX ident
        // or TX datalen fields from buildAudioPart on received packets.
        return length == packet.size && u(2) == 0 && u(3) == 0 &&
            u(4) == 0 && u(5) == 0 && (packet.size - 24) % 2 == 0
    }

    fun pcm(packet: ByteArray): ShortArray = ShortArray((packet.size - 24) / 2) { i ->
        val offset = 24 + i * 2
        ((packet[offset].toInt() and 0xff) or (packet[offset + 1].toInt() shl 8)).toShort()
    }
}

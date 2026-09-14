package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class IcomAudioPacketTest {
    @Test fun acceptsBoth48kFragmentsAndThe16kPacket() {
        for (payload in listOf(1364, 556, 640)) {
            val packet = packet(payload)
            packet[24] = 0x34; packet[25] = 0x12
            packet[26] = 0xfe.toByte(); packet[27] = 0xff.toByte()
            assertTrue(IcomAudioPacket.isAudio(packet))
            val pcm = IcomAudioPacket.pcm(packet)
            assertEquals(payload / 2, pcm.size)
            assertEquals(0x1234.toShort(), pcm[0])
            assertEquals((-2).toShort(), pcm[1])
        }
    }

    @Test fun rejectsMalformedLengthsOtherMessagesAndOddPcm() {
        assertFalse(IcomAudioPacket.isAudio(ByteArray(21)))
        assertFalse(IcomAudioPacket.isAudio(packet(640).also { it[0] = 0 }))
        assertTrue(IcomAudioPacket.isAudio(packet(640).also { it[16] = 0; it[22] = 0; it[23] = 0 }))
        assertFalse(IcomAudioPacket.isAudio(packet(640).also { it[4] = 7 }))
        assertFalse(IcomAudioPacket.isAudio(packet(639)))
    }

    private fun packet(payload: Int) = ByteArray(24 + payload).also {
        it[0] = it.size.toByte(); it[1] = (it.size ushr 8).toByte()
        it[16] = 0x80.toByte()
        it[22] = (payload ushr 8).toByte(); it[23] = payload.toByte()
    }
}

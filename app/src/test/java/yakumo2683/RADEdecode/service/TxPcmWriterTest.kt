package yakumo2683.RADEdecode.service

import org.junit.Assert.*
import org.junit.Test

class TxPcmWriterTest {
    @Test fun shortWritesPreserveWaveformAndEoo() {
        val played = mutableListOf<Short>()
        val writer = TxPcmWriter { samples, offset, count ->
            val accepted = minOf(count, 3)
            played.addAll(samples.slice(offset until offset + accepted))
            accepted
        }
        val speech = shortArrayOf(-32768, 0, 32767, 100, -100, 50, -50)
        val eoo = shortArrayOf(20, 30, 40, 50)
        writer.write(speech, speech.size)
        writer.write(eoo, 3)
        assertEquals(speech.toList() + eoo.take(3), played)
        assertEquals(10L, writer.framesWritten)
    }

    @Test fun errorDoesNotCountUnwrittenFramesOrSpinForever() {
        for (error in listOf(0, -3, -6, 100)) {
            var calls = 0
            val writer = TxPcmWriter { _, _, _ -> if (calls++ == 0) 2 else error }
            try {
                writer.write(ShortArray(5), 5)
                fail("Expected write error $error")
            } catch (_: IllegalStateException) {
                assertEquals(2L, writer.framesWritten)
                assertEquals(2, calls)
            }
        }
    }
}

package yakumo2683.RADEdecode.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Hl2PureSignalBlockAssemblerTest {

    @Test
    fun `feeds inverse-scaled RX2 as TX reference and RX1 as PA feedback`() {
        var calls = 0
        var txReference = FloatArray(0)
        var paFeedback = FloatArray(0)
        val assembler = Hl2PureSignalBlockAssembler(
            blockSamples = 2,
            txReferenceScale = 1.25f
        ) { tx, feedback, count ->
            calls++
            assertEquals(2, count)
            txReference = tx.copyOf()
            paFeedback = feedback.copyOf()
        }

        assembler.push(
            rx1FeedbackI = 0.125f,
            rx1FeedbackQ = -0.25f,
            rx2ReferenceI = 0.75f,
            rx2ReferenceQ = -0.5f
        )
        assembler.push(
            rx1FeedbackI = -0.375f,
            rx1FeedbackQ = 0.5f,
            rx2ReferenceI = -0.875f,
            rx2ReferenceQ = 0.625f
        )

        assertEquals(1, calls)
        assertArrayEquals(
            floatArrayOf(0.9375f, -0.625f, -1.09375f, 0.78125f),
            txReference,
            0f
        )
        assertArrayEquals(floatArrayOf(0.125f, -0.25f, -0.375f, 0.5f), paFeedback, 0f)
    }

    @Test
    fun `reset discards an incomplete receiver pair block`() {
        var calls = 0
        var txReference = FloatArray(0)
        val assembler = Hl2PureSignalBlockAssembler(
            blockSamples = 2,
            txReferenceScale = 1f
        ) { tx, _, _ ->
            calls++
            txReference = tx.copyOf()
        }

        assembler.push(
            rx1FeedbackI = 1f,
            rx1FeedbackQ = 2f,
            rx2ReferenceI = 3f,
            rx2ReferenceQ = 4f
        )
        assembler.reset()
        assembler.push(
            rx1FeedbackI = 5f,
            rx1FeedbackQ = 6f,
            rx2ReferenceI = 7f,
            rx2ReferenceQ = 8f
        )
        assembler.push(
            rx1FeedbackI = 9f,
            rx1FeedbackQ = 10f,
            rx2ReferenceI = 11f,
            rx2ReferenceQ = 12f
        )

        assertEquals(1, calls)
        assertArrayEquals(floatArrayOf(7f, 8f, 11f, 12f), txReference, 0f)
    }
}

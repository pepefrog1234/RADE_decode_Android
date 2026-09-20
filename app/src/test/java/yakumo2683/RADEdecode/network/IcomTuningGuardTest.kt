package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class IcomTuningGuardTest {
    @Test fun startupCannotProbeOrRestoreEitherVfo() {
        val guard = IcomTuningGuard()
        listOf("0700", "0701", "07B0", "0801", "25000051060700", "25000050060700",
            "050050060700", "000050060700").forEach {
            assertFalse(it, guard.allows(frame(it)))
        }
        listOf("03", "2500", "2501", "2600", "0401", "1C00", "1C0000", "1C0001").forEach {
            assertTrue(it, guard.allows(frame(it)))
        }
    }

    @Test fun permitAllowsOnlyRequestedFrequencyOnSelectedVfo() {
        val guard = IcomTuningGuard()
        val token = guard.begin(7_177_000)
        assertTrue(guard.allows(frame("25000070170700")))
        assertTrue(guard.allows(frame("050070170700")))
        assertFalse(guard.allows(frame("25010070170700")))
        assertFalse(guard.allows(frame("25000071170700"))) // probe +100
        assertFalse(guard.allows(frame("25000050060700"))) // saved 7065
        assertFalse(guard.allows(frame("0700")))
        guard.end(token)
        assertFalse(guard.allows(frame("25000070170700")))
    }

    @Test fun oldCompletionCannotRevokeNewRequestAndOldWritesStayBlocked() {
        val guard = IcomTuningGuard()
        val old = guard.begin(7_065_000)
        val current = guard.begin(7_177_000)
        guard.end(old)
        assertTrue(guard.allows(frame("25000070170700")))
        assertFalse(guard.allows(frame("25000050060700")))
        guard.end(current)
        assertFalse(guard.allows(frame("25000070170700")))
        assertFalse(IcomTuningGuard().allows(frame("25000070170700"))) // new session
    }

    @Test fun expiredOrMalformedWritesAreRejected() {
        var now = 0L
        val guard = IcomTuningGuard { now }
        guard.begin(7_177_000)
        assertFalse(guard.allows(frame("2500007A170700")))
        assertFalse(guard.allows(frame("250000701707")))
        now = 10_000
        assertFalse(guard.allows(frame("25000070170700")))
    }

    @Test fun negativeReplyUsesActualAddressesAndOptionalEcho() {
        val request = frame("0700")
        val nak = bytes("FEFEE0A4FAFD")
        assertArrayEquals(nak, IcomTuningGuard.rejection(request, false))
        assertArrayEquals(request + nak, IcomTuningGuard.rejection(request, true))
        assertTrue(IcomTuningGuard.isControllerEcho(request))
        assertFalse(IcomTuningGuard.isControllerEcho(nak))
        assertFalse(IcomTuningGuard().allows(bytes("FEFE") + request))
    }

    companion object {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun frame(body: String) = bytes("FEFEA4E0${body}FD")
    }
}

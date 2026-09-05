package yakumo2683.RADEdecode.network

import org.junit.Assert.*
import org.junit.Test

class RigctldReplyTest {
    @Test fun onlySuccessfulAcknowledgementConfirmsPtt() {
        assertEquals(0, rigctldPttResult("set_ptt: 1;RPRT 0", true))
        assertEquals(0, rigctldPttResult(" set_ptt: 0;RPRT\t0\r\n", false))
        assertEquals(-5, rigctldPttResult("set_ptt: 1;RPRT -5", true))
        assertEquals(-11, rigctldPttResult("set_ptt: 0;RPRT -11", false))
        for (reply in listOf(null, "", "RPRT 0", "RPRT -1", "1", "14115000", "set_ptt: 0;RPRT 0", "set_freq: 14115000;RPRT 0")) {
            assertNull("Must not acknowledge '$reply' as PTT ON", rigctldPttResult(reply, true))
        }
    }

    @Test fun lateStatusAndOppositePttRepliesAreSkipped() {
        val replies = listOf("14115000", "RPRT 0", "set_ptt: 1;RPRT 0", "set_ptt: 0;RPRT -5")
        assertEquals(-5, replies.firstNotNullOfOrNull { rigctldPttResult(it, false) })
    }

    @Test fun failedOrUnrelatedStatusReadDoesNotKeyTheUi() {
        assertEquals(false, rigctldPtt("0\r\n"))
        assertEquals(true, rigctldPtt("1"))
        assertEquals(true, rigctldPtt("2"))
        assertEquals(true, rigctldPtt("3"))
        for (reply in listOf(null, "", "RPRT -1", "RPRT 0", "14115000")) {
            assertNull(rigctldPtt(reply))
        }
    }
}

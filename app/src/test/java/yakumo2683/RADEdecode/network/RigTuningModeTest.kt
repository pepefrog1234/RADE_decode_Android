package yakumo2683.RADEdecode.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RigTuningModeTest {
    @Test fun dataModeFollowsTheBand() {
        assertEquals("PKTLSB", rigModeAfterTuning(7_177_000, "Icom"))
        assertEquals("PKTLSB", rigModeAfterTuning(9_999_999, "Yaesu"))
        assertEquals("PKTUSB", rigModeAfterTuning(10_000_000, "Yaesu"))
        assertEquals("PKTUSB", rigModeAfterTuning(14_236_000, "Kenwood"))
    }

    @Test fun rigsWithoutADataModeGetThePlainSideband() {
        assertEquals("LSB", rigModeAfterTuning(7_177_000, "Xiegu"))
        assertEquals("USB", rigModeAfterTuning(14_236_000, "Xiegu"))
        assertEquals("USB", rigModeAfterTuning(14_236_000, "Alinco"))
    }

    /** Hamlib's packet modes are the QMX's Digi (FSK) mode: see
     *  HamlibKenwoodPttTest.sidebandModesSelectSsbAndPacketModesTheDigiMode. */
    @Test fun qrpLabsRigsGetThePlainSideband() {
        assertEquals("LSB", rigModeAfterTuning(3_625_000, "QRP Labs"))
        assertEquals("USB", rigModeAfterTuning(14_236_000, "QRP Labs"))
    }
}

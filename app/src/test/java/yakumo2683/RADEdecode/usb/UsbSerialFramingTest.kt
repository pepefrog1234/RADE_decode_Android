package yakumo2683.RADEdecode.usb

import org.junit.Assert.*
import org.junit.Test

class UsbSerialFramingTest {
    @Test fun ft897AndFt897dUseTwoStopBits() {
        assertEquals(UsbSerialFraming.EIGHT_N_TWO, UsbSerialFraming.forRigModel(1023))
        assertEquals(UsbSerialFraming.EIGHT_N_TWO, UsbSerialFraming.forRigModel(1043))
    }

    @Test fun otherRigsKeepExistingOneStopBitDefault() {
        for (model in listOf(-1, 0, 1, 1022, 1024, 1042, 1044, 3073)) {
            assertEquals("model=$model", UsbSerialFraming.EIGHT_N_ONE,
                UsbSerialFraming.forRigModel(model))
        }
    }

    @Test fun ft897CdcRequestContains8N2AndLittleEndianBaudRate() {
        // CDC PSTN Table 17: bCharFormat = 2, bParityType = 0, bDataBits = 8.
        val framing = UsbSerialFraming.forRigModel(1023)
        val command = framing.cdcLineControl(38400, controlInterface = 2)
        assertSetup(command, 0x21, 0x20, 0x0000, 2)
        assertArrayEquals(byteArrayOf(0x00, 0x96.toByte(), 0x00, 0x00, 0x02, 0x00, 0x08),
            command.data)
    }

    @Test fun defaultCdcRequestKeeps8N1AndRequestedBaudRate() {
        val command = UsbSerialFraming.forRigModel(0).cdcLineControl(4800, 0)
        assertSetup(command, 0x21, 0x20, 0x0000, 0)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x12, 0x00, 0x00, 0x00, 0x00, 0x08),
            command.data)
    }

    @Test fun cp210xUsesHardwareStopBitCodeAndInterface() {
        // Linux cp210x.c: BITS_DATA_8 = 0x0800, BITS_STOP_2 = 0x0002.
        assertNoDataSetup(UsbSerialFraming.forRigModel(1023).cp210xLineControl(1),
            0x41, 0x03, 0x0802, 1)
        assertNoDataSetup(UsbSerialFraming.forRigModel(0).cp210xLineControl(0),
            0x41, 0x03, 0x0800, 0)
    }

    @Test fun ftdiUsesTwoStopBitsRatherThanOneAndAHalfAndOneBasedPort() {
        // Linux ftdi_sio.h: two stop bits = 0x1000; 0x0800 means 1.5 stop bits.
        assertNoDataSetup(UsbSerialFraming.forRigModel(1043).ftdiLineControl(1),
            0x40, 0x04, 0x1008, 2)
        assertNoDataSetup(UsbSerialFraming.forRigModel(0).ftdiLineControl(0),
            0x40, 0x04, 0x0008, 1)
    }

    @Test fun ch340UsesStopBitFlagInRegisterPayloadAndKeepsRxTxEnabled() {
        // Linux ch341.c: STOP_BITS_2 = 0x04, ENABLE_RX/TX = 0xc0, CS8 = 0x03.
        // CH340 writes the register address in wValue and its contents in wIndex.
        assertNoDataSetup(UsbSerialFraming.forRigModel(1023).ch340LineControl(),
            0x40, 0x9A, 0x2518, 0x00C7)
        assertNoDataSetup(UsbSerialFraming.forRigModel(0).ch340LineControl(),
            0x40, 0x9A, 0x2518, 0x00C3)
    }

    private fun assertSetup(command: UsbSerialLineControl,
        requestType: Int, request: Int, value: Int, index: Int
    ) {
        assertEquals("bmRequestType", requestType, command.requestType)
        assertEquals("bRequest", request, command.request)
        assertEquals("wValue", value, command.value)
        assertEquals("wIndex", index, command.index)
    }

    private fun assertNoDataSetup(command: UsbSerialLineControl,
        requestType: Int, request: Int, value: Int, index: Int
    ) {
        assertSetup(command, requestType, request, value, index)
        assertNull(command.data)
    }
}

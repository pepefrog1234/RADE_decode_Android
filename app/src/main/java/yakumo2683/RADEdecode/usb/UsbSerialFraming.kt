package yakumo2683.RADEdecode.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** USB control request which sets the physical UART framing. */
internal data class UsbSerialLineControl(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val data: ByteArray? = null
)

/**
 * The USB/pty bridge copies bytes only: Hamlib's termios settings on the pty do
 * not reach the USB UART. Configure the physical adapter for the selected rig.
 */
internal enum class UsbSerialFraming(val stopBits: Int) {
    EIGHT_N_ONE(1),
    EIGHT_N_TWO(2);

    val description: String get() = "8N$stopBits"

    companion object {
        fun forRigModel(model: Int): UsbSerialFraming = when (model) {
            // Hamlib 4.5.5 rigs/yaesu/ft897.c: 8 data, no parity, 2 stop bits.
            1023, 1043 -> EIGHT_N_TWO // FT-897, FT-897D
            else -> EIGHT_N_ONE
        }
    }

    fun cdcLineControl(baudRate: Int, controlInterface: Int): UsbSerialLineControl {
        // CDC PSTN SET_LINE_CODING: stop-bit encoding 0 = 1, 2 = 2.
        val data = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(baudRate).put(if (stopBits == 2) 2.toByte() else 0.toByte())
            .put(0).put(8).array()
        return UsbSerialLineControl(0x21, 0x20, 0, controlInterface, data)
    }

    fun cp210xLineControl(interfaceIndex: Int): UsbSerialLineControl {
        // CP210x SET_LINE_CTL: BITS_DATA_8 = 0x0800, BITS_STOP_2 = 0x0002.
        val value = 0x0800 or if (stopBits == 2) 0x0002 else 0
        return UsbSerialLineControl(0x41, 0x03, value, interfaceIndex)
    }

    fun ftdiLineControl(interfaceIndex: Int): UsbSerialLineControl {
        // FTDI SET_DATA: the two-stop-bit code is 2 in bits 11..12.
        val value = 0x0008 or if (stopBits == 2) 0x1000 else 0
        return UsbSerialLineControl(0x40, 0x04, value, interfaceIndex + 1)
    }

    fun ch340LineControl(): UsbSerialLineControl {
        // CH341 LCR: enable RX/TX, CS8; bit 2 selects two stop bits.
        val value = 0x00C3 or if (stopBits == 2) 0x0004 else 0
        return UsbSerialLineControl(0x40, 0x9A, 0x2518, value)
    }
}

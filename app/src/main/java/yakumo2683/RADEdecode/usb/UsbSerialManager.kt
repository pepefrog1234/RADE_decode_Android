package yakumo2683.RADEdecode.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages USB serial devices via Android USB Host API.
 *
 * Opens CDC/ACM or chip-specific USB serial devices, configures baud rate
 * via control transfers, then creates a pty pair and bridges USB bulk I/O
 * to the pty master.  rigctld connects to the pty slave path as if it
 * were a normal /dev/ttyXXX serial port.
 */
class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "yakumo2683.RADEdecode.USB_PERMISSION"

        private const val USB_CLASS_CDC = 2
        private const val USB_CLASS_CDC_DATA = 10
        private const val CDC_SUBCLASS_ACM = 2

        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22

        private const val VID_SILABS_CP210X = 0x10C4
        private const val VID_ICOM = 0x0C26
        private const val VID_FTDI = 0x0403
        private const val VID_PROLIFIC = 0x067B
        private const val VID_CH340 = 0x1A86

        private const val CP210X_SET_BAUDRATE = 0x1E
        private const val CP210X_IFC_ENABLE = 0x00
        private const val CP210X_SET_LINE_CTL = 0x03
        private const val FTDI_SET_BAUD_RATE = 3

        @JvmStatic
        external fun nativeStartBridge(
            connection: UsbDeviceConnection,
            endpointIn: UsbEndpoint,
            endpointOut: UsbEndpoint
        ): String?

        @JvmStatic
        external fun nativeStopBridge()

        init { System.loadLibrary("rade_jni") }
    }

    /** A single USB serial interface on a device (a composite device may have multiple) */
    data class UsbSerialDevice(
        val usbDevice: UsbDevice,
        val interfaceIndex: Int,       // which UsbInterface to use for bulk I/O
        val displayName: String,
        val chipType: ChipType
    )

    enum class ChipType { CDC_ACM, CP210X, FTDI, PROLIFIC, CH340, UNKNOWN }

    data class UsbSerialState(
        val devices: List<UsbSerialDevice> = emptyList(),
        val connectedDevice: UsbSerialDevice? = null,
        val ptyPath: String = "",
        val permissionRequested: Boolean = false,
        val error: String = ""
    )

    private val _state = MutableStateFlow(UsbSerialState())
    val state: StateFlow<UsbSerialState> = _state.asStateFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces = mutableListOf<UsbInterface>()

    private var pendingDevice: UsbSerialDevice? = null
    private var pendingBaudRate: Int = 19200
    private var pendingDtr: Boolean = true
    private var pendingRts: Boolean = false
    private var pendingCallback: ((String) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    _state.value = _state.value.copy(permissionRequested = false)

                    val pd = pendingDevice
                    val cb = pendingCallback
                    pendingDevice = null
                    pendingCallback = null

                    if (granted && pd != null) {
                        Log.i(TAG, "USB permission granted")
                        val path = openDeviceInternal(pd, pendingBaudRate, pendingDtr, pendingRts)
                        cb?.invoke(path)
                    } else {
                        Log.w(TAG, "USB permission denied")
                        _state.value = _state.value.copy(error = "USB permission denied")
                        cb?.invoke("")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device?.deviceName == _state.value.connectedDevice?.usbDevice?.deviceName) {
                        close()
                    }
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
            }
        }
    }

    private var receiverRegistered = false

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        refreshDevices()
    }

    fun unregister() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    /**
     * Scan for USB serial devices.
     * For composite devices (like Xiegu DE19), each serial interface is listed separately.
     */
    fun refreshDevices() {
        val devices = mutableListOf<UsbSerialDevice>()

        for (usbDevice in usbManager.deviceList.values) {
            val chipType = detectChipType(usbDevice) ?: continue

            // Find all interfaces that have bulk IN + OUT endpoints
            val serialInterfaces = mutableListOf<Int>()
            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                    }
                }
                if (hasIn && hasOut) serialInterfaces.add(i)
            }

            if (serialInterfaces.isEmpty()) {
                // Device detected by VID but no bulk endpoints — list it with interface 0 anyway
                devices.add(UsbSerialDevice(usbDevice, 0,
                    buildDisplayName(usbDevice, chipType, null), chipType))
            } else if (serialInterfaces.size == 1) {
                devices.add(UsbSerialDevice(usbDevice, serialInterfaces[0],
                    buildDisplayName(usbDevice, chipType, null), chipType))
            } else {
                // Composite device: list each serial interface separately
                for (ifIdx in serialInterfaces) {
                    devices.add(UsbSerialDevice(usbDevice, ifIdx,
                        buildDisplayName(usbDevice, chipType, ifIdx), chipType))
                }
            }
        }

        _state.value = _state.value.copy(devices = devices)
        Log.i(TAG, "Found ${devices.size} USB serial interface(s)")
    }

    /**
     * Open a USB serial device and start the pty bridge.
     * If permission is needed, the system dialog is shown and the callback fires later.
     *
     * @param dtr initial state of the DTR modem line (typical: on to signal "terminal ready")
     * @param rts initial state of the RTS modem line (typical: off — some USB-serial cables wire
     *            RTS to PTT, and asserting it would key the radio on connect)
     */
    fun openDevice(
        device: UsbSerialDevice,
        baudRate: Int,
        dtr: Boolean = true,
        rts: Boolean = false,
        onOpened: (String) -> Unit
    ) {
        _state.value = _state.value.copy(error = "")

        if (usbManager.hasPermission(device.usbDevice)) {
            val path = openDeviceInternal(device, baudRate, dtr, rts)
            onOpened(path)
        } else {
            pendingDevice = device
            pendingBaudRate = baudRate
            pendingDtr = dtr
            pendingRts = rts
            pendingCallback = onOpened
            _state.value = _state.value.copy(permissionRequested = true)

            val permIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, permIntent, flags)
            usbManager.requestPermission(device.usbDevice, pi)
        }
    }

    /**
     * Update DTR / RTS modem lines on the currently open USB serial device.
     * No-op if nothing is connected.
     */
    fun setModemLines(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: return
        val device = _state.value.connectedDevice ?: return
        applyModemLines(conn, device.chipType, device.interfaceIndex, dtr, rts)
    }

    fun close() {
        nativeStopBridge()
        claimedInterfaces.forEach { iface ->
            try { connection?.releaseInterface(iface) } catch (_: Exception) {}
        }
        claimedInterfaces.clear()
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        _state.value = _state.value.copy(connectedDevice = null, ptyPath = "")
    }

    fun destroy() {
        close()
        unregister()
    }

    // ── Internal ──────────────────────────────────────────────

    private fun openDeviceInternal(
        device: UsbSerialDevice,
        baudRate: Int,
        dtr: Boolean,
        rts: Boolean
    ): String {
        try {
            val conn = usbManager.openDevice(device.usbDevice)
            if (conn == null) {
                setError("Failed to open USB device (null connection)")
                return ""
            }
            connection = conn

            // Claim the target interface (and CDC control interface if separate)
            claimInterfaces(device, conn)

            // Find bulk endpoints on the target interface
            val iface = device.usbDevice.getInterface(device.interfaceIndex)
            val endpoints = findBulkEndpointsOnInterface(iface)
            if (endpoints == null) {
                setError("No bulk endpoints on interface ${device.interfaceIndex} " +
                         "(class=${iface.interfaceClass}, endpoints=${iface.endpointCount})")
                conn.close()
                connection = null
                return ""
            }
            val (epIn, epOut) = endpoints
            Log.i(TAG, "Using endpoints: IN=${epIn.address} OUT=${epOut.address} " +
                       "on interface ${device.interfaceIndex}")

            // Configure serial parameters
            configureBaudRate(conn, device, baudRate)
            enableDevice(conn, device.chipType, device.interfaceIndex)
            applyModemLinesForDevice(conn, device, dtr, rts)
            Log.i(TAG, "Modem lines: DTR=$dtr RTS=$rts")

            // Start native pty bridge
            Log.i(TAG, "Starting pty bridge...")
            val slavePath = nativeStartBridge(conn, epIn, epOut)
            if (slavePath == null) {
                setError("pty bridge failed (posix_openpt may be blocked by SELinux)")
                conn.close()
                connection = null
                return ""
            }

            _state.value = _state.value.copy(
                connectedDevice = device, ptyPath = slavePath, error = ""
            )
            Log.i(TAG, "Bridge active: ${device.displayName} ↔ $slavePath @ $baudRate baud")
            return slavePath

        } catch (e: Exception) {
            Log.e(TAG, "openDeviceInternal failed", e)
            setError("Open failed: ${e.message}")
            return ""
        }
    }

    private fun claimInterfaces(device: UsbSerialDevice, conn: UsbDeviceConnection) {
        claimedInterfaces.clear()
        val usbDev = device.usbDevice

        // Always claim the target data interface
        val targetIface = usbDev.getInterface(device.interfaceIndex)
        if (conn.claimInterface(targetIface, true)) {
            claimedInterfaces.add(targetIface)
            Log.d(TAG, "Claimed target interface ${targetIface.id}")
        }

        // Also claim the CDC control interface if it exists (often interface N-1)
        for (i in 0 until usbDev.interfaceCount) {
            if (i == device.interfaceIndex) continue
            val iface = usbDev.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC) {
                if (conn.claimInterface(iface, true)) {
                    claimedInterfaces.add(iface)
                    Log.d(TAG, "Claimed CDC control interface ${iface.id}")
                }
            }
        }
    }

    private fun findBulkEndpointsOnInterface(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
        }
        return if (epIn != null && epOut != null) Pair(epIn, epOut) else null
    }

    private fun configureBaudRate(conn: UsbDeviceConnection, device: UsbSerialDevice, baudRate: Int) {
        val result = when (device.chipType) {
            ChipType.CDC_ACM -> setCdcBaudRate(conn, baudRate, device)
            ChipType.CP210X -> setCp210xBaudRate(conn, baudRate, device.interfaceIndex)
            ChipType.FTDI -> setFtdiBaudRate(conn, baudRate)
            ChipType.CH340 -> setCh340BaudRate(conn, baudRate)
            ChipType.PROLIFIC -> setCdcBaudRate(conn, baudRate, device)
            ChipType.UNKNOWN -> setCdcBaudRate(conn, baudRate, device)
        }
        Log.i(TAG, "configureBaudRate(${device.chipType}, $baudRate): $result")
    }

    /**
     * Chip-specific initialization that doesn't touch DTR/RTS — those are set
     * separately by [applyModemLinesForDevice] so the user can control them.
     */
    private fun enableDevice(conn: UsbDeviceConnection, chipType: ChipType, ifaceIdx: Int = 0) {
        when (chipType) {
            ChipType.CP210X -> {
                // CP210x: wIndex must be the interface number for all control transfers
                val idx = ifaceIdx
                conn.controlTransfer(0x41, CP210X_IFC_ENABLE, 1, idx, null, 0, 5000)
                conn.controlTransfer(0x41, CP210X_SET_LINE_CTL, 0x0800, idx, null, 0, 5000)
                val noFlow = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                conn.controlTransfer(0x41, 0x13, 0, idx, noFlow, noFlow.size, 5000)
                conn.controlTransfer(0x41, 0x12, 0x000F, idx, null, 0, 5000)
            }
            ChipType.CDC_ACM, ChipType.FTDI, ChipType.CH340, ChipType.PROLIFIC, ChipType.UNKNOWN -> {
                // No chip-specific init beyond baud rate needed here.
            }
        }
    }

    /**
     * Send the DTR / RTS state to a specific device. Used both during open (before
     * `connectedDevice` is set) and by the public `setModemLines()`.
     */
    private fun applyModemLinesForDevice(
        conn: UsbDeviceConnection,
        device: UsbSerialDevice,
        dtr: Boolean,
        rts: Boolean
    ) {
        applyModemLines(conn, device.chipType, device.interfaceIndex, dtr, rts, device.usbDevice)
    }

    private fun applyModemLines(
        conn: UsbDeviceConnection,
        chipType: ChipType,
        ifaceIdx: Int,
        dtr: Boolean,
        rts: Boolean,
        usbDevice: UsbDevice? = _state.value.connectedDevice?.usbDevice
    ) {
        when (chipType) {
            ChipType.CDC_ACM, ChipType.PROLIFIC, ChipType.UNKNOWN -> {
                // USB CDC ACM SET_CONTROL_LINE_STATE: bit 0 = DTR, bit 1 = RTS.
                val value = (if (dtr) 0x01 else 0) or (if (rts) 0x02 else 0)
                val ctrlIface = usbDevice?.let { findControlInterfaceNum(it) } ?: 0
                conn.controlTransfer(0x21, SET_CONTROL_LINE_STATE, value, ctrlIface, null, 0, 5000)
            }
            ChipType.CP210X -> {
                // CP210x SET_MHS: bits 0-1 = DTR/RTS outputs, bits 8-9 = mask.
                val value = (if (dtr) 0x01 else 0) or (if (rts) 0x02 else 0) or 0x0300
                conn.controlTransfer(0x41, 0x07, value, ifaceIdx, null, 0, 5000)
            }
            ChipType.FTDI -> {
                // FTDI SET_MODEM_CTRL: two separate commands, one per line. High byte = mask.
                conn.controlTransfer(0x40, 1, if (dtr) 0x0101 else 0x0100, 0, null, 0, 5000)
                conn.controlTransfer(0x40, 1, if (rts) 0x0202 else 0x0200, 0, null, 0, 5000)
            }
            ChipType.CH340 -> {
                // CH340 modem ctrl (0xA4): value bits are active-low.
                // DTR = bit 5 (0x20), RTS = bit 6 (0x40).
                val control = (if (dtr) 0x20 else 0) or (if (rts) 0x40 else 0)
                conn.controlTransfer(0x40, 0xA4, control.inv() and 0xFFFF, 0, null, 0, 5000)
            }
        }
    }

    // ── Baud rate ─────────────────────────────────────────────

    private fun setCdcBaudRate(conn: UsbDeviceConnection, baudRate: Int, device: UsbSerialDevice): String {
        val data = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(baudRate).put(0).put(0).put(8).array()
        val ctrlIface = findControlInterfaceNum(device.usbDevice)
        val r = conn.controlTransfer(0x21, SET_LINE_CODING, 0, ctrlIface, data, 7, 5000)
        return "CDC SET_LINE_CODING→$r"
    }

    private fun setCp210xBaudRate(conn: UsbDeviceConnection, baudRate: Int, ifaceIdx: Int = 0): String {
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(baudRate).array()
        val r = conn.controlTransfer(0x41, CP210X_SET_BAUDRATE, 0, ifaceIdx, data, 4, 5000)
        return "CP210x SET_BAUDRATE→$r"
    }

    private fun setFtdiBaudRate(conn: UsbDeviceConnection, baudRate: Int): String {
        val divisor = when (baudRate) {
            300 -> 0x2710; 600 -> 0x1388; 1200 -> 0x09C4; 2400 -> 0x04E2
            4800 -> 0x0271; 9600 -> 0x4138; 19200 -> 0x809C; 38400 -> 0xC04E
            57600 -> 0x0034; 115200 -> 0x001A; else -> 0x4138
        }
        val r = conn.controlTransfer(0x40, FTDI_SET_BAUD_RATE,
            divisor and 0xFFFF, (divisor shr 16) and 0xFFFF, null, 0, 5000)
        return "FTDI SET_BAUD→$r"
    }

    /**
     * CH340/CH341 full initialization sequence.
     * Based on Linux ch341.c driver + usb-serial-for-android Ch34xSerialDriver.
     */
    private fun setCh340BaudRate(conn: UsbDeviceConnection, baudRate: Int): String {
        val buf = ByteArray(8)

        // Step 1: Read version (0x5F)
        conn.controlTransfer(0xC0, 0x5F, 0, 0, buf, 8, 5000)
        Log.d(TAG, "CH340 version: ${buf[0].toInt() and 0xFF}, ${buf[1].toInt() and 0xFF}")

        // Step 2: Initial handshake off
        conn.controlTransfer(0x40, 0xA4, 0xFFFF.toInt(), 0, null, 0, 5000)

        // Step 3: Set baud rate divisor (register 0x1312)
        val (factor, divisor) = ch340BaudParams(baudRate)
        val r1 = conn.controlTransfer(0x40, 0x9A, 0x1312, factor or divisor, null, 0, 5000)

        // Step 4: Read register 0x2518 (status check)
        conn.controlTransfer(0xC0, 0x95, 0x2518, 0, buf, 8, 5000)

        // Step 5: Second handshake off
        conn.controlTransfer(0x40, 0xA4, 0xFFFF.toInt(), 0, null, 0, 5000)

        // Step 6: Read register 0x0706
        conn.controlTransfer(0xC0, 0x95, 0x0706, 0, buf, 8, 5000)

        // Step 7: CRITICAL — Serial init with proper params to enable RX/TX
        val r2 = conn.controlTransfer(0x40, 0xA1, 0x501F, 0xD90A, null, 0, 5000)

        // Step 8: Set baud rate again
        conn.controlTransfer(0x40, 0x9A, 0x1312, factor or divisor, null, 0, 5000)

        // Step 9: Set line control 8N1 (register 0x2518, value 0xC3)
        val r3 = conn.controlTransfer(0x40, 0x9A, 0x2518, 0x00C3, null, 0, 5000)

        // DTR / RTS are applied by applyModemLines() after enableDevice().
        // Step 10: Final status check
        conn.controlTransfer(0xC0, 0x95, 0x0706, 0, buf, 8, 5000)

        return "CH340 init→$r1/$r2/$r3"
    }

    private fun ch340BaudParams(baudRate: Int): Pair<Int, Int> {
        // CH340 factor/divisor values from Linux ch341 driver
        return when (baudRate) {
            300    -> Pair(0xD980, 0x0000)
            600    -> Pair(0x6481, 0x0000)
            1200   -> Pair(0xB281, 0x0000)
            2400   -> Pair(0xD981, 0x0000)
            4800   -> Pair(0x6482, 0x0000)
            9600   -> Pair(0xB282, 0x0000)
            19200  -> Pair(0xD982, 0x0000)
            38400  -> Pair(0x6483, 0x0000)
            57600  -> Pair(0x9883, 0x0000)
            115200 -> Pair(0xCC83, 0x0000)
            else   -> Pair(0xB282, 0x0000)
        }
    }

    private fun findControlInterfaceNum(device: UsbDevice? = _state.value.connectedDevice?.usbDevice): Int {
        device ?: return 0
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC && iface.interfaceSubclass == CDC_SUBCLASS_ACM)
                return iface.id
        }
        return 0
    }

    // ── Detection ─────────────────────────────────────────────

    private fun detectChipType(device: UsbDevice): ChipType? {
        when (device.vendorId) {
            VID_SILABS_CP210X -> return ChipType.CP210X
            VID_ICOM -> return ChipType.CDC_ACM
            VID_FTDI -> return ChipType.FTDI
            VID_PROLIFIC -> return ChipType.PROLIFIC
            VID_CH340 -> return ChipType.CH340
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC && iface.interfaceSubclass == CDC_SUBCLASS_ACM)
                return ChipType.CDC_ACM
            if (iface.interfaceClass == USB_CLASS_CDC_DATA)
                return ChipType.CDC_ACM
        }
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC)
                return ChipType.UNKNOWN
        }
        return null
    }

    private fun buildDisplayName(device: UsbDevice, chipType: ChipType, ifaceIdx: Int?): String {
        val product = device.productName ?: ""
        val mfg = device.manufacturerName ?: ""
        val vid = String.format("%04X", device.vendorId)
        val pid = String.format("%04X", device.productId)
        val ifaceLabel = if (ifaceIdx != null) " IF#$ifaceIdx" else ""

        return when {
            product.isNotEmpty() -> "$product$ifaceLabel ($vid:$pid)"
            mfg.isNotEmpty() -> "$mfg ${chipType.name}$ifaceLabel ($vid:$pid)"
            else -> "${chipType.name}$ifaceLabel ($vid:$pid)"
        }
    }

    private fun setError(msg: String) {
        Log.e(TAG, msg)
        _state.value = _state.value.copy(error = msg)
    }
}

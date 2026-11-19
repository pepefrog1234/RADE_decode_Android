package yakumo2683.RADEdecode.network

/**
 * Manufacturers whose rigs are given plain LSB/USB instead of PKTLSB/PKTUSB.
 *
 * Xiegu, Alinco, Drake, AOR, JRC: taken to have no PKTLSB/PKTUSB, as before.
 *
 * QRP Labs: Hamlib 4.7 treats these rigs as a TS-480 emulation and sends
 * PKTLSB/PKTUSB as "MD6;"/"MD9;". On a QMX that is FSK, its single-tone Digi
 * mode, which cannot transmit a RADE signal; LSB and USB select SSB.
 */
private val SIDEBAND_ONLY_MANUFACTURERS = setOf("Xiegu", "Alinco", "Drake", "AOR", "JRC", "QRP Labs")

/**
 * The mode the app sets after it has tuned the rig to [hz]: lower sideband
 * below 10 MHz, upper sideband above, as a data mode where the rig has one.
 *
 * @param manufacturer the manufacturer the selected rig model is listed under
 */
internal fun rigModeAfterTuning(hz: Long, manufacturer: String): String {
    val sideband = if (hz < 10_000_000L) "LSB" else "USB"
    return if (manufacturer in SIDEBAND_ONLY_MANUFACTURERS) sideband else "PKT$sideband"
}

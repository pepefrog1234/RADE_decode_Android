package yakumo2683.RADEdecode.ui

import kotlin.math.abs

/*
 * Receiver-screen indicators that only the RADE V2 modem provides: the tuning
 * hint (rade_mistune) and the sound-card clock offset (rade_clock_offset_ppm).
 * Kept free of Android types so the rules are unit tested on the JVM.
 */

/** The sideband the receiver feeding the modem demodulates. */
enum class RxSideband { UPPER, LOWER, UNKNOWN }

/** What the receiver screen shows next to the sync indicator. */
enum class TuneHint {
    NONE,
    /** Raise the receiver's dial frequency. */
    TUNE_UP,
    /** Lower the receiver's dial frequency. */
    TUNE_DOWN,
    /** Sideband unknown: only the audio-frequency error can be stated. */
    SIGNAL_TOO_HIGH,
    SIGNAL_TOO_LOW
}

/**
 * Hamlib mode names with an unambiguous sideband: plain SSB, the packet/data
 * variants the app itself selects (PKTUSB/PKTLSB), ECSS and Kenwood's
 * USB-D1…3 / LSB-D1…3. CW, RTTY, AM, FM, DSB, ISB… stay unknown — their
 * audio-to-dial relationship differs between radios.
 */
private val SSB_MODE = Regex("(?:PKT|ECSS)?(USB|LSB)(?:-D[0-9])?")

/**
 * Sideband of the receiver the modem is listening to.
 *
 * @param hermesDirect a Hermes-Lite 2 is connected directly: the app
 *   demodulates its I/Q stream itself, always as upper sideband.
 * @param rigConnected CAT (rigctld) is connected, so [rigMode] is live.
 * @param rigMode the Hamlib mode name last read from the rig ("" before the
 *   first read).
 */
internal fun rxSideband(hermesDirect: Boolean, rigConnected: Boolean, rigMode: String): RxSideband {
    if (hermesDirect) return RxSideband.UPPER
    if (!rigConnected) return RxSideband.UNKNOWN
    return when (SSB_MODE.matchEntire(rigMode.trim().uppercase())?.groupValues?.get(1)) {
        "USB" -> RxSideband.UPPER
        "LSB" -> RxSideband.LOWER
        else -> RxSideband.UNKNOWN
    }
}

/**
 * The tuning hint for the RADE V2 tuning indicator.
 *
 * [mistune] is `rade_mistune()`: +1 when the received signal is too high in
 * audio frequency, -1 when too low, 0 when the modem has no indication. An
 * SSB receiver on USB puts a signal at (signal − dial) Hz of audio, so audio
 * that is too high needs the dial tuned up; on LSB audio is (dial − signal)
 * and the correction is reversed.
 *
 * Nothing is shown outside V2 mode, while not receiving, or in sync: the
 * sync state arrives by callback and [mistune] by polling, so a stale
 * indication can briefly coexist with SYNC.
 */
internal fun tuneHintFor(
    radeV2: Boolean,
    receiving: Boolean,
    synced: Boolean,
    mistune: Int,
    sideband: RxSideband
): TuneHint {
    if (!radeV2 || !receiving || synced || mistune == 0) return TuneHint.NONE
    val tooHigh = mistune > 0
    return when (sideband) {
        RxSideband.UPPER -> if (tooHigh) TuneHint.TUNE_UP else TuneHint.TUNE_DOWN
        RxSideband.LOWER -> if (tooHigh) TuneHint.TUNE_DOWN else TuneHint.TUNE_UP
        RxSideband.UNKNOWN -> if (tooHigh) TuneHint.SIGNAL_TOO_HIGH else TuneHint.SIGNAL_TOO_LOW
    }
}

/**
 * RADE V2 is specified for no significant loss up to this Tx/Rx sample clock
 * offset; a larger one usually means out-of-spec audio hardware at one of
 * the two stations.
 */
internal const val CLOCK_OFFSET_SPEC_PPM = 125f

/**
 * Whether the receiver screen shows the V2 clock offset. The modem reports
 * exactly 0 until its timing loop has moved after an acquisition (and always
 * on V1), which is "no estimate" rather than a perfect match.
 */
internal fun shouldShowClockOffset(radeV2: Boolean, receiving: Boolean, clockOffsetPpm: Float): Boolean =
    radeV2 && receiving && clockOffsetPpm.isFinite() && clockOffsetPpm != 0f

/** True when the estimated clock offset is outside what V2 is specified for. */
internal fun clockOffsetOutOfSpec(clockOffsetPpm: Float): Boolean =
    abs(clockOffsetPpm) > CLOCK_OFFSET_SPEC_PPM

/** Whole ppm with an explicit sign ("+37", "-180"), and a plain "0". */
internal fun formatClockOffsetPpm(clockOffsetPpm: Float): String {
    val ppm = Math.round(clockOffsetPpm)  // total: unlike roundToInt() it does not throw on NaN
    return if (ppm > 0) "+$ppm" else "$ppm"
}

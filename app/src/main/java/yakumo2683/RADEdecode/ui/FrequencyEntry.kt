package yakumo2683.RADEdecode.ui

import java.math.BigDecimal

/** Editable kHz text is distinct from the rig's polled dial frequency. */
internal data class FrequencyEntry(
    val text: String = "",
    val dirty: Boolean = false,
    val submittedHz: Long? = null
) {
    val frequencyHz: Long?
        get() {
            val normalized = text.trim().replace(',', '.')
            if (!normalized.matches(Regex("[0-9]+(?:\\.[0-9]{0,3})?"))) return null
            return try {
                BigDecimal(normalized).movePointRight(3).longValueExact()
                    .takeIf { it in 10_000L..1_300_000_000L }
            } catch (_: ArithmeticException) { null }
              catch (_: NumberFormatException) { null }
        }

    fun edit(value: String) = copy(text = value, dirty = true, submittedHz = null)
    fun submitted() = copy(dirty = true, submittedHz = frequencyHz)

    fun observe(hz: Long): FrequencyEntry {
        if (hz <= 0 || (dirty && submittedHz != hz)) return this
        // Always use a decimal point and preserve 1 Hz precision. Locale-based
        // formatting could produce a comma that the old input filter deleted.
        return FrequencyEntry(BigDecimal.valueOf(hz, 3).stripTrailingZeros().toPlainString())
    }
}

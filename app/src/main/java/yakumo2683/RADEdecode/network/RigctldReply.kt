package yakumo2683.RADEdecode.network

/** Single-line extended reply to `;T n`. Match both the command and its
 * argument: a late status value or an unrelated RPRT is not a PTT reply. */
internal fun rigctldPttResult(reply: String?, on: Boolean): Int? {
    val value = if (on) 1 else 0
    val match = Regex("set_ptt:\\s*$value\\s*;\\s*RPRT\\s+(-?\\d+)")
        .matchEntire(reply?.trim() ?: return null) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Errors, missing replies and unrelated CAT values must not become PTT ON. */
internal fun rigctldPtt(reply: String?): Boolean? = when (reply?.trim()) {
    "0" -> false
    "1", "2", "3" -> true // ON, ON_MIC, ON_DATA (Hamlib ptt_t)
    else -> null
}

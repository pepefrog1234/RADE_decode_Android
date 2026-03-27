package yakumo2683.RADEdecode.data

/**
 * Data classes for reception logging.
 * Plain Kotlin data classes (no Room annotations) — persisted via raw SQLite.
 */

data class ReceptionSession(
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val audioDevice: String = "",
    val sampleRateHz: Int = 8000,
    val audioFilename: String? = null,
    val audioFileSize: Long? = null,
    val totalModemFrames: Int = 0,
    val syncedFrames: Int = 0,
    val peakSnr: Float? = null,
    val avgSnr: Float? = null,
    val notes: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null
)

data class SignalSnapshot(
    val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val snr: Float,
    val freqOffset: Float,
    val syncState: Int,
    val inputLevelDb: Float,
    val outputLevelDb: Float
)

data class SyncEvent(
    val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val fromState: Int,
    val toState: Int,
    val snrAtEvent: Int,
    val freqOffsetAtEvent: Float
)

data class CallsignEvent(
    val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val callsign: String,
    val snrAtDecode: Int,
    val modemFrame: Int,
    val latitude: Double? = null,
    val longitude: Double? = null
)

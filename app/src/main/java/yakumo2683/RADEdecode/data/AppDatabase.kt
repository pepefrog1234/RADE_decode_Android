package yakumo2683.RADEdecode.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite database for reception logging.
 * Uses raw SQLite instead of Room to avoid KSP/annotation processor
 * compatibility issues with AGP 9.x's built-in Kotlin.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "rade_decode.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE reception_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startTime INTEGER NOT NULL,
                endTime INTEGER,
                audioDevice TEXT DEFAULT '',
                sampleRateHz INTEGER DEFAULT 8000,
                audioFilename TEXT,
                audioFileSize INTEGER,
                totalModemFrames INTEGER DEFAULT 0,
                syncedFrames INTEGER DEFAULT 0,
                peakSnr REAL,
                avgSnr REAL,
                notes TEXT,
                latitude REAL,
                longitude REAL,
                altitude REAL
            )
        """)

        db.execSQL("""
            CREATE TABLE signal_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL,
                snr REAL NOT NULL,
                freqOffset REAL NOT NULL,
                syncState INTEGER NOT NULL,
                inputLevelDb REAL NOT NULL,
                outputLevelDb REAL NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES reception_sessions(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX idx_snapshots_session ON signal_snapshots(sessionId)")

        db.execSQL("""
            CREATE TABLE sync_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL,
                fromState INTEGER NOT NULL,
                toState INTEGER NOT NULL,
                snrAtEvent INTEGER NOT NULL,
                freqOffsetAtEvent REAL NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES reception_sessions(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX idx_sync_session ON sync_events(sessionId)")

        db.execSQL("""
            CREATE TABLE callsign_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId INTEGER NOT NULL,
                offsetMs INTEGER NOT NULL,
                callsign TEXT NOT NULL,
                snrAtDecode INTEGER NOT NULL,
                modemFrame INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                FOREIGN KEY (sessionId) REFERENCES reception_sessions(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX idx_callsign_session ON callsign_events(sessionId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /* ── Delete operations ─────────────────────────────────────── */

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("reception_sessions", "id = ?", arrayOf(sessionId.toString()))
    }

    suspend fun deleteAllSessions() = withContext(Dispatchers.IO) {
        writableDatabase.delete("reception_sessions", null, null)
    }

    /* ── Session operations ──────────────────────────────────── */

    suspend fun insertSession(session: ReceptionSession): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("startTime", session.startTime)
            put("audioDevice", session.audioDevice)
            put("sampleRateHz", session.sampleRateHz)
        }
        writableDatabase.insert("reception_sessions", null, cv)
    }

    suspend fun updateSessionEnd(sessionId: Long, endTime: Long, totalFrames: Int, syncedFrames: Int) =
        withContext(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put("endTime", endTime)
                put("totalModemFrames", totalFrames)
                put("syncedFrames", syncedFrames)
            }
            writableDatabase.update("reception_sessions", cv, "id = ?", arrayOf(sessionId.toString()))
        }

    suspend fun closeOrphanedSessions() = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("endTime", System.currentTimeMillis())
        }
        writableDatabase.update("reception_sessions", cv, "endTime IS NULL", null)
    }

    suspend fun updateSessionAudio(sessionId: Long, filename: String, fileSize: Long) =
        withContext(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put("audioFilename", filename)
                put("audioFileSize", fileSize)
            }
            writableDatabase.update("reception_sessions", cv, "id = ?", arrayOf(sessionId.toString()))
        }

    private fun cursorToSession(it: android.database.Cursor): ReceptionSession {
        return ReceptionSession(
            id = it.getLong(it.getColumnIndexOrThrow("id")),
            startTime = it.getLong(it.getColumnIndexOrThrow("startTime")),
            endTime = it.getLongOrNull("endTime"),
            audioDevice = it.getString(it.getColumnIndexOrThrow("audioDevice")) ?: "",
            sampleRateHz = it.getInt(it.getColumnIndexOrThrow("sampleRateHz")),
            audioFilename = it.getStringOrNull("audioFilename"),
            audioFileSize = it.getLongOrNull("audioFileSize"),
            totalModemFrames = it.getInt(it.getColumnIndexOrThrow("totalModemFrames")),
            syncedFrames = it.getInt(it.getColumnIndexOrThrow("syncedFrames"))
        )
    }

    suspend fun getAllSessions(): List<ReceptionSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<ReceptionSession>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM reception_sessions ORDER BY startTime DESC", null
        )
        cursor.use { while (it.moveToNext()) sessions.add(cursorToSession(it)) }
        sessions
    }

    suspend fun getSession(sessionId: Long): ReceptionSession? = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM reception_sessions WHERE id = ?", arrayOf(sessionId.toString())
        )
        cursor.use { if (it.moveToFirst()) cursorToSession(it) else null }
    }

    /* ── Signal Snapshot operations ───────────────────────────── */

    suspend fun insertSnapshot(snapshot: SignalSnapshot) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("sessionId", snapshot.sessionId)
            put("offsetMs", snapshot.offsetMs)
            put("snr", snapshot.snr)
            put("freqOffset", snapshot.freqOffset)
            put("syncState", snapshot.syncState)
            put("inputLevelDb", snapshot.inputLevelDb)
            put("outputLevelDb", snapshot.outputLevelDb)
        }
        writableDatabase.insert("signal_snapshots", null, cv)
    }

    suspend fun getSnapshots(sessionId: Long): List<SignalSnapshot> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SignalSnapshot>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM signal_snapshots WHERE sessionId = ? ORDER BY offsetMs",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(SignalSnapshot(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    sessionId = it.getLong(it.getColumnIndexOrThrow("sessionId")),
                    offsetMs = it.getLong(it.getColumnIndexOrThrow("offsetMs")),
                    snr = it.getFloat(it.getColumnIndexOrThrow("snr")),
                    freqOffset = it.getFloat(it.getColumnIndexOrThrow("freqOffset")),
                    syncState = it.getInt(it.getColumnIndexOrThrow("syncState")),
                    inputLevelDb = it.getFloat(it.getColumnIndexOrThrow("inputLevelDb")),
                    outputLevelDb = it.getFloat(it.getColumnIndexOrThrow("outputLevelDb"))
                ))
            }
        }
        list
    }

    /* ── Sync Event operations ───────────────────────────────── */

    suspend fun insertSyncEvent(event: SyncEvent) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("sessionId", event.sessionId)
            put("offsetMs", event.offsetMs)
            put("fromState", event.fromState)
            put("toState", event.toState)
            put("snrAtEvent", event.snrAtEvent)
            put("freqOffsetAtEvent", event.freqOffsetAtEvent)
        }
        writableDatabase.insert("sync_events", null, cv)
    }

    suspend fun getSyncEvents(sessionId: Long): List<SyncEvent> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SyncEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM sync_events WHERE sessionId = ? ORDER BY offsetMs",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(SyncEvent(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    sessionId = it.getLong(it.getColumnIndexOrThrow("sessionId")),
                    offsetMs = it.getLong(it.getColumnIndexOrThrow("offsetMs")),
                    fromState = it.getInt(it.getColumnIndexOrThrow("fromState")),
                    toState = it.getInt(it.getColumnIndexOrThrow("toState")),
                    snrAtEvent = it.getInt(it.getColumnIndexOrThrow("snrAtEvent")),
                    freqOffsetAtEvent = it.getFloat(it.getColumnIndexOrThrow("freqOffsetAtEvent"))
                ))
            }
        }
        list
    }

    suspend fun getCallsignEvents(sessionId: Long): List<CallsignEvent> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CallsignEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM callsign_events WHERE sessionId = ? ORDER BY offsetMs",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(CallsignEvent(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    sessionId = it.getLong(it.getColumnIndexOrThrow("sessionId")),
                    offsetMs = it.getLong(it.getColumnIndexOrThrow("offsetMs")),
                    callsign = it.getString(it.getColumnIndexOrThrow("callsign")) ?: "",
                    snrAtDecode = it.getInt(it.getColumnIndexOrThrow("snrAtDecode")),
                    modemFrame = it.getInt(it.getColumnIndexOrThrow("modemFrame"))
                ))
            }
        }
        list
    }

    /* ── Callsign Event operations (insert) ──────────────────── */

    suspend fun insertCallsignEvent(event: CallsignEvent) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("sessionId", event.sessionId)
            put("offsetMs", event.offsetMs)
            put("callsign", event.callsign)
            put("snrAtDecode", event.snrAtDecode)
            put("modemFrame", event.modemFrame)
        }
        writableDatabase.insert("callsign_events", null, cv)
    }

    /* ── Helpers ──────────────────────────────────────────────── */

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }
}

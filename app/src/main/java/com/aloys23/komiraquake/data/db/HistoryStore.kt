package com.aloys23.komiraquake.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel

/** SQLite 历史存储。表结构与《NATIVE_PORT_SPEC》 §6 一致。使用框架 SQLite，避免额外依赖。 */
class HistoryStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    fun loadRecent(limit: Int = 200): List<EarthquakeEvent> {
        val out = ArrayList<EarthquakeEvent>()
        helper.readableDatabase.rawQuery(
            "SELECT id, source, magnitude, latitude, longitude, depth, location, timestamp, " +
                "distance_km, estimated_intensity, raw_intensity, p_wave_arrival, s_wave_arrival, " +
                "warning_level, report_num, is_final, is_canceled, source_provider, source_agency, " +
                "event_id, max_intensity_raw, max_intensity_text, source_updated_at " +
                "FROM events ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    EarthquakeEvent(
                        id = c.getString(0),
                        source = c.getString(1),
                        magnitude = c.getDouble(2),
                        latitude = c.getDouble(3),
                        longitude = c.getDouble(4),
                        depth = c.getDouble(5),
                        location = c.getString(6),
                        timestamp = c.getLong(7),
                        distanceKm = c.getDouble(8),
                        estimatedIntensity = c.getString(9),
                        rawIntensity = c.getDouble(10),
                        pWaveArrival = if (c.isNull(11)) null else c.getLong(11),
                        sWaveArrival = if (c.isNull(12)) null else c.getLong(12),
                        warningLevel = WarningLevel.fromCode(c.getInt(13)),
                        reportNum = c.getInt(14),
                        isFinal = c.getInt(15) != 0,
                        isCanceled = c.getInt(16) != 0,
                        sourceProvider = c.getString(17),
                        sourceAgency = c.getString(18),
                        eventId = c.getString(19),
                        maxIntensityRaw = c.getDouble(20),
                        maxIntensityText = c.getString(21),
                        sourceUpdatedAt = if (c.isNull(22)) null else c.getLong(22),
                    ),
                )
            }
        }
        return out
    }

    fun upsertAll(events: List<EarthquakeEvent>) {
        if (events.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (e in events) db.insertWithOnConflict(TABLE, null, toValues(e), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun prune(keep: Int = 400) {
        helper.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY timestamp DESC LIMIT ?)",
            arrayOf(keep),
        )
    }

    fun clear() {
        helper.writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    /** Alert tombstones are separate from user-clearable catalogue history. Values are epoch expiry. */
    fun loadTerminals(now: Long): Map<String, Long> {
        val db = helper.writableDatabase
        db.delete("alert_terminals", "expires_at <= ?", arrayOf(now.toString()))
        return buildMap {
            db.rawQuery("SELECT identity, expires_at FROM alert_terminals", null).use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
            }
        }
    }

    fun saveTerminal(identity: String, expiresAt: Long) {
        helper.writableDatabase.execSQL(
            "INSERT INTO alert_terminals(identity, expires_at) VALUES (?, ?) " +
                "ON CONFLICT(identity) DO UPDATE SET expires_at = MAX(expires_at, excluded.expires_at)",
            arrayOf<Any>(identity, expiresAt),
        )
    }

    fun close() = helper.close()

    private fun toValues(e: EarthquakeEvent) = ContentValues().apply {
        put("id", e.id)
        put("source", e.source)
        put("event_id", e.eventId)
        put("max_intensity_raw", e.maxIntensityRaw)
        put("max_intensity_text", e.maxIntensityText)
        put("source_updated_at", e.sourceUpdatedAt)
        put("source_provider", e.sourceProvider)
        put("source_agency", e.sourceAgency)
        put("magnitude", e.magnitude)
        put("latitude", e.latitude)
        put("longitude", e.longitude)
        put("depth", e.depth)
        put("location", e.location)
        put("timestamp", e.timestamp)
        put("distance_km", e.distanceKm)
        put("estimated_intensity", e.estimatedIntensity)
        put("raw_intensity", e.rawIntensity)
        put("p_wave_arrival", e.pWaveArrival)
        put("s_wave_arrival", e.sWaveArrival)
        put("warning_level", e.warningLevel.code)
        put("report_num", e.reportNum)
        put("is_final", if (e.isFinal) 1 else 0)
        put("is_canceled", if (e.isCanceled) 1 else 0)
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, SCHEMA_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            createTerminals(db)
            db.execSQL(
                """
                CREATE TABLE events (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    magnitude REAL NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    depth REAL NOT NULL,
                    location TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    distance_km REAL NOT NULL,
                    estimated_intensity TEXT NOT NULL,
                    raw_intensity REAL NOT NULL,
                    p_wave_arrival INTEGER,
                    s_wave_arrival INTEGER,
                    warning_level INTEGER NOT NULL,
                    report_num INTEGER NOT NULL,
                    is_final INTEGER NOT NULL,
                    is_canceled INTEGER NOT NULL,
                    source_provider TEXT NOT NULL DEFAULT '',
                    source_agency TEXT NOT NULL DEFAULT '',
                    event_id TEXT NOT NULL DEFAULT '',
                    max_intensity_raw REAL NOT NULL DEFAULT 0,
                    max_intensity_text TEXT NOT NULL DEFAULT '',
                    source_updated_at INTEGER
                )
                """.trimIndent(),
            )
        }

        private fun createTerminals(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE alert_terminals (identity TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 4 && newVersion >= 4) createTerminals(db)
            // SQLiteOpenHelper wraps upgrades in a transaction; never hide migration failures.
            if (oldVersion < 2 && newVersion >= 2) {
                db.execSQL("ALTER TABLE events ADD COLUMN source_provider TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN source_agency TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 3 && newVersion >= 3) {
                db.execSQL("ALTER TABLE events ADD COLUMN event_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN max_intensity_raw REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN max_intensity_text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN source_updated_at INTEGER")
            }
        }
    }

    companion object {
        const val DB_NAME = "komira_quake_history.db"
        const val SCHEMA_VERSION = 4
        const val TABLE = "events"
        const val DEFAULT_KEEP = 400
        const val DEFAULT_LIMIT = 200
    }
}

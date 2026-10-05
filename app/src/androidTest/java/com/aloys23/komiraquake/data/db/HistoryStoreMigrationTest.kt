package com.aloys23.komiraquake.data.db

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import com.aloys23.komiraquake.model.EarthquakeEvent

/** Real framework SQLite tests; run on a device/emulator, never against the application's DB. */
class HistoryStoreMigrationTest {
    private fun isolatedContext(prefix: String): Context = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = super.getDatabasePath(prefix + name)
        override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).also { it.parentFile?.mkdirs() }, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).also { it.parentFile?.mkdirs() }.absolutePath, factory, errorHandler)
    }

    @Test fun testVersionTwoMigrationPreservesHistoryAndRoundTripsCorrections() {
        val isolated = isolatedContext("history_migration_")
        isolated.deleteDatabase(HistoryStore.DB_NAME)
        isolated.openOrCreateDatabase(HistoryStore.DB_NAME, 0, null).use { db ->
            db.execSQL("""
                CREATE TABLE events (
                    id TEXT PRIMARY KEY, source TEXT NOT NULL, magnitude REAL NOT NULL,
                    latitude REAL NOT NULL, longitude REAL NOT NULL, depth REAL NOT NULL,
                    location TEXT NOT NULL, timestamp INTEGER NOT NULL, distance_km REAL NOT NULL,
                    estimated_intensity TEXT NOT NULL, raw_intensity REAL NOT NULL,
                    p_wave_arrival INTEGER, s_wave_arrival INTEGER, warning_level INTEGER NOT NULL,
                    report_num INTEGER NOT NULL, is_final INTEGER NOT NULL, is_canceled INTEGER NOT NULL,
                    source_provider TEXT NOT NULL DEFAULT '', source_agency TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO events VALUES ('legacy', 'CENC', 4.5, 30, 100, 10, 'legacy location',
                    1000, 20, 'II', 2, NULL, NULL, 0, 1, 0, 0, 'Wolfx', 'CENC')
            """.trimIndent())
            db.version = 2
        }
        // HistoryStore uses applicationContext; retain the isolated context for that access too.
        val wrapper = object : android.content.ContextWrapper(isolated) {
            override fun getApplicationContext(): android.content.Context = this
        }
        try {
            val store = HistoryStore(wrapper)
            val legacy = store.loadRecent().single()
            assertEquals("legacy location", legacy.location)
            assertEquals("", legacy.eventId)
            assertEquals(0.0, legacy.maxIntensityRaw, 0.0)
            assertEquals("", legacy.maxIntensityText)
            assertNull(legacy.sourceUpdatedAt)
            val correction = legacy.copy(eventId = "provider-id", maxIntensityRaw = 5.5,
                maxIntensityText = "5+", sourceUpdatedAt = 2000, isCanceled = true)
            store.upsertAll(listOf(correction))
            store.close()
            val reopened = HistoryStore(wrapper)
            try { assertEquals(correction, reopened.loadRecent().single()) }
            finally { reopened.close() }
        } finally { isolated.deleteDatabase(HistoryStore.DB_NAME) }
    }

    @Test fun testVersionThreeAddsDurableIsolatedTerminalsWithoutClearingHistory() {
        val isolated = isolatedContext("terminal_migration_")
        isolated.deleteDatabase(HistoryStore.DB_NAME)
        try {
            val event = EarthquakeEvent(id = "legacy", eventId = "same", source = "test",
                sourceProvider = "Wolfx", sourceAgency = "CENC", magnitude = 4.0,
                latitude = 30.0, longitude = 100.0, depth = 10.0, location = "test",
                timestamp = 1000, distanceKm = -1.0, estimatedIntensity = "--")
            HistoryStore(isolated).also { it.upsertAll(listOf(event)); it.close() }
            isolated.openOrCreateDatabase(HistoryStore.DB_NAME, 0, null).use { db ->
                db.execSQL("DROP TABLE alert_terminals")
                db.version = 3
            }
            val store = HistoryStore(isolated)
            assertEquals(event, store.loadRecent().single())
            assertTrue(store.loadTerminals(1000).isEmpty())
            store.saveTerminal(event.identity, 10_000)
            store.saveTerminal(event.identity, 9000) // delayed write cannot shorten retention
            store.saveTerminal(event.copy(sourceAgency = "JMA").identity, 20_000)
            store.clear() // catalogue actions never clear alert terminal state
            store.close()
            val reopened = HistoryStore(isolated)
            try {
                val restored = reopened.loadTerminals(1000)
                assertEquals(10_000L, restored[event.identity])
                assertEquals(2, restored.size)
                assertEquals(mapOf(event.copy(sourceAgency = "JMA").identity to 20_000L),
                    reopened.loadTerminals(10_000))
            } finally { reopened.close() }
        } finally { isolated.deleteDatabase(HistoryStore.DB_NAME) }
    }

    @Test fun testFreshDatabaseRoundTripsNullableSourceTime() {
        val isolated = isolatedContext("history_fresh_")
        val wrapper = object : android.content.ContextWrapper(isolated) {
            override fun getApplicationContext(): android.content.Context = this
        }
        isolated.deleteDatabase(HistoryStore.DB_NAME)
        val store = HistoryStore(wrapper)
        try {
            val event = EarthquakeEvent(id = "fresh", eventId = "original", source = "test",
                magnitude = 4.0, latitude = 30.0, longitude = 100.0, depth = 10.0,
                location = "test", timestamp = 1000, distanceKm = 0.0, estimatedIntensity = "未知",
                maxIntensityRaw = 4.0, maxIntensityText = "IV")
            store.upsertAll(listOf(event))
            assertEquals(event, store.loadRecent().single())
        } finally { store.close(); isolated.deleteDatabase(HistoryStore.DB_NAME) }
    }
}

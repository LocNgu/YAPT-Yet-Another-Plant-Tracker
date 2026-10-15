package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest17To18 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `migration adds the three away mode tables and leaves existing plants untouched`() {
        helper.createDatabase(TEST_DB, 17).use(::insertV17Plant)
        val db = helper.runMigrationsAndValidate(TEST_DB, 18, true, PlantDatabase.MIGRATION_17_18)

        db.query("SELECT name, wateringIntervalDays, repottingSeasons FROM plants WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Cactus", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            assertEquals("SPRING", cursor.getString(2))
        }
        assertEquals(
            listOf("id", "firstDay", "lastDay", "visitEveryDays", "createdAt"),
            columns(db, "away_periods")
        )
        assertEquals(listOf("id", "awayPeriodId", "day", "state", "createdAt"), columns(db, "sitter_visits"))
        assertEquals(listOf("visitId", "plantId", "watered"), columns(db, "sitter_visit_plants"))
        assertEquals(listOf("visitId", "plantId"), primaryKey(db, "sitter_visit_plants"))
        assertEquals(setOf("away_periods"), foreignKeyTables(db, "sitter_visits"))
        assertEquals(setOf("sitter_visits", "plants"), foreignKeyTables(db, "sitter_visit_plants"))
        assertTrue("index_sitter_visits_awayPeriodId" in indices(db, "sitter_visits"))
        assertTrue("index_sitter_visit_plants_plantId" in indices(db, "sitter_visit_plants"))
        db.close()
    }

    @Test
    fun `migrated tables accept rows and cascade deletes`() {
        helper.createDatabase(TEST_DB, 17).use(::insertV17Plant)
        val db = helper.runMigrationsAndValidate(TEST_DB, 18, true, PlantDatabase.MIGRATION_17_18)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO away_periods (id, firstDay, lastDay, visitEveryDays, createdAt) VALUES (1, 20000, 20010, 5, 1)"
        )
        db.execSQL(
            "INSERT INTO sitter_visits (id, awayPeriodId, day, state, createdAt) VALUES (1, 1, 20004, 'PLANNED', 1)"
        )
        db.execSQL("INSERT INTO sitter_visit_plants (visitId, plantId, watered) VALUES (1, 1, NULL)")

        db.execSQL("DELETE FROM away_periods WHERE id = 1")

        assertEquals(0, count(db, "sitter_visits"))
        assertEquals(0, count(db, "sitter_visit_plants"))
        assertEquals(1, count(db, "plants"))
        db.close()
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun primaryKey(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val keyed = mutableListOf<Pair<Int, String>>()
            while (cursor.moveToNext()) {
                val position = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                if (position > 0) keyed += position to cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            keyed.sortedBy { it.first }.map { it.second }
        }

    private fun foreignKeyTables(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table"))) }
        }

    private fun indices(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun insertV17Plant(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth, fertilizingSeasons, " +
                "dormantWateringIntervalDays, repotPlanSeasonStartAt, repotPlanMadeAt, repottingSeasons) VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, 360, " +
                "NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'SPRING')"
        )
    }

    companion object {
        private const val TEST_DB = "migration-17-18"
    }
}

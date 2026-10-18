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
    fun `migration leaves existing rows untouched and starts the new tables empty`() {
        helper.createDatabase(TEST_DB, 17).use(::insertV17Rows)
        val db = helper.runMigrationsAndValidate(TEST_DB, 18, true, PlantDatabase.MIGRATION_17_18)

        db.query("SELECT name, wateringIntervalDays, repottingSeasons FROM plants WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Cactus", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            assertEquals("SPRING", cursor.getString(2))
        }
        db.query("SELECT careType, loggedAt FROM care_logs WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WATER", cursor.getString(0))
            assertEquals(2000L, cursor.getLong(1))
        }
        listOf("away_periods", "sitter_visits", "sitter_visit_plants").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals("$table should start empty", 0, cursor.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun `the new tables have the expected columns, keys and indices`() {
        helper.createDatabase(TEST_DB, 17).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 18, true, PlantDatabase.MIGRATION_17_18)

        assertEquals(
            listOf("id", "firstDay", "lastDay", "visitEveryDays", "createdAt"),
            columns(db, "away_periods")
        )
        assertEquals(listOf("id", "awayPeriodId", "day", "state", "createdAt"), columns(db, "sitter_visits"))
        assertEquals(listOf("visitId", "plantId", "watered"), columns(db, "sitter_visit_plants"))

        assertEquals(emptyList<String>(), foreignKeys(db, "away_periods"))
        assertEquals(listOf("away_periods(awayPeriodId->id) CASCADE"), foreignKeys(db, "sitter_visits"))
        assertEquals(
            listOf("sitter_visits(visitId->id) CASCADE", "plants(plantId->id) CASCADE"),
            foreignKeys(db, "sitter_visit_plants").sortedByDescending { it.startsWith("sitter") }
        )

        assertEquals(listOf("visitId", "plantId"), primaryKey(db, "sitter_visit_plants"))
        assertEquals(listOf("index_sitter_visits_awayPeriodId"), indices(db, "sitter_visits"))
        assertTrue(indices(db, "sitter_visit_plants").contains("index_sitter_visit_plants_plantId"))
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

    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val parent = cursor.getString(cursor.getColumnIndexOrThrow("table"))
                    val from = cursor.getString(cursor.getColumnIndexOrThrow("from"))
                    val to = cursor.getString(cursor.getColumnIndexOrThrow("to"))
                    val onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
                    add("$parent($from->$to) $onDelete")
                }
            }
        }

    private fun indices(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name.startsWith("index_")) add(name)
                }
            }
        }

    private fun insertV17Rows(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                "(1, 1, 'WATER', 2000, NULL, NULL, NULL, NULL, 'UNSPECIFIED', NULL)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-17-18"
    }
}

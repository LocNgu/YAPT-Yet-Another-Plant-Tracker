package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest20To21 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `database version is 21`() {
        assertEquals(21, PlantDatabase.DB_VERSION)
    }

    @Test
    fun `migration drops amount and keeps every other care log column`() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertPlant(db, 1)
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                    "(1, 1, 'WATER', 2000, 'Soaked', NULL, '250 ml', 'TOO_LATE', 'UNSPECIFIED', NULL)"
            )
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                    "(2, 1, 'FERTILIZE', 3000, NULL, 'content://media/old.jpg', '5 g', NULL, 'LIQUID', NULL)"
            )
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                    "(3, 1, 'CUSTOM', 4000, NULL, NULL, NULL, NULL, 'UNSPECIFIED', 77)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true, PlantDatabase.MIGRATION_20_21)

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(care_logs)").use {
            while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
        }
        assertFalse("amount is dropped", "amount" in columns)
        assertEquals(
            listOf(
                "id", "plantId", "careType", "loggedAt", "notes", "photoUri",
                "wateringFeedback", "fertilizerType", "customReminderId"
            ),
            columns
        )

        db.query(
            "SELECT plantId, careType, loggedAt, notes, photoUri, wateringFeedback, fertilizerType, " +
                "customReminderId FROM care_logs ORDER BY id"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals("WATER", it.getString(1))
            assertEquals(2000L, it.getLong(2))
            assertEquals("Soaked", it.getString(3))
            assertTrue(it.isNull(4))
            assertEquals("TOO_LATE", it.getString(5))
            assertEquals("UNSPECIFIED", it.getString(6))
            assertTrue(it.isNull(7))

            assertTrue(it.moveToNext())
            assertEquals("FERTILIZE", it.getString(1))
            assertEquals("a legacy photo survives on a non-photo log", "content://media/old.jpg", it.getString(4))
            assertTrue(it.isNull(5))
            assertEquals("LIQUID", it.getString(6))

            assertTrue(it.moveToNext())
            assertEquals("CUSTOM", it.getString(1))
            assertEquals(77L, it.getLong(7))
            assertFalse(it.moveToNext())
        }
        db.close()
    }

    @Test
    fun `migrated care_logs keeps its plantId index and cascade delete`() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertPlant(db, 1)
            insertPlant(db, 2)
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, amount, fertilizerType) VALUES " +
                    "(1, 1, 'WATER', 2000, '1 cup', 'UNSPECIFIED'), (2, 2, 'WATER', 2000, NULL, 'UNSPECIFIED')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true, PlantDatabase.MIGRATION_20_21)

        val indices = mutableListOf<String>()
        db.query("PRAGMA index_list(care_logs)").use {
            while (it.moveToNext()) indices += it.getString(it.getColumnIndexOrThrow("name"))
        }
        assertTrue("index_care_logs_plantId" in indices)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM plants WHERE id = 1")
        db.query("SELECT id FROM care_logs").use {
            assertTrue(it.moveToFirst())
            assertEquals(2L, it.getLong(0))
            assertFalse(it.moveToNext())
        }
        db.close()
    }

    @Test
    fun `new logs after the migration continue the id sequence`() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertPlant(db, 1)
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, fertilizerType) VALUES " +
                    "(41, 1, 'WATER', 2000, 'UNSPECIFIED')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true, PlantDatabase.MIGRATION_20_21)
        db.execSQL(
            "INSERT INTO care_logs (plantId, careType, loggedAt, fertilizerType) " +
                "VALUES (1, 'WATER', 3000, 'UNSPECIFIED')"
        )

        db.query("SELECT MAX(id) FROM care_logs").use {
            assertTrue(it.moveToFirst())
            assertTrue("a rebuilt table must not reuse existing ids", it.getLong(0) > 41L)
        }
        db.close()
    }

    private fun insertPlant(db: SupportSQLiteDatabase, id: Long) {
        db.execSQL(
            "INSERT INTO plants (id, name, createdAt, updatedAt, useLiquidFertilizer, pinIntervalToBase) " +
                "VALUES ($id, 'Plant $id', 1, 1, 0, 0)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-20-21"
    }
}

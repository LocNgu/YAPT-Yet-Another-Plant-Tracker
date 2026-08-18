package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest7To8 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV7Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL, NULL)"
        )
    }

    @Test
    fun `migration7To8_careLogs gets a nullable customReminderId column`() {
        helper.createDatabase(TEST_DB_CARE_LOGS, 7).use { db ->
            insertV7Plant(db, 1, "Monstera")
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType) VALUES " +
                    "(1, 1, 'WATER', 1000, NULL, NULL, NULL, NULL, 'UNSPECIFIED')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_CARE_LOGS, 8, true, PlantDatabase.MIGRATION_7_8)

        db.query("SELECT customReminderId FROM care_logs WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("customReminderId")))
        }

        db.close()
    }

    @Test
    fun `migration7To8_creates custom_reminders table that accepts a row`() {
        helper.createDatabase(TEST_DB_REMINDERS, 7).use { db ->
            insertV7Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_REMINDERS, 8, true, PlantDatabase.MIGRATION_7_8)

        db.execSQL(
            "INSERT INTO custom_reminders (id, plantId, name, intervalDays, lastDoneAt, createdAt) VALUES " +
                "(1, 1, 'Neem oil treatment', 7, NULL, 2000)"
        )

        db.query("SELECT name, intervalDays FROM custom_reminders WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Neem oil treatment", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            Assert.assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("intervalDays")))
        }

        db.close()
    }

    @Test
    fun `migration7To8_careLogs can persist a non-null customReminderId`() {
        helper.createDatabase(TEST_DB_SET, 7).use { db ->
            insertV7Plant(db, 1, "Pothos")
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType) VALUES " +
                    "(1, 1, 'CUSTOM', 1000, NULL, NULL, NULL, NULL, 'UNSPECIFIED')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_SET, 8, true, PlantDatabase.MIGRATION_7_8)

        db.execSQL(
            "INSERT INTO custom_reminders (id, plantId, name, intervalDays, lastDoneAt, createdAt) VALUES " +
                "(1, 1, 'Neem oil treatment', 7, NULL, 2000)"
        )
        db.execSQL("UPDATE care_logs SET customReminderId = 1 WHERE id = 1")

        db.query("SELECT customReminderId FROM care_logs WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("customReminderId")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_CARE_LOGS = "migration-7-8-care-logs"
        private const val TEST_DB_REMINDERS = "migration-7-8-reminders"
        private const val TEST_DB_SET = "migration-7-8-set"
    }
}

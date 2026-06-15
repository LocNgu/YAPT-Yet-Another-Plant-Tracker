package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest5To6 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    @Test
    fun migration5To6_existingRow_archivedAtIsNull() {
        helper.createDatabase(TEST_DB_EXISTING, 5).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Monstera', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 6, true, PlantDatabase.MIGRATION_5_6)

        db.query("SELECT archivedAt FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            val colIndex = cursor.getColumnIndexOrThrow("archivedAt")
            Assert.assertTrue(cursor.isNull(colIndex))
        }

        db.close()
    }

    @Test
    fun migration5To6_canSetArchivedAt() {
        helper.createDatabase(TEST_DB_SET, 5).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Fern', NULL, NULL, NULL, NULL, NULL, NULL, 2000, 2000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_SET, 6, true, PlantDatabase.MIGRATION_5_6)

        db.execSQL("UPDATE plants SET archivedAt = 9999 WHERE id = 1")

        db.query("SELECT archivedAt FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(9999L, cursor.getLong(cursor.getColumnIndexOrThrow("archivedAt")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-5-6-existing"
        private const val TEST_DB_SET = "migration-5-6-set"
    }
}

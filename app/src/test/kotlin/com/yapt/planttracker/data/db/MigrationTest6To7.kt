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
class MigrationTest6To7 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    @Test
    fun migration6To7_existingRow_extendedIntervalsAreNull() {
        helper.createDatabase(TEST_DB_EXISTING, 6).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                    "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                    "wateringDueDateOverride, useLiquidFertilizer, archivedAt) VALUES " +
                    "(1, 'Monstera', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 7, true, PlantDatabase.MIGRATION_6_7)

        db.query("SELECT mistingIntervalDays, repottingIntervalDays FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("mistingIntervalDays")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("repottingIntervalDays")))
        }

        db.close()
    }

    @Test
    fun migration6To7_canSetExtendedIntervals() {
        helper.createDatabase(TEST_DB_SET, 6).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                    "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                    "wateringDueDateOverride, useLiquidFertilizer, archivedAt) VALUES " +
                    "(1, 'Fern', NULL, NULL, NULL, NULL, NULL, NULL, 2000, 2000, NULL, 0, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_SET, 7, true, PlantDatabase.MIGRATION_6_7)

        db.execSQL("UPDATE plants SET mistingIntervalDays = 7, repottingIntervalDays = 365 WHERE id = 1")

        db.query("SELECT mistingIntervalDays, repottingIntervalDays FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("mistingIntervalDays")))
            Assert.assertEquals(365L, cursor.getLong(cursor.getColumnIndexOrThrow("repottingIntervalDays")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-6-7-existing"
        private const val TEST_DB_SET = "migration-6-7-set"
    }
}

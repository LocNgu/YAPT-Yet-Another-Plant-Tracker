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
class MigrationTest11To12 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV11Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL, NULL, " +
                "NULL, NULL, 0)"
        )
    }

    @Test
    fun `migration11To12_existing plant survives untouched`() {
        helper.createDatabase(TEST_DB_EXISTING, 11).use { db ->
            insertV11Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 12, true, PlantDatabase.MIGRATION_11_12)

        db.query("SELECT name FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Fern", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }

        db.close()
    }

    @Test
    fun `migration11To12_watering_adjustments table accepts a row`() {
        helper.createDatabase(TEST_DB_WRITE, 11).use { db ->
            insertV11Plant(db, 1, "Pothos")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_WRITE, 12, true, PlantDatabase.MIGRATION_11_12)

        db.execSQL(
            "INSERT INTO watering_adjustments (plantId, triggeredAt, trigger, beforeIntervalDays, " +
                "afterIntervalDays) VALUES (1, 2000, 'WATER_TOO_SOON', 7, 8)"
        )

        db.query(
            "SELECT plantId, trigger, beforeIntervalDays, afterIntervalDays FROM watering_adjustments"
        ).use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("plantId")))
            Assert.assertEquals("WATER_TOO_SOON", cursor.getString(cursor.getColumnIndexOrThrow("trigger")))
            Assert.assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("beforeIntervalDays")))
            Assert.assertEquals(8, cursor.getInt(cursor.getColumnIndexOrThrow("afterIntervalDays")))
        }

        db.close()
    }

    @Test
    fun `migration11To12_watering_adjustments table starts empty`() {
        helper.createDatabase(TEST_DB_EMPTY, 11).use { db ->
            insertV11Plant(db, 1, "Cactus")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EMPTY, 12, true, PlantDatabase.MIGRATION_11_12)

        db.query("SELECT COUNT(*) FROM watering_adjustments").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(0, cursor.getInt(0))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-11-12-existing"
        private const val TEST_DB_WRITE = "migration-11-12-write"
        private const val TEST_DB_EMPTY = "migration-11-12-empty"
    }
}

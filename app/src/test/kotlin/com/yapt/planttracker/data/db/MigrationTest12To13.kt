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
class MigrationTest12To13 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV12Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
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
    fun `migration12To13_existing plant survives with reset columns null`() {
        helper.createDatabase(TEST_DB_EXISTING, 12).use { db ->
            insertV12Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 13, true, PlantDatabase.MIGRATION_12_13)

        db.query("SELECT name, wateringResetAt, wateringFreezeUntil FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Fern", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("wateringResetAt")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("wateringFreezeUntil")))
        }

        db.close()
    }

    @Test
    fun `migration12To13_reset columns accept values`() {
        helper.createDatabase(TEST_DB_WRITE, 12).use { db ->
            insertV12Plant(db, 1, "Pothos")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_WRITE, 13, true, PlantDatabase.MIGRATION_12_13)

        db.execSQL("UPDATE plants SET wateringResetAt = 5000, wateringFreezeUntil = 7000 WHERE id = 1")

        db.query("SELECT wateringResetAt, wateringFreezeUntil FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(5000L, cursor.getLong(cursor.getColumnIndexOrThrow("wateringResetAt")))
            Assert.assertEquals(7000L, cursor.getLong(cursor.getColumnIndexOrThrow("wateringFreezeUntil")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-12-13-existing"
        private const val TEST_DB_WRITE = "migration-12-13-write"
    }
}

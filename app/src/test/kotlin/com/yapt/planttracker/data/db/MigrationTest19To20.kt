package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
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
class MigrationTest19To20 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `database version is 20`() {
        assertEquals(20, PlantDatabase.DB_VERSION)
    }

    @Test
    fun `migration adds a nullable propagatedFromId and keeps every existing plant and log`() {
        helper.createDatabase(TEST_DB, 19).use { db ->
            val columns = "(id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth, fertilizingSeasons, " +
                "dormantWateringIntervalDays, repotPlanSeasonStartAt, repotPlanMadeAt, repottingSeasons, hiddenAt)"
            db.execSQL(
                "INSERT INTO plants $columns VALUES " +
                    "(1, 'Cactus', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, 360, " +
                    "3, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                    "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                    "(1, 1, 'WATER', 2000, NULL, NULL, NULL, NULL, 'UNSPECIFIED', NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, PlantDatabase.MIGRATION_19_20)

        db.query("SELECT name, wateringIntervalDays, wateringConfidence, propagatedFromId FROM plants").use {
            assertTrue(it.moveToFirst())
            assertEquals("Cactus", it.getString(0))
            assertEquals(7, it.getInt(1))
            assertEquals(3, it.getInt(2))
            assertTrue("an existing plant has no recorded parent", it.isNull(3))
        }
        db.query("SELECT careType FROM care_logs WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("WATER", it.getString(0))
        }
        db.close()
    }

    @Test
    fun `migrated database enforces the set-null foreign key`() {
        helper.createDatabase(TEST_DB, 19).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, PlantDatabase.MIGRATION_19_20)
        db.execSQL("PRAGMA foreign_keys = ON")
        val insert = "INSERT INTO plants (id, name, createdAt, updatedAt, useLiquidFertilizer, pinIntervalToBase, " +
            "propagatedFromId) VALUES"
        db.execSQL("$insert (1, 'Mother', 1, 1, 0, 0, NULL)")
        db.execSQL("$insert (2, 'Cutting', 1, 1, 0, 0, 1)")

        db.execSQL("DELETE FROM plants WHERE id = 1")

        db.query("SELECT propagatedFromId FROM plants WHERE id = 2").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-19-20"
    }
}

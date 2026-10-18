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
class MigrationTest18To19 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `migration adds a nullable hiddenAt and leaves every existing plant visible`() {
        helper.createDatabase(TEST_DB, 18).use(::insertV18Rows)
        val db = helper.runMigrationsAndValidate(TEST_DB, 19, true, PlantDatabase.MIGRATION_18_19)

        db.query("SELECT name, wateringIntervalDays, archivedAt, hiddenAt FROM plants ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Cactus", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertTrue("a pre-existing plant must stay visible", cursor.isNull(3))
            assertTrue(cursor.moveToNext())
            assertEquals("Fern", cursor.getString(0))
            assertEquals(5000L, cursor.getLong(2))
            assertTrue(cursor.isNull(3))
        }
        db.query("SELECT careType, loggedAt FROM care_logs WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WATER", cursor.getString(0))
            assertEquals(2000L, cursor.getLong(1))
        }
        db.close()
    }

    private fun insertV18Rows(db: SupportSQLiteDatabase) {
        val columns = "(id, name, species, room, coverPhotoUri, notes, " +
            "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
            "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
            "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
            "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth, fertilizingSeasons, " +
            "dormantWateringIntervalDays, repotPlanSeasonStartAt, repotPlanMadeAt, repottingSeasons)"
        db.execSQL(
            "INSERT INTO plants $columns VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, 360, " +
                "NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
        )
        db.execSQL(
            "INSERT INTO plants $columns VALUES " +
                "(2, 'Fern', NULL, NULL, NULL, NULL, 3, 30, 1000, 1000, NULL, 0, 5000, NULL, " +
                "NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
        )
        db.execSQL(
            "INSERT INTO care_logs (id, plantId, careType, loggedAt, notes, photoUri, amount, " +
                "wateringFeedback, fertilizerType, customReminderId) VALUES " +
                "(1, 1, 'WATER', 2000, NULL, NULL, NULL, NULL, 'UNSPECIFIED', NULL)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-18-19"
    }
}

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
class MigrationTest15To16 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `migration preserves existing plant with dormant watering cadence null and accepts supported value`() {
        helper.createDatabase(TEST_DB, 15).use(::insertV15Plant)
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            16,
            true,
            PlantDatabase.MIGRATION_15_16
        )
        db.query("SELECT name, dormantWateringIntervalDays FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Cactus", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.execSQL("UPDATE plants SET dormantWateringIntervalDays = 35 WHERE id = 1")
        db.query("SELECT dormantWateringIntervalDays FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(35, cursor.getInt(0))
        }
        db.close()
    }

    private fun insertV15Plant(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth, " +
                "fertilizingIntervalSpring, fertilizingIntervalSummer, fertilizingIntervalAutumn, " +
                "fertilizingIntervalWinter) VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, NULL, " +
                "NULL, NULL, 0, NULL, NULL, 11, 2, NULL, NULL, NULL, NULL)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-15-16"
    }
}

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
class MigrationTest16To17 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `migration keeps existing plants with every new repotting column null and accepts real values`() {
        helper.createDatabase(TEST_DB, 16).use(::insertV16Plant)
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            17,
            true,
            PlantDatabase.MIGRATION_16_17
        )
        db.query(
            "SELECT name, repottingIntervalDays, repotPlanSeasonStartAt, repotPlanMadeAt, repottingSeasons " +
                "FROM plants WHERE id = 1"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("Cactus", cursor.getString(0))
            assertEquals(360, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        db.execSQL(
            "UPDATE plants SET repotPlanSeasonStartAt = 1804032000000, repotPlanMadeAt = 1790000000000, " +
                "repottingSeasons = 'SPRING,SUMMER' WHERE id = 1"
        )
        db.query("SELECT repotPlanSeasonStartAt, repotPlanMadeAt, repottingSeasons FROM plants WHERE id = 1")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals(1_804_032_000_000L, cursor.getLong(0))
                assertEquals(1_790_000_000_000L, cursor.getLong(1))
                assertEquals("SPRING,SUMMER", cursor.getString(2))
            }
        db.close()
    }

    private fun insertV16Plant(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth, fertilizingSeasons, " +
                "dormantWateringIntervalDays) VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, 360, " +
                "NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-16-17"
    }
}

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
class MigrationTest14To15 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    @Test
    fun `migration14To15 preserves plants and defaults seasonal intervals to null`() {
        helper.createDatabase(TEST_DB, 14).use { db -> insertV14Plant(db) }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, PlantDatabase.MIGRATION_14_15)

        db.query(
            "SELECT name, fertilizingIntervalSpring, fertilizingIntervalSummer, " +
                "fertilizingIntervalAutumn, fertilizingIntervalWinter FROM plants WHERE id = 1"
        ).use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Fern", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            listOf(
                "fertilizingIntervalSpring",
                "fertilizingIntervalSummer",
                "fertilizingIntervalAutumn",
                "fertilizingIntervalWinter"
            ).forEach { column -> Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(column))) }
        }

        db.close()
    }

    @Test
    fun `migration14To15 seasonal interval columns accept values`() {
        helper.createDatabase(TEST_DB_VALUES, 14).use { db -> insertV14Plant(db) }

        val db = helper.runMigrationsAndValidate(TEST_DB_VALUES, 15, true, PlantDatabase.MIGRATION_14_15)
        db.execSQL(
            "UPDATE plants SET fertilizingIntervalSpring = 21, fertilizingIntervalSummer = 14, " +
                "fertilizingIntervalAutumn = 30, fertilizingIntervalWinter = 45 WHERE id = 1"
        )

        db.query(
            "SELECT fertilizingIntervalSpring, fertilizingIntervalSummer, " +
                "fertilizingIntervalAutumn, fertilizingIntervalWinter FROM plants WHERE id = 1"
        ).use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(21, cursor.getInt(0))
            Assert.assertEquals(14, cursor.getInt(1))
            Assert.assertEquals(30, cursor.getInt(2))
            Assert.assertEquals(45, cursor.getInt(3))
        }

        db.close()
    }

    private fun insertV14Plant(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil, dormancyStartMonth, dormancyEndMonth) VALUES " +
                "(1, 'Fern', NULL, NULL, NULL, NULL, 7, 30, 1000, 1000, NULL, 0, NULL, NULL, " +
                "NULL, NULL, 0, NULL, NULL, NULL, NULL)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-14-15"
        private const val TEST_DB_VALUES = "migration-14-15-values"
    }
}

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
class MigrationTest13To14 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV13Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence, wateringBaseIntervalDays, pinIntervalToBase, wateringResetAt, " +
                "wateringFreezeUntil) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL, NULL, " +
                "NULL, NULL, 0, NULL, NULL)"
        )
    }

    @Test
    fun `migration13To14_existing plant survives with dormancy columns null`() {
        helper.createDatabase(TEST_DB_EXISTING, 13).use { db ->
            insertV13Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 14, true, PlantDatabase.MIGRATION_13_14)

        db.query("SELECT name, dormancyStartMonth, dormancyEndMonth FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Fern", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("dormancyStartMonth")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("dormancyEndMonth")))
        }

        db.close()
    }

    @Test
    fun `migration13To14_dormancy columns accept values`() {
        helper.createDatabase(TEST_DB_WRITE, 13).use { db ->
            insertV13Plant(db, 1, "Cactus")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_WRITE, 14, true, PlantDatabase.MIGRATION_13_14)

        db.execSQL("UPDATE plants SET dormancyStartMonth = 11, dormancyEndMonth = 2 WHERE id = 1")

        db.query("SELECT dormancyStartMonth, dormancyEndMonth FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(11, cursor.getInt(cursor.getColumnIndexOrThrow("dormancyStartMonth")))
            Assert.assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("dormancyEndMonth")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-13-14-existing"
        private const val TEST_DB_WRITE = "migration-13-14-write"
    }
}

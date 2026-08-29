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
class MigrationTest9To10 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV9Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL, NULL)"
        )
    }

    @Test
    fun `migration9To10_existing plant survives with wateringConfidence null`() {
        helper.createDatabase(TEST_DB_EXISTING, 9).use { db ->
            insertV9Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EXISTING, 10, true, PlantDatabase.MIGRATION_9_10)

        db.query("SELECT name, wateringConfidence FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Fern", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("wateringConfidence")))
        }

        db.close()
    }

    @Test
    fun `migration9To10_wateringConfidence column accepts an integer value`() {
        helper.createDatabase(TEST_DB_WRITE, 9).use { db ->
            insertV9Plant(db, 1, "Pothos")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_WRITE, 10, true, PlantDatabase.MIGRATION_9_10)

        db.execSQL("UPDATE plants SET wateringConfidence = 3 WHERE id = 1")

        db.query("SELECT wateringConfidence FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("wateringConfidence")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EXISTING = "migration-9-10-existing"
        private const val TEST_DB_WRITE = "migration-9-10-write"
    }
}

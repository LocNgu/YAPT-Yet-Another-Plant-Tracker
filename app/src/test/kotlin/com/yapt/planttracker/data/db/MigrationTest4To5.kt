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
class MigrationTest4To5 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    @Test
    fun migration4To5_noDuplicates_allRowsSurvive() {
        helper.createDatabase(TEST_DB_NO_DUPS, 4).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                    "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                    "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                    "(1, 'Monstera', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO plant_photos (plantId, uri, capturedAt) VALUES (1, 'content://photo/a', 1000)"
            )
            db.execSQL(
                "INSERT INTO plant_photos (plantId, uri, capturedAt) VALUES (1, 'content://photo/b', 2000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_NO_DUPS, 5, true, PlantDatabase.MIGRATION_4_5)

        db.query("SELECT COUNT(*) FROM plant_photos").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(2, cursor.getInt(0))
        }

        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_plant_photos_plantId_uri'"
        ).use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(1, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    fun migration4To5_duplicatePair_onlyMinIdSurvives() {
        helper.createDatabase(TEST_DB_DUPS, 4).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                    "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                    "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                    "(1, 'Fern', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO plant_photos (plantId, uri, capturedAt) VALUES (1, 'content://photo/dup', 1000)"
            )
            db.execSQL(
                "INSERT INTO plant_photos (plantId, uri, capturedAt) VALUES (1, 'content://photo/dup', 2000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_DUPS, 5, true, PlantDatabase.MIGRATION_4_5)

        db.query("SELECT COUNT(*) FROM plant_photos").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(1, cursor.getInt(0))
        }

        db.query("SELECT capturedAt FROM plant_photos").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("capturedAt")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_NO_DUPS = "migration-4-5-no-dups"
        private const val TEST_DB_DUPS = "migration-4-5-dups"
    }
}

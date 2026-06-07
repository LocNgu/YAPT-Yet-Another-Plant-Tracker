package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest3To4 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    @Test
    fun migration3To4_seedsPlantPhotosFromCoverPhotoUri() {
        helper.createDatabase(TEST_DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Monstera', NULL, NULL, 'content://photo/1', NULL, 7, NULL, 1000, 1000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_NAME, 4, true, PlantDatabase.MIGRATION_3_4)

        db.query("SELECT plantId, uri, capturedAt FROM plant_photos").use { cursor ->
            assert(cursor.moveToFirst()) { "Expected 1 row in plant_photos after migration" }
            assert(cursor.getLong(cursor.getColumnIndexOrThrow("plantId")) == 1L) { "plantId mismatch" }
            assert(cursor.getString(cursor.getColumnIndexOrThrow("uri")) == "content://photo/1") { "uri mismatch" }
            assert(cursor.getLong(cursor.getColumnIndexOrThrow("capturedAt")) == 1000L) { "capturedAt mismatch" }
            assert(!cursor.moveToNext()) { "Expected exactly 1 row in plant_photos" }
        }

        db.close()
    }

    @Test
    fun migration3To4_nullCoverPhotoUri_notSeeded() {
        helper.createDatabase(TEST_DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 14, NULL, 2000, 2000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_NAME, 4, true, PlantDatabase.MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM plant_photos").use { cursor ->
            cursor.moveToFirst()
            assert(cursor.getInt(0) == 0) { "Expected 0 rows for plant with null coverPhotoUri" }
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_NAME = "migration-test"
    }
}

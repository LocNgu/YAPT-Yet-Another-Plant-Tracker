package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `migration 3 to 4 seeds plant_photos from coverPhotoUri`() {
        helper.createDatabase(TEST_DB_SEED, 3).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Monstera', NULL, NULL, 'content://photo/1', NULL, 7, NULL, 1000, 1000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_SEED, 4, true, PlantDatabase.MIGRATION_3_4)

        db.query("SELECT plantId, uri, capturedAt FROM plant_photos").use { cursor ->
            assertTrue("Expected 1 row in plant_photos after migration", cursor.moveToFirst())
            assertEquals("plantId mismatch", 1L, cursor.getLong(cursor.getColumnIndexOrThrow("plantId")))
            assertEquals("uri mismatch", "content://photo/1", cursor.getString(cursor.getColumnIndexOrThrow("uri")))
            assertEquals("capturedAt mismatch", 1000L, cursor.getLong(cursor.getColumnIndexOrThrow("capturedAt")))
            assertFalse("Expected exactly 1 row in plant_photos", cursor.moveToNext())
        }

        db.close()
    }

    @Test
    fun `migration 3 to 4 null coverPhotoUri is not seeded`() {
        helper.createDatabase(TEST_DB_NULL, 3).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer) VALUES " +
                "(1, 'Cactus', NULL, NULL, NULL, NULL, 14, NULL, 2000, 2000, NULL, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_NULL, 4, true, PlantDatabase.MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM plant_photos").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Expected 0 rows for plant with null coverPhotoUri", 0, cursor.getInt(0))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_SEED = "migration-test-seed"
        private const val TEST_DB_NULL = "migration-test-null"
    }
}

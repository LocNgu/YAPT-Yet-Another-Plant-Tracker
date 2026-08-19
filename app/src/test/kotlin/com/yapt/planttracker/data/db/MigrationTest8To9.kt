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
class MigrationTest8To9 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV8Plant(db: SupportSQLiteDatabase, id: Long, name: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, 7, NULL, 1000, 1000, NULL, 0, NULL, NULL)"
        )
    }

    @Test
    fun `migration8To9_creates plant_issues table that accepts an active row`() {
        helper.createDatabase(TEST_DB_ACTIVE, 8).use { db ->
            insertV8Plant(db, 1, "Fern")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_ACTIVE, 9, true, PlantDatabase.MIGRATION_8_9)

        db.execSQL(
            "INSERT INTO plant_issues (id, plantId, name, startedAt, resolvedAt, resolutionNote, " +
                "linkedReminderId) VALUES (1, 1, 'Spider mites', 2000, NULL, NULL, NULL)"
        )

        db.query("SELECT name, resolvedAt FROM plant_issues WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals("Spider mites", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("resolvedAt")))
        }

        db.close()
    }

    @Test
    fun `migration8To9_plant_issues can persist a resolved row with a linked reminder`() {
        helper.createDatabase(TEST_DB_RESOLVED, 8).use { db ->
            insertV8Plant(db, 1, "Pothos")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_RESOLVED, 9, true, PlantDatabase.MIGRATION_8_9)

        db.execSQL(
            "INSERT INTO plant_issues (id, plantId, name, startedAt, resolvedAt, resolutionNote, " +
                "linkedReminderId) VALUES (1, 1, 'Root rot', 1000, 5000, 'Repotted', 42)"
        )

        db.query("SELECT resolvedAt, resolutionNote, linkedReminderId FROM plant_issues WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertEquals(5000L, cursor.getLong(cursor.getColumnIndexOrThrow("resolvedAt")))
            Assert.assertEquals("Repotted", cursor.getString(cursor.getColumnIndexOrThrow("resolutionNote")))
            Assert.assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("linkedReminderId")))
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_ACTIVE = "migration-8-9-active"
        private const val TEST_DB_RESOLVED = "migration-8-9-resolved"
    }
}

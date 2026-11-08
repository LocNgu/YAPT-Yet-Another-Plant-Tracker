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
class MigrationTest21To22 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `migration adds a null made-at column and keeps existing rows and overrides`() {
        helper.createDatabase(TEST_DB, 21).use { db ->
            insertPlant(db, 1, override = "5000")
            insertPlant(db, 2, override = "NULL")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true, PlantDatabase.MIGRATION_21_22)

        db.query("SELECT id, name, wateringDueDateOverride, wateringDueDateOverrideMadeAt FROM plants ORDER BY id")
            .use {
                assertTrue(it.moveToFirst())
                assertEquals("Plant 1", it.getString(1))
                assertEquals(5000L, it.getLong(2))
                assertTrue("no backfill", it.isNull(3))
                assertTrue(it.moveToNext())
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
            }
        db.close()
    }

    private fun insertPlant(db: SupportSQLiteDatabase, id: Long, override: String) {
        db.execSQL(
            "INSERT INTO plants (id, name, createdAt, updatedAt, wateringDueDateOverride, useLiquidFertilizer, " +
                "pinIntervalToBase) VALUES ($id, 'Plant $id', 1, 1, $override, 0, 0)"
        )
    }

    companion object {
        private const val TEST_DB = "migration-21-22"
    }
}

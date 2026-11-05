package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
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
    fun `database version is 22`() {
        assertEquals(22, PlantDatabase.DB_VERSION)
    }

    @Test
    fun `migration adds a null wateringAmplitudeOverride and keeps every existing row intact`() {
        helper.createDatabase(TEST_DB, 21).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, createdAt, updatedAt, useLiquidFertilizer, pinIntervalToBase, " +
                    "wateringIntervalDays, wateringBaseIntervalDays) VALUES (1, 'Pinned', 1, 2, 0, 1, 9, 7.5)"
            )
            db.execSQL(
                "INSERT INTO plants (id, name, createdAt, updatedAt, useLiquidFertilizer, pinIntervalToBase) " +
                    "VALUES (2, 'Plain', 1, 2, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true, PlantDatabase.MIGRATION_21_22)

        db.query(
            "SELECT id, name, pinIntervalToBase, wateringIntervalDays, wateringBaseIntervalDays, " +
                "wateringAmplitudeOverride FROM plants ORDER BY id"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Pinned", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals(9, it.getInt(3))
            assertEquals(7.5, it.getDouble(4), 0.0)
            assertTrue("existing rows follow the app default", it.isNull(5))
            assertTrue(it.moveToNext())
            assertEquals("Plain", it.getString(1))
            assertTrue(it.isNull(5))
        }
        db.execSQL("UPDATE plants SET wateringAmplitudeOverride = 'STRONG' WHERE id = 2")
        db.query("SELECT wateringAmplitudeOverride FROM plants WHERE id = 2").use {
            assertTrue(it.moveToFirst())
            assertEquals("STRONG", it.getString(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-21-22"
    }
}

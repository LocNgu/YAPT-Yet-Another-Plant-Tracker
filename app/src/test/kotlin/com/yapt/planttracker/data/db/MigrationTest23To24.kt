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
class MigrationTest23To24 {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlantDatabase::class.java)

    @Test
    fun `database version is 24`() {
        assertEquals(24, PlantDatabase.DB_VERSION)
    }

    @Test
    fun `migration adds null growing-condition columns and keeps every existing row intact`() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            db.execSQL(
                "INSERT INTO plants (id, name, createdAt, updatedAt, useLiquidFertilizer, pinIntervalToBase, " +
                    "wateringIntervalDays) VALUES (1, 'Fern', 1, 2, 0, 0, 9)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, PlantDatabase.MIGRATION_23_24)

        db.query("SELECT name, wateringIntervalDays, lightLevel, potMaterial, potDiameterCm FROM plants").use {
            assertTrue(it.moveToFirst())
            assertEquals("Fern", it.getString(0))
            assertEquals(9, it.getInt(1))
            assertTrue("existing plants read as unset", it.isNull(2))
            assertTrue(it.isNull(3))
            assertTrue(it.isNull(4))
        }
        db.execSQL("UPDATE plants SET lightLevel = 'DIRECT', potMaterial = 'CERAMIC', potDiameterCm = 18 WHERE id = 1")
        db.query("SELECT lightLevel, potMaterial, potDiameterCm FROM plants WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("DIRECT", it.getString(0))
            assertEquals("CERAMIC", it.getString(1))
            assertEquals(18, it.getInt(2))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-23-24"
    }
}

package com.yapt.planttracker.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest10To11 {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlantDatabase::class.java
    )

    private fun insertV10Plant(db: SupportSQLiteDatabase, id: Long, name: String, wateringIntervalDays: Int?) {
        val intervalSql = wateringIntervalDays?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO plants (id, name, species, room, coverPhotoUri, notes, " +
                "wateringIntervalDays, fertilizingIntervalDays, createdAt, updatedAt, " +
                "wateringDueDateOverride, useLiquidFertilizer, archivedAt, repottingIntervalDays, " +
                "wateringConfidence) VALUES " +
                "($id, '$name', NULL, NULL, NULL, NULL, $intervalSql, NULL, 1000, 1000, NULL, 0, NULL, NULL, NULL)"
        )
    }

    @Test
    fun `migration10To11_existing plant effective interval on migration day equals previous wateringIntervalDays`() {
        helper.createDatabase(TEST_DB_EFFECTIVE, 10).use { db ->
            insertV10Plant(db, 1, "Monstera", wateringIntervalDays = 7)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_EFFECTIVE, 11, true, PlantDatabase.MIGRATION_10_11)

        db.query("SELECT wateringBaseIntervalDays, pinIntervalToBase FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            val base = cursor.getDouble(cursor.getColumnIndexOrThrow("wateringBaseIntervalDays"))
            Assert.assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pinIntervalToBase")))

            val today = LocalDate.now()
            val hemisphere = SeasonalWatering.currentHemisphere()
            val effectiveIntervalToday = SeasonalWatering.effectiveInterval(
                base,
                today,
                SeasonalAmplitude.STANDARD.value,
                hemisphere
            )
            Assert.assertEquals(7, effectiveIntervalToday)
        }

        db.close()
    }

    @Test
    fun `migration10To11_plant with no watering interval keeps a null base`() {
        helper.createDatabase(TEST_DB_NULL_INTERVAL, 10).use { db ->
            insertV10Plant(db, 1, "Cactus", wateringIntervalDays = null)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_NULL_INTERVAL, 11, true, PlantDatabase.MIGRATION_10_11)

        db.query("SELECT wateringBaseIntervalDays FROM plants WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            Assert.assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("wateringBaseIntervalDays")))
        }

        db.close()
    }

    @Test
    fun `migration10To11_pinIntervalToBase defaults false for existing rows`() {
        helper.createDatabase(TEST_DB_PIN_DEFAULT, 10).use { db ->
            insertV10Plant(db, 1, "Fern", wateringIntervalDays = 10)
            insertV10Plant(db, 2, "Pothos", wateringIntervalDays = null)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_PIN_DEFAULT, 11, true, PlantDatabase.MIGRATION_10_11)

        db.query("SELECT pinIntervalToBase FROM plants ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                Assert.assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pinIntervalToBase")))
            }
        }

        db.close()
    }

    companion object {
        private const val TEST_DB_EFFECTIVE = "migration-10-11-effective"
        private const val TEST_DB_NULL_INTERVAL = "migration-10-11-null-interval"
        private const val TEST_DB_PIN_DEFAULT = "migration-10-11-pin-default"
    }
}

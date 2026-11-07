package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ORDER BY triggeredAt DESC, id DESC` coverage for [WateringAdjustmentDao.getRecentForPlant]
 * (#699/#761, product ADR-0044 — Codex review round 1 on #776, item 5). This PR is the first place
 * two rows ([com.yapt.planttracker.domain.model.WateringAdjustmentTrigger.DORMANCY_EXCLUDED] +
 * `DORMANCY_EXIT`) are written with an identical `triggeredAt` for one logical observation — the `id
 * DESC` secondary key makes their relative order deterministic rather than relying on SQLite's
 * insertion-order-on-a-tie behavior, which is not a documented contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WateringAdjustmentDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantDao: PlantDao
    private lateinit var wateringAdjustmentDao: WateringAdjustmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plantDao = db.plantDao()
        wateringAdjustmentDao = db.wateringAdjustmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertParentPlant(): Long =
        plantDao.insertPlant(
            PlantEntity(
                name = "Test Plant",
                species = null,
                room = null,
                coverPhotoUri = null,
                notes = null,
                wateringIntervalDays = 7,
                fertilizingIntervalDays = null,
                createdAt = 1_000_000L,
                updatedAt = 1_000_000L
            )
        )

    private fun adjustment(
        plantId: Long,
        triggeredAt: Long,
        trigger: String,
        beforeIntervalDays: Int = 7,
        afterIntervalDays: Int = 7
    ) = WateringAdjustmentEntity(
        plantId = plantId,
        triggeredAt = triggeredAt,
        trigger = trigger,
        beforeIntervalDays = beforeIntervalDays,
        afterIntervalDays = afterIntervalDays
    )

    @Test
    fun `insertAdjustment and getRecentForPlant returns the inserted row`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, triggeredAt = 1_000L, trigger = "WATER_JUST_RIGHT"))

        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 5).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("WATER_JUST_RIGHT", list[0].trigger)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getRecentForPlant breaks a triggeredAt tie by id descending, newest insert first`() = runTest {
        // Mirrors #761's DORMANCY_EXCLUDED-then-DORMANCY_EXIT write order for one observation: both
        // rows share triggeredAt, so only insertion order (id) can disambiguate them.
        val plantId = insertParentPlant()
        val sharedTriggeredAt = 5_000_000L
        wateringAdjustmentDao.insertAdjustment(
            adjustment(plantId, triggeredAt = sharedTriggeredAt, trigger = "DORMANCY_EXCLUDED")
        )
        wateringAdjustmentDao.insertAdjustment(
            adjustment(plantId, triggeredAt = sharedTriggeredAt, trigger = "DORMANCY_EXIT")
        )

        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 5).test {
            val list = awaitItem()
            assertEquals(listOf("DORMANCY_EXIT", "DORMANCY_EXCLUDED"), list.map { it.trigger })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getRecentForPlant orders distinct triggeredAt values newest first regardless of insertion order`() = runTest {
        val plantId = insertParentPlant()
        // Inserted oldest-triggeredAt first, so a pure id-based order would get this wrong without
        // triggeredAt DESC leading the sort.
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, triggeredAt = 1_000L, trigger = "OLDEST"))
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, triggeredAt = 3_000L, trigger = "NEWEST"))
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, triggeredAt = 2_000L, trigger = "MIDDLE"))

        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 5).test {
            val list = awaitItem()
            assertEquals(listOf("NEWEST", "MIDDLE", "OLDEST"), list.map { it.trigger })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getRecentForPlant respects the limit`() = runTest {
        val plantId = insertParentPlant()
        repeat(3) { i ->
            wateringAdjustmentDao.insertAdjustment(
                adjustment(plantId, triggeredAt = (i + 1) * 1_000L, trigger = "TRIGGER_$i")
            )
        }

        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 2).test {
            assertEquals(2, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `cascading delete removes watering adjustments when plant is deleted`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, triggeredAt = 1_000L, trigger = "WATER_JUST_RIGHT"))

        plantDao.deleteAll()

        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 5).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    // ---- #799 (product ADR-0082): recordManualEdit same-day merge ----

    private val day1 = java.time.LocalDate.of(2026, 3, 10)
    private fun at(hour: Int, minute: Int = 0, day: java.time.LocalDate = day1): Long =
        day.atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun allRows(plantId: Long) =
        wateringAdjustmentDao.getRecentForPlant(plantId, limit = 50).first()

    @Test
    fun `two same-day manual edits merge into one row keeping the original before`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(9), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(11), "MANUAL_EDIT", 10, 12))

        val rows = allRows(plantId)
        assertEquals(1, rows.size)
        assertEquals(7, rows[0].beforeIntervalDays)
        assertEquals(12, rows[0].afterIntervalDays)
        assertEquals(at(11), rows[0].triggeredAt)
    }

    @Test
    fun `a net-zero day keeps the row with before equal to after`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(9), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(10), "MANUAL_EDIT", 10, 7))

        val rows = allRows(plantId)
        assertEquals(1, rows.size)
        assertEquals(7, rows[0].beforeIntervalDays)
        assertEquals(7, rows[0].afterIntervalDays)
    }

    @Test
    fun `an intervening non-manual row prevents the merge`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(9), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, at(10), "WATER_TOO_LATE", 10, 10))
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(11), "MANUAL_EDIT", 10, 12))

        assertEquals(listOf("MANUAL_EDIT", "WATER_TOO_LATE", "MANUAL_EDIT"), allRows(plantId).map { it.trigger })
    }

    @Test
    fun `an intervening DIALOG_EDIT prevents the merge`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(9), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.insertAdjustment(adjustment(plantId, at(10), "DIALOG_EDIT", 10, 11))
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(11), "MANUAL_EDIT", 11, 12))

        assertEquals(3, allRows(plantId).size)
    }

    @Test
    fun `edits on different local days do not merge`() = runTest {
        val plantId = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(plantId, at(23, 50), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.recordManualEdit(
            adjustment(plantId, at(0, 10, day1.plusDays(1)), "MANUAL_EDIT", 10, 12)
        )

        assertEquals(2, allRows(plantId).size)
    }

    @Test
    fun `merging is per plant`() = runTest {
        val a = insertParentPlant()
        val b = insertParentPlant()
        wateringAdjustmentDao.recordManualEdit(adjustment(a, at(9), "MANUAL_EDIT", 7, 10))
        wateringAdjustmentDao.recordManualEdit(adjustment(b, at(10), "MANUAL_EDIT", 7, 9))

        assertEquals(1, allRows(a).size)
        assertEquals(1, allRows(b).size)
    }
}

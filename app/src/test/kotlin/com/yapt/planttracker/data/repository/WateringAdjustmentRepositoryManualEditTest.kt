package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * #799 (product ADR-0081): [WateringAdjustmentRepository.recordManualEdit] against a real in-memory DB.
 * Plant Detail's `setWateringInterval()` and Add/Edit's `saveEdit()` both call this one method, so two
 * same-day edits from either writer (in any order) leave a single `MANUAL_EDIT` row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WateringAdjustmentRepositoryManualEditTest {

    private lateinit var db: PlantDatabase
    private lateinit var repo: WateringAdjustmentRepository
    private var plantId = 0L
    private val day = LocalDate.of(2026, 3, 10)

    private fun at(hour: Int, d: LocalDate = day) =
        d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun edit(
        t: Long,
        before: Int,
        after: Int,
        trigger: WateringAdjustmentTrigger = WateringAdjustmentTrigger.MANUAL_EDIT
    ) =
        WateringAdjustment(
            plantId = plantId,
            triggeredAt = t,
            trigger = trigger,
            beforeIntervalDays = before,
            afterIntervalDays = after
        )

    private suspend fun rows() = repo.getRecentForPlant(plantId, 50).first()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java).allowMainThreadQueries().build()
        repo = WateringAdjustmentRepository(db.wateringAdjustmentDao())
        plantId = db.plantDao().insertPlant(
            PlantEntity(
                name = "P", species = null, room = null, coverPhotoUri = null, notes = null,
                wateringIntervalDays = 7, fertilizingIntervalDays = null, createdAt = 0L, updatedAt = 0L
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `two same-day edits from different writers leave one row`() = runTest {
        repo.recordManualEdit(edit(at(9), 7, 10)) // e.g. Plant Detail slider
        repo.recordManualEdit(edit(at(18), 10, 12)) // e.g. Add/Edit save

        val rows = rows()
        assertEquals(1, rows.size)
        assertEquals(7, rows[0].beforeIntervalDays)
        assertEquals(12, rows[0].afterIntervalDays)
    }

    @Test
    fun `only the newest row is considered, an older same-day manual edit behind a newer day does not merge`() =
        runTest {
            repo.recordManualEdit(edit(at(9), 7, 10))
            repo.recordManualEdit(edit(at(9, day.plusDays(1)), 10, 12))
            repo.recordManualEdit(edit(at(10), 12, 14)) // backdated into day 1; newest row is day 2

            assertEquals(3, rows().size)
        }

    @Test(expected = IllegalArgumentException::class)
    fun `recordManualEdit rejects a non-manual trigger`() = runTest {
        repo.recordManualEdit(edit(at(9), 7, 7, WateringAdjustmentTrigger.DIALOG_EDIT))
    }
}

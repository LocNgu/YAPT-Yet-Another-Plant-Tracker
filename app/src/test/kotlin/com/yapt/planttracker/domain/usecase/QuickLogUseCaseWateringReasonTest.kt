package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `WateringReason.SOIL_STILL_MOIST` coverage (#649, product ADR-0033) — the late-direction reason
 * that replaced "It was dry by then", split out of [QuickLogUseCaseTest] to keep that file under
 * Detekt's `LargeClass` threshold, mirroring [QuickLogUseCaseSeasonalTest]'s precedent.
 */
class QuickLogUseCaseWateringReasonTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private lateinit var useCase: QuickLogUseCase

    private fun plant(id: Long = 1L, name: String = "Monstera", wateringIntervalDays: Int? = null) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        // #571: below the 3-gap bootstrap threshold by default — see QuickLogUseCaseTest's identical stub.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        useCase = QuickLogUseCase(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, database, wateringAdjustmentRepo
        )
    }

    // #649 (product ADR-0033): SOIL_STILL_MOIST is the late-direction reason that replaced the old
    // "It was dry by then" attribution — a late gap must never shorten the interval (a single
    // retrospective observation can't localize exactly when inside an overdue window the plant went
    // dry), so the late direction only ever holds (JUST_MY_TIMING) or lengthens (this reason).
    @Test
    fun `quickWaterWithReason SOIL_STILL_MOIST logs a WATER entry with TOO_SOON feedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
    }

    // #586/#649 acceptance criterion: only SOIL_STILL_MOIST ever writes TOO_SOON to a WATER log —
    // PLANT_NEEDED_IT, JUST_MY_TIMING, and the no-reason case never do. Checked exhaustively so a
    // future reason can't quietly widen this.
    @Test
    fun `only SOIL_STILL_MOIST writes TOO_SOON to a WATER log`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        for (reason in WateringReason.entries + listOf(null)) {
            if (reason != WateringReason.SOIL_STILL_MOIST) {
                assertNotEquals(WateringFeedback.TOO_SOON, reason?.toWateringFeedback())
            }
        }

        useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        coVerify(exactly = 0) {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
    }

    // #649 (product ADR-0033): a late "Soil was still moist" observation lengthens the interval
    // (TOO_SOON's multiplier, same as the Reschedule flow's identical signal) and records
    // WATER_TOO_SOON — confirming the new late-direction reason reaches the adaptive model exactly
    // like any other explicit attribution, with no special-casing needed for the WATER-log path.
    @Test
    fun `quickWaterWithReason SOIL_STILL_MOIST lengthens the interval and records WATER_TOO_SOON`() = runTest {
        val now = System.currentTimeMillis()
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo)
        coEvery { careLogRepo.getRecentWaterings(1L, limit = any()) } returns emptyList()

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST)

        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON &&
                        it.afterIntervalDays > it.beforeIntervalDays
                }
            )
        }
    }

    // Codex review finding on #661: the #571 cold-start bootstrap computes a plain median of
    // historical WATER-log gaps, entirely blind to WateringFeedback — so a late "Soil was still
    // moist" observation landing on a plant's first-ever adaptive observation could otherwise still
    // bootstrap to an interval *shorter* than the plant already had, breaking ADR-0033's guarantee
    // through the one path that bypasses CareSchedule.computeAdaptiveInterval's TOO_SOON multiplier.
    // History here is 3-day gaps (median 3) on a plant currently at a 7-day interval — without the
    // WateringLifecycleReset.maybeBootstrap() floor, this would bootstrap down to 3.
    @Test
    fun `quickWaterWithReason SOIL_STILL_MOIST never shortens the interval even via history bootstrap`() = runTest {
        val now = System.currentTimeMillis()
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(3))
        // 5 timestamps, 3 days apart -> 4 gaps (median 3), clears MIN_BOOTSTRAP_GAPS (3).
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns (0..4).map {
            now - TimeUnit.DAYS.toMillis((12 - it * 3).toLong())
        }

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST)

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == 7.0 }
            )
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match { it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP && it.afterIntervalDays == 7 }
            )
        }
    }
}

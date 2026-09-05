package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * #614 regression coverage, split out of [QuickLogUseCaseTest] to keep that file under Detekt's
 * `LargeClass` threshold — mirrors [QuickLogUseCaseSeasonalTest]'s precedent.
 *
 * Same bug class as #612 (fixed for `recordStillMoistCheck` in PR #613): a confidence write
 * triggered by the same watering used to be built off the stale pre-clear [Plant] snapshot in a
 * second `updatePlant` call, silently reverting the `wateringDueDateOverride` clear
 * `clearWateringOverrideIfActive` had just persisted moments earlier. The fix threads the fresh,
 * post-clear plant through `computeSuggestion` -> `adaptWateringInterval` so its confidence write
 * builds off state that already has the override cleared.
 */
class QuickLogUseCaseOverrideRevertTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)

    private val now = System.currentTimeMillis()
    private val sevenDaysAgo = now - TimeUnit.DAYS.toMillis(7)
    private val override = now + TimeUnit.DAYS.toMillis(2)

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()
        // Observed gap (7 days) agrees with the stored interval (7 days), so the adaptive model
        // raises confidence from 2 to 3 — the write these tests need to exercise the bug.
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } returns previousWatering()
    }

    private fun previousWatering(): CareLog = CareLog(
        plantId = 1L,
        careType = CareType.WATER,
        loggedAt = sevenDaysAgo,
        wateringFeedback = WateringFeedback.JUST_RIGHT
    )

    private fun plant(
        useLiquidFertilizer: Boolean = false,
        wateringDueDateOverride: Long? = null
    ) = Plant(
        id = 1L,
        name = "Monstera",
        useLiquidFertilizer = useLiquidFertilizer,
        wateringIntervalDays = 7,
        wateringDueDateOverride = wateringDueDateOverride,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** ADAPTIVE_WATERING on, so an observation can move [Plant.wateringConfidence]. */
    private fun adaptiveUseCase(): QuickLogUseCase {
        val adaptiveDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            adaptiveDataStore,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `quickWaterWithReason does not revert an active override on a confidence change`() = runTest {
        val useCase = adaptiveUseCase()
        // Stale parameter, as passed by the caller before the override was cleared.
        val monstera = plant(wateringDueDateOverride = override).copy(wateringConfidence = 2)
        // Fresh re-fetch inside clearWateringOverrideIfActive sees the same override still active.
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringDueDateOverride == null && it.wateringConfidence == 3 }
            )
        }
    }

    @Test
    fun `quickLiquidFertilizeWithReason does not revert an active override on a confidence change`() = runTest {
        val useCase = adaptiveUseCase()
        val monstera = plant(useLiquidFertilizer = true, wateringDueDateOverride = override)
            .copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLiquidFertilizeWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringDueDateOverride == null && it.wateringConfidence == 3 }
            )
        }
    }
}

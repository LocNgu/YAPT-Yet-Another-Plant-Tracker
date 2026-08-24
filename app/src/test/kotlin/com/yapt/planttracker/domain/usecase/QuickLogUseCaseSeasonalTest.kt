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
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * `quickWaterWithFeedback`'s de-seasonalization coverage (#569/#572), split out of
 * [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold — mirrors
 * `PlantDetailViewModelSeasonalTest`'s precedent.
 */
class QuickLogUseCaseSeasonalTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } returns Unit
    }

    private fun plant(id: Long = 1L, name: String = "Monstera", wateringIntervalDays: Int? = null) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** ADAPTIVE_WATERING + SEASONAL_WATERING both on, with [nowProvider] pinned to [peakDay]. */
    private fun useCaseWithSeasonOn(peakDay: Long): QuickLogUseCase {
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true,
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true
                )
            )
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            seasonalDataStore,
            database,
            wateringAdjustmentRepo,
            nowProvider = { peakDay }
        )
    }

    @Test
    fun `quickWaterWithFeedback de-seasonalizes the gap for a non-pinned plant when SEASONAL_WATERING is on`() =
        runTest {
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOn(peakDay)
            val twentyDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(20)
            val monstera = plant(wateringIntervalDays = 10)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = peakDay,
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                ),
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = twentyDaysBeforePeak,
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                )
            )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

            // Peak day (Jan 5, northern): season(peakDay) = 1 + 0.35 = 1.35, so the observed 20-day
            // gap de-seasonalizes to round(20 / 1.35) = 15 before feeding the adaptive model.
            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expected = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.JUST_RIGHT,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
            assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
        }

    @Test
    fun `quickWaterWithFeedback adapts against wateringBaseIntervalDays, not stale wateringIntervalDays`() =
        runTest {
            // #572 regression: currentBaseIntervalDays must be season-neutral (wateringBaseIntervalDays)
            // once SEASONAL_WATERING is on and the plant isn't pinned — feeding it the raw
            // wateringIntervalDays (10, stale once season is on) instead of wateringBaseIntervalDays
            // (6.0, the live season-neutral value) is exactly the bug this issue fixes.
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOn(peakDay)
            val monstera = plant(wateringIntervalDays = 10).copy(wateringBaseIntervalDays = 6.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = peakDay,
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                ),
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = peakDay - TimeUnit.DAYS.toMillis(20),
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                )
            )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expectedFromBase = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.JUST_RIGHT,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 6,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedFromStaleInterval = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.JUST_RIGHT,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            assertEquals(expectedFromBase.intervalDays.takeIf { it != 10 }, outcome.suggestion?.suggestedInterval)
            assertTrue(expectedFromBase.intervalDays != expectedFromStaleInterval.intervalDays)
            // #584 review: the WATER_JUST_RIGHT row itself must log the true base (6), not the stale
            // literal wateringIntervalDays (10) — the same value PlantDetailViewModelSeasonalTest's
            // "applySuggestedInterval logs the same base-space beforeIntervalDays..." case asserts a
            // DIALOG_EDIT row would log for this identical plant shape, so "Recent adjustments" never
            // mixes units for the same underlying change.
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.WATER_JUST_RIGHT && it.beforeIntervalDays == 6
                    }
                )
            }
        }

    @Test
    fun `quickWaterWithFeedback skips de-seasonalization for a pinned plant when SEASONAL_WATERING is on`() = runTest {
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val useCase = useCaseWithSeasonOn(peakDay)
        val twentyDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(20)
        val monstera = plant(wateringIntervalDays = 10).copy(pinIntervalToBase = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = peakDay,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            ),
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = twentyDaysBeforePeak,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            )
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.JUST_RIGHT,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
        assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}

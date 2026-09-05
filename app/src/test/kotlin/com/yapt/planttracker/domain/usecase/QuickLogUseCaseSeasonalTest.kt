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
import com.yapt.planttracker.domain.model.WateringReason
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
 * `quickWaterWithReason`'s de-seasonalization coverage (#569/#572), split out of
 * [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold — mirrors
 * `PlantDetailViewModelSeasonalTest`'s precedent.
 *
 * Every `quickWaterWithReason` call below passes `loggedAt = peakDay` explicitly (#654 review round 1):
 * since the season used to de-seasonalize an observed gap is now evaluated at the caller's `loggedAt`
 * rather than [nowProvider] (see `QuickLogUseCase.deseasonalizedObservedIntervalDays`/
 * `effectiveIntervalForDisplay`), leaving `loggedAt` at its real-wall-clock default would decouple it
 * from [nowProvider]'s pinned [peakDay] and make these tests depend on whatever day they happen to run.
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
        // #571: below the 3-gap bootstrap threshold by default — see QuickLogUseCaseTest's identical stub.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
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
    fun `quickWaterWithReason de-seasonalizes the gap for a non-pinned plant when SEASONAL_WATERING is on`() =
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

            val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

            // Peak day (Jan 5, northern): season(peakDay) = 1 + 0.35 = 1.35, so the observed 20-day
            // gap de-seasonalizes to round(20 / 1.35) = 15 before feeding the adaptive model.
            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expected = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
            assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
        }

    @Test
    fun `quickWaterWithReason adapts against wateringBaseIntervalDays, not stale wateringIntervalDays`() =
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

            val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expectedFromBase = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 6,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedFromStaleInterval = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            assertEquals(expectedFromBase.intervalDays.takeIf { it != 10 }, outcome.suggestion?.suggestedInterval)
            assertTrue(expectedFromBase.intervalDays != expectedFromStaleInterval.intervalDays)
            // #584 review: the WATER_TOO_LATE row itself must log the true base (6), not the stale
            // literal wateringIntervalDays (10) — the same value PlantDetailViewModelSeasonalTest's
            // "applySuggestedInterval logs the same base-space beforeIntervalDays..." case asserts a
            // DIALOG_EDIT row would log for this identical plant shape, so "Recent adjustments" never
            // mixes units for the same underlying change.
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.WATER_TOO_LATE && it.beforeIntervalDays == 6
                    }
                )
            }
        }

    @Test
    fun `quickWaterWithReason skips de-seasonalization for a pinned plant when SEASONAL_WATERING is on`() = runTest {
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

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.TOO_LATE,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
        assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
    }

    /** SEASONAL_WATERING on, ADAPTIVE_WATERING off — the legacy `computeSuggestedInterval()` path. */
    private fun useCaseWithSeasonOnLegacyPath(peakDay: Long): QuickLogUseCase {
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
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

    /**
     * #620's own gate, at the choke point ([QuickLogUseCase.computeSuggestion]) shared by Calendar,
     * Plant List, and Plant Detail: when the base-space suggestion's effective-space conversion equals
     * `plant.wateringIntervalDays`, the whole "change" is a unit-mismatch artifact, not a real model
     * change, so no [com.yapt.planttracker.domain.model.QuickWaterSuggestion] should be emitted at all —
     * not even one whose two numbers happen to render identically.
     */
    @Test
    fun `quickWaterWithReason suppresses the suggestion entirely when its effective value equals current`() =
        runTest {
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOnLegacyPath(peakDay)
            val elevenDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(11)

            val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant = plant(wateringIntervalDays = 10).copy(wateringBaseIntervalDays = 10.0),
                nowDate = LocalDate.of(2023, 1, 5),
                seasonalAmplitude = SeasonalAmplitude.STANDARD.value
            ) ?: 10
            // TOO_LATE's legacy suggestion is `max(1, base - 1)` where base = current when actual (11)
            // exceeds it, so pinning current >= 11 keeps the raw suggestion a deterministic 10
            // regardless of exactly what expectedEffective rounds to — no adaptive-model math to
            // hand-simulate.
            val monstera = plant(wateringIntervalDays = expectedEffective)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = peakDay, wateringFeedback = null),
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = elevenDaysBeforePeak,
                    wateringFeedback = null
                )
            )

            val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

            assertEquals(null, outcome.suggestion)
        }

    @Test
    fun `quickWaterWithReason's suggestedIntervalEffective is the base-to-effective conversion`() = runTest {
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val useCase = useCaseWithSeasonOnLegacyPath(peakDay)
        val elevenDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(11)

        val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant(wateringIntervalDays = 10).copy(wateringBaseIntervalDays = 10.0),
            nowDate = LocalDate.of(2023, 1, 5),
            seasonalAmplitude = SeasonalAmplitude.STANDARD.value
        ) ?: 10
        // A current interval deliberately different from expectedEffective, so the suggestion isn't
        // suppressed by the #620 gate covered above, while still keeping current >= actual (11) so
        // the raw suggestion stays the same deterministic 10.
        val monstera = plant(wateringIntervalDays = expectedEffective + 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = peakDay, wateringFeedback = null),
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = elevenDaysBeforePeak,
                wateringFeedback = null
            )
        )

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

        assertEquals(10, outcome.suggestion?.suggestedInterval)
        assertEquals(expectedEffective, outcome.suggestion?.suggestedIntervalEffective)
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}

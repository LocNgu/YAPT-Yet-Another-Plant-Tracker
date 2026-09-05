package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.R
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * #654 loggedAt-threading coverage, split out of [QuickLogUseCaseTest] to keep that file under
 * Detekt's `LargeClass` threshold — mirrors [QuickLogUseCaseSeasonalTest]'s precedent.
 *
 * Plant Detail's "Log watering" date picker can pass an explicit `loggedAt` (a chosen date, not
 * necessarily "now") into [QuickLogUseCase.quickWaterWithReason]/[QuickLogUseCase
 * .quickLiquidFertilizeWithReason]; these tests assert that value drives the same-day duplicate
 * guard, the [CareLog.loggedAt] write, and the adaptive-gap math consistently — none of the three
 * can silently fall back to the real wall-clock "now".
 */
class QuickLogUseCaseBackdateTest {

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_watered_and_fertilized, any())
        } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_already_watered, any())
        } answers { "Already watered ${(args[1] as Array<*>)[0]} today" }
        every {
            getString(R.string.quick_log_already_fertilized, any())
        } answers { "Already fertilized ${(args[1] as Array<*>)[0]} today" }
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private lateinit var useCase: QuickLogUseCase

    private val backdated = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)

    private fun plant(useLiquidFertilizer: Boolean = false) = Plant(
        id = 1L,
        name = "Monstera",
        useLiquidFertilizer = useLiquidFertilizer,
        wateringIntervalDays = 7,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setup() {
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        useCase = QuickLogUseCase(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, database, wateringAdjustmentRepo
        )
    }

    @Test
    fun `quickWaterWithReason with a backdated loggedAt writes that date to the CareLog`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, null, loggedAt = backdated)

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.loggedAt == backdated })
        }
    }

    @Test
    fun `quickWaterWithReason checks the duplicate guard against the backdated day, not today`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        // Only the backdated day already has a log; "today" (the setup() default) does not.
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, backdated, null) } returns true

        val outcome = useCase.quickWaterWithReason(monstera, null, loggedAt = backdated)

        assertFalse(outcome.logged)
        assertEquals("Already watered Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickWaterWithReason with a backdated loggedAt feeds the adaptive model that same date, not now`() = runTest {
        val adaptiveDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        useCase = QuickLogUseCase(
            application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo
        )
        val monstera = plant().copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = backdated - TimeUnit.DAYS.toMillis(7)),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = backdated - TimeUnit.DAYS.toMillis(14))
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        useCase.quickWaterWithReason(monstera, null, loggedAt = backdated)

        coVerify { wateringAdjustmentRepo.addAdjustment(match { it.triggeredAt == backdated }) }
    }

    /**
     * BLOCKING review fix (#654 round 1): [QuickLogUseCase.adaptWateringInterval]'s call to its private
     * de-seasonalization helper used to evaluate the season at [QuickLogUseCase]'s `nowProvider()`
     * (real wall-clock "now") rather than the caller's backdated `loggedAt` — neither
     * [QuickLogUseCaseSeasonalTest] (never backdates) nor the rest of this file (never enables
     * `SEASONAL_WATERING`) combined both dimensions to catch it. `nowProvider` is pinned to a summer
     * day while `loggedAt` is a winter day so the two seasons' de-seasonalized values provably differ;
     * asserting against the winter (loggedAt) value fails if the helper reverts to nowProvider().
     */
    @Test
    fun `quickWaterWithReason with SEASONAL_WATERING on de-seasonalizes using the backdated loggedAt's season`() =
        runTest {
            val nowProviderDay = localDateUtcMillis(2023, 7, 5) // northern summer — real "now"
            val loggedAtDay = localDateUtcMillis(2023, 1, 5) // northern winter — the backdated pick
            val seasonalDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(
                    preferencesOf(
                        FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true,
                        FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true
                    )
                )
            }
            useCase = QuickLogUseCase(
                application, plantRepo, careLogRepo, plantPhotoRepo, seasonalDataStore, database,
                wateringAdjustmentRepo, nowProvider = { nowProviderDay }
            )
            val monstera = plant().copy(wateringConfidence = 2)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = loggedAtDay),
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = loggedAtDay - TimeUnit.DAYS.toMillis(20))
            )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            useCase.quickWaterWithReason(monstera, null, loggedAt = loggedAtDay)

            val fromLoggedAtSeason = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val fromNowProviderSeason = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 7, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            assertTrue(fromLoggedAtSeason != fromNowProviderSeason)
            val expected = CareSchedule.computeAdaptiveInterval(
                feedback = null,
                observedIntervalDays = fromLoggedAtSeason,
                currentBaseIntervalDays = 7,
                currentConfidence = 2,
                recentFeedback = emptyList()
            )
            coVerify {
                wateringAdjustmentRepo.addAdjustment(match { it.afterIntervalDays == expected.intervalDays })
            }
        }

    // quickLiquidFertilizeWithReason mirrors the same two checks.

    @Test
    fun `quickLiquidFertilizeWithReason with a backdated loggedAt writes that date to both CareLogs`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLiquidFertilizeWithReason(monstera, null, loggedAt = backdated)

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.FERTILIZE && it.loggedAt == backdated })
        }
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.loggedAt == backdated })
        }
    }

    @Test
    fun `quickLiquidFertilizeWithReason checks the duplicate guard against the backdated day, not today`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, backdated, null) } returns true

        val outcome = useCase.quickLiquidFertilizeWithReason(monstera, null, loggedAt = backdated)

        assertFalse(outcome.logged)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}

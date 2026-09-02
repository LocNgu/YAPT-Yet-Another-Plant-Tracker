package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [QuickLogUseCase.applyWateringIntervalSuggestion] math-correctness coverage (#631) — split out of
 * [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold, mirroring
 * [QuickLogUseCaseSeasonalTest]'s precedent. This is the single write path the ADR-0006 suggestion
 * dialog's Apply button uses from all three surfaces (Plant Detail, Calendar, Plant List) — these
 * tests used to live against `PlantDetailViewModel.applyIntervalInternal()` directly in
 * `PlantDetailViewModelSeasonalTest` before the #572/#626 fixes it already carried were extracted here
 * so Calendar/Plant List could share them instead of reproducing the same bugs independently.
 */
class QuickLogUseCaseIntervalApplyTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk()

    @Before
    fun setUp() {
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun useCase(vararg flags: Preferences.Pair<Boolean>): QuickLogUseCase {
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                if (flags.isEmpty()) emptyPreferences() else preferencesOf(*flags)
            )
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `applyWateringIntervalSuggestion with SEASONAL_WATERING on and unpinned updates wateringBaseIntervalDays`() =
        runTest {
            // #572 regression: this write previously touched only wateringIntervalDays, so once
            // SEASONAL_WATERING is on, CareSchedule.effectiveWateringIntervalDays() kept reading the
            // stale wateringBaseIntervalDays and the due date never moved — the whole point of the "Why
            // this date?" sheet's "every number matches CareSchedule" acceptance criterion.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 7.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 14)

            // newInterval (14) is this class's own adaptive suggestion, already season-neutral
            // (base-space) — the base must be set to it *directly*, exactly 14.0, never
            // re-deseasonalized (which would silently corrupt it on any day where season(today) != 1.0).
            coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 14.0 }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion with SEASONAL_WATERING on writes an effective-space wateringIntervalDays`() =
        runTest {
            // #626 regression: this write previously wrote the raw base-space newInterval straight into
            // wateringIntervalDays, even though every other read site (the suggestion dialog's
            // "currently" figure, the Water tab slider/"every N days" text, WateringExplanationBuilder)
            // treats wateringIntervalDays as an effective, seasonally-adjusted value — silently drifting
            // the literal interval a little further on every single apply.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 7.0)
            val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant = monstera.copy(wateringBaseIntervalDays = 14.0, wateringIntervalDays = 14),
                seasonalAmplitude = SeasonalAmplitude.STANDARD.value
            )

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 14)

            coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == expectedEffective }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion with SEASONAL_WATERING off leaves wateringBaseIntervalDays untouched`() =
        runTest {
            // #584 review round 2: ADAPTIVE_WATERING/SEASONAL_WATERING are independent flags. With
            // season off, newInterval is this class's literal suggestion (not base-space) — writing it
            // straight into wateringBaseIntervalDays would clobber a real prior base (6.0, established
            // while season was previously on) with the literal 8.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 8)

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 8 && it.wateringBaseIntervalDays == 6.0 })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion logs DIALOG_EDIT's beforeIntervalDays in base-space, not stale literal`() =
        runTest {
            // #584 review: mirrors QuickLogUseCaseSeasonalTest's "adapts against wateringBaseIntervalDays,
            // not stale wateringIntervalDays" case with the identical plant shape (wateringIntervalDays=10
            // literal, wateringBaseIntervalDays=6.0 true base) — a WATER_*-triggered row and this
            // DIALOG_EDIT-triggered row must both log beforeIntervalDays=6, never the stale literal 10,
            // so "Recent adjustments" never mixes units for the same underlying change.
            val useCase = useCase(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true,
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true
            )
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

            coVerify {
                wateringAdjustmentRepo.addAdjustment(match { it.beforeIntervalDays == 6 && it.afterIntervalDays == 9 })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion on a pinned plant leaves wateringBaseIntervalDays untouched`() = runTest {
        val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        val monstera = plant().copy(wateringIntervalDays = 7, pinIntervalToBase = true, wateringBaseIntervalDays = null)

        useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 14)

        coVerify {
            plantRepo.updatePlant(match { it.wateringIntervalDays == 14 && it.wateringBaseIntervalDays == null })
        }
    }

    @Test
    fun `applyWateringIntervalSuggestion with ADAPTIVE_WATERING off writes no adjustment row`() = runTest {
        val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        val monstera = plant().copy(wateringIntervalDays = 7)

        useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `applyWateringIntervalSuggestion returns the plant's actual prior interval and base`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 5.0)

        val result = useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

        assertEquals(7, result.previousEffectiveIntervalDays)
        assertEquals(5.0, result.previousBaseIntervalDays)
        assertEquals(9, result.newEffectiveIntervalDays)
    }

    @Test
    fun `applyWateringIntervalSuggestion with a retyped interval outside tolerance lowers confidence`() = runTest {
        val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
        val monstera = plant().copy(wateringIntervalDays = 7).copy(wateringConfidence = 3)
        val expectedConfidence = CareSchedule.confidenceAfterDialogEdit(
            confidence = 3,
            suggestedIntervalDays = 10,
            appliedIntervalDays = 2
        )

        useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = 10, newInterval = 2)

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == expectedConfidence }) }
    }
}

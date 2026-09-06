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
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.toLocalDate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [QuickLogUseCase.applyWateringIntervalSuggestion] math-correctness coverage (#631, updated #644) —
 * split out of [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold, mirroring
 * [QuickLogUseCaseSeasonalTest]'s precedent. This is the single write path the ADR-0006 suggestion
 * dialog's Apply button uses from all three surfaces (Plant Detail, Calendar, Plant List).
 *
 * #644 flipped `newInterval`'s meaning from base-space to *effective*-space (the dialog's editable text
 * field now mirrors the "Suggested: N days" sentence instead of the raw base suggestion) — every test
 * here reflects that new contract; `originalSuggestion` is unchanged, still base-space.
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
    fun `applyWateringIntervalSuggestion writes newInterval straight into wateringIntervalDays`() =
        runTest {
            // #644: newInterval is now effective-space (the dialog's editable field mirrors the
            // "Suggested: N days" sentence built from the same value), so it must be written directly —
            // unlike pre-#644 where a base-space input was run through
            // effectiveWateringIntervalDaysForDisplay before the write.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 18, wateringBaseIntervalDays = 18.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 13)

            coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 13 }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion with SEASONAL_WATERING on derives base from the effective newInterval`() =
        runTest {
            // #572/#644: wateringBaseIntervalDays must be the *inverse* seasonal conversion of the
            // now-effective newInterval, not newInterval written straight through as it was pre-#644.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 18, wateringBaseIntervalDays = 18.0)
            val expectedBase = SeasonalWatering.deseasonalize(
                13.0,
                System.currentTimeMillis().toLocalDate(),
                SeasonalAmplitude.STANDARD.value,
                SeasonalWatering.currentHemisphere()
            )

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 13)

            coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == expectedBase }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion with SEASONAL_WATERING off leaves wateringBaseIntervalDays untouched`() =
        runTest {
            // #584 review round 2, still true post-#644: with season off, newInterval is a literal
            // value (base == effective) — writing it straight into wateringBaseIntervalDays would
            // clobber a real prior base (6.0, established while season was previously on) with the
            // literal 8.
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 8)

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 8 && it.wateringBaseIntervalDays == 6.0 })
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
    fun `applyWateringIntervalSuggestion logs DIALOG_EDIT's beforeIntervalDays in base-space, not stale literal`() =
        runTest {
            // #584 review: mirrors QuickLogUseCaseSeasonalTest's "adapts against wateringBaseIntervalDays,
            // not stale wateringIntervalDays" case with the identical plant shape (wateringIntervalDays=10
            // literal, wateringBaseIntervalDays=6.0 true base) — a WATER_*-triggered row and this
            // DIALOG_EDIT-triggered row must both log beforeIntervalDays=6, never the stale literal 10,
            // so "Recent adjustments" never mixes units for the same underlying change.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

            coVerify {
                wateringAdjustmentRepo.addAdjustment(match { it.beforeIntervalDays == 6 })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion logs DIALOG_EDIT's afterIntervalDays derived from newInterval`() =
        runTest {
            // #644: afterIntervalDays deliberately stays base-space (the model's own accounting) — it
            // must now be *derived* from the effective newInterval rather than passed straight through,
            // since newInterval itself is effective-space post-#644.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
            val expectedAfterBaseSpace = SeasonalWatering.deseasonalize(
                9.0,
                System.currentTimeMillis().toLocalDate(),
                SeasonalAmplitude.STANDARD.value,
                SeasonalWatering.currentHemisphere()
            ).roundToInt()

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

            coVerify {
                wateringAdjustmentRepo.addAdjustment(match { it.afterIntervalDays == expectedAfterBaseSpace })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion returns prior interval and base, and echoes newInterval back`() =
        runTest {
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 5.0)

            val result = useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = null, newInterval = 9)

            assertEquals(7, result.previousEffectiveIntervalDays)
            assertEquals(5.0, result.previousBaseIntervalDays)
            // #644: newInterval is already effective-space, so it's echoed straight back rather than
            // recomputed via effectiveWateringIntervalDaysForDisplay as it was pre-#644.
            assertEquals(9, result.newEffectiveIntervalDays)
        }

    @Test
    fun `applyWateringIntervalSuggestion with a retyped interval outside tolerance lowers confidence`() = runTest {
        // SEASONAL_WATERING stays off (amplitude 0) here, so effective == base-space and this exercises
        // the tolerance check's basic behavior without needing the #644 conversion step.
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7).copy(wateringConfidence = 3)
        val expectedConfidence = CareSchedule.confidenceAfterDialogEdit(
            confidence = 3,
            suggestedIntervalDays = 10,
            appliedIntervalDays = 2
        )

        useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = 10, newInterval = 2)

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == expectedConfidence }) }
    }

    @Test
    fun `applyWateringIntervalSuggestion converts effective newInterval to base-space for the confidence check`() =
        runTest {
            // #644: originalSuggestion is base-space but newInterval is now effective-space — comparing
            // them directly would misclassify an untouched apply (the dialog's unedited pre-filled value)
            // as a large edit whenever the seasonal multiplier isn't 1.0, wrongly lowering confidence.
            val useCase = useCase(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            val monstera = plant().copy(
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 10.0,
                wateringConfidence = 3
            )
            val amplitude = SeasonalAmplitude.STANDARD.value
            val hemisphere = SeasonalWatering.currentHemisphere()
            val today = System.currentTimeMillis().toLocalDate()
            // The effective value an *unedited* application of the base-space suggestion (10) would
            // produce — mirrors what the dialog's text field would have been pre-filled with.
            val uneditedEffective = SeasonalWatering.effectiveInterval(10.0, today, amplitude, hemisphere)

            useCase.applyWateringIntervalSuggestion(monstera, originalSuggestion = 10, newInterval = uneditedEffective)

            // An untouched apply must never lower confidence, regardless of what the seasonal multiplier
            // did to the displayed/applied number.
            coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 3 }) }
        }
}

package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.toLocalDate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [QuickLogUseCase.applyWateringIntervalSuggestion] math-correctness coverage (#631, updated #644) —
 * split out of [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold, mirroring
 * [QuickLogUseCaseSeasonalTest]'s precedent. This is the single write path the product ADR-0006 suggestion
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

    /**
     * Amplitude defaults to STANDARD (graduated, #656); pass `amplitudeOff = true` to exercise the Off
     * path. [nowProvider] defaults to real wall-clock time, mirroring the production default — pass a
     * fixed one to pin the date a seasonal conversion is evaluated at (#718).
     */
    private fun useCase(
        amplitudeOff: Boolean = false,
        nowProvider: () -> Long = System::currentTimeMillis
    ): QuickLogUseCase {
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                if (amplitudeOff) preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to "OFF") else emptyPreferences()
            )
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            database,
            wateringAdjustmentRepo,
            nowProvider
        )
    }

    @Test
    fun `applyWateringIntervalSuggestion writes newInterval straight into wateringIntervalDays`() =
        runTest {
            // #644: newInterval is now effective-space (the dialog's editable field mirrors the
            // "Suggested: N days" sentence built from the same value), so it must be written directly —
            // unlike pre-#644 where a base-space input was run through
            // effectiveWateringIntervalDaysForDisplay before the write.
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 18, wateringBaseIntervalDays = 18.0)

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 13,
                suggestedBaseInterval = null
            )

            coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 13 }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion derives base from the effective newInterval`() =
        runTest {
            // #572/#644: wateringBaseIntervalDays must be the *inverse* seasonal conversion of the
            // now-effective newInterval, not newInterval written straight through as it was pre-#644.
            // #718: suggestedBaseInterval = null is the caller's signal that the user retyped the
            // dialog's field (PlantDetailIntervalActions/CalendarScreen/PlantListScreen only pass a
            // non-null precise base when the field is untouched) — a typed number must still
            // de-seasonalize through this path, which is exactly what this test pins.
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 18, wateringBaseIntervalDays = 18.0)
            val expectedBase = SeasonalWatering.deseasonalize(
                13.0,
                System.currentTimeMillis().toLocalDate(),
                SeasonalAmplitude.STANDARD.value,
                SeasonalWatering.currentHemisphere()
            )

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 13,
                suggestedBaseInterval = null
            )

            coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == expectedBase }) }
        }

    @Test
    fun `applyWateringIntervalSuggestion with a precise suggestedBaseInterval persists it verbatim`() =
        runTest {
            // #718: an unedited apply must write the model's precise Double base as-is rather than
            // re-deriving it from the rounded effective display value — the worked example from the
            // issue (Standard amplitude, northern hemisphere, Sep 13: season ≈ 0.866). The model's raw
            // result was 8.85 (an observation that recommended *shortening*); the pre-#718 path instead
            // wrote round(9 × 0.866) = 8 back through deseasonalize(), landing on ≈9.234 — a ratchet in
            // the opposite direction of what the model actually said.
            val sep13 = localDateUtcMillis(2026, 9, 13)
            // The precise-base branch below never calls nowProvider() — this pin only matters if a
            // regression falls through to deseasonalize(), so it fails against a fixed ≈9.234 rather
            // than whatever season happens to run.
            val useCase = useCase(nowProvider = { sep13 })
            val monstera = plant().copy(wateringIntervalDays = 9, wateringBaseIntervalDays = 8.718)
            val preciseModelBase = 8.85
            val newInterval = 8

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = newInterval,
                suggestedBaseInterval = preciseModelBase
            )

            val ratchetedBase = SeasonalWatering.deseasonalize(
                newInterval.toDouble(),
                sep13.toLocalDate(),
                SeasonalAmplitude.STANDARD.value,
                SeasonalWatering.currentHemisphere()
            )
            // The two must genuinely differ, or this case would silently become vacuous.
            assertTrue(abs(preciseModelBase - ratchetedBase) > 0.01)
            coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == preciseModelBase }) }
        }

    @Test
    fun `unedited precise-base apply keeps the written literal aligned with the schedule`() = runTest {
        // #768: this deliberately hits the double-rounding disagreement from #718's second opinion.
        // At July's seasonal extreme, 9.5 produces different effective intervals depending on
        // whether the base is rounded first. The case works in either hemisphere: Standard amplitude
        // is approximately 0.65 or 1.35 here. An unedited apply must derive the literal from the same
        // precise base it persists, or the UI and the authoritative due-date schedule disagree.
        val applyDate = LocalDate.of(2026, 7, 6)
        val applyAt = applyDate.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(applyDate, applyAt.toLocalDate())
        val amplitude = SeasonalAmplitude.STANDARD.value
        val hemisphere = SeasonalWatering.currentHemisphere()
        val preciseModelBase = 9.5
        val effectiveFromPreciseBase = SeasonalWatering.effectiveInterval(
            preciseModelBase,
            applyDate,
            amplitude,
            hemisphere
        )
        val effectiveFromRoundedBase = SeasonalWatering.effectiveInterval(
            preciseModelBase.roundToInt().toDouble(),
            applyDate,
            amplitude,
            hemisphere
        )
        assertNotEquals(effectiveFromRoundedBase, effectiveFromPreciseBase)

        val useCase = useCase(nowProvider = { applyAt })
        val monstera = plant().copy(
            wateringIntervalDays = effectiveFromRoundedBase,
            wateringBaseIntervalDays = preciseModelBase,
            wateringConfidence = 1
        )
        every { plantRepo.getPlantById(monstera.id) } returns flowOf(monstera)
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.getLastTwoWaterings(monstera.id) } returns emptyList()
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastWateringBefore(monstera.id, applyAt) } returns CareLog(
            plantId = monstera.id,
            careType = CareType.WATER,
            loggedAt = applyAt - TimeUnit.DAYS.toMillis(20)
        )
        coEvery { careLogRepo.getRecentWaterings(monstera.id, limit = 3) } returns emptyList()

        // The null off-schedule observation leaves the precise base unchanged, but still exercises
        // computeSuggestion's production display conversion. If that conversion rounded the base
        // first, it would equal the plant's current literal and suppress the suggestion entirely.
        val suggestion = requireNotNull(
            useCase.quickWaterWithReason(monstera, reason = null, loggedAt = applyAt).suggestion
        )
        assertEquals(preciseModelBase, suggestion.suggestedBaseInterval, 0.0)
        assertEquals(effectiveFromPreciseBase, suggestion.suggestedIntervalEffective)

        useCase.applyWateringIntervalSuggestion(
            monstera,
            originalSuggestion = suggestion.suggestedInterval,
            newInterval = suggestion.suggestedIntervalEffective,
            suggestedBaseInterval = suggestion.suggestedBaseInterval
        )

        val writtenPlant = slot<Plant>()
        coVerify(exactly = 1) { plantRepo.updatePlant(capture(writtenPlant)) }
        val effectiveFromWrittenBase = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = writtenPlant.captured,
            nowDate = applyDate,
            seasonalAmplitude = amplitude,
            hemisphere = hemisphere
        )
        assertEquals(writtenPlant.captured.wateringIntervalDays, effectiveFromWrittenBase)
    }

    @Test
    fun `applyWateringIntervalSuggestion with amplitude Off leaves wateringBaseIntervalDays untouched`() =
        runTest {
            // #584 review round 2, still true post-#644: with amplitude Off, newInterval is a literal
            // value (base == effective) — writing it straight into wateringBaseIntervalDays would
            // clobber a real prior base (6.0, established while season was previously on) with the
            // literal 8.
            val useCase = useCase(amplitudeOff = true)
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 8,
                suggestedBaseInterval = null
            )

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 8 && it.wateringBaseIntervalDays == 6.0 })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion with amplitude Off ignores a non-null suggestedBaseInterval`() =
        runTest {
            // #718 guard: suggestedBaseInterval must only ever apply on the seasonAdjustable path —
            // passing a non-null precise base must not break the amplitude-Off/pinned posture's
            // "leave the stored base untouched" contract (#584 review round 2).
            val useCase = useCase(amplitudeOff = true)
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 8,
                suggestedBaseInterval = 8.85
            )

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 8 && it.wateringBaseIntervalDays == 6.0 })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion on a pinned plant leaves wateringBaseIntervalDays untouched`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, pinIntervalToBase = true, wateringBaseIntervalDays = null)

        useCase.applyWateringIntervalSuggestion(
            monstera,
            originalSuggestion = null,
            newInterval = 14,
            suggestedBaseInterval = null
        )

        coVerify {
            plantRepo.updatePlant(match { it.wateringIntervalDays == 14 && it.wateringBaseIntervalDays == null })
        }
    }

    @Test
    fun `applyWateringIntervalSuggestion on a pinned plant ignores a non-null suggestedBaseInterval`() = runTest {
        // #718 guard: a non-null precise base must not clobber a pinned plant's stored base — the
        // gate is `!plant.pinIntervalToBase && amplitude != 0.0`, and suggestedBaseInterval is only
        // ever consulted once that gate has already passed.
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, pinIntervalToBase = true, wateringBaseIntervalDays = null)

        useCase.applyWateringIntervalSuggestion(
            monstera,
            originalSuggestion = null,
            newInterval = 14,
            suggestedBaseInterval = 8.85
        )

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
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 9,
                suggestedBaseInterval = null
            )

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
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
            val expectedAfterBaseSpace = SeasonalWatering.deseasonalize(
                9.0,
                System.currentTimeMillis().toLocalDate(),
                SeasonalAmplitude.STANDARD.value,
                SeasonalWatering.currentHemisphere()
            ).roundToInt()

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 9,
                suggestedBaseInterval = null
            )

            coVerify {
                wateringAdjustmentRepo.addAdjustment(match { it.afterIntervalDays == expectedAfterBaseSpace })
            }
        }

    @Test
    fun `applyWateringIntervalSuggestion returns prior interval and base, and echoes newInterval back`() =
        runTest {
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 5.0)

            val result = useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = null,
                newInterval = 9,
                suggestedBaseInterval = null
            )

            assertEquals(7, result.previousEffectiveIntervalDays)
            assertEquals(5.0, result.previousBaseIntervalDays)
            // #644: newInterval is already effective-space, so it's echoed straight back rather than
            // recomputed via effectiveWateringIntervalDaysForDisplay as it was pre-#644.
            assertEquals(9, result.newEffectiveIntervalDays)
        }

    @Test
    fun `applyWateringIntervalSuggestion with a retyped interval outside tolerance lowers confidence`() = runTest {
        // Amplitude stays Off here, so effective == base-space and this exercises the tolerance
        // check's basic behavior without needing the #644 conversion step.
        val useCase = useCase(amplitudeOff = true)
        val monstera = plant().copy(wateringIntervalDays = 7).copy(wateringConfidence = 3)
        val expectedConfidence = CareSchedule.confidenceAfterDialogEdit(
            confidence = 3,
            suggestedIntervalDays = 10,
            appliedIntervalDays = 2
        )

        useCase.applyWateringIntervalSuggestion(
            monstera,
            originalSuggestion = 10,
            newInterval = 2,
            suggestedBaseInterval = null
        )

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == expectedConfidence }) }
    }

    @Test
    fun `applyWateringIntervalSuggestion converts effective newInterval to base-space for the confidence check`() =
        runTest {
            // #644: originalSuggestion is base-space but newInterval is now effective-space — comparing
            // them directly would misclassify an untouched apply (the dialog's unedited pre-filled value)
            // as a large edit whenever the seasonal multiplier isn't 1.0, wrongly lowering confidence.
            val useCase = useCase()
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

            useCase.applyWateringIntervalSuggestion(
                monstera,
                originalSuggestion = 10,
                newInterval = uneditedEffective,
                suggestedBaseInterval = null
            )

            // An untouched apply must never lower confidence, regardless of what the seasonal multiplier
            // did to the displayed/applied number.
            coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 3 }) }
        }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}

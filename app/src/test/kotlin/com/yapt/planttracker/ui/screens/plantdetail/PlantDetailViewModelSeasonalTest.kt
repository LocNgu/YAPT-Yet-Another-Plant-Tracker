package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * "Pin interval" / `wateringBaseIntervalDays` de-seasonalization coverage for [PlantDetailViewModel]
 * (#569), split out of `PlantDetailViewModelTest` to keep that file under Detekt's `LargeClass`
 * threshold — mirrors `PlantDetailViewModelPlantIssueTest`'s precedent.
 */
class PlantDetailViewModelSeasonalTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
        coEvery { addAdjustment(any()) } returns 1L
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(plantId: Long = 1L): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plantId) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plantId) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(plantId) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(plantId) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plantId,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `setWateringInterval with SEASONAL_WATERING off leaves wateringBaseIntervalDays untouched`() = runTest {
        val monstera = plant().copy(wateringBaseIntervalDays = 5.18)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setWateringInterval(10)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 5.18 }) }
    }

    @Test
    fun `setWateringInterval with SEASONAL_WATERING on de-seasonalizes the new value`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setWateringInterval(10)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays != null }) }
    }

    @Test
    fun `setWateringInterval with SEASONAL_WATERING on but plant pinned leaves base untouched`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val pinnedPlant = plant().copy(pinIntervalToBase = true, wateringBaseIntervalDays = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(pinnedPlant)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(pinnedPlant, awaitItem())
            vm.setWateringInterval(10)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == null }) }
    }

    // Math-correctness coverage for applySuggestedInterval's underlying write (the three tests
    // previously here: SEASONAL_WATERING on/unpinned updating wateringBaseIntervalDays, SEASONAL_WATERING
    // on writing an effective-space wateringIntervalDays, SEASONAL_WATERING off leaving
    // wateringBaseIntervalDays untouched) now lives in QuickLogUseCaseIntervalApplyTest, against
    // QuickLogUseCase.applyWateringIntervalSuggestion() directly (#631) — applySuggestedInterval() is a
    // thin delegation to that shared function now, tested as such in PlantDetailViewModelTest.

    @Test
    fun `undoSilentIntervalApply restores the exact captured prior interval and base`() =
        runTest {
            // #626 regression: undoSilentIntervalApply() used to recompute wateringBaseIntervalDays on
            // undo as beforeIntervalDays.toDouble() — correct only by the pre-fix bug's coincidence that
            // beforeIntervalDays happened to already be base-space. Now that
            // QuickLogUseCase.applyWateringIntervalSuggestion() writes a genuine effective value there,
            // deriving the base from it on undo would silently corrupt a real prior base.
            // beforeBaseIntervalDays must be threaded through from the actual prior
            // Plant.wateringBaseIntervalDays and restored as-is.
            val monstera = plant().copy(wateringIntervalDays = 9, wateringBaseIntervalDays = 6.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.undoSilentIntervalApply(beforeIntervalDays = 7, beforeBaseIntervalDays = 6.0)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == 6.0 })
            }
        }

    @Test
    fun `undoSilentIntervalApply restores a null captured prior base rather than deriving one from the literal`() =
        runTest {
            // #626: the corruption risk is sharpest here — deriving a base from beforeIntervalDays.toDouble()
            // would fabricate a non-null base for a plant that genuinely never had one recorded.
            val monstera = plant().copy(wateringIntervalDays = 14, wateringBaseIntervalDays = null)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.undoSilentIntervalApply(beforeIntervalDays = 7, beforeBaseIntervalDays = null)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == null })
            }
        }

    @Test
    fun `undoSilentIntervalApply with SEASONAL_WATERING on and unpinned restores the captured base verbatim`() =
        runTest {
            // #626 regression, distinguishing case: the old buggy formula
            // (`if (!p.pinIntervalToBase && amplitude != 0.0) beforeIntervalDays.toDouble() else
            // p.wateringBaseIntervalDays`) only diverges from "restore verbatim" when SEASONAL_WATERING is
            // on and the plant is unpinned — exactly this setup. beforeIntervalDays (7) and
            // beforeBaseIntervalDays (6.0) are deliberately different values; the old formula would have
            // written 7.0 into wateringBaseIntervalDays, clobbering the real captured base of 6.0.
            every { dataStore.data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
            val monstera = plant()
                .copy(wateringIntervalDays = 9, pinIntervalToBase = false, wateringBaseIntervalDays = 8.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.undoSilentIntervalApply(beforeIntervalDays = 7, beforeBaseIntervalDays = 6.0)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                plantRepo.updatePlant(match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == 6.0 })
            }
        }

    @Test
    fun `setWateringInterval logs the MANUAL_EDIT row's before-after in base-space, not the stale literal`() = runTest {
        // #584 review: mirrors AddEditPlantViewModel.saveEdit()'s equivalent MANUAL_EDIT fix — the row
        // must use the true base (6, from wateringBaseIntervalDays), not the stale literal
        // wateringIntervalDays (10), for either side.
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setWateringInterval(9)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.beforeIntervalDays == 6 })
        }
    }

    // "applySuggestedInterval logs the DIALOG_EDIT row's beforeIntervalDays in base-space, not the
    // stale literal" moved to QuickLogUseCaseIntervalApplyTest (#631), same reasoning as above.

    @Test
    fun `undoSilentIntervalApply writes a SILENT_APPLY_UNDONE row`() = runTest {
        // #584 review: the undo must not leave "Recent adjustments" looking like the original silent
        // apply still stands — a compensating row records the revert itself, before = the
        // silently-applied value being undone (9), after = the restored original (7).
        val monstera = plant().copy(wateringIntervalDays = 9)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoSilentIntervalApply(7, null)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.SILENT_APPLY_UNDONE &&
                        it.beforeIntervalDays == 9 &&
                        it.afterIntervalDays == 7
                }
            )
        }
    }

    // "applySuggestedInterval on a pinned plant leaves wateringBaseIntervalDays untouched" moved to
    // QuickLogUseCaseIntervalApplyTest (#631), same reasoning as above.

    @Test
    fun `dismissSuggestedInterval logs the DIALOG_DISMISSAL row's before-after in base-space, not the stale literal`() =
        runTest {
            // #584 review: mirrors the DIALOG_EDIT/MANUAL_EDIT fixes — the row must use the true base
            // (6, from wateringBaseIntervalDays) rather than the stale literal wateringIntervalDays (10).
            every { dataStore.data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.dismissSuggestedInterval()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.beforeIntervalDays == 6 && it.afterIntervalDays == 6 }
                )
            }
        }

    @Test
    fun `pendingWateringSuggestion converts base-space suggestion to effective space`() =
        runTest {
            // #620 repro: with SEASONAL_WATERING on, suggestedWateringInterval (9) is base-space, while
            // plant.wateringIntervalDays is already seasonally-adjusted. The dialog previously compared
            // the two directly, producing a misleading multi-day jump that was really just a unit
            // mismatch. The effective value must equal CareSchedule's own base->effective conversion of
            // the suggestion — never the raw 9 — so the two rows can't drift. `current` is deliberately
            // derived from `expectedEffective` (not a hardcoded literal) so this test can't accidentally
            // land on today's actual date rounding the two to the same value (which is itself correct
            // behaviour, covered by a separate "collapses to null" test below).
            every { dataStore.data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
            val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant = plant().copy(wateringBaseIntervalDays = 9.0, wateringIntervalDays = 9),
                seasonalAmplitude = SeasonalAmplitude.STANDARD.value
            )
            val current = (expectedEffective ?: 9) - 1
            val monstera = plant().copy(wateringIntervalDays = current, wateringBaseIntervalDays = current.toDouble())
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            vm.suggestedWateringInterval.value = 9

            vm.pendingWateringSuggestion.test {
                val suggestion = awaitItem()
                assertEquals(9, suggestion?.rawIntervalDays)
                assertEquals(expectedEffective, suggestion?.effectiveIntervalDays)
                assertEquals(current, suggestion?.currentIntervalDays)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pendingWateringSuggestion's effective interval equals the raw suggestion when the plant is pinned`() =
        runTest {
            // Spec edge case: effectiveWateringIntervalDaysForDisplay collapses to identity when pinned —
            // no special-casing needed here, it falls out of reusing the same function.
            every { dataStore.data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
            val monstera = plant()
                .copy(wateringIntervalDays = 7, pinIntervalToBase = true, wateringBaseIntervalDays = null)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            vm.suggestedWateringInterval.value = 9

            vm.pendingWateringSuggestion.test {
                assertEquals(9, awaitItem()?.effectiveIntervalDays)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pendingWateringSuggestion's effective interval equals the raw suggestion when SEASONAL_WATERING is off`() =
        runTest {
            // dataStore's default `every { data }` (set in the field mock above) reads empty preferences, so
            // SEASONAL_WATERING is off and amplitude is 0.0 — identity conversion, same as the pinned case.
            val monstera = plant().copy(wateringIntervalDays = 7)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            vm.suggestedWateringInterval.value = 9

            vm.pendingWateringSuggestion.test {
                assertEquals(9, awaitItem()?.effectiveIntervalDays)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pendingWateringSuggestion is null when there is no pending suggestion`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.pendingWateringSuggestion.test {
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingWateringSuggestion is null when the effective suggestion equals current (unit-mismatch artifact)`() =
        runTest {
            // #620 round-2: the entire jump can be a pure base/effective unit-mismatch artifact — the
            // dialog must not appear at all in that case, not just show a "0 day" delta. SEASONAL_WATERING
            // off makes the base->effective conversion an identity, so a raw suggestion equal to the
            // literal current interval is guaranteed to also be equal after conversion.
            val monstera = plant().copy(wateringIntervalDays = 7)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            vm.suggestedWateringInterval.value = 7

            vm.pendingWateringSuggestion.test {
                assertEquals(null, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setPinIntervalToBase persists the pin flag via repo`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setPinIntervalToBase(true)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.pinIntervalToBase }) }
    }
}

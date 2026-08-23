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

    @Test
    fun `applySuggestedInterval with SEASONAL_WATERING on and unpinned updates wateringBaseIntervalDays`() = runTest {
        // #572 regression: applySuggestedInterval() previously wrote only wateringIntervalDays, so
        // once SEASONAL_WATERING is on, CareSchedule.effectiveWateringIntervalDays() kept reading the
        // stale wateringBaseIntervalDays and the due date never moved — the whole point of the "Why
        // this date?" sheet's "every number matches CareSchedule" acceptance criterion.
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 7.0)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.applySuggestedInterval(14)
            cancelAndIgnoreRemainingEvents()
        }

        // #584 review: newInterval (14) is QuickLogUseCase's adaptive suggestion, already season-neutral
        // (base-space) — the base must be set to it *directly*, exactly 14.0, never re-deseasonalized
        // (which would silently corrupt it to 14 / season(today) on any day where season(today) != 1.0).
        coVerify {
            plantRepo.updatePlant(match { it.wateringIntervalDays == 14 && it.wateringBaseIntervalDays == 14.0 })
        }
    }

    @Test
    fun `setWateringInterval logs the MANUAL_EDIT row's before-after in base-space, not the stale literal`() = runTest {
        // #584 review: mirrors AddEditPlantViewModel.saveEdit()'s equivalent MANUAL_EDIT fix — the row
        // must use the true base (6, from wateringBaseIntervalDays), not the stale literal
        // wateringIntervalDays (10), for either side.
        every { dataStore.data } returns flowOf(
            preferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true,
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true
            )
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

    @Test
    fun `applySuggestedInterval logs the DIALOG_EDIT row's beforeIntervalDays in base-space, not the stale literal`() =
        runTest {
            // #584 review: mirrors QuickLogUseCaseSeasonalTest's "adapts against wateringBaseIntervalDays,
            // not stale wateringIntervalDays" case with the identical plant shape (wateringIntervalDays=10
            // literal, wateringBaseIntervalDays=6.0 true base) — a WATER_*-triggered row and this
            // DIALOG_EDIT-triggered row must both log beforeIntervalDays=6, never the stale literal 10,
            // so "Recent adjustments" never mixes units for the same underlying change.
            every { dataStore.data } returns flowOf(
                preferencesOf(
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true,
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true
                )
            )
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.applySuggestedInterval(9)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.beforeIntervalDays == 6 && it.afterIntervalDays == 9 }
                )
            }
        }

    @Test
    fun `undoSilentIntervalApply with SEASONAL_WATERING on and unpinned restores the exact prior base`() = runTest {
        // #584 review: beforeIntervalDays is also already base-space (the plant's prior
        // wateringIntervalDays, itself written by applyIntervalInternal's direct assignment above) —
        // the restored base must be exactly beforeIntervalDays.toDouble(), never re-deseasonalized.
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val monstera = plant().copy(wateringIntervalDays = 14, wateringBaseIntervalDays = 14.0)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoSilentIntervalApply(7)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == 7.0 })
        }
    }

    @Test
    fun `undoSilentIntervalApply with ADAPTIVE_WATERING on writes a SILENT_APPLY_UNDONE row`() = runTest {
        // #584 review: the undo must not leave "Recent adjustments" looking like the original silent
        // apply still stands — a compensating row records the revert itself, before = the
        // silently-applied value being undone (9), after = the restored original (7).
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
        )
        val monstera = plant().copy(wateringIntervalDays = 9)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoSilentIntervalApply(7)
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

    @Test
    fun `undoSilentIntervalApply with ADAPTIVE_WATERING off writes no adjustment row`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 9)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoSilentIntervalApply(7)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `applySuggestedInterval on a pinned plant leaves wateringBaseIntervalDays untouched`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
        )
        val monstera = plant().copy(wateringIntervalDays = 7, pinIntervalToBase = true, wateringBaseIntervalDays = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.applySuggestedInterval(14)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringIntervalDays == 14 && it.wateringBaseIntervalDays == null })
        }
    }

    @Test
    fun `dismissSuggestedInterval logs the DIALOG_DISMISSAL row's before-after in base-space, not the stale literal`() =
        runTest {
            // #584 review: mirrors the DIALOG_EDIT/MANUAL_EDIT fixes — the row must use the true base
            // (6, from wateringBaseIntervalDays) rather than the stale literal wateringIntervalDays (10).
            every { dataStore.data } returns flowOf(
                preferencesOf(
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true,
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true
                )
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

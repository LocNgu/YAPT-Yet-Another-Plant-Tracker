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

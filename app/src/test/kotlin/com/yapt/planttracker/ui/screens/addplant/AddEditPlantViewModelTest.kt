package com.yapt.planttracker.ui.screens.addplant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
    }

    private fun plant(id: Long = 1L, name: String = "Monstera", species: String? = "M. deliciosa") = Plant(
        id = id,
        name = name,
        species = species,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Test
    fun `blank name emits ValidationError`() = runTest {
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = ""

        vm.events.test {
            vm.save()
            val event = awaitItem()
            assertTrue(event is AddEditPlantViewModel.Event.ValidationError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `valid name in new plant mode calls addPlant and emits Saved with new id`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 42L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Monstera"

        vm.events.test {
            vm.save()
            val event = awaitItem() as AddEditPlantViewModel.Event.Saved
            assertEquals(42L, event.plantId)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.addPlant(match { it.name == "Monstera" }) }
    }

    @Test
    fun `edit mode deletePlant archives plant and emits ArchivedForUndo`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.archivePlant(any(), any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        vm.events.test {
            vm.deletePlant()
            val event = awaitItem()
            assertTrue(event is AddEditPlantViewModel.Event.ArchivedForUndo)
            val archived = event as AddEditPlantViewModel.Event.ArchivedForUndo
            assertEquals(1L, archived.plantId)
            assertEquals("Monstera", archived.plantName)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.archivePlant(1L, any()) }
    }

    @Test
    fun `edit mode init populates name and species from loaded plant`() = runTest {
        val monstera = plant(name = "Monstera", species = "M. deliciosa")
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        assertEquals("Monstera", vm.name)
        assertEquals("M. deliciosa", vm.species)
    }

    @Test
    fun `edit mode save calls updatePlant not addPlant`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        vm.name = "Monstera Updated"

        vm.events.test {
            vm.save()
            val event = awaitItem() as AddEditPlantViewModel.Event.Saved
            assertEquals(1L, event.plantId)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.name == "Monstera Updated" }) }
        coVerify(exactly = 0) { plantRepo.addPlant(any()) }
    }

    @Test
    fun `useLiquidFertilizer true saved in new plant mode`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 5L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Pothos"
        vm.fertilizingIntervalEnabled = true
        vm.useLiquidFertilizer = true

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.addPlant(match { it.useLiquidFertilizer }) }
    }

    @Test
    fun `repotting interval saved when enabled in new plant mode`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 7L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Calathea"
        vm.repottingIntervalEnabled = true
        vm.repottingIntervalMonths = 12

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Repotting is picked in months and persisted as months * 30 days.
        coVerify {
            plantRepo.addPlant(match { it.repottingIntervalDays == 360 })
        }
    }

    @Test
    fun `disabled repotting interval is saved as null`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 8L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Snake Plant"
        // repottingIntervalMonths has a non-zero default but the toggle is off

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.addPlant(match { it.repottingIntervalDays == null })
        }
    }

    @Test
    fun `repotting interval round-trips through edit mode`() = runTest {
        val calathea = plant().copy(repottingIntervalDays = 720)
        every { plantRepo.getPlantById(1L) } returns flowOf(calathea)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        assertTrue(vm.repottingIntervalEnabled)
        // 720 stored days -> 24 months in the picker.
        assertEquals(24, vm.repottingIntervalMonths)

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.repottingIntervalDays == 720 })
        }
    }

    @Test
    fun `stored repotting days not a multiple of 30 round to the nearest month`() = runTest {
        // 405 days is 13.5 months — rounds up to 14 rather than truncating to 13. Only reachable
        // via restored/imported data, since this screen always writes exact multiples of 30.
        val imported = plant().copy(repottingIntervalDays = 405)
        every { plantRepo.getPlantById(1L) } returns flowOf(imported)
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        assertTrue(vm.repottingIntervalEnabled)
        assertEquals(14, vm.repottingIntervalMonths)
    }

    @Test
    fun `stored repotting days below the month floor clamp to the minimum`() = runTest {
        // A 30-day stored interval (1 month) predates the months-based picker / is out of its range.
        val fastGrower = plant().copy(repottingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(fastGrower)
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        assertTrue(vm.repottingIntervalEnabled)
        assertEquals(AddEditPlantViewModel.MIN_REPOTTING_MONTHS, vm.repottingIntervalMonths)
    }

    @Test
    fun `useLiquidFertilizer round-trips through edit mode`() = runTest {
        val monstera = plant().copy(useLiquidFertilizer = true, fertilizingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        assertTrue(vm.useLiquidFertilizer)

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.useLiquidFertilizer }) }
    }

    @Test
    fun `addPhoto updates coverPhotoUri and adds to pendingPhotos`() = runTest {
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.addPhoto("file:///photo1.jpg")

        assertEquals("file:///photo1.jpg", vm.coverPhotoUri)
        assertEquals(1, vm.pendingPhotos.size)
        assertEquals("file:///photo1.jpg", vm.pendingPhotos[0])
    }

    @Test
    fun `addPhoto multiple times appends all to pendingPhotos and updates coverPhotoUri to last`() = runTest {
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.addPhoto("file:///photo1.jpg")
        vm.addPhoto("file:///photo2.jpg")

        assertEquals("file:///photo2.jpg", vm.coverPhotoUri)
        assertEquals(2, vm.pendingPhotos.size)
    }

    @Test
    fun `addPhoto with duplicate URI is ignored in pendingPhotos but coverPhotoUri still updates`() = runTest {
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.addPhoto("file:///photo1.jpg")
        vm.addPhoto("file:///photo1.jpg")

        assertEquals(1, vm.pendingPhotos.size)
        assertEquals("file:///photo1.jpg", vm.coverPhotoUri)
    }

    @Test
    fun `save in new mode inserts pending photos via plantPhotoRepo`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 10L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Cactus"
        vm.addPhoto("file:///cactus.jpg")

        vm.events.test {
            vm.save()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantPhotoRepo.addPhotos(
                match { photos -> photos.any { it.plantId == 10L && it.uri == "file:///cactus.jpg" } }
            )
        }
    }

    @Test
    fun `save in edit mode inserts pending photos with correct plantId`() = runTest {
        val existingPlant = plant(id = 1L, name = "Fern")
        every { plantRepo.getPlantById(1L) } returns flowOf(existingPlant)
        coEvery { plantRepo.updatePlant(any()) } just runs

        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        advanceUntilIdle()

        vm.addPhoto("content://new_photo.jpg")
        vm.save()
        advanceUntilIdle()

        coVerify {
            plantPhotoRepo.addPhotos(
                match { photos -> photos.any { it.plantId == 1L && it.uri == "content://new_photo.jpg" } }
            )
        }
    }

    // --- #569: seasonal watering base interval ---

    private fun seasonalWateringDataStore(
        amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD
    ): DataStore<Preferences> =
        mockk {
            every { data } returns flowOf(
                preferencesOf(
                    FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true,
                    SettingsKeys.SEASONAL_AMPLITUDE to amplitude.name
                )
            )
        }

    @Test
    fun `wateringBaseIntervalDays stays null when dataStore is absent (SEASONAL_WATERING unreachable)`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 42L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null)
        vm.name = "Monstera"
        vm.wateringIntervalEnabled = true
        vm.wateringIntervalDays = 7

        vm.save()
        advanceUntilIdle()

        coVerify { plantRepo.addPlant(match { it.wateringBaseIntervalDays == null }) }
    }

    @Test
    fun `new plant with SEASONAL_WATERING on de-seasonalizes the typed interval to today`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 42L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null, seasonalWateringDataStore())
        vm.name = "Monstera"
        vm.wateringIntervalEnabled = true
        vm.wateringIntervalDays = 7

        vm.save()
        advanceUntilIdle()

        coVerify {
            plantRepo.addPlant(
                match { it.wateringBaseIntervalDays != null && it.wateringBaseIntervalDays != 7.0 }
            )
        }
    }

    @Test
    fun `pinIntervalToBase true keeps wateringBaseIntervalDays null even with SEASONAL_WATERING on`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 42L
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = null, seasonalWateringDataStore())
        vm.name = "Monstera"
        vm.wateringIntervalEnabled = true
        vm.wateringIntervalDays = 7
        vm.pinIntervalToBase = true

        vm.save()
        advanceUntilIdle()

        coVerify {
            plantRepo.addPlant(match { it.wateringBaseIntervalDays == null && it.pinIntervalToBase })
        }
    }

    @Test
    fun `edit mode preserves the prior base when the watering interval is untouched`() = runTest {
        val existingPlant = plant(id = 1L).copy(
            wateringIntervalDays = 7,
            wateringBaseIntervalDays = 5.18
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(existingPlant)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L, seasonalWateringDataStore())
        advanceUntilIdle()

        vm.name = "Monstera Renamed"
        vm.save()
        advanceUntilIdle()

        coVerify { plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 5.18 }) }
    }

    @Test
    fun `edit mode re-deseasonalizes the base when the watering interval is edited`() = runTest {
        val existingPlant = plant(id = 1L).copy(
            wateringIntervalDays = 7,
            wateringBaseIntervalDays = 5.18
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(existingPlant)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L, seasonalWateringDataStore())
        advanceUntilIdle()

        vm.wateringIntervalDays = 10
        vm.save()
        advanceUntilIdle()

        coVerify {
            plantRepo.updatePlant(match { it.wateringBaseIntervalDays != 5.18 && it.wateringBaseIntervalDays != null })
        }
    }
}

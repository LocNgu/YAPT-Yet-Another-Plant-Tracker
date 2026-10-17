package com.yapt.planttracker.ui.screens.addplant

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelHiddenTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
    }

    private fun plant() = Plant(id = 1L, name = "Monstera", createdAt = 1000L, updatedAt = 1000L)

    @Test
    fun `edit mode hidePlant hides the plant and emits HiddenForUndo`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { plantRepo.hidePlant(any(), any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)

        vm.events.test {
            vm.hidePlant()
            val hidden = awaitItem() as AddEditPlantViewModel.Event.HiddenForUndo
            assertEquals(1L, hidden.plantId)
            assertEquals("Monstera", hidden.plantName)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.hidePlant(1L, any()) }
        coVerify(exactly = 0) { plantRepo.archivePlant(any(), any()) }
        assertTrue(vm.isHidden)
    }

    @Test
    fun `edit mode unhidePlant unhides the plant and emits Unhidden`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant().copy(hiddenAt = 5_000L))
        coEvery { plantRepo.unhidePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        assertTrue(vm.isHidden)

        vm.events.test {
            vm.unhidePlant()
            assertTrue(awaitItem() is AddEditPlantViewModel.Event.Unhidden)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.unhidePlant(1L) }
        assertFalse(vm.isHidden)
    }

    @Test
    fun `saving an edit carries hiddenAt over so an unrelated edit cannot unhide the plant`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant().copy(hiddenAt = 5_000L))
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        vm.name = "Monstera Renamed"

        vm.save()
        advanceUntilIdle()

        coVerify { plantRepo.updatePlant(match { it.name == "Monstera Renamed" && it.hiddenAt == 5_000L }) }
    }
}

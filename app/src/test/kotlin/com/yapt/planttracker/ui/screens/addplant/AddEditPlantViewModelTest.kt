package com.yapt.planttracker.ui.screens.addplant

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddEditPlantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()

    private fun plant(id: Long = 1L, name: String = "Monstera", species: String? = "M. deliciosa") = Plant(
        id = id,
        name = name,
        species = species,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Test
    fun `blank name emits ValidationError`() = runTest {
        val vm = AddEditPlantViewModel(plantRepo, plantId = null)
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
        val vm = AddEditPlantViewModel(plantRepo, plantId = null)
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
    fun `edit mode deletePlant calls repo deletePlant and emits Deleted`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.deletePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantId = 1L)

        vm.events.test {
            vm.deletePlant()
            val event = awaitItem()
            assertTrue(event is AddEditPlantViewModel.Event.Deleted)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.deletePlant(monstera) }
    }

    @Test
    fun `edit mode init populates name and species from loaded plant`() = runTest {
        val monstera = plant(name = "Monstera", species = "M. deliciosa")
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = AddEditPlantViewModel(plantRepo, plantId = 1L)

        assertEquals("Monstera", vm.name)
        assertEquals("M. deliciosa", vm.species)
    }

    @Test
    fun `edit mode save calls updatePlant not addPlant`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantId = 1L)
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
}

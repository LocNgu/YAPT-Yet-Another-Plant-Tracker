package com.yapt.planttracker.ui.screens.graveyard

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

class GraveyardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 1000L,
        updatedAt = 1000L,
        archivedAt = 5000L
    )

    @Test
    fun `archivedPlants emitsFromRepository`() = runTest {
        val archived = listOf(plant(id = 1L, name = "Dead Fern"), plant(id = 2L, name = "Old Cactus"))
        every { plantRepo.getArchivedPlants() } returns flowOf(archived)

        val vm = GraveyardViewModel(plantRepo)

        vm.archivedPlants.test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals("Dead Fern", list[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restorePlant calls repo restorePlant and emits Restored`() = runTest {
        every { plantRepo.getArchivedPlants() } returns flowOf(emptyList())
        coEvery { plantRepo.restorePlant(any()) } just runs
        val vm = GraveyardViewModel(plantRepo)

        vm.events.test {
            vm.restorePlant(1L)
            val event = awaitItem()
            assertTrue(event is GraveyardViewModel.Event.Restored)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.restorePlant(1L) }
    }

    @Test
    fun `deletePermanently calls repo deletePlant and emits Deleted`() = runTest {
        val plant = plant()
        every { plantRepo.getArchivedPlants() } returns flowOf(emptyList())
        coEvery { plantRepo.deletePlant(any()) } just runs
        val vm = GraveyardViewModel(plantRepo)

        vm.events.test {
            vm.deletePermanently(plant)
            val event = awaitItem()
            assertTrue(event is GraveyardViewModel.Event.Deleted)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.deletePlant(plant) }
    }

    @Test
    fun `emptyGraveyard calls deleteAllArchived and emits GraveyardEmptied`() = runTest {
        every { plantRepo.getArchivedPlants() } returns flowOf(emptyList())
        coEvery { plantRepo.deleteAllArchived() } just runs
        val vm = GraveyardViewModel(plantRepo)

        vm.events.test {
            vm.emptyGraveyard()
            val event = awaitItem()
            assertTrue(event is GraveyardViewModel.Event.GraveyardEmptied)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.deleteAllArchived() }
    }
}

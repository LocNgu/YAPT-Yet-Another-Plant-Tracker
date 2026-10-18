package com.yapt.planttracker.ui.screens.hidden

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

class HiddenPlantsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()

    private fun plant(id: Long, name: String) =
        Plant(id = id, name = name, createdAt = 1000L, updatedAt = 1000L, hiddenAt = 5000L)

    @Test
    fun `hiddenPlants emits what the repository reports`() = runTest {
        every { plantRepo.getHiddenPlants() } returns flowOf(listOf(plant(1, "Fern"), plant(2, "Cactus")))

        val vm = HiddenPlantsViewModel(plantRepo)

        vm.hiddenPlants.test {
            assertEquals(listOf("Fern", "Cactus"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unhidePlant calls the repository and emits Unhidden`() = runTest {
        every { plantRepo.getHiddenPlants() } returns flowOf(emptyList())
        coEvery { plantRepo.unhidePlant(any()) } just runs
        val vm = HiddenPlantsViewModel(plantRepo)

        vm.events.test {
            vm.unhidePlant(3L)
            assertTrue(awaitItem() is HiddenPlantsViewModel.Event.Unhidden)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.unhidePlant(3L) }
    }
}

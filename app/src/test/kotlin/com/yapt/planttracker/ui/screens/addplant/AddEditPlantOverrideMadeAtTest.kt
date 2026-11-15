package com.yapt.planttracker.ui.screens.addplant

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantOverrideMadeAtTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
    }

    @Test
    fun `edit mode save carries over the override and its made-at stamp`() = runTest {
        val existing = Plant(
            id = 1L,
            name = "Monstera",
            wateringDueDateOverride = 1_800_000_000_000L,
            wateringDueDateOverrideMadeAt = 1_799_000_000_000L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(existing)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        vm.name = "Renamed"

        vm.save()
        advanceUntilIdle()

        coVerify {
            plantRepo.updatePlant(
                match {
                    it.wateringDueDateOverride == 1_800_000_000_000L &&
                        it.wateringDueDateOverrideMadeAt == 1_799_000_000_000L
                }
            )
        }
    }
}

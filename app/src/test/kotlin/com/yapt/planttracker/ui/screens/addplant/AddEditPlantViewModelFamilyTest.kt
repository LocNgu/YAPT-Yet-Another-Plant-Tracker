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
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelFamilyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
    }

    private fun mother() = Plant(
        id = 7L,
        name = "Pothos",
        species = "Epipremnum aureum",
        room = "Kitchen",
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun newChildVm(parentId: Long? = 7L) = AddEditPlantViewModel(
        plantRepo,
        plantPhotoRepo,
        plantId = null,
        propagatedFromId = parentId,
        childNameFor = { "$it (cutting)" }
    )

    @Test
    fun `opened from the offer, Add Plant prefills species, room and the cutting name and shows the parent`() =
        runTest {
            every { plantRepo.getPlantById(7L) } returns flowOf(mother())

            val vm = newChildVm()
            advanceUntilIdle()

            assertEquals("Pothos (cutting)", vm.name)
            assertEquals("Epipremnum aureum", vm.species)
            assertEquals("Kitchen", vm.room)
            assertEquals("Pothos", vm.propagatedFromName)
        }

    @Test
    fun `saving the prefilled plant links it to the parent`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(mother())
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 8L
        val vm = newChildVm()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertEquals(7L, saved.captured.propagatedFromId)
        assertEquals("Pothos (cutting)", saved.captured.name)
    }

    @Test
    fun `a parent deleted before saving leaves the new plant unlinked`() = runTest {
        every { plantRepo.getPlantById(7L) } returnsMany listOf(flowOf(mother()), flowOf(null))
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 8L
        val vm = newChildVm()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertNull(saved.captured.propagatedFromId)
    }

    @Test
    fun `a parent that no longer exists gives a plain empty form`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(null)

        val vm = newChildVm()
        advanceUntilIdle()

        assertEquals("", vm.name)
        assertNull(vm.propagatedFromName)
    }

    @Test
    fun `a plain Add Plant has no parent line and saves unlinked`() = runTest {
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 9L
        val vm = newChildVm(parentId = null)
        vm.name = "Fern"

        vm.save()
        advanceUntilIdle()

        assertNull(vm.propagatedFromName)
        assertNull(saved.captured.propagatedFromId)
    }

    @Test
    fun `saving an edit preserves propagatedFromId`() = runTest {
        every { plantRepo.getPlantById(8L) } returns
            flowOf(mother().copy(id = 8L, name = "Pothos (cutting)", propagatedFromId = 7L))
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 8L)
        advanceUntilIdle()
        vm.name = "Pothos cutting renamed"

        vm.save()
        advanceUntilIdle()

        coVerify { plantRepo.updatePlant(match { it.name == "Pothos cutting renamed" && it.propagatedFromId == 7L }) }
    }
}

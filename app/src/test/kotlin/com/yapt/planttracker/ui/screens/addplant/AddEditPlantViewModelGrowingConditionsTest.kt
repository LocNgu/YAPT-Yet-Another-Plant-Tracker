package com.yapt.planttracker.ui.screens.addplant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.LightLevel
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PotMaterial
import com.yapt.planttracker.domain.schedule.Hemisphere
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Growing conditions (light, pot material, pot size) on Add/Edit Plant (#1003). */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelGrowingConditionsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val adjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }

    init {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
        coEvery { plantRepo.updatePlant(any()) } just runs
        coEvery { plantRepo.addPlant(any()) } returns 5L
    }

    private fun existing() = Plant(
        id = 1L,
        name = "Fern",
        wateringIntervalDays = 7,
        lightLevel = LightLevel.BRIGHT_INDIRECT,
        potMaterial = PotMaterial.TERRACOTTA,
        potDiameterCm = 15,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeVm(plant: Plant?): AddEditPlantViewModel {
        if (plant != null) every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        return AddEditPlantViewModel(
            plantRepo,
            plantPhotoRepo,
            plant?.id,
            dataStore,
            adjustmentRepo,
            { Hemisphere.NORTHERN },
            { 1_700_000_000_000L }
        )
    }

    private fun savedUpdate(): Plant {
        val slot = slot<Plant>()
        coVerify { plantRepo.updatePlant(capture(slot)) }
        return slot.captured
    }

    @Test
    fun `the stored growing conditions load into the form and an unrelated edit keeps them`() = runTest {
        val vm = makeVm(existing())
        advanceUntilIdle()
        assertEquals(LightLevel.BRIGHT_INDIRECT, vm.uiState.value.lightLevel)
        assertEquals("15", vm.uiState.value.potDiameterText)

        vm.setName("Renamed")
        vm.save()
        advanceUntilIdle()

        val saved = savedUpdate()
        assertEquals(LightLevel.BRIGHT_INDIRECT, saved.lightLevel)
        assertEquals(PotMaterial.TERRACOTTA, saved.potMaterial)
        assertEquals(15, saved.potDiameterCm)
    }

    @Test
    fun `an edit can change and clear each growing condition`() = runTest {
        val vm = makeVm(existing())
        advanceUntilIdle()

        vm.setLightLevel(LightLevel.LOW)
        vm.setPotMaterial(null)
        vm.setPotDiameterText("")
        vm.save()
        advanceUntilIdle()

        val saved = savedUpdate()
        assertEquals(LightLevel.LOW, saved.lightLevel)
        assertNull(saved.potMaterial)
        assertNull(saved.potDiameterCm)
    }

    @Test
    fun `a new plant saves its growing conditions and unset by default`() = runTest {
        val vm = makeVm(null)
        vm.setName("Cactus")
        vm.setPotMaterial(PotMaterial.PLASTIC)
        vm.setPotDiameterText("200")
        vm.save()
        advanceUntilIdle()

        val slot = slot<Plant>()
        coVerify { plantRepo.addPlant(capture(slot)) }
        assertNull(slot.captured.lightLevel)
        assertEquals(PotMaterial.PLASTIC, slot.captured.potMaterial)
        assertEquals(200, slot.captured.potDiameterCm)
    }

    @Test
    fun `pot size outside 1 to 200 is invalid, blocks save and jumps the wizard back to details`() = runTest {
        for (bad in listOf("0", "201", "999")) {
            val vm = makeVm(null)
            vm.setName("Cactus")
            vm.setPotDiameterText(bad)
            vm.goToStep(AddPlantStep.REVIEW)

            assertFalse(bad, vm.uiState.value.potDiameterValid)
            vm.save()
            advanceUntilIdle()

            assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
        }
        coVerify(exactly = 0) { plantRepo.addPlant(any()) }
    }

    @Test
    fun `blank and in-range pot sizes are valid and non-digits are dropped`() = runTest {
        val vm = makeVm(null)
        assertTrue(vm.uiState.value.potDiameterValid)

        vm.setPotDiameterText("1")
        assertTrue(vm.uiState.value.potDiameterValid)
        vm.setPotDiameterText("1a5.")
        assertEquals("15", vm.uiState.value.potDiameterText)
    }

    @Test
    fun `an invalid pot size keeps the wizard on the details step`() = runTest {
        val vm = makeVm(null)
        vm.setName("Cactus")
        vm.setPotDiameterText("0")

        vm.goNext()
        advanceUntilIdle()

        assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
    }
}

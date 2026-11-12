package com.yapt.planttracker.ui.screens.addplant

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelWizardTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
    }

    private fun newVm(handle: SavedStateHandle = SavedStateHandle(), parentId: Long? = null) =
        AddEditPlantViewModel(
            plantRepo,
            plantPhotoRepo,
            plantId = null,
            propagatedFromId = parentId,
            childNameFor = { "$it (cutting)" },
            savedStateHandle = handle
        )

    private fun mother() = Plant(
        id = 7L,
        name = "Pothos",
        species = "Epipremnum aureum",
        room = "Kitchen",
        wateringIntervalDays = 9,
        fertilizingIntervalDays = 21,
        fertilizingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER),
        dormancyStartMonth = 11,
        dormancyEndMonth = 2,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    // ---- Steps ----

    @Test
    fun `starts on the photo step and goNext walks through to review`() = runTest {
        val vm = newVm()
        assertEquals(AddPlantStep.PHOTO, vm.uiState.value.step)
        vm.goNext()
        assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
        vm.setName("Monstera")
        vm.goNext()
        assertEquals(AddPlantStep.CARE, vm.uiState.value.step)
        vm.goNext()
        assertEquals(AddPlantStep.REVIEW, vm.uiState.value.step)
        vm.goNext()
        assertEquals(AddPlantStep.REVIEW, vm.uiState.value.step)
    }

    @Test
    fun `goNext from details with a blank name stays and emits a string-resource error`() = runTest {
        val vm = newVm()
        vm.goNext()
        vm.setName("   ")

        vm.events.test {
            vm.goNext()
            val event = awaitItem() as AddEditPlantViewModel.Event.ValidationError
            assertEquals(R.string.add_plant_name_required, event.messageRes)
        }
        assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
    }

    @Test
    fun `goBack returns to the previous step and reports false on the first step`() = runTest {
        val vm = newVm()
        assertFalse(vm.goBack())
        vm.goToStep(AddPlantStep.CARE)

        assertTrue(vm.goBack())
        assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
        assertTrue(vm.goBack())
        assertEquals(AddPlantStep.PHOTO, vm.uiState.value.step)
        assertFalse(vm.goBack())
    }

    @Test
    fun `goBack never consumes back in edit mode`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(null)
        val vm = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        assertFalse(vm.goBack())
    }

    @Test
    fun `save with a blank name on review jumps back to the details step`() = runTest {
        val vm = newVm()
        vm.goToStep(AddPlantStep.REVIEW)

        vm.events.test {
            vm.save()
            assertTrue(awaitItem() is AddEditPlantViewModel.Event.ValidationError)
        }
        assertEquals(AddPlantStep.DETAILS, vm.uiState.value.step)
        coVerify(exactly = 0) { plantRepo.addPlant(any()) }
    }

    @Test
    fun `no plant is written before Add plant on the review step`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 5L
        val vm = newVm()
        vm.goNext()
        vm.setName("Fern")
        vm.goNext()
        vm.goNext()
        advanceUntilIdle()
        coVerify(exactly = 0) { plantRepo.addPlant(any()) }

        vm.events.test {
            vm.save()
            assertEquals(5L, (awaitItem() as AddEditPlantViewModel.Event.Saved).plantId)
        }
    }

    // ---- hasUnsavedInput ----

    @Test
    fun `a fresh form has no unsaved input`() = runTest {
        assertFalse(newVm().hasUnsavedInput)
    }

    @Test
    fun `text, a photo or a care setting counts as unsaved input`() = runTest {
        assertTrue(newVm().apply { setName("a") }.hasUnsavedInput)
        assertTrue(newVm().apply { setNotes("a") }.hasUnsavedInput)
        assertTrue(newVm().apply { addPhoto("content://p") }.hasUnsavedInput)
        assertTrue(newVm().apply { setWateringIntervalEnabled(true) }.hasUnsavedInput)
        assertTrue(newVm().apply { toggleRepottingSeason(FertilizingSeason.WINTER) }.hasUnsavedInput)
    }

    @Test
    fun `moving between steps alone is not unsaved input`() = runTest {
        val vm = newVm()
        vm.goToStep(AddPlantStep.CARE)
        assertFalse(vm.hasUnsavedInput)
    }

    // ---- SavedStateHandle restore ----

    @Test
    fun `every field and the step survive a restore from the same handle`() = runTest {
        val handle = SavedStateHandle()
        val first = newVm(handle)
        first.setName("Monstera")
        first.setSpecies("Deliciosa")
        first.setRoom("Hall")
        first.setNotes("A very long note that must survive process death intact")
        first.addPhoto("content://one")
        first.addPhoto("content://two")
        first.setWateringIntervalEnabled(true)
        first.setWateringIntervalDays(12)
        first.setFertilizingIntervalEnabled(true)
        first.setFertilizingIntervalDays(45)
        first.toggleFertilizingSeason(FertilizingSeason.WINTER)
        first.setUseLiquidFertilizer(true)
        first.setRepottingIntervalEnabled(true)
        first.setRepottingIntervalMonths(18)
        first.toggleRepottingSeason(FertilizingSeason.AUTUMN)
        first.setWateringAmplitudeOverride(SeasonalAmplitude.STRONG)
        first.setDormancyWindow(11, 2, 28)
        first.goToStep(AddPlantStep.CARE)

        val restored = newVm(handle)

        assertEquals(first.uiState.value, restored.uiState.value)
        assertEquals(AddPlantStep.CARE, restored.uiState.value.step)
        assertEquals("Monstera", restored.name)
        assertEquals("Deliciosa", restored.species)
        assertEquals("Hall", restored.room)
        assertEquals("A very long note that must survive process death intact", restored.notes)
        assertEquals(listOf("content://one", "content://two"), restored.uiState.value.pendingPhotos)
        assertEquals("content://two", restored.uiState.value.coverPhotoUri)
        assertEquals(
            setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER, FertilizingSeason.AUTUMN),
            restored.uiState.value.fertilizingSeasons
        )
        assertEquals(
            setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER, FertilizingSeason.WINTER),
            restored.uiState.value.repottingSeasons
        )
        assertEquals(SeasonalAmplitude.STRONG, restored.uiState.value.wateringAmplitudeOverride)
        assertEquals(11, restored.uiState.value.dormancyStartMonth)
        assertEquals(2, restored.uiState.value.dormancyEndMonth)
        assertEquals(28, restored.uiState.value.dormantWateringIntervalDays)
        assertEquals(18, restored.uiState.value.repottingIntervalMonths)
    }

    @Test
    fun `a corrupt saved form falls back to the defaults`() = runTest {
        val vm = newVm(SavedStateHandle(mapOf("add_edit_plant_form" to "{not json")))
        assertEquals(AddEditPlantFormState(), vm.uiState.value)
    }

    @Test
    fun `a restored add form saves the restored values`() = runTest {
        val handle = SavedStateHandle()
        newVm(handle).apply {
            setName("Fern")
            setWateringIntervalEnabled(true)
            setWateringIntervalDays(10)
            goToStep(AddPlantStep.REVIEW)
        }
        val saved = io.mockk.slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 3L

        val restored = newVm(handle)
        restored.save()
        advanceUntilIdle()

        assertEquals("Fern", saved.captured.name)
        assertEquals(10, saved.captured.wateringIntervalDays)
    }

    @Test
    fun `restored edit form is not overwritten by the edit load but display-only fields refresh`() = runTest {
        val plant = Plant(
            id = 1L,
            name = "Original",
            wateringIntervalDays = 7,
            hiddenAt = 99L,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        val handle = SavedStateHandle()
        val first = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L, savedStateHandle = handle)
        advanceUntilIdle()
        assertEquals("Original", first.name)
        assertEquals(7, first.uiState.value.seededWateringIntervalDays)
        first.setName("Edited by user")
        first.setWateringIntervalDays(21)

        val restored = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L, savedStateHandle = handle)
        advanceUntilIdle()

        assertEquals("Edited by user", restored.name)
        assertEquals(21, restored.uiState.value.wateringIntervalDays)
        assertEquals(7, restored.uiState.value.seededWateringIntervalDays)
        assertTrue(restored.isHidden)
    }

    // ---- Propagate ----

    @Test
    fun `propagate prefill is visible on every step`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(mother())
        val vm = newVm(parentId = 7L)
        advanceUntilIdle()

        AddPlantStep.entries.forEach { step ->
            vm.goToStep(step)
            assertEquals("Pothos (cutting)", vm.name)
            assertEquals("Pothos", vm.uiState.value.propagatedFromName)
            assertEquals(21, vm.uiState.value.fertilizingIntervalDays)
            assertTrue(vm.uiState.value.fertilizingIntervalEnabled)
        }
        assertEquals(11, vm.uiState.value.dormancyStartMonth)
    }

    @Test
    fun `a user change after the prefill survives a restore and the prefill does not re-apply`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(mother())
        val handle = SavedStateHandle()
        val first = newVm(handle, parentId = 7L)
        advanceUntilIdle()
        first.setFertilizingIntervalDays(60)
        first.setName("My own name")
        first.setDormancyWindow(null, null)

        val restored = newVm(handle, parentId = 7L)
        advanceUntilIdle()

        assertEquals(60, restored.uiState.value.fertilizingIntervalDays)
        assertEquals("My own name", restored.name)
        assertNull(restored.uiState.value.dormancyStartMonth)
        assertEquals("Pothos", restored.uiState.value.propagatedFromName)
        assertEquals(7L, restored.uiState.value.linkedParentId)
    }

    @Test
    fun `restore before the parent loaded still prefills only the untouched fields`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(mother())
        val handle = SavedStateHandle()
        newVm(handle).apply {
            setFertilizingIntervalEnabled(true)
            setFertilizingIntervalDays(90)
            setName("Typed first")
        }

        val restored = newVm(handle, parentId = 7L)
        advanceUntilIdle()

        assertEquals(90, restored.uiState.value.fertilizingIntervalDays)
        assertEquals("Typed first", restored.name)
        assertEquals("Epipremnum aureum", restored.species)
        assertEquals(11, restored.uiState.value.dormancyStartMonth)
    }
}

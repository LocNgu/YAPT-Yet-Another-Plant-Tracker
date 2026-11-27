package com.yapt.planttracker.ui.screens.addplant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.SavedStateHandle
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelPresetTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
    }

    private val january = LocalDate.of(2026, 1, 15)
    private val nowMillis = january.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun standardStore(): DataStore<Preferences> =
        mockk { every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to "STANDARD")) }

    private fun newVm(
        handle: SavedStateHandle = SavedStateHandle(),
        hemisphere: Hemisphere = Hemisphere.NORTHERN,
        parentId: Long? = null,
        dataStore: DataStore<Preferences>? = null
    ) = AddEditPlantViewModel(
        plantRepo,
        plantPhotoRepo,
        plantId = null,
        dataStore = dataStore,
        hemisphere = { hemisphere },
        nowProvider = { nowMillis },
        propagatedFromId = parentId,
        savedStateHandle = handle
    )

    @Test
    fun `picking a preset sets the species and seeds the care step`() = runTest {
        val vm = newVm()
        vm.pickPreset("monstera")

        assertEquals("Monstera deliciosa", vm.species)
        val form = vm.uiState.value
        assertEquals("monstera", form.presetId)
        assertTrue(form.wateringIntervalEnabled)
        assertEquals(7, form.wateringIntervalDays)
        assertTrue(form.fertilizingIntervalEnabled)
        assertEquals(30, form.fertilizingIntervalDays)
        assertEquals(setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER), form.fertilizingSeasons)
        assertNull(form.dormancyStartMonth)
        assertFalse(form.moreOptionsExpanded)
    }

    @Test
    fun `a preset with dormancy seeds it visibly and shifts it in the southern hemisphere`() = runTest {
        val north = newVm()
        north.pickPreset("aloe_vera")
        assertEquals(11, north.uiState.value.dormancyStartMonth)
        assertEquals(2, north.uiState.value.dormancyEndMonth)
        assertEquals(28, north.uiState.value.dormantWateringIntervalDays)
        assertTrue(north.uiState.value.moreOptionsExpanded)

        val south = newVm(hemisphere = Hemisphere.SOUTHERN)
        south.pickPreset("aloe_vera")
        assertEquals(5, south.uiState.value.dormancyStartMonth)
        assertEquals(8, south.uiState.value.dormancyEndMonth)
    }

    @Test
    fun `no preset or an unknown id changes nothing`() = runTest {
        val vm = newVm()
        vm.setSpecies("Some rare thing")
        vm.pickPreset("does_not_exist")

        assertEquals("Some rare thing", vm.species)
        assertNull(vm.uiState.value.presetId)
        assertFalse(vm.uiState.value.wateringIntervalEnabled)
    }

    @Test
    fun `editing the species text after a pick keeps the seeded values and the label`() = runTest {
        val vm = newVm()
        vm.pickPreset("pothos")
        vm.setSpecies("Golden pothos")

        assertEquals(8, vm.uiState.value.wateringIntervalDays)
        assertTrue(vm.uiState.value.wateringIntervalEnabled)
        assertEquals("pothos", vm.uiState.value.presetId)
    }

    @Test
    fun `picking B after A replaces untouched fields only`() = runTest {
        val vm = newVm()
        vm.pickPreset("monstera")
        vm.setWateringIntervalDays(12)
        vm.pickPreset("snake_plant")

        assertEquals("snake_plant", vm.uiState.value.presetId)
        assertEquals(12, vm.uiState.value.wateringIntervalDays)
        assertEquals(60, vm.uiState.value.fertilizingIntervalDays)
        assertEquals("Dracaena trifasciata", vm.species)
    }

    @Test
    fun `picking B after A drops A's dormancy when untouched and keeps a hand-set one`() = runTest {
        val untouched = newVm()
        untouched.pickPreset("aloe_vera")
        untouched.pickPreset("monstera")
        assertNull(untouched.uiState.value.dormancyStartMonth)
        assertNull(untouched.uiState.value.dormantWateringIntervalDays)

        val touched = newVm()
        touched.pickPreset("aloe_vera")
        touched.setDormancyWindow(12, 1)
        touched.pickPreset("monstera")
        assertEquals(12, touched.uiState.value.dormancyStartMonth)
    }

    @Test
    fun `fertilizing seasons and switch count as touched`() = runTest {
        val vm = newVm()
        vm.pickPreset("monstera")
        vm.toggleFertilizingSeason(FertilizingSeason.AUTUMN)
        vm.pickPreset("snake_plant")

        assertEquals(30, vm.uiState.value.fertilizingIntervalDays)
        assertTrue(FertilizingSeason.AUTUMN in vm.uiState.value.fertilizingSeasons)
    }

    @Test
    fun `seeded values the preset label and touched state survive a restore`() = runTest {
        val handle = SavedStateHandle()
        val first = newVm(handle)
        first.pickPreset("monstera")
        first.setFertilizingIntervalDays(45)

        val restored = newVm(handle)
        assertEquals(first.uiState.value, restored.uiState.value)
        assertEquals("monstera", restored.uiState.value.presetId)
        assertEquals("Monstera deliciosa", restored.species)

        restored.pickPreset("snake_plant")
        assertEquals(45, restored.uiState.value.fertilizingIntervalDays)
        assertEquals(18, restored.uiState.value.wateringIntervalDays)
    }

    @Test
    fun `a picked preset saves species and seeded intervals`() = runTest {
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 1L
        val vm = newVm()
        vm.setName("Monty")
        vm.pickPreset("monstera")

        vm.save()
        advanceUntilIdle()

        assertEquals("Monstera deliciosa", saved.captured.species)
        assertEquals(7, saved.captured.wateringIntervalDays)
        assertEquals(30, saved.captured.fertilizingIntervalDays)
    }

    @Test
    fun `a seasonal pick shows the effective value and saves the exact preset base`() = runTest {
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 1L
        val vm = newVm(dataStore = standardStore())
        vm.setName("Monty")
        vm.pickPreset("monstera")

        val effective = SeasonalWatering.effectiveInterval(7.0, january, 0.35, Hemisphere.NORTHERN)
        assertTrue(effective != 7)
        assertEquals(effective, vm.uiState.value.wateringIntervalDays)

        vm.save()
        advanceUntilIdle()

        assertEquals(effective, saved.captured.wateringIntervalDays)
        assertEquals(7.0, saved.captured.wateringBaseIntervalDays!!, 0.0)
    }

    @Test
    fun `changing the slider after a seasonal pick de-seasonalizes the typed value instead`() = runTest {
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 1L
        val vm = newVm(dataStore = standardStore())
        vm.setName("Monty")
        vm.pickPreset("monstera")
        vm.setWateringIntervalDays(20)

        vm.save()
        advanceUntilIdle()

        val expected = SeasonalWatering.deseasonalize(20.0, january, 0.35, Hemisphere.NORTHERN)
        assertEquals(20, saved.captured.wateringIntervalDays)
        assertEquals(expected, saved.captured.wateringBaseIntervalDays!!, 1e-9)
    }

    @Test
    fun `a group touched before the first pick does not block it`() = runTest {
        val vm = newVm()
        vm.setWateringIntervalDays(12)
        vm.pickPreset("monstera")
        assertEquals(7, vm.uiState.value.wateringIntervalDays)

        vm.setWateringIntervalDays(12)
        vm.pickPreset("snake_plant")
        assertEquals(12, vm.uiState.value.wateringIntervalDays)
    }

    @Test
    fun `the last of two quick picks wins`() = runTest {
        val vm = newVm(dataStore = standardStore())
        vm.pickPreset("monstera")
        vm.pickPreset("snake_plant")

        assertEquals("snake_plant", vm.uiState.value.presetId)
        assertEquals("Dracaena trifasciata", vm.species)
    }

    @Test
    fun `edit mode and propagate get no presets`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(null)
        val edit = AddEditPlantViewModel(plantRepo, plantPhotoRepo, plantId = 1L)
        assertFalse(edit.presetsAvailable)
        edit.pickPreset("monstera")
        assertNull(edit.uiState.value.presetId)
        assertEquals("", edit.species)

        every { plantRepo.getPlantById(7L) } returns flowOf(null)
        val propagate = newVm(parentId = 7L)
        assertFalse(propagate.presetsAvailable)
        propagate.pickPreset("monstera")
        assertNull(propagate.uiState.value.presetId)

        assertTrue(newVm().presetsAvailable)
    }
}

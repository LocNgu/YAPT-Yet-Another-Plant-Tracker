package com.yapt.planttracker.ui.screens.addplant

import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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

    private val january = LocalDate.of(2026, 1, 15)
    private val nowMillis = january.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun liveFor(base: Double) =
        SeasonalWatering.effectiveInterval(base, january, SeasonalAmplitude.STANDARD.value, Hemisphere.NORTHERN)

    /** A new-plant form opened from the offer with a fixed January clock and the STANDARD seasonal curve. */
    private fun careChildVm(parentId: Long? = 7L) = AddEditPlantViewModel(
        plantRepo,
        plantPhotoRepo,
        plantId = null,
        dataStore = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to "STANDARD"))
        },
        hemisphere = { Hemisphere.NORTHERN },
        nowProvider = { nowMillis },
        propagatedFromId = parentId,
        childNameFor = { "$it (cutting)" }
    )

    private fun careMother() = mother().copy(
        notes = "Loves a north window",
        coverPhotoUri = "content://photo",
        wateringIntervalDays = 7,
        wateringBaseIntervalDays = 8.0,
        wateringConfidence = 4,
        wateringResetAt = 111L,
        wateringFreezeUntil = 222L,
        wateringDueDateOverride = 333L,
        pinIntervalToBase = false,
        fertilizingIntervalDays = 21,
        fertilizingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER),
        useLiquidFertilizer = true,
        repottingIntervalDays = 360,
        repottingSeasons = setOf(FertilizingSeason.SPRING),
        repotPlanSeasonStartAt = 444L,
        repotPlanMadeAt = 555L,
        dormancyStartMonth = 11,
        dormancyEndMonth = 2,
        dormantWateringIntervalDays = 35,
        hiddenAt = 666L
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

    @Test
    fun `the new plant starts with the parent's dormancy window, fertilizing, seasons and pin`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(careMother().copy(pinIntervalToBase = true))

        val vm = careChildVm()
        advanceUntilIdle()

        assertEquals(11, vm.dormancyStartMonth)
        assertEquals(2, vm.dormancyEndMonth)
        assertEquals(35, vm.dormantWateringIntervalDays)
        assertTrue(vm.fertilizingIntervalEnabled)
        assertEquals(21, vm.fertilizingIntervalDays)
        assertEquals(setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER), vm.fertilizingSeasons)
        assertTrue(vm.useLiquidFertilizer)
        assertEquals(setOf(FertilizingSeason.SPRING), vm.repottingSeasons)
        assertTrue(vm.pinIntervalToBase)
    }

    @Test
    fun `the watering reminder starts at the parent's live interval for today, not its stored literal`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(careMother())

        val vm = careChildVm()
        advanceUntilIdle()

        assertTrue(vm.wateringIntervalEnabled)
        assertEquals(liveFor(8.0), vm.wateringIntervalDays)
        assertNotEquals(7, vm.wateringIntervalDays)
    }

    @Test
    fun `a parent without a watering or fertilizing reminder leaves both reminders off`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(mother())

        val vm = careChildVm()
        advanceUntilIdle()

        assertFalse(vm.wateringIntervalEnabled)
        assertEquals(7, vm.wateringIntervalDays)
        assertFalse(vm.fertilizingIntervalEnabled)
        assertEquals(30, vm.fertilizingIntervalDays)
        assertNull(vm.dormancyStartMonth)
    }

    @Test
    fun `the repotting reminder, repot plan, notes and photo are not copied`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(careMother())

        val vm = careChildVm()
        advanceUntilIdle()

        assertFalse(vm.repottingIntervalEnabled)
        assertEquals(AddEditPlantViewModel.DEFAULT_REPOTTING_MONTHS, vm.repottingIntervalMonths)
        assertEquals("", vm.notes)
        assertNull(vm.coverPhotoUri)
        assertTrue(vm.pendingPhotos.isEmpty())
    }

    @Test
    fun `saving the copied form stores a fresh plant with no learned watering state`() = runTest {
        every { plantRepo.getPlantById(7L) } returns flowOf(careMother())
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 8L
        val vm = careChildVm()
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        val plant = saved.captured
        assertEquals(7L, plant.propagatedFromId)
        assertEquals(liveFor(8.0), plant.wateringIntervalDays)
        assertNull(plant.wateringConfidence)
        assertNull(plant.wateringResetAt)
        assertNull(plant.wateringFreezeUntil)
        assertNull(plant.wateringDueDateOverride)
        assertNull(plant.repottingIntervalDays)
        assertNull(plant.repotPlanSeasonStartAt)
        assertNull(plant.repotPlanMadeAt)
        assertNull(plant.notes)
        assertNull(plant.coverPhotoUri)
        assertNull(plant.hiddenAt)
        assertNull(plant.archivedAt)
        assertEquals(21, plant.fertilizingIntervalDays)
        assertEquals(setOf(FertilizingSeason.SPRING), plant.repottingSeasons)
        assertEquals(11, plant.dormancyStartMonth)
        assertEquals(35, plant.dormantWateringIntervalDays)
        assertTrue(plant.useLiquidFertilizer)
    }

    @Test
    fun `values the user changed before the parent loaded are not overwritten`() = runTest {
        val parent = MutableSharedFlow<Plant?>(replay = 1)
        every { plantRepo.getPlantById(7L) } returns parent
        val vm = careChildVm()
        vm.wateringIntervalEnabled = true
        vm.wateringIntervalDays = 12
        vm.fertilizingIntervalEnabled = true
        vm.fertilizingIntervalDays = 45
        vm.fertilizingSeasons = setOf(FertilizingSeason.AUTUMN)
        vm.repottingSeasons = setOf(FertilizingSeason.WINTER)
        vm.useLiquidFertilizer = true
        vm.pinIntervalToBase = true
        vm.setDormancyWindow(5, 6)

        parent.emit(careMother().copy(useLiquidFertilizer = false))
        advanceUntilIdle()

        assertEquals(12, vm.wateringIntervalDays)
        assertEquals(45, vm.fertilizingIntervalDays)
        assertEquals(setOf(FertilizingSeason.AUTUMN), vm.fertilizingSeasons)
        assertEquals(setOf(FertilizingSeason.WINTER), vm.repottingSeasons)
        assertTrue(vm.useLiquidFertilizer)
        assertTrue(vm.pinIntervalToBase)
        assertEquals(5, vm.dormancyStartMonth)
        assertEquals(6, vm.dormancyEndMonth)
        assertNull(vm.dormantWateringIntervalDays)
        assertEquals("Pothos (cutting)", vm.name)
    }

    @Test
    fun `a plain Add Plant copies nothing`() = runTest {
        val vm = careChildVm(parentId = null)
        advanceUntilIdle()

        assertFalse(vm.wateringIntervalEnabled)
        assertFalse(vm.fertilizingIntervalEnabled)
        assertNull(vm.dormancyStartMonth)
        assertFalse(vm.useLiquidFertilizer)
        assertEquals(FertilizingSeason.entries.toSet(), vm.fertilizingSeasons)
        assertNull(vm.propagatedFromName)
    }

    @Test
    fun `the edit path never reads a parent or copies its settings`() = runTest {
        val own = mother().copy(id = 8L, name = "Cutting", wateringIntervalDays = null, propagatedFromId = 7L)
        every { plantRepo.getPlantById(8L) } returns flowOf(own)
        val vm = AddEditPlantViewModel(
            plantRepo,
            plantPhotoRepo,
            plantId = 8L,
            propagatedFromId = 7L
        )
        advanceUntilIdle()

        assertEquals("Cutting", vm.name)
        assertFalse(vm.wateringIntervalEnabled)
        assertNull(vm.propagatedFromName)
        verify(exactly = 0) { plantRepo.getPlantById(7L) }
    }
}

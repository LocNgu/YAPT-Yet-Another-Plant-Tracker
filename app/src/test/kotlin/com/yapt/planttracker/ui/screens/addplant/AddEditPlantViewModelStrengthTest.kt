package com.yapt.planttracker.ui.screens.addplant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
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
import java.time.LocalDate
import java.time.ZoneId

/** Per-plant seasonal strength on Add/Edit Plant (#700, product ADR-0084). */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelStrengthTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val adjustmentRepo: WateringAdjustmentRepository = mockk()
    private val adjustments = mutableListOf<WateringAdjustment>()

    private val january = LocalDate.of(2026, 1, 15)
    private val nowMillis = january.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    init {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.addPhotos(any()) } just runs
        coEvery { plantRepo.updatePlant(any()) } just runs
        coEvery { plantRepo.addPlant(any()) } returns 5L
        coEvery { adjustmentRepo.recordManualEdit(capture(adjustments)) } returns 1L
    }

    private fun dataStore(global: SeasonalAmplitude): DataStore<Preferences> =
        mockk { every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to global.name)) }

    private fun plant(override: SeasonalAmplitude? = null, pinned: Boolean = false) = Plant(
        id = 1L,
        name = "Fern",
        wateringIntervalDays = 7,
        wateringBaseIntervalDays = 8.0,
        pinIntervalToBase = pinned,
        wateringAmplitudeOverride = override,
        wateringConfidence = 3,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeVm(
        existing: Plant?,
        global: SeasonalAmplitude = SeasonalAmplitude.STANDARD
    ): AddEditPlantViewModel {
        if (existing != null) every { plantRepo.getPlantById(1L) } returns flowOf(existing)
        return AddEditPlantViewModel(
            plantRepo,
            plantPhotoRepo,
            existing?.id,
            dataStore(global),
            adjustmentRepo,
            { Hemisphere.NORTHERN },
            { nowMillis }
        )
    }

    private fun saved(): Plant {
        val slot = slot<Plant>()
        coVerify { plantRepo.updatePlant(capture(slot)) }
        return slot.captured
    }

    @Test
    fun `choosing pin clears the custom strength and choosing a custom strength clears the pin`() = runTest {
        val vm = makeVm(null)

        vm.wateringAmplitudeOverride = SeasonalAmplitude.STRONG
        vm.pinIntervalToBase = true
        assertTrue(vm.pinIntervalToBase)
        assertNull(vm.wateringAmplitudeOverride)

        vm.wateringAmplitudeOverride = SeasonalAmplitude.MILD
        assertFalse(vm.pinIntervalToBase)
        assertEquals(SeasonalAmplitude.MILD, vm.wateringAmplitudeOverride)
    }

    @Test
    fun `the stored custom strength loads into the form`() = runTest {
        val vm = makeVm(plant(override = SeasonalAmplitude.MILD))
        advanceUntilIdle()

        assertEquals(SeasonalAmplitude.MILD, vm.wateringAmplitudeOverride)
        assertFalse(vm.pinIntervalToBase)
    }

    @Test
    fun `an unrelated edit carries the stored strength over`() = runTest {
        val vm = makeVm(plant(override = SeasonalAmplitude.STRONG))
        advanceUntilIdle()

        vm.name = "Renamed"
        vm.save()
        advanceUntilIdle()

        assertEquals(SeasonalAmplitude.STRONG, saved().wateringAmplitudeOverride)
    }

    @Test
    fun `changing only the strength keeps the base, the literal and the confidence and logs nothing`() = runTest {
        val vm = makeVm(plant())
        advanceUntilIdle()

        vm.wateringAmplitudeOverride = SeasonalAmplitude.STRONG
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(SeasonalAmplitude.STRONG, result.wateringAmplitudeOverride)
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(7, result.wateringIntervalDays)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `pinning from a custom strength stores the pin and no override`() = runTest {
        val vm = makeVm(plant(override = SeasonalAmplitude.MILD))
        advanceUntilIdle()

        vm.pinIntervalToBase = true
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertTrue(result.pinIntervalToBase)
        assertNull(result.wateringAmplitudeOverride)
    }

    @Test
    fun `a typed interval de-seasonalizes with the strength chosen in the form`() = runTest {
        val vm = makeVm(plant(), global = SeasonalAmplitude.OFF)
        advanceUntilIdle()

        vm.wateringAmplitudeOverride = SeasonalAmplitude.STRONG
        vm.wateringIntervalDays = 12
        vm.save()
        advanceUntilIdle()

        val expected = 12 / SeasonalWatering.season(january, SeasonalAmplitude.STRONG.value, Hemisphere.NORTHERN)
        assertEquals(expected, saved().wateringBaseIntervalDays!!, 1e-9)
    }

    @Test
    fun `a new plant stores its chosen strength`() = runTest {
        val vm = makeVm(null)
        vm.name = "Cutting"
        vm.wateringAmplitudeOverride = SeasonalAmplitude.MILD
        vm.save()
        advanceUntilIdle()

        val slot = slot<Plant>()
        coVerify { plantRepo.addPlant(capture(slot)) }
        assertEquals(SeasonalAmplitude.MILD, slot.captured.wateringAmplitudeOverride)
    }
}

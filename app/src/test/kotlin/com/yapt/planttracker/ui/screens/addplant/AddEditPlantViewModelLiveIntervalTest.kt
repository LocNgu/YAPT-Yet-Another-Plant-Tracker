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
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * #928 (product ADR-0070): Add/Edit Plant seeds its watering slider with today's live interval and
 * compares Save against that seed. A fixed clock and hemisphere make the seasonal factor deterministic
 * (15 January, northern: winter, the slow season).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlantViewModelLiveIntervalTest {

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
        coEvery { adjustmentRepo.addAdjustment(capture(adjustments)) } returns 1L
    }

    private fun factor(amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD) =
        SeasonalWatering.season(january, amplitude.value, Hemisphere.NORTHERN)

    private fun live(base: Double, amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD) =
        SeasonalWatering.effectiveInterval(base, january, amplitude.value, Hemisphere.NORTHERN)

    private fun dataStore(amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD): DataStore<Preferences> =
        mockk { every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude.name)) }

    private fun plant(
        literal: Int? = 7,
        base: Double? = 8.0,
        pinned: Boolean = false,
        confidence: Int = 3
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = literal,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        wateringConfidence = confidence,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeVm(
        existing: Plant?,
        amplitude: SeasonalAmplitude = SeasonalAmplitude.STANDARD
    ): AddEditPlantViewModel {
        if (existing != null) every { plantRepo.getPlantById(1L) } returns flowOf(existing)
        return AddEditPlantViewModel(
            plantRepo,
            plantPhotoRepo,
            existing?.id,
            dataStore(amplitude),
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
    fun `slider is seeded with today's live value, not the stale stored literal`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()

        assertEquals(live(8.0), vm.wateringIntervalDays)
        assertNotEquals(7, vm.wateringIntervalDays)
        assertTrue(vm.wateringIntervalEnabled)
    }

    @Test
    fun `name-only save with a stale literal leaves base, confidence and audit log untouched`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0, confidence = 3))
        advanceUntilIdle()

        vm.name = "Monstera Renamed"
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(3, result.wateringConfidence)
        assertEquals(7, result.wateringIntervalDays)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `name-only save keeps the literal of a plant that never recorded a base`() = runTest {
        val vm = makeVm(plant(literal = 7, base = null))
        advanceUntilIdle()

        vm.name = "Renamed"
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(7, result.wateringIntervalDays)
        assertNull(result.wateringBaseIntervalDays)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `moving the slider away and back is treated as untouched`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()
        val seed = vm.wateringIntervalDays

        vm.wateringIntervalDays = seed + 4
        vm.wateringIntervalDays = seed
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `moving the slider re-anchors the base, resets confidence and logs one MANUAL_EDIT row`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()
        val typed = vm.wateringIntervalDays + 3

        vm.wateringIntervalDays = typed
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(typed, result.wateringIntervalDays)
        assertEquals(typed / factor(), result.wateringBaseIntervalDays!!, 1e-9)
        assertEquals(0, result.wateringConfidence)
        assertEquals(1, adjustments.size)
        assertEquals(WateringAdjustmentTrigger.MANUAL_EDIT, adjustments.single().trigger)
        assertEquals(8, adjustments.single().beforeIntervalDays)
        assertEquals((typed / factor()).roundToInt(), adjustments.single().afterIntervalDays)
    }

    @Test
    fun `a changed slider that lands on the same rounded base resets confidence but logs no row`() = runTest {
        val amplitude = SeasonalAmplitude.STRONG
        val f = factor(amplitude)
        fun sameBaseValues(base: Int) =
            (1..100).filter { it != live(base.toDouble(), amplitude) && (it / f).roundToInt() == base }
        val base = (3..40).first { sameBaseValues(it).isNotEmpty() }.toDouble()
        val sameBase = sameBaseValues(base.toInt()).first()
        val vm = makeVm(plant(literal = 7, base = base), amplitude)
        advanceUntilIdle()

        vm.wateringIntervalDays = sameBase
        vm.save()
        advanceUntilIdle()

        assertEquals(0, saved().wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `pinning with an untouched slider pins the displayed value and carries the base over`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()
        val seed = vm.wateringIntervalDays

        vm.pinIntervalToBase = true
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertTrue(result.pinIntervalToBase)
        assertEquals(seed, result.wateringIntervalDays)
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `unpinning with an untouched slider keeps the stored base and logs nothing`() = runTest {
        val vm = makeVm(plant(literal = 9, base = 8.0, pinned = true))
        advanceUntilIdle()
        assertEquals(9, vm.wateringIntervalDays)

        vm.pinIntervalToBase = false
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(9, result.wateringIntervalDays)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `a live value above 60 shows truthfully and an untouched save is still a no-op`() = runTest {
        val vm = makeVm(plant(literal = 50, base = 50.0))
        advanceUntilIdle()
        assertEquals(live(50.0), vm.wateringIntervalDays)
        assertTrue(vm.wateringIntervalDays > 60)

        vm.name = "Renamed"
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(50.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `switching the reminder off and on again with an untouched slider is still a no-op`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()

        vm.wateringIntervalEnabled = false
        vm.wateringIntervalEnabled = true
        vm.save()
        advanceUntilIdle()

        val result = saved()
        assertEquals(8.0, result.wateringBaseIntervalDays!!, 0.0)
        assertEquals(3, result.wateringConfidence)
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `amplitude Off seeds the stored literal`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0), SeasonalAmplitude.OFF)
        advanceUntilIdle()

        assertEquals(7, vm.wateringIntervalDays)
        assertNull(vm.seasonalAdjustment)
    }

    @Test
    fun `a new plant still de-seasonalizes the typed value`() = runTest {
        coEvery { plantRepo.addPlant(any()) } returns 42L
        val vm = makeVm(null)
        vm.name = "Fern"
        vm.wateringIntervalEnabled = true
        vm.wateringIntervalDays = 7

        vm.save()
        advanceUntilIdle()

        coVerify {
            plantRepo.addPlant(
                match { it.wateringIntervalDays == 7 && it.wateringBaseIntervalDays == 7 / factor() }
            )
        }
        assertNull(vm.seasonalAdjustment)
    }

    @Test
    fun `the seasonal adjustment carries the season and the rounded base`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0))
        advanceUntilIdle()

        val adjustment = vm.seasonalAdjustment
        assertNotNull(adjustment)
        assertEquals(FertilizingSeason.WINTER, adjustment!!.season)
        assertEquals(8, adjustment.baseDays)
    }

    @Test
    fun `no seasonal adjustment for a pinned plant`() = runTest {
        val vm = makeVm(plant(literal = 9, base = 8.0, pinned = true))
        advanceUntilIdle()

        assertNull(vm.seasonalAdjustment)
    }

    @Test
    fun `no seasonal adjustment while the plant is dormant today`() = runTest {
        val vm = makeVm(plant(literal = 7, base = 8.0).copy(dormancyStartMonth = 12, dormancyEndMonth = 2))
        advanceUntilIdle()

        assertNull(vm.seasonalAdjustment)
        assertEquals(live(8.0), vm.wateringIntervalDays)
    }
}

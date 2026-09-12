package com.yapt.planttracker.domain.usecase

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Pure-JVM tests for [SeasonalGraduationFixup] (#702) — the one-time backfill correcting
 * [Plant.wateringBaseIntervalDays] left stale by `SEASONAL_WATERING` graduating (#656) out of
 * developer mode. See technical/product ADR references in the class KDoc.
 */
class SeasonalGraduationFixupTest {

    // Mid-year: with amplitude 0.5 (northern), season() = 1 - 0.5 = 0.5 (peak day is Jan 5).
    private val midYear = LocalDate.of(2024, 7, 5)
    private val amplitude = 0.5
    private val hemisphere = Hemisphere.NORTHERN

    private fun plant(
        wateringIntervalDays: Int?,
        wateringBaseIntervalDays: Double? = null,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `a plant with a stale base gets recomputed and logs an adjustment`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updatePlant(any()) } returns Unit

        // Literal interval moved to 14 while the base stayed frozen at 7 (the #702 bug).
        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(stalePlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(
            request,
            plantRepository,
            wateringAdjustmentRepository,
            now = 5_000L
        )

        assertEquals(1, changedCount)
        val expectedBase = SeasonalWatering.deseasonalize(14.0, midYear, amplitude, hemisphere)

        val updatedPlant = slot<Plant>()
        coVerify(exactly = 1) { plantRepository.updatePlant(capture(updatedPlant)) }
        assertEquals(expectedBase, updatedPlant.captured.wateringBaseIntervalDays!!, 0.0001)
        assertEquals(14, updatedPlant.captured.wateringIntervalDays)
        assertEquals(5_000L, updatedPlant.captured.updatedAt)

        coVerify(exactly = 1) {
            wateringAdjustmentRepository.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP &&
                        it.beforeIntervalDays == 7 &&
                        it.afterIntervalDays == expectedBase.roundToInt()
                }
            )
        }
    }

    @Test
    fun `a pinned plant is untouched`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val pinnedPlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0, pinIntervalToBase = true)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(pinnedPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `an amplitude-Off plant is untouched with no adjustment row`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(stalePlant),
            amplitude = 0.0,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `a plant with wateringIntervalDays null is untouched`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val neverConfiguredPlant = plant(wateringIntervalDays = null, wateringBaseIntervalDays = null)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(neverConfiguredPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `an already-correct base is a no-op`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        // The base already matches what re-anchoring to today would produce - nothing to fix.
        val expectedBase = SeasonalWatering.deseasonalize(14.0, midYear, amplitude, hemisphere)
        val alreadyCorrectPlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = expectedBase)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(alreadyCorrectPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `maybeRun's flag prevents a second run from re-touching an already-fixed-up plant`() = runTest {
        val dataStoreFile = File.createTempFile("seasonal_graduation_fixup_test_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updatePlant(any()) } returns Unit

        // The same stale plant instance is (deliberately) reused across both calls: if the flag didn't
        // gate the second call, run() would recompute against a different "today" and register another
        // change, since the object passed in never reflects the first call's write.
        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)

        val firstRunChanged = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )
        assertEquals(1, firstRunChanged)
        coVerify(exactly = 1) { plantRepository.updatePlant(any()) }

        val secondRunChanged = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear.plusDays(1)
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )
        assertEquals(0, secondRunChanged)
        // Still exactly once, total - the second call never re-touched the plant.
        coVerify(exactly = 1) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 1) { wateringAdjustmentRepository.addAdjustment(any()) }
    }
}

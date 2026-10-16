package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [WateringLifecycleReset.BootstrapRequest.isGapExcluded] (#897, product ADR-0065): the one gap-exclusion
 * predicate reaches [com.yapt.planttracker.domain.schedule.CareSchedule.bootstrapBaseInterval] through
 * the request, the way the plant's dormancy months do. The filtering itself is in `CareScheduleAwayTest`.
 */
class WateringLifecycleResetAwayTest {

    private fun ms(date: LocalDate) = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    // Three raw gaps (7, 7, 28 across the trip) = exactly MIN_BOOTSTRAP_GAPS before filtering.
    private val timestamps = listOf(
        ms(LocalDate.of(2026, 6, 8)),
        ms(LocalDate.of(2026, 6, 15)),
        ms(LocalDate.of(2026, 6, 22)),
        ms(LocalDate.of(2026, 7, 20))
    )

    private val plant = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = 7,
        wateringConfidence = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `an away-spanning gap in history drops the bootstrap below the threshold`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val adjustments: WateringAdjustmentRepository = mockk(relaxed = true)
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = timestamps,
            boundaryMs = Long.MIN_VALUE,
            seasonFn = { 1.0 },
            isGapExcluded = { earlier, later -> AwayPeriods.spansAway(listOf(trip), earlier, later) }
        )

        val applied = WateringLifecycleReset.maybeBootstrap(request, plantRepository, adjustments, timestamps.last())

        assertFalse(applied)
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
    }

    @Test
    fun `the same history bootstraps when no gap is excluded`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val adjustments: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updatePlant(any()) } returns Unit
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = timestamps,
            boundaryMs = Long.MIN_VALUE,
            seasonFn = { 1.0 }
        )

        val applied = WateringLifecycleReset.maybeBootstrap(request, plantRepository, adjustments, timestamps.last())

        assertTrue(applied)
        coVerify(exactly = 1) { plantRepository.updatePlant(any()) }
    }
}

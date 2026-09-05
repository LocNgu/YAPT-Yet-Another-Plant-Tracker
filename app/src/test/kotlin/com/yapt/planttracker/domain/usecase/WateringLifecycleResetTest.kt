package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for [WateringLifecycleReset]'s decision and persistence behavior (#571/#662).
 * See technical ADR-0023.
 */
class WateringLifecycleResetTest {

    // --- roomChangeTriggersReset() ---

    @Test
    fun `no room set at all does not trigger a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset(null, null))
    }

    @Test
    fun `unchanged room does not trigger a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("Living room", "Living room"))
    }

    @Test
    fun `blank to filled for the first time is data entry, not a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset(null, "Living room"))
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("", "Living room"))
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("   ", "Living room"))
    }

    @Test
    fun `filled to a different filled room is a real move and resets`() {
        assertTrue(WateringLifecycleReset.roomChangeTriggersReset("Living room", "Bedroom"))
    }

    @Test
    fun `filled to blank is treated as a move and resets`() {
        assertTrue(WateringLifecycleReset.roomChangeTriggersReset("Living room", null))
    }

    // --- isFrozen() ---

    @Test
    fun `no freeze marker is never frozen`() {
        assertFalse(WateringLifecycleReset.isFrozen(null, now = 1_000L))
    }

    @Test
    fun `before the freeze marker elapses is frozen`() {
        assertTrue(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 1_000L))
    }

    @Test
    fun `at or after the freeze marker is not frozen`() {
        assertFalse(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 2_000L))
        assertFalse(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 3_000L))
    }

    // #662: the history estimator returns a season-neutral base, but wateringIntervalDays is a
    // literal effective-space value for every display surface that reads it.
    @Test
    fun `bootstrap writes an effective interval while its adjustment stays in base space`() = runTest {
        val plantRepository: PlantRepository = mockk()
        val adjustmentRepository: WateringAdjustmentRepository = mockk()
        coEvery { plantRepository.updatePlant(any()) } returns Unit
        coEvery { adjustmentRepository.addAdjustment(any()) } returns 1L
        val now = 1_800_000_000_000L
        val timestamps = (0..3).map { index ->
            now - TimeUnit.DAYS.toMillis((42 - index * 14).toLong())
        }
        val plant = Plant(
            id = 1L,
            name = "Monstera",
            wateringIntervalDays = 7,
            wateringBaseIntervalDays = 7.0,
            createdAt = 0L,
            updatedAt = 0L
        )
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = timestamps,
            boundaryMs = Long.MIN_VALUE,
            seasonFn = { 1.35 }
        )

        assertTrue(
            WateringLifecycleReset.maybeBootstrap(
                request,
                plantRepository,
                adjustmentRepository,
                now
            )
        )

        val updatedPlant = slot<Plant>()
        coVerify(exactly = 1) { plantRepository.updatePlant(capture(updatedPlant)) }
        assertEquals(14.0 / 1.35, updatedPlant.captured.wateringBaseIntervalDays ?: 0.0, 1e-9)
        assertEquals(14, updatedPlant.captured.wateringIntervalDays)

        val adjustment = slot<WateringAdjustment>()
        coVerify(exactly = 1) { adjustmentRepository.addAdjustment(capture(adjustment)) }
        assertEquals(10, adjustment.captured.afterIntervalDays)
    }
}

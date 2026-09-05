package com.yapt.planttracker.domain.usecase

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Pure-JVM tests for [WateringLifecycleReset]'s decision functions (#571). See technical ADR-0023.
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

    // --- maybeBootstrap() ---

    /** July 5 midday UTC — half a year from the northern peak day (Jan 5), so `season() != 1.0`. */
    private val julyFifthMidYearMs =
        LocalDate.of(2024, 7, 5).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun plant(wateringIntervalDays: Int?) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        wateringConfidence = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    // #662: previously wrote the raw base-space CareSchedule.bootstrapBaseInterval() result straight
    // into the literal Plant.wateringIntervalDays, skipping the base->effective seasonal conversion
    // QuickLogUseCase.applyWateringIntervalSuggestion() already applies for the equivalent write
    // (#626/#644). wateringBaseIntervalDays must stay the raw value; only wateringIntervalDays converts.
    @Test
    fun `maybeBootstrap converts wateringIntervalDays to effective space when a seasonal function is supplied`() =
        runTest {
            val plantRepository: PlantRepository = mockk(relaxed = true)
            val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
            coEvery { plantRepository.updatePlant(any()) } returns Unit

            val amplitude = 0.5
            val hemisphere = Hemisphere.NORTHERN
            val seasonFn: (LocalDate) -> Double = { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
            // 5 timestamps, 7 days apart -> 4 gaps, clears MIN_BOOTSTRAP_GAPS (3).
            val waterLogTimestampsMs = (0..4).map {
                julyFifthMidYearMs - TimeUnit.DAYS.toMillis((28 - it * 7).toLong())
            }
            val request = WateringLifecycleReset.BootstrapRequest(
                plant = plant(wateringIntervalDays = 7),
                waterLogTimestampsMs = waterLogTimestampsMs,
                boundaryMs = Long.MIN_VALUE,
                seasonFn = seasonFn
            )

            val applied = WateringLifecycleReset.maybeBootstrap(
                request,
                plantRepository,
                wateringAdjustmentRepository,
                now = julyFifthMidYearMs
            )

            assertTrue(applied)
            val updatedPlant = slot<Plant>()
            coVerify(exactly = 1) { plantRepository.updatePlant(capture(updatedPlant)) }
            val plantAfter = updatedPlant.captured
            val rawBase = requireNotNull(plantAfter.wateringBaseIntervalDays)
            val expectedEffective = SeasonalWatering.effectiveInterval(
                rawBase,
                LocalDate.of(2024, 7, 5),
                amplitude,
                hemisphere
            )

            assertEquals(expectedEffective, plantAfter.wateringIntervalDays)
            assertNotEquals(rawBase.roundToInt(), plantAfter.wateringIntervalDays)

            // The HISTORY_BOOTSTRAP adjustment row stays base-space, unchanged (mirrors DIALOG_EDIT).
            coVerify {
                wateringAdjustmentRepository.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP &&
                            it.afterIntervalDays == rawBase.roundToInt()
                    }
                )
            }
        }

    @Test
    fun `maybeBootstrap leaves wateringIntervalDays byte-identical when the season function is a no-op`() =
        runTest {
            val plantRepository: PlantRepository = mockk(relaxed = true)
            val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
            coEvery { plantRepository.updatePlant(any()) } returns Unit

            // 5 timestamps, 7 days apart -> 4 gaps, clears MIN_BOOTSTRAP_GAPS (3).
            val waterLogTimestampsMs = (0..4).map {
                julyFifthMidYearMs - TimeUnit.DAYS.toMillis((28 - it * 7).toLong())
            }
            val request = WateringLifecycleReset.BootstrapRequest(
                plant = plant(wateringIntervalDays = 7),
                waterLogTimestampsMs = waterLogTimestampsMs,
                boundaryMs = Long.MIN_VALUE,
                seasonFn = { 1.0 }
            )

            WateringLifecycleReset.maybeBootstrap(
                request,
                plantRepository,
                wateringAdjustmentRepository,
                now = julyFifthMidYearMs
            )

            val updatedPlant = slot<Plant>()
            coVerify(exactly = 1) { plantRepository.updatePlant(capture(updatedPlant)) }
            val plantAfter = updatedPlant.captured
            assertEquals(7, plantAfter.wateringIntervalDays)
            assertEquals(7.0, requireNotNull(plantAfter.wateringBaseIntervalDays), 0.0)
        }
}

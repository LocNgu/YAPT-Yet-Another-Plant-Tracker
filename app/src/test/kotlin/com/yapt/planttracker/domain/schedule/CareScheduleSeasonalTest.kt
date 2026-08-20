package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Wiring tests for #569 (product ADR-0026) — the pure curve itself is covered by [SeasonalWateringTest]. */
class CareScheduleSeasonalTest {

    // Jan 5 12:00 UTC 2023 — the northern-hemisphere peak day, so season(now) = 1 + amplitude exactly.
    private val now = LocalDateUtcMillis(2023, 1, 5)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun plantWith(
        wateringIntervalDays: Int? = 7,
        wateringBaseIntervalDays: Double? = null,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = now,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase
    )

    @Test
    fun `seasonalAmplitude 0 (flag off) ignores wateringBaseIntervalDays entirely`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 5.0),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.0
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(10), status.nextWateringDueAt)
    }

    @Test
    fun `seasonalAmplitude on with no recorded base falls back to wateringIntervalDays as the base`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = null),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        // now is the peak day: effectiveInterval = round(10 * 1.35) = 14 (round-half-up).
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(14), status.nextWateringDueAt)
    }

    @Test
    fun `seasonalAmplitude on with a recorded base multiplies the base, not the literal interval`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            // wateringIntervalDays is stale/face-value; wateringBaseIntervalDays is what's used.
            plant = plantWith(wateringIntervalDays = 999, wateringBaseIntervalDays = 10.0),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(14), status.nextWateringDueAt)
    }

    @Test
    fun `pinIntervalToBase skips the seasonal curve even when seasonalAmplitude is on`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 5.0, pinIntervalToBase = true),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(10), status.nextWateringDueAt)
    }

    @Test
    fun `no watering interval configured stays not scheduled regardless of seasonalAmplitude`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = null, wateringBaseIntervalDays = 5.0),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            seasonalAmplitude = 0.35
        )
        assertNull(status.nextWateringDueAt)
    }
}

@Suppress("FunctionNaming")
private fun LocalDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}

package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Away mode wiring in the pure schedule layer (#897, product ADR-0065): [PlantCareStatus
 * .isWateringGapAwaySpanning] and the gap-exclusion predicate of [CareSchedule.bootstrapBaseInterval].
 * The date predicate itself is covered by `AwayPeriodsTest`.
 */
class CareScheduleAwayTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun ms(date: LocalDate): Long = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))
    private val flatSeason: (LocalDate) -> Double = { 1.0 }

    private fun status(
        lastWatered: LocalDate?,
        today: LocalDate,
        periods: List<AwayPeriod>? = listOf(trip)
    ): PlantCareStatus {
        val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        return if (periods == null) {
            CareSchedule.computeStatus(plant, lastWatered?.let(::ms), null, 0, now = ms(today))
        } else {
            CareSchedule.computeStatus(
                plant,
                lastWatered?.let(::ms),
                null,
                0,
                now = ms(today),
                awayPeriods = periods
            )
        }
    }

    // ---- isWateringGapAwaySpanning ----

    @Test
    fun `a gap that crosses the trip is away-spanning and skips the reason prompt`() {
        val status = status(lastWatered = LocalDate.of(2026, 6, 25), today = LocalDate.of(2026, 7, 20))

        assertTrue(status.isWateringGapAwaySpanning)
        assertTrue(status.skipsWateringReasonPrompt)
    }

    @Test
    fun `a gap that starts inside the trip is away-spanning`() {
        val status = status(lastWatered = LocalDate.of(2026, 7, 10), today = LocalDate.of(2026, 7, 20))

        assertTrue(status.isWateringGapAwaySpanning)
    }

    @Test
    fun `a gap that starts the day after the trip is not away-spanning`() {
        val status = status(lastWatered = LocalDate.of(2026, 7, 16), today = LocalDate.of(2026, 7, 23))

        assertFalse(status.isWateringGapAwaySpanning)
    }

    @Test
    fun `a gap that ends the day before the trip is not away-spanning`() {
        val status = status(lastWatered = LocalDate.of(2026, 6, 20), today = LocalDate.of(2026, 6, 30))

        assertFalse(status.isWateringGapAwaySpanning)
    }

    @Test
    fun `no prior watering reports not away-spanning`() {
        val status = status(lastWatered = null, today = LocalDate.of(2026, 7, 8))

        assertFalse(status.isWateringGapAwaySpanning)
    }

    @Test
    fun `the default of no away periods leaves the flag false`() {
        val status = status(lastWatered = LocalDate.of(2026, 6, 25), today = LocalDate.of(2026, 7, 20), periods = null)

        assertFalse(status.isWateringGapAwaySpanning)
    }

    @Test
    fun `an away trip leaves every due date and flag other than the gap flag unchanged`() {
        val without = status(lastWatered = LocalDate.of(2026, 6, 25), today = LocalDate.of(2026, 7, 20), periods = null)
        val with = status(lastWatered = LocalDate.of(2026, 6, 25), today = LocalDate.of(2026, 7, 20))

        assertEquals(without.nextWateringDueAt, with.nextWateringDueAt)
        assertEquals(without.isOverdue, with.isOverdue)
        assertEquals(without.isDueSoon, with.isDueSoon)
        assertEquals(without, with.copy(isWateringGapAwaySpanning = false))
    }

    @Test
    fun `skipsWateringReasonPrompt is true for each clause alone and false when none holds`() {
        val base = PlantCareStatus(
            plant = Plant(id = 1L, name = "Fern", createdAt = 0L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = null,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = null,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 0,
            isWateringOnSchedule = false
        )

        assertFalse(base.skipsWateringReasonPrompt)
        assertTrue(base.copy(isWateringOnSchedule = true).skipsWateringReasonPrompt)
        assertTrue(base.copy(isWateringGapDormancySpanning = true).skipsWateringReasonPrompt)
        assertTrue(base.copy(isWateringGapAwaySpanning = true).skipsWateringReasonPrompt)
    }

    // ---- bootstrapBaseInterval's gap-exclusion predicate ----

    @Test
    fun `an excluded gap is dropped from gapCount and the median anywhere in history`() {
        val dates = listOf(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8),
            LocalDate.of(2026, 6, 22),
            LocalDate.of(2026, 6, 29)
        )
        val unfiltered = requireNotNull(CareSchedule.bootstrapBaseInterval(dates.map(::ms), flatSeason))
        assertEquals(3, unfiltered.gapCount)
        assertEquals(7.0, unfiltered.baseIntervalDays, 1e-9)

        val excludeTheFourteenDayGap: (Long, Long) -> Boolean = { earlier, later ->
            CareSchedule.daysBetween(earlier, later) == 14
        }
        val filtered = requireNotNull(
            CareSchedule.bootstrapBaseInterval(dates.map(::ms), flatSeason, isGapExcluded = excludeTheFourteenDayGap)
        )
        assertEquals(2, filtered.gapCount)
        assertEquals(7.0, filtered.baseIntervalDays, 1e-9)
    }

    @Test
    fun `the exclusion predicate removes a gap that would skew the median`() {
        val dates = listOf(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8),
            LocalDate.of(2026, 7, 20)
        )
        val skewed = requireNotNull(CareSchedule.bootstrapBaseInterval(dates.map(::ms), flatSeason))
        assertEquals(2, skewed.gapCount)
        assertEquals(24.5, skewed.baseIntervalDays, 1e-9)

        val dropped = requireNotNull(
            CareSchedule.bootstrapBaseInterval(
                dates.map(::ms),
                flatSeason,
                isGapExcluded = { earlier, later -> CareSchedule.daysBetween(earlier, later) > 30 }
            )
        )
        assertEquals(1, dropped.gapCount)
        assertEquals(7.0, dropped.baseIntervalDays, 1e-9)
    }

    @Test
    fun `returns null when the predicate excludes every gap`() {
        val dates = listOf(LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 20))

        assertNull(CareSchedule.bootstrapBaseInterval(dates.map(::ms), flatSeason, isGapExcluded = { _, _ -> true }))
    }

    @Test
    fun `the default predicate excludes nothing`() {
        val dates = listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 8))

        assertNotNull(CareSchedule.bootstrapBaseInterval(dates.map(::ms), flatSeason))
    }
}

package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class CareScheduleSeasonalRepottingTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private val spring = setOf(FertilizingSeason.SPRING)

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun noon(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun plant(
        createdAt: Long = noon(2025, 1, 1),
        repottingIntervalDays: Int? = null,
        repottingSeasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet(),
        repotPlanSeasonStartAt: Long? = null,
        repotPlanMadeAt: Long? = null
    ) = Plant(
        name = "Fern",
        createdAt = createdAt,
        repottingIntervalDays = repottingIntervalDays,
        repottingSeasons = repottingSeasons,
        repotPlanSeasonStartAt = repotPlanSeasonStartAt,
        repotPlanMadeAt = repotPlanMadeAt
    )

    private fun status(
        plant: Plant,
        now: Long,
        lastRepottedAt: Long? = null,
        hemisphere: Hemisphere = Hemisphere.NORTHERN
    ) = CareSchedule.computeStatus(
        plant = plant,
        lastWateredAt = null,
        lastFertilizedAt = null,
        totalLogs = 0,
        now = now,
        lastRepottedAt = lastRepottedAt,
        hemisphere = hemisphere
    )

    private fun noon(date: LocalDate): Long = noon(date.year, date.monthValue, date.dayOfMonth)

    private fun datesFrom(first: LocalDate, last: LocalDate, stepDays: Long = 1): Sequence<LocalDate> =
        generateSequence(first) { it.plusDays(stepDays) }.takeWhile { !it.isAfter(last) }

    // --- unchanged behaviour with the new columns unset ---

    @Test
    fun `default plant keeps the plain interval date and reports no plan`() {
        val created = noon(2025, 6, 15)
        val status = status(plant(createdAt = created, repottingIntervalDays = 365), now = noon(2026, 1, 1))

        assertEquals(created + 365L * 24 * 60 * 60 * 1000, status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
        assertFalse(status.isRepottingPlanned)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    @Test
    fun `every season preferred leaves an overdue interval date overdue`() {
        val lastRepotted = noon(2024, 1, 15)
        val status = status(
            plant(repottingIntervalDays = 365, repottingSeasons = FertilizingSeason.entries.toSet()),
            now = noon(2026, 9, 29),
            lastRepottedAt = lastRepotted
        )

        assertEquals(noon(2025, 1, 14), status.nextRepottingDueAt)
        assertTrue(status.isRepottingOverdue)
    }

    @Test
    fun `no interval and no plan leaves repotting unset`() {
        val status = status(plant(), now = noon(2026, 9, 29))

        assertNull(status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    // --- preferred seasons on the recurring interval ---

    @Test
    fun `interval date moves to the nearest preferred spring`() {
        val lastRepotted = noon(2025, 6, 15)
        val status = status(
            plant(repottingIntervalDays = 730, repottingSeasons = spring),
            now = noon(2026, 9, 29),
            lastRepottedAt = lastRepotted
        )

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
        assertNull(status.repottingPlanSeasonEndAt)
    }

    @Test
    fun `a never-repotted plant's createdAt anchor goes through the same shift`() {
        val created = noon(2025, 6, 15)
        val status = status(
            plant(createdAt = created, repottingIntervalDays = 730, repottingSeasons = spring),
            now = noon(2026, 9, 29)
        )

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
    }

    @Test
    fun `a shifted interval date becomes due on its first day and overdue after it`() {
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = spring)
        val lastRepotted = noon(2025, 6, 15)

        val onFirstDay = status(plant, now = noon(2027, 3, 1), lastRepottedAt = lastRepotted)
        val nextDay = status(plant, now = noon(2027, 3, 2), lastRepottedAt = lastRepotted)

        assertTrue(onFirstDay.isRepottingDueSoon)
        assertFalse(onFirstDay.isRepottingOverdue)
        assertTrue(nextDay.isRepottingOverdue)
        assertFalse(nextDay.isRepottingDueSoon)
    }

    @Test
    fun `a genuinely overdue interval date outside a preferred season stays overdue, not deferred`() {
        val lastRepotted = noon(2024, 7, 1)
        val status = status(
            plant(repottingIntervalDays = 365, repottingSeasons = spring),
            now = noon(2026, 10, 5),
            lastRepottedAt = lastRepotted
        )

        assertEquals(startOfDay(2025, 3, 1), status.nextRepottingDueAt)
        assertTrue(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    // --- the shifted date is time-stable (#809): a function of the raw date, never of today ---

    @Test
    fun `the issue example keeps March 2027 through and after its raw date of June 15 2027`() {
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = spring)
        val lastRepotted = noon(2025, 6, 15)
        val expected = startOfDay(2027, 3, 1)

        for (now in listOf(
            noon(2026, 9, 29),
            noon(2027, 2, 28),
            noon(2027, 3, 1),
            noon(2027, 3, 2),
            noon(2027, 6, 14),
            noon(2027, 6, 15),
            noon(2027, 6, 16),
            noon(2027, 8, 15),
            noon(2028, 8, 15)
        )) {
            assertEquals(expected, status(plant, now, lastRepotted).nextRepottingDueAt)
        }
    }

    @Test
    fun `the issue example is not due before March 1, due on it, and overdue from March 2 for good`() {
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = spring)
        val lastRepotted = noon(2025, 6, 15)

        val before = status(plant, noon(2027, 2, 28), lastRepotted)
        assertFalse(before.isRepottingDueSoon)
        assertFalse(before.isRepottingOverdue)

        val on = status(plant, noon(2027, 3, 1), lastRepotted)
        assertTrue(on.isRepottingDueSoon)
        assertFalse(on.isRepottingOverdue)

        for (date in datesFrom(LocalDate.of(2027, 3, 2), LocalDate.of(2029, 3, 1))) {
            val status = status(plant, noon(date), lastRepotted)
            assertTrue("overdue on $date", status.isRepottingOverdue)
            assertFalse("not due-today on $date", status.isRepottingDueSoon)
        }
    }

    @Test
    fun `a never-repotted plant's createdAt anchor is equally time-stable across its raw date`() {
        val plant = plant(createdAt = noon(2025, 6, 15), repottingIntervalDays = 730, repottingSeasons = spring)

        for (now in listOf(noon(2027, 6, 14), noon(2027, 6, 16), noon(2027, 11, 1))) {
            val status = status(plant, now)
            assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
            assertTrue(status.isRepottingOverdue)
        }
    }

    @Test
    fun `southern hemisphere spring stays September 1 across its raw date and is overdue from the day after`() {
        // Spring is Sep-Nov in the south. Raw Dec 20 2027 is nearer Sep 1 2027 (110 days) than Sep 1 2028.
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = spring)
        val lastRepotted = noon(2025, 12, 20)
        val south = Hemisphere.SOUTHERN

        val nows = listOf(noon(2027, 8, 15), noon(2027, 9, 1), noon(2027, 12, 19), noon(2027, 12, 21), noon(2028, 8, 1))
        for (now in nows) {
            assertEquals(startOfDay(2027, 9, 1), status(plant, now, lastRepotted, south).nextRepottingDueAt)
        }
        assertTrue(status(plant, noon(2027, 9, 1), lastRepotted, south).isRepottingDueSoon)
        for (date in datesFrom(LocalDate.of(2027, 9, 2), LocalDate.of(2029, 1, 1), stepDays = 3)) {
            assertTrue("overdue on $date", status(plant, noon(date), lastRepotted, south).isRepottingOverdue)
        }
    }

    @Test
    fun `a stretch that wraps the year boundary keeps its first day across the raw date`() {
        // Autumn+Winter preferred (north: Sep-Feb). Raw Mar 2 2027 is 182 days after Sep 1 2026 and 183
        // before Sep 1 2027, so it snaps back to Sep 1 2026 and the plant reads overdue from Sep 2 2026.
        val autumnWinter = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = autumnWinter)
        val lastRepotted = noon(2025, 3, 2)

        val nows = listOf(noon(2026, 8, 31), noon(2026, 9, 1), noon(2027, 3, 1), noon(2027, 3, 3), noon(2027, 10, 1))
        for (now in nows) {
            assertEquals(startOfDay(2026, 9, 1), status(plant, now, lastRepotted).nextRepottingDueAt)
        }
        assertTrue(status(plant, noon(2026, 9, 1), lastRepotted).isRepottingDueSoon)
        for (date in datesFrom(LocalDate.of(2026, 9, 2), LocalDate.of(2028, 9, 1), stepDays = 3)) {
            assertTrue("overdue on $date", status(plant, noon(date), lastRepotted).isRepottingOverdue)
        }
    }

    @Test
    fun `the shifted date never changes with today for any preferred set, hemisphere or raw date`() {
        val preferredSets = listOf(
            spring,
            setOf(FertilizingSeason.SUMMER),
            setOf(FertilizingSeason.WINTER),
            setOf(FertilizingSeason.SPRING, FertilizingSeason.AUTUMN),
            setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        )
        val schedules = listOf(LocalDate.of(2025, 6, 15), LocalDate.of(2026, 1, 15)).flatMap { anchor ->
            listOf(60, 180, 400, 730, 1000).map { intervalDays -> anchor to intervalDays }
        }
        val cases = preferredSets.flatMap { preferred ->
            Hemisphere.entries.flatMap { hemisphere ->
                schedules.map { (anchor, intervalDays) -> RepotCase(preferred, hemisphere, anchor, intervalDays) }
            }
        }

        for (case in cases) {
            val plant = plant(repottingIntervalDays = case.intervalDays, repottingSeasons = case.preferred)
            val expected = SeasonalRepotting.nextPreferredDueAtMillis(
                rawDueAtMillis = noon(case.anchor.plusDays(case.intervalDays.toLong())),
                preferredSeasons = case.preferred,
                hemisphere = case.hemisphere,
                anchorAtMillis = noon(case.anchor)
            )
            for (date in datesFrom(case.anchor, LocalDate.of(2031, 12, 31), stepDays = 7)) {
                assertEquals(
                    "$case on $date",
                    expected,
                    status(plant, noon(date), noon(case.anchor), case.hemisphere).nextRepottingDueAt
                )
            }
        }
    }

    private data class RepotCase(
        val preferred: Set<FertilizingSeason>,
        val hemisphere: Hemisphere,
        val anchor: LocalDate,
        val intervalDays: Int
    )

    @Test
    fun `the anchor guard still falls forward, and stays put, however long ago the anchor was`() {
        // Repotted Apr 10 2024 with a 91-day interval: raw Jul 10 2024. Mar 1 2024 precedes the repot, so
        // the date falls forward to Mar 1 2025 and, once past, is overdue rather than pushed to 2026.
        val plant = plant(repottingIntervalDays = 91, repottingSeasons = spring)
        val lastRepotted = noon(2024, 4, 10)

        val nows = listOf(noon(2024, 5, 1), noon(2024, 7, 11), noon(2025, 3, 1), noon(2025, 3, 2), noon(2026, 9, 29))
        for (now in nows) {
            assertEquals(startOfDay(2025, 3, 1), status(plant, now, lastRepotted).nextRepottingDueAt)
        }
        assertTrue(status(plant, noon(2026, 9, 29), lastRepotted).isRepottingOverdue)
    }

    // --- minimum gap (#809): a candidate must fall at least half the interval after the anchor ---

    @Test
    fun `a plant repotted Jan 15 with a 180-day interval is due March 2027, not March 2026, at every date`() {
        val plant = plant(repottingIntervalDays = 180, repottingSeasons = spring)
        val lastRepotted = noon(2026, 1, 15)

        for (now in listOf(
            noon(2026, 2, 1),
            noon(2026, 3, 1),
            noon(2026, 3, 2),
            noon(2026, 7, 14),
            noon(2026, 7, 15),
            noon(2026, 11, 1),
            noon(2027, 2, 28)
        )) {
            val status = status(plant, now, lastRepotted)
            assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
            assertFalse(status.isRepottingOverdue)
            assertFalse(status.isRepottingDueSoon)
        }
        assertTrue(status(plant, noon(2027, 3, 1), lastRepotted).isRepottingDueSoon)
        assertTrue(status(plant, noon(2027, 3, 2), lastRepotted).isRepottingOverdue)
        assertEquals(startOfDay(2027, 3, 1), status(plant, noon(2028, 8, 1), lastRepotted).nextRepottingDueAt)
    }

    @Test
    fun `the too-close guard applies to a never-repotted plant's createdAt anchor`() {
        val plant = plant(createdAt = noon(2026, 1, 15), repottingIntervalDays = 180, repottingSeasons = spring)

        for (now in listOf(noon(2026, 3, 1), noon(2026, 7, 15), noon(2027, 2, 28))) {
            val status = status(plant, now)
            assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
            assertFalse(status.isRepottingOverdue)
        }
    }

    @Test
    fun `the too-close guard mirrors in the southern hemisphere`() {
        val plant = plant(repottingIntervalDays = 180, repottingSeasons = spring)
        val lastRepotted = noon(2026, 7, 15)
        val south = Hemisphere.SOUTHERN

        for (now in listOf(noon(2026, 9, 1), noon(2027, 1, 11), noon(2027, 8, 31))) {
            val status = status(plant, now, lastRepotted, south)
            assertEquals(startOfDay(2027, 9, 1), status.nextRepottingDueAt)
            assertFalse(status.isRepottingOverdue)
        }
        assertTrue(status(plant, noon(2027, 9, 1), lastRepotted, south).isRepottingDueSoon)
        assertTrue(status(plant, noon(2027, 9, 2), lastRepotted, south).isRepottingOverdue)
    }

    @Test
    fun `a year-wrapping stretch rejected for being too close waits a full year`() {
        val autumnWinter = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val plant = plant(repottingIntervalDays = 244, repottingSeasons = autumnWinter)
        val lastRepotted = noon(2026, 7, 1)

        for (now in listOf(noon(2026, 9, 1), noon(2027, 3, 2), noon(2027, 8, 31))) {
            val status = status(plant, now, lastRepotted)
            assertEquals(startOfDay(2027, 9, 1), status.nextRepottingDueAt)
            assertFalse(status.isRepottingOverdue)
        }
        assertTrue(status(plant, noon(2027, 9, 1), lastRepotted).isRepottingDueSoon)
        assertTrue(status(plant, noon(2027, 9, 2), lastRepotted).isRepottingOverdue)
    }

    @Test
    fun `every season preferred still returns the raw date untouched at any date`() {
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = FertilizingSeason.entries.toSet())
        val lastRepotted = noon(2025, 6, 15)

        for (now in listOf(noon(2026, 1, 1), noon(2027, 6, 15), noon(2028, 1, 1))) {
            assertEquals(noon(2027, 6, 15), status(plant, now, lastRepotted).nextRepottingDueAt)
        }
    }

    // --- one-off plan ---

    @Test
    fun `a plan stands alone with no repotting interval configured`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))
        val status = status(plant, now = noon(2026, 9, 29))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertEquals(startOfDay(2027, 6, 1), status.repottingPlanSeasonEndAt)
        assertTrue(status.isRepottingPlanned)
        assertFalse(status.isRepottingDueSoon)
        assertFalse(status.isRepottingOverdue)
    }

    @Test
    fun `a plan is due for its whole season and never overdue while in season`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))

        for (now in listOf(noon(2027, 3, 1), noon(2027, 4, 15), noon(2027, 5, 31))) {
            val status = status(plant, now = now)
            assertTrue(status.isRepottingDueSoon)
            assertFalse(status.isRepottingOverdue)
            assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        }
    }

    @Test
    fun `a plan turns overdue the day after its season ends and stays overdue`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))

        for (now in listOf(noon(2027, 6, 1), noon(2028, 2, 1))) {
            val status = status(plant, now = now)
            assertTrue(status.isRepottingOverdue)
            assertFalse(status.isRepottingDueSoon)
            assertEquals(startOfDay(2027, 6, 1), status.repottingPlanSeasonEndAt)
        }
    }

    @Test
    fun `a plan wins outright over an earlier interval date`() {
        val plant = plant(
            repottingIntervalDays = 365,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2026, 9, 29), lastRepottedAt = noon(2025, 1, 1))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
    }

    @Test
    fun `a plan wins outright over a later interval date`() {
        val plant = plant(
            repottingIntervalDays = 1000,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2027, 3, 10), lastRepottedAt = noon(2026, 9, 1))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertTrue(status.isRepottingDueSoon)
    }

    @Test
    fun `clearing the plan restores the interval date`() {
        val withPlan = plant(
            repottingIntervalDays = 365,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val cleared = withPlan.copy(repotPlanSeasonStartAt = null, repotPlanMadeAt = null)
        val lastRepotted = noon(2026, 1, 1)

        val status = status(cleared, now = noon(2026, 9, 29), lastRepottedAt = lastRepotted)

        assertEquals(noon(2027, 1, 1), status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
    }

    @Test
    fun `a plan ignores the preferred seasons entirely`() {
        val plant = plant(
            repottingIntervalDays = 365,
            repottingSeasons = spring,
            repotPlanSeasonStartAt = startOfDay(2026, 12, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2026, 9, 29), lastRepottedAt = noon(2026, 1, 1))

        assertEquals(startOfDay(2026, 12, 1), status.nextRepottingDueAt)
        assertEquals(startOfDay(2027, 3, 1), status.repottingPlanSeasonEndAt)
    }
}

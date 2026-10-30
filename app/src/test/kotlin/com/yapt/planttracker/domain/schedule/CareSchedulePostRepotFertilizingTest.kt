package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.util.plusCalendarDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

class CareSchedulePostRepotFertilizingTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private val now = utcNoon(2026, 7, 15)
    private fun daysAgo(n: Long) = now.plusCalendarDays(-n)
    private fun plant(
        interval: Int = 30,
        seasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet()
    ) = Plant(
        name = "Fern",
        createdAt = daysAgo(300),
        fertilizingIntervalDays = interval,
        fertilizingSeasons = seasons
    )

    private fun status(
        plant: Plant,
        lastFertilizedAt: Long?,
        lastRepottedAt: Long?,
        at: Long = now,
        hemisphere: Hemisphere = Hemisphere.NORTHERN
    ) = CareSchedule.computeStatus(
        plant = plant,
        lastWateredAt = null,
        lastFertilizedAt = lastFertilizedAt,
        totalLogs = 1,
        now = at,
        lastRepottedAt = lastRepottedAt,
        hemisphere = hemisphere
    )

    @Test
    fun `repot pushes an overdue fertilizing date to repot day plus 30`() {
        val repot = daysAgo(5)
        val s = status(plant(), lastFertilizedAt = daysAgo(100), lastRepottedAt = repot)
        assertEquals(repot.plusCalendarDays(30), s.nextFertilizingDueAt)
        assertFalse(s.isFertilizingOverdue)
    }

    @Test
    fun `never-fertilized plant is held after a repot`() {
        val repot = daysAgo(5)
        val s = status(plant(), lastFertilizedAt = null, lastRepottedAt = repot)
        assertEquals(repot.plusCalendarDays(30), s.nextFertilizingDueAt)
    }

    @Test
    fun `fertilizing after the repot does not cancel the pause`() {
        val repot = daysAgo(10)
        val s = status(plant(interval = 7), lastFertilizedAt = daysAgo(2), lastRepottedAt = repot)
        assertEquals(repot.plusCalendarDays(30), s.nextFertilizingDueAt)
    }

    @Test
    fun `a backdated repot whose window has passed still leaves the plant overdue`() {
        val s = status(plant(), lastFertilizedAt = daysAgo(100), lastRepottedAt = daysAgo(60))
        assertTrue(s.isFertilizingOverdue)
        assertTrue(s.nextFertilizingDueAt!! < now)
    }

    @Test
    fun `a later interval date still wins over the pause`() {
        val fed = daysAgo(1)
        val s = status(plant(interval = 60), lastFertilizedAt = fed, lastRepottedAt = daysAgo(10))
        assertEquals(fed.plusCalendarDays(60), s.nextFertilizingDueAt)
    }

    @Test
    fun `no repot leaves the date unchanged`() {
        val fed = daysAgo(10)
        val s = status(plant(), lastFertilizedAt = fed, lastRepottedAt = null)
        assertEquals(fed.plusCalendarDays(30), s.nextFertilizingDueAt)
    }

    @Test
    fun `pause end in an inactive season is shifted in the northern hemisphere`() {
        val repot = utcNoon(2026, 6, 20)
        val s = status(
            plant(seasons = setOf(FertilizingSeason.WINTER)),
            lastFertilizedAt = utcNoon(2026, 1, 1),
            lastRepottedAt = repot,
            at = utcNoon(2026, 6, 25)
        )
        assertEquals(startOfDay(2026, 12, 1), s.nextFertilizingDueAt)
    }

    @Test
    fun `the same pause end is already active in the southern hemisphere`() {
        val repot = utcNoon(2026, 6, 20)
        val s = status(
            plant(seasons = setOf(FertilizingSeason.WINTER)),
            lastFertilizedAt = utcNoon(2026, 1, 1),
            lastRepottedAt = repot,
            at = utcNoon(2026, 6, 25),
            hemisphere = Hemisphere.SOUTHERN
        )
        assertEquals(repot.plusCalendarDays(30), s.nextFertilizingDueAt)
    }

    @Test
    fun `pause end helper is non-null only while the pause is active`() {
        val today = LocalDate.of(2026, 7, 15)
        assertNull(CareSchedule.postRepotFertilizingPauseEndAt(null, today))
        assertEquals(
            daysAgo(5).plusCalendarDays(30),
            CareSchedule.postRepotFertilizingPauseEndAt(daysAgo(5), today)
        )
        assertNull(CareSchedule.postRepotFertilizingPauseEndAt(daysAgo(30), today))
        assertNull(CareSchedule.postRepotFertilizingPauseEndAt(daysAgo(60), today))
    }

    private fun utcNoon(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, m - 1, d, 12, 0, 0)
        return c.timeInMillis
    }

    private fun startOfDay(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}

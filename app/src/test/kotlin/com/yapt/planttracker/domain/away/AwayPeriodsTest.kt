package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.SitterVisit
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

class AwayPeriodsTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun day(month: Int, dayOfMonth: Int) = LocalDate.of(2026, month, dayOfMonth)

    private fun millis(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun trip(first: LocalDate, last: LocalDate, id: Long = 1L) =
        AwayPeriod(id = id, firstDay = first, lastDay = last, createdAt = 0L)

    private val oct10to20 = trip(day(10, 10), day(10, 20))

    private fun spans(from: LocalDate, to: LocalDate, periods: List<AwayPeriod> = listOf(oct10to20)) =
        AwayPeriods.spansAway(periods, millis(from), millis(to))

    @Test
    fun `isAway is inclusive at both ends`() {
        assertFalse(AwayPeriods.isAway(day(10, 9), listOf(oct10to20)))
        assertTrue(AwayPeriods.isAway(day(10, 10), listOf(oct10to20)))
        assertTrue(AwayPeriods.isAway(day(10, 15), listOf(oct10to20)))
        assertTrue(AwayPeriods.isAway(day(10, 20), listOf(oct10to20)))
        assertFalse(AwayPeriods.isAway(day(10, 21), listOf(oct10to20)))
    }

    @Test
    fun `isAway checks every trip`() {
        val trips = listOf(oct10to20, trip(day(12, 20), day(12, 27), id = 2L))
        assertTrue(AwayPeriods.isAway(day(10, 12), trips))
        assertFalse(AwayPeriods.isAway(day(11, 15), trips))
        assertTrue(AwayPeriods.isAway(day(12, 27), trips))
        assertFalse(AwayPeriods.isAway(day(10, 12), emptyList()))
    }

    @Test
    fun `spansAway is true for a gap ending on the first away day`() {
        assertTrue(spans(day(10, 1), day(10, 10)))
    }

    @Test
    fun `spansAway is true for a gap starting on the last away day`() {
        assertTrue(spans(day(10, 20), day(10, 28)))
    }

    @Test
    fun `spansAway is true for a gap entirely inside the trip`() {
        assertTrue(spans(day(10, 12), day(10, 14)))
    }

    @Test
    fun `spansAway is true for a gap that swallows the trip`() {
        assertTrue(spans(day(10, 1), day(10, 28)))
    }

    @Test
    fun `spansAway is false for a gap entirely before or after the trip`() {
        assertFalse(spans(day(10, 1), day(10, 9)))
        assertFalse(spans(day(10, 21), day(10, 28)))
    }

    @Test
    fun `spansAway is false for a reversed or equal span even inside a trip`() {
        assertFalse(AwayPeriods.spansAway(listOf(oct10to20), millis(day(10, 15)), millis(day(10, 14))))
        assertFalse(AwayPeriods.spansAway(listOf(oct10to20), millis(day(10, 15)), millis(day(10, 15))))
    }

    @Test
    fun `spansAway compares local days so a late-evening gap still touches the day`() {
        assertTrue(AwayPeriods.spansAway(listOf(oct10to20), millis(day(10, 5)), millis(day(10, 10), hour = 0)))
        assertTrue(AwayPeriods.spansAway(listOf(oct10to20), millis(day(10, 20), hour = 23), millis(day(10, 25))))
    }

    @Test
    fun `spansAway looks at every trip and ignores none`() {
        val trips = listOf(oct10to20, trip(day(12, 20), day(12, 27), id = 2L))
        assertTrue(spans(day(11, 25), day(12, 21), trips))
        assertFalse(spans(day(10, 21), day(12, 19), trips))
        assertFalse(spans(day(10, 12), day(10, 14), emptyList()))
    }

    @Test
    fun `spansAway on the 25-hour DST fall-back day still follows local days`() {
        val newYork = ZoneId.of("America/New_York")
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val fallBackDay = LocalDate.of(2026, 11, 1)
        val trip = listOf(trip(fallBackDay, fallBackDay))
        fun at(date: LocalDate, hour: Int, minute: Int = 0) =
            date.atTime(hour, minute).atZone(newYork).toInstant().toEpochMilli()

        assertTrue(AwayPeriods.spansAway(trip, at(fallBackDay.minusDays(1), 22), at(fallBackDay, 0, 30)))
        assertTrue(AwayPeriods.spansAway(trip, at(fallBackDay, 23, 30), at(fallBackDay.plusDays(1), 8)))
        assertFalse(AwayPeriods.spansAway(trip, at(fallBackDay.minusDays(2), 8), at(fallBackDay.minusDays(1), 23, 59)))
        assertFalse(AwayPeriods.spansAway(trip, at(fallBackDay.plusDays(1), 0), at(fallBackDay.plusDays(3), 8)))
    }

    @Test
    fun `currentOrUpcoming returns the trip in progress, else the next one, else null`() {
        val past = trip(day(9, 1), day(9, 10), id = 1L)
        val ongoing = trip(day(10, 10), day(10, 20), id = 2L)
        val next = trip(day(12, 20), day(12, 27), id = 3L)
        assertNull(AwayPeriods.currentOrUpcoming(emptyList(), day(10, 15)))
        assertNull(AwayPeriods.currentOrUpcoming(listOf(past), day(10, 15)))
        assertEquals(ongoing, AwayPeriods.currentOrUpcoming(listOf(next, past, ongoing), day(10, 15)))
        assertEquals(next, AwayPeriods.currentOrUpcoming(listOf(next, past, ongoing), day(10, 21)))
        assertEquals(ongoing, AwayPeriods.currentOrUpcoming(listOf(ongoing), day(10, 20)))
        assertEquals(ongoing, AwayPeriods.currentOrUpcoming(listOf(ongoing), day(10, 1)))
    }

    private fun validate(
        first: LocalDate,
        last: LocalDate,
        today: LocalDate = day(10, 1),
        existing: List<AwayPeriod> = emptyList(),
        editingId: Long? = null
    ) = AwayPeriods.validate(first, last, today, existing, editingId)

    @Test
    fun `a future trip is valid`() {
        assertEquals(AwayPeriodValidation.Valid, validate(day(10, 10), day(10, 20)))
    }

    @Test
    fun `a single-day trip, and one starting and ending today, are valid`() {
        assertEquals(AwayPeriodValidation.Valid, validate(day(10, 10), day(10, 10)))
        assertEquals(AwayPeriodValidation.Valid, validate(day(10, 1), day(10, 1)))
    }

    @Test
    fun `a start in the past is valid while the trip is ongoing`() {
        assertEquals(AwayPeriodValidation.Valid, validate(day(9, 28), day(10, 5)))
        assertEquals(AwayPeriodValidation.Valid, validate(day(9, 28), day(10, 1)))
    }

    @Test
    fun `a reversed trip is rejected`() {
        assertEquals(AwayPeriodValidation.FirstDayAfterLastDay, validate(day(10, 20), day(10, 10)))
    }

    @Test
    fun `a trip that has already ended is rejected`() {
        assertEquals(AwayPeriodValidation.EndsInThePast, validate(day(9, 20), day(9, 30)))
    }

    @Test
    fun `reversed is reported before ended`() {
        assertEquals(AwayPeriodValidation.FirstDayAfterLastDay, validate(day(9, 30), day(9, 20)))
    }

    @Test
    fun `90 days is the longest allowed trip`() {
        val first = day(10, 1)
        assertEquals(AwayPeriodValidation.Valid, validate(first, first.plusDays(89)))
        assertEquals(AwayPeriodValidation.TooLong, validate(first, first.plusDays(90)))
    }

    @Test
    fun `a trip overlapping another is rejected, even at a single shared day`() {
        val existing = listOf(oct10to20)
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(oct10to20),
            validate(day(10, 15), day(10, 25), existing = existing)
        )
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(oct10to20),
            validate(day(10, 20), day(10, 25), existing = existing)
        )
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(oct10to20),
            validate(day(10, 5), day(10, 10), existing = existing)
        )
    }

    @Test
    fun `a second current or upcoming trip is rejected even without overlap`() {
        val existing = listOf(oct10to20)
        assertEquals(
            AwayPeriodValidation.AnotherCurrentOrUpcomingTrip(oct10to20),
            validate(day(10, 25), day(10, 30), existing = existing)
        )
        assertEquals(
            AwayPeriodValidation.AnotherCurrentOrUpcomingTrip(oct10to20),
            validate(day(10, 3), day(10, 5), existing = existing)
        )
    }

    @Test
    fun `past trips never conflict with a new one`() {
        val existing = listOf(trip(day(9, 1), day(9, 10)))
        assertEquals(AwayPeriodValidation.Valid, validate(day(10, 10), day(10, 20), existing = existing))
    }

    @Test
    fun `a new ongoing trip that reaches back over a past trip overlaps it`() {
        val existing = listOf(trip(day(9, 25), day(9, 29)))
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(existing[0]),
            validate(day(9, 27), day(10, 5), existing = existing)
        )
    }

    @Test
    fun `an edited trip never conflicts with itself`() {
        val existing = listOf(oct10to20)
        assertEquals(
            AwayPeriodValidation.Valid,
            validate(day(10, 12), day(10, 25), existing = existing, editingId = oct10to20.id)
        )
        assertEquals(
            AwayPeriodValidation.Valid,
            validate(day(10, 10), day(10, 20), existing = existing, editingId = oct10to20.id)
        )
    }

    @Test
    fun `an edited trip still conflicts with a different trip`() {
        val other = trip(day(11, 1), day(11, 5), id = 2L)
        val existing = listOf(oct10to20, other)
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(other),
            validate(day(10, 12), day(11, 2), existing = existing, editingId = oct10to20.id)
        )
    }

    @Test
    fun `an edit is still subject to the length, ended and order rules`() {
        val existing = listOf(oct10to20)
        assertEquals(
            AwayPeriodValidation.TooLong,
            validate(day(10, 10), day(10, 10).plusDays(95), existing = existing, editingId = oct10to20.id)
        )
        assertEquals(
            AwayPeriodValidation.EndsInThePast,
            validate(day(9, 1), day(9, 5), existing = existing, editingId = oct10to20.id)
        )
    }

    @Test
    fun `endEarly on a mid-trip day makes yesterday the last day`() {
        val result = AwayPeriods.endEarly(oct10to20, day(10, 15))
        assertEquals(oct10to20.copy(lastDay = day(10, 14)), result)
    }

    @Test
    fun `endEarly on the second day keeps the first day only`() {
        assertEquals(oct10to20.copy(lastDay = day(10, 10)), AwayPeriods.endEarly(oct10to20, day(10, 11)))
    }

    @Test
    fun `endEarly on the first day removes the trip`() {
        assertNull(AwayPeriods.endEarly(oct10to20, day(10, 10)))
    }

    @Test
    fun `endEarly on the last day shortens it by one`() {
        assertEquals(oct10to20.copy(lastDay = day(10, 19)), AwayPeriods.endEarly(oct10to20, day(10, 20)))
    }

    @Test
    fun `endEarly never extends a trip that already ended`() {
        assertEquals(oct10to20, AwayPeriods.endEarly(oct10to20, day(11, 5)))
    }

    @Test
    fun `endEarly on an upcoming trip removes it`() {
        assertNull(AwayPeriods.endEarly(oct10to20, day(10, 1)))
    }

    private fun visit(day: LocalDate, id: Long = 0L) = SitterVisit(id = id, awayPeriodId = 1L, day = day)

    @Test
    fun `a visit day must lie inside its trip, inclusive`() {
        assertTrue(AwayPeriods.isVisitDayInside(day(10, 10), day(10, 10), day(10, 20)))
        assertTrue(AwayPeriods.isVisitDayInside(day(10, 20), day(10, 10), day(10, 20)))
        assertFalse(AwayPeriods.isVisitDayInside(day(10, 9), day(10, 10), day(10, 20)))
        assertFalse(AwayPeriods.isVisitDayInside(day(10, 21), day(10, 10), day(10, 20)))
    }

    @Test
    fun `visitsOutside reports the visits an edit strands`() {
        val visits = listOf(visit(day(10, 11), 1), visit(day(10, 15), 2), visit(day(10, 20), 3))
        assertEquals(
            listOf(visits[1], visits[2]),
            AwayPeriods.visitsOutside(visits, day(10, 10), day(10, 14))
        )
        assertEquals(emptyList<SitterVisit>(), AwayPeriods.visitsOutside(visits, day(10, 10), day(10, 20)))
        assertEquals(
            listOf(visits[0]),
            AwayPeriods.visitsOutside(visits, day(10, 12), day(10, 30))
        )
    }

    @Test
    fun `visitsOutside after endEarly reports visits from today onward`() {
        val visits = listOf(visit(day(10, 12), 1), visit(day(10, 15), 2), visit(day(10, 17), 3))
        val shortened = AwayPeriods.endEarly(oct10to20, day(10, 15))!!
        assertEquals(
            listOf(visits[1], visits[2]),
            AwayPeriods.visitsOutside(visits, shortened.firstDay, shortened.lastDay)
        )
    }
}

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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class AwayPeriodsTest {

    private lateinit var originalTimeZone: TimeZone

    // Pinned (technical ADR-0013): New York has a DST fall-back on 2026-11-01, a 25-hour local day.
    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun day(month: Int, dayOfMonth: Int): LocalDate = LocalDate.of(2026, month, dayOfMonth)

    private fun millis(date: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun trip(id: Long, first: LocalDate, last: LocalDate) =
        AwayPeriod(id = id, firstDay = first, lastDay = last, createdAt = 0L)

    private val octTrip = trip(1, day(10, 10), day(10, 20))

    // spansAway: inclusive endpoints

    @Test
    fun `a gap ending on the first away day spans the trip`() {
        assertTrue(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 5)), millis(day(10, 10), 7)))
    }

    @Test
    fun `a gap starting on the last away day spans the trip`() {
        assertTrue(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 20), 21), millis(day(10, 25))))
    }

    @Test
    fun `a gap entirely inside the trip spans it`() {
        assertTrue(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 12)), millis(day(10, 15))))
    }

    @Test
    fun `a gap entirely before or after the trip does not span it`() {
        assertFalse(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 1)), millis(day(10, 9), 23, 59)))
        assertFalse(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 21), 0, 1), millis(day(10, 28))))
    }

    @Test
    fun `a gap covering the whole trip spans it`() {
        assertTrue(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 9)), millis(day(10, 21))))
    }

    @Test
    fun `a reversed or empty span never spans`() {
        val inside = millis(day(10, 15))
        assertFalse(AwayPeriods.spansAway(listOf(octTrip), inside, inside))
        assertFalse(AwayPeriods.spansAway(listOf(octTrip), millis(day(10, 18)), millis(day(10, 12))))
    }

    @Test
    fun `no trips means no span`() {
        assertFalse(AwayPeriods.spansAway(emptyList(), millis(day(10, 1)), millis(day(10, 30))))
    }

    @Test
    fun `any one of several trips is enough`() {
        val novTrip = trip(2, day(11, 10), day(11, 12))
        val periods = listOf(octTrip, novTrip)
        assertTrue(AwayPeriods.spansAway(periods, millis(day(11, 5)), millis(day(11, 11))))
        assertFalse(AwayPeriods.spansAway(periods, millis(day(10, 25)), millis(day(11, 9))))
    }

    @Test
    fun `DST fall-back - a gap is measured in local days, not 24-hour steps`() {
        val novTrip = trip(2, day(11, 2), day(11, 5))
        val start = millis(day(11, 1), 0, 30)
        // 24 real hours after 00:30 on the 25-hour Nov 1 is 23:30 the same local day.
        assertFalse(AwayPeriods.spansAway(listOf(novTrip), start, start + 24L * 60 * 60 * 1000))
        assertTrue(AwayPeriods.spansAway(listOf(novTrip), start, millis(day(11, 2), 0, 30)))
    }

    // isAway / currentOrUpcoming

    @Test
    fun `isAway includes both endpoints and nothing outside`() {
        val periods = listOf(octTrip, trip(2, day(12, 1), day(12, 3)))
        assertTrue(AwayPeriods.isAway(day(10, 10), periods))
        assertTrue(AwayPeriods.isAway(day(10, 20), periods))
        assertTrue(AwayPeriods.isAway(day(12, 2), periods))
        assertFalse(AwayPeriods.isAway(day(10, 9), periods))
        assertFalse(AwayPeriods.isAway(day(10, 21), periods))
        assertFalse(AwayPeriods.isAway(day(11, 15), periods))
    }

    @Test
    fun `currentOrUpcoming returns the ongoing trip, the next one, or null once all have ended`() {
        val past = trip(1, day(9, 1), day(9, 5))
        val upcoming = trip(2, day(11, 1), day(11, 4))
        val periods = listOf(past, octTrip.copy(id = 3), upcoming)
        assertEquals(3L, AwayPeriods.currentOrUpcoming(periods, day(10, 20))?.id)
        assertEquals(2L, AwayPeriods.currentOrUpcoming(periods, day(10, 21))?.id)
        assertNull(AwayPeriods.currentOrUpcoming(periods, day(11, 5)))
    }

    // validate (spec Q1)

    private val today = day(10, 15)

    @Test
    fun `a future trip within 90 days is valid`() {
        assertEquals(AwayPeriodValidation.Valid, AwayPeriods.validate(day(11, 1), day(11, 10), today, emptyList()))
    }

    @Test
    fun `a trip that already started but has not ended is valid`() {
        assertEquals(AwayPeriodValidation.Valid, AwayPeriods.validate(day(10, 10), today, today, emptyList()))
    }

    @Test
    fun `a last day before the first day is rejected`() {
        assertEquals(
            AwayPeriodValidation.EndsBeforeStart,
            AwayPeriods.validate(day(11, 10), day(11, 1), today, emptyList())
        )
    }

    @Test
    fun `a trip that has already ended is rejected`() {
        assertEquals(
            AwayPeriodValidation.AlreadyEnded,
            AwayPeriods.validate(day(10, 1), day(10, 14), today, emptyList())
        )
    }

    @Test
    fun `90 days is the limit`() {
        val first = day(11, 1)
        assertEquals(AwayPeriodValidation.Valid, AwayPeriods.validate(first, first.plusDays(89), today, emptyList()))
        assertEquals(
            AwayPeriodValidation.TooLong,
            AwayPeriods.validate(first, first.plusDays(90), today, emptyList())
        )
    }

    @Test
    fun `overlapping another trip is rejected, including a past one`() {
        val past = trip(7, day(10, 1), day(10, 12))
        assertEquals(
            AwayPeriodValidation.OverlapsTrip(past),
            AwayPeriods.validate(day(10, 12), day(10, 18), today, listOf(past))
        )
    }

    @Test
    fun `a second current or upcoming trip is rejected`() {
        val upcoming = trip(8, day(12, 1), day(12, 5))
        assertEquals(
            AwayPeriodValidation.AnotherTripPlanned(upcoming),
            AwayPeriods.validate(day(11, 1), day(11, 3), today, listOf(upcoming))
        )
    }

    @Test
    fun `a past trip that does not overlap does not block a new one`() {
        val past = trip(7, day(9, 1), day(9, 5))
        assertEquals(AwayPeriodValidation.Valid, AwayPeriods.validate(day(11, 1), day(11, 3), today, listOf(past)))
    }

    @Test
    fun `an edited trip never conflicts with itself`() {
        val current = trip(9, day(10, 10), day(10, 20))
        assertEquals(
            AwayPeriodValidation.Valid,
            AwayPeriods.validate(day(10, 10), day(10, 25), today, listOf(current), editingId = 9)
        )
    }

    // endEarly (I'm back)

    @Test
    fun `I'm back mid-trip makes yesterday the last day`() {
        assertEquals(day(10, 14), AwayPeriods.endEarly(octTrip, day(10, 15))?.lastDay)
    }

    @Test
    fun `I'm back on the first day removes the trip`() {
        assertNull(AwayPeriods.endEarly(octTrip, day(10, 10)))
    }

    @Test
    fun `I'm back on the last day keeps the trip until yesterday`() {
        assertEquals(day(10, 19), AwayPeriods.endEarly(octTrip, day(10, 20))?.lastDay)
    }

    @Test
    fun `I'm back never extends a trip that already ended`() {
        assertEquals(octTrip, AwayPeriods.endEarly(octTrip, day(10, 25)))
    }

    // visitsOutside

    @Test
    fun `visits outside the edited dates are reported`() {
        val visits = listOf(
            SitterVisit(id = 1, awayPeriodId = 1, day = day(10, 11)),
            SitterVisit(id = 2, awayPeriodId = 1, day = day(10, 16)),
            SitterVisit(id = 3, awayPeriodId = 1, day = day(10, 19))
        )
        val shortened = octTrip.copy(lastDay = day(10, 16))
        assertEquals(listOf(3L), AwayPeriods.visitsOutside(shortened, visits).map { it.id })
        val ended = checkNotNull(AwayPeriods.endEarly(octTrip, day(10, 12)))
        assertEquals(listOf(2L, 3L), AwayPeriods.visitsOutside(ended, visits).map { it.id })
        assertEquals(emptyList<SitterVisit>(), AwayPeriods.visitsOutside(octTrip, visits))
    }
}

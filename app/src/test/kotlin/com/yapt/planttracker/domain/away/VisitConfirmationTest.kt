package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Confirming sitter visits after the trip (#900, product ADR-0065): the offer window, its order and the Settings trip. */
class VisitConfirmationTest {

    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 10, dayOfMonth)

    private fun visit(
        id: Long,
        tripId: Long,
        dayOfMonth: Int,
        state: SitterVisitState = SitterVisitState.PLANNED,
        plantCount: Int = 2
    ) = SitterVisit(
        id = id,
        awayPeriodId = tripId,
        day = day(dayOfMonth),
        state = state,
        plants = (1..plantCount).map { SitterVisitPlant(it.toLong()) }
    )

    private fun trip(id: Long, firstDay: Int, lastDay: Int, vararg visits: SitterVisit) =
        AwayPeriodWithVisits(AwayPeriod(id, day(firstDay), day(lastDay), createdAt = 1L), visits.toList())

    @Test
    fun `nothing is offered while the trip is still on`() {
        val trips = listOf(trip(1, 1, 10, visit(1, 1, 5)))

        assertTrue(visitsToConfirmAfterTrip(trips, day(10)).isEmpty())
    }

    @Test
    fun `the first day back offers every planned visit of the trip`() {
        val trips = listOf(trip(1, 1, 10, visit(1, 1, 3), visit(2, 1, 8)))

        assertEquals(listOf(1L, 2L), visitsToConfirmAfterTrip(trips, day(11)).map { it.id })
    }

    @Test
    fun `visits are offered oldest first whatever order they are stored in`() {
        val trips = listOf(trip(1, 1, 10, visit(2, 1, 8), visit(1, 1, 3), visit(3, 1, 8)))

        assertEquals(listOf(1L, 2L, 3L), visitsToConfirmAfterTrip(trips, day(11)).map { it.id })
    }

    @Test
    fun `the offer ends 14 days after the last away day`() {
        val trips = listOf(trip(1, 1, 10, visit(1, 1, 5)))

        assertEquals(1, visitsToConfirmAfterTrip(trips, day(24)).size)
        assertTrue(visitsToConfirmAfterTrip(trips, day(25)).isEmpty())
    }

    @Test
    fun `an answered visit is not offered again`() {
        val answered = visit(1, 1, 3, SitterVisitState.CONFIRMED)
        val didNotHappen = visit(2, 1, 5, SitterVisitState.DID_NOT_HAPPEN)
        val trips = listOf(trip(1, 1, 10, answered, didNotHappen, visit(3, 1, 8)))

        assertEquals(listOf(3L), visitsToConfirmAfterTrip(trips, day(11)).map { it.id })
    }

    @Test
    fun `a visit with no plants left is not offered`() {
        val trips = listOf(trip(1, 1, 10, visit(1, 1, 5, plantCount = 0), visit(2, 1, 6)))

        assertEquals(listOf(2L), visitsToConfirmAfterTrip(trips, day(11)).map { it.id })
    }

    @Test
    fun `a trip that ended earlier but is still inside its window is offered with the newer one`() {
        val trips = listOf(trip(1, 1, 5, visit(1, 1, 3)), trip(2, 8, 10, visit(2, 2, 9)))

        assertEquals(listOf(1L, 2L), visitsToConfirmAfterTrip(trips, day(12)).map { it.id })
    }

    @Test
    fun `a trip that is upcoming offers nothing`() {
        val trips = listOf(trip(1, 20, 25, visit(1, 1, 21)))

        assertTrue(visitsToConfirmAfterTrip(trips, day(11)).isEmpty())
    }

    @Test
    fun `settings lists the current trip, else the most recently ended one`() {
        val ended = trip(1, 1, 5)
        val older = trip(2, 1, 2)
        val current = trip(3, 9, 12)

        assertEquals(3L, settingsVisitTrip(listOf(ended, current), day(10))?.period?.id)
        assertEquals(1L, settingsVisitTrip(listOf(older, ended), day(10))?.period?.id)
        assertNull(settingsVisitTrip(emptyList(), day(10)))
    }

    @Test
    fun `settings lists an upcoming trip rather than an ended one`() {
        val ended = trip(1, 1, 5)
        val upcoming = trip(2, 20, 25)

        assertEquals(2L, settingsVisitTrip(listOf(ended, upcoming), day(10))?.period?.id)
    }

    @Test
    fun `a planned visit can be confirmed from its own day on, during or after the trip`() {
        val planned = visit(1, 1, 5)

        assertFalse(planned.canConfirmOn(day(4)))
        assertTrue(planned.canConfirmOn(day(5)))
        assertTrue(planned.canConfirmOn(day(30)))
    }

    @Test
    fun `an answered visit cannot be confirmed again`() {
        assertFalse(visit(1, 1, 5, SitterVisitState.CONFIRMED).canConfirmOn(day(8)))
        assertFalse(visit(1, 1, 5, SitterVisitState.DID_NOT_HAPPEN).canConfirmOn(day(8)))
    }
}

package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.AwayPeriod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [isChosenDateAwaySpanning] (#897, product ADR-0065): Plant Detail's reason-prompt gate checks the
 * *picked* date against its own predecessor, so a backdated pick is judged on the gap it really closes.
 */
class PlantDetailScreenGateAwayTest {

    private val zone = ZoneId.systemDefault()
    private fun millisAt(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    @Test
    fun `a pick that closes a gap crossing the trip is away-spanning`() {
        assertTrue(
            isChosenDateAwaySpanning(
                listOf(trip),
                millisAt(LocalDate.of(2026, 6, 25)),
                millisAt(LocalDate.of(2026, 7, 20))
            )
        )
    }

    @Test
    fun `a backdated pick inside the trip is away-spanning even when today is long after it`() {
        assertTrue(
            isChosenDateAwaySpanning(
                listOf(trip),
                millisAt(LocalDate.of(2026, 6, 28)),
                millisAt(LocalDate.of(2026, 7, 5))
            )
        )
    }

    @Test
    fun `a pick whose own predecessor is after the trip is not away-spanning`() {
        assertFalse(
            isChosenDateAwaySpanning(
                listOf(trip),
                millisAt(LocalDate.of(2026, 7, 16)),
                millisAt(LocalDate.of(2026, 7, 23))
            )
        )
    }

    @Test
    fun `no predecessor or no trips is never away-spanning`() {
        assertFalse(isChosenDateAwaySpanning(listOf(trip), null, millisAt(LocalDate.of(2026, 7, 5))))
        assertFalse(
            isChosenDateAwaySpanning(
                emptyList(),
                millisAt(LocalDate.of(2026, 6, 25)),
                millisAt(LocalDate.of(2026, 7, 20))
            )
        )
    }
}

package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import io.mockk.mockk
import io.mockk.verify
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

    // ---- requestWater / requestLiquidFertilize wiring ----

    private val previousWateringAt = millisAt(LocalDate.of(2026, 6, 25))
    private val pickedAt = millisAt(LocalDate.of(2026, 7, 20))

    // 25 days after a 7-day interval is well off schedule, so only the trip can skip the prompt.
    private val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)

    private fun context(viewModel: PlantDetailViewModel, trips: List<AwayPeriod>) =
        QuickWaterGateContext(plant, seasonalAmplitude = 0.0, viewModel = viewModel, awayPeriods = trips)

    @Test
    fun `requestWater logs straight away with the picked date when the gap spans a trip`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestWater(context(viewModel, listOf(trip)), pickedAt, previousWateringAt) { sheetShown = true }

        verify(exactly = 1) { viewModel.quickWater(reason = null, loggedAt = pickedAt) }
        assertFalse(sheetShown)
    }

    @Test
    fun `requestWater still shows the reason sheet for the same off-schedule gap without a trip`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestWater(context(viewModel, emptyList()), pickedAt, previousWateringAt) { sheetShown = true }

        assertTrue(sheetShown)
        verify(exactly = 0) { viewModel.quickWater(any(), any()) }
    }

    @Test
    fun `requestLiquidFertilize logs straight away with the picked date when the gap spans a trip`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestLiquidFertilize(context(viewModel, listOf(trip)), pickedAt, previousWateringAt) { sheetShown = true }

        verify(exactly = 1) { viewModel.quickLiquidFertilize(reason = null, loggedAt = pickedAt) }
        assertFalse(sheetShown)
    }

    @Test
    fun `requestLiquidFertilize still shows the reason sheet for the same off-schedule gap without a trip`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestLiquidFertilize(context(viewModel, emptyList()), pickedAt, previousWateringAt) { sheetShown = true }

        assertTrue(sheetShown)
        verify(exactly = 0) { viewModel.quickLiquidFertilize(any(), any()) }
    }
}

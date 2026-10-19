package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The "Soil was already too dry" tick (#838, product ADR-0067) against the reason gate: visible on
 * schedule and early, hidden late and when the gap spans dormancy or a trip; ticked it logs
 * [WateringReason.PLANT_NEEDED_IT] without asking, unticked it behaves exactly as before.
 */
class PlantDetailScreenGateSoilTooDryTest {

    private val zone = ZoneId.systemDefault()
    private fun millisAt(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val previousWateringAt = millisAt(LocalDate.of(2026, 6, 8))
    private val onSchedule = millisAt(LocalDate.of(2026, 6, 15))
    private val early = millisAt(LocalDate.of(2026, 6, 11))
    private val late = millisAt(LocalDate.of(2026, 6, 25))

    private val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)

    private fun inputs(
        plant: Plant = this.plant,
        trips: List<AwayPeriod> = emptyList()
    ) = WateringGateInputs(plant, seasonalAmplitude = 0.0, awayPeriods = trips)

    private fun context(viewModel: PlantDetailViewModel, plant: Plant = this.plant) =
        QuickWaterGateContext(plant, seasonalAmplitude = 0.0, viewModel = viewModel, awayPeriods = emptyList())

    @Test
    fun `option is visible for an on-schedule pick`() {
        assertTrue(isSoilTooDryOptionVisible(inputs(), previousWateringAt, onSchedule))
    }

    @Test
    fun `option is visible for an early pick`() {
        assertTrue(isSoilTooDryOptionVisible(inputs(), previousWateringAt, early))
    }

    @Test
    fun `option is hidden for a late pick`() {
        assertFalse(isSoilTooDryOptionVisible(inputs(), previousWateringAt, late))
    }

    @Test
    fun `option is hidden when the gap spans dormancy`() {
        val dormant = plant.copy(dormancyStartMonth = 6, dormancyEndMonth = 7)
        assertFalse(isSoilTooDryOptionVisible(inputs(dormant), previousWateringAt, onSchedule))
    }

    @Test
    fun `option is hidden when the gap spans an away trip`() {
        val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 6, 10), lastDay = LocalDate.of(2026, 6, 12))
        assertFalse(isSoilTooDryOptionVisible(inputs(trips = listOf(trip)), previousWateringAt, onSchedule))
    }

    @Test
    fun `ticked on schedule logs the plant needed it reason`() {
        assertEquals(
            WateringGateResult.Log(WateringReason.PLANT_NEEDED_IT),
            evaluateWateringGate(inputs(), onSchedule, previousWateringAt, soilTooDry = true)
        )
    }

    @Test
    fun `ticked early logs the plant needed it reason and does not ask`() {
        assertEquals(
            WateringGateResult.Log(WateringReason.PLANT_NEEDED_IT),
            evaluateWateringGate(inputs(), early, previousWateringAt, soilTooDry = true)
        )
    }

    @Test
    fun `unticked keeps today's behaviour per direction`() {
        val inputs = inputs()
        assertEquals(WateringGateResult.Log(null), evaluateWateringGate(inputs, onSchedule, previousWateringAt, false))
        assertEquals(WateringGateResult.AskReason, evaluateWateringGate(inputs, early, previousWateringAt, false))
        assertEquals(WateringGateResult.AskReason, evaluateWateringGate(inputs, late, previousWateringAt, false))
    }

    @Test
    fun `an 8-day gap on a 7-day plant is late even inside the tolerance band so the option is hidden`() {
        val eightDays = millisAt(LocalDate.of(2026, 6, 16))

        assertTrue(isChosenDateOnSchedule(plant, previousWateringAt, eightDays, seasonalAmplitude = 0.0))
        assertTrue(isChosenDateGapLong(plant, previousWateringAt, eightDays, seasonalAmplitude = 0.0))
        assertFalse(isSoilTooDryOptionVisible(inputs(), previousWateringAt, eightDays))
        assertEquals(
            WateringGateResult.Log(null),
            evaluateWateringGate(inputs(), eightDays, previousWateringAt, soilTooDry = true)
        )
        assertEquals(
            WateringGateResult.Log(null),
            evaluateWateringGate(inputs(), eightDays, previousWateringAt, soilTooDry = false)
        )
    }

    @Test
    fun `a tick on a late date is ignored and the late prompt still asks`() {
        assertEquals(
            WateringGateResult.AskReason,
            evaluateWateringGate(inputs(), late, previousWateringAt, soilTooDry = true)
        )
    }

    @Test
    fun `a tick on a dormancy-spanning gap is ignored`() {
        val dormant = plant.copy(dormancyStartMonth = 6, dormancyEndMonth = 7)
        assertEquals(
            WateringGateResult.Log(null),
            evaluateWateringGate(inputs(dormant), onSchedule, previousWateringAt, soilTooDry = true)
        )
    }

    @Test
    fun `requestWater logs the picked date with the reason when ticked on schedule`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestWater(context(viewModel), onSchedule, previousWateringAt, soilTooDry = true) { sheetShown = true }

        verify(exactly = 1) { viewModel.quickWater(WateringReason.PLANT_NEEDED_IT, onSchedule) }
        assertFalse(sheetShown)
    }

    @Test
    fun `requestWater skips the why-now sheet when ticked early`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestWater(context(viewModel), early, previousWateringAt, soilTooDry = true) { sheetShown = true }

        verify(exactly = 1) { viewModel.quickWater(WateringReason.PLANT_NEEDED_IT, early) }
        assertFalse(sheetShown)
    }

    @Test
    fun `requestWater unticked on schedule logs no reason and early still asks`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestWater(context(viewModel), onSchedule, previousWateringAt) { sheetShown = true }
        verify(exactly = 1) { viewModel.quickWater(null, onSchedule) }
        assertFalse(sheetShown)

        requestWater(context(viewModel), early, previousWateringAt) { sheetShown = true }
        assertTrue(sheetShown)
        verify(exactly = 0) { viewModel.quickWater(null, early) }
    }

    @Test
    fun `requestLiquidFertilize logs the paired watering with the reason when ticked`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestLiquidFertilize(context(viewModel), early, previousWateringAt, soilTooDry = true) { sheetShown = true }

        verify(exactly = 1) { viewModel.quickLiquidFertilize(WateringReason.PLANT_NEEDED_IT, early) }
        assertFalse(sheetShown)
    }

    @Test
    fun `requestLiquidFertilize still asks late even when ticked`() {
        val viewModel = mockk<PlantDetailViewModel>(relaxed = true)
        var sheetShown = false

        requestLiquidFertilize(context(viewModel), late, previousWateringAt, soilTooDry = true) { sheetShown = true }

        assertTrue(sheetShown)
        verify(exactly = 0) { viewModel.quickLiquidFertilize(any(), any()) }
    }
}

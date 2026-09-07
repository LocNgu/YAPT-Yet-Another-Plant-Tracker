package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [isChosenDateOnSchedule]/[isChosenDateGapLong] unit coverage for the #679 backdating bug: the
 * "Log watering" date picker's on/off-schedule gate must compare a backdated [loggedAt] against
 * *that date's own chronological predecessor* ([PlantDetailViewModel.previousWateringBefore]) rather
 * than [com.yapt.planttracker.domain.model.PlantCareStatus.lastWateredAt] (always the plant's
 * globally newest watering). Both functions were made `internal` (from `private`) specifically for
 * this test — see the KDoc on [isChosenDateOnSchedule] for why an instrumented test driving the real
 * Material3 `DatePicker` to a specific backdated day was judged impractical instead.
 *
 * This exercises the exact scenario from the bug report: a chosen date that falls *before* an
 * already-existing later watering. Passing the real predecessor (earlier than [chosenDate]) is the
 * fixed behavior; passing a reference *later* than [chosenDate] (standing in for the old, buggy
 * `status.lastWateredAt` wiring) demonstrates the wrong-direction answer the fix prevents.
 */
class PlantDetailScreenGateTest {

    private val zone = ZoneId.systemDefault()

    private fun millisAt(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val chosenDate = millisAt(LocalDate.of(2026, 6, 15))

    // The watering the chosen date actually follows chronologically — 21 days earlier, well past a
    // 7-day interval's GAP_AGREEMENT_TOLERANCE, so the real gap ran long.
    private val realPredecessor = millisAt(LocalDate.of(2026, 5, 25))

    // Stands in for the plant's globally newest watering (PlantCareStatus.lastWateredAt) in the
    // buggy wiring — chronologically *after* chosenDate, since chosenDate backdates to before it.
    private val laterUnrelatedWatering = millisAt(LocalDate.of(2026, 6, 20))

    private fun plant(intervalDays: Int = 7) = Plant(
        id = 1L,
        name = "Fern",
        wateringIntervalDays = intervalDays,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `chosen date against its real predecessor is off schedule and gap ran long`() {
        assertFalse(isChosenDateOnSchedule(plant(), realPredecessor, chosenDate, seasonalAmplitude = 0.0))
        assertTrue(isChosenDateGapLong(plant(), realPredecessor, chosenDate, seasonalAmplitude = 0.0))
    }

    @Test
    fun `chosen date against a later unrelated watering is off schedule but not reported as gap long`() {
        // This is the #679 bug reproduced directly: comparing against a watering that is chronologically
        // *after* the chosen date yields a negative observed gap, which never reads as "ran long" —
        // the reason prompt would have asked "Why now?" (early wording) instead of "Why was it late?".
        assertFalse(isChosenDateOnSchedule(plant(), laterUnrelatedWatering, chosenDate, seasonalAmplitude = 0.0))
        assertFalse(isChosenDateGapLong(plant(), laterUnrelatedWatering, chosenDate, seasonalAmplitude = 0.0))
    }

    @Test
    fun `no predecessor at all is treated as on schedule`() {
        assertTrue(isChosenDateOnSchedule(plant(), null, chosenDate, seasonalAmplitude = 0.0))
        assertFalse(isChosenDateGapLong(plant(), null, chosenDate, seasonalAmplitude = 0.0))
    }
}

package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringFeedback.JUST_RIGHT
import com.yapt.planttracker.domain.model.WateringFeedback.TOO_LATE
import com.yapt.planttracker.domain.model.WateringFeedback.TOO_SOON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the multiplicative + confidence-weighted adaptive watering model (#568,
 * technical ADR-0021). See [CareScheduleAdaptiveReplayTest] for the multi-observation replay harness.
 */
class CareScheduleAdaptiveTest {

    // --- correctionStreak() ---

    @Test
    fun `correctionStreak of empty list is zero`() {
        assertEquals(0, CareSchedule.correctionStreak(emptyList()))
    }

    @Test
    fun `correctionStreak of a single TOO_SOON is plus one`() {
        assertEquals(1, CareSchedule.correctionStreak(listOf(TOO_SOON)))
    }

    @Test
    fun `correctionStreak of a single TOO_LATE is minus one`() {
        assertEquals(-1, CareSchedule.correctionStreak(listOf(TOO_LATE)))
    }

    @Test
    fun `correctionStreak of a run of two same-direction is plus two`() {
        assertEquals(2, CareSchedule.correctionStreak(listOf(TOO_SOON, TOO_SOON)))
    }

    @Test
    fun `correctionStreak of a run of three same-direction is plus three`() {
        assertEquals(3, CareSchedule.correctionStreak(listOf(TOO_SOON, TOO_SOON, TOO_SOON)))
    }

    @Test
    fun `correctionStreak broken by JUST_RIGHT does not extend past it`() {
        // [TOO_SOON, TOO_SOON, JUST_RIGHT] -> +2, per the documented example.
        assertEquals(2, CareSchedule.correctionStreak(listOf(TOO_SOON, TOO_SOON, JUST_RIGHT)))
    }

    @Test
    fun `correctionStreak broken by JUST_RIGHT at the start is zero`() {
        assertEquals(0, CareSchedule.correctionStreak(listOf(JUST_RIGHT, TOO_SOON, TOO_SOON)))
    }

    @Test
    fun `correctionStreak broken by null does not extend past it`() {
        // The leading TOO_LATE is counted; the null immediately after it stops the run.
        assertEquals(-1, CareSchedule.correctionStreak(listOf(TOO_LATE, null, TOO_LATE)))
    }

    @Test
    fun `correctionStreak broken by null at the start is zero`() {
        assertEquals(0, CareSchedule.correctionStreak(listOf(null, TOO_SOON)))
    }

    @Test
    fun `correctionStreak stops at a direction reversal`() {
        // [TOO_LATE, TOO_SOON] -> -1, per the documented example.
        assertEquals(-1, CareSchedule.correctionStreak(listOf(TOO_LATE, TOO_SOON)))
    }

    @Test
    fun `correctionStreak reversal after a run keeps only the leading run`() {
        assertEquals(2, CareSchedule.correctionStreak(listOf(TOO_SOON, TOO_SOON, TOO_LATE)))
    }

    // --- computeAdaptiveInterval(): bootstrap (wateringConfidence == null) ---

    @Test
    fun `first observation bootstraps confidence to zero without evaluating a transition`() {
        // A streak that WOULD decrement confidence if evaluated (>= 2) must be ignored on bootstrap.
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 7,
            currentBaseIntervalDays = 7,
            currentConfidence = null,
            recentFeedback = listOf(TOO_SOON, TOO_SOON, TOO_SOON)
        )
        assertEquals(0, result.confidence)
    }

    @Test
    fun `first observation applies the base correction at the confidence-0 gain`() {
        // target = 7 * 1.25 = 8.75; base = 7 + 0.60*(8.75-7) = 8.05 -> rounds to 8.
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 7,
            currentBaseIntervalDays = 7,
            currentConfidence = null,
            recentFeedback = listOf(TOO_SOON)
        )
        assertEquals(8, result.intervalDays)
    }

    @Test
    fun `first observation after a bootstrap follows normal transition rules`() {
        // wateringConfidence non-null (e.g. seeded by a future history bootstrap, #571) -> normal rules.
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 7,
            currentBaseIntervalDays = 7,
            currentConfidence = 2,
            recentFeedback = listOf(TOO_SOON, TOO_SOON)
        )
        assertEquals(0, result.confidence) // streak of 2 -> max(2-2, 0)
    }

    // --- computeAdaptiveInterval(): gap agreement raises confidence ---

    @Test
    fun `gap agreement raises confidence by one`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = JUST_RIGHT,
            observedIntervalDays = 10,
            currentBaseIntervalDays = 10,
            currentConfidence = 1,
            recentFeedback = listOf(JUST_RIGHT)
        )
        assertEquals(2, result.confidence)
    }

    @Test
    fun `gap agreement increment is capped at five`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = JUST_RIGHT,
            observedIntervalDays = 10,
            currentBaseIntervalDays = 10,
            currentConfidence = 5,
            recentFeedback = listOf(JUST_RIGHT)
        )
        assertEquals(5, result.confidence)
    }

    @Test
    fun `gap disagreement leaves confidence unchanged`() {
        // observed 20 vs predicted 10 is far outside the 15% tolerance.
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = JUST_RIGHT,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = 2,
            recentFeedback = listOf(JUST_RIGHT)
        )
        assertEquals(2, result.confidence)
    }

    @Test
    fun `confidence never rises from the feedback chip alone on an off-schedule watering`() {
        // JUST_RIGHT every time, but the observed gap never agrees with the (unmoving) base.
        var confidence: Int? = null
        var base = 8
        val history = mutableListOf<WateringFeedback?>()
        repeat(2) {
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = JUST_RIGHT,
                observedIntervalDays = 13,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(JUST_RIGHT) + history).take(3)
            )
            history.add(0, JUST_RIGHT)
            base = result.intervalDays
            confidence = result.confidence
        }
        // Two observations in: still climbing toward 13, not yet within tolerance of the moving base.
        assertEquals(0, confidence)
    }

    // --- computeAdaptiveInterval(): same-direction streak lowers confidence ---

    @Test
    fun `same-direction streak of two decrements confidence by two`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 7,
            currentBaseIntervalDays = 7,
            currentConfidence = 3,
            recentFeedback = listOf(TOO_SOON, TOO_SOON)
        )
        assertEquals(1, result.confidence)
    }

    @Test
    fun `same-direction streak decrement floors at zero`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 7,
            currentBaseIntervalDays = 7,
            currentConfidence = 1,
            recentFeedback = listOf(TOO_SOON, TOO_SOON)
        )
        assertEquals(0, result.confidence)
    }

    @Test
    fun `alternating feedback does not trigger the streak decrement`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 10,
            currentBaseIntervalDays = 10,
            currentConfidence = 2,
            recentFeedback = listOf(TOO_SOON, TOO_LATE)
        )
        // No streak (>= 2) branch; falls through to gap agreement (observed == base -> agrees).
        assertEquals(3, result.confidence)
    }

    // --- computeAdaptiveInterval(): clamps ---

    @Test
    fun `result is clamped to at most 180 days`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 179,
            currentBaseIntervalDays = 179,
            currentConfidence = 0,
            recentFeedback = listOf(TOO_SOON)
        )
        assertTrue(result.intervalDays <= 180)
    }

    @Test
    fun `result is clamped to at least 1 day`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_LATE,
            observedIntervalDays = 1,
            currentBaseIntervalDays = 1,
            currentConfidence = 0,
            recentFeedback = listOf(TOO_LATE)
        )
        assertTrue(result.intervalDays >= 1)
    }

    @Test
    fun `no single step moves the base by more than 40 percent`() {
        val oldBase = 10
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_SOON,
            observedIntervalDays = 100, // wildly larger than base, would push target way up
            currentBaseIntervalDays = oldBase,
            currentConfidence = 0,
            recentFeedback = listOf(TOO_SOON)
        )
        val change = kotlin.math.abs(result.intervalDays - oldBase).toDouble() / oldBase
        assertTrue("step change $change exceeded 40%", change <= 0.40 + 1e-9)
    }

    @Test
    fun `observed interval of zero never produces a zero-day result (#446 regression guard)`() {
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = TOO_LATE,
            observedIntervalDays = 0,
            currentBaseIntervalDays = 7,
            currentConfidence = 0,
            recentFeedback = listOf(TOO_LATE)
        )
        assertTrue(result.intervalDays >= 1)
    }

    // --- confidenceAfterDismissal() ---

    @Test
    fun `dismissal raises null confidence to one`() {
        assertEquals(1, CareSchedule.confidenceAfterDismissal(null))
    }

    @Test
    fun `repeated dismissals raise confidence to three and no further`() {
        var confidence: Int? = null
        repeat(5) { confidence = CareSchedule.confidenceAfterDismissal(confidence) }
        assertEquals(3, confidence)
    }

    @Test
    fun `dismissal on a plant already above the ceiling leaves it unchanged`() {
        assertEquals(4, CareSchedule.confidenceAfterDismissal(4))
        assertEquals(5, CareSchedule.confidenceAfterDismissal(5))
    }

    // --- confidenceAfterDialogEdit() ---

    @Test
    fun `dialog edit within tolerance leaves confidence unchanged`() {
        assertEquals(3, CareSchedule.confidenceAfterDialogEdit(3, suggestedIntervalDays = 10, appliedIntervalDays = 11))
    }

    @Test
    fun `dialog edit outside tolerance decrements confidence by two`() {
        assertEquals(1, CareSchedule.confidenceAfterDialogEdit(3, suggestedIntervalDays = 10, appliedIntervalDays = 20))
    }

    @Test
    fun `dialog edit outside tolerance floors at zero`() {
        assertEquals(0, CareSchedule.confidenceAfterDialogEdit(1, suggestedIntervalDays = 10, appliedIntervalDays = 20))
    }

    @Test
    fun `applying the suggestion unchanged leaves confidence unchanged`() {
        assertEquals(2, CareSchedule.confidenceAfterDialogEdit(2, suggestedIntervalDays = 10, appliedIntervalDays = 10))
    }
}

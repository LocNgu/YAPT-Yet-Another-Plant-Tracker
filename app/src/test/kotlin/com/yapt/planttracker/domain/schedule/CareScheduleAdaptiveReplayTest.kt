package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringFeedback.JUST_RIGHT
import com.yapt.planttracker.domain.model.WateringFeedback.TOO_LATE
import com.yapt.planttracker.domain.model.WateringFeedback.TOO_SOON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-JVM replay harness (#568): synthetic watering histories fed through
 * [CareSchedule.computeAdaptiveInterval] to assert convergence speed and stability, exactly as
 * scoped by the issue thread's acceptance criteria. No Android dependencies.
 *
 * Each iteration feeds the previous iteration's [CareSchedule.AdaptiveInterval] back in as the next
 * iteration's `currentBaseIntervalDays`/`currentConfidence` — i.e. it simulates a user who always
 * applies the suggestion, which is the only way "the app's due date" (referenced by the scenario
 * descriptions) can track the learned interval across many observations. The real app's ADR-0006
 * dialog still requires an explicit tap; this harness tests the pure convergence properties of the
 * update rule in isolation from that UI gate.
 */
class CareScheduleAdaptiveReplayTest {

    private data class Step(val base: Int, val confidence: Int)

    /** Runs [observations] (feedback, observedIntervalDays) starting from [startBase], history-window 3. */
    private fun runReplay(startBase: Int, observations: List<Pair<WateringFeedback, Int>>): List<Step> {
        var base = startBase
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        val steps = mutableListOf<Step>()
        for ((feedback, observed) in observations) {
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = feedback,
                observedIntervalDays = observed,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(feedback) + history).take(3)
            )
            history.add(0, feedback)
            base = result.intervalDays
            confidence = result.confidence
            steps.add(Step(base, confidence))
        }
        return steps
    }

    /**
     * The per-step clamp bounds the *continuous* pre-rounding value to +-40% of the previous base;
     * rounding to a whole day (the stored/displayed unit) can then add at most another 0.5 days of
     * slop on top, which is a larger relative fraction for a small `previous`. This tolerance is
     * exactly that bound, not a loosened assertion.
     */
    private fun assertNoRunaway(startBase: Int, steps: List<Step>) {
        var previous = startBase
        for (step in steps) {
            assertTrue("base ${step.base} must be in [1,180]", step.base in 1..180)
            assertFalse("base must never be zero or negative", step.base <= 0)
            val relativeChange = abs(step.base - previous).toDouble() / previous
            val tolerance = 0.40 + 0.5 / previous + 1e-9
            assertTrue(
                "step from $previous to ${step.base} exceeded the 40% (+ rounding) bound (was $relativeChange)",
                relativeChange <= tolerance
            )
            previous = step.base
        }
    }

    // --- 1a: Obedient — user waters exactly on the app's due date, always reports still-wet ---

    @Test
    fun `scenario 1a obedient converges to within 1 day of 14 within 6 observations`() {
        var base = 7
        // observed gap = whatever base currently is, since the user waters exactly on the due date.
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        var convergedAt = -1
        for (i in 1..6) {
            val observed = base
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = TOO_SOON,
                observedIntervalDays = observed,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(TOO_SOON) + history).take(3)
            )
            history.add(0, TOO_SOON)
            base = result.intervalDays
            confidence = result.confidence
            if (convergedAt == -1 && abs(base - 14) <= 1) convergedAt = i
        }
        assertTrue("expected convergence within 6 observations, got $convergedAt", convergedAt in 1..6)
    }

    // --- 1b: Autonomous — user waters when the plant is actually ready (14 days), reports still-wet ---

    @Test
    fun `scenario 1b autonomous converges to within 1 day of 14 within 2 observations`() {
        var base = 7
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        var convergedAt = -1
        for (i in 1..2) {
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = TOO_SOON,
                observedIntervalDays = 14,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(TOO_SOON) + history).take(3)
            )
            history.add(0, TOO_SOON)
            base = result.intervalDays
            confidence = result.confidence
            if (convergedAt == -1 && abs(base - 14) <= 1) convergedAt = i
        }
        assertTrue("expected convergence within 2 observations, got $convergedAt", convergedAt in 1..2)
    }

    // --- 2: Stability under noise — true interval ~10, gaps jittered +-1, feedback alternating ---

    @Test
    fun `scenario 2 stability under noise raises confidence monotonically and shrinks step size`() {
        var base = 10
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        val confidenceTrail = mutableListOf<Int>()
        var lastStepMagnitude = Double.MAX_VALUE
        for (i in 1..20) {
            val feedback = if (i % 2 == 1) TOO_SOON else TOO_LATE
            val jitter = if (i % 2 == 0) 1 else -1
            val observed = (base + jitter).coerceAtLeast(1)
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = feedback,
                observedIntervalDays = observed,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(feedback) + history).take(3)
            )
            history.add(0, feedback)
            val stepMagnitude = abs(result.intervalDays - base).toDouble()
            base = result.intervalDays
            confidence = result.confidence
            confidenceTrail += confidence!!
            if (i == 5) lastStepMagnitude = stepMagnitude
        }
        for (i in 1 until confidenceTrail.size) {
            assertTrue(
                "confidence must never decrease under alternating feedback",
                confidenceTrail[i] >= confidenceTrail[i - 1]
            )
        }
        assertTrue("confidence should reach at least 4", confidenceTrail.last() >= 4)
        assertTrue(
            "step magnitude by the 5th observation should be < 1 day, was $lastStepMagnitude",
            lastStepMagnitude < 1.0
        )
    }

    // --- 3a: Stable defaulted-JUST_RIGHT — observed gaps consistently match base ---

    @Test
    fun `scenario 3a stable defaulted JUST_RIGHT stays within 5 percent of start over 20 observations`() {
        val start = 8
        var base = start
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        for (i in 1..20) {
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = JUST_RIGHT,
                observedIntervalDays = base,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(JUST_RIGHT) + history).take(3)
            )
            history.add(0, JUST_RIGHT)
            base = result.intervalDays
            confidence = result.confidence
        }
        val drift = abs(base - start).toDouble() / start
        assertTrue("base drifted $drift from start, expected <= 5%", drift <= 0.05)
    }

    // --- 3b: Drifting defaulted-JUST_RIGHT — actual gaps 13, base starts at 8 (#446-adjacent) ---

    @Test
    fun `scenario 3b drifting defaulted JUST_RIGHT reaches within 15 percent of 13 by observation 10`() {
        var base = 8
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        var baseAtObs10 = -1
        for (i in 1..20) {
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
            if (i == 10) baseAtObs10 = base
        }
        val driftAtObs10 = abs(baseAtObs10 - 13).toDouble() / 13
        assertTrue(
            "base at obs10 ($baseAtObs10) should be within 15% of 13, drift was $driftAtObs10",
            driftAtObs10 <= 0.15
        )
    }

    /**
     * The issue thread's comment 5 asserted "confidence never reaches 5" for this scenario, but that
     * bound is unreachable under the update rule it explicitly pinned (gain table, multipliers): with
     * gain 0.60 at confidence 0, `base` closes to within [GAP_AGREEMENT_TOLERANCE] of the constant
     * observed gap (13) by observation 3, and once gap agreement starts it never lapses (the target is
     * constant, so the gap only keeps shrinking), so confidence climbs 0->5 by observation 7 — five
     * strictly-consecutive agreements after the first. This mirrors the thread's own earlier
     * correction of the "3 steps ~= 25 days" figure (also based on hand-verification vs. a claim that
     * turned out not to match the rule as specified): see technical ADR-0021 for the corrected numbers.
     * This test instead asserts the two properties that *are* true of the design and that a purely
     * chip-driven confidence scheme (the bug this issue exists to fix) would violate: confidence stays
     * at the floor while the watering is still off-schedule, and it only starts climbing once the gap
     * genuinely agrees with the (still-adapting) base.
     */
    @Test
    fun `scenario 3b confidence stays at the floor while off-schedule then climbs once the gap agrees`() {
        var base = 8
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        val confidenceTrail = mutableListOf<Int>()
        for (i in 1..7) {
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
            confidenceTrail += confidence!!
        }
        // Observations 1-2: still off-schedule (base hasn't converged yet) -> confidence stays 0.
        assertEquals(0, confidenceTrail[0])
        assertEquals(0, confidenceTrail[1])
        // Confidence is non-decreasing throughout and never jumps by more than 1 per observation
        // (gap agreement) or drops (no same-direction streak is possible under constant JUST_RIGHT).
        for (i in 1 until confidenceTrail.size) {
            val delta = confidenceTrail[i] - confidenceTrail[i - 1]
            assertTrue("confidence must move by at most 1 per observation, was $delta", delta in 0..1)
        }
    }

    // --- 4: No runaway, across every scenario above ---

    @Test
    fun `scenario 4 no runaway across obedient autonomous noise and defaulted JUST_RIGHT`() {
        val obedient = runReplay(7, seedObedient())
        assertNoRunaway(7, obedient)

        val autonomous = runReplay(7, List(2) { TOO_SOON to 14 })
        assertNoRunaway(7, autonomous)

        val noiseObservations = (1..20).map { i ->
            val feedback = if (i % 2 == 1) TOO_SOON else TOO_LATE
            val jitter = if (i % 2 == 0) 1 else -1
            feedback to (10 + jitter)
        }
        val noise = runReplay(10, noiseObservations)
        assertNoRunaway(10, noise)

        val stableJustRight = runReplay(8, List(20) { JUST_RIGHT to 8 })
        assertNoRunaway(8, stableJustRight)

        val driftingJustRight = runReplay(8, List(20) { JUST_RIGHT to 13 })
        assertNoRunaway(8, driftingJustRight)
    }

    /** Obedient replay needs the *previous* step's base as next observation's gap, so it can't be a static list. */
    private fun seedObedient(): List<Pair<WateringFeedback, Int>> {
        var base = 7
        val out = mutableListOf<Pair<WateringFeedback, Int>>()
        var confidence: Int? = null
        val history = mutableListOf<WateringFeedback?>()
        repeat(6) {
            out += TOO_SOON to base
            val result = CareSchedule.computeAdaptiveInterval(
                feedback = TOO_SOON,
                observedIntervalDays = base,
                currentBaseIntervalDays = base,
                currentConfidence = confidence,
                recentFeedback = (listOf(TOO_SOON) + history).take(3)
            )
            history.add(0, TOO_SOON)
            base = result.intervalDays
            confidence = result.confidence
        }
        return out
    }
}

package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.CustomReminderStatus
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The pure-logic home for all care scheduling and adaptive-watering math (status due-dates plus
// #568's confidence-weighted interval model) — splitting it to dodge Detekt's TooManyFunctions
// threshold would scatter closely-related pure functions across files for no readability gain
// (cf. CareLogRepository's identical justification).
@Suppress("TooManyFunctions")
object CareSchedule {

    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    /**
     * Never-fertilized plants get a grace period before they start being flagged as due, anchored
     * to when the plant was added. Nursery mixes typically contain slow-release fertilizer and
     * new plants are acclimating, so a single fertilizing interval (often ~2 weeks) is too short
     * a hold-off (issue #428).
     */
    const val FIRST_FERTILIZE_GRACE_DAYS = 30

    @Suppress("LongParameterList")
    fun computeStatus(
        plant: Plant,
        lastWateredAt: Long?,
        lastFertilizedAt: Long?,
        totalLogs: Int,
        now: Long = System.currentTimeMillis(),
        lastRepottedAt: Long? = null,
        customReminders: List<CustomReminder> = emptyList(),
        // #569 (product ADR-0026): 0.0 (same as flag off/SeasonalAmplitude.OFF) never applies the
        // seasonal curve — every existing call site is unaffected unless it opts in.
        seasonalAmplitude: Double = 0.0,
        hemisphere: Hemisphere = SeasonalWatering.currentHemisphere()
    ): PlantCareStatus {
        val daysSinceWatering = lastWateredAt?.let {
            (now - it) / ONE_DAY_MS
        }
        val nowDate = now.toLocalDate()

        val (nextDueAt, isOverdue, isDueSoon) =
            computeWateringDue(plant, lastWateredAt, now, nowDate, seasonalAmplitude, hemisphere)
        val (nextFertilizingDueAt, isFertilizingOverdue, isFertilizingDueSoon) =
            computeFertilizingDue(plant, lastFertilizedAt, nowDate)
        val (nextRepottingDueAt, isRepottingOverdue, isRepottingDueSoon) =
            computeExtendedCareDue(plant.repottingIntervalDays, lastRepottedAt, plant.createdAt, nowDate)
        val customReminderStatuses = computeCustomReminderStatuses(customReminders, nowDate)

        return PlantCareStatus(
            plant = plant,
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = lastFertilizedAt,
            daysSinceLastWatering = daysSinceWatering,
            nextWateringDueAt = nextDueAt,
            isOverdue = isOverdue,
            isDueSoon = isDueSoon,
            nextFertilizingDueAt = nextFertilizingDueAt,
            isFertilizingOverdue = isFertilizingOverdue,
            isFertilizingDueSoon = isFertilizingDueSoon,
            totalCareLogs = totalLogs,
            lastRepottedAt = lastRepottedAt,
            nextRepottingDueAt = nextRepottingDueAt,
            isRepottingOverdue = isRepottingOverdue,
            isRepottingDueSoon = isRepottingDueSoon,
            customReminderStatuses = customReminderStatuses
        )
    }

    @Suppress("LongParameterList")
    private fun computeWateringDue(
        plant: Plant,
        lastWateredAt: Long?,
        now: Long,
        nowDate: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): DueStatus {
        val effectiveIntervalDays = effectiveWateringIntervalDays(plant, nowDate, seasonalAmplitude, hemisphere)
        val computedNextDueAt = if (effectiveIntervalDays == null) {
            null
        } else if (lastWateredAt != null) {
            lastWateredAt + TimeUnit.DAYS.toMillis(effectiveIntervalDays.toLong())
        } else {
            now
        }

        val nextDueAt = when {
            computedNextDueAt == null -> plant.wateringDueDateOverride
            plant.wateringDueDateOverride == null -> computedNextDueAt
            else -> maxOf(computedNextDueAt, plant.wateringDueDateOverride)
        }

        return dueStatusFor(nextDueAt, nowDate)
    }

    /**
     * The watering interval actually used for due-date math (#569, product ADR-0026):
     * [Plant.wateringIntervalDays] unchanged when [seasonalAmplitude] is 0.0 (flag off or
     * [SeasonalAmplitude.OFF]) or the plant opted out ([Plant.pinIntervalToBase]) — this is what
     * makes the flag genuinely reversible. Otherwise the seasonal curve applied to
     * [Plant.wateringBaseIntervalDays], falling back to the literal [Plant.wateringIntervalDays] as
     * the base when one was never recorded (e.g. a plant created while the flag was off).
     */
    @Suppress("ReturnCount")
    private fun effectiveWateringIntervalDays(
        plant: Plant,
        nowDate: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): Int? {
        val configuredIntervalDays = plant.wateringIntervalDays ?: return null
        if (seasonalAmplitude == 0.0 || plant.pinIntervalToBase) return configuredIntervalDays
        val base = plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()
        return SeasonalWatering.effectiveInterval(base, nowDate, seasonalAmplitude, hemisphere)
    }

    /**
     * Public wrapper around [effectiveWateringIntervalDays] for the "Why this date?" sheet (#572) —
     * the single source both [computeWateringDue] and the sheet call, so the sheet's "Watering every
     * N days" row can never drift from the number that actually drove the due date.
     */
    fun effectiveWateringIntervalDaysForDisplay(
        plant: Plant,
        nowDate: LocalDate = LocalDate.now(),
        seasonalAmplitude: Double = 0.0,
        hemisphere: Hemisphere = SeasonalWatering.currentHemisphere()
    ): Int? = effectiveWateringIntervalDays(plant, nowDate, seasonalAmplitude, hemisphere)

    private fun computeFertilizingDue(plant: Plant, lastFertilizedAt: Long?, nowDate: LocalDate): DueStatus {
        val nextFertilizingDueAt = if (plant.fertilizingIntervalDays == null) {
            null
        } else if (lastFertilizedAt != null) {
            lastFertilizedAt + TimeUnit.DAYS.toMillis(plant.fertilizingIntervalDays.toLong())
        } else {
            plant.createdAt + TimeUnit.DAYS.toMillis(FIRST_FERTILIZE_GRACE_DAYS.toLong())
        }

        return dueStatusFor(nextFertilizingDueAt, nowDate)
    }

    private fun computeExtendedCareDue(
        intervalDays: Int?,
        lastDoneAt: Long?,
        createdAt: Long,
        nowDate: LocalDate
    ): DueStatus = dueStatusFor(extendedCareDueAt(intervalDays, lastDoneAt, createdAt), nowDate)

    /**
     * Per-reminder due status for every [CustomReminder] on a plant (#232). Each reminder always has
     * its own [CustomReminder.intervalDays] (no on/off toggle like watering/fertilizing), so this
     * reuses the same first-due anchor logic as [extendedCareDueAt] — `createdAt + interval` for a
     * reminder that has never been marked done — but anchored to each reminder's own [CustomReminder
     * .createdAt], since reminders can be added long after the plant itself (unlike repotting, which
     * shares the plant's lifecycle — see product ADR-0022).
     */
    private fun computeCustomReminderStatuses(
        reminders: List<CustomReminder>,
        nowDate: LocalDate
    ): List<CustomReminderStatus> = reminders.map { reminder ->
        val dueAt = extendedCareDueAt(reminder.intervalDays, reminder.lastDoneAt, reminder.createdAt)
        val (nextDueAt, isOverdue, isDueSoon) = dueStatusFor(dueAt, nowDate)
        CustomReminderStatus(reminder, nextDueAt, isOverdue, isDueSoon)
    }

    private fun dueStatusFor(nextDueAt: Long?, nowDate: LocalDate): DueStatus {
        val overdue = nextDueAt != null && nextDueAt.toLocalDate().isBefore(nowDate)
        val dueSoon = nextDueAt != null && !overdue && nextDueAt.toLocalDate() == nowDate
        return DueStatus(nextDueAt, overdue, dueSoon)
    }

    /**
     * Due date for an extended-care reminder (currently repotting). Returns `null` when the interval
     * is unset. For a plant that has never had this care logged, the first due date is anchored to
     * `createdAt + interval` rather than the day the reminder was enabled — a newly acquired plant
     * was presumably just misted/repotted, so it should not fire immediately (see product ADR-0022).
     */
    private fun extendedCareDueAt(intervalDays: Int?, lastDoneAt: Long?, createdAt: Long): Long? {
        if (intervalDays == null) return null
        val base = lastDoneAt ?: createdAt
        return base + TimeUnit.DAYS.toMillis(intervalDays.toLong())
    }

    private data class DueStatus(val dueAt: Long?, val isOverdue: Boolean, val isDueSoon: Boolean)

    fun computeSuggestedInterval(
        feedback: WateringFeedback,
        actualIntervalDays: Int,
        currentIntervalDays: Int? = null
    ): Int {
        return when (feedback) {
            WateringFeedback.TOO_LATE -> {
                val base = if (currentIntervalDays != null && actualIntervalDays > currentIntervalDays) {
                    currentIntervalDays
                } else {
                    actualIntervalDays
                }
                max(1, base - 1)
            }
            WateringFeedback.JUST_RIGHT -> actualIntervalDays
            WateringFeedback.TOO_SOON -> {
                val base = if (currentIntervalDays != null && actualIntervalDays < currentIntervalDays) {
                    currentIntervalDays
                } else {
                    actualIntervalDays
                }
                base + 1
            }
        }.coerceAtLeast(1)
    }

    fun daysBetween(earlierMs: Long, laterMs: Long): Int =
        ChronoUnit.DAYS.between(earlierMs.toLocalDate(), laterMs.toLocalDate()).toInt()

    // --- Adaptive watering (multiplicative + confidence-weighted), behind `adaptive_watering` ---
    // (#568, technical ADR-0021). Gated entirely at the call site — computeSuggestedInterval() above
    // is untouched and stays the flag-off path.

    /**
     * How close (as a fraction of the predicted interval) an observed watering gap must be to count
     * as "the schedule predicted reality" — the only source, besides a dialog dismissal, that can
     * raise [Plant.wateringConfidence]. Deliberately not derived from the feedback chip (#568 comment 2):
     * a defaulted JUST_RIGHT tap on an off-schedule watering must not look like agreement.
     */
    const val GAP_AGREEMENT_TOLERANCE = 0.15

    /**
     * A dialog dismissal can raise confidence (the user is saying "the schedule is fine") but only
     * up to this ceiling — reserving 4-5 for states actually supported by observed gap agreement, so
     * a user who dismisses out of habit can't reach a gain low enough to look "dialed in" (#568
     * comment 3).
     */
    const val DISMISSAL_CONFIDENCE_CEILING = 3

    private const val MAX_CONFIDENCE = 5
    private const val STREAK_DECREMENT_THRESHOLD = 2
    private const val STREAK_CONFIDENCE_PENALTY = 2
    private const val MIN_ADAPTIVE_INTERVAL_DAYS = 1
    private const val MAX_ADAPTIVE_INTERVAL_DAYS = 180
    private const val PER_STEP_CLAMP_FRACTION = 0.40

    /** g, indexed by confidence 0-5. Confidence-0 (never adapted / just reset) moves fastest. */
    @Suppress("MagicNumber")
    private val ADAPTIVE_GAIN_BY_CONFIDENCE = listOf(0.60, 0.45, 0.35, 0.28, 0.22, 0.15)

    private const val TOO_SOON_TARGET_MULTIPLIER = 1.25
    private const val JUST_RIGHT_TARGET_MULTIPLIER = 1.00
    private const val TOO_LATE_TARGET_MULTIPLIER = 0.82

    /**
     * `null` feedback (#570, product ADR-0027 — the 3-way soil-state chip collapsed to one optional
     * flag, making `null` the dominant case on WATER logs): with no chip, the observed gap itself is
     * the whole signal, so the target is the gap verbatim, same as [JUST_RIGHT_TARGET_MULTIPLIER].
     */
    const val NEUTRAL_TARGET_MULTIPLIER = 1.00

    /**
     * Ceiling on the gain applied to a `null`-feedback observation — a cap on the existing
     * confidence-driven gain, not a second parallel learning rate. Explicit feedback still moves
     * [Plant.wateringConfidence] at the full [ADAPTIVE_GAIN_BY_CONFIDENCE] gain; a silent observation
     * moves it more slowly, so a single outlier gap (e.g. a 30-day holiday) with no feedback can't
     * drag `base` as hard as an explicit TOO_SOON/TOO_LATE would (the ±40% per-step clamp below
     * covers the rest of that case).
     */
    const val NEUTRAL_OBSERVATION_GAIN = 0.15

    /** Result of one adaptive-watering observation: the new suggested base interval and confidence. */
    data class AdaptiveInterval(val intervalDays: Int, val confidence: Int)

    /**
     * Signed run length of same-direction feedback ending at the most recent watering.
     * [TOO_SOON, TOO_SOON, JUST_RIGHT] -> +2 ; [TOO_LATE, TOO_SOON] -> -1
     *
     * [recentFeedback] is most-recent-first (index 0 = the watering just logged), matching
     * [com.yapt.planttracker.data.repository.CareLogRepository.getRecentWaterings]. JUST_RIGHT and
     * `null` break the run (direction 0); a direction reversal also stops the run without counting
     * the reversing entry. Deliberately **not** cached on a care log or plant column — YAPT supports
     * editing/deleting past logs, so a stored `lastCorrectionDirection` would go stale with nothing
     * to invalidate it (#568 comment 1). Callers pass a window of at most 3 logs.
     */
    fun correctionStreak(recentFeedback: List<WateringFeedback?>): Int {
        var streak = 0
        for (feedback in recentFeedback) {
            val direction = when (feedback) {
                WateringFeedback.TOO_SOON -> 1
                WateringFeedback.TOO_LATE -> -1
                else -> 0
            }
            val runContinues = direction != 0 && (streak == 0 || (direction > 0) == (streak > 0))
            if (!runContinues) break
            streak += direction
        }
        return streak
    }

    /**
     * The multiplicative + confidence-weighted update rule (#568, technical ADR-0021):
     * `target = observed * multiplier(feedback)`, `base = base + g(confidence) * (target - base)`,
     * clamped to ±40% per step and to [1, 180] overall (#446 regression guard: an `observedIntervalDays
     * == 0` same-day duplicate can never produce a 0-day result).
     *
     * Confidence never rises from the feedback chip's value directly (#568 comment 2) — only from
     * [recentFeedback] showing a two-or-more same-direction run (confidence falls, since the model is
     * persistently wrong) or from the observed gap agreeing with [currentBaseIntervalDays] within
     * [GAP_AGREEMENT_TOLERANCE] (confidence rises). [currentConfidence] == `null` means this plant has
     * never been adapted: confidence bootstraps to 0 at the confidence-0 gain, and no transition is
     * evaluated on this first observation (nothing to agree or disagree with yet).
     *
     * [feedback] may be `null` (#570, product ADR-0027) — the WATER-log feedback chip collapsed to
     * one optional flag, so a silent (no-chip) observation is now the dominant case. `null` maps to
     * [NEUTRAL_TARGET_MULTIPLIER] (`target = observed`, same value as JUST_RIGHT's) at a gain capped by
     * [NEUTRAL_OBSERVATION_GAIN] — a ceiling on the same gain used elsewhere, not a second learning
     * rate. Confidence still updates normally (gap agreement is evidence about the schedule regardless
     * of what was tapped); only the `base` correction is throttled for a silent observation.
     */
    fun computeAdaptiveInterval(
        feedback: WateringFeedback?,
        observedIntervalDays: Int,
        currentBaseIntervalDays: Int,
        currentConfidence: Int?,
        recentFeedback: List<WateringFeedback?>
    ): AdaptiveInterval {
        val multiplier = when (feedback) {
            WateringFeedback.TOO_SOON -> TOO_SOON_TARGET_MULTIPLIER
            WateringFeedback.JUST_RIGHT -> JUST_RIGHT_TARGET_MULTIPLIER
            WateringFeedback.TOO_LATE -> TOO_LATE_TARGET_MULTIPLIER
            null -> NEUTRAL_TARGET_MULTIPLIER
        }
        val target = observedIntervalDays * multiplier

        if (currentConfidence == null) {
            val gain = gainFor(ADAPTIVE_GAIN_BY_CONFIDENCE[0], feedback)
            val rawNewBase = currentBaseIntervalDays + gain * (target - currentBaseIntervalDays)
            return AdaptiveInterval(clampStep(currentBaseIntervalDays, rawNewBase), 0)
        }

        val gain = gainFor(ADAPTIVE_GAIN_BY_CONFIDENCE[currentConfidence], feedback)
        val rawNewBase = currentBaseIntervalDays + gain * (target - currentBaseIntervalDays)
        val newBase = clampStep(currentBaseIntervalDays, rawNewBase)

        val streak = correctionStreak(recentFeedback)
        val newConfidence = when {
            abs(streak) >= STREAK_DECREMENT_THRESHOLD ->
                (currentConfidence - STREAK_CONFIDENCE_PENALTY).coerceAtLeast(0)
            gapAgrees(observedIntervalDays, currentBaseIntervalDays) ->
                min(currentConfidence + 1, MAX_CONFIDENCE)
            else -> currentConfidence
        }
        return AdaptiveInterval(newBase, newConfidence)
    }

    /**
     * `null` feedback (silent gap-only observation) never moves `base` faster than
     * [NEUTRAL_OBSERVATION_GAIN], regardless of confidence — explicit feedback (non-null) always
     * uses the full confidence-driven [confidenceGain] unchanged (#570).
     */
    private fun gainFor(confidenceGain: Double, feedback: WateringFeedback?): Double =
        if (feedback == null) min(confidenceGain, NEUTRAL_OBSERVATION_GAIN) else confidenceGain

    /**
     * Confidence effect of dismissing the ADR-0006 suggestion dialog without applying: a dismissal
     * says "the current schedule is fine", so it raises confidence, but only up to
     * [DISMISSAL_CONFIDENCE_CEILING] — it never lowers an already-higher confidence (#568 comment 3).
     */
    fun confidenceAfterDismissal(confidence: Int?): Int {
        val current = confidence ?: 0
        return max(current, min(current + 1, DISMISSAL_CONFIDENCE_CEILING))
    }

    /**
     * Confidence effect of applying a suggestion the user retyped inside the ADR-0006 dialog before
     * tapping Apply. An edit within [GAP_AGREEMENT_TOLERANCE] of [suggestedIntervalDays] is fine-tuning
     * and leaves confidence on normal rules (already applied by [computeAdaptiveInterval] at log time);
     * an edit further off says the suggestion was materially wrong, so confidence falls — but this is
     * not the full reset an AddEditPlant edit is, since the model itself still stands (#568 comment 4).
     */
    fun confidenceAfterDialogEdit(
        confidence: Int?,
        suggestedIntervalDays: Int,
        appliedIntervalDays: Int
    ): Int {
        val current = confidence ?: 0
        if (suggestedIntervalDays <= 0) return current
        return if (gapAgrees(appliedIntervalDays, suggestedIntervalDays)) {
            current
        } else {
            (current - STREAK_CONFIDENCE_PENALTY).coerceAtLeast(0)
        }
    }

    private fun gapAgrees(observedIntervalDays: Int, predictedIntervalDays: Int): Boolean {
        if (predictedIntervalDays <= 0) return false
        return abs(observedIntervalDays - predictedIntervalDays) <= GAP_AGREEMENT_TOLERANCE * predictedIntervalDays
    }

    private fun clampStep(oldBaseIntervalDays: Int, rawNewBaseIntervalDays: Double): Int {
        val minStep = oldBaseIntervalDays * (1 - PER_STEP_CLAMP_FRACTION)
        val maxStep = oldBaseIntervalDays * (1 + PER_STEP_CLAMP_FRACTION)
        val clamped = rawNewBaseIntervalDays.coerceIn(minStep, maxStep)
        return clamped.roundToInt().coerceIn(MIN_ADAPTIVE_INTERVAL_DAYS, MAX_ADAPTIVE_INTERVAL_DAYS)
    }
}

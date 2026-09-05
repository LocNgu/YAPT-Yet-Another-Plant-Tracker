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

        val wateringDue = computeWateringDue(plant, lastWateredAt, now, nowDate, seasonalAmplitude, hemisphere)
        val (nextDueAt, isOverdue, isDueSoon) = wateringDue.dueStatus
        val (nextFertilizingDueAt, isFertilizingOverdue, isFertilizingDueSoon) =
            computeFertilizingDue(plant, lastFertilizedAt, nowDate)
        val (nextRepottingDueAt, isRepottingOverdue, isRepottingDueSoon) =
            computeExtendedCareDue(plant.repottingIntervalDays, lastRepottedAt, plant.createdAt, nowDate)
        val customReminderStatuses = computeCustomReminderStatuses(customReminders, nowDate)
        val effectiveWateringDays = effectiveWateringIntervalDays(plant, nowDate, seasonalAmplitude, hemisphere)
        val onSchedule = wateringOnScheduleNow(
            lastWateredAt = lastWateredAt,
            effectiveIntervalDays = effectiveWateringDays,
            now = now
        )
        val gapRanLong = wateringGapRanLong(
            lastWateredAt = lastWateredAt,
            effectiveIntervalDays = effectiveWateringDays,
            now = now
        )

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
            customReminderStatuses = customReminderStatuses,
            isWateringOnSchedule = onSchedule,
            isWateringGapLong = gapRanLong,
            rescheduleDeltaDays = wateringDue.rescheduleDeltaDays
        )
    }

    /**
     * Backs [PlantCareStatus.isWateringOnSchedule] (#586, product ADR-0030): would a watering logged
     * **now** agree with the schedule, within the same [GAP_AGREEMENT_TOLERANCE] the adaptive model
     * already uses to decide "the prediction matched reality"? A second notion of "close enough"
     * would inevitably drift from the first, so there is deliberately no new constant.
     *
     * `true` — no reason prompt — whenever there is nothing to be off-schedule against: no watering
     * interval configured, or no previous watering to measure a gap from.
     *
     * This compares the raw observed gap against the *effective* (seasonally adjusted) interval,
     * while [computeAdaptiveInterval] compares the de-seasonalized gap against the base interval.
     * Those are the same test — `observed / season` vs `base` is `observed` vs `base × season` — so
     * the prompt appears exactly when the model is about to see a disagreeing gap, which is what lets
     * the "prompt was shown" state be derived rather than persisted.
     */
    private fun wateringOnScheduleNow(lastWateredAt: Long?, effectiveIntervalDays: Int?, now: Long): Boolean {
        if (lastWateredAt == null || effectiveIntervalDays == null) return true
        return gapAgrees(daysBetween(lastWateredAt, now), effectiveIntervalDays)
    }

    /**
     * Public wrapper around [wateringOnScheduleNow] for a **backdated** quick-water (#654): would a
     * watering logged on [chosenDate] (not necessarily "now") agree with the schedule, within
     * [GAP_AGREEMENT_TOLERANCE] — the exact same comparison [computeStatus] uses for
     * [PlantCareStatus.isWateringOnSchedule], just generalized to any candidate date so the Plant
     * Detail "Log watering" date picker's reason-prompt gate can evaluate the date the user actually
     * picked instead of always "now". Passing today's date reproduces [PlantCareStatus
     * .isWateringOnSchedule] exactly, since [daysBetween] is calendar-day granular.
     */
    fun isWateringOnScheduleAt(lastWateredAt: Long?, effectiveIntervalDays: Int?, chosenDate: Long): Boolean =
        wateringOnScheduleNow(lastWateredAt, effectiveIntervalDays, chosenDate)

    /**
     * Backs [PlantCareStatus.isWateringGapLong] (#586): which *side* of the schedule an off-schedule
     * watering falls on, so the reason prompt can ask "why was it late?" rather than "why now?" when
     * the gap ran long. Deliberately derived from the same gap-vs-effective-interval comparison as
     * [wateringOnScheduleNow] rather than from [PlantCareStatus.isOverdue]: `isOverdue` is measured
     * against the *due date*, which an active `wateringDueDateOverride` moves, so the two can
     * disagree — a deferred plant is not overdue while its gap since the last watering may still have
     * run long. The prompt's wording must match the test that decided to show the prompt at all.
     */
    private fun wateringGapRanLong(lastWateredAt: Long?, effectiveIntervalDays: Int?, now: Long): Boolean {
        if (lastWateredAt == null || effectiveIntervalDays == null) return false
        return daysBetween(lastWateredAt, now) > effectiveIntervalDays
    }

    /**
     * Public wrapper around [wateringGapRanLong] for a backdated quick-water (#654) — [isWateringOnScheduleAt]'s
     * counterpart, so the "Log watering" date picker's off-schedule sheet can pick the correct
     * early/late wording for the date the user actually chose rather than "now".
     */
    fun isWateringGapLongAt(lastWateredAt: Long?, effectiveIntervalDays: Int?, chosenDate: Long): Boolean =
        wateringGapRanLong(lastWateredAt, effectiveIntervalDays, chosenDate)

    /** [computeWateringDue]'s result: the usual [DueStatus] plus the #630 reschedule delta. */
    private data class WateringDueStatus(val dueStatus: DueStatus, val rescheduleDeltaDays: Int?)

    @Suppress("LongParameterList")
    private fun computeWateringDue(
        plant: Plant,
        lastWateredAt: Long?,
        now: Long,
        nowDate: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): WateringDueStatus {
        val effectiveIntervalDays = effectiveWateringIntervalDays(plant, nowDate, seasonalAmplitude, hemisphere)
        val computedNextDueAt = if (effectiveIntervalDays == null) {
            null
        } else if (lastWateredAt != null) {
            lastWateredAt + TimeUnit.DAYS.toMillis(effectiveIntervalDays.toLong())
        } else {
            now
        }

        val override = plant.wateringDueDateOverride
        val nextDueAt = when {
            computedNextDueAt == null -> override
            override == null -> computedNextDueAt
            else -> maxOf(computedNextDueAt, override)
        }

        // #630: non-null only when the override is the actual maxOf() winner, so a stale override the
        // schedule has since caught up with (and exceeded) reports no delta — nothing to explain or revert.
        val rescheduleDeltaDays = if (computedNextDueAt != null && override != null && override > computedNextDueAt) {
            daysBetween(computedNextDueAt, override)
        } else {
            null
        }

        return WateringDueStatus(dueStatusFor(nextDueAt, nowDate), rescheduleDeltaDays)
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

    /**
     * Result of one adaptive-watering observation: the new suggested base interval and confidence.
     * [excludedFromBaseLearning] is true when #586's rule left [intervalDays] deliberately untouched
     * (an off-schedule watering the user declined to attribute to the plant) — callers use it to
     * label the `watering_adjustments` row so the "Why this date?" sheet can say *why* nothing moved.
     */
    data class AdaptiveInterval(
        val intervalDays: Int,
        val confidence: Int,
        val excludedFromBaseLearning: Boolean = false
    )

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
     *
     * **#586 (product ADR-0030) narrows that further.** A `null`-feedback observation whose gap
     * *disagrees* with [currentBaseIntervalDays] is excluded from base learning entirely (gain 0,
     * [AdaptiveInterval.excludedFromBaseLearning] set). Off-schedule is exactly when the reason prompt
     * appears, so a `null` there means the user was asked why and declined to attribute it to the
     * plant — a pre-emptive holiday watering at day 5 of a 7-day plant must not quietly *shorten* the
     * interval through the passive channel after the user has explicitly said it was about them. The
     * distinction between "prompt never appeared" and "prompt appeared and was declined" is derived
     * here from timing (see [wateringOnScheduleNow]), so no fifth state is ever persisted in the
     * four-state [WateringFeedback] column.
     *
     * [frozen] (#571) is the REPOT-triggered 4-week freeze window: `true` when the observation falls
     * before [Plant.wateringFreezeUntil] elapses, forcing the same exclusion treatment #586 already
     * gives an unattributed off-schedule observation (gain 0, [AdaptiveInterval.excludedFromBaseLearning]
     * set) — reusing that mechanism rather than inventing a second one. Confidence is **not** separately
     * suppressed while frozen, for the same reason ADR-0030 gives: it is evidence about the schedule
     * regardless of why an observation is excluded from `base`. Defaults `false` so every existing call
     * site/test is unaffected.
     */
    @Suppress("LongParameterList")
    fun computeAdaptiveInterval(
        feedback: WateringFeedback?,
        observedIntervalDays: Int,
        currentBaseIntervalDays: Int,
        currentConfidence: Int?,
        recentFeedback: List<WateringFeedback?>,
        frozen: Boolean = false
    ): AdaptiveInterval {
        val multiplier = when (feedback) {
            WateringFeedback.TOO_SOON -> TOO_SOON_TARGET_MULTIPLIER
            WateringFeedback.JUST_RIGHT -> JUST_RIGHT_TARGET_MULTIPLIER
            WateringFeedback.TOO_LATE -> TOO_LATE_TARGET_MULTIPLIER
            null -> NEUTRAL_TARGET_MULTIPLIER
        }
        val target = observedIntervalDays * multiplier
        val excluded = frozen ||
            isUnattributedOffScheduleObservation(feedback, observedIntervalDays, currentBaseIntervalDays)

        if (currentConfidence == null) {
            val gain = gainFor(ADAPTIVE_GAIN_BY_CONFIDENCE[0], feedback, excluded)
            val rawNewBase = currentBaseIntervalDays + gain * (target - currentBaseIntervalDays)
            return AdaptiveInterval(clampStep(currentBaseIntervalDays, rawNewBase), 0, excluded)
        }

        val gain = gainFor(ADAPTIVE_GAIN_BY_CONFIDENCE[currentConfidence], feedback, excluded)
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
        return AdaptiveInterval(newBase, newConfidence, excluded)
    }

    /**
     * The #586 rule, stated once: no feedback **and** a gap that disagrees with the current base means
     * the reason prompt was shown and the user declined to attribute the watering to the plant. It is
     * deliberately derived rather than passed in — a boolean threaded through every call site is one
     * a caller can forget, and this way the rule holds identically for the quick-log sheets, the
     * AddCareLog form, a bulk log, and the notification's "Watered" action.
     */
    private fun isUnattributedOffScheduleObservation(
        feedback: WateringFeedback?,
        observedIntervalDays: Int,
        currentBaseIntervalDays: Int
    ): Boolean = feedback == null && !gapAgrees(observedIntervalDays, currentBaseIntervalDays)

    /**
     * `null` feedback (silent gap-only observation) never moves `base` faster than
     * [NEUTRAL_OBSERVATION_GAIN], regardless of confidence — explicit feedback (non-null) always
     * uses the full confidence-driven [confidenceGain] unchanged (#570) — and an [excluded]
     * observation does not move it at all (#586).
     */
    private fun gainFor(confidenceGain: Double, feedback: WateringFeedback?, excluded: Boolean): Double = when {
        excluded -> 0.0
        feedback == null -> min(confidenceGain, NEUTRAL_OBSERVATION_GAIN)
        else -> confidenceGain
    }

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

    // --- Cold-start bootstrap from watering history (#571 Part B) ---

    /** [bootstrapBaseInterval] needs at least this many gaps (4 waterings) before a caller may apply it. */
    const val MIN_BOOTSTRAP_GAPS = 3

    private const val BOOTSTRAP_GAPS_PER_CONFIDENCE_POINT = 3

    /**
     * Result of [bootstrapBaseInterval]: always computed when there is at least one gap (pure,
     * always testable), regardless of whether [gapCount] clears [MIN_BOOTSTRAP_GAPS] — it is the
     * caller's job to gate applying it on that threshold (#571 spec: below 3 gaps, initial-enable
     * keeps the user's typed interval, and a post-reset plant just stays at confidence 0).
     */
    data class BootstrapResult(val baseIntervalDays: Double, val confidence: Int, val gapCount: Int)

    /**
     * Cold-starts a per-plant base interval from the user's own watering history (#571 Part B, salvaged
     * from #285's approach 2): `base = median over past waterings of (gap_i / season(date_i))`,
     * `confidence = min(5, count / 3)`. Dividing each historical gap by its own season factor
     * de-seasonalizes it, so waterings from any month contribute to the same estimate — the whole
     * history is usable, not one twelfth of it (#569's per-month bootstrap alternative, rejected).
     * Median (not mean) absorbs a single outlier gap (e.g. a holiday) without extra trimming logic.
     *
     * [waterLogTimestampsMs] may be in any order and represents one plant's WATER log timestamps (a
     * caller evaluating the post-reset opportunity pre-filters to timestamps at/after the freeze
     * boundary — #571 spec: "a gap that isn't trusted for live per-observation learning isn't trusted
     * for the one-time cold-start estimate either"). [seasonFn] is `{ 1.0 }` when `SEASONAL_WATERING`
     * is off or the plant is pinned, matching every other de-seasonalization call site in this file.
     *
     * Returns `null` when there are fewer than 2 timestamps (zero gaps — "no estimate", per the spec's
     * empty-history edge case). A single gap (2 timestamps) still returns a real, testable result with
     * [BootstrapResult.gapCount] == 1; it is the caller's job not to apply it below [MIN_BOOTSTRAP_GAPS].
     */
    fun bootstrapBaseInterval(
        waterLogTimestampsMs: List<Long>,
        seasonFn: (LocalDate) -> Double
    ): BootstrapResult? {
        val sorted = waterLogTimestampsMs.sorted()
        if (sorted.size < 2) return null

        val deseasonalizedGaps = sorted.zipWithNext { earlier, later ->
            val gapDays = daysBetween(earlier, later)
            gapDays / seasonFn(later.toLocalDate())
        }
        val gapCount = deseasonalizedGaps.size
        val baseIntervalDays = median(deseasonalizedGaps)
            .coerceIn(MIN_ADAPTIVE_INTERVAL_DAYS.toDouble(), MAX_ADAPTIVE_INTERVAL_DAYS.toDouble())
        val confidence = (gapCount / BOOTSTRAP_GAPS_PER_CONFIDENCE_POINT).coerceAtMost(MAX_CONFIDENCE)
        return BootstrapResult(baseIntervalDays, confidence, gapCount)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun clampStep(oldBaseIntervalDays: Int, rawNewBaseIntervalDays: Double): Int {
        val minStep = oldBaseIntervalDays * (1 - PER_STEP_CLAMP_FRACTION)
        val maxStep = oldBaseIntervalDays * (1 + PER_STEP_CLAMP_FRACTION)
        val clamped = rawNewBaseIntervalDays.coerceIn(minStep, maxStep)
        return clamped.roundToInt().coerceIn(MIN_ADAPTIVE_INTERVAL_DAYS, MAX_ADAPTIVE_INTERVAL_DAYS)
    }
}

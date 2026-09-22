package com.yapt.planttracker.domain.model

/**
 * A record of one adaptive-watering-model evaluation for a plant (#572) — written every time
 * [com.yapt.planttracker.domain.schedule.CareSchedule.computeAdaptiveInterval] is evaluated,
 * including a no-op observation where [beforeIntervalDays] equals
 * [afterIntervalDays] (still evidence the model considered). Backs the "Recent adjustments" list on
 * the "Why this date?" sheet.
 *
 * A dedicated table rather than a `CareLog` replay (product ADR-0028): a dialog dismissal, an
 * inline/AddEditPlant manual edit, and a silently-applied suggestion all change
 * [com.yapt.planttracker.domain.model.Plant.wateringConfidence]/base without ever writing a
 * `CareLog` row, so a pure replay would misrepresent history.
 */
data class WateringAdjustment(
    val id: Long = 0,
    val plantId: Long,
    val triggeredAt: Long = System.currentTimeMillis(),
    val trigger: WateringAdjustmentTrigger,
    val beforeIntervalDays: Int,
    val afterIntervalDays: Int
)

/** What caused a [WateringAdjustment] row to be written. See [WateringAdjustment]'s doc for context. */
enum class WateringAdjustmentTrigger {
    WATER_TOO_SOON,
    WATER_TOO_LATE,
    WATER_JUST_RIGHT,
    WATER_NEUTRAL,

    /**
     * An off-schedule watering the user was asked about and declined to attribute to the plant
     * ("Just my timing", or logging without choosing a reason) — #586, product ADR-0030. Distinct
     * from [WATER_NEUTRAL] (an on-schedule watering, never prompted) so "Recent adjustments" can say
     * the model deliberately ignored an observation rather than merely finding nothing to change.
     */
    WATER_NOT_ATTRIBUTED,

    /**
     * A "Soil still moist" reschedule observation. **Retained for historical data only (#738,
     * product ADR-0039): no longer written** — a reschedule is model-neutral again and writes only
     * `Plant.wateringDueDateOverride`. Existing rows stay visible, unfiltered, in "Why this date?" →
     * Recent adjustments, which is model provenance rather than a user journal.
     */
    CHECK_STILL_MOIST,
    DIALOG_DISMISSAL,
    DIALOG_EDIT,
    MANUAL_EDIT,

    /**
     * The Snackbar "Undo" action on a silently-applied suggestion (#584 review) —
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.undoSilentIntervalApply],
     * deliberately distinct from [DIALOG_EDIT] so "Recent adjustments" never shows a reverted apply
     * as if it still stood.
     */
    SILENT_APPLY_UNDONE,

    /**
     * A `REPOT` care log resetting `wateringConfidence` to 0 and starting the 4-week freeze window
     * (#571) — [com.yapt.planttracker.domain.usecase.WateringLifecycleReset.applyRepotReset].
     * `beforeIntervalDays`/`afterIntervalDays` are always equal: a repot resets confidence, not the
     * interval itself.
     */
    REPOT_RESET,

    /**
     * A [Plant.room] change resetting `wateringConfidence` to 0 with no freeze window (#571) —
     * [com.yapt.planttracker.ui.screens.addplant.AddEditPlantViewModel]'s room-diff check in
     * `saveEdit()`. `beforeIntervalDays`/`afterIntervalDays` are always equal, same reasoning as
     * [REPOT_RESET].
     */
    ROOM_CHANGE_RESET,

    /**
     * A WATER/CHECK observation excluded from base-learning because it fell inside a REPOT-triggered
     * freeze window (#571) — distinct from [WATER_NOT_ATTRIBUTED] (a *declined* attribution) so
     * "Recent adjustments" doesn't misrepresent an automatic freeze as the user having been asked and
     * saying no.
     */
    FROZEN_POST_REPOT,

    /**
     * The one-time cold-start bootstrap from watering history (#571 Part B) —
     * [com.yapt.planttracker.domain.usecase.WateringLifecycleReset.maybeBootstrap], reached either when
     * the adaptive model is first evaluated for a plant with enough existing history, or once enough
     * post-reset history accumulates after a [REPOT_RESET]/[ROOM_CHANGE_RESET].
     */
    HISTORY_BOOTSTRAP,

    /**
     * The one-time app-start backfill correcting the fallout of graduating `SEASONAL_WATERING` out of
     * developer mode (#702) — [com.yapt.planttracker.domain.usecase.SeasonalGraduationFixup]. Before
     * graduation (#656), `seasonalAmplitudeFlow()` always returned `0.0` while the dev-mode flag was
     * off (the default for every install), so every write path that dual-writes
     * [Plant.wateringBaseIntervalDays] alongside a [Plant.wateringIntervalDays] edit or suggestion-apply
     * silently skipped its `amplitude != 0.0` gate — leaving `wateringBaseIntervalDays` frozen at
     * whatever `MIGRATION_10_11` (#569) set it to while the visible literal interval kept moving on
     * every subsequent edit. This fixup re-anchors `wateringBaseIntervalDays` to today so the effective
     * interval immediately after it runs equals the plant's current (correct) literal interval, exactly
     * once, distinct from [HISTORY_BOOTSTRAP] (which reconstructs a base from watering-log history, not
     * from reconciling a stale column against the literal interval).
     */
    SEASONAL_GRADUATION_FIXUP,

    /**
     * A watering whose gap overlapped the plant's dormancy window (#699/#761, product ADR-0044) —
     * excluded from base learning regardless of feedback, the same `frozen` exclusion mechanism
     * [FROZEN_POST_REPOT] uses, but deliberately a **distinct** trigger: this is a user-declared
     * dormancy window, not an automatic post-repot freeze, and the sheet should not conflate the two.
     * `beforeIntervalDays`/`afterIntervalDays` are always equal — dormancy excludes the base from
     * moving, it never moves it.
     */
    DORMANCY_EXCLUDED,

    /**
     * Leaving a dormancy window: `wateringConfidence = max(0, confidence - 1)` (#699/#761, product
     * ADR-0044) — never a full reset, since the pre-dormancy base is still the best available estimate
     * for the same plant in the same position. Written alongside [DORMANCY_EXCLUDED] on the same
     * watering when that watering both spans the window and is the first one after it, since the two
     * are independent facts about that one observation (the base didn't move; confidence *did*, for a
     * different reason than the exclusion itself). `beforeIntervalDays`/`afterIntervalDays` are always
     * equal, same reasoning as [DORMANCY_EXCLUDED] — this is a confidence-only event.
     * When the same watering fires a history bootstrap (#779), the bootstrap's fresh confidence
     * supersedes this incremental decrement: [HISTORY_BOOTSTRAP] and [DORMANCY_EXCLUDED] record
     * that observation instead, with no [DORMANCY_EXIT] row.
     */
    DORMANCY_EXIT
}

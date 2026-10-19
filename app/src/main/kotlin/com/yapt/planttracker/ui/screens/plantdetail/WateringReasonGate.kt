package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.toLocalDate

/**
 * `requestWater`/`requestLiquidFertilize`'s on/off-schedule + dormancy gate, split out of
 * `PlantDetailScreen.kt` to stay under Detekt's per-file `TooManyFunctions` threshold — same
 * reasoning as `RescheduleDateSelection.kt`'s split from `WateringDueActions.kt`
 * (`.claude/rules/plant-detail.md`). `internal` visibility throughout (not `private`) so these can
 * still be called from `PlantDetailScreen.kt` in the same package and exercised directly by
 * `PlantDetailScreenGateTest` (a plain JVM test, #679 review round 1 precedent).
 */

/**
 * Bundles [requestWater]/[requestLiquidFertilize]'s per-plant inputs (#654) to stay under Detekt's
 * `LongParameterList` threshold — mirrors `CustomReminderActions`'s existing bundling precedent
 * (`.claude/rules/plant-detail.md`).
 */
internal data class QuickWaterGateContext(
    val plant: Plant,
    val seasonalAmplitude: Double,
    val viewModel: PlantDetailViewModel,
    val awayPeriods: List<AwayPeriod>
) {
    val inputs: WateringGateInputs get() = WateringGateInputs(plant, seasonalAmplitude, awayPeriods)
}

/**
 * The chosen [LogWateringDatePickerDialog] date plus the watering it actually follows (#679) —
 * [PlantDetailViewModel.previousWateringBefore], fetched once in the `onConfirm` callback and reused
 * for both the on/off-schedule gate ([requestWater]/[requestLiquidFertilize]) and the
 * [WateringReasonBottomSheet]'s own gap-length wording ([isChosenDateGapLong]) — so the two can't
 * disagree about which prior watering [loggedAt] is being compared against.
 */
internal data class PendingReasonPrompt(val loggedAt: Long, val previousWateringAt: Long?)

/**
 * The pure inputs of the reason-prompt gate, shared by Plant Detail ([QuickWaterGateContext]) and the
 * Care tiles' "Log watering" picker (#838) so both surfaces judge a picked date by the same rules.
 */
internal data class WateringGateInputs(
    val plant: Plant,
    val seasonalAmplitude: Double,
    val awayPeriods: List<AwayPeriod>
)

internal sealed interface WateringGateResult {
    data class Log(val reason: WateringReason?) : WateringGateResult
    data object AskReason : WateringGateResult
}

/**
 * Whether the "Soil was already too dry" option is offered for [chosenDate] (#838, product ADR-0067):
 * on schedule or early, never late (product ADR-0033 — a late gap never shortens the interval) and
 * never when the gap spans dormancy or an Away trip (the observation is excluded anyway). The option
 * follows the picked date live, so this is re-evaluated every time the date changes.
 */
internal fun isSoilTooDryOptionVisible(
    inputs: WateringGateInputs,
    previousWateringAt: Long?,
    chosenDate: Long
): Boolean = !isChosenDateGapLong(inputs.plant, previousWateringAt, chosenDate, inputs.seasonalAmplitude) &&
    !isChosenDateDormancySpanning(inputs.plant, previousWateringAt, chosenDate) &&
    !isChosenDateAwaySpanning(inputs.awayPeriods, previousWateringAt, chosenDate)

/**
 * The #586 fast path (product ADR-0030) plus #838's optional "Soil was already too dry" tick (product
 * ADR-0067): a ticked option that is visible for [loggedAt] logs [WateringReason.PLANT_NEEDED_IT]
 * straight away (skipping "Why now?" when early); an unticked on-schedule, dormancy- or away-spanning
 * watering logs with no reason; everything else asks. A tick on a date where the option is hidden is
 * ignored, so moving the date after ticking can never apply a stale answer.
 */
internal fun evaluateWateringGate(
    inputs: WateringGateInputs,
    loggedAt: Long,
    previousWateringAt: Long?,
    soilTooDry: Boolean
): WateringGateResult = when {
    soilTooDry && isSoilTooDryOptionVisible(inputs, previousWateringAt, loggedAt) ->
        WateringGateResult.Log(WateringReason.PLANT_NEEDED_IT)
    isChosenDateOnSchedule(inputs.plant, previousWateringAt, loggedAt, inputs.seasonalAmplitude) ||
        isChosenDateDormancySpanning(inputs.plant, previousWateringAt, loggedAt) ||
        isChosenDateAwaySpanning(inputs.awayPeriods, previousWateringAt, loggedAt) ->
        WateringGateResult.Log(null)
    else -> WateringGateResult.AskReason
}

/**
 * The #586 fast path (product ADR-0030): a watering on-schedule *for [loggedAt]* is logged straight
 * away — no sheet, no question — and only an off-schedule one opens [WateringReasonBottomSheet] via
 * [showReasonSheet]. Shared by every quick-water surface on this screen so they can never disagree
 * about when the question is worth asking. [soilTooDry] is the picker's optional tick (#838), see
 * [evaluateWateringGate].
 *
 * [loggedAt] is the date the user picked in [LogWateringDatePickerDialog] (#654) — not necessarily
 * "now" — so the on-schedule gate is re-evaluated against it via [isChosenDateOnSchedule] rather than
 * reusing [PlantCareStatus.isWateringOnSchedule], which is always computed against real wall-clock
 * "now". [previousWateringAt] is [loggedAt]'s own chronological predecessor
 * ([PlantDetailViewModel.previousWateringBefore], #679) rather than the plant's globally newest
 * watering — the two disagree once [loggedAt] backdates before an already-existing later watering.
 * Picking today reproduces the exact same result `status.isWateringOnSchedule` would have given,
 * since [CareSchedule.daysBetween] is calendar-day granular.
 *
 * [isChosenDateDormancySpanning] is a second, independent reason to skip the prompt (#699/#761,
 * product ADR-0044) — a gap that overlaps the plant's dormancy window asks an incoherent question of
 * a plant that was asleep. [isChosenDateAwaySpanning] is a third (Away mode, #897, product ADR-0065):
 * a gap that overlaps a trip is excluded from learning, including for a backdated picked date.
 */
internal fun requestWater(
    context: QuickWaterGateContext,
    loggedAt: Long,
    previousWateringAt: Long?,
    soilTooDry: Boolean = false,
    showReasonSheet: () -> Unit
) {
    when (val result = evaluateWateringGate(context.inputs, loggedAt, previousWateringAt, soilTooDry)) {
        is WateringGateResult.Log -> context.viewModel.quickWater(result.reason, loggedAt)
        WateringGateResult.AskReason -> showReasonSheet()
    }
}

/** [requestWater]'s counterpart for a liquid-fertilizer plant, whose paired WATER log follows the same rule. */
internal fun requestLiquidFertilize(
    context: QuickWaterGateContext,
    loggedAt: Long,
    previousWateringAt: Long?,
    soilTooDry: Boolean = false,
    showReasonSheet: () -> Unit
) {
    when (val result = evaluateWateringGate(context.inputs, loggedAt, previousWateringAt, soilTooDry)) {
        is WateringGateResult.Log -> context.viewModel.quickLiquidFertilize(result.reason, loggedAt)
        WateringGateResult.AskReason -> showReasonSheet()
    }
}

/**
 * Re-evaluates [PlantCareStatus.isWateringOnSchedule]'s exact gap-vs-effective-interval comparison
 * against [chosenDate] instead of "now" (#654) — reuses [CareSchedule.effectiveWateringIntervalDaysForDisplay]
 * (the same wrapper the "Why this date?" sheet and every other quick-log surface already share) and
 * [CareSchedule.isWateringOnScheduleAt], so there is no second notion of "close enough"
 * (`CareSchedule.GAP_AGREEMENT_TOLERANCE` stays the only tolerance constant).
 *
 * `internal` rather than `private` (#679 review round 1) so `PlantDetailScreenGateTest` (JVM unit
 * test) can exercise the exact [lastWateredAt]-vs-real-predecessor scenario the bug fixed — an
 * instrumented test would need to drive Material3's `DatePicker` day grid to a specific backdated
 * day, which has no existing precedent in this suite and is fragile across the run date's position
 * within its calendar month.
 */
internal fun isChosenDateOnSchedule(
    plant: Plant,
    lastWateredAt: Long?,
    chosenDate: Long,
    seasonalAmplitude: Double
): Boolean {
    val effectiveIntervalDays = CareSchedule.effectiveWateringIntervalDaysForDisplay(
        plant = plant,
        nowDate = chosenDate.toLocalDate(),
        seasonalAmplitude = seasonalAmplitude,
        hemisphere = SeasonalWatering.currentHemisphere()
    )
    return CareSchedule.isWateringOnScheduleAt(lastWateredAt, effectiveIntervalDays, chosenDate)
}

/** [isChosenDateOnSchedule]'s counterpart for [PlantCareStatus.isWateringGapLong] (#654). Also `internal` for the same reason. */
internal fun isChosenDateGapLong(
    plant: Plant,
    lastWateredAt: Long?,
    chosenDate: Long,
    seasonalAmplitude: Double
): Boolean {
    val effectiveIntervalDays = CareSchedule.effectiveWateringIntervalDaysForDisplay(
        plant = plant,
        nowDate = chosenDate.toLocalDate(),
        seasonalAmplitude = seasonalAmplitude,
        hemisphere = SeasonalWatering.currentHemisphere()
    )
    return CareSchedule.isWateringGapLongAt(lastWateredAt, effectiveIntervalDays, chosenDate)
}

/**
 * Whether the gap [lastWateredAt]→[chosenDate] overlaps [plant]'s dormancy window (#699/#761, product
 * ADR-0044) — [requestWater]/[requestLiquidFertilize]'s second, independent reason to skip
 * [WateringReasonBottomSheet] alongside [isChosenDateOnSchedule]: the question is incoherent for a
 * plant that was asleep, and the answer is already known. `false` with no predecessor at all, matching
 * [isChosenDateOnSchedule]'s own "nothing to gate against" convention. `internal`, not `private`, for
 * the same [PlantDetailScreenGateTest] testability reason as its two siblings above.
 */
internal fun isChosenDateDormancySpanning(plant: Plant, lastWateredAt: Long?, chosenDate: Long): Boolean =
    lastWateredAt != null &&
        DormancyWindow.spansDormancy(plant.dormancyStartMonth, plant.dormancyEndMonth, lastWateredAt, chosenDate)

/**
 * Whether the gap [lastWateredAt]→[chosenDate] overlaps an away trip (Away mode, #897, product
 * ADR-0065) — the third independent reason to skip [WateringReasonBottomSheet], and the only one that
 * reads [AwayPeriods.spansAway], the same predicate the observation itself uses, so the gate and the
 * learning exclusion can't disagree. `false` with no predecessor, matching its siblings.
 */
internal fun isChosenDateAwaySpanning(
    awayPeriods: List<AwayPeriod>,
    lastWateredAt: Long?,
    chosenDate: Long
): Boolean =
    lastWateredAt != null && AwayPeriods.spansAway(awayPeriods, lastWateredAt, chosenDate)

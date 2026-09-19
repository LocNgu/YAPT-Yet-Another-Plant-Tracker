package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R

/**
 * Disambiguates the watering-due row's "Water" button from the `plant_detail_tab_water` tab strip
 * label (same string, both clickable) in Compose UI tests (#508 review fix) — text alone can't tell
 * them apart via `onNodeWithText`.
 */
internal const val WATERING_DUE_WATER_BUTTON_TEST_TAG = "watering_due_water_button"

/**
 * Disambiguates [FertilizeDueActionRow]'s "Fertilize" button from the `plant_detail_tab_fertilize`
 * tab strip label (same string, both clickable) in Compose UI tests — same rationale as
 * [WATERING_DUE_WATER_BUTTON_TEST_TAG] (#508 review fix).
 */
internal const val FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG = "fertilize_due_action_button"

/**
 * Locates [CombinedWaterFertilizeActionRow]'s button in Compose UI tests, distinct from
 * [WATERING_DUE_WATER_BUTTON_TEST_TAG] — the two are visible side-by-side (stacked) on the Water tab
 * for a liquid-fertilizer plant (#652 round 2), so a shared tag or text-only query could not
 * distinguish them.
 */
internal const val WATERING_DUE_COMBINED_WATER_FERTILIZE_BUTTON_TEST_TAG =
    "watering_due_combined_water_fertilize_button"

/**
 * Locates [RescheduleDeltaChip] in Compose UI tests. Its label text is not unique on its own — the
 * "Why this date?" sheet mirrors the same "Rescheduled +N days" string in a display-only row while
 * both remain in the semantics tree together, so a text-only query can match both at once.
 */
internal const val RESCHEDULE_DELTA_CHIP_TEST_TAG = "reschedule_delta_chip"

/**
 * "Rescheduled +N days" chip (#630), rendered directly above [WateringDueActionsRow] in both the
 * classic layout and the Water tab, whenever [com.yapt.planttracker.domain.model.PlantCareStatus
 * .rescheduleDeltaDays] is non-null — i.e. [com.yapt.planttracker.domain.model.Plant
 * .wateringDueDateOverride] is the actual `maxOf()` winner over the schedule-computed due date.
 * Tapping the chip reverts the reschedule immediately (no confirmation dialog, snackbar-undo instead —
 * product spec decision on #630). A trailing close icon (decorative — the chip is one clickable unit,
 * not a separately-tappable target) makes the revert affordance visually obvious rather than relying
 * on the chip's clickability alone (review follow-up). The icon's own `contentDescription` is `null`,
 * matching this file's convention for decorative icons inside an already-labeled clickable unit (e.g.
 * [WateringDueActionsRow]'s Water icon) — `AssistChip` merges descendant semantics into one TalkBack
 * announcement, so a spoken description here would just tack a redundant phrase onto the chip's own
 * label (round-2 review follow-up).
 */
@Composable
internal fun RescheduleDeltaChip(
    deltaDays: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(pluralStringResource(R.plurals.watering_reschedule_delta_days, deltaDays, deltaDays))
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        modifier = modifier.testTag(RESCHEDULE_DELTA_CHIP_TEST_TAG)
    )
}

/**
 * The two watering-due actions row (#586, product ADR-0030, narrowing #508/ADR-0029's three):
 * **Water** and **Reschedule watering**, in both the classic layout and the Water tab — see
 * `.claude/rules/plant-detail.md`. "Did water go in, or not?" is a fact, not a judgement, so the user
 * never has to work out *why* they are deferring in order to pick a button; the reason is asked
 * afterwards, and only when the action is off-schedule.
 *
 * "Still moist" is retired entirely (#738, product ADR-0039) — Reschedule asks no reason and
 * writes only `Plant.wateringDueDateOverride`.
 *
 * Water is a filled primary [Button] (water-drop icon + text, `colorScheme.primary` — resolving to
 * `SageGreen`/`SageGreenLight` in both themes, no hardcoded color); Reschedule is a secondary,
 * icon-only [OutlinedIconButton] (`Icons.Filled.MoreTime`, no visible text — its `contentDescription`
 * reuses [R.string.reschedule_watering_title]) so Water's [Modifier.weight] naturally takes the rest
 * of the row's width (#603 round-3 visual polish).
 *
 * Plain `16dp` horizontal padding, matching every other card on the screen (#610) — this row can
 * still scroll flush against a screen edge and land inside the pinned Back/Edit/FAB overlay buttons'
 * touch targets there (Box overlay, not Scaffold — technical ADR-0018), but that narrow collision risk
 * is now a deliberate, human-confirmed trade-off in exchange for visual consistency (technical
 * ADR-0022) rather than something this row's own margins should compensate for; ADR-0022 addresses
 * the Edit corner by fading that button on scroll instead.
 */
@Composable
internal fun WateringDueActionsRow(
    onWaterClick: () -> Unit,
    onRescheduleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onWaterClick,
            modifier = Modifier.weight(1f).testTag(WATERING_DUE_WATER_BUTTON_TEST_TAG)
        ) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.watering_due_action_water))
        }
        OutlinedIconButton(onClick = onRescheduleClick) {
            Icon(
                Icons.Filled.MoreTime,
                contentDescription = stringResource(R.string.reschedule_watering_title)
            )
        }
    }
}

/**
 * A second, visually distinct action for a liquid-fertilizer plant (#652 round 2), rendered directly
 * below [WateringDueActionsRow] on the Water tab whenever
 * [com.yapt.planttracker.domain.model.Plant.useLiquidFertilizer] is `true` — never on the Water tab of
 * a regular plant. [WateringDueActionsRow]'s own "Water" button is left unchanged (plain
 * `requestWater`, never branching on `useLiquidFertilizer`) so this is purely additive: a liquid-fert
 * plant owner gets *both* a plain Water action and this combined one, rather than the first commit's
 * silent same-looking-button behavior swap the human rejected. Wired to the same
 * `requestLiquidFertilize`/`showLiquidFertilizeSheet` path [FertilizeDueActionRow] already uses.
 *
 * `OutlinedButton` (not the filled primary style of [WateringDueActionsRow]'s Water button) so the two
 * are visually distinguishable at a glance, not just by label text.
 */
@Composable
internal fun CombinedWaterFertilizeActionRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.weight(1f).testTag(WATERING_DUE_COMBINED_WATER_FERTILIZE_BUTTON_TEST_TAG)
        ) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.water_fertilize_combined_button))
        }
    }
}

/**
 * Fertilize tab's single always-visible action button (#603), replacing the fertilizing `StatChip`'s
 * `onFertilizeClick` entry point once `StatsRow` is dropped from the tabs layout. No "reschedule"
 * counterpart — fertilizing has no equivalent concept, so this is one button, not a row of two.
 *
 * [useLiquidFertilizer] only changes the button's label (#652 round 2) — `onFertilizeClick`'s
 * behavior already branched on the same flag at the call site before this change, so relabeling here
 * just makes the button's text match what it already does: "Water + Fertilize" for a liquid-fertilizer
 * plant, plain "Fertilize" otherwise.
 *
 * Uses the same plain `16dp` horizontal padding as [WateringDueActionsRow] (#610) — see that
 * function's KDoc for the accepted residual collision trade-off with the pinned Back/FAB buttons.
 */
@Composable
internal fun FertilizeDueActionRow(
    useLiquidFertilizer: Boolean,
    onFertilizeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        OutlinedButton(
            onClick = onFertilizeClick,
            modifier = Modifier.weight(1f).testTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG)
        ) {
            Text(
                stringResource(
                    if (useLiquidFertilizer) {
                        R.string.water_fertilize_combined_button
                    } else {
                        R.string.bulk_action_fertilize
                    }
                )
            )
        }
    }
}

/**
 * Bundles [RescheduleWateringDialog]'s per-option callbacks so the composable itself stays under
 * Detekt's `LongParameterList` threshold. Each field keeps its own distinct anchoring semantics
 * explicit (due-date-relative vs. now-relative) rather than collapsing them behind one shared shape.
 */
internal data class RescheduleDialogActions(
    val onDismiss: () -> Unit,
    val onToday: () -> Unit,
    val onRelativeDays: (Int) -> Unit,
    val onCustomDate: (Long) -> Unit
)

/**
 * The "Reschedule watering" dialog (#508, product ADR-0029, replaces the 1-7 day stepper): Today /
 * +1 / +2 / +3 days / a Material 3 [DatePicker] for a custom date. [todayEnabled] (#746, via
 * `isRescheduleTodayEnabled`) is `false` when local today is on or before
 * `computedNextWateringDueAt`'s local calendar day (a true no-op — tapping Today couldn't move the
 * effective due date) and `true` whenever it would actually pull the date in, including a winning
 * *future* override the plant's `isOverdue` status doesn't reflect.
 * Every option writes `wateringDueDateOverride` only via [actions] — this dialog never fires the
 * ADR-0006 interval-suggestion dialog, unlike the flow it replaces.
 *
 * Opens directly from a Reschedule tap, with no reason prompt (#738, product ADR-0039 — a
 * reschedule is model-neutral, so there is nothing left to ask "why" about). **How many days the
 * user picks has no effect on the adaptive model** — all learning comes from the next actual
 * watering.
 *
 * [computedNextWateringDueAt] (#720) is [com.yapt.planttracker.domain.model.PlantCareStatus
 * .computedNextWateringDueAt] — the pre-override, schedule-computed due date — threaded down to the
 * "Custom date…" picker so it can reject any date `CareSchedule.computeWateringDue()`'s `maxOf()`
 * would silently discard. Today/+1/+2/+3 need no such gate: they anchor to `maxOf(nextWateringDueAt,
 * now)` and only ever add forward time, so they cannot produce an ineffective date by construction.
 */
@Composable
internal fun RescheduleWateringDialog(
    todayEnabled: Boolean,
    actions: RescheduleDialogActions,
    computedNextWateringDueAt: Long? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        RescheduleDatePickerDialog(
            computedNextWateringDueAt = computedNextWateringDueAt,
            onDismiss = actions.onDismiss,
            onConfirm = { utcMidnightMs ->
                showDatePicker = false
                utcMidnightMs?.let { actions.onCustomDate(utcMidnightMsToLocalStartOfDayMillis(it)) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.reschedule_watering_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                RescheduleOption(
                    label = stringResource(R.string.reschedule_watering_today),
                    onClick = actions.onToday,
                    enabled = todayEnabled
                )
                for (days in RELATIVE_DAY_OPTIONS) {
                    RescheduleOption(
                        label = pluralStringResource(R.plurals.reschedule_watering_plus_days, days, days),
                        onClick = { actions.onRelativeDays(days) }
                    )
                }
                RescheduleOption(
                    label = stringResource(R.string.reschedule_watering_custom_date),
                    onClick = { showDatePicker = true }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The fixed relative-deferral options offered between "Today" and "Custom date…". */
private val RELATIVE_DAY_OPTIONS = listOf(1, 2, 3)

/** One full-width row of [RescheduleWateringDialog]'s option list. */
@Composable
private fun RescheduleOption(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/**
 * The "Custom date…" branch of [RescheduleWateringDialog], extracted so the option list stays
 * readable. [TodayOrLaterSelectableDates] (via [isSelectableRescheduleDate]) already excludes both
 * past dates and dates the schedule's own `maxOf()` would discard (#720) **from the day grid itself**,
 * but a second check at confirm time is still required (#720 review round 1): Material3's
 * `rememberDatePickerState` re-validates its retained state's day grid against a fresh
 * `SelectableDates` instance on recomposition, but it does **not** clear an already-tapped
 * `selectedDateMillis` that a since-moved [computedNextWateringDueAt] would now reject — e.g. a
 * watering logged from another surface (a notification action) while this dialog sits open. Without
 * the [isRescheduleConfirmEnabled] gate below, OK would still forward that stale selection to
 * [onConfirm], reproducing the exact silent-no-op bug #720 exists to prevent. Do not delete this check
 * as apparently redundant with the grid's own `SelectableDates` — it is not. [onConfirm] receives the
 * raw `selectedDateMillis`, which is `null` until the user actually taps a day — OK stays enabled and
 * closes the picker either way when nothing has been tapped yet, and the caller does the
 * UTC-midnight reinterpretation documented on [utcMidnightMsToLocalStartOfDayMillis]. The
 * [SelectableDates] instance is `remember`ed keyed on [computedNextWateringDueAt] so
 * [rememberDatePickerState] is not handed a fresh instance on every recomposition.
 */
@Composable
private fun RescheduleDatePickerDialog(
    computedNextWateringDueAt: Long?,
    onDismiss: () -> Unit,
    onConfirm: (utcMidnightMs: Long?) -> Unit
) {
    val selectableDates = remember(computedNextWateringDueAt) {
        TodayOrLaterSelectableDates(computedNextWateringDueAt)
    }
    val datePickerState = rememberDatePickerState(selectableDates = selectableDates)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val selectedMillis = datePickerState.selectedDateMillis
            TextButton(
                onClick = { onConfirm(selectedMillis) },
                enabled = isRescheduleConfirmEnabled(selectedMillis, computedNextWateringDueAt)
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

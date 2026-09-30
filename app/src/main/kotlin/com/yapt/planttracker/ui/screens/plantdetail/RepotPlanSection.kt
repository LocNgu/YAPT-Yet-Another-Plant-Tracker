package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.RepotPlan
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import com.yapt.planttracker.domain.schedule.SeasonalRepotting
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.ui.util.repotPlanLabelRes
import com.yapt.planttracker.util.DateUtils
import java.time.LocalDate

/** Locates the Repot tab's season picker ([RepotPlanSeasonDialog], #809) in Compose UI tests. */
internal const val REPOT_PLAN_DIALOG_TEST_TAG = "repot_plan_dialog"

/**
 * Bundles [RepotPlanSummary]'s two plan actions so the composable stays under Detekt's
 * `LongParameterList` threshold.
 */
internal data class RepotPlanSummaryActions(val onEdit: () -> Unit, val onClear: () -> Unit)

/**
 * What the Repot tab says about *when* (#809, product ADR-0057), beneath the action row. With a plan:
 * "Planned: spring 2027" with Edit and Clear — the season and year are derived from the stored
 * timestamp via [SeasonalRepotting.resolvePlan], so a timezone or hemisphere change never moves the due
 * date, only the words. Without one: the next interval-based due date (`PlantCareStatus`'s, so it
 * includes the preferred-season shift) and the preferred seasons when they aren't all four. Renders
 * nothing — not even spacing — for a plant with neither a plan nor an interval. No inline season chips
 * here (product ADR-0023): preferred seasons are edited on Add/Edit Plant.
 */
@Composable
internal fun RepotPlanSummary(
    plant: Plant,
    status: PlantCareStatus?,
    actions: RepotPlanSummaryActions,
    modifier: Modifier = Modifier
) {
    val hemisphere = remember { SeasonalWatering.currentHemisphere() }
    val plan = remember(plant.repotPlanSeasonStartAt, hemisphere) {
        SeasonalRepotting.resolvePlan(plant.repotPlanSeasonStartAt, hemisphere)
    }
    val nextDueAt = status?.nextRepottingDueAt.takeIf { plant.repottingIntervalDays != null }
    val preferredSeasons = plant.repottingSeasons.takeIf {
        plant.repottingIntervalDays != null && it.size < FertilizingSeason.entries.size
    }
    if (plan == null && nextDueAt == null && preferredSeasons == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (plan != null) {
            RepotPlanLine(plan, actions)
        } else {
            if (nextDueAt != null) {
                Text(
                    text = stringResource(R.string.repot_plan_next_due, DateUtils.formatDate(nextDueAt)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (preferredSeasons != null) {
                val names = FertilizingSeason.entries
                    .filter { it in preferredSeasons }
                    .map { stringResource(it.labelRes()) }
                    .joinToString(", ")
                Text(
                    text = stringResource(R.string.repot_plan_preferred_seasons, names),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The "Plan repot" season picker (#809, product ADR-0057): the next four seasons after the current one,
 * each with the year of its first day — the current season is "repot now", so it isn't offered. An
 * [AlertDialog] of full-width options, the same presentation as `RescheduleWateringDialog`; picking a
 * season commits immediately.
 */
@Composable
internal fun RepotPlanSeasonDialog(
    onSelect: (RepotPlanSeason) -> Unit,
    onDismiss: () -> Unit,
    seasons: List<RepotPlanSeason> = remember {
        SeasonalRepotting.upcomingSeasons(LocalDate.now(), SeasonalWatering.currentHemisphere())
    }
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(REPOT_PLAN_DIALOG_TEST_TAG),
        title = { Text(stringResource(R.string.repot_plan_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                seasons.forEach { option ->
                    TextButton(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                R.string.repot_plan_season_option,
                                stringResource(option.season.labelRes()),
                                option.year
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The "Planned: spring 2027" line with its Edit and Clear buttons. */
@Composable
private fun RepotPlanLine(plan: RepotPlan, actions: RepotPlanSummaryActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(plan.season.repotPlanLabelRes(), plan.year),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        val editDescription = stringResource(R.string.repot_plan_edit_cd)
        TextButton(
            onClick = actions.onEdit,
            modifier = Modifier.semantics { contentDescription = editDescription }
        ) { Text(stringResource(R.string.repot_plan_edit)) }
        val clearDescription = stringResource(R.string.repot_plan_clear_cd)
        TextButton(
            onClick = actions.onClear,
            modifier = Modifier.semantics { contentDescription = clearDescription }
        ) { Text(stringResource(R.string.repot_plan_clear)) }
    }
}

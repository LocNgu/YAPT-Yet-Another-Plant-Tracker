package com.yapt.planttracker.ui.screens.repotting

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverviewItem
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.repotting.RepottingPlannedItem
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.util.chipLabelRes
import com.yapt.planttracker.ui.util.headerRes
import com.yapt.planttracker.ui.util.repotPlanLabelRes
import com.yapt.planttracker.util.DateUtils

internal const val REPOTTING_OVERVIEW_LIST_TAG = "repotting_overview_list"

private const val ROW_NAME_MAX_LINES = 2

internal fun repottingRowTag(plantId: Long): String = "repotting_overview_row_$plantId"

internal data class RepottingRowActions(
    val onOpen: (Long) -> Unit,
    val onRepot: (Long) -> Unit,
    val onPlan: (Long) -> Unit,
    val onClearPlan: (Long) -> Unit
)

// "Plan repot…" and "Change plan…" both open the season picker; only their label differs.
private fun RepottingRowActions.perform(action: RepottingMenuAction, plantId: Long) {
    when (action) {
        RepottingMenuAction.REPOT -> onRepot(plantId)
        RepottingMenuAction.PLAN_REPOT,
        RepottingMenuAction.CHANGE_PLAN -> onPlan(plantId)
        RepottingMenuAction.CLEAR_PLAN -> onClearPlan(plantId)
    }
}

@Composable
internal fun RepottingThresholdChips(
    selected: RepottingOverviewThreshold,
    onSelect: (RepottingOverviewThreshold) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RepottingOverviewThreshold.entries.forEach { threshold ->
            FilterChip(
                selected = threshold == selected,
                onClick = { onSelect(threshold) },
                label = { Text(stringResource(threshold.chipLabelRes())) },
                colors = yaptFilterChipColors()
            )
        }
    }
}

@Composable
internal fun RepottingOverviewList(
    threshold: RepottingOverviewThreshold,
    planned: List<RepottingPlannedItem>,
    items: List<RepottingOverviewItem>,
    actions: RepottingRowActions,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(REPOTTING_OVERVIEW_LIST_TAG),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (planned.isNotEmpty()) {
            item(key = "header:planned") {
                SectionHeader(stringResource(R.string.repotting_overview_planned_header))
            }
            items(planned, key = { "planned:${it.plant.id}" }) { PlannedRow(it, actions) }
        }
        item(key = "header:threshold") { SectionHeader(stringResource(threshold.headerRes())) }
        if (items.isEmpty()) {
            item(key = "empty:threshold") {
                Text(
                    text = stringResource(R.string.repotting_overview_no_other_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(items, key = { "plant:${it.plant.id}" }) { ThresholdRow(it, actions) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun PlannedRow(item: RepottingPlannedItem, actions: RepottingRowActions) {
    val label = stringResource(item.plan.season.repotPlanLabelRes(), item.plan.year)
    val statusLine = if (item.isOverdue) stringResource(R.string.repotting_overview_plan_overdue, label) else label
    RepottingRow(
        plant = item.plant,
        statusLine = statusLine,
        statusColor = if (item.isOverdue) OverdueRed else MaterialTheme.colorScheme.onSurfaceVariant,
        isPlanned = true,
        actions = actions
    )
}

@Composable
private fun ThresholdRow(item: RepottingOverviewItem, actions: RepottingRowActions) {
    val date = remember(item.anchorAtMillis) { DateUtils.formatMonthYear(item.anchorAtMillis) }
    val statusRes = if (item.neverRepotted) {
        R.string.repotting_overview_never_repotted
    } else {
        R.string.repotting_overview_last_repotted
    }
    val statusLine = stringResource(statusRes, date)
    RepottingRow(
        plant = item.plant,
        statusLine = statusLine,
        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
        isPlanned = false,
        actions = actions
    )
}

// The row's own text is its accessible name (name, room, status line merge into the clickable node).
// The long-press menu is mirrored as customActions plus an onLongClickLabel, like Care's tiles
// (product ADR-0056), so TalkBack users never need the gesture.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepottingRow(
    plant: Plant,
    statusLine: String,
    statusColor: Color,
    isPlanned: Boolean,
    actions: RepottingRowActions
) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuActions = repottingMenuActions(isPlanned)
    val menuLabels = menuActions.map { repottingMenuActionLabel(it) }
    val customActions = menuActions.mapIndexed { index, action ->
        CustomAccessibilityAction(menuLabels[index]) {
            actions.perform(action, plant.id)
            true
        }
    }
    Box {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { actions.onOpen(plant.id) },
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = stringResource(R.string.repotting_overview_row_actions_label),
                        role = Role.Button
                    )
                    .semantics { this.customActions = customActions }
                    .testTag(repottingRowTag(plant.id))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.clearAndSetSemantics { }) {
                    PlantPhoto(uri = plant.coverPhotoUri, size = 56.dp, rounded = true)
                }
                Spacer(Modifier.width(12.dp))
                RepottingRowText(plant, statusLine, statusColor)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuActions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    text = { Text(menuLabels[index]) },
                    onClick = {
                        menuOpen = false
                        actions.perform(action, plant.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun RowScope.RepottingRowText(plant: Plant, statusLine: String, statusColor: Color) {
    val room = plant.room?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = plant.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = ROW_NAME_MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
        if (room != null) {
            Text(
                text = room,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = statusLine, style = MaterialTheme.typography.bodySmall, color = statusColor)
    }
}

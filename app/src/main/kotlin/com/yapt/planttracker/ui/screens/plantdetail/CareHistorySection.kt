package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView

/** How many rows a collapsible care-log list shows before its "Show N more" chip (#253). */
internal const val CARE_HISTORY_COLLAPSED_COUNT = 5

/**
 * The expand/collapse state of one collapsible care-log list. The state itself is hoisted to
 * `PlantDetailScreen` (`remember`, so it resets on every screen open, #253) because the chip lives in a
 * lazy item that leaves composition when scrolled away; each list owns its own, so expanding one never
 * expands another.
 */
internal data class CareHistoryCollapse(
    val isExpanded: Boolean,
    val onToggleExpanded: () -> Unit,
    @StringRes val expandDescriptionRes: Int,
    @StringRes val collapseDescriptionRes: Int
)

internal data class CareLogRowActions(
    val onEdit: (CareLog) -> Unit,
    val onDelete: (CareLog) -> Unit,
    val customReminderName: (CareLog) -> String?
)

/**
 * Rows of one care-log list — the first [CARE_HISTORY_COLLAPSED_COUNT], or all of them once expanded —
 * followed by the "Show N more"/"Show less" chip (hidden when nothing is collapsed). Shared by Home's
 * combined log and the Water tab's WATER list (#530, product ADR-0060) so the two can't drift. [key] must
 * differ between lists that could ever share a lazy column.
 */
internal fun LazyListScope.collapsibleCareLogItems(
    logs: List<CareLog>,
    key: (CareLog) -> Any,
    collapse: CareHistoryCollapse,
    rowActions: CareLogRowActions
) {
    val visibleLogs = if (collapse.isExpanded) logs else logs.take(CARE_HISTORY_COLLAPSED_COUNT)
    items(visibleLogs, key = key) { log ->
        CareLogItem(
            log = log,
            onEdit = { rowActions.onEdit(log) },
            onDelete = { rowActions.onDelete(log) },
            customReminderName = rowActions.customReminderName(log)
        )
    }
    if (logs.size > CARE_HISTORY_COLLAPSED_COUNT) {
        item {
            CareHistoryToggleChip(collapse, hiddenCount = logs.size - CARE_HISTORY_COLLAPSED_COUNT)
        }
    }
}

/**
 * Home's combined log: a header with the count, then every care type in one list, or the shared empty
 * state. Existing `CareType.CHECK` rows (#738, product ADR-0039) are kept on disk but hidden here — a
 * screen-local display filter on the list and its count, not a filter on `PlantDetailViewModel.careLogs`,
 * which also feeds `CareSchedule.computeStatus`'s totalLogs and must not change.
 */
internal fun LazyListScope.combinedCareHistoryItems(
    careLogs: List<CareLog>,
    collapse: CareHistoryCollapse,
    rowActions: CareLogRowActions
) {
    val displayedCareLogs = careLogs.filter { it.careType != CareType.CHECK }

    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.care_history),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.plant_detail_care_logs_count, displayedCareLogs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (displayedCareLogs.isEmpty()) {
        item {
            Box(modifier = Modifier.height(200.dp)) {
                EmptyStateView(
                    message = stringResource(R.string.no_care_logs_detail),
                    icon = Icons.AutoMirrored.Filled.Notes
                )
            }
        }
    } else {
        collapsibleCareLogItems(
            logs = displayedCareLogs,
            key = { it.id },
            collapse = collapse,
            rowActions = rowActions
        )
    }
}

@Composable
private fun CareHistoryToggleChip(collapse: CareHistoryCollapse, hiddenCount: Int) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapse.isExpanded) 180f else 0f,
        label = "chevronRotation"
    )
    AssistChip(
        onClick = collapse.onToggleExpanded,
        label = {
            Text(
                if (collapse.isExpanded) {
                    stringResource(R.string.care_history_show_less)
                } else {
                    pluralStringResource(R.plurals.care_history_show_more, hiddenCount, hiddenCount)
                }
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (collapse.isExpanded) collapse.collapseDescriptionRes else collapse.expandDescriptionRes
                ),
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

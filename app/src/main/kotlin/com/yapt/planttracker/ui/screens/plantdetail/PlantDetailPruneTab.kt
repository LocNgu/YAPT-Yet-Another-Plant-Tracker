package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.CareInsights
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.util.relativeDateText

internal fun LazyListScope.pruneTabItems(
    careLogs: List<CareLog>,
    onPruneClick: () -> Unit,
    onEdit: (CareLog) -> Unit,
    onDelete: (CareLog) -> Unit
) {
    item {
        PlantDetailTabActionRow(
            labelRes = R.string.bulk_action_prune,
            icon = Icons.Filled.ContentCut,
            testTag = PRUNE_TAB_ACTION_BUTTON_TEST_TAG,
            onClick = onPruneClick
        )
        Spacer(Modifier.height(16.dp))
    }
    val pruneLogs = careLogs.filter { it.careType == CareType.PRUNE }
    item {
        val summary = CareInsights.summarize(careLogs, CareType.PRUNE)
        val lastAt = summary.lastAt
        if (summary.count > 0 && lastAt != null) {
            TabInsightsCard(
                listOf(
                    stringResource(R.string.insight_prunings) to summary.count.toString(),
                    stringResource(R.string.insight_last_pruned) to relativeDateText(lastAt)
                )
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    if (pruneLogs.isEmpty()) {
        item {
            Box(modifier = Modifier.height(160.dp)) {
                EmptyStateView(
                    message = stringResource(R.string.plant_detail_tab_prune_empty),
                    icon = Icons.Filled.ContentCut
                )
            }
        }
    } else {
        items(pruneLogs, key = { "prune-${it.id}" }) { log ->
            CareLogItem(
                log = log,
                onEdit = { onEdit(log) },
                onDelete = { onDelete(log) }
            )
        }
    }
}

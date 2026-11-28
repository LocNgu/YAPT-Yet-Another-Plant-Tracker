package com.yapt.planttracker.ui.screens.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.ActiveIssueItem
import com.yapt.planttracker.domain.insights.CollectionInsights
import com.yapt.planttracker.domain.insights.LateWateringItem
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.theme.IssuePurple
import com.yapt.planttracker.ui.util.labelRes

private const val ROW_NAME_MAX_LINES = 2

internal fun lateWateringRowTag(plantId: Long): String = "collection_insights_late_row_$plantId"

internal fun activeIssueRowTag(issueId: Long): String = "collection_insights_issue_row_$issueId"

@Composable
internal fun CollectionInsightsContent(
    insights: CollectionInsights,
    onOpenPlant: (Long, PlantDetailTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "window") {
            Text(
                text = stringResource(R.string.collection_insights_window_note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        sectionHeader("late", R.string.collection_insights_late_title, primary)
        if (insights.lateWaterings.isEmpty()) {
            item(key = "late:empty") { EmptyLine(R.string.collection_insights_late_empty) }
        } else {
            item(key = "late:hint") { HintLine(R.string.collection_insights_late_hint) }
            items(insights.lateWaterings, key = { "late:${it.plant.id}" }) {
                LateRow(it) { onOpenPlant(it.plant.id, PlantDetailTab.WATER) }
            }
        }
        sectionHeader("care", R.string.collection_insights_care_title, primary)
        if (insights.careCounts.isEmpty()) {
            item(key = "care:empty") { EmptyLine(R.string.collection_insights_care_empty) }
        } else {
            items(insights.careCounts, key = { "care:${it.type.name}" }) {
                Text(
                    text = stringResource(
                        R.string.collection_insights_care_count,
                        stringResource(it.type.labelRes()),
                        it.count
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        sectionHeader("issues", R.string.collection_insights_issues_title, IssuePurple)
        if (insights.activeIssues.isEmpty()) {
            item(key = "issues:empty") { EmptyLine(R.string.collection_insights_issues_empty) }
        } else {
            item(key = "issues:hint") { HintLine(R.string.collection_insights_issues_hint) }
            items(insights.activeIssues, key = { "issue:${it.issue.id}" }) {
                IssueRow(it) { onOpenPlant(it.plant.id, PlantDetailTab.ISSUES) }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(
    key: String,
    titleRes: Int,
    color: Color
) {
    item(key = "header:$key") {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { heading() }
        )
    }
}

@Composable
private fun EmptyLine(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun HintLine(textRes: Int) = EmptyLine(textRes)

@Composable
private fun LateRow(item: LateWateringItem, onClick: () -> Unit) {
    PlantRow(
        plant = item.plant,
        statusLine = pluralStringResource(
            R.plurals.collection_insights_late_count,
            item.lateCount,
            item.lateCount
        ),
        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tag = lateWateringRowTag(item.plant.id),
        onClick = onClick
    )
}

@Composable
private fun IssueRow(item: ActiveIssueItem, onClick: () -> Unit) {
    val duration = if (item.daysActive == 0) {
        stringResource(R.string.collection_insights_issue_started_today)
    } else {
        pluralStringResource(R.plurals.collection_insights_issue_days, item.daysActive, item.daysActive)
    }
    PlantRow(
        plant = item.plant,
        title = item.issue.name,
        statusLine = stringResource(R.string.collection_insights_issue_row, item.plant.name, duration),
        statusColor = IssuePurple,
        tag = activeIssueRowTag(item.issue.id),
        onClick = onClick
    )
}

@Suppress("LongParameterList")
@Composable
private fun PlantRow(
    plant: Plant,
    statusLine: String,
    statusColor: Color,
    tag: String,
    onClick: () -> Unit,
    title: String = plant.name
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                PlantPhoto(uri = plant.coverPhotoUri, size = 56.dp, rounded = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = ROW_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}

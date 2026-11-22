package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.issues.ResolvedIssueEntry
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.ui.components.PhotoGallery
import com.yapt.planttracker.ui.theme.IssuePurple
import com.yapt.planttracker.util.DateUtils

/**
 * Collapsible "Resolved" history below the active issues on the Issues tab (#1000). Renders nothing
 * when [entries] is empty (no placeholder). Collapsed by default; the flag is `rememberSaveable`.
 * Health-axis accents use [IssuePurple] only, never due-status colours (technical ADR-0020).
 */
@Composable
internal fun ResolvedIssuesCard(
    entries: List<ResolvedIssueEntry>,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ResolvedIssuesHeader(
                count = entries.size,
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded }
            )
            if (isExpanded) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ResolvedIssueItem(entry = entry, onPhotoClick = onPhotoClick)
                }
            }
        }
    }
}

@Composable
private fun ResolvedIssuesHeader(count: Int, isExpanded: Boolean, onToggle: () -> Unit) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "resolvedIssuesChevron"
    )
    val stateText = stringResource(
        if (isExpanded) R.string.plant_issues_resolved_state_expanded else R.string.plant_issues_resolved_state_collapsed
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(
                    if (isExpanded) R.string.plant_issues_resolved_collapse else R.string.plant_issues_resolved_expand
                ),
                onClick = onToggle
            )
            .semantics { stateDescription = stateText }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.plant_issues_resolved_section, count),
            style = MaterialTheme.typography.titleMedium
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.rotate(chevronRotation)
        )
    }
}

@Composable
private fun ResolvedIssueItem(entry: ResolvedIssueEntry, onPhotoClick: (String) -> Unit) {
    val resolvedAt = entry.issue.resolvedAt ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = entry.issue.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = pluralStringResource(
                R.plurals.plant_issue_resolved_duration,
                entry.durationDays,
                entry.durationDays,
                DateUtils.formatDate(resolvedAt)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = IssuePurple
        )
        val note = entry.issue.resolutionNote?.takeIf { it.isNotBlank() }
        if (note != null) {
            Text(
                text = stringResource(R.string.plant_issue_resolution_note, note),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (entry.treatments.isNotEmpty()) {
            ResolvedIssueTreatments(entry.treatments)
        }
        entry.beforePhoto?.let { ResolvedIssuePhotoRow(R.string.plant_issue_photos_before, listOf(it), onPhotoClick) }
        if (entry.duringPhotos.isNotEmpty()) {
            ResolvedIssuePhotoRow(R.string.plant_issue_photos_during, entry.duringPhotos, onPhotoClick)
        }
    }
}

@Composable
private fun ResolvedIssueTreatments(treatments: List<CareLog>) {
    Text(
        text = pluralStringResource(R.plurals.plant_issue_treatments_header, treatments.size, treatments.size),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp)
    )
    treatments.forEach { log ->
        val date = DateUtils.formatDate(log.loggedAt)
        val note = log.notes?.takeIf { it.isNotBlank() }
        Text(
            text = if (note == null) {
                stringResource(R.string.plant_issue_treatment_row, date)
            } else {
                stringResource(R.string.plant_issue_treatment_row_with_note, date, note)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResolvedIssuePhotoRow(labelRes: Int, photos: List<GalleryPhoto>, onPhotoClick: (String) -> Unit) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    PhotoGallery(
        photoUris = photos.map { it.uri },
        onPhotoClick = onPhotoClick,
        contentPadding = PaddingValues(0.dp)
    )
}

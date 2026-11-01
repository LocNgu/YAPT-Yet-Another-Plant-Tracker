package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R

private val CARE_HEADER_MIN_HEIGHT = 48.dp

@Composable
fun CareGroupHeader(
    title: String,
    plantCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val label = stringResource(R.string.care_group_header, title, plantCount)
    val description = pluralStringResource(R.plurals.care_group_header_cd, plantCount, title, plantCount)
    val state = stringResource(
        if (collapsed) R.string.care_group_state_collapsed else R.string.care_group_state_expanded
    )
    val action = stringResource(
        if (collapsed) R.string.care_group_expand_action else R.string.care_group_collapse_action
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .heightIn(min = CARE_HEADER_MIN_HEIGHT)
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                contentDescription = description
                stateDescription = state
            }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

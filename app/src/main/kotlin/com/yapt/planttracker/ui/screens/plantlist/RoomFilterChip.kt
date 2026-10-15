package com.yapt.planttracker.ui.screens.plantlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.yaptFilterChipColors

@Composable
internal fun RoomFilterChip(
    label: String,
    selected: Boolean,
    containsSelection: Boolean,
    onClick: () -> Unit
) {
    val stateText = when {
        !containsSelection -> null
        selected -> stringResource(R.string.state_room_chip_selected_has_selection)
        else -> stringResource(R.string.state_room_chip_has_selection)
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (containsSelection) {
                    Spacer(Modifier.width(6.dp))
                    Spacer(
                        Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
        },
        modifier = if (stateText != null) {
            Modifier.semantics { stateDescription = stateText }
        } else {
            Modifier
        },
        colors = yaptFilterChipColors()
    )
}

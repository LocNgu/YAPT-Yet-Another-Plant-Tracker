package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.util.icon
import com.yapt.planttracker.ui.util.labelRes

@Composable
fun CareTypeChip(
    careType: CareType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(careType.labelRes())) },
        leadingIcon = {
            Icon(
                imageVector = careType.icon(),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize)
            )
        },
        modifier = modifier
    )
}

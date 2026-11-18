package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val INFO_ICON_SIZE = 20.dp

/**
 * An (i) icon that opens a rich tooltip with [text] on tap (product ADR-0087). Closes on an outside
 * tap or Back. Tapping never changes whatever setting it sits beside; place it outside any clickable
 * or toggleable row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTooltip(contentDescription: String, text: String, modifier: Modifier = Modifier) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { RichTooltip { Text(text) } },
        state = tooltipState,
        modifier = modifier,
        enableUserInput = false
    ) {
        IconButton(onClick = { scope.launch { tooltipState.show() } }) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = contentDescription,
                modifier = Modifier.size(INFO_ICON_SIZE)
            )
        }
    }
}

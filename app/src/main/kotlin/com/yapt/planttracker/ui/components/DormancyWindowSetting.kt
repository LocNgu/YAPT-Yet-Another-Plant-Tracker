package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import java.time.LocalDate

/** Shared editor for the two nullable month columns (#762). Every change supplies a complete pair. */
@Composable
fun DormancyWindowSetting(
    startMonth: Int?,
    endMonth: Int?,
    onWindowChange: (Int?, Int?) -> Unit
) {
    val enabled = startMonth != null && endMonth != null && startMonth in 1..12 && endMonth in 1..12
    val label = stringResource(R.string.dormancy_window_label)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        val month = LocalDate.now().monthValue
                        onWindowChange(month, month)
                    } else {
                        onWindowChange(null, null)
                    }
                },
                modifier = Modifier.semantics { contentDescription = label }
            )
        }
        if (enabled) {
            Text(
                stringResource(R.string.dormancy_window_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthDropdown(
                    label = stringResource(R.string.dormancy_start_month),
                    month = startMonth!!,
                    onMonthChange = { onWindowChange(it, endMonth) },
                    modifier = Modifier.weight(1f)
                )
                MonthDropdown(
                    label = stringResource(R.string.dormancy_end_month),
                    month = endMonth!!,
                    onMonthChange = { onWindowChange(startMonth, it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MonthDropdown(label: String, month: Int, onMonthChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val months = LocalContext.current.resources.getStringArray(R.array.dormancy_months)
    val selectedMonthDescription = stringResource(R.string.dormancy_month_selection_cd, label, months[month - 1])
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = selectedMonthDescription }
        ) {
            Text(months[month - 1])
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            months.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onMonthChange(index + 1)
                    }
                )
            }
        }
    }
}

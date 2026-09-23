package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.DormancyWindow
import java.time.LocalDate
import kotlin.math.roundToInt

/** Shared editor for the two nullable month columns (#762). Every change supplies a complete pair. */
@Composable
fun DormancyWindowSetting(
    startMonth: Int?,
    endMonth: Int?,
    onWindowChange: (Int?, Int?) -> Unit
) = DormancyWindowSetting(startMonth, endMonth, null) { start, end, _ -> onWindowChange(start, end) }

@Composable
fun DormancyWindowSetting(
    startMonth: Int?,
    endMonth: Int?,
    dormantWateringIntervalDays: Int?,
    onWindowChange: (Int?, Int?, Int?) -> Unit
) {
    val persistedWindow = Triple(
        startMonth,
        endMonth,
        DormancyWindow.validWateringInterval(dormantWateringIntervalDays)
    )
    // Room may re-emit an earlier selection after the user has already changed the other month.
    // Keep the most recent complete pair visible until persistence catches up.
    var pendingWindow by remember { mutableStateOf<Triple<Int?, Int?, Int?>?>(null) }
    LaunchedEffect(persistedWindow) {
        if (pendingWindow == persistedWindow) pendingWindow = null
    }
    val (shownStart, shownEnd, shownCadence) = pendingWindow ?: persistedWindow
    val enabled = shownStart != null && shownEnd != null && shownStart in 1..12 && shownEnd in 1..12
    val label = stringResource(R.string.dormancy_window_label)
    fun changeWindow(nextStart: Int?, nextEnd: Int?, nextCadence: Int?) {
        pendingWindow = Triple(nextStart, nextEnd, nextCadence)
        onWindowChange(nextStart, nextEnd, nextCadence)
    }
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
                        changeWindow(month, month, null)
                    } else {
                        changeWindow(null, null, null)
                    }
                },
                modifier = Modifier.semantics { contentDescription = label }
            )
        }
        if (enabled) {
            DormancyWindowControls(shownStart!!, shownEnd!!, shownCadence, ::changeWindow)
        }
    }
}

@Composable
private fun DormancyWindowControls(
    startMonth: Int,
    endMonth: Int,
    cadence: Int?,
    onChange: (Int?, Int?, Int?) -> Unit
) {
    Text(
        stringResource(R.string.dormancy_window_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MonthDropdown(
            stringResource(R.string.dormancy_start_month),
            startMonth,
            { onChange(it, endMonth, cadence) },
            Modifier.weight(1f)
        )
        MonthDropdown(
            stringResource(R.string.dormancy_end_month),
            endMonth,
            { onChange(startMonth, it, cadence) },
            Modifier.weight(1f)
        )
    }
    DormantWateringIntervalControl(cadence) { onChange(startMonth, endMonth, it) }
}

/**
 * The switch turns the dormant cadence on; its label names what it enables ("Dormancy watering
 * interval") so switching it on reads as starting watering, not ending a pause (#785). The subtitle
 * carries the current state and the slider only appears once a cadence exists.
 */
@Composable
private fun DormantWateringIntervalControl(cadence: Int?, onCadenceChange: (Int?) -> Unit) {
    val label = stringResource(R.string.dormant_watering_label)
    val persistedWeeks = DormancyWindow.wateringIntervalWeeks(cadence)
    // Local while dragging so the subtitle follows the thumb; committed on release.
    var sliderWeeks by remember(persistedWeeks) {
        mutableIntStateOf(persistedWeeks ?: DormancyWindow.DEFAULT_WATERING_INTERVAL_WEEKS)
    }
    val weeksLabel = dormantWateringIntervalLabel(sliderWeeks)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (persistedWeeks == null) stringResource(R.string.dormant_watering_suspended) else weeksLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = persistedWeeks != null,
            onCheckedChange = { enabled ->
                onCadenceChange(
                    if (enabled) {
                        DormancyWindow.wateringIntervalDays(DormancyWindow.DEFAULT_WATERING_INTERVAL_WEEKS)
                    } else {
                        null
                    }
                )
            },
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
    if (persistedWeeks != null) {
        Slider(
            value = sliderWeeks.toFloat(),
            onValueChange = { sliderWeeks = it.roundToInt() },
            onValueChangeFinished = { onCadenceChange(DormancyWindow.wateringIntervalDays(sliderWeeks)) },
            valueRange = WEEK_SLIDER_RANGE,
            steps = DormancyWindow.MAX_WATERING_INTERVAL_WEEKS - DormancyWindow.MIN_WATERING_INTERVAL_WEEKS - 1,
            modifier = Modifier.semantics { stateDescription = weeksLabel }
        )
    }
}

private val WEEK_SLIDER_RANGE =
    DormancyWindow.MIN_WATERING_INTERVAL_WEEKS.toFloat()..DormancyWindow.MAX_WATERING_INTERVAL_WEEKS.toFloat()

@Composable
private fun dormantWateringIntervalLabel(weeks: Int): String = pluralStringResource(
    R.plurals.dormant_watering_every_weeks,
    weeks,
    weeks
)

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

package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.SageGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TimeRange(val labelRes: Int, val daysBack: Int) {
    ONE_MONTH(R.string.time_range_1m, 30),
    THREE_MONTHS(R.string.time_range_3m, 90),
    SIX_MONTHS(R.string.time_range_6m, 180),
    TWELVE_MONTHS(R.string.time_range_12m, 365)
}

data class WateringInterval(
    val timestamp: Long,
    val daysSincePrevious: Float
)

@Composable
fun WateringHistoryChart(
    careLogs: List<CareLog>,
    selectedRange: TimeRange = TimeRange.ONE_MONTH,
    onRangeSelected: (TimeRange) -> Unit
) {
    val wateringLogs = careLogs.filter { it.careType == CareType.WATER }
        .sortedBy { it.loggedAt }

    val now = System.currentTimeMillis()
    val rangeStartMs = now - (selectedRange.daysBack.toLong() * 24 * 60 * 60 * 1000)

    val intervals = computeWateringIntervals(wateringLogs, rangeStartMs, now)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.watering_history),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeRange.values().forEach { range ->
                FilterChip(
                    selected = range == selectedRange,
                    onClick = { onRangeSelected(range) },
                    label = { Text(stringResource(range.labelRes)) }
                )
            }
        }

        if (intervals.size < 2) {
            Text(
                text = stringResource(R.string.insufficient_watering_logs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            ChartContent(intervals)
        }
    }
}

@Composable
private fun ChartContent(intervals: List<WateringInterval>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.days_between_waterings),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (intervals.isNotEmpty()) {
                val maxDays = intervals.maxOf { it.daysSincePrevious }
                val minDays = intervals.minOf { it.daysSincePrevious }
                val range = if (maxDays > minDays) maxDays - minDays else maxDays

                intervals.forEach { interval ->
                    BarColumn(
                        value = interval.daysSincePrevious,
                        minValue = minDays,
                        range = range,
                        maxValue = maxDays,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            }
        }

        if (intervals.isNotEmpty()) {
            ChartAxisLabels(intervals)
        }
    }

    if (intervals.isNotEmpty()) {
        ChartLegend(intervals)
    }
}

@Composable
private fun BarColumn(
    value: Float,
    minValue: Float,
    range: Float,
    maxValue: Float,
    modifier: Modifier = Modifier
) {
    val normalizedHeight = if (range > 0) {
        (value - minValue) / range
    } else {
        if (value > 0) 1f else 0.5f
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f - normalizedHeight)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(normalizedHeight)
                .background(SageGreen)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(OkGreen)
        )
    }
}

@Composable
private fun ChartAxisLabels(intervals: List<WateringInterval>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val first = intervals.first()
        val last = intervals.last()

        val dateFormatter = DateTimeFormatter.ofPattern("MMM d")
            .withZone(ZoneId.systemDefault())

        Text(
            text = dateFormatter.format(Instant.ofEpochMilli(first.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = dateFormatter.format(Instant.ofEpochMilli(last.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChartLegend(intervals: List<WateringInterval>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (intervals.isNotEmpty()) {
            val lastInterval = intervals.last()
            val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                .withZone(ZoneId.systemDefault())
            val dateStr = dateFormatter.format(Instant.ofEpochMilli(lastInterval.timestamp))

            Text(
                text = stringResource(R.string.last_watering, dateStr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val avgInterval = intervals.map { it.daysSincePrevious }.average()
            Text(
                text = stringResource(R.string.average_interval, avgInterval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun computeWateringIntervals(
    wateringLogs: List<CareLog>,
    rangeStartMs: Long,
    now: Long
): List<WateringInterval> {
    val filtered = wateringLogs.filter { it.loggedAt >= rangeStartMs && it.loggedAt <= now }

    if (filtered.size < 2) {
        return emptyList()
    }

    val intervals = mutableListOf<WateringInterval>()
    for (i in 1 until filtered.size) {
        val prevLog = filtered[i - 1]
        val currentLog = filtered[i]
        val daysDiff = (currentLog.loggedAt - prevLog.loggedAt) / (24 * 60 * 60 * 1000).toFloat()
        intervals.add(WateringInterval(currentLog.loggedAt, daysDiff))
    }

    return intervals
}

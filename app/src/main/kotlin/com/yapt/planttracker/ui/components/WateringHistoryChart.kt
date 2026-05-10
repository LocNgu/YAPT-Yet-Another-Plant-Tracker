package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val DAY_IN_MS = 24L * 60 * 60 * 1000
private val MonthLabelsKey = ExtraStore.Key<Map<Int, String>>()

enum class TimeRange(val labelRes: Int, val daysBack: Int) {
    ONE_MONTH(R.string.time_range_1m, 30),
    THREE_MONTHS(R.string.time_range_3m, 90),
    SIX_MONTHS(R.string.time_range_6m, 180),
    TWELVE_MONTHS(R.string.time_range_12m, 365),
    ALL_TIME(R.string.time_range_all, Int.MAX_VALUE)
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
    val rangeStartMs = when (selectedRange) {
        TimeRange.ALL_TIME -> wateringLogs.minByOrNull { it.loggedAt }?.loggedAt ?: now
        else -> now - (selectedRange.daysBack.toLong() * DAY_IN_MS)
    }

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

        if (intervals.isEmpty()) {
            Text(
                text = stringResource(R.string.insufficient_watering_logs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            ChartContent(intervals, rangeStartMs, now)
        }
    }
}

@Composable
private fun ChartContent(intervals: List<WateringInterval>, rangeStartMs: Long, now: Long) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val (monthlyPoints, monthLabels) = remember(intervals, rangeStartMs, now) {
        val points = mutableListOf<Pair<Float, Float>>()
        val indexToZdt = mutableListOf<ZonedDateTime>()

        var monthIndex = 0
        var monthStart = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(rangeStartMs), ZoneId.systemDefault()
        ).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)

        while (!monthStart.toInstant().isAfter(Instant.ofEpochMilli(now))) {
            val monthStartMs = monthStart.toInstant().toEpochMilli()
            val monthEndMs = monthStart.plusMonths(1).toInstant().toEpochMilli()

            val monthIntervals = intervals.filter {
                it.timestamp >= monthStartMs && it.timestamp < monthEndMs
            }
            val y = if (monthIntervals.isEmpty()) 0f
                    else monthIntervals.map { it.daysSincePrevious }.average().toFloat()

            points.add(monthIndex.toFloat() to y)
            indexToZdt.add(monthStart)

            monthIndex++
            monthStart = monthStart.plusMonths(1)
        }

        // If any short month name repeats (e.g. "May" twice in a 12-month range that
        // crosses a year boundary), fall back to "MMM yy" so each label is unique.
        val fmtShort = DateTimeFormatter.ofPattern("MMM").withZone(ZoneId.systemDefault())
        val shortNames = indexToZdt.map { fmtShort.format(it) }
        val fmt = if (shortNames.toSet().size < shortNames.size) {
            DateTimeFormatter.ofPattern("MMM yy").withZone(ZoneId.systemDefault())
        } else {
            fmtShort
        }
        val labels = indexToZdt.mapIndexed { idx, zdt -> idx to fmt.format(zdt) }.toMap()

        points to labels
    }

    // Key on both intervals and rangeStartMs so the effect also fires when Room data
    // first arrives (intervals goes from empty to populated) and when range changes.
    // Labels are stored in the ExtraStore alongside the data so they are always read
    // from the same model snapshot, eliminating any label/data timing mismatch.
    LaunchedEffect(intervals, rangeStartMs) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = monthlyPoints.map { it.first },
                    y = monthlyPoints.map { it.second }
                )
            }
            extras { store -> store[MonthLabelsKey] = monthLabels }
        }
    }

    val dateFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(MonthLabelsKey)?.get(x.roundToInt()) ?: " "
        }
    }

    val dayFormatter = remember {
        CartesianValueFormatter { _, y, _ ->
            "${y.toInt()}d"
        }
    }

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

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = dayFormatter
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = dateFormatter
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        )
    }

    if (intervals.isNotEmpty()) {
        ChartLegend(intervals)
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

package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.ui.util.relativeDateText
import com.yapt.planttracker.util.DateUtils

internal enum class HomeSummaryField(@StringRes val labelRes: Int) {
    LAST_WATERED(R.string.insight_last_watered),
    NEXT_WATERING(R.string.home_summary_next_watering),
    LAST_FERTILIZED(R.string.insight_last_fertilized),
    NEXT_FERTILIZING(R.string.home_summary_next_fertilizing)
}

internal sealed interface HomeSummaryValue {
    data object Never : HomeSummaryValue
    data object Dormant : HomeSummaryValue
    data class At(val timestampMs: Long) : HomeSummaryValue
}

internal data class HomeSummaryRow(val field: HomeSummaryField, val value: HomeSummaryValue)

/**
 * Rows of the Home tab's summary card (#530, product ADR-0060), derived only from the already-computed
 * [PlantCareStatus] — no date math here. Watering is suspended exactly when the status's schedule mode
 * says so (`isDormant` alone is also true during a dormant *cadence*, which has a live due date to show);
 * fertilizing is suspended whenever `isDormant`. A missing next date hides its row.
 */
internal fun homeSummaryRows(status: PlantCareStatus): List<HomeSummaryRow> {
    val plant = status.plant
    val rows = mutableListOf(
        HomeSummaryRow(
            HomeSummaryField.LAST_WATERED,
            status.lastWateredAt?.let { HomeSummaryValue.At(it) } ?: HomeSummaryValue.Never
        )
    )
    val nextWatering: HomeSummaryValue? = when {
        status.wateringScheduleMode == WateringScheduleMode.DORMANT_SUSPENDED ->
            HomeSummaryValue.Dormant.takeIf { plant.wateringIntervalDays != null }
        else -> status.nextWateringDueAt?.let { HomeSummaryValue.At(it) }
    }
    nextWatering?.let { rows += HomeSummaryRow(HomeSummaryField.NEXT_WATERING, it) }
    if (plant.fertilizingIntervalDays != null) {
        rows += HomeSummaryRow(
            HomeSummaryField.LAST_FERTILIZED,
            status.lastFertilizedAt?.let { HomeSummaryValue.At(it) } ?: HomeSummaryValue.Never
        )
        val nextFertilizing: HomeSummaryValue? = if (status.isDormant) {
            HomeSummaryValue.Dormant
        } else {
            status.nextFertilizingDueAt?.let { HomeSummaryValue.At(it) }
        }
        nextFertilizing?.let { rows += HomeSummaryRow(HomeSummaryField.NEXT_FERTILIZING, it) }
    }
    return rows
}

@Composable
private fun HomeSummaryValue.text(field: HomeSummaryField): String = when (this) {
    HomeSummaryValue.Dormant -> stringResource(R.string.date_group_dormant)
    HomeSummaryValue.Never -> stringResource(
        if (field == HomeSummaryField.LAST_FERTILIZED) {
            R.string.fert_label_never_fertilized
        } else {
            R.string.water_label_never_watered
        }
    )
    is HomeSummaryValue.At -> stringResource(
        R.string.home_summary_date_with_relative,
        relativeDateText(timestampMs),
        DateUtils.formatDate(timestampMs)
    )
}

/** Home tab's last/next watering and fertilizing dates; hosts no settings controls (product ADR-0023). */
@Composable
internal fun HomeSummaryCard(status: PlantCareStatus, modifier: Modifier = Modifier) {
    val items = homeSummaryRows(status).map { row ->
        stringResource(row.field.labelRes) to row.value.text(row.field)
    }
    TabInsightsCard(items, modifier)
}

/**
 * The Reschedule delta chip (same gate as the Water tab: a winning override that is not suspended by
 * dormancy) above [WateringDueActionsRow]. Shared by the Water and Home tabs (#530) so Home adds no
 * second code path for logging or rescheduling a watering.
 */
@Composable
internal fun RescheduleChipAndWateringActions(
    status: PlantCareStatus,
    onRevertReschedule: () -> Unit,
    onWaterClick: () -> Unit,
    onRescheduleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        status.rescheduleDeltaDays?.takeUnless {
            status.wateringScheduleMode == WateringScheduleMode.DORMANT_SUSPENDED
        }?.let { delta ->
            RescheduleDeltaChip(
                deltaDays = delta,
                onClick = onRevertReschedule,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        WateringDueActionsRow(
            onWaterClick = onWaterClick,
            onRescheduleClick = if (status.computedNextWateringDueAt != null) onRescheduleClick else null
        )
    }
}

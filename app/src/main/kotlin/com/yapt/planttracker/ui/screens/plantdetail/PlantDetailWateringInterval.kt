package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.LiveWateringInterval
import com.yapt.planttracker.ui.util.sentenceLabelRes

/**
 * The 💧 line under the plant name in the Plant Detail hero (#927, product ADR-0068). Shows today's
 * live interval, never `Plant.wateringIntervalDays`. Hidden until [status] has loaded, so a stale or
 * missing status can neither flash the ordinary form on a dormant plant nor the reverse.
 * [dormantWateringIntervalDays] is only read in `DORMANT_CADENCE`. [onWhyThisDate] is non-null only when
 * the "Why this date?" sheet has a date to explain, which makes the line tappable; `DORMANT_SUSPENDED`
 * ("Dormant") stays plain text because there is no schedule to explain.
 */
@Composable
internal fun WateringHeroLine(
    status: PlantCareStatus?,
    live: LiveWateringInterval?,
    dormantWateringIntervalDays: Int?,
    onWhyThisDate: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val text = when (status?.wateringScheduleMode) {
        null -> null
        WateringScheduleMode.DORMANT_SUSPENDED -> stringResource(R.string.plant_detail_watering_dormant)
        WateringScheduleMode.DORMANT_CADENCE -> DormancyWindow.wateringIntervalWeeks(dormantWateringIntervalDays)
            ?.let { weeks -> pluralStringResource(R.plurals.plant_detail_watering_dormant_cadence, weeks, weeks) }
        WateringScheduleMode.NORMAL -> live?.let { interval ->
            val adjustment = interval.adjustment
            if (adjustment == null) {
                stringResource(R.string.plant_detail_watering_interval, interval.effectiveDays)
            } else {
                stringResource(
                    R.string.plant_detail_watering_interval_seasonal,
                    interval.effectiveDays,
                    stringResource(adjustment.season.sentenceLabelRes()),
                    adjustment.baseDays
                )
            }
        }
    }
    if (text != null) {
        val onTap = onWhyThisDate.takeIf { status?.wateringScheduleMode != WateringScheduleMode.DORMANT_SUSPENDED }
        val lineModifier = if (onTap != null) {
            modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    onClickLabel = stringResource(R.string.why_this_date_button),
                    role = Role.Button,
                    onClick = onTap
                )
        } else {
            modifier
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = lineModifier
        )
    }
}

/**
 * The "Adjusted for winter · base 8 days" line in the Water-tab interval card (#927). Rendered only
 * while the ordinary schedule is the active one (`NORMAL`); [onWhyThisDate] is non-null only when the
 * "Why this date?" sheet has a date to explain, which is what makes the line tappable.
 */
@Composable
internal fun WateringSeasonalAdjustmentLine(
    status: PlantCareStatus?,
    live: LiveWateringInterval?,
    onWhyThisDate: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val adjustment = live?.adjustment
    if (status?.wateringScheduleMode != WateringScheduleMode.NORMAL || adjustment == null) return
    val text = pluralStringResource(
        R.plurals.watering_seasonal_adjustment,
        adjustment.baseDays,
        stringResource(adjustment.season.sentenceLabelRes()),
        adjustment.baseDays
    )
    val lineModifier = if (onWhyThisDate != null) {
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = stringResource(R.string.why_this_date_button),
                role = Role.Button,
                onClick = onWhyThisDate
            )
    } else {
        modifier.fillMaxWidth().padding(vertical = 4.dp)
    }
    Box(modifier = lineModifier, contentAlignment = Alignment.CenterStart) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

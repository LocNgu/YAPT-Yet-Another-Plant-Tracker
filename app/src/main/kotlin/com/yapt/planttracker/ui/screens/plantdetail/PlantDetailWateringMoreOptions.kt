package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.WateringStrengthMode
import com.yapt.planttracker.domain.schedule.resolveWateringAmplitude
import com.yapt.planttracker.domain.schedule.wateringStrengthMode
import com.yapt.planttracker.ui.components.DormancyWindowSetting
import com.yapt.planttracker.ui.components.SeasonalCurvePlantContext
import com.yapt.planttracker.ui.components.SeasonalWateringCurveChart
import com.yapt.planttracker.ui.components.WateringStrengthCallbacks
import com.yapt.planttracker.ui.components.WateringStrengthSetting
import java.time.Month
import java.time.format.TextStyle

internal data class WateringMoreOptionsState(
    val plant: Plant?,
    val globalAmplitude: Double,
    val expanded: Boolean
)

internal data class WateringMoreOptionsActions(
    val onToggle: (Boolean) -> Unit,
    val onStrengthChange: (pinned: Boolean, override: SeasonalAmplitude?) -> Unit,
    val onWindowChange: (Int?, Int?, Int?) -> Unit
)

/** Collapsed-by-default section below the Water card (#1038, product ADR-0088). */
@Composable
internal fun WateringMoreOptionsSection(
    state: WateringMoreOptionsState,
    actions: WateringMoreOptionsActions
) {
    val plant = state.plant
    val wateringOn = plant?.wateringIntervalDays != null
    val values = WateringMoreOptionsValues(
        dormancyStartMonth = plant?.dormancyStartMonth,
        dormancyEndMonth = plant?.dormancyEndMonth,
        dormantWateringIntervalDays = plant?.dormantWateringIntervalDays,
        strengthMode = if (wateringOn) {
            wateringStrengthMode(plant?.pinIntervalToBase == true, plant?.wateringAmplitudeOverride)
        } else {
            null
        }
    )
    val summary = joinWateringSummary(wateringOptionChanges(values).map { wateringChangeText(it) })
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        WateringMoreOptionsHeader(state.expanded, summary, actions.onToggle)
        if (state.expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wateringOn) {
                    WateringStrengthSetting(
                        mode = checkNotNull(values.strengthMode),
                        customStrength = plant?.wateringAmplitudeOverride,
                        callbacks = WateringStrengthCallbacks(
                            onSelectAppDefault = { actions.onStrengthChange(false, null) },
                            onSelectCustom = { actions.onStrengthChange(false, it) },
                            onSelectPin = { actions.onStrengthChange(true, null) }
                        ),
                        showInfo = true
                    )
                }
                DormancyWindowSetting(
                    startMonth = plant?.dormancyStartMonth,
                    endMonth = plant?.dormancyEndMonth,
                    dormantWateringIntervalDays = plant?.dormantWateringIntervalDays,
                    showInfo = true,
                    onWindowChange = actions.onWindowChange
                )
                if (wateringOn) {
                    val hemisphere = remember { SeasonalWatering.currentHemisphere() }
                    SeasonalWateringCurveChart(
                        amplitude = resolveWateringAmplitude(
                            pinned = false,
                            override = plant?.wateringAmplitudeOverride,
                            globalAmplitude = state.globalAmplitude
                        ),
                        hemisphere = hemisphere,
                        plantContext = SeasonalCurvePlantContext(
                            isPinned = plant?.pinIntervalToBase == true,
                            baseIntervalDays = plant?.wateringBaseIntervalDays
                                ?: plant?.wateringIntervalDays?.toDouble(),
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun wateringChangeText(change: WateringOptionChange): String = when (change) {
    is WateringOptionChange.Dormancy -> {
        val locale = LocalConfiguration.current.locales[0]
        val start = Month.of(change.startMonth).getDisplayName(TextStyle.SHORT, locale)
        val end = Month.of(change.endMonth).getDisplayName(TextStyle.SHORT, locale)
        val weeks = change.cadenceWeeks
        if (weeks == null) {
            stringResource(R.string.watering_more_options_summary_dormant, start, end)
        } else {
            pluralStringResource(R.plurals.watering_more_options_summary_dormant_cadence, weeks, start, end, weeks)
        }
    }
    is WateringOptionChange.Strength -> stringResource(
        if (change.mode == WateringStrengthMode.PIN) {
            R.string.watering_more_options_summary_pinned
        } else {
            R.string.watering_more_options_summary_custom_strength
        }
    )
}

@Composable
private fun WateringMoreOptionsHeader(expanded: Boolean, summary: String?, onToggle: (Boolean) -> Unit) {
    val state = stringResource(
        if (expanded) R.string.add_plant_more_options_expanded else R.string.add_plant_more_options_collapsed
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onToggle(!expanded) }
            .semantics { stateDescription = state }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.add_plant_more_options), style = MaterialTheme.typography.titleSmall)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null
        )
    }
}

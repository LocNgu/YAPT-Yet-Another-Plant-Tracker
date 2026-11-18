package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.CUSTOM_WATERING_STRENGTHS
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.WateringStrengthMode
import com.yapt.planttracker.ui.util.labelRes

/**
 * The 3-way per-plant seasonal strength control (#700, product ADR-0084): App default, Custom (Mild /
 * Standard / Strong) or Pin. Shared by Add/Edit Plant and the Plant Detail Water tab so the two
 * surfaces can never disagree. Picking Custom starts at [SeasonalAmplitude.STANDARD].
 */
@Composable
fun WateringStrengthSetting(
    mode: WateringStrengthMode,
    customStrength: SeasonalAmplitude?,
    callbacks: WateringStrengthCallbacks,
    modifier: Modifier = Modifier,
    showInfo: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StrengthLabelRow(showInfo)
        val modes = WateringStrengthMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == mode,
                    onClick = {
                        when (option) {
                            WateringStrengthMode.APP_DEFAULT -> callbacks.onSelectAppDefault()
                            WateringStrengthMode.CUSTOM ->
                                if (mode != WateringStrengthMode.CUSTOM) {
                                    callbacks.onSelectCustom(SeasonalAmplitude.STANDARD)
                                }
                            WateringStrengthMode.PIN -> callbacks.onSelectPin()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    modifier = Modifier.testTag("watering_strength_mode_${option.name}")
                ) {
                    Text(stringResource(option.labelRes()))
                }
            }
        }
        if (mode == WateringStrengthMode.CUSTOM) {
            CustomStrengthOptions(customStrength, callbacks.onSelectCustom)
        }
        StrengthSubtitleRow(mode, showInfo)
    }
}

@Composable
private fun StrengthLabelRow(showInfo: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.watering_strength_label),
            style = MaterialTheme.typography.bodyMedium
        )
        if (showInfo) {
            InfoTooltip(
                contentDescription = stringResource(R.string.info_strength_cd),
                text = stringResource(R.string.info_strength_text)
            )
        }
    }
}

@Composable
private fun StrengthSubtitleRow(mode: WateringStrengthMode, showInfo: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                when (mode) {
                    WateringStrengthMode.APP_DEFAULT -> R.string.watering_strength_subtitle_default
                    WateringStrengthMode.CUSTOM -> R.string.watering_strength_subtitle_custom
                    WateringStrengthMode.PIN -> R.string.pin_interval_subtitle
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (showInfo) {
            InfoTooltip(
                contentDescription = stringResource(R.string.info_pin_cd),
                text = stringResource(R.string.info_pin_text)
            )
        }
    }
}

@Composable
private fun CustomStrengthOptions(customStrength: SeasonalAmplitude?, onSelectCustom: (SeasonalAmplitude) -> Unit) {
    val customOptionsDescription = stringResource(R.string.watering_strength_custom_options_label)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = customOptionsDescription }
    ) {
        CUSTOM_WATERING_STRENGTHS.forEachIndexed { index, strength ->
            SegmentedButton(
                selected = strength == customStrength,
                onClick = { onSelectCustom(strength) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = CUSTOM_WATERING_STRENGTHS.size),
                modifier = Modifier.testTag("watering_strength_option_${strength.name}")
            ) {
                Text(stringResource(strength.labelRes()))
            }
        }
    }
}

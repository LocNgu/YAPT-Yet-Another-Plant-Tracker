package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.wateringStrengthMode
import com.yapt.planttracker.ui.components.DormancyWindowSetting
import com.yapt.planttracker.ui.components.FertilizingSeasonsSelector
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.SteppedSlider
import com.yapt.planttracker.ui.components.SteppedSliderCallbacks
import com.yapt.planttracker.ui.components.SteppedSliderLabels
import com.yapt.planttracker.ui.components.WateringStrengthCallbacks
import com.yapt.planttracker.ui.components.WateringStrengthSetting
import com.yapt.planttracker.ui.util.sentenceLabelRes

@Composable
internal fun CoverPhotoPicker(uri: String?, onPickPhoto: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        PlantPhoto(uri = uri, size = 120.dp, rounded = true)
        FloatingActionButton(
            onClick = onPickPhoto,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(40.dp)
        ) {
            Icon(
                Icons.Filled.AddAPhoto,
                contentDescription = stringResource(R.string.cd_add_photo),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun PropagatedFromNote(parentName: String) {
    Column {
        Text(
            text = stringResource(R.string.add_plant_propagated_from, parentName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.add_plant_care_settings_copied, parentName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlantIdentityFields(viewModel: AddEditPlantViewModel, rooms: List<String>) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = viewModel.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.field_plant_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = viewModel.species,
            onValueChange = viewModel::setSpecies,
            label = { Text(stringResource(R.string.field_species)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = viewModel.room,
            onValueChange = viewModel::setRoom,
            label = { Text(stringResource(R.string.field_location)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.placeholder_location)) }
        )
        if (rooms.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { chip ->
                    val isMatch = chip.equals(viewModel.room, ignoreCase = true) && chip != viewModel.room
                    SuggestionChip(
                        onClick = {
                            viewModel.setRoom(chip)
                            keyboardController?.hide()
                        },
                        label = { Text(chip) },
                        colors = if (isMatch) {
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            SuggestionChipDefaults.suggestionChipColors()
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlantNotesField(viewModel: AddEditPlantViewModel) {
    OutlinedTextField(
        value = viewModel.notes,
        onValueChange = viewModel::setNotes,
        label = { Text(stringResource(R.string.field_notes)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 6
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun WateringSection(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState) {
    Column {
        val wateringIntervalLabel = stringResource(R.string.watering_interval_label, form.wateringIntervalDays)
        SwitchRow(
            label = if (form.wateringIntervalEnabled) {
                wateringIntervalLabel
            } else {
                stringResource(R.string.watering_reminder_label)
            },
            checked = form.wateringIntervalEnabled,
            onCheckedChange = viewModel::setWateringIntervalEnabled
        )
        if (form.wateringIntervalEnabled) {
            SteppedSlider(
                value = form.wateringIntervalDays,
                range = 1..60,
                callbacks = SteppedSliderCallbacks(onValueChange = viewModel::setWateringIntervalDays),
                labels = SteppedSliderLabels(
                    decreaseContentDescription = stringResource(R.string.watering_interval_decrease_cd),
                    increaseContentDescription = stringResource(R.string.watering_interval_increase_cd),
                    stateDescription = wateringIntervalLabel
                )
            )
            val adjustment = viewModel.seasonalAdjustment
            if (adjustment != null && form.seasonalHintApplies) {
                Text(
                    text = pluralStringResource(
                        R.plurals.watering_seasonal_adjustment,
                        adjustment.baseDays,
                        stringResource(adjustment.season.sentenceLabelRes()),
                        adjustment.baseDays
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            WateringStrengthSetting(
                mode = wateringStrengthMode(form.pinIntervalToBase, form.wateringAmplitudeOverride),
                customStrength = form.wateringAmplitudeOverride,
                callbacks = WateringStrengthCallbacks(
                    onSelectAppDefault = viewModel::selectAppDefaultStrength,
                    onSelectCustom = viewModel::setWateringAmplitudeOverride,
                    onSelectPin = { viewModel.setPinIntervalToBase(true) }
                )
            )
        }
    }
}

@Composable
internal fun DormancySection(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState) {
    DormancyWindowSetting(
        startMonth = form.dormancyStartMonth,
        endMonth = form.dormancyEndMonth,
        dormantWateringIntervalDays = form.dormantWateringIntervalDays,
        onWindowChange = viewModel::setDormancyWindow
    )
}

@Composable
internal fun FertilizingSection(
    viewModel: AddEditPlantViewModel,
    form: AddEditPlantFormState,
    onLastSeasonLocked: () -> Unit
) {
    Column {
        val fertilizingIntervalLabel = stringResource(R.string.fertilizing_interval_label, form.fertilizingIntervalDays)
        SwitchRow(
            label = if (form.fertilizingIntervalEnabled) {
                fertilizingIntervalLabel
            } else {
                stringResource(R.string.fertilizing_reminder_label)
            },
            checked = form.fertilizingIntervalEnabled,
            onCheckedChange = viewModel::setFertilizingIntervalEnabled
        )
        if (form.fertilizingIntervalEnabled) {
            SteppedSlider(
                value = form.fertilizingIntervalDays,
                range = 1..180,
                callbacks = SteppedSliderCallbacks(onValueChange = viewModel::setFertilizingIntervalDays),
                labels = SteppedSliderLabels(
                    decreaseContentDescription = stringResource(R.string.fertilizing_interval_decrease_cd),
                    increaseContentDescription = stringResource(R.string.fertilizing_interval_increase_cd),
                    stateDescription = fertilizingIntervalLabel
                )
            )
            FertilizingSeasonsSelector(
                selected = form.fertilizingSeasons,
                onToggle = viewModel::toggleFertilizingSeason,
                onLastSeasonLocked = onLastSeasonLocked
            )
            SwitchRow(
                label = stringResource(R.string.liquid_fertilizer_label),
                checked = form.useLiquidFertilizer,
                onCheckedChange = viewModel::setUseLiquidFertilizer
            )
        }
    }
}

@Composable
internal fun RepottingSection(
    viewModel: AddEditPlantViewModel,
    form: AddEditPlantFormState,
    onLastSeasonLocked: () -> Unit
) {
    Column {
        val repottingIntervalLabel = pluralStringResource(
            R.plurals.repotting_interval_label,
            form.repottingIntervalMonths,
            form.repottingIntervalMonths
        )
        SwitchRow(
            label = if (form.repottingIntervalEnabled) {
                repottingIntervalLabel
            } else {
                stringResource(R.string.repotting_reminder_label)
            },
            checked = form.repottingIntervalEnabled,
            onCheckedChange = viewModel::setRepottingIntervalEnabled
        )
        if (form.repottingIntervalEnabled) {
            SteppedSlider(
                value = form.repottingIntervalMonths,
                range = AddEditPlantViewModel.MIN_REPOTTING_MONTHS..AddEditPlantViewModel.MAX_REPOTTING_MONTHS,
                callbacks = SteppedSliderCallbacks(onValueChange = viewModel::setRepottingIntervalMonths),
                labels = SteppedSliderLabels(
                    decreaseContentDescription = stringResource(R.string.repotting_interval_decrease_cd),
                    increaseContentDescription = stringResource(R.string.repotting_interval_increase_cd),
                    stateDescription = repottingIntervalLabel
                )
            )
            FertilizingSeasonsSelector(
                selected = form.repottingSeasons,
                onToggle = viewModel::toggleRepottingSeason,
                onLastSeasonLocked = onLastSeasonLocked,
                labelRes = R.string.repotting_preferred_seasons_label
            )
        }
    }
}

@Composable
internal fun CareSections(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState, actions: FormActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WateringSection(viewModel, form)
        DormancySection(viewModel, form)
        FertilizingSection(viewModel, form, actions.onFertilizingLastSeasonLocked)
        RepottingSection(viewModel, form, actions.onRepottingLastSeasonLocked)
    }
}

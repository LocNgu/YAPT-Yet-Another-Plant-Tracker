package com.yapt.planttracker.ui.screens.addplant

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.wateringStrengthMode
import com.yapt.planttracker.ui.components.WateringStrengthCallbacks
import com.yapt.planttracker.ui.components.WateringStrengthSetting

@Composable
internal fun WateringStrengthSection(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState) {
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

@Composable
internal fun WizardCareSections(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState, actions: FormActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WateringSection(viewModel, form, showStrength = false)
        FertilizingSection(viewModel, form, actions.onFertilizingLastSeasonLocked)
        MoreOptionsHeader(expanded = form.moreOptionsExpanded, onToggle = viewModel::setMoreOptionsExpanded)
        if (form.moreOptionsExpanded) {
            DormancySection(viewModel, form)
            WateringStrengthSection(viewModel, form)
            RepottingSection(viewModel, form, actions.onRepottingLastSeasonLocked)
        }
    }
}

@Composable
private fun MoreOptionsHeader(expanded: Boolean, onToggle: (Boolean) -> Unit) {
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
        Text(text = stringResource(R.string.add_plant_more_options), style = MaterialTheme.typography.titleSmall)
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null
        )
    }
}

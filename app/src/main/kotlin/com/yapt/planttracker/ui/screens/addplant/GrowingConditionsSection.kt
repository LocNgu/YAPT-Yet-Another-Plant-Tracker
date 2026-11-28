package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.LightLevelField
import com.yapt.planttracker.ui.components.PotMaterialField
import com.yapt.planttracker.ui.components.PotSizeField

@Composable
internal fun GrowingConditionsSection(viewModel: AddEditPlantViewModel, form: AddEditPlantFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.growing_conditions_title),
            style = MaterialTheme.typography.titleSmall
        )
        LightLevelField(selected = form.lightLevel, onSelect = viewModel::setLightLevel)
        PotMaterialField(selected = form.potMaterial, onSelect = viewModel::setPotMaterial)
        PotSizeField(text = form.potDiameterText, onTextChange = viewModel::setPotDiameterText)
    }
}

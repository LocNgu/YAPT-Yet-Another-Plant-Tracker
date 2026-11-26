package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R

@Composable
internal fun StepIntro(step: AddPlantStep) {
    Text(
        text = stringResource(step.introRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun AddPlantStep.introRes(): Int = when (this) {
    AddPlantStep.DETAILS -> R.string.add_plant_intro_details
    AddPlantStep.PHOTO -> R.string.add_plant_intro_photo
    AddPlantStep.CARE -> R.string.add_plant_intro_care
    AddPlantStep.REVIEW -> R.string.add_plant_intro_review
}

package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R

@Composable
internal fun WizardProgressHeader(step: AddPlantStep) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(
                text = stringResource(R.string.add_plant_step_progress, step.number, AddPlantStep.COUNT),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = stringResource(step.titleRes()), style = MaterialTheme.typography.titleMedium)
        }
        LinearProgressIndicator(
            progress = { step.number.toFloat() / AddPlantStep.COUNT },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun AddPlantStep.titleRes(): Int = when (this) {
    AddPlantStep.PHOTO -> R.string.add_plant_step_photo
    AddPlantStep.DETAILS -> R.string.add_plant_step_details
    AddPlantStep.CARE -> R.string.add_plant_step_care
    AddPlantStep.REVIEW -> R.string.add_plant_step_review
}

@Composable
internal fun WizardNavigationBar(step: AddPlantStep, onBack: () -> Unit, onNext: () -> Unit, onAdd: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step == AddPlantStep.PHOTO) {
                Spacer(Modifier)
            } else {
                TextButton(onClick = onBack) { Text(stringResource(R.string.add_plant_back)) }
            }
            if (step == AddPlantStep.REVIEW) {
                Button(onClick = onAdd) { Text(stringResource(R.string.add_plant_finish)) }
            } else {
                Button(onClick = onNext) { Text(stringResource(R.string.add_plant_next)) }
            }
        }
    }
}

@Composable
internal fun PhotoStep(form: AddEditPlantFormState, onPickPhoto: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CoverPhotoPicker(uri = form.coverPhotoUri, onPickPhoto = onPickPhoto)
        Text(
            text = stringResource(R.string.add_plant_photo_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ReviewStep(
    viewModel: AddEditPlantViewModel,
    form: AddEditPlantFormState,
    onEditStep: (AddPlantStep) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlantNotesField(viewModel)
        Text(
            text = stringResource(R.string.add_plant_review_heading),
            style = MaterialTheme.typography.titleSmall
        )
        Column {
            SummaryLine(stringResource(photoSummaryRes(form.coverPhotoUri != null))) { onEditStep(AddPlantStep.PHOTO) }
            SummaryLine(stringResource(R.string.add_plant_summary_name, viewModel.name.trim())) {
                onEditStep(AddPlantStep.DETAILS)
            }
            if (viewModel.species.isNotBlank()) {
                SummaryLine(stringResource(R.string.add_plant_summary_species, viewModel.species.trim())) {
                    onEditStep(AddPlantStep.DETAILS)
                }
            }
            if (viewModel.room.isNotBlank()) {
                SummaryLine(stringResource(R.string.add_plant_summary_location, viewModel.room.trim())) {
                    onEditStep(AddPlantStep.DETAILS)
                }
            }
            CareSummaryLines(form) { onEditStep(AddPlantStep.CARE) }
        }
    }
}

private fun photoSummaryRes(hasPhoto: Boolean): Int =
    if (hasPhoto) R.string.add_plant_summary_photo_added else R.string.add_plant_summary_photo_none

@Composable
private fun CareSummaryLines(form: AddEditPlantFormState, onClick: () -> Unit) {
    SummaryLine(
        if (form.wateringIntervalEnabled) {
            stringResource(R.string.add_plant_summary_watering_on, form.wateringIntervalDays)
        } else {
            stringResource(R.string.add_plant_summary_watering_off)
        },
        onClick
    )
    SummaryLine(
        if (form.fertilizingIntervalEnabled) {
            stringResource(R.string.add_plant_summary_fertilizing_on, form.fertilizingIntervalDays)
        } else {
            stringResource(R.string.add_plant_summary_fertilizing_off)
        },
        onClick
    )
    SummaryLine(
        if (form.repottingIntervalEnabled) {
            pluralStringResource(
                R.plurals.add_plant_summary_repotting_on,
                form.repottingIntervalMonths,
                form.repottingIntervalMonths
            )
        } else {
            stringResource(R.string.add_plant_summary_repotting_off)
        },
        onClick
    )
}

@Composable
private fun SummaryLine(text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.add_plant_summary_change),
            role = Role.Button,
            onClick = onClick
        ),
        headlineContent = { Text(text = text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    )
}

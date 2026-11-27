package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yapt.planttracker.R

@Composable
internal fun SpeciesPresetSuggestions(viewModel: AddEditPlantViewModel, presetId: String?) {
    val resources = LocalContext.current.resources
    val species = viewModel.species
    val suggestions = remember(species, LocalConfiguration.current) {
        matchSpeciesPresets(species) { resources.getString(it.nameRes) }
    }
    if (suggestions.isNotEmpty()) {
        val listDescription = stringResource(R.string.preset_suggestions_cd)
        Column(
            modifier = Modifier.semantics {
                contentDescription = listDescription
                collectionInfo = CollectionInfo(rowCount = suggestions.size, columnCount = 1)
            }
        ) {
            Text(
                text = stringResource(R.string.preset_suggestion_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            suggestions.forEachIndexed { index, preset ->
                ListItem(
                    modifier = Modifier
                        .clickable(role = Role.Button) { viewModel.pickPreset(preset.id) }
                        .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) },
                    headlineContent = { Text(stringResource(preset.nameRes)) },
                    supportingContent = { Text(preset.botanicalName) }
                )
            }
        }
    }
    SpeciesPresets.byId(presetId)?.let { preset ->
        Text(
            text = stringResource(R.string.preset_applied_note, stringResource(preset.nameRes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

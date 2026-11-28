package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PotDiameter
import com.yapt.planttracker.domain.model.PotMaterial
import com.yapt.planttracker.ui.components.PotMaterialField
import com.yapt.planttracker.ui.components.PotSizeField
import com.yapt.planttracker.ui.util.labelRes

/** The Home tab's read-only growing-conditions rows (#1003): only set values, nothing when all are unset. */
@Composable
internal fun GrowingConditionsCard(plant: Plant, modifier: Modifier = Modifier) {
    val items = mutableListOf<Pair<String, String>>()
    plant.lightLevel?.let {
        items += stringResource(R.string.growing_conditions_light) to stringResource(it.labelRes())
    }
    val material = plant.potMaterial?.let { stringResource(it.labelRes()) }
    val diameter = plant.potDiameterCm
    val pot = when {
        material != null && diameter != null ->
            stringResource(R.string.growing_conditions_pot_value, material, diameter)
        material != null -> material
        diameter != null -> stringResource(R.string.growing_conditions_pot_size_value, diameter)
        else -> null
    }
    pot?.let { items += stringResource(R.string.growing_conditions_pot_label) to it }
    if (items.isEmpty()) return
    TabInsightsCard(items, modifier)
    Spacer(Modifier.height(16.dp))
}

/** Offered after a repot logged from Plant Detail (#1003); pre-filled with the current pot, Skip changes nothing. */
@Composable
internal fun PotPromptDialog(
    prompt: PotPrompt,
    onSave: (PotMaterial?, String) -> Unit,
    onSkip: () -> Unit
) {
    var material by remember { mutableStateOf(prompt.material) }
    var diameterText by remember { mutableStateOf(prompt.diameterCm?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.repot_pot_prompt_title)) },
        text = {
            Column {
                Text(stringResource(R.string.repot_pot_prompt_message))
                Spacer(Modifier.height(12.dp))
                PotMaterialField(selected = material, onSelect = { material = it })
                Spacer(Modifier.height(12.dp))
                PotSizeField(
                    text = diameterText,
                    onTextChange = { diameterText = it.filter(Char::isDigit).take(3) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(material, diameterText) },
                enabled = PotDiameter.isValidInput(diameterText)
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.repot_pot_prompt_skip)) }
        }
    )
}

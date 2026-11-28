package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.LightLevel
import com.yapt.planttracker.domain.model.PotDiameter
import com.yapt.planttracker.domain.model.PotMaterial
import com.yapt.planttracker.ui.util.labelRes

@Composable
fun LightLevelField(selected: LightLevel?, onSelect: (LightLevel?) -> Unit) {
    ChoiceChips(
        label = stringResource(R.string.growing_conditions_light),
        options = LightLevel.entries,
        selected = selected,
        optionLabel = { stringResource(it.labelRes()) },
        onSelect = onSelect
    )
}

@Composable
fun PotMaterialField(selected: PotMaterial?, onSelect: (PotMaterial?) -> Unit) {
    ChoiceChips(
        label = stringResource(R.string.growing_conditions_pot_material),
        options = PotMaterial.entries,
        selected = selected,
        optionLabel = { stringResource(it.labelRes()) },
        onSelect = onSelect
    )
}

@Composable
fun PotSizeField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val invalid = !PotDiameter.isValidInput(text)
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(stringResource(R.string.growing_conditions_pot_size)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.growing_conditions_pot_size_error)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/** A tap on the selected chip clears it: every growing condition is optional and can be unset again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: @Composable (T) -> String,
    onSelect: (T?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(if (option == selected) null else option) },
                    label = { Text(optionLabel(option)) },
                    colors = yaptFilterChipColors()
                )
            }
        }
    }
}

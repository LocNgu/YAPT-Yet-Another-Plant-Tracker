package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus

/**
 * The shared quick-log button pair used by both the plant-overview list ([PlantCard]) and the
 * calendar day sheet. A water button and a fertilize button, stacked vertically.
 *
 * For liquid-fertilizer plants (`status.plant.useLiquidFertilizer`) the fertilize button becomes a
 * combined water+fertilize button (💧 + 🌿), since fertilizing rides along with watering
 * (product ADR-0008/product ADR-0017); the plain water button remains so the user can water without fertilizing.
 *
 * Keeping this in one place means the two surfaces never drift apart in appearance or behaviour.
 */
@Composable
fun QuickLogButtons(
    status: PlantCareStatus,
    onQuickWater: () -> Unit,
    onQuickFertilize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onQuickWater,
            modifier = Modifier.size(WATER_BUTTON_SIZE)
        ) {
            Icon(
                Icons.Filled.WaterDrop,
                contentDescription = stringResource(R.string.quick_log_water_cd),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onQuickFertilize,
            modifier = Modifier.size(fertilizeButtonSize(status))
        ) {
            if (status.plant.useLiquidFertilizer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.WaterDrop,
                        stringResource(R.string.quick_log_fertilize_cd),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    Icon(
                        Icons.Filled.Spa,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Icon(
                    Icons.Filled.Spa,
                    stringResource(R.string.quick_log_fertilize_cd),
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Takes the exact space of [QuickLogButtons] for the same [status] but draws nothing, has no pointer
 * input and no semantics. [PlantCard] shows it in selection mode so the card keeps its height while
 * taps over that strip reach the card's selection toggle (#990). Hiding the real buttons instead
 * (`alpha(0f)` + `enabled = false`) leaves a dead zone: a disabled `clickable` still consumes the
 * pointer down/up, so the card's `combinedClickable` never sees the gesture.
 */
@Composable
fun QuickLogButtonsPlaceholder(
    status: PlantCareStatus,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.size(WATER_BUTTON_SIZE))
        Spacer(Modifier.size(fertilizeButtonSize(status)))
    }
}

private val WATER_BUTTON_SIZE = 36.dp

private fun fertilizeButtonSize(status: PlantCareStatus): Dp =
    if (status.plant.useLiquidFertilizer) 44.dp else 36.dp

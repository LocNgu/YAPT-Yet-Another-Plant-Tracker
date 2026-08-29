package com.yapt.planttracker.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.util.icon

/**
 * The care types offered as one-tap bulk actions in [BulkActionBar]. NOTE and PHOTO are excluded
 * because they require per-plant input (free text / an image) that a fire-and-forget bulk action
 * can't supply.
 */
private val BULK_CARE_TYPES = listOf(
    CareType.WATER,
    CareType.FERTILIZE,
    CareType.PRUNE,
    CareType.MIST,
    CareType.REPOT
)

@StringRes
private fun CareType.bulkActionLabelRes(): Int = when (this) {
    CareType.WATER -> R.string.bulk_action_water
    CareType.FERTILIZE -> R.string.bulk_action_fertilize
    CareType.PRUNE -> R.string.bulk_action_prune
    CareType.MIST -> R.string.bulk_action_mist
    CareType.REPOT -> R.string.bulk_action_repot
    // Not offered in bulk (see BULK_CARE_TYPES); fall back to the water label defensively.
    CareType.NOTE, CareType.PHOTO, CareType.CUSTOM, CareType.CHECK -> R.string.bulk_action_water
}

/**
 * Persistent bottom action sheet shown while the plant list is in multi-select mode. It slides up
 * the moment the first plant is marked (see `PlantListScreen`) and stays up — non-modal, so the list
 * above remains scrollable and tappable and the user can keep adding/removing plants before acting.
 *
 * Compact by design so it leaves as much room as possible for the list above: the care actions are a
 * horizontally scrollable chip row (mirroring the care-type selector on the Add Care Log screen) and
 * the destructive "Move to Graveyard" action sits inline with the selected-count header. Care actions
 * log directly with sensible defaults (watering uses `JUST_RIGHT` feedback) and don't raise the
 * per-plant interval-suggestion or photo-reminder dialogs, which would stack up once per plant.
 */
@Composable
fun BulkActionBar(
    selectedCount: Int,
    onCareAction: (CareType) -> Unit,
    onMoveToGraveyard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // Drag-handle-style pill, echoing the pull-up sheet look.
            Spacer(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.bulk_action_bar_title,
                        selectedCount,
                        selectedCount
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onMoveToGraveyard,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.bulk_action_move_to_graveyard))
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                items(BULK_CARE_TYPES) { type ->
                    AssistChip(
                        onClick = { onCareAction(type) },
                        label = { Text(stringResource(type.bulkActionLabelRes())) },
                        leadingIcon = {
                            Icon(
                                imageVector = type.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
        }
    }
}

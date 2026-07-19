package com.yapt.planttracker.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    CareType.NOTE, CareType.PHOTO -> R.string.bulk_action_water
}

/**
 * Persistent bottom action sheet shown while the plant list is in multi-select mode. It slides up
 * the moment the first plant is marked (see `PlantListScreen`) and stays up — non-modal, so the list
 * above remains scrollable and tappable and the user can keep adding/removing plants before acting.
 *
 * Offers a one-tap bulk care action (Water, Fertilize, Prune, Mist, Repot) plus a destructive
 * "Move to Graveyard" action, each applied to every currently selected plant. The care actions log
 * directly with sensible defaults (watering uses `JUST_RIGHT` feedback) and do not raise the
 * per-plant interval-suggestion or photo-reminder dialogs — those would stack up once per plant and
 * overwhelm a bulk operation.
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
                .padding(bottom = 8.dp)
        ) {
            // Drag-handle-style pill, echoing the pull-up sheet look.
            Spacer(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Text(
                text = pluralStringResource(
                    R.plurals.bulk_action_sheet_title,
                    selectedCount,
                    selectedCount
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            BULK_CARE_TYPES.forEach { type ->
                BulkActionRow(
                    icon = type.icon(),
                    label = stringResource(type.bulkActionLabelRes()),
                    onClick = { onCareAction(type) }
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            BulkActionRow(
                icon = Icons.Filled.DeleteOutline,
                label = stringResource(R.string.bulk_action_move_to_graveyard),
                tint = MaterialTheme.colorScheme.error,
                onClick = onMoveToGraveyard
            )
        }
    }
}

@Composable
private fun BulkActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.util.DateUtils

/**
 * Plant Detail's Photo tab quick action (#694) — extracted to its own file, a sibling of
 * `CareDatePicker.kt`/`WateringDueActions.kt`, since `PlantDetailScreen.kt` is already ~1580 lines and
 * would trip Detekt's per-file `TooManyFunctions` threshold otherwise (see `.claude/rules/plant-detail.md`).
 */

internal const val ADD_PHOTO_SHEET_TEST_TAG = "add_photo_sheet"
internal const val ADD_PHOTO_DATE_ROW_TEST_TAG = "add_photo_date_row"

/**
 * One sheet combining date entry with both photo sources (#694, product ADR-0038) — replacing the
 * Photo tab's earlier navigate-to-`AddCareLogScreen` behavior (#658/#693). [loggedAt] defaults to
 * today and is editable in place via [onLoggedAtChange] before either source is chosen; whichever
 * source returns an image, the caller logs the resulting PHOTO [com.yapt.planttracker.domain.model
 * .CareLog] at this same date — see [PlantDetailViewModel.savePhotoLog].
 *
 * Two internal content states swapped inside **one** `ModalBottomSheet`, never two nested sheets: the
 * default state ([AddPhotoOptions]) shows the date row plus the two source [ListItem]s; tapping the
 * date row swaps in [CareDatePickerContent] in place. `skipPartiallyExpanded = true` mirrors
 * [CareDatePickerBottomSheet] (product ADR-0037) — the date-edit state hosts the same full `DatePicker`
 * calendar grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddPhotoBottomSheet(
    loggedAt: Long,
    onLoggedAtChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(ADD_PHOTO_SHEET_TEST_TAG)
    ) {
        if (showDatePicker) {
            Column(modifier = Modifier.fillMaxWidth()) {
                CareDatePickerContent(
                    initialSelectedDateMillis = localDayToUtcMidnightMillis(loggedAt),
                    onCancel = { showDatePicker = false },
                    onConfirm = { newLoggedAt ->
                        onLoggedAtChange(newLoggedAt)
                        showDatePicker = false
                    }
                )
            }
        } else {
            AddPhotoOptions(
                loggedAt = loggedAt,
                onDateClick = { showDatePicker = true },
                onTakePhoto = onTakePhoto,
                onChooseGallery = onChooseGallery
            )
        }
    }
}

/** [AddPhotoBottomSheet]'s default content state — split out to stay under Detekt's `LongMethod` threshold. */
@Composable
private fun AddPhotoOptions(
    loggedAt: Long,
    onDateClick: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.plant_detail_action_add_photo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onDateClick() }
                .testTag(ADD_PHOTO_DATE_ROW_TEST_TAG)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateUtils.formatDate(loggedAt),
                    style = MaterialTheme.typography.bodyLarge
                )
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = stringResource(R.string.cd_pick_date),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.photo_source_take_photo)) },
            leadingContent = {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = stringResource(R.string.photo_source_take_photo)
                )
            },
            modifier = Modifier.clickable(role = Role.Button) { onTakePhoto() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.photo_source_choose_gallery)) },
            leadingContent = {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = stringResource(R.string.photo_source_choose_gallery)
                )
            },
            modifier = Modifier.clickable(role = Role.Button) { onChooseGallery() }
        )
    }
}

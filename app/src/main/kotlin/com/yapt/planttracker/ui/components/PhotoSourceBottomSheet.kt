package com.yapt.planttracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourceBottomSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.photo_source_take_photo)) },
            leadingContent = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
            modifier = Modifier.clickable { onTakePhoto() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.photo_source_choose_gallery)) },
            leadingContent = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            modifier = Modifier.clickable { onChooseGallery() }
        )
    }
}

package com.yapt.planttracker.ui.screens.addplant

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.PhotoSourceBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.ui.util.showSnackbarOnce
import kotlinx.coroutines.launch

@Composable
internal fun rememberFormActions(viewModel: AddEditPlantViewModel, snackbarHostState: SnackbarHostState): FormActions {
    val onPickPhoto = rememberPhotoPicker(viewModel, snackbarHostState)
    val fertilizingLocked = rememberLastSeasonLock(snackbarHostState, R.string.fertilizing_last_season_locked_snackbar)
    val repottingLocked = rememberLastSeasonLock(snackbarHostState, R.string.repotting_last_season_locked_snackbar)
    return FormActions(onPickPhoto, fertilizingLocked, repottingLocked)
}

@Composable
private fun rememberLastSeasonLock(snackbarHostState: SnackbarHostState, messageRes: Int): () -> Unit {
    val scope = rememberCoroutineScope()
    val message = stringResource(messageRes)
    return { scope.launch { snackbarHostState.showSnackbarOnce(message) } }
}

@Composable
private fun rememberPhotoPicker(viewModel: AddEditPlantViewModel, snackbarHostState: SnackbarHostState): () -> Unit {
    val context = LocalContext.current
    var showPhotoSourceSheet by remember { mutableStateOf(false) }
    val cameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        viewModel.addPhoto(uri.toString())
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            cameraState.onGallerySelected()
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            viewModel.addPhoto(it.toString())
        }
    }
    CameraPhotoDialogs(cameraState)
    if (showPhotoSourceSheet) {
        PhotoSourceBottomSheet(
            onDismiss = { showPhotoSourceSheet = false },
            onTakePhoto = {
                showPhotoSourceSheet = false
                cameraState.launch()
            },
            onChooseGallery = {
                showPhotoSourceSheet = false
                cameraState.onGallerySelected()
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
    }
    return { showPhotoSourceSheet = true }
}

@Composable
internal fun ArchiveDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_plant)) },
        text = { Text(stringResource(R.string.archive_plant_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.move_to_graveyard)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
internal fun DiscardDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_plant_discard_title)) },
        text = { Text(stringResource(R.string.add_plant_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.add_plant_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_plant_discard_keep_editing)) }
        }
    )
}

@Composable
internal fun SaveFab(onSave: () -> Unit) {
    FloatingActionButton(onClick = onSave) {
        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save))
    }
}

package com.yapt.planttracker.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yapt.planttracker.R
import com.yapt.planttracker.util.ImageUtils
import com.yapt.planttracker.util.findActivity
import java.io.File
import kotlinx.coroutines.launch

class CameraPhotoState {
    var showPermissionRationale by mutableStateOf(false)
        internal set
    var showPermissionDenied by mutableStateOf(false)
        internal set
    internal var pendingCameraFile: File? = null
    internal var pendingCameraUri: Uri? = null
    internal var onLaunch: () -> Unit = {}
    internal var onRationaleConfirmed: () -> Unit = {}

    fun launch() = onLaunch()

    fun onGallerySelected() {
        pendingCameraFile?.delete()
        pendingCameraFile = null
        pendingCameraUri = null
    }
}

@Composable
fun rememberCameraPhotoState(
    snackbarHostState: SnackbarHostState,
    onPhotoTaken: (Uri) -> Unit
): CameraPhotoState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noCameraMessage = stringResource(R.string.camera_not_available)
    val hasCameraHardware = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val state = remember { CameraPhotoState() }
    val currentOnPhotoTaken by rememberUpdatedState(onPhotoTaken)

    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            state.pendingCameraUri?.let { currentOnPhotoTaken(it) }
        } else {
            state.pendingCameraFile?.delete()
        }
        state.pendingCameraFile = null
        state.pendingCameraUri = null
    }

    fun launchCamera() {
        try {
            state.pendingCameraFile?.delete()
            val file = ImageUtils.createCameraImageFile(context)
            state.pendingCameraFile = file
            val uri = ImageUtils.createCameraImageUri(context, file)
            state.pendingCameraUri = uri
            cameraCaptureLauncher.launch(uri)
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar(noCameraMessage) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            val activity = context.findActivity()
            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.CAMERA
                )
            ) {
                state.showPermissionRationale = true
            } else {
                state.showPermissionDenied = true
            }
        }
    }

    state.onRationaleConfirmed = {
        state.showPermissionRationale = false
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    state.onLaunch = {
        if (!hasCameraHardware) {
            scope.launch { snackbarHostState.showSnackbar(noCameraMessage) }
        } else when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> launchCamera()
            context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } == true -> state.showPermissionRationale = true
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    return state
}

@Composable
fun CameraPhotoDialogs(state: CameraPhotoState) {
    if (state.showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { state.showPermissionRationale = false },
            title = { Text(stringResource(R.string.camera_permission_rationale_title)) },
            text = { Text(stringResource(R.string.camera_permission_rationale_text)) },
            confirmButton = {
                TextButton(onClick = state.onRationaleConfirmed) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.showPermissionRationale = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.showPermissionDenied) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { state.showPermissionDenied = false },
            title = { Text(stringResource(R.string.camera_permission_denied_title)) },
            text = { Text(stringResource(R.string.camera_permission_denied_text)) },
            confirmButton = {
                TextButton(onClick = {
                    state.showPermissionDenied = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.camera_permission_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { state.showPermissionDenied = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

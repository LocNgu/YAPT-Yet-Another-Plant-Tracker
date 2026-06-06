package com.yapt.planttracker.ui.screens.addplant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.PhotoSourceBottomSheet
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.util.ImageUtils
import com.yapt.planttracker.util.findActivity
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditPlantScreen(
    viewModel: AddEditPlantViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current

    var showPhotoSourceSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showPermissionDenied by remember { mutableStateOf(false) }
    val hasCameraHardware = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val noCameraMessage = stringResource(R.string.camera_not_available)

    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { viewModel.coverPhotoUri = it.toString() }
            pendingCameraFile = null
            pendingCameraUri = null
        } else {
            pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
        }
    }

    val launchCamera = {
        try {
            // Delete the previous camera-captured file if the user is replacing it.
            pendingCameraFile?.delete()
            val file = ImageUtils.createCameraImageFile(context)
            pendingCameraFile = file
            val uri = ImageUtils.createCameraImageUri(context, file)
            pendingCameraUri = uri
            cameraCaptureLauncher.launch(uri)
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar(noCameraMessage) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            val activity = context.findActivity()
            if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.CAMERA
                )
            ) {
                showPermissionRationale = true
            } else {
                showPermissionDenied = true
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            // Switching to a gallery photo; discard any uncommitted camera file.
            pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            viewModel.coverPhotoUri = it.toString()
        }
    }

    fun onTakePhotoTapped() {
        if (!hasCameraHardware) {
            scope.launch { snackbarHostState.showSnackbar(noCameraMessage) }
            return
        }
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> launchCamera()
            context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } == true -> showPermissionRationale = true
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddEditPlantViewModel.Event.Saved -> onNavigateBack()
                is AddEditPlantViewModel.Event.Deleted -> onNavigateBack()
                is AddEditPlantViewModel.Event.ValidationError ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_plant)) },
            text = { Text(stringResource(R.string.delete_plant_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deletePlant()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text(stringResource(R.string.camera_permission_rationale_title)) },
            text = { Text(stringResource(R.string.camera_permission_rationale_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showPermissionDenied) {
        AlertDialog(
            onDismissRequest = { showPermissionDenied = false },
            title = { Text(stringResource(R.string.camera_permission_denied_title)) },
            text = { Text(stringResource(R.string.camera_permission_denied_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDenied = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.camera_permission_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDenied = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showPhotoSourceSheet) {
        PhotoSourceBottomSheet(
            onDismiss = { showPhotoSourceSheet = false },
            onTakePhoto = {
                showPhotoSourceSheet = false
                onTakePhotoTapped()
            },
            onChooseGallery = {
                showPhotoSourceSheet = false
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditMode) stringResource(R.string.edit_plant) else stringResource(R.string.add_plant)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (viewModel.isEditMode) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.save() }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PlantPhoto(
                    uri = viewModel.coverPhotoUri,
                    size = 120.dp,
                    rounded = true
                )
                FloatingActionButton(
                    onClick = { showPhotoSourceSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.AddAPhoto,
                        contentDescription = stringResource(R.string.cd_add_photo),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                label = { Text(stringResource(R.string.field_plant_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = viewModel.species,
                onValueChange = { viewModel.species = it },
                label = { Text(stringResource(R.string.field_species)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = viewModel.room,
                onValueChange = { viewModel.room = it },
                label = { Text(stringResource(R.string.field_location)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.placeholder_location)) }
            )

            if (rooms.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rooms.forEach { chip ->
                        val isMatch = chip.equals(viewModel.room, ignoreCase = true) && chip != viewModel.room
                        SuggestionChip(
                            onClick = {
                                viewModel.room = chip
                                keyboardController?.hide()
                            },
                            label = { Text(chip) },
                            colors = if (isMatch)
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            else SuggestionChipDefaults.suggestionChipColors()
                        )
                    }
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (viewModel.wateringIntervalEnabled)
                            stringResource(R.string.watering_interval_label, viewModel.wateringIntervalDays)
                        else stringResource(R.string.watering_reminder_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = viewModel.wateringIntervalEnabled,
                        onCheckedChange = { viewModel.wateringIntervalEnabled = it }
                    )
                }
                if (viewModel.wateringIntervalEnabled) {
                    Slider(
                        value = viewModel.wateringIntervalDays.toFloat(),
                        onValueChange = { viewModel.wateringIntervalDays = it.roundToInt() },
                        valueRange = 1f..60f,
                        steps = 58
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (viewModel.fertilizingIntervalEnabled)
                            stringResource(R.string.fertilizing_interval_label, viewModel.fertilizingIntervalDays)
                        else stringResource(R.string.fertilizing_reminder_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = viewModel.fertilizingIntervalEnabled,
                        onCheckedChange = { viewModel.fertilizingIntervalEnabled = it }
                    )
                }
                if (viewModel.fertilizingIntervalEnabled) {
                    Slider(
                        value = viewModel.fertilizingIntervalDays.toFloat(),
                        onValueChange = { viewModel.fertilizingIntervalDays = it.roundToInt() },
                        valueRange = 1f..90f,
                        steps = 88
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.liquid_fertilizer_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = viewModel.useLiquidFertilizer,
                            onCheckedChange = { viewModel.useLiquidFertilizer = it }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = { viewModel.notes = it },
                label = { Text(stringResource(R.string.field_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6
            )

            Spacer(Modifier.height(72.dp))
        }
    }
}

package com.yapt.planttracker.ui.screens.addplant

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.DormancyWindowSetting
import com.yapt.planttracker.ui.components.FertilizingSeasonsSelector
import com.yapt.planttracker.ui.components.PhotoSourceBottomSheet
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.SteppedSlider
import com.yapt.planttracker.ui.components.SteppedSliderCallbacks
import com.yapt.planttracker.ui.components.SteppedSliderLabels
import com.yapt.planttracker.ui.components.rememberCameraPhotoState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditPlantScreen(
    viewModel: AddEditPlantViewModel,
    onNavigateBack: () -> Unit,
    onPlantArchived: (plantId: Long, plantName: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current

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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddEditPlantViewModel.Event.Saved -> onNavigateBack()
                is AddEditPlantViewModel.Event.ArchivedForUndo ->
                    onPlantArchived(event.plantId, event.plantName)
                is AddEditPlantViewModel.Event.ValidationError ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.archive_plant)) },
            text = { Text(stringResource(R.string.archive_plant_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deletePlant()
                }) { Text(stringResource(R.string.move_to_graveyard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isEditMode) {
                            stringResource(
                                R.string.edit_plant
                            )
                        } else {
                            stringResource(R.string.add_plant)
                        }
                    )
                },
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
                            colors = if (isMatch) {
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else {
                                SuggestionChipDefaults.suggestionChipColors()
                            }
                        )
                    }
                }
            }

            Column {
                val wateringIntervalLabel =
                    stringResource(R.string.watering_interval_label, viewModel.wateringIntervalDays)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (viewModel.wateringIntervalEnabled) {
                            wateringIntervalLabel
                        } else {
                            stringResource(R.string.watering_reminder_label)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = viewModel.wateringIntervalEnabled,
                        onCheckedChange = { viewModel.wateringIntervalEnabled = it }
                    )
                }
                if (viewModel.wateringIntervalEnabled) {
                    SteppedSlider(
                        value = viewModel.wateringIntervalDays,
                        range = 1..60,
                        callbacks = SteppedSliderCallbacks(
                            onValueChange = { viewModel.wateringIntervalDays = it }
                        ),
                        labels = SteppedSliderLabels(
                            decreaseContentDescription = stringResource(R.string.watering_interval_decrease_cd),
                            increaseContentDescription = stringResource(R.string.watering_interval_increase_cd),
                            stateDescription = wateringIntervalLabel
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pin_interval_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = viewModel.pinIntervalToBase,
                            onCheckedChange = { viewModel.pinIntervalToBase = it }
                        )
                    }
                    Text(
                        text = stringResource(R.string.pin_interval_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DormancyWindowSetting(
                startMonth = viewModel.dormancyStartMonth,
                endMonth = viewModel.dormancyEndMonth,
                dormantWateringIntervalDays = viewModel.dormantWateringIntervalDays,
                onWindowChange = viewModel::setDormancyWindow
            )

            Column {
                val fertilizingIntervalLabel =
                    stringResource(R.string.fertilizing_interval_label, viewModel.fertilizingIntervalDays)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (viewModel.fertilizingIntervalEnabled) {
                            fertilizingIntervalLabel
                        } else {
                            stringResource(R.string.fertilizing_reminder_label)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = viewModel.fertilizingIntervalEnabled,
                        onCheckedChange = { viewModel.fertilizingIntervalEnabled = it }
                    )
                }
                if (viewModel.fertilizingIntervalEnabled) {
                    SteppedSlider(
                        value = viewModel.fertilizingIntervalDays,
                        range = 1..180,
                        callbacks = SteppedSliderCallbacks(
                            onValueChange = { viewModel.fertilizingIntervalDays = it }
                        ),
                        labels = SteppedSliderLabels(
                            decreaseContentDescription = stringResource(R.string.fertilizing_interval_decrease_cd),
                            increaseContentDescription = stringResource(R.string.fertilizing_interval_increase_cd),
                            stateDescription = fertilizingIntervalLabel
                        )
                    )
                    FertilizingSeasonsSelector(
                        selected = viewModel.fertilizingSeasons,
                        onChange = viewModel::updateFertilizingSeasons
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

            Column {
                val repottingIntervalLabel = pluralStringResource(
                    R.plurals.repotting_interval_label,
                    viewModel.repottingIntervalMonths,
                    viewModel.repottingIntervalMonths
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (viewModel.repottingIntervalEnabled) {
                            repottingIntervalLabel
                        } else {
                            stringResource(R.string.repotting_reminder_label)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = viewModel.repottingIntervalEnabled,
                        onCheckedChange = { viewModel.repottingIntervalEnabled = it }
                    )
                }
                if (viewModel.repottingIntervalEnabled) {
                    val minMonths = AddEditPlantViewModel.MIN_REPOTTING_MONTHS
                    val maxMonths = AddEditPlantViewModel.MAX_REPOTTING_MONTHS
                    SteppedSlider(
                        value = viewModel.repottingIntervalMonths,
                        range = minMonths..maxMonths,
                        callbacks = SteppedSliderCallbacks(
                            onValueChange = { viewModel.repottingIntervalMonths = it }
                        ),
                        labels = SteppedSliderLabels(
                            decreaseContentDescription = stringResource(R.string.repotting_interval_decrease_cd),
                            increaseContentDescription = stringResource(R.string.repotting_interval_increase_cd),
                            stateDescription = repottingIntervalLabel
                        )
                    )
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

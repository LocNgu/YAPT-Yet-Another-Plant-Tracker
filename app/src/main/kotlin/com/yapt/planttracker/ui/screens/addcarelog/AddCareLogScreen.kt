package com.yapt.planttracker.ui.screens.addcarelog

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.PhotoSourceBottomSheet
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import com.yapt.planttracker.ui.util.icon
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.DateUtils
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun AddCareLogScreen(
    viewModel: AddCareLogViewModel,
    onNavigateBack: (suggestedInterval: Int?, suggestedBaseInterval: Double?) -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    // Keyed on isLoaded so the picker re-initializes once the async load completes, picking up the
    // log's original loggedAt instead of "now".
    val datePickerState = key(viewModel.isLoaded) {
        rememberDatePickerState(initialSelectedDateMillis = viewModel.loggedAt)
    }

    var showPhotoSourceSheet by remember { mutableStateOf(false) }

    val cameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        viewModel.photoUri = uri.toString()
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
            viewModel.photoUri = it.toString()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddCareLogViewModel.Event.Saved ->
                    onNavigateBack(event.suggestedWateringInterval, event.suggestedWateringBaseInterval)
                is AddCareLogViewModel.Event.NavigateBack ->
                    onNavigateBack(null, null)
            }
        }
    }

    // A duplicate-log error is specific to the date that triggered it; clear it as soon as the user
    // changes it so a fixed re-attempt isn't blocked by stale error text (#509).
    LaunchedEffect(viewModel.loggedAt) {
        viewModel.clearDuplicateLogError()
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                        // selectedDateMillis is UTC midnight; convert to local date and
                        // preserve the original time-of-day from loggedAt.
                        val pickerCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        pickerCal.timeInMillis = utcMidnightMs
                        val localCal = Calendar.getInstance()
                        localCal.timeInMillis = viewModel.loggedAt
                        localCal.set(Calendar.YEAR, pickerCal.get(Calendar.YEAR))
                        localCal.set(Calendar.MONTH, pickerCal.get(Calendar.MONTH))
                        localCal.set(Calendar.DAY_OF_MONTH, pickerCal.get(Calendar.DAY_OF_MONTH))
                        viewModel.loggedAt = localCal.timeInMillis
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.care_log_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = { onNavigateBack(null, null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        floatingActionButton = {
            val isSaveEnabled = viewModel.isLoaded && !(viewModel.careType == CareType.PHOTO && viewModel.photoUri == null)
            FloatingActionButton(
                onClick = { if (isSaveEnabled) viewModel.saveLog() },
                modifier = Modifier.alpha(if (isSaveEnabled) 1f else 0.38f)
            ) {
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
            if (!viewModel.isLoaded) return@Column

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateUtils.formatDate(viewModel.loggedAt),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.cd_pick_date),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = viewModel.careType.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(viewModel.careType.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }

            viewModel.duplicateLogError?.let { errorRes ->
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (viewModel.careType == CareType.WATER) {
                // Single optional flag, not a 3-way chip group (#570, product ADR-0027): "was the
                // soil dry when watered" is the only signal a WATER log can add that isn't already
                // captured by the check-first flow's own Watered/Still-moist actions or the
                // timestamp itself. Nothing pre-selected.
                FilterChip(
                    selected = viewModel.selectedFeedback == WateringFeedback.TOO_LATE,
                    onClick = {
                        viewModel.selectedFeedback =
                            if (viewModel.selectedFeedback == WateringFeedback.TOO_LATE) null else WateringFeedback.TOO_LATE
                    },
                    label = { Text(stringResource(R.string.care_log_feedback_plant_needed_it)) },
                    colors = yaptFilterChipColors()
                )
            }

            if (viewModel.careType == CareType.FERTILIZE) {
                Column {
                    Text(
                        text = stringResource(R.string.care_log_fertilizer_type_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            FertilizerType.LIQUID to stringResource(R.string.fertilizer_type_liquid),
                            FertilizerType.SOLID to stringResource(R.string.fertilizer_type_solid)
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = viewModel.selectedFertilizerType == type,
                                onClick = {
                                    viewModel.selectedFertilizerType =
                                        if (viewModel.selectedFertilizerType == type) FertilizerType.UNSPECIFIED else type
                                },
                                label = { Text(label) },
                                colors = yaptFilterChipColors()
                            )
                        }
                    }
                }
            }

            if (viewModel.careType in listOf(CareType.WATER, CareType.FERTILIZE)) {
                OutlinedTextField(
                    value = viewModel.amount,
                    onValueChange = { viewModel.amount = it },
                    label = { Text(stringResource(R.string.field_amount_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.placeholder_amount)) },
                    singleLine = true
                )
            }

            Column {
                Text(
                    text = if (viewModel.careType == CareType.PHOTO) {
                        stringResource(R.string.care_log_photo_label_required)
                    } else {
                        stringResource(R.string.care_log_photo_label)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (viewModel.photoUri != null) {
                    PlantPhoto(
                        uri = viewModel.photoUri,
                        size = 72.dp,
                        rounded = false
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // A PHOTO log reveals the two source actions inline (Take photo /
                // Choose from gallery) as full-width buttons so the user goes straight
                // to the camera or picker with no intermediate sheet. Other care types
                // keep the compact icon that opens the source sheet, since a photo is
                // only an optional attachment there (#443).
                AnimatedContent(
                    targetState = viewModel.careType == CareType.PHOTO,
                    label = "photoSourceActions"
                ) { isPhotoCareType ->
                    if (isPhotoCareType) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { cameraState.launch() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.photo_source_take_photo))
                            }
                            FilledTonalButton(
                                onClick = {
                                    cameraState.onGallerySelected()
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.photo_source_choose_gallery))
                            }
                        }
                    } else {
                        IconButton(onClick = { showPhotoSourceSheet = true }) {
                            Icon(
                                Icons.Filled.AddAPhoto,
                                contentDescription = stringResource(R.string.cd_add_photo),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = { viewModel.notes = it },
                label = { Text(stringResource(R.string.field_notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            Spacer(Modifier.height(72.dp))
        }
    }
}

package com.yapt.planttracker.ui.screens.addcarelog

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.ui.components.CareTypeChip
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.util.DateUtils
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCareLogScreen(
    viewModel: AddCareLogViewModel,
    onNavigateBack: (suggestedInterval: Int?) -> Unit
) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }

    // Keyed on isLoaded so in edit mode the picker re-initializes once the async
    // load completes, picking up the log's original loggedAt instead of "now".
    val datePickerState = key(viewModel.isLoaded) {
        rememberDatePickerState(initialSelectedDateMillis = viewModel.loggedAt)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            viewModel.photoUri = it.toString()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddCareLogViewModel.Event.Saved ->
                    onNavigateBack(event.suggestedWateringInterval)
                is AddCareLogViewModel.Event.NavigateBack ->
                    onNavigateBack(null)
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                        // selectedDateMillis is UTC midnight; convert to local date.
                        // For edits, preserve the original time-of-day from loggedAt.
                        // For new logs, use the current wall-clock time so the
                        // timestamp reflects when the user confirmed, not screen-open time.
                        val pickerCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        pickerCal.timeInMillis = utcMidnightMs
                        val localCal = Calendar.getInstance()
                        localCal.timeInMillis =
                            if (viewModel.isEditMode) viewModel.loggedAt
                            else System.currentTimeMillis()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditMode) stringResource(R.string.care_log_title_edit) else stringResource(R.string.care_log_title_add)) },
                navigationIcon = {
                    IconButton(onClick = { onNavigateBack(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.saveLog() }) {
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
            if (!viewModel.isEditMode || viewModel.isLoaded) {
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
            }

            Text(
                text = stringResource(R.string.care_log_prompt_what_did_you_do),
                style = MaterialTheme.typography.titleMedium
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                items(CareType.entries) { type ->
                    CareTypeChip(
                        careType = type,
                        selected = viewModel.selectedCareType == type,
                        onClick = {
                            viewModel.selectedCareType = type
                            viewModel.selectedFeedback = if (type == CareType.WATER) WateringFeedback.JUST_RIGHT else null
                        }
                    )
                }
            }

            if (viewModel.selectedCareType == CareType.WATER) {
                Column {
                    Text(
                        text = stringResource(R.string.care_log_prompt_how_was_soil),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WateringFeedback.entries.forEach { feedback ->
                            FilterChip(
                                selected = viewModel.selectedFeedback == feedback,
                                onClick = {
                                    viewModel.selectedFeedback =
                                        if (viewModel.selectedFeedback == feedback) null else feedback
                                },
                                label = { Text("${feedback.emoji} ${feedback.displayName}") }
                            )
                        }
                    }
                }
            }

            if (viewModel.selectedCareType == CareType.FERTILIZE) {
                Column {
                    Text(
                        text = stringResource(R.string.care_log_fertilizer_type_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(FertilizerType.LIQUID to stringResource(R.string.fertilizer_type_liquid), FertilizerType.SOLID to stringResource(R.string.fertilizer_type_solid)).forEach { (type, label) ->
                            FilterChip(
                                selected = viewModel.selectedFertilizerType == type,
                                onClick = {
                                    viewModel.selectedFertilizerType =
                                        if (viewModel.selectedFertilizerType == type) FertilizerType.UNSPECIFIED else type
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (viewModel.selectedFertilizerType == FertilizerType.LIQUID) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.fertilizer_also_logs_watering),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (viewModel.selectedCareType in listOf(CareType.WATER, CareType.FERTILIZE)) {
                OutlinedTextField(
                    value = viewModel.amount,
                    onValueChange = { viewModel.amount = it },
                    label = { Text(stringResource(R.string.field_amount_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.placeholder_amount)) },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = { viewModel.notes = it },
                label = { Text(stringResource(R.string.field_notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            Column {
                Text(
                    text = stringResource(R.string.care_log_photo_label),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (viewModel.photoUri != null) {
                        PlantPhoto(
                            uri = viewModel.photoUri,
                            size = 72.dp,
                            rounded = false
                        )
                    }
                    IconButton(onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = stringResource(R.string.cd_add_photo),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(72.dp))
        }
    }
}

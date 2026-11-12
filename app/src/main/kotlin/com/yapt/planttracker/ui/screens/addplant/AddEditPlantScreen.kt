package com.yapt.planttracker.ui.screens.addplant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R

internal data class FormActions(
    val onPickPhoto: () -> Unit,
    val onFertilizingLastSeasonLocked: () -> Unit,
    val onRepottingLastSeasonLocked: () -> Unit
)

internal data class FormNavigation(
    val onNavigateBack: () -> Unit,
    val onPlantArchived: (plantId: Long, plantName: String) -> Unit,
    val onPlantHidden: (plantId: Long, plantName: String) -> Unit
)

@Composable
fun AddEditPlantScreen(
    viewModel: AddEditPlantViewModel,
    onNavigateBack: () -> Unit,
    onPlantArchived: (plantId: Long, plantName: String) -> Unit = { _, _ -> },
    onPlantHidden: (plantId: Long, plantName: String) -> Unit = { _, _ -> }
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val form by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val isWizard = !viewModel.isEditMode

    PlantFormEvents(viewModel, snackbarHostState, FormNavigation(onNavigateBack, onPlantArchived, onPlantHidden))
    val actions = rememberFormActions(viewModel, snackbarHostState)

    val handleBack = {
        if (!isWizard) {
            onNavigateBack()
        } else if (!viewModel.goBack()) {
            if (viewModel.hasUnsavedInput) showDiscardDialog = true else onNavigateBack()
        }
    }
    if (isWizard) BackHandler(onBack = handleBack)

    if (showDeleteDialog) {
        ArchiveDialog(onDismiss = { showDeleteDialog = false }, onConfirm = {
            showDeleteDialog = false
            viewModel.deletePlant()
        })
    }
    if (showDiscardDialog) {
        DiscardDialog(onDismiss = { showDiscardDialog = false }, onConfirm = {
            showDiscardDialog = false
            onNavigateBack()
        })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PlantFormTopBar(
                isEditMode = viewModel.isEditMode,
                step = form.step,
                onBack = handleBack,
                onDelete = { showDeleteDialog = true }
            )
        },
        bottomBar = {
            if (isWizard) WizardNavigationBar(form.step, handleBack, viewModel::goNext, viewModel::save)
        },
        floatingActionButton = { if (!isWizard) SaveFab(viewModel::save) }
    ) { padding ->
        FormScrollBody(padding) {
            if (isWizard) {
                WizardStepContent(viewModel, form, rooms, actions)
            } else {
                EditForm(viewModel, form, rooms, actions)
            }
        }
    }
}

@Composable
private fun FormScrollBody(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun PlantFormEvents(
    viewModel: AddEditPlantViewModel,
    snackbarHostState: SnackbarHostState,
    navigation: FormNavigation
) {
    val resources = LocalResources.current
    val unhiddenMessage = stringResource(R.string.snackbar_plant_unhidden)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddEditPlantViewModel.Event.Saved -> navigation.onNavigateBack()
                is AddEditPlantViewModel.Event.ArchivedForUndo ->
                    navigation.onPlantArchived(event.plantId, event.plantName)
                is AddEditPlantViewModel.Event.HiddenForUndo ->
                    navigation.onPlantHidden(event.plantId, event.plantName)
                is AddEditPlantViewModel.Event.Unhidden ->
                    snackbarHostState.showSnackbar(unhiddenMessage)
                is AddEditPlantViewModel.Event.ValidationError ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
            }
        }
    }
}

/** Camera/gallery plumbing for the cover photo; returns the action that opens the source sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlantFormTopBar(isEditMode: Boolean, step: AddPlantStep, onBack: () -> Unit, onDelete: () -> Unit) {
    Column {
        TopAppBar(
            title = { Text(stringResource(if (isEditMode) R.string.edit_plant else R.string.add_plant)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            },
            actions = {
                if (isEditMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
        if (!isEditMode) WizardProgressHeader(step)
    }
}

@Composable
private fun WizardStepContent(
    viewModel: AddEditPlantViewModel,
    form: AddEditPlantFormState,
    rooms: List<String>,
    actions: FormActions
) {
    when (form.step) {
        AddPlantStep.PHOTO -> PhotoStep(form, actions.onPickPhoto)
        AddPlantStep.DETAILS -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            form.propagatedFromName?.let { PropagatedFromNote(it) }
            PlantIdentityFields(viewModel, rooms)
        }
        AddPlantStep.CARE -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            form.propagatedFromName?.let { PropagatedFromNote(it) }
            CareSections(viewModel, form, actions)
        }
        AddPlantStep.REVIEW -> ReviewStep(viewModel, form, onEditStep = viewModel::goToStep)
    }
}

@Composable
private fun EditForm(
    viewModel: AddEditPlantViewModel,
    form: AddEditPlantFormState,
    rooms: List<String>,
    actions: FormActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CoverPhotoPicker(uri = form.coverPhotoUri, onPickPhoto = actions.onPickPhoto)
        PlantIdentityFields(viewModel, rooms)
        CareSections(viewModel, form, actions)
        PlantNotesField(viewModel)
        HorizontalDivider()
        HidePlantItem(viewModel)
    }
}

@Composable
private fun HidePlantItem(viewModel: AddEditPlantViewModel) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button) {
            if (viewModel.isHidden) viewModel.unhidePlant() else viewModel.hidePlant()
        },
        leadingContent = {
            Icon(
                if (viewModel.isHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = null
            )
        },
        headlineContent = {
            Text(stringResource(if (viewModel.isHidden) R.string.unhide_plant else R.string.hide_plant))
        },
        supportingContent = {
            Text(
                stringResource(
                    if (viewModel.isHidden) R.string.unhide_plant_hint else R.string.hide_plant_hint
                )
            )
        }
    )
}

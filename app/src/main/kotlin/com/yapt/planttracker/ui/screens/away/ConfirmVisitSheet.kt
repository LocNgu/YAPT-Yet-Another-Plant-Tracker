package com.yapt.planttracker.ui.screens.away

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/**
 * Slice 4's confirm checklist ([ConfirmVisitContent]) in a sheet, hosted by Care and Settings. Open it with
 * [ConfirmVisitViewModel.open]; an answer closes it and reports through [onMessage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmVisitSheetHost(viewModel: ConfirmVisitViewModel, onMessage: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val latestOnMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = context.planVisitEventText(event)
            if (message.isNotEmpty()) latestOnMessage(message)
        }
    }
    state?.let { confirm ->
        ModalBottomSheet(
            onDismissRequest = viewModel::close,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ConfirmVisitContent(
                state = confirm,
                today = LocalDate.now(),
                actions = ConfirmVisitActions(
                    onToggle = viewModel::toggleRow,
                    onContinue = { viewModel.confirm() },
                    onDidNotHappen = { viewModel.didNotHappen() }
                )
            )
        }
    }
}

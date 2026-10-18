package com.yapt.planttracker.ui.screens.away

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanVisitScreen(viewModel: PlanVisitViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    PlanVisitEventsEffect(viewModel, snackbarHostState)
    val confirming = state is PlanVisitUiState.Confirm
    BackHandler(enabled = confirming) { viewModel.skipConfirm() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (confirming) R.string.sitter_confirm_title else R.string.sitter_plan_title
                        )
                    )
                },
                navigationIcon = {
                    AwayBackNavigationIcon(if (confirming) viewModel::skipConfirm else onNavigateBack)
                }
            )
        }
    ) { padding ->
        when (val current = state) {
            PlanVisitUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            PlanVisitUiState.NoTrip -> Text(
                text = stringResource(R.string.sitter_plan_no_trip),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(16.dp)
            )
            is PlanVisitUiState.Confirm -> ConfirmVisitContent(
                state = current,
                today = LocalDate.now(),
                actions = ConfirmVisitActions(
                    onToggle = viewModel::toggleConfirmRow,
                    onContinue = { viewModel.confirmVisit() },
                    onDidNotHappen = { viewModel.visitDidNotHappen() }
                ),
                contentPadding = padding
            )
            is PlanVisitUiState.Plan -> PlanVisitContent(
                state = current,
                actions = PlanVisitActions(
                    onPickDay = viewModel::pickDay,
                    onSetGap = viewModel::setGapDays,
                    onSharePdf = { viewModel.share(asText = false) },
                    onShareText = { viewModel.share(asText = true) }
                ),
                contentPadding = padding
            )
        }
    }
}

@Composable
private fun PlanVisitEventsEffect(viewModel: PlanVisitViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlanVisitEvent.SharePdf -> SitterShare.sharePdf(context, event.file, event.caption)
                is PlanVisitEvent.ShareText -> SitterShare.shareText(context, event.text)
                else -> snackbarHostState.showSnackbar(context.planVisitEventText(event))
            }
        }
    }
}

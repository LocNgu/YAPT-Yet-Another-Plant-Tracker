package com.yapt.planttracker.ui.screens.away

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.RhythmOption
import com.yapt.planttracker.domain.away.SitterRhythm
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SitterRhythmScreen(viewModel: SitterRhythmViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SitterRhythmScaffold(onNavigateBack) { padding ->
        when (val current = state) {
            SitterRhythmUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            SitterRhythmUiState.NoTrip -> Text(
                text = stringResource(R.string.sitter_rhythm_no_trip),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(16.dp)
            )
            is SitterRhythmUiState.Ready -> SitterRhythmContent(
                state = current,
                onSave = { days ->
                    viewModel.save(days)
                    onNavigateBack()
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitterRhythmScaffold(onNavigateBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sitter_rhythm_title)) },
                navigationIcon = { AwayBackNavigationIcon(onNavigateBack) }
            )
        },
        content = content
    )
}

@Composable
internal fun SitterRhythmContent(
    state: SitterRhythmUiState.Ready,
    onSave: (everyDays: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val evaluation = state.evaluation
    val initial = state.savedEveryDays?.takeIf { it in SitterRhythm.OPTIONS } ?: evaluation.recommendedEveryDays
    var chosen by rememberSaveable(state.trip.id) { mutableIntStateOf(initial) }
    val option = evaluation.options.first { it.everyDays == chosen }
    val today = LocalDate.now()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.sitter_rhythm_heading), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.sitter_rhythm_assumption, awayDayText(evaluation.lastDayAtHome, today)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.selectableGroup()) {
            evaluation.options.forEach { candidate ->
                RhythmOptionRow(
                    option = candidate,
                    selected = candidate.everyDays == chosen,
                    recommended = candidate.everyDays == evaluation.recommendedEveryDays,
                    onSelect = { chosen = candidate.everyDays }
                )
            }
        }
        FlaggedPlants(option)
        AskYourSitterCard(
            everyDays = chosen,
            firstVisitBy = SitterRhythm.firstVisitBy(state.trip.firstDay, chosen),
            today = today
        )
        Button(
            onClick = { onSave(chosen) },
            enabled = chosen != state.savedEveryDays,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.sitter_rhythm_save)) }
    }
}

@Composable
private fun RhythmOptionRow(option: RhythmOption, selected: Boolean, recommended: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                pluralStringResource(R.plurals.sitter_rhythm_option, option.everyDays, option.everyDays),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (option.flaggedCount == 0) {
                    stringResource(R.string.sitter_rhythm_flagged_none)
                } else {
                    pluralStringResource(
                        R.plurals.sitter_rhythm_flagged_count,
                        option.flaggedCount,
                        option.flaggedCount
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (recommended) {
            Text(
                stringResource(R.string.sitter_rhythm_recommended),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FlaggedPlants(option: RhythmOption) {
    if (option.flagged.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sitter_rhythm_flagged_heading), style = MaterialTheme.typography.titleSmall)
        option.flagged.forEach { flagged ->
            Text(
                pluralStringResource(
                    R.plurals.sitter_rhythm_flagged_plant,
                    flagged.usualIntervalDays,
                    flagged.plant.name,
                    flagged.usualIntervalDays
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AskYourSitterCard(everyDays: Int, firstVisitBy: LocalDate, today: LocalDate) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val text = context.askYourSitterText(everyDays, firstVisitBy, today)
    val label = stringResource(R.string.sitter_ask_title)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
            }) { Text(stringResource(R.string.sitter_ask_copy)) }
        }
    }
}

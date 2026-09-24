package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.util.labelRes
import java.time.LocalDate

/**
 * Shared "Active in" season selector for fertilizing (#795, product ADR-0049, replacing #286's
 * four discrete per-season interval fields) — used by both Add/Edit Plant and Plant Detail's
 * Fertilize tab inline editor, so the two surfaces can never disagree on which seasons a plant
 * fertilizes in. The currently active hemisphere-aware season is marked on its chip's visible
 * label (announced along with the rest of the chip's text). The last remaining selected chip
 * renders disabled: tapping it is a no-op, and the standard disabled semantics tell assistive
 * tech why, rather than the tap silently doing nothing.
 *
 * [onToggle] reports only the tapped season, not a full replacement set (#804) — building the new
 * set from [selected] here raced a second tap against Plant Detail's `plant` StateFlow, which can
 * still hold the pre-first-tap snapshot when the second tap lands. Each caller now applies the
 * toggle to its own freshest source of truth: Add/Edit Plant's local form state, Plant Detail's
 * plant row re-read inside `PlantDetailViewModel.intervalEditMutex`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FertilizingSeasonsSelector(
    selected: Set<FertilizingSeason>,
    onToggle: (FertilizingSeason) -> Unit,
    modifier: Modifier = Modifier
) {
    val hemisphere = remember { SeasonalWatering.currentHemisphere() }
    val currentSeason = remember(hemisphere) { SeasonalFertilizing.season(LocalDate.now(), hemisphere) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.fertilizing_active_seasons_label),
            style = MaterialTheme.typography.bodyMedium
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FertilizingSeason.entries.forEach { season ->
                val isSelected = season in selected
                val isOnlySelected = isSelected && selected.size == 1
                val seasonName = stringResource(season.labelRes())
                val label = if (season == currentSeason) {
                    stringResource(R.string.fertilizing_current_season, seasonName)
                } else {
                    seasonName
                }
                FilterChip(
                    selected = isSelected,
                    enabled = !isOnlySelected,
                    onClick = { onToggle(season) },
                    label = { Text(label) }
                )
            }
        }
    }
}

package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import java.time.LocalDate

/** Canonical four-slot editor used by Add/Edit Plant (#286, product ADR-0045). */
@Composable
fun SeasonalFertilizingSetting(
    sameForAllSeasons: Boolean,
    fallbackDays: Int,
    intervalForSeason: (FertilizingSeason) -> Int?,
    onSameForAllSeasonsChange: (Boolean) -> Unit,
    onIntervalChange: (FertilizingSeason, Int?) -> Unit
) {
    val currentSeason = SeasonalFertilizing.season(LocalDate.now(), SeasonalWatering.currentHemisphere())
    val toggleLabel = stringResource(R.string.fertilizing_same_all_seasons)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(toggleLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = sameForAllSeasons,
                onCheckedChange = onSameForAllSeasonsChange,
                modifier = Modifier.semantics { contentDescription = toggleLabel }
            )
        }
        if (!sameForAllSeasons) {
            Text(
                stringResource(R.string.fertilizing_seasonal_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FertilizingSeason.entries.forEach { season ->
                FertilizingSeasonField(
                    season = season,
                    interval = intervalForSeason(season),
                    fallbackDays = fallbackDays,
                    isCurrent = season == currentSeason,
                    onIntervalChange = { onIntervalChange(season, it) }
                )
            }
        }
    }
}

@Composable
private fun FertilizingSeasonField(
    season: FertilizingSeason,
    interval: Int?,
    fallbackDays: Int,
    isCurrent: Boolean,
    onIntervalChange: (Int?) -> Unit
) {
    val seasonName = fertilizingSeasonName(season)
    val fieldDescription = if (isCurrent) {
        stringResource(R.string.fertilizing_current_season, seasonName)
    } else {
        seasonName
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        OutlinedTextField(
            value = interval?.toString().orEmpty(),
            onValueChange = { input ->
                val parsed = input.toIntOrNull()
                when {
                    input.isBlank() -> onIntervalChange(null)
                    parsed != null && parsed in 1..180 -> onIntervalChange(parsed)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .semantics { contentDescription = fieldDescription },
            label = { Text(fieldDescription) },
            placeholder = { Text(stringResource(R.string.fertilizing_use_fallback, fallbackDays)) },
            supportingText = if (interval == null) {
                { Text(stringResource(R.string.fertilizing_using_fallback, fallbackDays)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
    }
}

/** Read-only Plant Detail summary; Add/Edit Plant remains the canonical four-field editor. */
@Composable
fun SeasonalFertilizingSummary(plant: Plant, onEdit: () -> Unit) {
    val fallbackDays = plant.fertilizingIntervalDays ?: return
    val hasOverrides = listOf(
        plant.fertilizingIntervalSpring,
        plant.fertilizingIntervalSummer,
        plant.fertilizingIntervalAutumn,
        plant.fertilizingIntervalWinter
    ).any { it != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.fertilizing_seasonal_schedule_title),
            style = MaterialTheme.typography.labelLarge
        )
        if (hasOverrides) {
            FertilizingSeason.entries.forEach { season ->
                val interval = seasonalInterval(plant, season)
                val effective = interval ?: fallbackDays
                Text(
                    if (interval == null) {
                        stringResource(
                            R.string.fertilizing_season_summary_fallback,
                            fertilizingSeasonName(season),
                            effective
                        )
                    } else {
                        stringResource(
                            R.string.fertilizing_season_summary,
                            fertilizingSeasonName(season),
                            effective
                        )
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Text(
                stringResource(R.string.fertilizing_same_all_seasons_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (plant.dormancyStartMonth != null && plant.dormancyEndMonth != null) {
            Text(
                stringResource(R.string.fertilizing_dormancy_pause_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.fertilizing_edit_seasonal_schedule)) }
    }
}

@Composable
private fun fertilizingSeasonName(season: FertilizingSeason): String = stringResource(
    when (season) {
        FertilizingSeason.SPRING -> R.string.season_spring
        FertilizingSeason.SUMMER -> R.string.season_summer
        FertilizingSeason.AUTUMN -> R.string.season_autumn
        FertilizingSeason.WINTER -> R.string.season_winter
    }
)

private fun seasonalInterval(plant: Plant, season: FertilizingSeason): Int? = when (season) {
    FertilizingSeason.SPRING -> plant.fertilizingIntervalSpring
    FertilizingSeason.SUMMER -> plant.fertilizingIntervalSummer
    FertilizingSeason.AUTUMN -> plant.fertilizingIntervalAutumn
    FertilizingSeason.WINTER -> plant.fertilizingIntervalWinter
}

package com.yapt.planttracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.util.labelRes
import kotlinx.coroutines.launch
import java.time.LocalDate

private val CURRENT_SEASON_DOT_SIZE = 8.dp
private const val LOCKED_CHIP_SHAKE_DURATION_MS = 300

private fun lockedChipShakeSpec(): FiniteAnimationSpec<Float> = keyframes {
    durationMillis = LOCKED_CHIP_SHAKE_DURATION_MS
    0f at 0
    -8f at 50
    8f at 100
    -6f at 150
    6f at 200
    -3f at 250
    0f at LOCKED_CHIP_SHAKE_DURATION_MS
}

/** Bundles one chip's derived state so [FertilizingSeasonChip] stays within Detekt's `LongParameterList`. */
private data class SeasonChipState(
    val season: FertilizingSeason,
    val isSelected: Boolean,
    val isOnlySelected: Boolean,
    val isCurrentSeason: Boolean
)

/**
 * Shared "Active in" season selector for fertilizing (#795, product ADR-0049, replacing #286's
 * four discrete per-season interval fields) — used by both Add/Edit Plant and Plant Detail's
 * Fertilize tab inline editor, so the two surfaces can never disagree on which seasons a plant
 * fertilizes in.
 *
 * Selected chips carry a checkmark [leadingIcon] and a `primaryContainer` fill so selection is
 * never signalled by fill color alone (#813, product ADR-0051 — local to this component only, not
 * `Theme.kt`; the app-wide `FilterChip` palette is a separate follow-up). The currently active
 * hemisphere-aware season is marked with a small decorative trailing dot (`contentDescription =
 * null`); the "current season" wording that used to be part of the visible label instead lives in
 * the chip's `stateDescription` semantics, so TalkBack still announces it (#813, amending product
 * ADR-0049's visible-label clause).
 *
 * The last remaining selected chip stays **enabled and selected** rather than disabled (#813,
 * amending product ADR-0049's disabled-chip clause): tapping it never calls [onToggle] — the
 * selection is unchanged — but it does run a short decorative wiggle and a
 * [HapticFeedbackType.Reject] buzz, then invokes [onLastSeasonLocked] so the caller can show an
 * explanatory snackbar. The snackbar is the accessible cue (works with "Remove animations" and
 * TalkBack); the wiggle is visual only and is never required for correctness — [onLastSeasonLocked]
 * fires whether or not the animation actually runs.
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
    modifier: Modifier = Modifier,
    onLastSeasonLocked: () -> Unit = {}
) {
    val hemisphere = remember { SeasonalWatering.currentHemisphere() }
    val currentSeason = remember(hemisphere) { SeasonalFertilizing.season(LocalDate.now(), hemisphere) }
    val currentSeasonStateDescription = stringResource(R.string.fertilizing_current_season)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.fertilizing_active_seasons_label),
            style = MaterialTheme.typography.bodyMedium
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FertilizingSeason.entries.forEach { season ->
                FertilizingSeasonChip(
                    state = SeasonChipState(
                        season = season,
                        isSelected = season in selected,
                        isOnlySelected = season in selected && selected.size == 1,
                        isCurrentSeason = season == currentSeason
                    ),
                    currentSeasonStateDescription = currentSeasonStateDescription,
                    onToggle = onToggle,
                    onLastSeasonLocked = onLastSeasonLocked
                )
            }
        }
    }
}

@Composable
private fun FertilizingSeasonChip(
    state: SeasonChipState,
    currentSeasonStateDescription: String,
    onToggle: (FertilizingSeason) -> Unit,
    onLastSeasonLocked: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }
    val chipModifier = Modifier
        .graphicsLayer { translationX = shakeOffset.value }
        .then(
            if (state.isCurrentSeason) {
                Modifier.semantics { stateDescription = currentSeasonStateDescription }
            } else {
                Modifier
            }
        )
    FilterChip(
        selected = state.isSelected,
        onClick = {
            if (state.isOnlySelected) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                onLastSeasonLocked()
                coroutineScope.launch {
                    shakeOffset.animateTo(targetValue = 0f, animationSpec = lockedChipShakeSpec())
                }
            } else {
                onToggle(state.season)
            }
        },
        label = { Text(stringResource(state.season.labelRes())) },
        leadingIcon = if (state.isSelected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else {
            null
        },
        trailingIcon = if (state.isCurrentSeason) {
            {
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(CURRENT_SEASON_DOT_SIZE)
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = chipModifier
    )
}

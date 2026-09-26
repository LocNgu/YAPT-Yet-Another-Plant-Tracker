package com.yapt.planttracker.ui.components

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * The one colour set every `FilterChip` in the app uses (#814, product ADR-0052, generalising
 * product ADR-0051's season-chip fix to the whole app).
 *
 * A selected chip gets a `primaryContainer`/`onPrimaryContainer` fill — green in both themes, matching
 * the app's "selected/active/on" convention. Material 3's default (`secondaryContainer`) reads as a
 * dusty grey-taupe in light theme and an off-palette grey-purple in dark theme, which made an
 * unselected chip (green outlined label) look more "on" than a selected one. No checkmark: the outline
 * an unselected chip has and a selected one drops is the non-color cue (ADR-0051), and TalkBack gets
 * the selected state from the chip's own semantics.
 *
 * Every `FilterChip` call site must pass this as `colors` so they cannot drift apart again. Unselected
 * colours are left at the Material defaults.
 */
@Composable
fun yaptFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

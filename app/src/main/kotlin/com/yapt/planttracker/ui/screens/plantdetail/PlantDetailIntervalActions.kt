package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.ui.components.TimeRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Plain reset of the raw suggestion with no confidence side effect — as opposed to
 * [dismissSuggestedInterval], the explicit Dismiss tap. `PlantDetailScreen` no longer calls this
 * to silently pre-empt a stale suggestion before it renders (#620 round 2) — `pendingWateringSuggestion`
 * already collapses to `null` by itself whenever the effective-space delta is 0, so a screen-side
 * short-circuit against the raw value would only risk discarding a suggestion that is genuinely
 * different in effective space but happens to numerically coincide with it in base space.
 */
fun PlantDetailViewModel.clearSuggestedInterval() {
    suggestedWateringInterval.value = null
}

/**
 * Dismissing the ADR-0006 suggestion dialog without applying (explicit Dismiss tap, or tapping
 * outside it). A genuine dismissal raises [com.yapt.planttracker.domain.model.Plant.wateringConfidence]
 * up to [CareSchedule.DISMISSAL_CONFIDENCE_CEILING] when [FeatureFlagRegistry.ADAPTIVE_WATERING] is on
 * (#568) — the user is saying the current schedule is fine.
 */
fun PlantDetailViewModel.dismissSuggestedInterval() {
    viewModelScope.launch {
        if (isAdaptiveWateringEnabled()) {
            plant.value?.let { p ->
                plantRepository.updatePlant(
                    p.copy(
                        wateringConfidence = CareSchedule.confidenceAfterDismissal(p.wateringConfidence),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                p.wateringIntervalDays?.let { current ->
                    // #584 review: log the base-space reference, not the literal effective
                    // value, so this row's units match the WATER_*/CHECK_STILL_MOIST rows when
                    // season is on and the plant isn't pinned.
                    val currentBase = currentBaseIntervalDaysOrLiteral(p, current)
                    wateringAdjustmentRepository.addAdjustment(
                        WateringAdjustment(
                            plantId = p.id,
                            trigger = WateringAdjustmentTrigger.DIALOG_DISMISSAL,
                            beforeIntervalDays = currentBase,
                            afterIntervalDays = currentBase
                        )
                    )
                }
            }
        }
        suggestedWateringInterval.value = null
    }
}

internal suspend fun PlantDetailViewModel.isAdaptiveWateringEnabled(): Boolean =
    dataStore.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
        ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

/**
 * "Ask before changing intervals" (#572) — the ADR-0006 dialog is skipped only when
 * `adaptive_watering` is on **and** the setting is off; the toggle is inert while the flag is off
 * (today's dialog-always behavior).
 */
private suspend fun PlantDetailViewModel.shouldShowIntervalDialog(): Boolean {
    if (!isAdaptiveWateringEnabled()) return true
    return dataStore.data.first()[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS] ?: true
}

/**
 * Routes a freshly-computed adaptive suggestion to either the ADR-0006 dialog or a silent apply
 * + undo Snackbar, depending on [shouldShowIntervalDialog] (#572).
 */
internal suspend fun PlantDetailViewModel.applySuggestionOrPrompt(suggestedInterval: Int) {
    if (shouldShowIntervalDialog()) {
        suggestedWateringInterval.value = suggestedInterval
        return
    }
    val p = plant.value ?: return
    // #644: applyWateringIntervalSuggestion's newInterval is effective-space now — convert the raw
    // (base-space) suggestedInterval the same way pendingWateringSuggestion does, so a silent apply
    // commits the same number the dialog would have shown/pre-filled had it been shown.
    val amplitude = dataStore.seasonalAmplitudeOnce()
    val effectiveInterval = CareSchedule.effectiveWateringIntervalDaysForDisplay(
        plant = p.copy(
            wateringBaseIntervalDays = suggestedInterval.toDouble(),
            wateringIntervalDays = suggestedInterval
        ),
        seasonalAmplitude = amplitude
    ) ?: suggestedInterval
    val result = quickLogUseCase.applyWateringIntervalSuggestion(p, suggestedInterval, effectiveInterval)
    emitEvent(
        PlantDetailViewModel.Event.SilentIntervalApplied(
            beforeIntervalDays = result.previousEffectiveIntervalDays,
            beforeBaseIntervalDays = result.previousBaseIntervalDays,
            afterIntervalDays = result.newEffectiveIntervalDays
        )
    )
}

/** Entry point for the ADR-0006 suggestion surfaced via `AddCareLogScreen`'s save flow (see `NavGraph`). */
fun PlantDetailViewModel.handleSuggestedWateringInterval(suggestedInterval: Int) {
    viewModelScope.launch { applySuggestionOrPrompt(suggestedInterval) }
}

internal fun PlantDetailViewModel.setTimeRange(range: TimeRange) {
    selectedTimeRange.value = range
}

fun PlantDetailViewModel.applySuggestedInterval(newInterval: Int) {
    viewModelScope.launch {
        val originalSuggestion = suggestedWateringInterval.value
        plant.value?.let { p -> quickLogUseCase.applyWateringIntervalSuggestion(p, originalSuggestion, newInterval) }
        suggestedWateringInterval.value = null
        emitEvent(PlantDetailViewModel.Event.IntervalUpdated)
    }
}

/**
 * Reverts a silently-applied suggestion (#572) back to [beforeIntervalDays] /
 * [beforeBaseIntervalDays] — the Snackbar's "Undo" action. Both values are the plant's actual
 * prior watering interval/base, captured and threaded straight through from
 * `applySuggestionOrPrompt` via `Event.SilentIntervalApplied` — restored as-is, never recomputed
 * (#626: recomputing [beforeBaseIntervalDays] from [beforeIntervalDays] relied on
 * [beforeIntervalDays] coincidentally already being base-space, which stopped being true once the
 * write path started writing a genuine effective value there). Writes a compensating
 * [WateringAdjustment] row ([WateringAdjustmentTrigger.SILENT_APPLY_UNDONE], #584 review) so
 * "Recent adjustments" reflects the revert instead of still showing the original silent apply as
 * if it stood — `before` is the silently-applied value being undone, `after` is the restored
 * original.
 */
fun PlantDetailViewModel.undoSilentIntervalApply(beforeIntervalDays: Int, beforeBaseIntervalDays: Double?) {
    viewModelScope.launch {
        plant.value?.let { p ->
            val silentlyAppliedInterval = p.wateringIntervalDays ?: beforeIntervalDays
            val now = System.currentTimeMillis()
            plantRepository.updatePlant(
                p.copy(
                    wateringIntervalDays = beforeIntervalDays,
                    wateringBaseIntervalDays = beforeBaseIntervalDays,
                    updatedAt = now
                )
            )
            if (isAdaptiveWateringEnabled()) {
                wateringAdjustmentRepository.addAdjustment(
                    WateringAdjustment(
                        plantId = p.id,
                        triggeredAt = now,
                        trigger = WateringAdjustmentTrigger.SILENT_APPLY_UNDONE,
                        beforeIntervalDays = silentlyAppliedInterval,
                        afterIntervalDays = beforeIntervalDays
                    )
                )
            }
        }
    }
}

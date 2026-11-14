package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import java.time.LocalDate

/**
 * The one "does this adaptive suggestion differ from today's effective interval?" gate for the
 * product ADR-0006 suggestion dialog (#968). [AdaptiveWateringObservation.observe] and
 * `PlantDetailViewModel.pendingWateringSuggestion` both call [evaluate], so a fix to the gate
 * (seasonal conversion, #620/#716-style unit mismatches) lands in one place.
 *
 * Both sides are live, effective-space values evaluated on the same [nowDate] (#716): the
 * suggestion's base run through today's season versus the plant's own base run through the same
 * day, never the stale `Plant.wateringIntervalDays` literal. Pinned and amplitude-Off plants fall
 * through to the literal values, as in [CareSchedule.effectiveWateringIntervalDaysForDisplay].
 */
object WateringSuggestionGate {

    /**
     * [suggestedEffectiveIntervalDays] is the suggestion converted to effective (display) space;
     * [currentEffectiveIntervalDays] is today's live effective interval of the plant as it stands,
     * `null` only when the plant has no watering interval at all.
     */
    data class Evaluation(
        val suggestedEffectiveIntervalDays: Int,
        val currentEffectiveIntervalDays: Int?
    ) {
        /** `true` when the dialog should show; `false` means the "jump" is calendar drift only. */
        val differs: Boolean get() = suggestedEffectiveIntervalDays != currentEffectiveIntervalDays
    }

    /**
     * Converts a base-space suggestion to effective space by treating it as the plant's new base.
     * A one-way, display-only conversion: the precise [suggestedBaseIntervalDays] is never rounded
     * or rewritten here (technical ADR-0027).
     */
    fun suggestedEffectiveIntervalDays(
        plant: Plant,
        suggestedIntervalDays: Int,
        suggestedBaseIntervalDays: Double,
        nowDate: LocalDate,
        seasonalAmplitude: Double
    ): Int = CareSchedule.effectiveWateringIntervalDaysForDisplay(
        plant = plant.copy(
            wateringBaseIntervalDays = suggestedBaseIntervalDays,
            wateringIntervalDays = suggestedIntervalDays
        ),
        nowDate = nowDate,
        seasonalAmplitude = seasonalAmplitude
    ) ?: suggestedIntervalDays

    fun evaluate(
        plant: Plant,
        suggestedIntervalDays: Int,
        suggestedBaseIntervalDays: Double,
        nowDate: LocalDate,
        seasonalAmplitude: Double
    ): Evaluation = Evaluation(
        suggestedEffectiveIntervalDays = suggestedEffectiveIntervalDays(
            plant,
            suggestedIntervalDays,
            suggestedBaseIntervalDays,
            nowDate,
            seasonalAmplitude
        ),
        currentEffectiveIntervalDays = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant,
            nowDate = nowDate,
            seasonalAmplitude = seasonalAmplitude
        )
    )
}

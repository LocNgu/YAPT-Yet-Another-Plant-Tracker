package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant

/**
 * The one place that decides which seasonal strength a plant's watering interval follows (#700,
 * product ADR-0081): Pin gives `0.0` (no seasonality), a custom [override] wins over everything but
 * Pin, and otherwise the [globalAmplitude] applies. Pin outranks an override so a row that somehow
 * carries both (a hand-edited backup) still behaves as pinned.
 */
fun resolveWateringAmplitude(
    pinned: Boolean,
    override: SeasonalAmplitude?,
    globalAmplitude: Double
): Double = when {
    pinned -> 0.0
    override != null -> override.value
    else -> globalAmplitude
}

/** [resolveWateringAmplitude] for a [Plant]; [globalAmplitude] is the raw app-wide setting. */
fun Plant.effectiveWateringAmplitude(globalAmplitude: Double): Double =
    resolveWateringAmplitude(pinIntervalToBase, wateringAmplitudeOverride, globalAmplitude)

/** The three choices of the per-plant strength control (#700, product ADR-0081). */
enum class WateringStrengthMode { APP_DEFAULT, CUSTOM, PIN }

/**
 * Strengths offered under "Custom". Off is deliberately absent: a plant with no seasonal swing is
 * exactly what Pin already is, so a second way to say it would only be a trap (product ADR-0081).
 */
val CUSTOM_WATERING_STRENGTHS: List<SeasonalAmplitude> =
    listOf(SeasonalAmplitude.MILD, SeasonalAmplitude.STANDARD, SeasonalAmplitude.STRONG)

/** What the control shows as selected. A stray custom Off (only reachable from a hand-edited backup) reads as Pin. */
fun wateringStrengthMode(pinned: Boolean, override: SeasonalAmplitude?): WateringStrengthMode = when {
    pinned || override == SeasonalAmplitude.OFF -> WateringStrengthMode.PIN
    override != null -> WateringStrengthMode.CUSTOM
    else -> WateringStrengthMode.APP_DEFAULT
}

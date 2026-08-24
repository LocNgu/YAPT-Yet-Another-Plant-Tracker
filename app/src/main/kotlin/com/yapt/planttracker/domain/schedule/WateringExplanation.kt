package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.util.toLocalDate
import kotlin.math.roundToInt

/**
 * Everything the "Why this date?" sheet (#572) needs to render — every field is either a stored
 * value or a single multiplication, per the issue's own framing ("no inferred narrative, no
 * invented explanation"). Built by [WateringExplanationBuilder.build], never by re-deriving the
 * numbers in the UI layer, so the sheet can't drift from what [CareSchedule] actually used.
 */
data class WateringExplanation(
    val nextWateringDueAt: Long?,
    val lastWateredAt: Long?,
    /** The number [CareSchedule.computeStatus] actually used for the due date — "Watering every N days". */
    val effectiveIntervalDays: Int,
    val waterLogCount: Int,
    val adaptiveWateringEnabled: Boolean,
    /** `null` when [adaptiveWateringEnabled] is false — no base/confidence/adjustments are invented. */
    val baseIntervalDays: Int? = null,
    val season: WateringExplanationSeason? = null,
    val confidenceLevel: WateringConfidenceLevel? = null,
    /** Raw 0-5 dot count backing [confidenceLevel]'s decorative dots — `0` when never adapted. */
    val confidenceScore: Int = 0,
    val recentAdjustments: List<WateringAdjustment> = emptyList()
)

/** The season row — hidden entirely (not `null` multiplier) when amplitude is 0.0 or the plant is pinned. */
data class WateringExplanationSeason(val multiplier: Double, val band: SeasonBand)

/**
 * Three-way bucket for the season row's short reason string (#572) — "one string per season band,
 * not per month" per the issue. [SLOWER_GROWTH]/[FASTER_GROWTH] are the outer thirds of
 * `[1 - amplitude, 1 + amplitude]`; [TRANSITIONAL] is the middle third.
 */
enum class SeasonBand { SLOWER_GROWTH, FASTER_GROWTH, TRANSITIONAL }

/**
 * Three-way bucket for [Plant.wateringConfidence] (0-5, `null` = never adapted) — the sheet renders
 * this label as the accessible content, dots are decorative (#420).
 */
enum class WateringConfidenceLevel {
    STILL_LEARNING,
    GETTING_THERE,
    DIALED_IN;

    companion object {
        private const val STILL_LEARNING_MAX = 1
        private const val GETTING_THERE_MAX = 3

        fun fromScore(score: Int?): WateringConfidenceLevel {
            val value = score ?: 0
            return when {
                value <= STILL_LEARNING_MAX -> STILL_LEARNING
                value <= GETTING_THERE_MAX -> GETTING_THERE
                else -> DIALED_IN
            }
        }
    }
}

object WateringExplanationBuilder {

    private const val SEASON_BAND_THIRD = 3.0

    /**
     * Pure builder — takes [nextWateringDueAt]/[lastWateredAt] from
     * [com.yapt.planttracker.domain.model.PlantCareStatus] (already computed by
     * [CareSchedule.computeStatus]) rather than recomputing them, so the due-date math can never
     * drift between the schedule and the sheet. Returns `null` when [plant] has no watering
     * schedule at all (nothing to explain).
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun build(
        plant: Plant,
        nextWateringDueAt: Long?,
        lastWateredAt: Long?,
        waterLogCount: Int,
        adaptiveWateringEnabled: Boolean,
        seasonalAmplitude: Double,
        recentAdjustments: List<WateringAdjustment>,
        hemisphere: Hemisphere = SeasonalWatering.currentHemisphere(),
        now: Long = System.currentTimeMillis()
    ): WateringExplanation? {
        plant.wateringIntervalDays ?: return null
        val nowDate = now.toLocalDate()
        val effectiveIntervalDays =
            CareSchedule.effectiveWateringIntervalDaysForDisplay(plant, nowDate, seasonalAmplitude, hemisphere)
                ?: return null

        if (!adaptiveWateringEnabled) {
            return WateringExplanation(
                nextWateringDueAt = nextWateringDueAt,
                lastWateredAt = lastWateredAt,
                effectiveIntervalDays = effectiveIntervalDays,
                waterLogCount = waterLogCount,
                adaptiveWateringEnabled = false
            )
        }

        val baseIntervalDays = (plant.wateringBaseIntervalDays ?: effectiveIntervalDays.toDouble()).roundToInt()
        val showSeason = seasonalAmplitude != 0.0 && !plant.pinIntervalToBase
        val season = if (showSeason) {
            val multiplier = SeasonalWatering.season(nowDate, seasonalAmplitude, hemisphere)
            WateringExplanationSeason(multiplier, seasonBandFor(multiplier, seasonalAmplitude))
        } else {
            null
        }

        return WateringExplanation(
            nextWateringDueAt = nextWateringDueAt,
            lastWateredAt = lastWateredAt,
            effectiveIntervalDays = effectiveIntervalDays,
            waterLogCount = waterLogCount,
            adaptiveWateringEnabled = true,
            baseIntervalDays = baseIntervalDays,
            season = season,
            confidenceLevel = WateringConfidenceLevel.fromScore(plant.wateringConfidence),
            confidenceScore = plant.wateringConfidence ?: 0,
            recentAdjustments = recentAdjustments
        )
    }

    private fun seasonBandFor(multiplier: Double, amplitude: Double): SeasonBand {
        val threshold = amplitude / SEASON_BAND_THIRD
        return when {
            multiplier >= 1 + threshold -> SeasonBand.SLOWER_GROWTH
            multiplier <= 1 - threshold -> SeasonBand.FASTER_GROWTH
            else -> SeasonBand.TRANSITIONAL
        }
    }
}

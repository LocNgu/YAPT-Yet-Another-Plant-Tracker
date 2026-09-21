package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The one adaptive-observation write path for quick watering and the Add Care Log form (#780).
 * The form's historical newest-pair gap and its nullable test dependencies are explicit inputs;
 * dormancy, bootstrap, confidence, adjustment rows and state persistence have one implementation.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class AdaptiveWateringObservation(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val dataStore: DataStore<Preferences>?,
    private val wateringAdjustmentRepository: WateringAdjustmentRepository?
) {
    enum class GapSource { CHRONOLOGICAL_PREDECESSOR, NEWEST_PAIR_OR_CONFIGURED }

    data class Suggestion(
        val intervalDays: Int,
        val effectiveIntervalDays: Int,
        val baseIntervalDays: Double,
        val currentEffectiveIntervalDays: Int
    )

    /**
     * Suppress feedback before the log write, including edits and re-saves. A dormant log's
     * persisted feedback would otherwise enter a later correctionStreak window (#776, P2-c/P2).
     * [excludeLogId] prevents an edited row from becoming its own predecessor.
     */
    @Suppress("ReturnCount")
    suspend fun feedbackForLog(
        plant: Plant,
        feedback: WateringFeedback?,
        loggedAt: Long,
        excludeLogId: Long? = null
    ): WateringFeedback? {
        if (feedback == null) return null
        val previous = careLogRepository.getLastWateringBefore(plant.id, loggedAt, excludeLogId)?.loggedAt
            ?: return feedback
        return feedback.takeUnless { spansDormancy(plant, previous, loggedAt) }
    }

    /**
     * [loggedAt] drives observation math and every write. [displayNow] drives only the two live
     * effective values compared for the suggestion dialog (#716). [gapSource] preserves the form's
     * existing newest-pair arithmetic; the same chronological predecessor drives dormancy in both
     * paths, even when the newest pair disagrees for a backdated insertion (#776, P2-d/P1-4).
     * [isEditMode] skips adaptation for the form's edit flow while feedback suppression still runs.
     */
    @Suppress("ReturnCount")
    suspend fun observe(
        plant: Plant,
        feedback: WateringFeedback?,
        loggedAt: Long,
        displayNow: Long,
        gapSource: GapSource,
        isEditMode: Boolean = false
    ): Suggestion? {
        if (isEditMode) return null
        val current = plant.wateringIntervalDays ?: return null
        val previous = careLogRepository.getLastWateringBefore(plant.id, loggedAt)?.loggedAt
        val dormancySpanning = previous != null && spansDormancy(plant, previous, loggedAt)
        val actual = when (gapSource) {
            GapSource.CHRONOLOGICAL_PREDECESSOR -> {
                if (previous == null) return null
                CareSchedule.daysBetween(previous, loggedAt)
            }
            GapSource.NEWEST_PAIR_OR_CONFIGURED -> {
                val latest = careLogRepository.getLastTwoWaterings(plant.id)
                if (latest.size >= 2) CareSchedule.daysBetween(latest[1].loggedAt, latest[0].loggedAt) else current
            }
        }
        if (actual <= 0 && !dormancySpanning) return null
        val observationFeedback = feedback.takeUnless { dormancySpanning }
        val result = adapt(plant, observationFeedback, actual, current, loggedAt, dormancySpanning) ?: return null
        val effectiveSuggested = effectiveIntervalForDisplay(
            plant,
            result.baseIntervalDays,
            result.intervalDays,
            displayNow
        )
        val currentEffective = effectiveIntervalForDisplay(
            plant,
            currentAdaptiveBaseIntervalDays(plant, current),
            current,
            displayNow
        )
        persistAdaptiveState(plant, result, effectiveSuggested == currentEffective, loggedAt)
        return if (effectiveSuggested != currentEffective) {
            Suggestion(result.intervalDays, effectiveSuggested, result.baseIntervalDays, currentEffective)
        } else {
            null
        }
    }

    private fun spansDormancy(plant: Plant, previous: Long, loggedAt: Long): Boolean =
        DormancyWindow.spansDormancy(plant.dormancyStartMonth, plant.dormancyEndMonth, previous, loggedAt)

    /** Bootstrap wins over an incremental update, but a spanning observation still gets its
     * DORMANCY_EXCLUDED provenance row (#776, P1-3). The bootstrap itself excludes dormant gaps. */
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun adapt(
        plant: Plant,
        feedback: WateringFeedback?,
        actual: Int,
        current: Int,
        loggedAt: Long,
        dormancySpanning: Boolean
    ): CareSchedule.AdaptiveInterval? {
        val currentBase = currentAdaptiveBaseIntervalDays(plant, current)
        if (maybeApplyHistoryBootstrap(plant, feedback, loggedAt)) {
            if (dormancySpanning) recordDormancyExcludedForBootstrap(plant, loggedAt, currentBase)
            return null
        }
        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        val frozenPostRepot = WateringLifecycleReset.isFrozen(plant.wateringFreezeUntil, loggedAt)
        val dormancyExited = dormancySpanning && !DormancyWindow.isDormant(
            loggedAt.toLocalDate().monthValue, plant.dormancyStartMonth, plant.dormancyEndMonth
        )
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = feedback,
            observedIntervalDays = deseasonalizedObservedIntervalDays(actual, plant.pinIntervalToBase, loggedAt),
            currentBaseIntervalDays = currentBase,
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback,
            frozen = frozenPostRepot || dormancySpanning,
            suppressConfidenceTransition = dormancySpanning
        )
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = loggedAt,
                trigger = adjustmentTriggerFor(
                    feedback,
                    result.excludedFromBaseLearning,
                    frozenPostRepot,
                    dormancySpanning
                ),
                beforeIntervalDays = currentBase.roundToInt(),
                afterIntervalDays = result.intervalDays
            )
        )
        return applyDormancyExitDecrement(plant, loggedAt, currentBase, dormancyExited, result)
    }

    /** A cycle is identified by no dormant month between its recorded exit and [loggedAt] (#776, P1-2). */
    @Suppress("ReturnCount")
    private suspend fun applyDormancyExitDecrement(
        plant: Plant,
        loggedAt: Long,
        currentBase: Double,
        dormancyExited: Boolean,
        result: CareSchedule.AdaptiveInterval
    ): CareSchedule.AdaptiveInterval {
        if (!dormancyExited) return result
        val confidence = result.confidence ?: return result
        if (alreadyRecordedDormancyExit(plant, loggedAt)) return result
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = loggedAt,
                trigger = WateringAdjustmentTrigger.DORMANCY_EXIT,
                beforeIntervalDays = currentBase.roundToInt(),
                afterIntervalDays = currentBase.roundToInt()
            )
        )
        return result.copy(confidence = (confidence - 1).coerceAtLeast(0))
    }

    private suspend fun alreadyRecordedDormancyExit(plant: Plant, loggedAt: Long): Boolean =
        wateringAdjustmentRepository?.getByTrigger(plant.id, WateringAdjustmentTrigger.DORMANCY_EXIT)?.any {
            !DormancyWindow.spansDormancy(
                plant.dormancyStartMonth,
                plant.dormancyEndMonth,
                minOf(it.triggeredAt, loggedAt),
                maxOf(it.triggeredAt, loggedAt)
            )
        } ?: false

    private suspend fun recordDormancyExcludedForBootstrap(plant: Plant, loggedAt: Long, currentBase: Double) {
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = loggedAt,
                trigger = WateringAdjustmentTrigger.DORMANCY_EXCLUDED,
                beforeIntervalDays = currentBase.roundToInt(),
                afterIntervalDays = currentBase.roundToInt()
            )
        )
    }

    private suspend fun maybeApplyHistoryBootstrap(plant: Plant, feedback: WateringFeedback?, loggedAt: Long): Boolean {
        val boundaryMs = when {
            plant.wateringConfidence == null -> Long.MIN_VALUE
            plant.wateringResetAt != null -> plant.wateringFreezeUntil ?: plant.wateringResetAt
            else -> return false
        }
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = careLogRepository.getWaterLogTimestampsAscending(plant.id),
            boundaryMs = boundaryMs,
            seasonFn = seasonFnFor(plant),
            feedback = feedback
        )
        return WateringLifecycleReset.maybeBootstrap(
            request,
            plantRepository,
            wateringAdjustmentRepository,
            loggedAt,
            displayNow = System.currentTimeMillis()
        )
    }

    @Suppress("ReturnCount")
    private suspend fun seasonFnFor(plant: Plant): (LocalDate) -> Double {
        if (plant.pinIntervalToBase) return { 1.0 }
        val amplitude = dataStore?.seasonalAmplitudeOnce() ?: 0.0
        if (amplitude == 0.0) return { 1.0 }
        val hemisphere = SeasonalWatering.currentHemisphere()
        return { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
    }

    private fun adjustmentTriggerFor(
        feedback: WateringFeedback?,
        excludedFromBaseLearning: Boolean,
        frozenPostRepot: Boolean,
        dormancySpanning: Boolean
    ): WateringAdjustmentTrigger = when {
        dormancySpanning -> WateringAdjustmentTrigger.DORMANCY_EXCLUDED
        frozenPostRepot -> WateringAdjustmentTrigger.FROZEN_POST_REPOT
        excludedFromBaseLearning -> WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED
        feedback == WateringFeedback.TOO_SOON -> WateringAdjustmentTrigger.WATER_TOO_SOON
        feedback == WateringFeedback.TOO_LATE -> WateringAdjustmentTrigger.WATER_TOO_LATE
        feedback == WateringFeedback.JUST_RIGHT -> WateringAdjustmentTrigger.WATER_JUST_RIGHT
        else -> WateringAdjustmentTrigger.WATER_NEUTRAL
    }

    private suspend fun persistAdaptiveState(
        plant: Plant,
        result: CareSchedule.AdaptiveInterval,
        persistBase: Boolean,
        loggedAt: Long
    ) {
        val seasonAdjustable = !plant.pinIntervalToBase && (dataStore?.seasonalAmplitudeOnce() ?: 0.0) != 0.0
        val newBase = result.baseIntervalDays.takeIf { persistBase && seasonAdjustable }
            ?: plant.wateringBaseIntervalDays
        if (result.confidence != plant.wateringConfidence || newBase != plant.wateringBaseIntervalDays) {
            plantRepository.updatePlant(
                plant.copy(
                    wateringConfidence = result.confidence,
                    wateringBaseIntervalDays = newBase,
                    updatedAt = loggedAt
                )
            )
        }
    }

    private suspend fun effectiveIntervalForDisplay(
        plant: Plant,
        suggestionBase: Double,
        suggestion: Int,
        displayNow: Long
    ): Int = CareSchedule.effectiveWateringIntervalDaysForDisplay(
        plant = plant.copy(wateringBaseIntervalDays = suggestionBase, wateringIntervalDays = suggestion),
        nowDate = displayNow.toLocalDate(),
        seasonalAmplitude = dataStore?.seasonalAmplitudeOnce() ?: 0.0
    ) ?: suggestion

    @Suppress("ReturnCount")
    suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Double {
        if (plant.pinIntervalToBase) return configuredIntervalDays.toDouble()
        if ((dataStore?.seasonalAmplitudeOnce() ?: 0.0) == 0.0) return configuredIntervalDays.toDouble()
        return plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()
    }

    @Suppress("ReturnCount")
    private suspend fun deseasonalizedObservedIntervalDays(actual: Int, pinned: Boolean, loggedAt: Long): Int {
        if (pinned) return actual
        val amplitude = dataStore?.seasonalAmplitudeOnce() ?: 0.0
        if (amplitude == 0.0) return actual
        return SeasonalWatering.deseasonalizeToDays(
            actual,
            loggedAt.toLocalDate(),
            amplitude,
            SeasonalWatering.currentHemisphere()
        )
    }

    private companion object {
        const val RECENT_WATERINGS_WINDOW = 3
    }
}

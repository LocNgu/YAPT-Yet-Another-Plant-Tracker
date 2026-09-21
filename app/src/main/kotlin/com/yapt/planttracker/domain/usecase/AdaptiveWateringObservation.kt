package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
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
     * Suppress feedback to `null` when the gap from [loggedAt]'s own chronological predecessor
     * overlaps [plant]'s dormancy window (#699/#761, product ADR-0044 — #776, P2-c). The quick-log
     * surfaces already gate their reason prompt on this condition, but this is defense in depth: a
     * persisted [WateringFeedback] on a dormancy-spanning log would still enter a later
     * [CareSchedule.correctionStreak] window even though this observation's own base and confidence
     * transitions are excluded. The Add Care Log form's feedback chip is static, so this write-time
     * gate is its only opportunity to prevent that stale feedback.
     *
     * Run the gate before every log write, including edits and re-saves (#776, P2). Editing a WATER
     * log into a spanning position must clear selected feedback, and re-saving an older dormant log
     * must strip feedback it already carries. [excludeLogId] prevents the row being edited from
     * becoming its own predecessor while its old date is still on file.
     *
     * This lookup runs before the insert, while [observe] runs after it. Both use
     * [CareLogRepository.getLastWateringBefore]; the inserted log cannot be its own strictly earlier
     * predecessor. This preserves the quick path's original two-query ordering (#776, P2-c).
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
     * [feedback] may be `null` (#570, product ADR-0027) — a silent gap-only observation, capped at
     * [CareSchedule.NEUTRAL_OBSERVATION_GAIN]. [isEditMode] skips adaptation for the form's edit flow;
     * [feedbackForLog] still suppresses feedback before an edited log is persisted.
     *
     * Gates on a **live-to-live, effective-space** comparison (#716) — the pre-observation model's
     * base run through today's season versus the post-observation model's base run through the same
     * today, never the stale `plant.wateringIntervalDays` literal that used to stand in for
     * "current". That literal is only rewritten on a manual edit, suggestion apply, bootstrap, or
     * one-time fixup, while the true effective value moves as the seasonal curve crosses rounding
     * thresholds. Comparing a live number against that stale one flagged pure calendar drift as a
     * model change and attributed it to whichever watering happened to be logged. A base that moves
     * but still rounds to the same effective value today is intentionally persisted silently.
     *
     * **Two separate clocks, on purpose (#716 review round 1).** [loggedAt] is the just-inserted
     * log's observation time (possibly backdated) and drives the gap, model input, freeze check,
     * [WateringAdjustment.triggeredAt], and [Plant.updatedAt]. [displayNow] drives only the two
     * [effectiveIntervalForDisplay] calls: the suggested and current values are what the user reads
     * **today**, regardless of the date the log claims to have happened. Evaluating them at a
     * backdated [loggedAt] could show a spurious dialog for a number that would not change today or,
     * worse, silently apply a real display change without asking. The quick caller defaults this
     * clock to its `nowProvider`; the form defaults it to the real wall clock. The history bootstrap
     * has its own always-real display clock (#679), documented at [maybeApplyHistoryBootstrap].
     *
     * [gapSource] makes the pre-existing caller difference explicit. Quick watering uses [loggedAt]'s
     * chronological predecessor ([CareLogRepository.getLastWateringBefore], strictly earlier), not
     * the two globally newest waterings (#654 round-2 review fix). A backdated log may be older than
     * both of those rows. The form retains its historical newest-pair-or-configured *numeric gap*,
     * but its dormancy check always uses this log's true predecessor (#776, P2-d). Those two lookups
     * can disagree. A stale same-day duplicate in the newest pair may make the numeric gap zero;
     * the `actual <= 0` guard must not skip dormancy exclusion and exit bookkeeping for a genuine
     * spanning gap elsewhere in history (#776, P1-4).
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

    /**
     * Applies the multiplicative, confidence-weighted model (#568, technical ADR-0021). The
     * resulting confidence is persisted by [observe] immediately, independent of whether the
     * caller later shows or applies a suggestion. A `null` [feedback] is a silent gap-only
     * observation, capped at [CareSchedule.NEUTRAL_OBSERVATION_GAIN].
     *
     * [dormancySpanning] is computed *before* the bootstrap early return (#776, P1-3). When this
     * WATER log unlocks the #571 cold-start or post-reset history bootstrap, the bootstrap has
     * already committed its new interval, so this function returns `null` rather than running an
     * incremental correction or surfacing a suggestion dialog on top of that value. The bootstrap
     * itself excludes dormant gaps from its median and gap count through
     * [CareSchedule.bootstrapBaseInterval] (#776, P1-a). Computing [dormancySpanning] first preserves
     * the separate DORMANCY_EXCLUDED provenance row for the triggering observation, even when the
     * bootstrap wins. It does not add a dormancy exit decrement to a wholesale bootstrap result;
     * [recordDormancyExcludedForBootstrap] explains that precedence.
     *
     * [loggedAt] is the same value that drove the duplicate-day check and [CareLog] write in the
     * caller. It also anchors the freeze-window check and every adjustment timestamp here, so a
     * backdated observation cannot be evaluated against the entry day's wall clock while claiming
     * to have happened earlier (#654, #776 P1). Only the bootstrap's display-space conversion uses
     * a separate real-wall-clock date (#679).
     *
     * A gap spanning dormancy is excluded from base learning through the same `frozen` mechanism as
     * a REPOT freeze, but its adjustment trigger is DORMANCY_EXCLUDED rather than
     * FROZEN_POST_REPOT. It **also suppresses the confidence transition** (#776, P1-b): a short gap
     * entirely within the dormant window can satisfy `gapAgrees()` and would otherwise raise
     * confidence despite testing nothing about the schedule. `frozen` alone does not suppress that
     * transition. The pure function must also leave an uninitialised confidence `null`, rather than
     * burning `null → 0` and disabling a future cold-start bootstrap (#776, P1-1).
     *
     * If this spanning gap ends outside dormancy, [applyDormancyExitDecrement] applies a separate
     * one-point confidence decrease after the suppressed transition and writes DORMANCY_EXIT as a
     * second adjustment. It acts only when the result's confidence is non-null and this dormancy
     * cycle has not recorded an exit already. Backdated inserts between existing waterings must not
     * decrement the same cycle twice (#776, P1-2).
     */
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

    /**
     * Tail of [adapt]: `null` confidence means there is nothing to decrement from (#776, P1-1),
     * and an exit already recorded for this dormancy cycle is a no-op (#776, P1-2). Confidence is
     * floored at zero. The DORMANCY_EXIT adjustment is separate from the DORMANCY_EXCLUDED row:
     * the base did not move, while confidence moved for an independent reason.
     */
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

    /**
     * #699/#761 (product ADR-0044 — #776, P1-2): whether a
     * [WateringAdjustmentTrigger.DORMANCY_EXIT] has already been recorded for the same dormancy
     * cycle [loggedAt] belongs to, so a backdated watering inserted *between* two already-existing
     * waterings cannot re-decrement confidence for a cycle another observation already exited.
     * Two [WateringAdjustment] rows share a cycle when [DormancyWindow.spansDormancy] finds **no**
     * dormant month between them — the same uninterrupted wakeful stretch. Reusing this predicate
     * avoids inventing separate cycle-identity bookkeeping. A row from a different earlier or later
     * cycle has a full dormant window between it and [loggedAt], so it does not suppress this one.
     * The comparison uses the observation date, never the wall-clock entry date (#776, P1).
     */
    private suspend fun alreadyRecordedDormancyExit(plant: Plant, loggedAt: Long): Boolean =
        wateringAdjustmentRepository?.getByTrigger(plant.id, WateringAdjustmentTrigger.DORMANCY_EXIT)?.any {
            !DormancyWindow.spansDormancy(
                plant.dormancyStartMonth,
                plant.dormancyEndMonth,
                minOf(it.triggeredAt, loggedAt),
                maxOf(it.triggeredAt, loggedAt)
            )
        } ?: false

    /**
     * #699/#761 (product ADR-0044 — #776, P1-3): before this fix, the bootstrap returned early
     * before `dormancySpanning` was computed, so its triggering observation got only a
     * [WateringAdjustmentTrigger.HISTORY_BOOTSTRAP] row and silently lost the dormancy provenance
     * shown by "Why this date?" for other spanning observations. This DORMANCY_EXCLUDED row has
     * `before == after`: the bootstrap's own median/gapCount already filters the dormant gap, so
     * this row is transparency only.
     *
     * **Deliberately no [WateringAdjustmentTrigger.DORMANCY_EXIT] row or extra decrement when
     * bootstrap wins.** Bootstrapped confidence is a wholesale re-derivation (`gapCount / 3`, capped
     * at 5), not an incremental step off the prior confidence. There is no principled minus one to
     * layer onto it. An exit row would also falsely claim normal per-step exit evaluation occurred.
     * This precedence was resolved by design in #779 and tested in #776.
     */
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

    /**
     * The #571 cold-start opportunity on every WATER observation: a never-adapted plant
     * ([Plant.wateringConfidence] is `null`, using whole history) or a pending post-reset plant
     * ([Plant.wateringResetAt] is non-null, using history at or after the freeze boundary). Returns
     * false when neither applies or fewer than [CareSchedule.MIN_BOOTSTRAP_GAPS] eligible gaps exist.
     *
     * [feedback] is passed to [WateringLifecycleReset.BootstrapRequest] so a late "Soil was still
     * moist" observation ([WateringFeedback.TOO_SOON]) cannot make the median bootstrap shorten
     * the interval, contrary to product ADR-0033's guarantee (#649 follow-up). That request floors
     * the bootstrapped base at the plant's prior interval for this feedback.
     *
     * [loggedAt] may be backdated. [WateringLifecycleReset.maybeBootstrap] gets a separate, always
     * real-wall-clock `displayNow` for converting the new base to today's effective interval (#679).
     * The adjustment's `triggeredAt` and [Plant.updatedAt] still use [loggedAt]. The quick caller's
     * injectable `nowProvider` deliberately does not control this bootstrap display clock.
     */
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

    /**
     * The season function used by [WateringLifecycleReset.maybeBootstrap] and
     * [CareSchedule.bootstrapBaseInterval] to de-seasonalize each historical gap. It returns
     * `{ 1.0 }` when [Plant.pinIntervalToBase] is set or amplitude is Off, matching the live
     * observation's [deseasonalizedObservedIntervalDays] and [currentAdaptiveBaseIntervalDays].
     */
    @Suppress("ReturnCount")
    private suspend fun seasonFnFor(plant: Plant): (LocalDate) -> Double {
        if (plant.pinIntervalToBase) return { 1.0 }
        val amplitude = dataStore?.seasonalAmplitudeOnce() ?: 0.0
        if (amplitude == 0.0) return { 1.0 }
        val hemisphere = SeasonalWatering.currentHemisphere()
        return { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
    }

    /**
     * Dormancy takes priority over a REPOT freeze when both apply: it is the more specific,
     * user-declared cause. A frozen observation is not labelled WATER_NOT_ATTRIBUTED, since the
     * user was never asked to attribute it; these triggers explain distinct exclusions (#571,
     * #699/#761, technical ADR-0023 and product ADR-0044).
     */
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

    /**
     * Confidence is committed for every qualifying observation. A new precise seasonal base is
     * committed here only when today's effective display value is unchanged; a visible change is
     * left for the user's suggestion Apply path (#717/#718, technical ADR-0027). Use [loggedAt] for
     * [Plant.updatedAt] too: the form used to stamp this one update with entry-time wall clock while
     * all other adaptive observation writes used the logged date (#780 follow-up to #776, P1).
     */
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

    /**
     * Converts a base-space [suggestion] to effective display space through the same
     * [CareSchedule.effectiveWateringIntervalDaysForDisplay] wrapper used by "Why this date?" and
     * Plant Detail (#620), so the suggestion gate cannot drift from the displayed due-date math.
     * Treats [suggestion] as if it were the plant's new base; this is a display-only, one-way
     * conversion and does not round or rewrite the precise base passed to the Apply path.
     *
     * **[displayNow], not the observation's `loggedAt` (#716 review round 1).** Both sides of the
     * comparison are values shown *today*. The #654-era conversion used the logged day because the
     * other side was the stale `wateringIntervalDays` literal. Once #716 made both sides live,
     * converting on a backdated day could silently reintroduce the same mismatch. Observation
     * gap math and adjustment timestamps remain anchored to the logged day.
     */
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

    /**
     * The watering-model input for `currentBaseIntervalDays` (#572, amending technical ADR-0021):
     * season-neutral, reading [Plant.wateringBaseIntervalDays] instead of the raw, possibly stale
     * [configuredIntervalDays] whenever amplitude is on and the plant is not pinned. Previously the
     * model could use a value that changed only on manual edits while [CareSchedule.computeStatus]
     * used the seasonally adjusted base for the actual due date. Pinned and amplitude-Off plants
     * continue to use [configuredIntervalDays] verbatim.
     */
    @Suppress("ReturnCount")
    suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Double {
        if (plant.pinIntervalToBase) return configuredIntervalDays.toDouble()
        if ((dataStore?.seasonalAmplitudeOnce() ?: 0.0) == 0.0) return configuredIntervalDays.toDouble()
        return plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()
    }

    /**
     * "Interaction with Part 1" (#569): `observedBase = observedGap / season(dateOfGap)`, so a
     * seasonal correction is not baked into [Plant.wateringConfidence] as a permanent change in
     * thirst. A no-op when [dataStore] is null, amplitude is Off, or [pinned] is true — the due-date
     * math never applies the curve to a pinned plant, so its observed gaps are already flat.
     * [loggedAt] is the observation day, possibly backdated (#654); using wall-clock today here
     * would de-seasonalize the wrong month while the log and adjustment claim an earlier date.
     */
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

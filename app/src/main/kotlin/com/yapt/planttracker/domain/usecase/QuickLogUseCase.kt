package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDao
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Shared quick-log business logic used by both `PlantListViewModel` (PlantCard quick-log buttons)
 * and `CalendarViewModel` (day-sheet quick-log buttons), extracted to avoid duplicating the same
 * ~150 lines of care-log + adaptive-interval + photo-reminder logic across both ViewModels.
 *
 * Each ViewModel resolves its own [Plant] from its own `plantsWithStatus` state, calls the
 * relevant method here, and maps the returned domain object (message string, [QuickWaterSuggestion],
 * or [PhotoReminderRequest]) onto its own StateFlow/SharedFlow. This use case owns no UI-facing
 * state itself — every method is a plain suspend function.
 */
// #568 added two small adaptive-watering helpers to this already-cohesive choke point; splitting
// them out would scatter closely related logic across files for no readability gain.
@Suppress("TooManyFunctions", "LongParameterList")
class QuickLogUseCase(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val database: PlantDatabase,
    private val wateringAdjustmentRepository: WateringAdjustmentRepository,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val onWaterLogged: suspend (Long) -> Unit = {}
) {

    /**
     * Result of a quick-log attempt. [logged] is false when the log was skipped because [plant]
     * already has a WATER/FERTILIZE log for the current calendar day (#509) — callers should skip
     * side effects (adaptive-interval suggestion, photo-reminder check) in that case. [waterPaired]
     * is true only when a liquid-fertilizer quick action actually inserted the paired WATER log
     * (it's suppressed, but FERTILIZE still proceeds, when the plant was already watered today).
     */
    data class QuickLogOutcome(
        val message: String,
        val logged: Boolean,
        val waterPaired: Boolean = false,
        val suggestion: QuickWaterSuggestion? = null,
        val waterLoggedAt: Long? = null
    )

    /** Summary of a [bulkLog] run: how many of [totalCount] plants were actually logged vs. skipped. */
    data class BulkLogResult(val loggedCount: Int, val skippedCount: Int, val totalCount: Int)

    /**
     * Result of [applyWateringIntervalSuggestion] — the plant's actual prior
     * [Plant.wateringIntervalDays]/[Plant.wateringBaseIntervalDays], alongside the effective value that
     * was actually written. Pre-#644 this differed from the raw base-space `newInterval` handed in
     * (#626); since #644, `newInterval` is itself already effective-space, so [newEffectiveIntervalDays]
     * is just that same value echoed back — kept as its own field for callers ([Event.SilentIntervalApplied]
     * etc.) that read it without needing to know the input parameter's semantics changed.
     */
    data class IntervalApplyResult(
        val previousEffectiveIntervalDays: Int,
        val previousBaseIntervalDays: Double?,
        val newEffectiveIntervalDays: Int
    )

    /**
     * Logs [careType] for every plant in [plants] inside a single Room transaction, so a bulk
     * care action is applied atomically — a killed process can't leave some of the selected
     * plants logged and others not (#448). Watering carries no reason (see below); other
     * care types route through [quickLog] so liquid-fertilizer plants still get a paired watering.
     * Plants that already have today's log for [careType] are skipped (#509) rather than aborting
     * the whole batch. Per-plant interval-suggestion and photo-reminder side effects are
     * intentionally not surfaced here — bulk callers skip those dialogs.
     *
     * A bulk watering carries no reason: the user never saw a per-plant prompt, so it passes `null`
     * (#586, product ADR-0030 — replacing the defaulted [WateringFeedback.JUST_RIGHT] this used to
     * write). For an on-schedule plant that is the same quiet gap observation as before; for an
     * off-schedule one the adaptive model excludes it from base learning, which is the safe direction
     * for an action the user took without attributing anything to any particular plant.
     */
    suspend fun bulkLog(plants: List<Plant>, careType: CareType): BulkLogResult {
        var loggedCount = 0
        var latestWaterLoggedAt: Long? = null
        database.withTransaction {
            for (plant in plants) {
                val outcome = if (careType == CareType.WATER) {
                    quickWaterWithReasonInternal(
                        plant,
                        reason = null,
                        loggedAt = System.currentTimeMillis(),
                        schedulePostWateringReminder = false
                    )
                } else {
                    quickLogInternal(
                        plant,
                        careType,
                        loggedAt = System.currentTimeMillis(),
                        schedulePostWateringReminder = false
                    )
                }
                if (outcome.logged) loggedCount++
                outcome.waterLoggedAt?.let { latestWaterLoggedAt = it }
            }
        }
        latestWaterLoggedAt?.let { onWaterLogged(it) }
        return BulkLogResult(
            loggedCount = loggedCount,
            skippedCount = plants.size - loggedCount,
            totalCount = plants.size
        )
    }

    /**
     * Logs [careType] for [plant]. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a [careType] log today (WATER/FERTILIZE only, #509). For a
     * liquid-fertilizer plant, the "already watered today" check runs before the FERTILIZE insert
     * so the paired WATER insert can be suppressed without racing against itself.
     *
     * [loggedAt] defaults to "now" but Plant Detail's Repot tab action (#694) can pass a backdated
     * timestamp instead, mirroring [quickWaterWithReason]'s parameter of the same name (#654) — the
     * same value drives the duplicate-day check (WATER/FERTILIZE only; REPOT is never guarded), the
     * [CareLog] write, the paired liquid-fertilizer WATER insert, the post-watering reminder debounce,
     * and [maybeApplyRepotReset]'s reset anchor, so none of them can drift from each other or silently
     * fall back to the real wall-clock time. Only [quickRepot][com.yapt.planttracker.ui.screens
     * .plantdetail.PlantDetailViewModel.quickRepot] passes a non-default value; every other caller
     * (`bulkLog`, Plant List, Calendar, plain `quickFertilize`) keeps using real "now".
     */
    suspend fun quickLog(
        plant: Plant,
        careType: CareType,
        loggedAt: Long = System.currentTimeMillis()
    ): QuickLogOutcome = quickLogInternal(plant, careType, loggedAt, schedulePostWateringReminder = true)

    private suspend fun quickLogInternal(
        plant: Plant,
        careType: CareType,
        loggedAt: Long,
        schedulePostWateringReminder: Boolean
    ): QuickLogOutcome {
        if (isDuplicateGuarded(careType) && hasLoggedToday(plant.id, careType, loggedAt)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, careType), logged = false)
        }
        val alreadyWateredToday = careType == CareType.FERTILIZE &&
            plant.useLiquidFertilizer &&
            hasLoggedToday(plant.id, CareType.WATER, loggedAt)

        val log = CareLog(
            plantId = plant.id,
            careType = careType,
            loggedAt = loggedAt,
            wateringFeedback = null,
            fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
        )
        careLogRepository.addLog(log)
        maybeApplyRepotReset(plant, careType, loggedAt)
        val waterPaired = careType == CareType.FERTILIZE && plant.useLiquidFertilizer && !alreadyWateredToday
        if (waterPaired) {
            // No reason: the user fertilized, and the watering came along with it (product ADR-0008) — they
            // were never asked why they watered, so nothing is attributed (#586).
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = loggedAt,
                    wateringFeedback = null
                )
            )
            clearWateringOverrideIfActive(plant.id)
        }
        val message = when (careType) {
            CareType.FERTILIZE -> if (waterPaired) {
                application.getString(R.string.quick_log_watered_and_fertilized, plant.name)
            } else {
                application.getString(R.string.quick_log_fertilized, plant.name)
            }
            else -> application.getString(
                R.string.quick_log_other,
                application.getString(careType.labelRes()),
                plant.name
            )
        }
        val waterLoggedAt = notifyWaterLoggedIfNeeded(
            careType,
            waterPaired,
            loggedAt,
            schedulePostWateringReminder
        )
        return QuickLogOutcome(
            message = message,
            logged = true,
            waterPaired = waterPaired,
            waterLoggedAt = waterLoggedAt
        )
    }

    private suspend fun notifyWaterLoggedIfNeeded(
        careType: CareType,
        waterPaired: Boolean,
        loggedAt: Long,
        schedulePostWateringReminder: Boolean
    ): Long? {
        if (careType != CareType.WATER && !waterPaired) return null
        if (schedulePostWateringReminder) onWaterLogged(loggedAt)
        return loggedAt
    }

    /**
     * Logs a watering with the given [reason] (#586, product ADR-0030), clears any active skip
     * override, and returns a [QuickLogOutcome] with a [QuickWaterSuggestion] if the adaptive
     * interval system produces one. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a WATER log on the calendar day containing [loggedAt] (#509).
     *
     * [reason] is `null` for an on-schedule watering (no prompt appears at all — the fast path), for
     * a watering the user logged without choosing a reason, and for surfaces that never ask (bulk
     * log, the notification's "Watered" action). Which of those it was is never stored: the model
     * separates them from timing alone (see [CareSchedule.computeAdaptiveInterval]).
     *
     * [loggedAt] defaults to "now" but Plant Detail's "Log watering" date picker (#654) can pass a
     * backdated timestamp instead — the same value drives the duplicate-day check, the [CareLog] write,
     * and the adaptive-gap math ([computeSuggestion]/[adaptWateringInterval]'s `now`), so none of the
     * three can drift from each other or silently fall back to the real wall-clock time.
     *
     * The active [Plant.wateringDueDateOverride] is cleared only when this WATER log actually becomes
     * the plant's newest one (#679) — `>=` the previous newest WATER's `loggedAt`, queried *before* this
     * insert via [CareLogRepository.getLastTwoWaterings]. Backfilling an old, forgotten watering from
     * before an active reschedule was made would otherwise silently discard that unrelated reschedule
     * even though the backfilled entry has no bearing on the current due date. No prior WATER log at all
     * (`null`) is treated as `Long.MIN_VALUE`, so the plant's first-ever WATER log always clears an
     * active override, matching the unconditional-clear behavior this replaces for that case.
     */
    suspend fun quickWaterWithReason(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long = System.currentTimeMillis()
    ): QuickLogOutcome = quickWaterWithReasonInternal(
        plant,
        reason,
        loggedAt,
        schedulePostWateringReminder = true
    )

    private suspend fun quickWaterWithReasonInternal(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long,
        schedulePostWateringReminder: Boolean
    ): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.WATER, loggedAt)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.WATER), logged = false)
        }
        val feedback = feedbackUnlessDormancySpanning(plant, reason, loggedAt)
        val previousNewest = careLogRepository.getLastTwoWaterings(plant.id).firstOrNull()?.loggedAt
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.WATER,
                loggedAt = loggedAt,
                wateringFeedback = feedback
            )
        )
        val freshPlant = if (loggedAt >= (previousNewest ?: Long.MIN_VALUE)) {
            clearWateringOverrideIfActive(plant.id) ?: plant
        } else {
            plant
        }
        val suggestion = computeSuggestion(freshPlant, feedback, loggedAt)
        if (schedulePostWateringReminder) onWaterLogged(loggedAt)
        return QuickLogOutcome(
            message = application.getString(R.string.quick_log_watered, plant.name),
            logged = true,
            suggestion = suggestion,
            waterLoggedAt = loggedAt
        )
    }

    /**
     * Logs a paired FERTILIZE + WATER entry for liquid-fertilizer plants, mirroring
     * [quickWaterWithReason] — the paired watering is a watering like any other, so the same #586
     * reason prompt governs it. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a FERTILIZE log on the calendar day containing [loggedAt]. If
     * [plant] was already watered that same day, the paired WATER insert is suppressed (checked before
     * the FERTILIZE insert so it can't race against a WATER row inserted earlier in this same call)
     * but the FERTILIZE log still proceeds (#509).
     *
     * [loggedAt] mirrors [quickWaterWithReason]'s parameter of the same name (#654) — the same value
     * drives both duplicate-day checks, both [CareLog] writes, and the paired watering's adaptive-gap
     * math. The paired WATER insert's override-clear gate mirrors [quickWaterWithReason]'s own (#679).
     */
    suspend fun quickLiquidFertilizeWithReason(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long = System.currentTimeMillis()
    ): QuickLogOutcome = quickLiquidFertilizeWithReasonInternal(
        plant,
        reason,
        loggedAt,
        schedulePostWateringReminder = true
    )

    private suspend fun quickLiquidFertilizeWithReasonInternal(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long,
        schedulePostWateringReminder: Boolean
    ): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.FERTILIZE, loggedAt)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.FERTILIZE), logged = false)
        }
        val feedback = feedbackUnlessDormancySpanning(plant, reason, loggedAt)
        val alreadyWateredToday = hasLoggedToday(plant.id, CareType.WATER, loggedAt)

        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.FERTILIZE,
                loggedAt = loggedAt,
                wateringFeedback = null,
                fertilizerType = FertilizerType.LIQUID
            )
        )

        return if (alreadyWateredToday) {
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_fertilized, plant.name),
                logged = true,
                waterPaired = false
            )
        } else {
            val previousNewest = careLogRepository.getLastTwoWaterings(plant.id).firstOrNull()?.loggedAt
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = loggedAt,
                    wateringFeedback = feedback
                )
            )
            val freshPlant = if (loggedAt >= (previousNewest ?: Long.MIN_VALUE)) {
                clearWateringOverrideIfActive(plant.id) ?: plant
            } else {
                plant
            }
            if (schedulePostWateringReminder) onWaterLogged(loggedAt)
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_watered_and_fertilized, plant.name),
                logged = true,
                waterPaired = true,
                suggestion = computeSuggestion(freshPlant, feedback, loggedAt),
                waterLoggedAt = loggedAt
            )
        }
    }

    /**
     * The single write path for committing a new [Plant.wateringIntervalDays] from a product ADR-0006
     * adaptive suggestion (#572) — shared by the Plant Detail dialog's Apply button/silent-apply path,
     * the Calendar suggestion dialog, and the Plant List suggestion dialog (#631). Before this fix each
     * of the three screens carried its own independent copy of this exact math; only Plant Detail's had
     * been fixed for #572 (base dual-write) and #626 (effective-space conversion), and Calendar/Plant
     * List silently reproduced both bugs. Now there is exactly one implementation.
     *
     * [originalSuggestion] is the interval that was originally suggested before any retyping — still
     * **base-space** (this class's own adaptive suggestion, e.g. [QuickWaterSuggestion.suggestedInterval]
     * / `PendingWateringSuggestion.rawIntervalDays`) — `null` disables
     * [CareSchedule.confidenceAfterDialogEdit] (no caller passes `null` today; kept for parity with call
     * sites that may not always have one).
     *
     * **[newInterval] is *effective*-space (#644)**, not base-space as it was before this fix — every
     * one of the three dialogs' editable text fields now pre-fills from (and, on Apply, submits) the
     * same effective, seasonally-converted number the dialog's "Suggested: N days" sentence already
     * shows, so the field and the sentence can no longer disagree (#644's bug). [Plant.wateringIntervalDays]
     * is read everywhere else as that same effective value (the "currently" figure, the Water tab
     * slider, [com.yapt.planttracker.domain.schedule.WateringExplanationBuilder]), so [newInterval] is
     * now written to it **directly** — no re-conversion, unlike pre-#644 where a base-space input was
     * run through [CareSchedule.effectiveWateringIntervalDaysForDisplay] before the write. What used to
     * be that conversion is now inverted: [SeasonalWatering.deseasonalize] derives the base-space
     * equivalent of [newInterval] once (`newIntervalBaseSpace`, mirroring
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel]'s `deseasonalizedBaseOrNull`/
     * `setWateringInterval` manual-edit precedent), reused both for the [Plant.wateringBaseIntervalDays]
     * dual-write (#572) below and for the confidence-tolerance comparison against [originalSuggestion]
     * (see the comment there for why that comparison needs it). Gated on the same
     * `!plant.pinIntervalToBase && amplitude != 0.0` condition as before (#584 review round 2) — pinned
     * or zero-amplitude plants keep [newInterval] as a literal value and leave the stored base untouched
     * rather than clobbering a real prior base with one that was never seasonally converted.
     *
     * Also writes a [WateringAdjustmentTrigger.DIALOG_EDIT] row whose
     * `afterIntervalDays` deliberately stays base-space (`newIntervalBaseSpace`, rounded) — the model's
     * base-space accounting, not the user-facing effective value now written into
     * [Plant.wateringIntervalDays] — this divergence from the literal value is intentional and unchanged
     * from before #644, only the source value used to derive it has changed.
     *
     * **[suggestedBaseInterval] (#718, technical ADR-0027)** short-circuits the [SeasonalWatering.deseasonalize]
     * derivation above: when non-null (and the plant is season-adjustable), it is persisted to
     * [Plant.wateringBaseIntervalDays] verbatim instead of re-deriving the base from the already-rounded
     * [newInterval] — that round-trip divides a `±0.5`-day rounding residual by `season(today)`, ratcheting
     * the base upward on every seasonal-threshold-triggered apply. Callers pass the adaptive model's
     * precise `Double` base only when [newInterval] is the dialog's unedited pre-fill; a `null` here is the
     * caller's signal that the user retyped the field, so the value still de-seasonalizes through the
     * existing path (#644's typed-number contract, unchanged).
     */
    suspend fun applyWateringIntervalSuggestion(
        plant: Plant,
        originalSuggestion: Int?,
        newInterval: Int,
        suggestedBaseInterval: Double?
    ): IntervalApplyResult {
        val now = System.currentTimeMillis()
        // When amplitude is 0 (SeasonalAmplitude.OFF) or the plant is pinned, newInterval is a
        // *literal* value, not base-space-convertible, so it's used as-is for the confidence check
        // and the base is left untouched below rather than clobbered with a never-seasonally-converted
        // value (#584 review round 2, still applies post-#644).
        val amplitude = dataStore.seasonalAmplitudeOnce()
        val seasonAdjustable = !plant.pinIntervalToBase && amplitude != 0.0
        val newIntervalBaseSpace = if (seasonAdjustable && suggestedBaseInterval != null) {
            suggestedBaseInterval
        } else if (seasonAdjustable) {
            SeasonalWatering.deseasonalize(
                newInterval.toDouble(),
                nowProvider().toLocalDate(),
                amplitude,
                SeasonalWatering.currentHemisphere()
            )
        } else {
            newInterval.toDouble()
        }
        // Retyping the suggested number before tapping Apply is fine-tuning within the model, not a
        // rejection of it — never a full reset like an AddEditPlant edit (#568). Outside
        // GAP_AGREEMENT_TOLERANCE of the original suggestion, the suggestion was materially wrong and
        // confidence falls, but the model still stands. A silent apply always passes an unedited
        // newInterval, so confidence never falls from an apply the user never edited.
        // #644: originalSuggestion is base-space, newInterval is now effective-space — compare like for
        // like by converting newInterval back down to newIntervalBaseSpace (computed above and reused
        // for the wateringBaseIntervalDays write below) rather than converting originalSuggestion up,
        // since the base-space conversion is already needed regardless of this check.
        val wateringConfidence = if (originalSuggestion != null) {
            CareSchedule.confidenceAfterDialogEdit(
                plant.wateringConfidence,
                originalSuggestion,
                newIntervalBaseSpace.roundToInt()
            )
        } else {
            plant.wateringConfidence
        }
        val newBaseIntervalDays = if (seasonAdjustable) newIntervalBaseSpace else plant.wateringBaseIntervalDays
        val previousEffectiveIntervalDays = plant.wateringIntervalDays ?: newInterval
        val previousBaseIntervalDays = plant.wateringBaseIntervalDays
        // #644: newInterval is effective-space now — write it to wateringIntervalDays directly, no
        // re-conversion (that used to be #626's fix; it's now folded into the deseasonalize step above).
        plantRepository.updatePlant(
            plant.copy(
                wateringIntervalDays = newInterval,
                wateringBaseIntervalDays = newBaseIntervalDays,
                wateringConfidence = wateringConfidence,
                updatedAt = now
            )
        )
        // #584 review: `previousEffectiveIntervalDays` may still be a literal effective value
        // rather than base-space — read the plant's actual current base for the row instead. #626:
        // this row stays base-space and deliberately keeps diverging from the literal
        // wateringIntervalDays now written above — it represents the model's base-space
        // accounting, not the user-facing effective value.
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.DIALOG_EDIT,
                beforeIntervalDays = currentAdaptiveBaseIntervalDays(plant, previousEffectiveIntervalDays).roundToInt(),
                afterIntervalDays = newIntervalBaseSpace.roundToInt()
            )
        )
        return IntervalApplyResult(previousEffectiveIntervalDays, previousBaseIntervalDays, newInterval)
    }

    /**
     * Dismissing the product ADR-0006 suggestion dialog without applying (explicit Dismiss tap, or tapping
     * outside it) — the single write path shared by the Plant Detail, Calendar, and Plant List
     * dismiss actions (#674). Before this fix each of the three screens carried its own copy of the
     * confidence bump, but only Plant Detail's also wrote the matching
     * [WateringAdjustmentTrigger.DIALOG_DISMISSAL] row — Calendar and Plant List silently changed
     * confidence with no entry ever surfacing in the "Why this date?" sheet's "Recent adjustments"
     * list. Now there is exactly one implementation.
     *
     * A genuine dismissal raises [Plant.wateringConfidence] up to
     * [CareSchedule.DISMISSAL_CONFIDENCE_CEILING] (#568) — the user is saying the current schedule is
     * fine — and, only when [Plant.wateringIntervalDays] is configured, writes a no-op
     * [WateringAdjustment] row (`beforeIntervalDays == afterIntervalDays`, the base-space reference via
     * [currentAdaptiveBaseIntervalDays], mirroring [applyWateringIntervalSuggestion]'s own row) so
     * "Recent adjustments" reflects the dismissal even though nothing about the interval itself moved.
     * Returns the freshly-persisted [Plant] so each caller can update its own ViewModel-scoped state
     * off the same value rather than re-fetching.
     */
    suspend fun recordWateringSuggestionDismissal(plant: Plant): Plant {
        val now = System.currentTimeMillis()
        val updated = plant.copy(
            wateringConfidence = CareSchedule.confidenceAfterDismissal(plant.wateringConfidence),
            updatedAt = now
        )
        plantRepository.updatePlant(updated)
        plant.wateringIntervalDays?.let { current ->
            val currentBase = currentAdaptiveBaseIntervalDays(plant, current)
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = plant.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.DIALOG_DISMISSAL,
                    beforeIntervalDays = currentBase.roundToInt(),
                    afterIntervalDays = currentBase.roundToInt()
                )
            )
        }
        return updated
    }

    /**
     * Records a reschedule: a plain [Plant.wateringDueDateOverride] write to [newDueAtMillis],
     * nothing else (#738, product ADR-0039 — a reschedule is model-neutral again). Never writes a
     * [CareType.CHECK] log, [Plant.wateringConfidence], [Plant.wateringIntervalDays]/
     * [Plant.wateringBaseIntervalDays], or a [WateringAdjustment] row. All learning comes from the
     * next actual watering, which already has its own reason prompt at the moment the gap is
     * genuinely measured.
     *
     * Uses the column-specific [PlantRepository.updateWateringDueDateOverride] rather than a
     * full-row `updatePlant()` off a possibly-stale [plant] snapshot — same rationale as
     * [PlantDao.updateWateringBaseInterval] (#703 review round 3): a statement that can't touch a
     * column it doesn't name eliminates any race with a concurrent write to a different column
     * entirely, rather than just narrowing its window.
     */
    suspend fun recordReschedule(plant: Plant, newDueAtMillis: Long) {
        plantRepository.updateWateringDueDateOverride(plant.id, newDueAtMillis, System.currentTimeMillis())
    }

    /**
     * Builds a [PhotoReminderRequest] for [plantId] if the feature is enabled, the plant hasn't
     * already been reminded this session (shared across surfaces via
     * [PhotoReminderPolicy.shownThisSession]), and the newest photo across plant photos and
     * care-log photos is at least [PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS] days old.
     * Returns null when no reminder should be shown. Callers decide what to do with the result
     * (e.g. suppress it while another dialog is showing) — that UI-level rule stays in the screen.
     */
    suspend fun maybeBuildPhotoReminderRequest(plantId: Long): PhotoReminderRequest? {
        val enabled = dataStore.data.first()[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false
        if (!enabled) return null
        if (plantId in PhotoReminderPolicy.shownThisSession) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val lastPlantPhotoTs = plantPhotoRepository.getPhotosForPlantOnce(plantId)
            .maxOfOrNull { it.capturedAt }
        val lastCareLogPhotoTs = careLogRepository.getPhotoLogsForPlant(plantId).first()
            .mapNotNull { log -> log.photoUri?.let { log.loggedAt } }
            .maxOrNull()
        val lastPhotoTs = listOfNotNull(lastPlantPhotoTs, lastCareLogPhotoTs).maxOrNull()
        val daysSince = PhotoReminderPolicy.lastPhotoDaysSince(lastPhotoTs, plant.createdAt)
        if (daysSince >= PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS) {
            PhotoReminderPolicy.shownThisSession.add(plantId)
            return PhotoReminderRequest(plantId, plant.name, daysSince)
        }
        return null
    }

    private fun isDuplicateGuarded(careType: CareType) =
        careType == CareType.WATER || careType == CareType.FERTILIZE

    /**
     * [dayTimestampMs] defaults to "now" but a caller backdating a log (#654) passes the chosen date
     * instead, so the duplicate-day guard keys off the *logged* day, not the wall-clock day the check
     * happens to run on.
     */
    private suspend fun hasLoggedToday(
        plantId: Long,
        careType: CareType,
        dayTimestampMs: Long = System.currentTimeMillis()
    ): Boolean = careLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs)

    /**
     * `reason?.toWateringFeedback()`, suppressed to `null` when the gap [loggedAt]'s own chronological
     * predecessor → [loggedAt] overlaps [plant]'s dormancy window (#699/#761, product ADR-0044 — Codex
     * review round 1 on #776, P2-c). The three production UI surfaces already gate the reason prompt
     * itself on this exact condition (`WateringReasonGate.kt`/`CalendarScreen`/`PlantListScreen`'s
     * `isWateringGapDormancySpanning`/`isChosenDateDormancySpanning` checks), so in practice [reason] is
     * already `null` here — this is defense-in-depth at the model layer, mirroring why [frozen] inside
     * [CareSchedule.computeAdaptiveInterval] is "derived inside the pure function, never passed in": a
     * persisted [CareLog.wateringFeedback] on a dormancy-spanning log would otherwise still enter a
     * later [CareSchedule.correctionStreak] window even though this observation's own base/confidence
     * transition is separately excluded inside [adaptWateringInterval]. Queries
     * [CareLogRepository.getLastWateringBefore] itself (a second query beyond [computeSuggestion]'s own
     * identical lookup moments later) rather than threading a value through, since this runs *before*
     * the [CareLog] insert while [computeSuggestion] necessarily runs after — the predecessor is
     * unaffected either way ([loggedAt]'s own log is never its own predecessor).
     */
    @Suppress("ReturnCount")
    private suspend fun feedbackUnlessDormancySpanning(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long
    ): WateringFeedback? {
        val feedback = reason?.toWateringFeedback() ?: return null
        val previousWateringAt = careLogRepository.getLastWateringBefore(plant.id, loggedAt)?.loggedAt
            ?: return feedback
        val dormancySpanning = DormancyWindow.spansDormancy(
            plant.dormancyStartMonth,
            plant.dormancyEndMonth,
            previousWateringAt,
            loggedAt
        )
        return feedback.takeUnless { dormancySpanning }
    }

    private fun alreadyLoggedMessage(plant: Plant, careType: CareType): String = when (careType) {
        CareType.WATER -> application.getString(R.string.quick_log_already_watered, plant.name)
        CareType.FERTILIZE -> application.getString(R.string.quick_log_already_fertilized, plant.name)
        else -> error("No duplicate guard defined for $careType")
    }

    /**
     * [feedback] may be `null` (#570, product ADR-0027) — the quick-water sheet's chip collapsed to
     * one optional flag, so `null` is now the dominant case; the adaptive model accepts `null` directly.
     *
     * Gates on a **live-to-live, effective-space** comparison (#716) — the pre-observation model's
     * base run through today's season versus the post-observation model's base run through the same
     * today, **never** the stale `plant.wateringIntervalDays` literal that used to stand in for
     * "current". That literal is only rewritten on a manual edit / suggestion apply / the #571
     * bootstrap / the #702 fixup, while the true effective value moves every day the seasonal curve
     * crosses a rounding threshold — comparing a live number against that stale one flagged pure
     * calendar drift as a model change and attributed it to whichever watering happened to be logged
     * that day. [currentEffective] is exactly what [com.yapt.planttracker.domain.schedule
     * .CareSchedule.effectiveWateringIntervalDaysForDisplay] would show for [plant] **before** this
     * observation, reusing [currentAdaptiveBaseIntervalDays]/[effectiveIntervalForDisplay] — the same
     * two helpers [effectiveSuggestion] itself is built from — so "did the model change what's shown
     * today" can't drift from "what's shown today" by construction. [current] is unchanged and is
     * still the literal fed into [adaptWateringInterval] as the base-fallback input (unrelated to this
     * display-space comparison).
     *
     * **Two separate clocks, on purpose (#716 review round 1).** [now] is the observation's own
     * timestamp — the just-inserted log's own `loggedAt` (real "now", or Plant Detail's #654 backdated
     * pick) — and drives every piece of *observation* math: the gap computation below, the model input
     * ([adaptWateringInterval]), and what gets persisted ([persistAdaptiveState]'s `updatedAt` /
     * [WateringAdjustment.triggeredAt]). [displayNow] drives only the two [effectiveIntervalForDisplay]
     * calls below — [effectiveSuggestion] and [currentEffective] are both numbers the user reads
     * **today**, on screen, regardless of what date the watering being logged claims to have happened
     * on. Evaluating them at a backdated [now] instead (the bug this split fixes) can fire a spurious
     * dialog for a backdated log whose displayed number wouldn't actually change today, or — worse —
     * silently apply a real display change without ever asking, since the silent-apply path only runs
     * once this gate has decided nothing needs asking.
     *
     * Defaults to [nowProvider] — deliberately **not** a fresh `System.currentTimeMillis()` call, unlike
     * [maybeApplyHistoryBootstrap]'s otherwise-identical `displayNow` split (#679): that object-level
     * `WateringLifecycleReset` function has no injectable clock of its own to reuse, while this method
     * lives inside [QuickLogUseCase], which already has one — [nowProvider] itself defaults to
     * `System::currentTimeMillis`, so production behavior is identical either way, and every existing
     * test that pins [nowProvider] to a fixed instant (and never independently backdates [now]) keeps
     * working unchanged, since [now] and [displayNow] still resolve to the same instant when a caller
     * doesn't explicitly diverge them. Only a caller that explicitly passes a [now] different from
     * [nowProvider]'s current value (Plant Detail's #654 backdated quick-water; equivalently, a test
     * pinning both clocks independently) exercises the split at all.
     *
     * The observed gap is computed against [now]'s own chronological predecessor
     * ([CareLogRepository.getLastWateringBefore], strictly earlier `loggedAt`), not "the two globally
     * newest waterings" (#654 round-2 review fix) — [now] is the just-inserted log's own `loggedAt`
     * (real "now", or Plant Detail's backdated pick), and the two can disagree once a caller backdates
     * a log to a date *before* an already-existing WATER log: the old `getLastTwoWaterings()`-based
     * query would pair the new log with that later, already-existing one (or skip the new log's real
     * neighbor entirely) instead of the log the new one actually follows.
     *
     * `internal`, not `private` (#716 review round 1) — mirrors `PlantDetailScreen.kt`'s
     * `isChosenDateOnSchedule`/`isChosenDateGapLong` precedent (#679 review round 1,
     * `.claude/rules/plant-detail.md`): widened specifically so a plain JVM test can exercise the
     * `now`/`displayNow` split directly with both clocks pinned independently, rather than fighting the
     * real device clock for a deterministic "today".
     */
    @Suppress("ReturnCount")
    internal suspend fun computeSuggestion(
        plant: Plant,
        feedback: WateringFeedback?,
        now: Long = System.currentTimeMillis(),
        displayNow: Long = nowProvider()
    ): QuickWaterSuggestion? {
        val current = plant.wateringIntervalDays ?: return null
        val previousWatering = careLogRepository.getLastWateringBefore(plant.id, now) ?: return null
        val actual = CareSchedule.daysBetween(previousWatering.loggedAt, now)
        if (actual <= 0) return null
        val result = adaptWateringInterval(plant, feedback, actual, current, now, previousWatering.loggedAt) ?: return null
        val suggestion = result.intervalDays
        val effectiveSuggestion = effectiveIntervalForDisplay(plant, result.baseIntervalDays, suggestion, displayNow)
        val currentEffective = effectiveIntervalForDisplay(
            plant,
            currentAdaptiveBaseIntervalDays(plant, current),
            current,
            displayNow
        )
        persistAdaptiveState(plant, result, effectiveSuggestion == currentEffective, now)
        return if (effectiveSuggestion != currentEffective) {
            QuickWaterSuggestion(
                plant.id,
                plant.name,
                suggestion,
                effectiveSuggestion,
                result.baseIntervalDays,
                currentEffective
            )
        } else {
            null
        }
    }

    /**
     * Converts a base-space [suggestion] to effective (display) space via the same wrapper
     * [CareSchedule.effectiveWateringIntervalDaysForDisplay] the "Why this date?" sheet and
     * `PlantDetailViewModel.pendingWateringSuggestion` use (#620), so no call site can drift from
     * another. Treats [suggestion] as if it were the plant's new base — mirroring
     * `PlantDetailViewModel`'s identical `plant.copy(...)` pattern — so this is a display-only, one-way
     * conversion; the returned [QuickWaterSuggestion.suggestedInterval] (write path) is untouched.
     *
     * **[displayNow], not the observation's `loggedAt` (#716 review round 1, amending #654's original
     * rationale below).** Both call sites in [computeSuggestion] compare two numbers the user reads on
     * screen *today* — [effectiveSuggestion] and [currentEffective] — so both must be converted at the
     * same real "today", never at a backdated `now`. The #654-era rationale this replaces argued the
     * opposite (convert at the logged day, "or the comparison could be judged against the wrong
     * season") because at the time the other side of the comparison was `plant.wateringIntervalDays`,
     * itself not date-anchored to anything in particular; #716 replaced that stale literal with a
     * second live conversion, and once both sides are live, "the wrong season" is unambiguously
     * *today's*, not the logged day's — a backdated `now` here would silently re-introduce a
     * `now`-vs-`displayNow` mismatch of exactly the kind #716 exists to remove. Observation-space math
     * ([adaptWateringInterval]'s gap/season conversion, the [CareLog] write, [WateringAdjustment
     * .triggeredAt]) is unaffected and stays anchored to the logged day — only this display conversion
     * moved.
     */
    private suspend fun effectiveIntervalForDisplay(
        plant: Plant,
        suggestionBase: Double,
        suggestion: Int,
        displayNow: Long = nowProvider()
    ): Int =
        CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant.copy(wateringBaseIntervalDays = suggestionBase, wateringIntervalDays = suggestion),
            nowDate = displayNow.toLocalDate(),
            seasonalAmplitude = dataStore.seasonalAmplitudeOnce()
        ) ?: suggestion

    /**
     * Applies the multiplicative + confidence-weighted model (#568, technical ADR-0021) and
     * persists the resulting [Plant.wateringConfidence] immediately, independent of whether the
     * caller ends up surfacing/applying the returned suggestion. [feedback] may be `null` (#570) — a
     * silent gap-only observation, capped at [CareSchedule.NEUTRAL_OBSERVATION_GAIN].
     *
     * [dormancySpanning] is now computed *before* [maybeApplyHistoryBootstrap]'s early-return
     * (#699/#761, Codex review round 2 on #776, P1-3 — this replaces an earlier claim, made when
     * P1-a first shipped, that re-deriving it there would be pointless; see
     * [recordDormancyExcludedForBootstrap]'s doc for why that claim was incomplete and what changed).
     * Before evaluating the per-observation update, checks whether this WATER log is the one that
     * unlocks the #571 history bootstrap (either the plant's first-ever adaptive observation, or a
     * pending post-reset opportunity) — see [maybeApplyHistoryBootstrap]. When it fires, the bootstrap
     * already silently committed the new interval, so this returns `null` (suppressing the product
     * ADR-0006 suggestion dialog for this observation) rather than also running the incremental
     * per-step correction on top of a value the model just cold-started. **The bootstrap itself is
     * separately protected from dormancy-spanning gaps** — [maybeApplyHistoryBootstrap] ultimately
     * calls [CareSchedule.bootstrapBaseInterval] with [plant]'s dormancy months, which filters any
     * dormancy-overlapping gap out of the history it medians over (Codex review round 1 on #776,
     * P1-a) — so [dormancySpanning] being computed early is for transparency (a
     * [WateringAdjustmentTrigger.DORMANCY_EXCLUDED] row), not because the bootstrap needs this
     * function's own exclusion to protect it a second time.
     *
     * [now] defaults to the real wall-clock time but [computeSuggestion] threads through the caller's
     * chosen [loggedAt][quickWaterWithReason] instead when backdating (#654) — the same value that
     * decided the duplicate-day check and the [CareLog] write also decides the freeze-window check and
     * the [WateringAdjustment.triggeredAt] this records, so a backdated observation can't be evaluated
     * against "today" while claiming to have happened on an earlier day. [maybeApplyHistoryBootstrap] is
     * passed a separate, always-real-wall-clock `displayNow` (#679) — see its doc for why.
     *
     * [previousWateringAt] (#699/#761, product ADR-0044) is this observation's own chronological
     * predecessor — [computeSuggestion]'s already-fetched `previousWatering.loggedAt` — used to derive
     * two dormancy facts, both via [DormancyWindow]: whether the gap [previousWateringAt]→[now]
     * overlapped the dormancy window ([DormancyWindow.spansDormancy], excludes the base from learning
     * regardless of [feedback], same `frozen` exclusion mechanism a REPOT freeze uses but recorded
     * under the distinct [WateringAdjustmentTrigger.DORMANCY_EXCLUDED], **and** suppresses the
     * confidence transition entirely via [CareSchedule.computeAdaptiveInterval]'s
     * `suppressConfidenceTransition` parameter — Codex review round 1 on #776, P1-b, corrected the
     * original assumption that confidence was "unchanged by construction"; a short in-window gap can
     * satisfy `gapAgrees()` and would otherwise silently raise confidence for an observation that
     * tested nothing about the schedule), and whether [now] itself falls *outside* the window after
     * that overlap ([DormancyWindow.isDormant] on `now`'s month) — "did the plant just leave
     * dormancy?" — which additionally decrements confidence by exactly 1 (floored at 0, applied *after*
     * the guaranteed-unchanged confidence above) and writes a second, independent
     * [WateringAdjustmentTrigger.DORMANCY_EXIT] row, since the two are separate facts about the same
     * observation (the base didn't move; confidence moved for an unrelated reason) — but only when
     * [result]'s confidence is non-null (P1-1: a suppressed transition on a never-adapted plant leaves
     * confidence `null`, and there is nothing to decrement from `null`) **and** no dormancy cycle has
     * already recorded its exit ([alreadyRecordedDormancyExit], P1-2: a backdated watering inserted
     * between two already-existing ones must not re-decrement confidence for a cycle another,
     * chronologically-later observation already exited). `null` when there is no previous watering to
     * measure a gap from.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun adaptWateringInterval(
        plant: Plant,
        feedback: WateringFeedback?,
        actualIntervalDays: Int,
        currentInterval: Int,
        now: Long = System.currentTimeMillis(),
        previousWateringAt: Long? = null
    ): CareSchedule.AdaptiveInterval? {
        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val dormancySpanning = previousWateringAt != null && DormancyWindow.spansDormancy(
            plant.dormancyStartMonth,
            plant.dormancyEndMonth,
            previousWateringAt,
            now
        )
        if (maybeApplyHistoryBootstrap(plant, feedback, now)) {
            if (dormancySpanning) {
                recordDormancyExcludedForBootstrap(plant, now, currentBase)
            }
            return null
        }

        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        val frozenPostRepot = WateringLifecycleReset.isFrozen(plant.wateringFreezeUntil, now)
        val dormancyExited = dormancySpanning &&
            !DormancyWindow.isDormant(now.toLocalDate().monthValue, plant.dormancyStartMonth, plant.dormancyEndMonth)
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = feedback,
            observedIntervalDays = deseasonalizedObservedIntervalDays(
                actualIntervalDays,
                plant.pinIntervalToBase,
                atDate = now.toLocalDate()
            ),
            currentBaseIntervalDays = currentBase,
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback,
            frozen = frozenPostRepot || dormancySpanning,
            suppressConfidenceTransition = dormancySpanning
        )
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
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
        return applyDormancyExitDecrement(plant, now, currentBase, dormancyExited, result)
    }

    /**
     * Tail of [adaptWateringInterval] — extracted to keep that function under Detekt's `LongMethod`
     * threshold. See [adaptWateringInterval]'s own doc for the full P1-1/P1-2 rationale: `null` when
     * there is nothing to decrement from (P1-1), and a no-op when this exact dormancy cycle already
     * recorded its exit (P1-2, [alreadyRecordedDormancyExit]).
     */
    @Suppress("ReturnCount")
    private suspend fun applyDormancyExitDecrement(
        plant: Plant,
        now: Long,
        currentBase: Double,
        dormancyExited: Boolean,
        result: CareSchedule.AdaptiveInterval
    ): CareSchedule.AdaptiveInterval {
        if (!dormancyExited) return result
        val currentResultConfidence = result.confidence ?: return result
        if (alreadyRecordedDormancyExit(plant, now)) return result
        val adjustedConfidence = (currentResultConfidence - 1).coerceAtLeast(0)
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.DORMANCY_EXIT,
                beforeIntervalDays = currentBase.roundToInt(),
                afterIntervalDays = currentBase.roundToInt()
            )
        )
        return result.copy(confidence = adjustedConfidence)
    }

    /**
     * #699/#761 (product ADR-0044 — Codex review round 2 on #776, P1-3): before this fix,
     * [maybeApplyHistoryBootstrap] firing returned early *before* [dormancySpanning] was even
     * computed, so a triggering observation whose own gap spanned dormancy got **no**
     * [WateringAdjustmentTrigger.DORMANCY_EXCLUDED] row at all when it happened to also be the
     * observation that unlocked a cold-start bootstrap — only [WateringAdjustmentTrigger
     * .HISTORY_BOOTSTRAP] was written, silently omitting the dormancy provenance "Why this date?"
     * shows for every other dormancy-spanning observation. [dormancySpanning] is now computed
     * *before* the bootstrap early-return so this row can still be written for transparency — with
     * `before == after` (a no-op observation, mirroring every other "nothing moved but the model
     * still considered it" row), since the correctness-critical half of P1-3 is already handled:
     * [maybeApplyHistoryBootstrap] ultimately calls [CareSchedule.bootstrapBaseInterval] with
     * [Plant.dormancyStartMonth]/[Plant.dormancyEndMonth] (Codex review round 1, P1-a), which
     * already filters this exact gap — and every other dormancy-spanning gap in the eligible
     * history — out of the wholesale median/gapCount the bootstrap computes. There is nothing left
     * for this per-observation exclusion to additionally protect.
     *
     * **Deliberately no [WateringAdjustmentTrigger.DORMANCY_EXIT] row and no extra confidence
     * decrement when bootstrap wins — "bootstrap wins, no decrement" is the explicit, tested
     * decision, not an accident of statement order.** The bootstrapped confidence
     * ([WateringLifecycleReset.maybeBootstrap]'s `result.confidence`) is a wholesale re-derivation —
     * `gapCount / 3`, capped at 5 — not an incremental step off the plant's prior confidence the way
     * every other per-observation transition is; there is no principled "-1 for having just left
     * dormancy" to layer on top of a value that was not arrived at by incrementing/decrementing in
     * the first place. Writing a DORMANCY_EXIT row here would also misrepresent the observation as
     * having gone through the normal per-step exit evaluation when the bootstrap superseded it
     * entirely — the same reasoning [WateringAdjustmentTrigger.FROZEN_POST_REPOT] already documents
     * for not conflating an automatic exclusion with a declined attribution.
     */
    private suspend fun recordDormancyExcludedForBootstrap(plant: Plant, now: Long, currentBase: Double) {
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.DORMANCY_EXCLUDED,
                beforeIntervalDays = currentBase.roundToInt(),
                afterIntervalDays = currentBase.roundToInt()
            )
        )
    }

    /**
     * #699/#761 (product ADR-0044 — Codex review round 2 on #776, P1-2): whether a
     * [WateringAdjustmentTrigger.DORMANCY_EXIT] has already been recorded for the same dormancy
     * cycle [now] belongs to, so a backdated watering inserted *between* two already-existing
     * waterings can't re-decrement confidence for a cycle another observation already exited.
     * Two [WateringAdjustment] rows share a cycle when [DormancyWindow.spansDormancy] finds **no**
     * dormant month between them — i.e. they fall in the same uninterrupted wakeful stretch,
     * reusing the exact predicate that already defines a dormancy cycle's boundaries rather than
     * inventing separate "cycle identity" bookkeeping. A row from a genuinely different (earlier or
     * later) cycle has at least one full dormancy window between it and [now], so it correctly does
     * not suppress this one.
     */
    private suspend fun alreadyRecordedDormancyExit(plant: Plant, now: Long): Boolean =
        wateringAdjustmentRepository.getByTrigger(plant.id, WateringAdjustmentTrigger.DORMANCY_EXIT).any {
            !DormancyWindow.spansDormancy(
                plant.dormancyStartMonth,
                plant.dormancyEndMonth,
                minOf(it.triggeredAt, now),
                maxOf(it.triggeredAt, now)
            )
        }

    private suspend fun persistAdaptiveState(
        plant: Plant,
        result: CareSchedule.AdaptiveInterval,
        persistBase: Boolean,
        now: Long
    ) {
        val seasonAdjustable = !plant.pinIntervalToBase && dataStore.seasonalAmplitudeOnce() != 0.0
        val newBase = result.baseIntervalDays.takeIf { persistBase && seasonAdjustable }
            ?: plant.wateringBaseIntervalDays
        if (result.confidence != plant.wateringConfidence || newBase != plant.wateringBaseIntervalDays) {
            plantRepository.updatePlant(
                plant.copy(wateringConfidence = result.confidence, wateringBaseIntervalDays = newBase, updatedAt = now)
            )
        }
    }

    /**
     * The #571 REPOT-triggered lifecycle reset, reached from [quickLog]'s bulk-action REPOT path
     * (`BulkActionBar`) — extracted out of [quickLog] to stay under Detekt's
     * `CyclomaticComplexMethod` threshold.
     */
    private suspend fun maybeApplyRepotReset(plant: Plant, careType: CareType, now: Long) {
        if (careType == CareType.REPOT) {
            WateringLifecycleReset.applyRepotReset(plant, now, plantRepository, wateringAdjustmentRepository)
        }
    }

    /**
     * The #571 cold-start bootstrap opportunity, evaluated on every WATER-log adaptive observation:
     * the plant's first-ever adaptive observation ([Plant.wateringConfidence] == `null`, using its
     * whole history), or a pending post-reset opportunity ([Plant.wateringResetAt] != `null`, using
     * only history at/after the freeze boundary). Returns `false` (no-op) when neither applies, or
     * when [WateringLifecycleReset.maybeBootstrap] doesn't find enough gaps yet.
     *
     * [feedback] is threaded through to [WateringLifecycleReset.BootstrapRequest] so a bootstrap
     * triggered by a late "Soil was still moist" observation ([WateringFeedback.TOO_SOON]) can't
     * undercut ADR-0033's "a late watering never shortens the interval" guarantee — see that
     * function's doc for why the median-of-history estimate needs this floor (#649 follow-up).
     *
     * [now] may be a backdated `loggedAt` (#654); [WateringLifecycleReset.maybeBootstrap] additionally
     * takes a separate, always-real-wall-clock `displayNow` (#679, `System.currentTimeMillis()` here,
     * not [nowProvider] — the bootstrap's `wateringIntervalDays` write must reflect *today's* season
     * regardless of how [nowProvider] is pinned for a backdated observation's own gap math).
     */
    private suspend fun maybeApplyHistoryBootstrap(plant: Plant, feedback: WateringFeedback?, now: Long): Boolean {
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
            now,
            displayNow = System.currentTimeMillis()
        )
    }

    /**
     * The season function [WateringLifecycleReset.maybeBootstrap]/[CareSchedule.bootstrapBaseInterval]
     * de-seasonalize each historical gap with — `{ 1.0 }` (a no-op) when [Plant.pinIntervalToBase] is
     * set or amplitude is Off, mirroring every other de-seasonalization call site
     * in this file ([deseasonalizedObservedIntervalDays]/[currentAdaptiveBaseIntervalDays]).
     */
    @Suppress("ReturnCount")
    private suspend fun seasonFnFor(plant: Plant): (LocalDate) -> Double {
        if (plant.pinIntervalToBase) return { 1.0 }
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return { 1.0 }
        val hemisphere = SeasonalWatering.currentHemisphere()
        return { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
    }

    private fun adjustmentTriggerFor(
        feedback: WateringFeedback?,
        excludedFromBaseLearning: Boolean,
        frozenPostRepot: Boolean = false,
        dormancySpanning: Boolean = false
    ): WateringAdjustmentTrigger = when {
        // Dormancy takes priority over a REPOT freeze when (rarely) both could apply — it is the more
        // specific, user-declared cause, and conflating the two would misrepresent why nothing moved.
        dormancySpanning -> WateringAdjustmentTrigger.DORMANCY_EXCLUDED
        frozenPostRepot -> WateringAdjustmentTrigger.FROZEN_POST_REPOT
        excludedFromBaseLearning -> WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED
        feedback == WateringFeedback.TOO_SOON -> WateringAdjustmentTrigger.WATER_TOO_SOON
        feedback == WateringFeedback.TOO_LATE -> WateringAdjustmentTrigger.WATER_TOO_LATE
        feedback == WateringFeedback.JUST_RIGHT -> WateringAdjustmentTrigger.WATER_JUST_RIGHT
        else -> WateringAdjustmentTrigger.WATER_NEUTRAL
    }

    /**
     * "Interaction with Part 1" (#569): `observedBase = observedGap / season(dateOfGap)`, so a
     * seasonal correction isn't baked into [Plant.wateringConfidence] as a permanent thirst change.
     * A no-op when amplitude is Off or [pinIntervalToBase] is set — [CareSchedule]'s due-date
     * math never applies the seasonal curve for a pinned plant, so its observed gaps are already
     * flat and must not be seasonally corrected.
     *
     * [atDate] defaults to [nowProvider]'s real wall-clock date, but [adaptWateringInterval] passes
     * its own `now` (possibly a backdated `loggedAt`, #654) explicitly, so the observed gap is
     * de-seasonalized using the day the watering actually happened, not the day the app happens to
     * be evaluating it.
     */
    @Suppress("ReturnCount")
    private suspend fun deseasonalizedObservedIntervalDays(
        actualIntervalDays: Int,
        pinIntervalToBase: Boolean,
        atDate: LocalDate = nowProvider().toLocalDate()
    ): Int {
        if (pinIntervalToBase) return actualIntervalDays
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return actualIntervalDays
        return SeasonalWatering.deseasonalizeToDays(
            actualIntervalDays,
            atDate,
            amplitude,
            SeasonalWatering.currentHemisphere()
        )
    }

    /**
     * The watering-model input for `currentBaseIntervalDays` (#572, amending technical ADR-0021):
     * season-neutral, reading [Plant.wateringBaseIntervalDays] instead of the raw (possibly seasonally
     * stale) [configuredIntervalDays] whenever amplitude isn't Off and the plant isn't pinned.
     * Prior to this fix every call site fed the model a value that only ever changed on a manual
     * edit, silently diverging from what [CareSchedule.computeStatus] actually used for the due date.
     */
    @Suppress("ReturnCount")
    private suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Double {
        if (plant.pinIntervalToBase) return configuredIntervalDays.toDouble()
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return configuredIntervalDays.toDouble()
        return plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()
    }

    /**
     * Clears [Plant.wateringDueDateOverride] if [plantId] currently has one active, and returns the
     * freshly-fetched plant (cleared or not) so callers that go on to call [computeSuggestion] can
     * build any follow-up [PlantRepository.updatePlant] call off consistent, post-clear state rather
     * than the stale [Plant] snapshot they were originally passed (#614, same bug class as #612) —
     * without this, [adaptWateringInterval]'s own `.copy()` would silently resurrect the override it
     * just cleared. Returns `null` only if [plantId] no longer exists (a pre-existing race, not
     * introduced here); callers fall back to their own stale snapshot in that case.
     */
    @Suppress("ReturnCount")
    private suspend fun clearWateringOverrideIfActive(plantId: Long): Plant? {
        val p = plantRepository.getPlantById(plantId).first() ?: return null
        if (p.wateringDueDateOverride == null) return p
        val cleared = p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
        plantRepository.updatePlant(cleared)
        return cleared
    }

    companion object {
        private const val RECENT_WATERINGS_WINDOW = 3
    }
}
